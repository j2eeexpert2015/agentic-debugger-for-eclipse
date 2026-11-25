package org.learningfromexperience.agentdebug.report.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * JUnit 5 unit tests for {@link FlowPage}, the program flow of the HTML report (FP-U1 to FP-U8, OPEN-30 R4 phases 1 and 2).
 * The steps have the titles and details the MCP server writes (trial of 25 Nov 10:23 and variations of it). No Eclipse
 * needed: run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class FlowPageTest {

    private static ActivityStore.Step step(String kind, String status, String title, List<String> reader, String... technical) {
        List<String> details = new ArrayList<>(reader);
        details.add(ReportFormat.TECHNICAL_DETAILS);
        details.addAll(List.of(technical));
        return new ActivityStore.Step(kind, title, status, details, 100, "2026-11-25T04:53:00Z", "2026-11-25T04:53:01Z");
    }

    private static ActivityStore.Step ok(String kind, String title, List<String> reader, String... technical) {
        return step(kind, "OK", title, reader, technical);
    }

    private static ActivityStore.Investigation inv(List<ActivityStore.Step> steps, String conclusion) {
        return inv(steps, conclusion, null);
    }

    private static ActivityStore.Investigation inv(List<ActivityStore.Step> steps, String conclusion, String analysis) {
        return new ActivityStore.Investigation("0e462d67-0590", "NORMAL", "claude-code", "Why 120.00?", true,
                "2026-11-25T04:52:44Z", steps, 0, "FINISHED", conclusion, List.of(), null, "2026-11-25T04:53:13Z",
                ActivityStore.PROGRAM_ENDED, null, "2026-11-25T04:53:13Z", analysis);
    }

    /** A variables table as the MCP server writes it (name, type, value triples). */
    private static List<String> table(String... cells) {
        List<String> lines = new ArrayList<>();
        lines.add("Variable           Type            Value");
        lines.add("---------------    ------------    -----");
        for (int i = 0; i < cells.length; i += 3)
            lines.add(String.format("%-15s    %-12s    %s", cells[i], cells[i + 1], cells[i + 2]));
        return lines;
    }

    private static ActivityStore.Step stop(String title, String frames, List<String> table) {
        List<String> reader = new ArrayList<>(List.of("Thread: http-nio-exec-2 (index 4)", "Frames: " + frames, ""));
        reader.addAll(table);
        return ok("STOP", title, reader, "Tool: wait_for_stop", "launchName: OrdersApplication");
    }

    private static ActivityStore.Step breakpoint(String type, int line) {
        return ok("BREAKPOINT", "Set breakpoint: " + type + ", line " + line,
                List.of("New breakpoint owned by the agent; it is removed again at cleanup."), "Tool: set_breakpoint");
    }

    private static ActivityStore.Step control(String action, String title) {
        return ok("STEP", title, List.of(), "Tool: control_debug_session", "action: " + action, "Result: accepted=true");
    }

    private static final ActivityStore.Step LAUNCH = ok("LAUNCH", "Started application in debug mode: OrdersApplication",
            List.of("Started a new JVM in debug mode with the existing launch configuration."), "Tool: start_java_application");
    private static final ActivityStore.Step REQUEST = ok("HTTP", "Sent HTTP request: POST /orders/quote (discount scenario)",
            List.of("POST http://127.0.0.1:61495/orders/quote", "Body sent: {\"quantity\":2,\"discountPercent\":10}",
                    "Request id: 7a53 (PENDING)"), "Tool: start_fixture_http_request");
    private static final ActivityStore.Step RESPONSE = ok("HTTP", "Received HTTP response: 200",
            List.of("HTTP 200", "Body: {\"total\":120.00}"), "Tool: get_fixture_http_request");
    private static final ActivityStore.Step CLEANUP = ok("CLEANUP", "Finished investigation and cleaned up",
            List.of("Action: CLEANUP", "Removed own breakpoint OrderService:24", "JVM ended: true"));
    private static final ActivityStore.Step STOP_24 = stop("Stopped at breakpoint: OrderService.quote, line 24",
            "OrderService.quote:24  <  OrderController.quote:21  <  (53 more)",
            table("quantity", "int", "2", "unitPrice", "BigDecimal", " (id=75)", "percent", "int", "10", "rate", "int", "0"));

    private static List<FlowPage.Kind> kinds(List<FlowPage.Node> nodes) {
        return nodes.stream().map(FlowPage.Node::kind).toList();
    }

    @Test @DisplayName("FP-U1 the trial of 25 Nov 10:23: one node per place, the expansions folded into the stop")
    void fpU1() {
        ActivityStore.Investigation inv = inv(List.of(breakpoint("OrderService", 24), LAUNCH, REQUEST, STOP_24,
                ok("INSPECT", "Expanded variable: unitPrice", table("intCompact", "long", "60", "scale", "int", "0"),
                        "Tool: expand_frame_variable", "path: unitPrice"),
                ok("INSPECT", "Expanded variable: unitPrice", table("scale", "int", "0", "precision", "int", "0"),
                        "Tool: expand_frame_variable", "path: unitPrice"),
                control("RESUME", "Resumed execution"), RESPONSE, CLEANUP),
                "## Symptom\nReproduced.\n## Root cause\n`OrderService.java:23` uses **integer division**.");
        List<FlowPage.Node> nodes = FlowPage.nodes(inv);
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.STOP, FlowPage.Kind.RESPONSE,
                FlowPage.Kind.CLEAN, FlowPage.Kind.AGENT), kinds(nodes));
        assertEquals("breakpoint OrderService:24 · OrdersApplication started in debug mode", nodes.get(0).sub());
        assertEquals("POST /orders/quote (discount scenario)", nodes.get(1).name());
        assertEquals("{\"quantity\":2,\"discountPercent\":10}", nodes.get(1).sub());
        FlowPage.Node stop = nodes.get(2);
        assertEquals("OrderService.quote:24", stop.name());
        assertTrue(stop.breakpointHit());
        assertEquals("called from OrderController.quote:21 · thread http-nio-exec-2", stop.sub());
        assertEquals(List.of("quantity", "unitPrice", "percent", "rate"),
                stop.variables().stream().map(ActivityPage.Variable::name).toList(), "the frame's variables, not the fields");
        assertEquals(1, stop.expansions().size(), "two expansions of one path, folded");
        assertEquals(2, stop.expansions().get(0).count());
        assertEquals(List.of("intCompact", "scale", "precision"),
                stop.expansions().get(0).fields().stream().map(ActivityPage.Variable::name).toList(), "fields without repeats");
        assertEquals(List.of("wait_for_stop", "expand_frame_variable unitPrice ×2", "control_debug_session RESUME"),
                stop.calls().stream().map(FlowPage.Call::label).toList());
        assertEquals(List.of(3, 4, 5, 6), stop.steps(), "the stop and the calls made there");
        assertEquals("HTTP 200 {\"total\":120.00}", nodes.get(3).name());
        assertEquals("Cleaned up", nodes.get(4).name());
        assertEquals("Removed own breakpoint OrderService:24 · JVM ended: true", nodes.get(4).sub());
        assertEquals("OrderService.java:23 uses integer division.", nodes.get(5).sub(), "the root cause line");
        assertNull(stop.purpose(), "phase 1: no statements of the agent at the nodes");
        assertNull(stop.finding());
    }

    @Test @DisplayName("FP-U2 a step to the next line: the movement and the changed values are shown")
    void fpU2() {
        List<FlowPage.Node> nodes = FlowPage.nodes(inv(List.of(breakpoint("OrderService", 24), LAUNCH, REQUEST, STOP_24,
                control("STEP_OVER", "Stepped over the current line"),
                stop("Stopped after step: OrderService.quote, line 25", "OrderService.quote:25  <  OrderController.quote:21",
                        table("quantity", "int", "2", "percent", "int", "10", "rate", "int", "1")),
                control("RESUME", "Resumed execution"), RESPONSE, CLEANUP), null));
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.STOP, FlowPage.Kind.STOP,
                FlowPage.Kind.RESPONSE, FlowPage.Kind.CLEAN), kinds(nodes), "no agent node without a conclusion");
        FlowPage.Node second = nodes.get(3);
        assertEquals("line 24 → 25", second.movement());
        assertEquals("same method, line 24 → 25 · thread http-nio-exec-2", second.sub());
        assertFalse(second.breakpointHit(), "a step end, not a breakpoint");
        ActivityPage.Variable rate = second.variables().get(2);
        assertEquals("1", rate.value());
        assertEquals("0", rate.previous(), "changed since the previous stop");
        assertFalse(second.variables().get(0).changed());
        String html = FlowPage.render(inv(List.of(STOP_24, control("STEP_OVER", "Stepped over the current line"),
                stop("Stopped after step: OrderService.quote, line 25", "OrderService.quote:25",
                        table("rate", "int", "1"))), null));
        assertTrue(html.contains("<span>stepped over</span>"), "how the program left the first stop");
        assertTrue(html.contains("<tr class=\"changed\"><td>rate</td>") && html.contains("(was 0)"), html);
    }

    @Test @DisplayName("FP-U3 breakpoints never hit, and no stop at all, appear before the cleanup")
    void fpU3() {
        List<FlowPage.Node> nodes = FlowPage.nodes(inv(List.of(breakpoint("org.x.OrderService", 24),
                breakpoint("OrderRepository", 40),
                ok("BREAKPOINT", "Set tracepoint: OrderController, line 21", List.of(), "Tool: set_tracepoint"),
                LAUNCH, REQUEST, STOP_24, control("RESUME", "Resumed execution"), RESPONSE, CLEANUP), null));
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.STOP, FlowPage.Kind.RESPONSE,
                FlowPage.Kind.NEVER, FlowPage.Kind.CLEAN), kinds(nodes), "a tracepoint never stops: not listed as missed");
        assertEquals("OrderRepository:40", nodes.get(4).name());
        assertEquals(List.of(1), nodes.get(4).steps(), "the step that set it");

        List<FlowPage.Node> none = FlowPage.nodes(inv(List.of(breakpoint("OrderService", 24), LAUNCH, REQUEST,
                ok("STOP", "Waited for a stop: none (TIMEOUT)", List.of("No stop: TIMEOUT"), "Tool: wait_for_stop"),
                RESPONSE, CLEANUP), null));
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.NOSTOP, FlowPage.Kind.RESPONSE,
                FlowPage.Kind.NEVER, FlowPage.Kind.CLEAN), kinds(none), "the wait itself is the no-stop node");
        assertEquals("Waited for a stop: none (TIMEOUT)", none.get(2).name());

        List<FlowPage.Node> silent = FlowPage.nodes(inv(List.of(breakpoint("OrderService", 24), LAUNCH, REQUEST,
                RESPONSE, CLEANUP), null));
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.RESPONSE, FlowPage.Kind.NOSTOP,
                FlowPage.Kind.NEVER, FlowPage.Kind.CLEAN), kinds(silent));
        assertEquals("No stop was reached", silent.get(3).name());
    }

    @Test @DisplayName("FP-U4 expressions with their results; a refused call stays at its node and is marked")
    void fpU4() {
        FlowPage.Node stop = FlowPage.nodes(inv(List.of(STOP_24,
                ok("INSPECT", "Evaluated expression: percent / 100", List.of(), "Tool: evaluate_expression",
                        "expression: percent / 100", "Result: value=0"),
                step("INSPECT", "FAILED", "Evaluated expression: percent / 100.0",
                        List.of("Error: Evaluation failed: syntax"), "Tool: evaluate_expression")), null)).get(0);
        assertEquals(List.of(new FlowPage.Expression("percent / 100", "0", false),
                new FlowPage.Expression("percent / 100.0", "Error: Evaluation failed: syntax", true)), stop.expressions());
        assertEquals(1, stop.refused());
        assertEquals(List.of("wait_for_stop", "evaluate_expression", "evaluate_expression"),
                stop.calls().stream().map(FlowPage.Call::label).toList(), "a refused call is counted apart");
        String html = FlowPage.render(inv(List.of(STOP_24, step("INSPECT", "FAILED", "Evaluated expression: x",
                List.of("Error: no x"), "Tool: evaluate_expression")), null));
        assertTrue(html.contains("REFUSED CALL") && html.contains("evaluate_expression (refused)"), html);
        assertTrue(html.contains("refused: Error: no x"));
    }

    @Test @DisplayName("FP-U5 the report: dark by default, flow first, every node's details in the file, All steps kept, all escaped")
    void fpU5() {
        ActivityStore.Investigation inv = inv(List.of(breakpoint("OrderService", 24), LAUNCH, REQUEST,
                stop("Stopped at breakpoint: OrderService.quote, line 24", "OrderService.quote:24",
                        table("note", "String", "\"<script>alert(1)</script>\"")),
                control("RESUME", "Resumed execution"), RESPONSE, CLEANUP), "Root cause: <b>x</b>");
        String html = ActivityPage.report(inv, 0, ZoneId.of("Asia/Kolkata"), "Agentic Debugger for Eclipse");
        assertTrue(html.indexOf("<main class=\"fl\">") < html.indexOf("<div id=\"all\">"), "the flow first");
        for (int i = 0; i < 6; i++)
            assertTrue(html.contains("<section class=\"fd\" id=\"fd" + i + "\" data-n=\"" + i + "\">"), "section " + i);
        assertTrue(html.contains("Step 3 of 6") && html.contains("Stop 1 of 1: <a class=\"src\""));
        assertTrue(html.contains("Previous") && html.contains("Replay") && html.contains("flInit();"));
        assertTrue(html.contains("<body class=\"report dark\">") && html.contains("id=\"ftheme\""),
                "dark by default, with a switch to the light theme");
        assertTrue(html.contains("<span class=\"lbl\">Request: </span>") && html.contains("<h2>From the agent</h2>")
                && html.contains("Finished investigation and cleaned up"), "what the REPORT CHECK of the trials looks for");
        assertTrue(html.contains("<details open data-i=\"1\""), "All steps as before");
        assertFalse(html.contains("<script>alert(1)"), "values from the program escaped");
        assertFalse(html.contains("<b>x</b>"), "the agent's text escaped");
        assertFalse(html.contains("<link") || html.contains(" src=\""), "nothing loaded from outside");
        // A node is a link; no source link may sit inside it.
        int at = 0;
        while ((at = html.indexOf("<a class=\"fnode", at)) >= 0) {
            int end = html.indexOf("</a>", at);
            assertFalse(html.substring(at + 2, end).contains("<a "), "a link inside a node");
            at = end;
        }
    }

    @Test @DisplayName("FP-U6 the live view: both looks, the flow while the agent works, the running call in its node, nothing missed yet")
    void fpU6() {
        ActivityStore.Step running = new ActivityStore.Step("INSPECT", "Evaluated expression: percent / 100", "RUNNING",
                List.of(), -1, "2026-11-25T04:53:05Z", null);
        ActivityStore.Investigation inv = new ActivityStore.Investigation("0e462d67-0590", "NORMAL", "claude-code", "Why?",
                true, "2026-11-25T04:52:44Z", List.of(breakpoint("OrderService", 24), breakpoint("OrderRepository", 40),
                LAUNCH, REQUEST, STOP_24, running), 0, null, null, List.of(), null, null, ActivityStore.PROGRAM_PAUSED,
                "OrderService.quote:24", "2026-11-25T04:53:05Z", null);
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.STOP), kinds(FlowPage.nodes(inv)),
                "no never-hit node while the investigation runs: OrderRepository:40 may still be hit");
        assertTrue(FlowPage.nodes(inv).get(2).expressions().isEmpty(), "a running evaluation has no result yet");
        String live = FlowPage.live(inv);
        assertTrue(live.contains("data-i=\"f2\" data-d=\"1\" open>"), "the newest node open");
        assertTrue(live.contains("data-i=\"f0\" data-d=\"0\">"), "earlier nodes closed");
        assertTrue(live.contains(" busy\"") && live.contains("<span class=\"frun\"><span class=\"live\"></span>"
                + "Evaluated expression: percent / 100</span>"), "the running call pulses in its node");
        assertTrue(ActivityPage.shell().contains("<div id=\"flow\"></div>") && ActivityPage.shell().contains("adView("),
                "the view has the flow, with Flow and Steps");
        assertTrue(ActivityPage.shell(true).contains("<body class=\"dark\">") && ActivityPage.shell(true).contains("adTheme()")
                && !ActivityPage.shell().contains("<body class="), "dark or light as the user chose; light by default");
        assertTrue(ActivityPage.update(inv, 0, ZoneId.of("Asia/Kolkata")).contains("flow:"), "and updates it in place");
    }

    /** The analysis the MCP server sends at the finish (FinishAnalysis): the agent's fields and the source it read. */
    private static final String ANALYSIS = "{\"title\":\"Discount lost by <b>integer</b> division\","
            + "\"trackedValue\":{\"name\":\"rate\",\"expected\":\"0.10\",\"actual\":\"0\"},"
            + "\"findings\":[{\"at\":\"OrderService.quote:24\",\"text\":\"Inputs and percent=10 correct; rate is 0.\"},"
            + "{\"at\":\"OrderController.quote:21\",\"text\":\"Not stopped here.\"}],"
            + "\"rootCause\":{\"file\":\"OrderService.java\",\"lines\":\"23\",\"explanation\":\"percent / 100 is integer division\"},"
            + "\"source\":{\"path\":\"src/main/java/OrderService.java\",\"from\":21,\"highlightFrom\":23,\"highlightTo\":23,"
            + "\"lines\":[\"    int percent = 10;\",\"    // <script>\",\"    int rate = percent / 100;\"]},"
            + "\"fix\":\"BigDecimal.valueOf(percent).movePointLeft(2)\",\"notObserved\":[\"line 23 was not stepped\"],"
            + "\"assumedPath\":\"OrderController.quote -> OrderService.quote\"}";

    @Test @DisplayName("FP-U7 phase 2: the agent's findings placed in the flow, its value checked, the source shown")
    void fpU7() {
        ActivityStore.Investigation inv = inv(List.of(breakpoint("OrderService", 24), LAUNCH, REQUEST, STOP_24,
                control("RESUME", "Resumed execution"), RESPONSE, CLEANUP), null, ANALYSIS);
        List<FlowPage.Node> nodes = FlowPage.nodes(inv);
        assertEquals(List.of(FlowPage.Kind.PREP, FlowPage.Kind.REQUEST, FlowPage.Kind.ASSUMED, FlowPage.Kind.STOP,
                FlowPage.Kind.RESPONSE, FlowPage.Kind.CLEAN, FlowPage.Kind.AGENT), kinds(nodes),
                "the assumed path between the request and the first stop; an agent node even without a Markdown summary");
        assertEquals("Inputs and percent=10 correct; rate is 0.", nodes.get(3).finding(), "the finding at its stop");
        assertEquals("Discount lost by <b>integer</b> division", nodes.get(6).sub());
        FlowPage.Analysis a = FlowPage.analysis(inv);
        assertTrue(FlowPage.check(a, nodes).contains("OBSERVED</span> Recorded: rate = 0 at OrderService.quote:24"),
                FlowPage.check(a, nodes));
        String html = ActivityPage.report(inv, 0, ZoneId.of("Asia/Kolkata"), "Agentic Debugger for Eclipse");
        assertTrue(html.contains("<span class=\"sl hl\"><span class=\"ln\">23</span>    int rate = percent / 100;</span>"),
                "the root cause line, read by the tool, highlighted");
        assertTrue(html.contains("What the agent learned here") && html.contains("Not stopped here.")
                && html.contains("Findings not tied to a recorded stop"), "placed and unplaced findings");
        assertTrue(html.contains("line 23 was not stepped") && html.contains("Suggested fix (not applied)"));
        assertFalse(html.contains("<b>integer</b>") || html.contains("// <script>"), "the agent's text and the source escaped");

        FlowPage.Analysis wrong = new FlowPage.Analysis(null, "rate", "0.10", "5", List.of(), null, null, null, null, 0,
                0, 0, List.of(), null, null, List.of(), null);
        assertTrue(FlowPage.check(wrong, nodes).contains("DIFFERS</span> Recorded: rate = 0 at OrderService.quote:24, not 5"));
        FlowPage.Analysis inResponse = new FlowPage.Analysis(null, "total", "108.00", "120.00", List.of(), null, null,
                null, null, 0, 0, 0, List.of(), null, null, List.of(), null);
        assertTrue(FlowPage.check(inResponse, nodes).contains("Seen in the HTTP response"));
        FlowPage.Analysis unknown = new FlowPage.Analysis(null, "tax", "1", "2", List.of(), null, null, null, null, 0, 0,
                0, List.of(), null, null, List.of(), null);
        assertTrue(FlowPage.check(unknown, nodes).contains("NOT RECORDED"));
        assertTrue(FlowPage.sameLocation("org.x.OrderService.quote, line 24", "OrderService.quote:24")
                && FlowPage.sameLocation("OrderService.java:24", "OrderService.quote:24")
                && !FlowPage.sameLocation("OrderService.java:25", "OrderService.quote:24"));
        assertEquals(FlowPage.Analysis.NONE, FlowPage.analysis(inv(List.of(), null, "{not json")), "unreadable: ignored");
    }

    @Test @DisplayName("FP-U8 phase 2: the agent's purposes at the stops, and the value tracker with the first wrong value")
    void fpU8() {
        ActivityStore.Step bp24 = ok("BREAKPOINT", "Set breakpoint: OrderService, line 24", List.of(), "Tool: set_breakpoint",
                "typeName: org.example.OrderService", "lineNumber: 24", "purpose: See the discount values before the total");
        ActivityStore.Step bp40 = ok("BREAKPOINT", "Set breakpoint: OrderRepository, line 40", List.of(), "Tool: set_breakpoint",
                "purpose: Check the stored discount");
        ActivityStore.Step step = ok("STEP", "Stepped over the current line", List.of(), "Tool: control_debug_session",
                "action: STEP_OVER", "purpose: Watch rate after line 24");
        ActivityStore.Step eval = ok("INSPECT", "Evaluated expression: percent / 100", List.of(), "Tool: evaluate_expression",
                "purpose: Test the division", "Result: value=0");
        String analysis = "{\"trackedValue\":{\"name\":\"rate\",\"expected\":\"0.1\",\"actual\":\"0\"}}";
        ActivityStore.Investigation inv = inv(List.of(bp24, bp40, LAUNCH, REQUEST, STOP_24, eval, step,
                stop("Stopped after step: OrderService.quote, line 25", "OrderService.quote:25",
                        table("rate", "int", "0")), control("RESUME", "Resumed execution"), RESPONSE, CLEANUP),
                null, analysis);
        List<FlowPage.Node> nodes = FlowPage.nodes(inv);
        assertEquals("See the discount values before the total", nodes.get(2).purpose(), "the breakpoint's purpose");
        assertEquals("Watch rate after line 24", nodes.get(3).purpose(), "the step's purpose");
        assertEquals("Test the division", nodes.get(2).expressions().get(0).purpose());
        FlowPage.Node never = nodes.stream().filter(n -> n.kind() == FlowPage.Kind.NEVER).findFirst().orElseThrow();
        assertEquals("Check the stored discount", never.purpose());

        List<FlowPage.Seen> seen = FlowPage.seen(FlowPage.analysis(inv), nodes);
        assertEquals(2, seen.size(), "rate at both stops");
        assertFalse(seen.get(0).ok(), "0 is not 0.1");
        String html = ActivityPage.report(inv, 0, ZoneId.of("Asia/Kolkata"), "Agentic Debugger for Eclipse");
        assertTrue(html.contains("<div class=\"tv bad\" data-n=\"2\"><span class=\"first\">FIRST WRONG</span>"), html);
        assertTrue(html.contains("<div class=\"fmis\" data-n=\"2\">Mismatch at OrderService.quote:24"));
        assertEquals(1, html.split("FIRST WRONG", -1).length - 1, "only the first wrong place is marked");
        assertTrue(html.contains("Why the agent stopped here") && html.contains("why: Test the division")
                && html.contains("Why the agent set it"));

        // The request's own value, by name; numbers compared by value.
        String discount = "{\"trackedValue\":{\"name\":\"discountPercent\",\"expected\":\"10\",\"actual\":\"10\"}}";
        List<FlowPage.Seen> request = FlowPage.seen(FlowPage.analysis(inv(List.of(LAUNCH, REQUEST, STOP_24, RESPONSE),
                null, discount)), FlowPage.nodes(inv(List.of(LAUNCH, REQUEST, STOP_24, RESPONSE), null, discount)));
        assertEquals("Request", request.get(0).where());
        assertTrue(request.get(0).ok());
        assertTrue(FlowPage.same("0.10", "0.1") && !FlowPage.same("0", "0.1") && FlowPage.same("\"abc\"", "abc"));
        assertTrue(FlowPage.same("0.1", "0.10 (10%)") && !FlowPage.same("0", "0.10 (10%)"),
                "the agent's expected value with words (trial 25 Nov 12:29): its first number counts");
        assertEquals("", FlowPage.tracker(FlowPage.Analysis.NONE, nodes), "no value named: no tracker");
    }
}
