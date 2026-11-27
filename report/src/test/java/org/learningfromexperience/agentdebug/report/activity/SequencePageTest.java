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
 * JUnit 5 unit tests for {@link SequencePage} (SQ-U1 to SQ-U4, OPEN-65): lanes, the kind of each move, thinking time,
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
        return new ActivityStore.Investigation("inv-sq", "NORMAL", "copilot", "Why 120.00?", true, "2026-11-28T00:00:00Z",
                steps, 0, finished ? "FINISHED" : null, null, List.of(), null, finished ? "2026-11-28T00:01:00Z" : null,
                finished ? ActivityStore.PROGRAM_ENDED : ActivityStore.PROGRAM_PAUSED, null, "2026-11-28T00:00:45Z",
                analysis, null, null, null);
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
        assertTrue(SequencePage.live(inv(RUN.subList(0, 2), false, null)).contains("No stop yet"));
    }
}
