package org.learningfromexperience.agentdebug.report.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * JUnit 5 unit tests for {@link SequencePage} (SQ-U1 to SQ-U5, OPEN-65, OPEN-66): lanes, the kind of each move, thinking time,
 * the root cause box, and the page parts. Steps are shaped like the MCP server writes them.
 *
 * @author Ayan Dutta
 */
class SequencePageTest {

    private static ActivityStore.Step step(String kind, String title, String start, String end, List<String> reader, String... tech) {
        List<String> details = new ArrayList<>(reader);
        details.add(ReportFormat.TECHNICAL_DETAILS);
        details.addAll(List.of(tech));
        return new ActivityStore.Step(kind, title, "OK", details, 1000, start, end);
    }

    private static ActivityStore.Step stop(String title, String frames, String start, String end, String... vars) {
        List<String> reader = new ArrayList<>(List.of("Thread: http-nio-exec-1 (index 4)", "Frames: " + frames, "",
                "Variable           Type            Value", "---------------    ------------    -----"));
        for (int i = 0; i < vars.length; i += 3) reader.add(String.format("%-15s    %-12s    %s", vars[i], vars[i + 1], vars[i + 2]));
        return step("STOP", title, start, end, reader, "Tool: wait_for_stop");
    }

    private static final List<ActivityStore.Step> RUN = List.of(
            step("BREAKPOINT", "Set breakpoint: OrderController, line 20", "2026-11-28T00:00:01Z", "2026-11-28T00:00:02Z", List.of()),
            step("LAUNCH", "Started JUnit test in debug mode: OrderApiIntegrationTest.quoteAppliesDiscount",
                    "2026-11-28T00:00:03Z", "2026-11-28T00:00:05Z", List.of()),
            stop("Stopped at breakpoint: OrderController.quote, line 20", "OrderController.quote:20  <  X.dispatch:1",
                    "2026-11-28T00:00:06Z", "2026-11-28T00:00:20Z", "quantity", "int", "2"),
            step("STEP", "Resumed", "2026-11-28T00:00:28Z", "2026-11-28T00:00:29Z", List.of(), "action: RESUME"),
            stop("Stopped at breakpoint: OrderService.quote, line 15", "OrderService.quote:15  <  OrderController.quote:21  <  X.dispatch:1",
                    "2026-11-28T00:00:29Z", "2026-11-28T00:00:30Z", "quantity", "int", "2"),
            step("STEP", "Resumed", "2026-11-28T00:00:35Z", "2026-11-28T00:00:36Z", List.of(), "action: RESUME"),
            stop("Stopped at breakpoint: OrderRepository.findDiscount, line 10",
                    "OrderRepository.findDiscount:10  <  OrderService.quote:22  <  OrderController.quote:21",
                    "2026-11-28T00:00:36Z", "2026-11-28T00:00:37Z", "requestedPercent", "int", "10"),
            step("STEP", "Stepped out (step return)", "2026-11-28T00:00:40Z", "2026-11-28T00:00:41Z", List.of(), "action: STEP_RETURN"),
            stop("Stopped after step: OrderService.quote, line 23", "OrderService.quote:23  <  OrderController.quote:21",
                    "2026-11-28T00:00:41Z", "2026-11-28T00:00:42Z", "percent", "int", "10"),
            step("STEP", "Stepped over", "2026-11-28T00:00:43Z", "2026-11-28T00:00:44Z", List.of(), "action: STEP_OVER"),
            stop("Stopped after step: OrderService.quote, line 24", "OrderService.quote:24  <  OrderController.quote:21",
                    "2026-11-28T00:00:44Z", "2026-11-28T00:00:45Z", "percent", "int", "10", "rate", "int", "0"));

    private static ActivityStore.Investigation inv(List<ActivityStore.Step> steps, boolean finished, String analysis) {
        return inv(steps, finished, analysis, finished ? ActivityStore.PROGRAM_ENDED : ActivityStore.PROGRAM_PAUSED);
    }

    private static ActivityStore.Investigation inv(List<ActivityStore.Step> steps, boolean finished, String analysis, String program) {
        return new ActivityStore.Investigation("inv-sq", "NORMAL", "copilot", "Why 120.00?", true, "2026-11-28T00:00:00Z",
                steps, 0, finished ? "FINISHED" : null, null, List.of(), null, finished ? "2026-11-28T00:01:00Z" : null,
                program, null, "2026-11-28T00:00:45Z", analysis, null, null, null);
    }

    @Test @DisplayName("SQ-U1 one lane per class in order of appearance; the kind of every move from the call stacks")
    void sqU1() {
        SequencePage.Model m = SequencePage.model(inv(RUN, false, null));
        assertEquals(List.of("OrderController", "OrderService", "OrderRepository"), m.lanes());
        assertEquals(5, m.stops().size());
        assertEquals(List.of(SequencePage.Move.ENTRY, SequencePage.Move.CALL, SequencePage.Move.CALL,
                SequencePage.Move.RETURN, SequencePage.Move.STEP), m.edges().stream().map(SequencePage.Edge::move).toList());
        assertEquals("line 23 → 24", m.edges().get(4).label());
        assertEquals("Stepped over", m.edges().get(4).command(), "the agent's command before the stop");
        assertEquals("new: rate = 0", SequencePage.newValues(m, m.stops().get(4)), "a local that appeared since line 23");
        assertEquals("", SequencePage.newValues(m, m.stops().get(1)), "first stop in a method: nothing called new");
    }

    @Test @DisplayName("SQ-U2 thinking time before a move is the sum of the gaps between calls")
    void sqU2() {
        SequencePage.Model m = SequencePage.model(inv(RUN, false, null));
        assertEquals(8_000, m.edges().get(1).thinkingMs(), "20 s -> 28 s");
        assertEquals(5_000, m.edges().get(2).thinkingMs(), "30 s -> 35 s");
    }

    @Test @DisplayName("SQ-U3 the root cause box only at the finish, at the agent's location")
    void sqU3() {
        String analysis = "{\"rootCause\":{\"file\":\"OrderService.java\",\"lines\":\"23\",\"explanation\":\"int division\"}}";
        assertEquals(3, SequencePage.model(inv(RUN, true, analysis)).rootStop());
        assertTrue(SequencePage.live(inv(RUN, true, analysis)).contains("ROOT CAUSE"));
        assertFalse(SequencePage.live(inv(RUN, false, analysis)).contains("ROOT CAUSE"), "not while it runs");
    }

    @Test @DisplayName("SQ-U4 the page parts: boxes, clickable lines and classes, a block per stop, line, class and step, thinking rows; a message before the first stop")
    void sqU4() {
        String html = SequencePage.live(inv(RUN, false, null));
        assertTrue(html.contains("adSq('s4')") && html.contains("adSq('e3')") && html.contains("adSq('c2')"), "clickable");
        assertTrue(html.contains("data-b=\"s0\"") && html.contains("data-b=\"e3\"") && html.contains("data-b=\"c1\"")
                && html.contains("data-b=\"t0\""), "a block per selection");
        assertTrue(html.contains("INFERRED"), "back in the caller is marked inferred");
        assertTrue(html.contains("Agent thinking · 8.0 s") || html.contains("Agent thinking · 8 s"), html);
        assertTrue(html.contains("sqbox cur"), "the paused stop glows");
        assertTrue(SequencePage.live(inv(RUN.subList(0, 2), false, null)).contains("No pause yet"));
    }

    @Test @DisplayName("SQ-U5 OPEN-66: 'paused' wording, a colour per class, a colour per kind of line, and the waiting period")
    void sqU5() {
        ActivityStore.Step timedOut = step("STOP", "Waiting for a breakpoint: none reached yet (program running)",
                "2026-11-28T00:00:05Z", "2026-11-28T00:00:05Z", List.of("No stop: TIMEOUT"), "Tool: wait_for_stop");
        // Before the first pause: the program runs, a wait ran out; the whole diagram is the waiting box.
        List<ActivityStore.Step> before = new ArrayList<>(RUN.subList(0, 2));
        before.add(timedOut);
        String waiting = SequencePage.live(inv(before, false, null, ActivityStore.PROGRAM_RUNNING));
        assertTrue(waiting.contains("Waiting for the first breakpoint"), waiting);
        assertTrue(waiting.contains("class=\"sqev wait now\""), "the timed-out wait is an amber row, the newest one");

        // The new titles ("Paused ..."), the wait before the first pause, then resumed: waiting for the next pause.
        List<ActivityStore.Step> run = new ArrayList<>();
        for (ActivityStore.Step st : RUN)
            run.add(new ActivityStore.Step(st.kind(), st.title().replace("Stopped", "Paused"), st.status(), st.details(),
                    st.durationMs(), st.startedAt(), st.finishedAt()));
        run.add(2, timedOut);
        String html = SequencePage.live(inv(run, false, null, ActivityStore.PROGRAM_RUNNING));
        assertEquals(5, SequencePage.model(inv(run, false, null)).stops().size(), "'Paused at breakpoint' is a pause");
        assertTrue(html.contains("Where the program paused") && html.contains("data-t=\"Pause 1 of 5\""), "paused, not stopped");
        assertFalse(html.contains(">Stop ") || html.contains("program stopped"), "no 'stop' for a pause");
        assertTrue(html.contains("--cc:#2563eb") && html.contains("--cc:#db2777") && html.contains("--cc:#d97706"),
                "a colour per class: columns, boxes, timeline rows");
        assertTrue(html.contains("class=\"sqev stop\" style=\"--cc:#db2777"), "a pause row in its class colour");
        assertTrue(html.contains("url(#sqas)") && html.contains("url(#sqar)") && html.contains("url(#sqae)")
                && html.contains("url(#sqa)"), "an arrowhead per kind of line");
        assertTrue(html.contains("first pause") && html.contains("class=\"sqleg\""), "legend in the line colours");
        assertTrue(html.contains("for the first pause"), "how long it waited before the first pause");
        assertTrue(html.contains("waiting for the next pause or the end"), "resumed: running until the next pause");
        assertFalse(SequencePage.live(inv(run, false, null)).contains("waiting for the next pause"), "not while paused");
        assertEquals(SequencePage.colour(0), SequencePage.colour(SequencePage.CLASS_COLOURS.length), "colours repeat");
    }
}
