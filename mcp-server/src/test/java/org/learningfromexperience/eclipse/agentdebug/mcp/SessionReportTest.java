package org.learningfromexperience.eclipse.agentdebug.mcp;

import org.learningfromexperience.agentdebug.report.format.Json;
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
    @DisplayName("SR-U1 reporting is on by default and off with AGENTDEBUG_REPORT=off")
    void srU1() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        SessionReport off = SessionReport.fromEnvironment(Map.of("AGENTDEBUG_REPORT", "off"),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertFalse(off.active());
        off.toolCalled("c", "begin_debug_investigation", Map.of(), false, BEGIN, 1);   // ignored, no error
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        assertTrue(off.reportInfo(null).contains("\"enabled\":false"), off.reportInfo(null));

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        SessionReport byDefault = SessionReport.fromEnvironment(Map.of(), new PrintStream(log, true, StandardCharsets.UTF_8));
        try {
            assertTrue(byDefault.active(), "reports must be on without any setting: " + log);
            assertTrue(log.toString(StandardCharsets.UTF_8).contains("debug-reports folder of each launched Eclipse project"),
                    log.toString(StandardCharsets.UTF_8));
        } finally {
            byDefault.close();
        }
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
        assertEquals("Set breakpoint: OrderService, line 24", steps.get(0).title());
        assertEquals(StepKind.BREAKPOINT, steps.get(0).kind());
        assertEquals("Started application in debug mode: OrdersApplication", steps.get(1).title());
        assertEquals(StepKind.LAUNCH, steps.get(1).kind());
        assertEquals("Stopped at breakpoint: OrderService.quote, line 24", steps.get(2).title());
        assertEquals(StepKind.STOP, steps.get(2).kind());
        assertEquals("Resumed execution", steps.get(3).title());
        List<String> first = steps.get(0).details();
        assertEquals("Tool: set_breakpoint", first.get(first.indexOf(ReportEvent.TECHNICAL_DETAILS) + 1),
                "the technical part starts with the tool name");
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
        assertEquals("Waited for a stop: none (TIMEOUT)", last.title());
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
        // What a reader needs comes first; tool, arguments and the raw result follow the technical separator.
        assertEquals("New breakpoint owned by the agent; it is removed again at cleanup.", bp.get(0));
        assertTrue(bp.indexOf(ReportEvent.TECHNICAL_DETAILS) < bp.indexOf("Tool: set_breakpoint"), bp::toString);

        call("get_frame_variables", Map.of("launchName", launch, "threadIndex", 4, "frameIndex", 0), false,
                "{\"status\":\"success\",\"variables\":[{\"name\":\"no method return value\",\"type\":null,\"value\":null},"
                        + "{\"name\":\"rate\",\"type\":\"int\",\"value\":\"0\"}]}");
        String vars = String.join("\n", captured.steps().get(3).details());
        assertFalse(vars.contains("no method return value"), vars);
        assertTrue(vars.contains("rate        int     0"), vars);

        call("wait_for_debug_pause", Map.of("launchName", launch, "timeoutSeconds", 30), false,
                "{\"status\":\"success\",\"threads\":[{\"index\":0,\"name\":\"main\",\"suspended\":false},"
                        + "{\"index\":4,\"name\":\"http-exec-1\",\"suspended\":true}]}");
        assertEquals("Thread suspended: http-exec-1 (thread 4)", captured.steps().get(4).title());
    }

    @Test
    @DisplayName("SR-U10 HTTP steps read as request and response, failed steps end with their error, JVM-internal frames are left out")
    void srU10() {
        call("begin_debug_investigation", Map.of(), false, BEGIN);
        call("start_fixture_http_request", Map.of("launchName", "Orders", "scenario", "discount"), false,
                "{\"requestId\":\"r1\",\"status\":\"PENDING\",\"method\":\"POST\",\"url\":\"http://127.0.0.1:61956/orders/quote\","
                        + "\"scenario\":\"discount\",\"pid\":2536,\"bodySent\":\"{}\"}");
        call("get_fixture_http_request", Map.of("requestId", "r1"), false,
                "{\"requestId\":\"r1\",\"status\":\"COMPLETE\",\"httpStatus\":200,\"body\":\"{\\\"total\\\":120.00}\",\"fixturePid\":2536}");
        call("start_fixture_http_request", Map.of("launchName", "Orders", "scenario", "discount"), true,
                "Orders fixture not ready; retry when Tomcat startup is visible");
        call("wait_for_stop", Map.of("launchName", "Orders"), false, STOP.replace(
                "{\"index\":1,\"className\":\"org.example.OrderController\"",
                "{\"index\":1,\"className\":\"0x000000008d24c000\",\"methodName\":\"invokeVirtual\",\"lineNumber\":-1},"
                        + "{\"index\":2,\"className\":\"org.example.OrderController\""));
        var steps = captured.steps();
        assertEquals("Sent HTTP request: POST /orders/quote (discount scenario)", steps.get(0).title());
        assertEquals("Received HTTP response: 200", steps.get(1).title());
        assertTrue(steps.get(1).details().contains("Body: {\"total\":120.00}"), steps.get(1).details()::toString);
        assertEquals("HTTP request not sent (discount scenario)", steps.get(2).title());
        List<String> failed = steps.get(2).details();
        assertEquals("Error: Orders fixture not ready; retry when Tomcat startup is visible", failed.get(0));
        assertFalse(failed.contains(""), "no empty line in a failed step: " + failed);
        String frames = steps.get(3).details().stream().filter(l -> l.startsWith("Frames:")).findFirst().orElse("");
        assertEquals("Frames: OrderService.quote:24  <  OrderController.quote:31  <  (58 more)", frames);
    }

    @Test
    @DisplayName("SR-U11 the request opens the report, the report file is named after the application, printed and returned by get_investigation_report")
    void srU11(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Captured events = new Captured();
        SessionReport session = new SessionReport(events, folder, null, new PrintStream(log, true, StandardCharsets.UTF_8));
        assertTrue(session.reportInfo(null).contains("No finished investigation"), session.reportInfo(null));

        session.toolCalled("claude-code", "begin_debug_investigation", Map.of("request", "Why is the quote total 120 and not 108?"),
                false, BEGIN, 5);
        var started = (ReportEvent.InvestigationStarted) events.events.get(0);
        assertEquals("Why is the quote total 120 and not 108?", started.request());
        assertFalse(started.requestExact(), "a request passed by the agent is not the exact prompt");
        session.toolCalled("claude-code", "start_java_application", Map.of("projectName", "orders", "mainType", "org.example.OrdersApplication"),
                false, "{\"status\":\"success\",\"launchName\":\"Orders\",\"projectLocation\":"
                        + quoted(folder.resolve("orders-project").toString()) + "}", 900);
        assertTrue(session.reportInfo("inv-1").contains("call finish_debug_investigation first"), session.reportInfo("inv-1"));
        session.toolCalled("claude-code", "finish_debug_investigation", Map.of("investigationId", "inv-1",
                        "summary", "percent / 100 is integer division, so rate is 0."), false,
                "{\"status\":\"success\",\"action\":\"CLEANUP\",\"processesEnded\":true}", 3);

        var finished = (ReportEvent.InvestigationFinished) events.events.get(events.events.size() - 1);
        assertEquals("percent / 100 is integer division, so rate is 0.", finished.conclusion());
        String name = finished.reportFile().getFileName().toString();
        assertTrue(name.matches("OrdersApplication_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.html"), name);
        // AGENTDEBUG_REPORT_DIR (here: folder) wins over the project's location.
        assertEquals(folder.toAbsolutePath().normalize(), finished.reportFile().getParent());
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("Debugging session report: " + finished.reportFile()),
                "full path not printed: " + log);
        String info = session.reportInfo(null);
        assertTrue(info.contains("\"reportFile\":") && info.contains(name) && info.contains("\"enabled\":true"), info);
        assertTrue(new SessionReport(ReportRecorder.off()).reportInfo(null).contains("\"enabled\":false"), "reporting off must say so");
    }

    @Test
    @DisplayName("SR-U13 without a report folder setting, the report goes to debug-reports in the launched Eclipse project, else to the home folder")
    void srU13(@org.junit.jupiter.api.io.TempDir java.nio.file.Path project) {
        Captured events = new Captured();
        SessionReport session = new SessionReport(events, null, null, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        session.toolCalled("claude-code", "begin_debug_investigation", Map.of(), false, BEGIN, 5);
        session.toolCalled("claude-code", "start_java_application", Map.of("projectName", "orders", "mainType", "org.example.OrdersApplication"),
                false, "{\"status\":\"success\",\"launchName\":\"Orders\",\"projectLocation\":" + quoted(project.toString()) + "}", 900);
        session.toolCalled("claude-code", "finish_debug_investigation", Map.of("investigationId", "inv-1"), false,
                "{\"status\":\"success\",\"action\":\"CLEANUP\"}", 3);
        List<String> launchDetails = events.steps().get(0).details();
        assertEquals("Started a new JVM in debug mode with the existing launch configuration.", launchDetails.get(0));
        assertTrue(launchDetails.contains("Project folder: " + project), launchDetails::toString);
        assertFalse(launchDetails.stream().anyMatch(l -> l.startsWith("Result:") && l.contains("projectLocation")),
                launchDetails.toString());
        var inProject = (ReportEvent.InvestigationFinished) events.events.get(events.events.size() - 1);
        assertEquals(project.resolve("debug-reports").toAbsolutePath().normalize(), inProject.reportFile().getParent());
        assertTrue(session.reportInfo("inv-1").contains("\"reportFolder\":" + quoted(inProject.reportFile().getParent().toString())),
                session.reportInfo("inv-1"));

        // Nothing launched (or a project folder that does not exist): the home folder.
        session.toolCalled("claude-code", "begin_debug_investigation", Map.of(), false, BEGIN, 5);
        session.toolCalled("claude-code", "start_java_application", Map.of("projectName", "gone", "mainType", "org.example.Gone"),
                false, "{\"status\":\"success\",\"launchName\":\"Gone\",\"projectLocation\":"
                        + quoted(project.resolve("missing").toString()) + "}", 900);
        session.toolCalled("claude-code", "finish_debug_investigation", Map.of("investigationId", "inv-1"), false,
                "{\"status\":\"success\",\"action\":\"CLEANUP\"}", 3);
        var atHome = (ReportEvent.InvestigationFinished) events.events.get(events.events.size() - 1);
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "reports"),
                atHome.reportFile().getParent());
    }

    @Test
    @DisplayName("SR-U14 live view only: a running step is announced before the call, the report recorder stays off")
    void srU14() {
        Captured live = new Captured();
        SessionReport session = new SessionReport(ReportRecorder.off(), live, null, null,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertTrue(session.active(), "the live view alone makes the session active");
        session.toolStarting("eclipse_ping", Map.of());                         // outside an investigation: ignored
        session.toolCalled("claude-code", "begin_debug_investigation", Map.of(), false, BEGIN, 5);
        session.toolStarting("wait_for_stop", Map.of("investigationId", "inv-1", "launchName", "Orders"));
        session.toolCalled("claude-code", "wait_for_stop", Map.of("investigationId", "inv-1", "launchName", "Orders"), false, STOP, 40);
        assertTrue(live.events.get(0) instanceof ReportEvent.InvestigationStarted, live.events::toString);
        var running = (ReportEvent.StepStarted) live.events.get(1);
        assertEquals("Waiting for the program to stop", running.title());
        assertEquals(StepKind.STOP, running.kind());
        assertTrue(live.events.get(2) instanceof ReportEvent.StepRecorded);
        assertTrue(session.reportInfo(null).contains("\"enabled\":false"), "reports are still off");
        assertEquals("Starting application in debug mode: OrdersApplication",
                SessionReport.startedTitle("start_java_application", Map.of("mainType", "org.example.OrdersApplication")));
    }

    /** A JSON string literal, for building bridge replies with Windows paths. */
    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("SR-U12 when a test harness knows the exact prompt, it is used instead of the agent's wording")
    void srU12() {
        Captured events = new Captured();
        SessionReport session = new SessionReport(events, null, "Exact words typed by the user", System.err);
        session.toolCalled("claude-code", "begin_debug_investigation", Map.of("request", "paraphrase by the agent"), false, BEGIN, 5);
        var started = (ReportEvent.InvestigationStarted) events.events.get(0);
        assertEquals("Exact words typed by the user", started.request());
        assertTrue(started.requestExact());
    }

    @Test
    @DisplayName("SR-U16 the skill, model and effort the agent names at the start are recorded with the investigation; none: null (OPEN-41)")
    void srU16() {
        call("begin_debug_investigation", Map.of("skill", "eclipse-debugger 0.1.0", "model", "claude-haiku-5-5", "effort", "high"),
                false, BEGIN);
        var started = (ReportEvent.InvestigationStarted) captured.events.get(0);
        assertEquals("eclipse-debugger 0.1.0", started.skill());
        assertEquals("claude-haiku-5-5", started.model());
        assertEquals("high", started.effort());
        Captured without = new Captured();
        new SessionReport(without).toolCalled("claude-code", "begin_debug_investigation", Map.of(), false, BEGIN, 5);
        assertEquals(null, ((ReportEvent.InvestigationStarted) without.events.get(0)).skill());
    }

    @Test
    @DisplayName("SR-U8 the small JSON reader handles the bridge's replies and rejects invalid text")
    void srU8() {
        var stop = Json.obj(Json.parse(STOP));
        assertEquals("3", Json.str(stop.get("threadIndex")));
        assertEquals(Boolean.TRUE, stop.get("stopped"));
        assertEquals(3, Json.arr(stop.get("locals")).size());
        assertEquals("a\"b\né", Json.parse("\"a\\\"b\\n\\u00e9\""));
        assertEquals(null, Json.parse("{\"a\":"));
        assertEquals(null, Json.parse("{} trailing"));
        assertEquals("OrderService", SessionReport.simple("org.example.OrderService"));
        assertEquals("List<java.lang.String>", SessionReport.simple("java.util.List<java.lang.String>"));
    }

    @Test
    @DisplayName("SR-U15 the finish reply names the report file, so no get_investigation_report call is needed (OPEN-39)")
    void srU15(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        Captured events = new Captured();
        SessionReport session = new SessionReport(events, folder, null, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String reply = "{\"status\":\"success\",\"action\":\"CLEANUP\",\"completed\":true}";
        session.toolCalled("claude-code", "begin_debug_investigation", Map.of("request", "Why 120?"), false, BEGIN, 5);
        assertEquals(reply, session.withReportFile("inv-1", reply), "no report chosen yet: unchanged");
        session.toolCalled("claude-code", "finish_debug_investigation", Map.of("investigationId", "inv-1", "summary", "s"),
                false, reply, 3);
        var finished = (ReportEvent.InvestigationFinished) events.events.get(events.events.size() - 1);
        String extended = session.withReportFile("inv-1", reply);
        var json = org.learningfromexperience.agentdebug.report.format.Json.obj(
                org.learningfromexperience.agentdebug.report.format.Json.parse(extended));
        assertEquals("CLEANUP", json.get("action"), "the original reply is kept");
        assertEquals(true, json.get("completed"));
        assertEquals(finished.reportFile().toString(), json.get("reportFile"));
        assertTrue(String.valueOf(json.get("reportMessage")).contains("No further call is needed"), extended);
        assertEquals(extended, session.withReportFile("inv-1", extended), "added once only");
        assertEquals("not json", session.withReportFile("inv-1", "not json"));
        assertEquals(reply, session.withReportFile("inv-2", reply), "another investigation: unchanged");
        assertEquals(reply, new SessionReport(ReportRecorder.off()).withReportFile("inv-1", reply), "reports off: unchanged");
    }
}
