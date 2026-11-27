package org.learningfromexperience.agentdebug.report.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * JUnit 5 unit tests for {@link ActivityPage}, the Agent Activity page (AP-U1 to AP-U13; OPEN-36 deliveries 4 and 5, OPEN-41, OPEN-48, OPEN-49).
 * No Eclipse needed: run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class ActivityPageTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    /** 2026-11-24T10:00:00Z, the start of the test investigation. */
    private static final long START = 1795514400000L;

    private static String enc(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String q(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static ActivityStore started(String request) {
        ActivityStore store = new ActivityStore();
        store.accept(enc("{\"type\":\"investigationStarted\",\"investigationId\":\"3b80cb03-1111\",\"at\":\"2026-11-24T10:00:00Z\","
                + "\"mode\":\"NORMAL\",\"client\":\"claude-code\",\"request\":" + q(request) + ",\"requestExact\":true}"));
        return store;
    }

    private static void step(ActivityStore store, String type, String kind, String status, String title, List<String> details) {
        StringBuilder array = new StringBuilder("[");
        for (String line : details) array.append(array.length() > 1 ? "," : "").append(q(line));
        store.accept(enc("{\"type\":\"" + type + "\",\"investigationId\":\"3b80cb03-1111\",\"at\":\"2026-11-24T10:00:05Z\","
                + "\"kind\":\"" + kind + "\",\"status\":\"" + status + "\",\"title\":" + q(title) + ",\"details\":"
                + array.append(']') + ",\"durationMs\":1038}"));
    }

    private static void step(ActivityStore store, String type, String kind, String status, String title) {
        step(store, type, kind, status, title, List.of());
    }

    private static void finish(ActivityStore store, String outcome, String conclusion) {
        store.accept(enc("{\"type\":\"investigationFinished\",\"investigationId\":\"3b80cb03-1111\","
                + "\"at\":\"2026-11-24T10:00:45Z\",\"outcome\":\"" + outcome + "\",\"conclusion\":"
                + (conclusion == null ? "null" : q(conclusion)) + ",\"cleanup\":[],\"reportFile\":null}"));
    }

    /** A variables table exactly as the MCP server writes it (SessionReport.variables). */
    private static List<String> table(String... nameTypeValue) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Variable", "Type", "Value"});
        for (int i = 0; i < nameTypeValue.length; i += 3)
            rows.add(new String[] {nameTypeValue[i], nameTypeValue[i + 1], nameTypeValue[i + 2]});
        int w0 = 0, w1 = 0;
        for (String[] r : rows) { w0 = Math.max(w0, r[0].length()); w1 = Math.max(w1, r[1].length()); }
        List<String> lines = new ArrayList<>(List.of(""));
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            lines.add(String.format("%-" + w0 + "s    %-" + w1 + "s    %s", r[0], r[1], r[2]));
            if (i == 0) lines.add("-".repeat(w0) + "    " + "-".repeat(Math.max(w1, 4)) + "    -----");
        }
        return lines;
    }

    private static List<String> stopDetails(String frames, List<String> table) {
        List<String> lines = new ArrayList<>(List.of("Thread: http-nio-127.0.0.1-auto-1-exec-1 (index 3)", "Frames: " + frames));
        lines.addAll(table);
        lines.add("Note: values of this frame only");
        lines.add(ReportFormat.TECHNICAL_DETAILS);
        lines.add("Tool: wait_for_stop");
        return lines;
    }

    @Test @DisplayName("AP-U1 steps: escaped, coloured by kind and status like the report, a refused call is amber")
    void apU1() {
        ActivityStore store = started("Why?");
        step(store, "stepRecorded", "HTTP", "FAILED", "<script>alert(1)</script>");
        String steps = ActivityPage.steps(store.current());
        assertFalse(steps.contains("<script>"), "must be escaped");
        assertTrue(steps.contains("&lt;script&gt;"));
        assertTrue(steps.contains("class=\"s refused\""));
        assertTrue(steps.contains("--k:#d63384;--c:#e08a00"), "HTTP colour bar, amber status: " + steps);
        assertTrue(steps.contains(">REFUSED<"));
        assertTrue(steps.contains(">1.0 s<"), "duration");
        assertFalse(steps.contains("<details"), "a step without details cannot be opened");
    }

    @Test @DisplayName("AP-U2 live state: running banner, blinking step and wait timer; green or amber and no blinking at the end")
    void apU2() {
        ActivityStore store = started("Why?");
        assertEquals("Investigation started", ActivityPage.statusLine(store.current()));
        step(store, "stepStarted", "STOP", "RUNNING", "Waiting for the program to stop");
        assertTrue(ActivityPage.banner(store.current()).contains("class=\"banner running debugging\""));
        assertTrue(ActivityPage.banner(store.current()).contains("Running: Waiting for the program to stop"));
        assertTrue(ActivityPage.steps(store.current()).contains("class=\"s run\""), "the running step blinks");
        assertTrue(ActivityPage.steps(store.current()).contains("<span class=\"live\"></span>"), "pulsing dot on the running step");
        assertTrue(ActivityPage.banner(store.current()).contains("<span class=\"live\"></span>"), "pulsing dot in the banner");
        String running = ActivityPage.update(store.current(), 0, KOLKATA);
        assertTrue(running.contains("start:" + START + ",end:0,"), running);
        assertTrue(running.contains("wait:" + (START + 5000) + ",running:true"), "the page warns after 30 s of waiting");
        step(store, "stepRecorded", "STOP", "OK", "Stopped at breakpoint: OrderService.quote, line 24");
        assertEquals("The agent is thinking", ActivityPage.statusLine(store.current()));
        assertFalse(ActivityPage.steps(store.current()).contains(" run\""));
        assertTrue(ActivityPage.update(store.current(), 0, KOLKATA).contains("wait:0,running:false"));

        finish(store, "FINISHED", null);
        assertTrue(ActivityPage.banner(store.current()).contains("class=\"banner done\""));
        assertFalse(ActivityPage.banner(store.current()).contains("class=\"live\""), "no pulsing once finished");
        assertTrue(ActivityPage.banner(store.current()).contains("took <span id=\"timer\">"));
        assertTrue(ActivityPage.update(store.current(), 0, KOLKATA).contains("start:" + START + ",end:" + (START + 45000) + ","),
                "timer stopped at the finish");

        ActivityStore ended = started("Why?");
        finish(ended, "ENDED_WITHOUT_FINISH", null);
        assertTrue(ActivityPage.banner(ended.current()).contains("class=\"banner ended\""));
        assertTrue(ActivityPage.banner(ended.current()).contains("Ended without the agent finishing it"));
        assertEquals("Ended without the agent finishing it", ActivityPage.statusLine(ended.current()), "no reason recorded");

        // The agent's connection closed: the banner says so, so it does not read like an error of the tool.
        ActivityStore closed = started("Why?");
        closed.accept(enc("{\"type\":\"investigationFinished\",\"investigationId\":\"3b80cb03-1111\","
                + "\"at\":\"2026-11-24T10:00:45Z\",\"outcome\":\"ENDED_WITHOUT_FINISH\",\"conclusion\":null,"
                + "\"cleanup\":[\"Finished by the MCP server: agent closed the MCP connection\","
                + "\"Removed own breakpoint StepFilterApplication:9\"],\"reportFile\":null}"));
        assertEquals("Ended without the agent finishing it: agent closed the MCP connection, so the MCP server finished it",
                ActivityPage.statusLine(closed.current()));
    }

    @Test @DisplayName("AP-U3 header: short title from the launch, request cut to a preview that expands, local start time")
    void apU3() {
        String longRequest = "x".repeat(ActivityPage.REQUEST_PREVIEW + 50);
        ActivityStore store = started(longRequest);
        assertEquals("Debugging session", ActivityPage.title(store.current()));
        step(store, "stepRecorded", "LAUNCH", "OK", "Started application in debug mode: OrdersApplication");
        assertEquals("Debugging OrdersApplication", ActivityPage.title(store.current()));
        String head = ActivityPage.head(store.current(), KOLKATA);
        assertTrue(head.contains("<h1>Debugging OrdersApplication</h1>"));
        assertTrue(head.contains("<details class=\"req\">"), "the request is collapsed");
        assertTrue(head.contains("x".repeat(ActivityPage.REQUEST_PREVIEW) + "&#8230;"), "preview cut with an ellipsis");
        assertTrue(head.contains("(click to show all)"));
        assertTrue(head.contains("<div class=\"full\">" + longRequest + "</div>"), "the full request is there when expanded");
        assertTrue(head.contains("<b>Started:</b> 24 Nov, 15:30:00"), "local date and time, not UTC: " + head);
        assertTrue(head.contains("<b>Investigation ID:</b> <code title=\"3b80cb03-1111\">3b80cb03</code>"), head);
        assertTrue(ActivityPage.update(store.current(), 0, KOLKATA).contains("title:'Debugging OrdersApplication'"),
                "the title is sent with every update: the header is drawn once, before the launch names the program");
    }

    @Test @DisplayName("AP-U4 the update is one safe script call; with nothing yet the page waits")
    void apU4() {
        ActivityStore store = started("Say \"hi\" and it's </script>\nnext line");
        String call = ActivityPage.update(store.current(), 0, KOLKATA);
        assertTrue(call.startsWith("adUpdate({id:'3b80cb03-1111',"), call);
        assertFalse(call.contains("<"), "no raw < inside the script: " + call);
        assertFalse(call.contains("\n"), "no raw line break inside the script");
        assertEquals("'it\\'s \\x3c/b>'", ActivityPage.js("it's </b>"));
        assertTrue(ActivityPage.update(null, 0, KOLKATA).contains("Waiting for an agent debugging session"));
        assertTrue(ActivityPage.update(null, 3, KOLKATA).contains("3 live events were dropped"));
        assertTrue(ActivityPage.shell().contains("function adUpdate("));
        assertFalse(ActivityPage.tools(store.current()).contains("copyMarkdown"), "no Copy as Markdown button");
        assertFalse(ActivityPage.tools(store.current()).contains("openReport"), "no report yet");
    }

    @Test @DisplayName("AP-U5 analysis rendered from Markdown; the text fallback has the whole investigation as Markdown")
    void apU5() {
        ActivityStore store = started("Why?");
        step(store, "stepRecorded", "HTTP", "FAILED", "Sent HTTP request");
        finish(store, "FINISHED", "## Root cause\n`rate` is **0** at OrderService.java:23");
        String analysis = ActivityPage.analysis(store.current());
        assertTrue(analysis.contains("<div class=\"ad-h\">Root cause</div>"), analysis);
        assertTrue(analysis.contains("<code>rate</code> is <strong>0</strong>"), analysis);
        assertTrue(analysis.contains("adGo(event,'OrderService',23)"), "source links in the analysis: " + analysis);
        String markdown = ActivityPage.markdown(store.current(), KOLKATA);
        assertTrue(markdown.startsWith("# Debugging session\n"), markdown);
        assertTrue(markdown.contains("**Status:** Finished in 45.0 s"), markdown);
        assertTrue(markdown.contains("1. **REFUSED** Sent HTTP request (1.0 s)"), markdown);
        assertTrue(markdown.contains("## From the agent\n\n## Root cause"), markdown);
        String text = ActivityPage.text(store.current(), 2, KOLKATA);
        assertTrue(text.contains("Finished in 45.0 s") && text.contains("2 live events were dropped"), text);
        assertFalse(ActivityPage.tools(store.current()).contains("copyAnalysis"), "no separate Copy Analysis button");
    }

    @Test @DisplayName("AP-U6 current stop: where, call chain, variables; values changed since the previous stop are marked")
    void apU6() {
        ActivityStore store = started("Why?");
        assertNull(ActivityPage.currentStop(store.current()));
        assertEquals("", ActivityPage.stop(store.current()));
        step(store, "stepRecorded", "STOP", "OK", "Stopped at breakpoint: OrderService.quote, line 23",
                stopDetails("OrderService.quote:23  <  OrderController.quote:21",
                        table("quantity", "int", "2", "percent", "int", "10", "total", "BigDecimal", "0")));
        step(store, "stepRecorded", "STEP", "OK", "Stepped over the current line");
        step(store, "stepRecorded", "STOP", "OK", "Stopped after step: OrderService.quote, line 24",
                stopDetails("OrderService.quote:24  <  OrderController.quote:21",
                        table("quantity", "int", "2", "percent", "int", "10", "rate", "int", "0", "total", "BigDecimal", "120")));
        ActivityPage.Stop stop = ActivityPage.currentStop(store.current());
        assertEquals("OrderService.quote:24", stop.where(), "the top frame");
        assertEquals("Stopped after step: OrderService.quote, line 24", stop.title());
        assertEquals("OrderService.quote:24  <  OrderController.quote:21", stop.frames());
        assertEquals(4, stop.variables().size(), "the Note line after the table is not a variable");
        assertEquals(new ActivityPage.Variable("total", "BigDecimal", "120", "0"), stop.variables().get(3));
        assertFalse(stop.variables().get(0).changed(), "unchanged value");
        assertFalse(stop.variables().get(2).changed(), "a new variable is not a change");
        String html = ActivityPage.stop(store.current());
        assertTrue(html.contains("<h2>Current stop: <a class=\"src\""), html);
        assertTrue(html.contains("<tr class=\"changed\"><td>total</td><td>BigDecimal</td><td>120 <span class=\"was\">(was 0)</span>"), html);
        assertTrue(html.contains("adGo(event,'OrderService',24)"), "the stop location is a link");
        assertTrue(ActivityPage.markdown(store.current(), KOLKATA).contains("| total | BigDecimal | 120 (was 0) |"));
        // A long call chain shows its first frames; the rest (framework and JDK) is folded under "N more".
        String chain = ActivityPage.frames("OrderService.quote:24  <  OrderController.quote:21  <  Method.invoke:565"
                + "  <  InvocableHandlerMethod.doInvoke:258  <  FrameworkServlet.service:885");
        assertTrue(chain.contains("<details class=\"more\"><summary>2 more</summary>"), chain);
        assertTrue(chain.indexOf("Method.invoke:565") < chain.indexOf("<details"), "the first three frames are shown");
        assertTrue(chain.indexOf("InvocableHandlerMethod.doInvoke:258") > chain.indexOf("<details"), "the rest is folded");
        assertFalse(ActivityPage.frames("A.b:1  <  C.d:2").contains("<details"), "a short chain is shown whole");
        // A step with details opens on a click: what happened, then the additional details collapsed.
        String steps = ActivityPage.steps(store.current());
        assertTrue(steps.contains("<details data-i=\"3\"><summary class=\"row\">"), steps);
        assertTrue(steps.contains("<details class=\"tech\"><summary>Additional Details</summary><pre>Tool: wait_for_stop</pre>"), steps);
        String shell = ActivityPage.shell();
        assertTrue(shell.contains("onclick=\"return adSteps(true)\"") && shell.contains(">Expand All</button>"), "Expand All button");
        assertTrue(shell.contains("onclick=\"return adSteps(false)\"") && shell.contains(">Collapse All</button>"), "Collapse All button");
        assertTrue(shell.contains("all:true"), "steps are expanded by default");
        // Time order: steps, then the current stop, then the analysis; toolbar and banner pinned above them.
        assertTrue(shell.indexOf("id=\"steps\"") < shell.indexOf("id=\"stop\"")
                && shell.indexOf("id=\"stop\"") < shell.indexOf("id=\"analysis\""), "steps, stop, analysis in that order");
        assertTrue(shell.indexOf("id=\"pin\"") < shell.indexOf("id=\"banner\"") && shell.indexOf("id=\"banner\"") < shell.indexOf("id=\"head\""),
                "the banner is in the pinned strip at the top");
        assertTrue(html.contains("<details class=\"stopbox\" data-i=\"stop\" data-d=\"1\" open>"), "current stop open while running");
        finish(store, "FINISHED", null);
        assertTrue(ActivityPage.stop(store.current()).contains("<h2>Last stop: "));
        assertTrue(ActivityPage.stop(store.current()).contains("data-d=\"0\"><summary><h2>Last stop: "), "folded after the finish");

        // A pause ("Thread suspended") has no frames of its own: they come from a later "Read call stack".
        ActivityStore paused = started("Why?");
        step(paused, "stepRecorded", "STOP", "OK", "Thread suspended: http-nio-127.0.0.1-auto-1-exec-3 (thread 4)");
        step(paused, "stepRecorded", "INSPECT", "OK", "Read call stack", List.of("  OrderService.quote:24",
                "  OrderController.quote:21", ReportFormat.TECHNICAL_DETAILS, "Tool: get_stack_frames"));
        List<String> vars = new ArrayList<>(table("quantity", "int", "2", "rate", "int", "0"));
        vars.add(ReportFormat.TECHNICAL_DETAILS);
        step(paused, "stepRecorded", "INSPECT", "OK", "Read variables", vars);
        ActivityPage.Stop fromPause = ActivityPage.currentStop(paused.current());
        assertEquals("OrderService.quote:24", fromPause.where());
        assertEquals("OrderService.quote:24  <  OrderController.quote:21", fromPause.frames());
        assertEquals(2, fromPause.variables().size());
    }

    @Test @DisplayName("AP-U7 source locations become links that open the file; tags, attributes and other text are left alone")
    void apU7() {
        assertTrue(ActivityPage.links("Set breakpoint: OrderService, line 23").contains("adGo(event,'OrderService',23)"));
        assertTrue(ActivityPage.links("Stopped at breakpoint: OrderService.quote, line 24").contains("adGo(event,'OrderService',24)"));
        assertTrue(ActivityPage.links("see OrderService.java:23 and OrderController.quote:21").contains("adGo(event,'OrderController',21)"));
        assertTrue(ActivityPage.links("at org.acme.OrderService.quote:24").contains("adGo(event,'org.acme.OrderService',24)"));
        assertTrue(ActivityPage.links("OrderService.quote line 24").contains(">OrderService.quote line 24</a>"));
        for (String plain : List.of("Thread: http-nio-127.0.0.1-auto-1-exec-1 (index 3)", "HTTP 200", "Body: {&quot;total&quot;:120.00}",
                "started 19:59:56", "localhost:8080"))
            assertEquals(plain, ActivityPage.links(plain), "no link in: " + plain);
        assertEquals("<div class=\"ad-h\"><a class=\"src\" href=\"#\" title=\"Open in the editor\" onclick=\"return adGo(event,'Main',5)\">Main:5</a></div>",
                ActivityPage.links("<div class=\"ad-h\">Main:5</div>"));
        String once = ActivityPage.links("Main:5");
        assertEquals(once, ActivityPage.links(once), "an existing link is not linked again");
    }

    @Test @DisplayName("AP-U8 program state from Eclipse: paused (location in the banner), the agent looking at it, a warning when it ended without a stop")
    void apU8() {
        ActivityStore store = started("Why?");
        assertNull(ActivityPage.programLine(store.current()), "nothing before the launch");
        step(store, "stepRecorded", "LAUNCH", "OK", "Started application in debug mode: OrdersApplication");
        store.program(ActivityStore.PROGRAM_PAUSED, "OrderService.quote:24");
        assertEquals("Program: <b>paused</b>", ActivityPage.programLine(store.current()),
                "where it is paused is in the banner now (OPEN-49)");
        assertTrue(ActivityPage.banner(store.current()).contains("Paused in</span><a class=\"src\""));
        assertEquals("The agent is looking at the paused program", ActivityPage.statusLine(store.current()));
        store.program(ActivityStore.PROGRAM_ENDED, null);
        assertEquals("Program: <b>ended</b>", ActivityPage.programLine(store.current()));
        assertTrue(ActivityPage.warnings(store.current()).contains("ended without stopping at a breakpoint"));
        step(store, "stepRecorded", "STOP", "OK", "Stopped at breakpoint: OrderService.quote, line 24");
        assertEquals("", ActivityPage.warnings(store.current()), "it did stop");
    }

    @Test @DisplayName("AP-U9 status bar line (the notification popup at the end was removed, OPEN-52)")
    void apU9() {
        ActivityStore store = started("Why?");
        step(store, "stepRecorded", "LAUNCH", "OK", "Started application in debug mode: OrdersApplication");
        assertNull(ActivityPage.statusBar(null, START));
        assertEquals("Agent: running · 5.0 s", ActivityPage.statusBar(store.current(), START + 5_400));
        step(store, "stepStarted", "STOP", "RUNNING", "Waiting for the program to stop");
        assertEquals("Agent: waiting for a stop · 1 min 05 s", ActivityPage.statusBar(store.current(), START + 65_000));
        finish(store, "FINISHED", "## Symptom\n**POST /orders/quote** returned `120.00`; expected 108.00.");
        assertEquals("Agent: finished · 45.0 s", ActivityPage.statusBar(store.current(), START + 999_000));
    }

    @Test @DisplayName("AP-U10 the stop keeps the frame's variables when the agent expands one of them afterwards")
    void apU10() {
        ActivityStore store = started("Why?");
        step(store, "stepRecorded", "STOP", "OK", "Stopped at breakpoint: OrderService.quote, line 24",
                stopDetails("OrderService.quote:24  <  OrderController.quote:21",
                        table("quantity", "int", "2", "unitPrice", "BigDecimal", " (id=75)", "rate", "int", "0")));
        List<String> fields = new ArrayList<>(table("intCompact", "long", "60", "scale", "int", "0"));
        fields.add(ReportFormat.TECHNICAL_DETAILS);
        fields.add("Tool: expand_frame_variable");
        step(store, "stepRecorded", "INSPECT", "OK", "Expanded variable: unitPrice", fields);
        ActivityPage.Stop stop = ActivityPage.currentStop(store.current());
        assertEquals(List.of("quantity", "unitPrice", "rate"), stop.variables().stream().map(ActivityPage.Variable::name).toList(),
                "the frame's variables, not the fields of unitPrice (trial 25 Nov 09:47)");
        finish(store, "FINISHED", "Done.");
        String report = ActivityPage.report(store.current(), 0, KOLKATA, "Agentic Debugger for Eclipse");
        assertTrue(report.contains("data-i=\"stop\" data-d=\"1\" open>"), "the last stop is open in the report");
        assertTrue(report.contains("<td>rate</td>"), "with the frame's variables");
    }

    @Test @DisplayName("AP-U11 the Agent details block: agent by its name, model/skill/effort marked as the agent's own statement, cleanup in plain words (OPEN-41)")
    void apU11() {
        ActivityStore store = new ActivityStore();
        store.accept(enc(org.learningfromexperience.agentdebug.report.ActivityEvents.json(
                new org.learningfromexperience.agentdebug.report.ReportEvent.InvestigationStarted("3b80cb03-1111",
                        java.time.Instant.parse("2026-11-24T10:00:00Z"), "NORMAL", "claude-code 2.1.3", "Why?", false,
                        "eclipse-debugger 0.1.0", "claude-haiku-5-5", null))));
        String head = ActivityPage.head(store.current(), KOLKATA);
        assertTrue(head.contains("<details class=\"dby\"><summary><span class=\"dby-h\">Agent details</span><span class=\"facts\">"
                + " <b class=\"fa\">Claude Code</b> \u00B7 <span class=\"fm\">claude-haiku-5-5</span> \u00B7 <span class=\"fs\">"
                + "eclipse-debugger 0.1.0</span></span><span class=\"tog\">"),
                "folded to one line in the window, the full block a click away: " + head);
        assertTrue(ActivityPage.report(store.current(), 0, KOLKATA, "P").contains("<details class=\"dby\" open>"),
                "open in the report");
        assertTrue(head.contains("<span class=\"k\">Agent</span><span class=\"v\"><b>Claude Code</b> <span class=\"ver\">2.1.3</span>"),
                "the client ID becomes the agent's name: " + head);
        assertTrue(head.contains("<span class=\"k\">Model</span><span class=\"v\"><b>claude-haiku-5-5</b> <span class=\"said\""), head);
        assertTrue(head.contains("<b>eclipse-debugger</b> <span class=\"ver\">0.1.0</span> <span class=\"said\""),
                "the skill name and its version apart: " + head);
        assertTrue(head.contains("<span class=\"k\">Effort</span><span class=\"v none\">not stated</span>"), head);
        assertTrue(head.contains("Reported by the agent itself; Eclipse cannot verify it"), head);
        assertTrue(head.contains("<b>After it finishes:</b> the agent&#39;s own breakpoints are removed")
                || head.contains("<b>After it finishes:</b> the agent's own breakpoints are removed"), head);
        assertFalse(head.contains("NORMAL"), "no mode names on the page: " + head);
        String md = ActivityPage.markdown(store.current(), KOLKATA);
        assertTrue(md.contains("**Agent details:** Claude Code 2.1.3 · Model claude-haiku-5-5 \u25C6 · Skill eclipse-debugger 0.1.0 \u25C6"
                + " · Effort not stated"), md);
        assertTrue(md.contains("**Investigation ID:** 3b80cb03"), md);

        String bare = ActivityPage.head(started("Why?").current(), KOLKATA);
        assertTrue(bare.contains("<span class=\"v none\">none reported</span>"), "without a skill: " + bare);
        assertFalse(bare.contains("dby-key"), "nothing stated, so no key: " + bare);

        assertEquals("Cursor", ActivityPage.agent("cursor 1.0")[0]);
        assertEquals("some-agent", ActivityPage.agent("some-agent 3")[0], "unknown clients as they are");
        // OPEN-23: what GitHub Copilot for Eclipse sends as its MCP client name and version
        String[] copilot = ActivityPage.agent("Eclipse/copilot-eclipse 4.41.0.v20260828-1142/0.22.0.202611251609_nightly");
        assertEquals("GitHub Copilot", copilot[0]);
        assertEquals("0.22.0 (nightly)", copilot[1]);
        assertEquals("0.21.0", ActivityPage.agent("Eclipse/copilot-eclipse 4.41.0/0.21.0.202608060743")[1]);
        assertEquals("unknown agent", ActivityPage.agent(null)[0]);
        assertTrue(ActivityPage.afterFinish("CONTROLLED").contains("kept, so you can inspect them"));
    }

    @Test @DisplayName("AP-U12 a colour per phase (OPEN-48): thinking before the launch and after the program ended, debugging while it runs, finished, ended")
    void apU12() {
        assertNull(ActivityPage.phase(null));
        ActivityStore store = started("Why?");
        assertEquals(ActivityPage.PHASE_THINKING, ActivityPage.phase(store.current()), "nothing launched yet");
        String banner = ActivityPage.banner(store.current());
        assertTrue(banner.contains("class=\"banner running thinking\""), banner);
        assertTrue(banner.contains("<span class=\"phase\">Thinking</span>"), "the phase in words, not only by colour");
        assertEquals("Agent: thinking · 3.0 s", ActivityPage.statusBar(store.current(), START + 3_000));
        step(store, "stepRecorded", "BREAKPOINT", "OK", "Set breakpoint: OrderService, line 23");
        assertEquals(ActivityPage.PHASE_THINKING, ActivityPage.phase(store.current()), "a breakpoint is still preparation");
        step(store, "stepStarted", "LAUNCH", "RUNNING", "Starting JUnit test in debug mode");
        assertEquals(ActivityPage.PHASE_DEBUGGING, ActivityPage.phase(store.current()), "the launch is under way");
        step(store, "stepRecorded", "LAUNCH", "OK", "Started JUnit test in debug mode: OrderServiceTest.calculatesDiscount");
        assertEquals(ActivityPage.PHASE_DEBUGGING, ActivityPage.phase(store.current()), "launched, Eclipse has not reported yet");
        store.program(ActivityStore.PROGRAM_PAUSED, "OrderService.quote:23");
        banner = ActivityPage.banner(store.current());
        assertTrue(banner.contains("class=\"banner running debugging\""), banner);
        assertTrue(banner.contains("<span class=\"phase\">Debugging</span>"));
        store.program(ActivityStore.PROGRAM_ENDED, null);
        assertEquals(ActivityPage.PHASE_THINKING, ActivityPage.phase(store.current()), "the agent writes its answer");
        finish(store, "FINISHED", null);
        assertEquals(ActivityPage.PHASE_FINISHED, ActivityPage.phase(store.current()));
        assertFalse(ActivityPage.banner(store.current()).contains("class=\"phase\""), "no phase label at the end");
        ActivityStore ended = started("Why?");
        finish(ended, "ENDED_WITHOUT_FINISH", null);
        assertEquals(ActivityPage.PHASE_ENDED, ActivityPage.phase(ended.current()));
    }

    @Test @DisplayName("AP-U13 the banner shows where the program is (OPEN-49): paused location, else the last stop; none before a stop, after the end or the finish")
    void apU13() {
        ActivityStore store = started("Why?");
        step(store, "stepRecorded", "LAUNCH", "OK", "Started JUnit test in debug mode: OrderServiceTest.calculatesDiscount");
        assertNull(ActivityPage.where(store.current()), "no stop yet");
        assertFalse(ActivityPage.banner(store.current()).contains("class=\"where\""));
        step(store, "stepRecorded", "STOP", "OK", "Stopped at breakpoint: OrderService.quote, line 23",
                stopDetails("OrderService.quote:23  <  OrderServiceTest.calculatesDiscount:19", table("percent", "int", "10")));
        assertEquals("OrderService.quote:23", ActivityPage.where(store.current()), "from the stop");
        store.program(ActivityStore.PROGRAM_PAUSED, "OrderService.quote:24");
        assertEquals("OrderService.quote:24", ActivityPage.where(store.current()), "Eclipse's paused location, after a step");
        String banner = ActivityPage.banner(store.current());
        assertTrue(banner.contains("<span class=\"wl\">Paused in</span><a class=\"src\""), banner);
        assertTrue(banner.contains("adGo(event,'OrderService',24)"), "a link to the line");
        store.program(ActivityStore.PROGRAM_RUNNING, null);
        assertTrue(ActivityPage.banner(store.current()).contains("<span class=\"wl\">Last stop</span>"), "resumed: the last stop");
        store.program(ActivityStore.PROGRAM_ENDED, null);
        assertNull(ActivityPage.where(store.current()), "the program ended");
        finish(store, "FINISHED", null);
        assertNull(ActivityPage.where(store.current()), "finished: the report shows the last stop");
    }

    @Test @DisplayName("AP-U14 Stop and clean up (OPEN-62) only while it runs; 'Stopped by you'; banner text keeps a readable width (no word per line)")
    void apU14() {
        ActivityStore store = started("Why?");
        assertTrue(ActivityPage.tools(store.current()).contains("adCall('stop')"), "while it runs");
        store.stoppedByUser(List.of("breakpoints removed"));
        assertFalse(ActivityPage.tools(store.current()).contains("adCall('stop')"), "not after the end");
        assertTrue(ActivityPage.banner(store.current()).contains("Stopped by you"));
        assertTrue(ActivityPage.statusBar(store.current(), START + 3_000).startsWith("Agent: stopped by you"));
        assertEquals(ActivityPage.PHASE_ENDED, ActivityPage.phase(store.current()));
        String page = ActivityPage.shell(false);
        assertTrue(page.contains(".banner .what{flex:1 1 14em;min-width:14em}"), "the location wraps, not the text");
    }
}
