package org.learningfromexperience.eclipse.agentdebug.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.agentdebug.report.ReportEvent;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepKind;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepStatus;
import org.learningfromexperience.agentdebug.report.ReportRecorder;

/**
 * Unit tests (JUnit 5) for turning MCP tool calls into session report events (OPEN-30 step R1). The tool replies
 * below have the shape the Eclipse bridge actually returns. No Eclipse and no report writer needed: events are
 * captured in memory. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class SessionReportTest {

    /** Keeps the events instead of writing a report. */
    private static final class Captured implements ReportRecorder {
        final List<ReportEvent> events = new ArrayList<>();
        boolean closed;
        boolean failOnRecord;

        @Override public void record(ReportEvent event) {
            if (failOnRecord) throw new IllegalStateException("recorder broken");
            events.add(event);
        }
        @Override public void close(Duration timeout) { closed = true; }
        @Override public boolean active() { return true; }

        List<ReportEvent.StepRecorded> steps() {
            return events.stream().filter(e -> e instanceof ReportEvent.StepRecorded)
                    .map(e -> (ReportEvent.StepRecorded) e).toList();
        }
    }

    private static final String BEGIN = "{\"status\":\"success\",\"investigationId\":\"inv-1\",\"mode\":\"NORMAL\"}";
    private static final String STOP = "{\"status\":\"success\",\"stopped\":true,\"reason\":\"BREAKPOINT\","
            + "\"launchName\":\"Orders\",\"threadIndex\":3,\"threadName\":\"http-nio-exec-1\","
            + "\"location\":{\"index\":0,\"className\":\"org.example.OrderService\",\"methodName\":\"quote\",\"lineNumber\":24},"
            + "\"frames\":[{\"index\":0,\"className\":\"org.example.OrderService\",\"methodName\":\"quote\",\"lineNumber\":24},"
            + "{\"index\":1,\"className\":\"org.example.OrderController\",\"methodName\":\"quote\",\"lineNumber\":31}],"
            + "\"totalFrames\":60,\"locals\":[{\"name\":\"percent\",\"type\":\"int\",\"value\":\"10\"},"
            + "{\"name\":\"rate\",\"type\":\"int\",\"value\":\"0\"},{\"name\":\"apiToken\",\"type\":\"java.lang.String\",\"value\":\"s3cr3t\"}]}";

    private final Captured captured = new Captured();
    private final SessionReport report = new SessionReport(captured);

    private void call(String tool, Map<String, Object> args, boolean failed, String reply) {
        report.toolCalled("claude-code", tool, args, failed, reply, 12);
    }

    @Test
    @DisplayName("SR-U1 reporting is off unless AGENTDEBUG_REPORT names a writer")
    void srU1() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        SessionReport off = SessionReport.fromEnvironment(Map.of(), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertFalse(off.active());
        off.toolCalled("c", "begin_debug_investigation", Map.of(), false, BEGIN, 1);   // ignored, no error
        assertFalse(SessionReport.fromEnvironment(Map.of("AGENTDEBUG_REPORT", "off"), System.err).active());
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("SR-U2 a whole investigation becomes start, steps with titles and kinds, and a finish with cleanup")
    void srU2() {
        call("begin_debug_investigation", Map.of("mode", "NORMAL"), false, BEGIN);
        call("set_breakpoint", Map.of("typeName", "org.example.OrderService", "lineNumber", 24), false,
                "{\"attachedToInvestigation\":\"inv-1\",\"status\":\"success\",\"created\":true}");
        call("start_java_application", Map.of("projectName", "orders", "mainType", "org.example.OrdersApplication"), false,
                "{\"attachedToInvestigation\":\"inv-1\",\"status\":\"success\",\"launchName\":\"Orders\"}");
        call("wait_for_stop", Map.of("launchName", "Orders", "timeoutSeconds", 20), false, STOP);
        call("control_debug_session", Map.of("launchName", "Orders", "threadIndex", 3, "action", "RESUME"), false,
                "{\"status\":\"success\"}");
        call("finish_debug_investigation", Map.of("investigationId", "inv-1"), false,
                "{\"status\":\"success\",\"investigationId\":\"inv-1\",\"mode\":\"NORMAL\",\"action\":\"CLEANUP\","
                        + "\"launchName\":\"Orders\",\"ownedSession\":true,\"processesEnded\":true,\"completed\":true}");

        var started = (ReportEvent.InvestigationStarted) captured.events.get(0);
        assertEquals("inv-1", started.investigationId());
        assertEquals("NORMAL", started.mode());
        assertEquals("claude-code", started.client());

        var steps = captured.steps();
        assertEquals(4, steps.size());
        assertEquals("set_breakpoint OrderService:24", steps.get(0).title());
        assertEquals(StepKind.BREAKPOINT, steps.get(0).kind());
        assertEquals("start_java_application OrdersApplication", steps.get(1).title());
        assertEquals(StepKind.LAUNCH, steps.get(1).kind());
        assertEquals("wait_for_stop: BREAKPOINT at OrderService.quote:24", steps.get(2).title());
        assertEquals(StepKind.STOP, steps.get(2).kind());
        assertEquals("control_debug_session RESUME thread 3", steps.get(3).title());
        assertTrue(steps.stream().allMatch(s -> s.investigationId().equals("inv-1") && s.status() == StepStatus.OK));

        var finished = (ReportEvent.InvestigationFinished) captured.events.get(captured.events.size() - 1);
        assertEquals(ReportEvent.Outcome.FINISHED, finished.outcome());
        assertTrue(finished.cleanup().contains("Removed own breakpoint OrderService:24"), finished.cleanup()::toString);
        assertTrue(finished.cleanup().contains("JVM ended: true"), finished.cleanup()::toString);
    }

    @Test
    @DisplayName("SR-U3 a stop shows thread, frames and the variables as a table, with secrets masked")
    void srU3() {
        call("begin_debug_investigation", Map.of(), false, BEGIN);
        call("wait_for_stop", Map.of("launchName", "Orders", "timeoutSeconds", 20), false, STOP);
        List<String> lines = captured.steps().get(0).details();
        String text = String.join("\n", lines);
        assertTrue(text.contains("Thread: http-nio-exec-1 (index 3)"), text);
        assertTrue(text.contains("Frames: OrderService.quote:24  <  OrderController.quote:31  <  (58 more)"), text);
        assertTrue(lines.contains("Variable    Type      Value"), text);
        assertTrue(lines.contains("rate        int       0"), text);
        assertTrue(lines.contains("apiToken    String    ******"), text);
        assertFalse(text.contains("s3cr3t"), "secret leaked into the report");
    }

    @Test
    @DisplayName("SR-U4 with two open investigations, a call is attributed by its launch; calls outside any investigation are not recorded")
    void srU4() {
        call("list_java_applications", Map.of(), false, "{\"status\":\"success\",\"applications\":[]}");
        assertTrue(captured.events.isEmpty(), "a call before any investigation must not be recorded");

        call("begin_debug_investigation", Map.of(), false, BEGIN);
        call("begin_debug_investigation", Map.of(), false, BEGIN.replace("inv-1", "inv-2"));
        call("start_java_application", Map.of("projectName", "p", "mainType", "a.Orders", "investigationId", "inv-1"), false,
                "{\"status\":\"success\",\"launchName\":\"Orders\"}");
        call("start_java_application", Map.of("projectName", "p", "mainType", "a.Inventory", "investigationId", "inv-2"), false,
                "{\"status\":\"success\",\"launchName\":\"Inventory\"}");
        call("wait_for_stop", Map.of("launchName", "Inventory", "timeoutSeconds", 5), false,
                "{\"status\":\"success\",\"stopped\":false,\"reason\":\"TIMEOUT\"}");
        var last = captured.steps().get(captured.steps().size() - 1);
        assertEquals("inv-2", last.investigationId());
        assertEquals("wait_for_stop: no stop (TIMEOUT)", last.title());
    }

    @Test
    @DisplayName("SR-U5 a failed tool call is a FAILED step with the error message")
    void srU5() {
        call("begin_debug_investigation", Map.of(), false, BEGIN);
        call("evaluate_expression", Map.of("launchName", "Orders", "expression", "percent / 100.0"), true,
                "Explicit consent required for evaluation");
        var step = captured.steps().get(0);
        assertEquals(StepStatus.FAILED, step.status());
        assertEquals(StepKind.INSPECT, step.kind());
        assertTrue(step.details().contains("Error: Explicit consent required for evaluation"), step.details()::toString);
    }

    @Test
    @DisplayName("SR-U6 an investigation the MCP server had to finish is recorded as ended without finish; then the report closes")
    void srU6() {
        call("begin_debug_investigation", Map.of(), false, BEGIN);
        call("set_breakpoint", Map.of("typeName", "a.Calculator", "lineNumber", 10), false, "{\"status\":\"success\"}");
        report.endedWithoutFinish("inv-1", "agent closed the MCP connection");
        report.endedWithoutFinish("inv-1", "again");   // already ended: ignored
        report.close();
        var finished = (ReportEvent.InvestigationFinished) captured.events.get(captured.events.size() - 1);
        assertEquals(ReportEvent.Outcome.ENDED_WITHOUT_FINISH, finished.outcome());
        assertEquals("Finished by the MCP server: agent closed the MCP connection", finished.cleanup().get(0));
        assertTrue(finished.cleanup().contains("Removed own breakpoint Calculator:10"), finished.cleanup()::toString);
        assertEquals(1, captured.events.stream().filter(e -> e instanceof ReportEvent.InvestigationFinished).count());
        assertTrue(captured.closed);
    }

    @Test
    @DisplayName("SR-U7 a broken recorder or an unreadable reply never makes a tool call fail")
    void srU7() {
        call("begin_debug_investigation", Map.of(), false, BEGIN);
        call("get_process_output", Map.of("launchName", "Orders"), false, "not json at all {");   // must not throw
        captured.failOnRecord = true;
        call("get_debug_status", Map.of("launchName", "Orders"), false, "{\"status\":\"success\"}");   // must not throw
        report.endedWithoutFinish("inv-1", "shutdown");                                              // must not throw
        report.close();
        assertEquals("Result: not json at all {", captured.steps().get(0).details().get(captured.steps().get(0).details().size() - 1));
    }

    @Test
    @DisplayName("SR-U9 replies are shortened to what a reader needs: launch label, output tail, top frames, no repeated arguments")
    void srU9() {
        assertEquals("OrdersApplication", SessionReport.launchLabel("Agentic Debugger - spring-boot-debugging - OrdersApplication"));
        assertEquals("My saved config", SessionReport.launchLabel("My saved config"));

        call("begin_debug_investigation", Map.of(), false, BEGIN);
        String launch = "Agentic Debugger - spring-boot-debugging - OrdersApplication";
        StringBuilder console = new StringBuilder();
        for (int i = 1; i <= 20; i++) console.append("line ").append(i).append("\\n");
        call("get_process_output", Map.of("launchName", launch), false, "{\"status\":\"success\",\"processes\":[{\"label\":"
                + "\"javaw.exe [pid: 2536]\",\"stdout\":\"\",\"stderr\":\"\",\"console\":\"" + console + "\"}]}");
        List<String> output = captured.steps().get(0).details();
        assertTrue(output.contains("launchName: OrdersApplication"), output::toString);
        assertTrue(output.contains("Process [pid: 2536]"), output::toString);
        assertTrue(output.contains("  ... last 8 of 20 lines"), output::toString);
        assertTrue(output.contains("  | line 20") && !output.contains("  | line 12"), output::toString);

        StringBuilder frames = new StringBuilder();
        for (int i = 0; i < 25; i++) frames.append(i == 0 ? "" : ",").append("{\"index\":").append(i)
                .append(",\"className\":\"a.C").append(i).append("\",\"methodName\":\"m\",\"lineNumber\":").append(i).append('}');
        call("get_stack_frames", Map.of("launchName", launch, "threadIndex", 4), false,
                "{\"status\":\"success\",\"frames\":[" + frames + "]}");
        List<String> stack = captured.steps().get(1).details();
        assertTrue(stack.contains("  C9.m:9") && !stack.contains("  C10.m:10"), stack::toString);
        assertTrue(stack.contains("  ... (15 more frames)"), stack::toString);

        call("set_breakpoint", Map.of("typeName", "a.OrderService", "lineNumber", 24), false,
                "{\"status\":\"success\",\"typeName\":\"a.OrderService\",\"lineNumber\":24,\"created\":true,\"owner\":\"agent\"}");
        List<String> bp = captured.steps().get(2).details();
        assertEquals("Result: created=true, owner=agent", bp.get(bp.size() - 1), "arguments repeated in the result");

        call("get_frame_variables", Map.of("launchName", launch, "threadIndex", 4, "frameIndex", 0), false,
                "{\"status\":\"success\",\"variables\":[{\"name\":\"no method return value\",\"type\":null,\"value\":null},"
                        + "{\"name\":\"rate\",\"type\":\"int\",\"value\":\"0\"}]}");
        String vars = String.join("\n", captured.steps().get(3).details());
        assertFalse(vars.contains("no method return value"), vars);
        assertTrue(vars.contains("rate        int     0"), vars);

        call("wait_for_debug_pause", Map.of("launchName", launch, "timeoutSeconds", 30), false,
                "{\"status\":\"success\",\"threads\":[{\"index\":0,\"name\":\"main\",\"suspended\":false},"
                        + "{\"index\":4,\"name\":\"http-exec-1\",\"suspended\":true}]}");
        assertEquals("wait_for_debug_pause: suspended http-exec-1 (thread 4)", captured.steps().get(4).title());
    }

    @Test
    @DisplayName("SR-U8 the small JSON reader handles the bridge's replies and rejects invalid text")
    void srU8() {
        var stop = MiniJson.obj(MiniJson.parse(STOP));
        assertEquals("3", MiniJson.str(stop.get("threadIndex")));
        assertEquals(Boolean.TRUE, stop.get("stopped"));
        assertEquals(3, MiniJson.arr(stop.get("locals")).size());
        assertEquals("a\"b\né", MiniJson.parse("\"a\\\"b\\n\\u00e9\""));
        assertEquals(null, MiniJson.parse("{\"a\":"));
        assertEquals(null, MiniJson.parse("{} trailing"));
        assertEquals("OrderService", SessionReport.simple("org.example.OrderService"));
        assertEquals("List<java.lang.String>", SessionReport.simple("java.util.List<java.lang.String>"));
    }
}
