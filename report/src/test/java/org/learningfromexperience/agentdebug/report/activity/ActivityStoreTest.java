package org.learningfromexperience.agentdebug.report.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for {@link ActivityStore} (AS-U1 to AS-U6, AS-U8 and AS-U9, OPEN-36; the page tests that were
 * AS-U7 are in {@code ActivityPageTest}).
 * No Eclipse needed: run by {@code mvn clean package}. The events have the format the MCP server's
 * {@code ActivityForwarder} sends.
 *
 * @author Ayan Dutta
 */
class ActivityStoreTest {

    private static final String OK = "{\"status\":\"success\"}";

    private static String enc(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String started(String id) {
        return enc("{\"type\":\"investigationStarted\",\"investigationId\":\"" + id + "\",\"at\":\"2026-11-24T10:00:00Z\","
                + "\"mode\":\"symptom\",\"client\":\"claude-code\",\"request\":\"Why does /quote fail?\",\"requestExact\":true}");
    }

    private static String stepStarted(String id, String title) {
        return enc("{\"type\":\"stepStarted\",\"investigationId\":\"" + id + "\",\"at\":\"2026-11-24T10:00:01Z\","
                + "\"kind\":\"BREAKPOINT\",\"title\":\"" + title + "\"}");
    }

    private static String stepRecorded(String id, String title, String status) {
        return enc("{\"type\":\"stepRecorded\",\"investigationId\":\"" + id + "\",\"at\":\"2026-11-24T10:00:02Z\","
                + "\"kind\":\"BREAKPOINT\",\"status\":\"" + status + "\",\"title\":\"" + title + "\","
                + "\"details\":[\"line 42\",\"--- technical details ---\"],\"durationMs\":120}");
    }

    private static String finished(String id) {
        return enc("{\"type\":\"investigationFinished\",\"investigationId\":\"" + id + "\",\"at\":\"2026-11-24T10:01:00Z\","
                + "\"outcome\":\"FINISHED\",\"conclusion\":\"Null price\",\"cleanup\":[\"removed 1 breakpoint\"],"
                + "\"reportFile\":null}");
    }

    @Test @DisplayName("AS-U1 a whole investigation is kept: request, steps, conclusion")
    void asU1() {
        ActivityStore store = new ActivityStore();
        assertEquals(OK, store.accept(started("inv-1")));
        assertEquals(OK, store.accept(stepStarted("inv-1", "Set breakpoint")));
        assertEquals(OK, store.accept(stepRecorded("inv-1", "Breakpoint set at Quote:42", "OK")));
        assertEquals(OK, store.accept(finished("inv-1")));

        ActivityStore.Investigation inv = store.current();
        assertEquals("Why does /quote fail?", inv.request());
        assertTrue(inv.requestExact());
        assertEquals("claude-code", inv.client());
        assertEquals(1, inv.steps().size(), "the result must replace the running step, not add a second one");
        ActivityStore.Step step = inv.steps().get(0);
        assertEquals("OK", step.status());
        assertEquals("Breakpoint set at Quote:42", step.title());
        assertEquals(List.of("line 42", "--- technical details ---"), step.details());
        assertEquals(120, step.durationMs());
        assertEquals("2026-11-24T10:00:01Z", step.startedAt(), "the start time of the running step is kept");
        assertTrue(inv.finished());
        assertEquals("Null price", inv.conclusion());
        assertEquals(List.of("removed 1 breakpoint"), inv.cleanup());
        assertNull(inv.reportFile());
    }

    @Test @DisplayName("AS-U2 a running step is shown as running until its result arrives")
    void asU2() {
        ActivityStore store = new ActivityStore();
        store.accept(started("inv-1"));
        store.accept(stepStarted("inv-1", "Wait for stop"));
        ActivityStore.Investigation inv = store.current();
        assertTrue(inv.steps().get(0).running());
        assertFalse(inv.finished());
        assertEquals("Running: Wait for stop", ActivityPage.statusLine(inv));
    }

    @Test @DisplayName("AS-U3 a step still running at the end is closed as UNKNOWN")
    void asU3() {
        ActivityStore store = new ActivityStore();
        store.accept(started("inv-1"));
        store.accept(stepStarted("inv-1", "Wait for stop"));
        store.accept(finished("inv-1"));
        ActivityStore.Step step = store.current().steps().get(0);
        assertEquals("UNKNOWN", step.status());
        assertFalse(step.running());
    }

    @Test @DisplayName("AS-U8 a successful finish completes its running cleanup step as OK; ended without finish does not")
    void asU8() {
        ActivityStore store = new ActivityStore();
        store.accept(started("inv-1"));
        store.accept(enc("{\"type\":\"stepStarted\",\"investigationId\":\"inv-1\",\"at\":\"2026-11-24T10:00:59Z\","
                + "\"kind\":\"CLEANUP\",\"title\":\"Finishing the investigation and cleaning up\"}"));
        store.accept(finished("inv-1"));
        ActivityStore.Step step = store.current().steps().get(0);
        assertEquals("OK", step.status());
        assertEquals(ActivityStore.FINISH_TITLE, step.title());
        assertEquals(List.of("removed 1 breakpoint"), step.details(), "the cleanup lines are the step's details");
        assertEquals(1000, step.durationMs(), "from the step start to the finish");

        store.accept(started("inv-2"));
        store.accept(enc("{\"type\":\"stepStarted\",\"investigationId\":\"inv-2\",\"at\":\"2026-11-24T10:00:59Z\","
                + "\"kind\":\"CLEANUP\",\"title\":\"Finishing the investigation and cleaning up\"}"));
        store.accept(enc("{\"type\":\"investigationFinished\",\"investigationId\":\"inv-2\",\"at\":\"2026-11-24T10:01:00Z\","
                + "\"outcome\":\"ENDED_WITHOUT_FINISH\",\"conclusion\":null,\"cleanup\":[],\"reportFile\":null}"));
        assertEquals("UNKNOWN", store.investigations().get(1).steps().get(0).status());
    }

    @Test @DisplayName("AS-U4 malformed events are answered with an error and change nothing")
    void asU4() {
        ActivityStore store = new ActivityStore();
        for (String bad : new String[] {null, "!!not base64!!", enc("not json"), enc("{\"investigationId\":\"x\"}"),
                enc("{\"type\":\"stepStarted\"}"), enc("{\"type\":\"somethingNew\",\"investigationId\":\"x\"}")}) {
            String reply = store.accept(bad);
            assertTrue(reply.startsWith("{\"status\":\"error\""), "must be an error: " + reply);
        }
        assertTrue(store.investigations().isEmpty());
        assertNull(store.current());
    }

    @Test @DisplayName("AS-U5 events of an investigation whose start was missed are still shown")
    void asU5() {
        ActivityStore store = new ActivityStore();
        assertEquals(OK, store.accept(stepRecorded("late", "Inspected variables", "OK")));
        ActivityStore.Investigation inv = store.current();
        assertEquals("late", inv.id());
        assertEquals(1, inv.steps().size());
        store.accept(enc("{\"type\":\"eventsDropped\",\"count\":3}"));
        assertEquals(3, store.droppedEvents());
    }

    @Test @DisplayName("AS-U6 bounded: oldest finished investigations and oldest steps are dropped; listeners notified")
    void asU6() {
        ActivityStore store = new ActivityStore();
        List<String> notified = new ArrayList<>();
        store.addListener(notified::add);
        store.addListener(id -> { throw new IllegalStateException("broken listener"); });
        store.accept(started("open"));
        for (int i = 0; i < ActivityStore.MAX_INVESTIGATIONS + 5; i++) {
            store.accept(started("done-" + i));
            store.accept(finished("done-" + i));
        }
        assertEquals(ActivityStore.MAX_INVESTIGATIONS, store.investigations().size());
        assertEquals("open", store.current().id(), "the open investigation is kept and shown");
        for (int i = 0; i < ActivityStore.MAX_STEPS + 10; i++) store.accept(stepRecorded("open", "step " + i, "OK"));
        assertEquals(ActivityStore.MAX_STEPS, store.current().steps().size());
        assertEquals(10, store.current().droppedSteps());
        assertTrue(notified.contains("open"), "listeners are notified, a broken one does not stop the others");
    }

    @Test @DisplayName("AS-U9 program state goes to the open investigation; after the finish only 'ended'; last activity and revision follow every change")
    void asU9() {
        ActivityStore store = new ActivityStore();
        List<String> notified = new ArrayList<>();
        store.addListener(notified::add);
        store.program(ActivityStore.PROGRAM_RUNNING, null);
        assertTrue(notified.isEmpty(), "no open investigation: ignored");
        store.accept(started("inv-1"));
        assertTrue(store.current().lastActivity() != null, "receive time of the last event");
        assertNull(store.current().program());
        store.program(ActivityStore.PROGRAM_PAUSED, "OrderService.quote:24");
        store.program(ActivityStore.PROGRAM_PAUSED, "OrderService.quote:24");
        assertEquals(List.of("inv-1", "inv-1"), notified, "one notification for the start, one for the change, none for a repeat");
        assertEquals(2, store.revision(), "the view's catch-up counter grows with every change, not with a repeat");
        assertEquals("OrderService.quote:24", store.current().programWhere());
        store.program(ActivityStore.PROGRAM_RUNNING, "ignored");
        assertNull(store.current().programWhere(), "a location only while paused");
        store.accept(finished("inv-1"));
        store.program(ActivityStore.PROGRAM_PAUSED, "Elsewhere.main:3");
        assertEquals(ActivityStore.PROGRAM_RUNNING, store.current().program(), "after the finish, running or paused is not applied");
        store.program(ActivityStore.PROGRAM_ENDED, null);
        assertEquals(ActivityStore.PROGRAM_ENDED, store.current().program(),
                "the program ending after the finish (its cleanup) is shown (25 Nov 21:30, Eclipse 2025-12)");
    }

    @Test @DisplayName("AS-U10 stopped by the user (OPEN-62): the open investigation ends as STOPPED with the cleanup; the MCP server's later end does not overwrite it")
    void asU10() {
        ActivityStore store = new ActivityStore();
        assertNull(store.stoppedByUser(List.of("x")), "nothing open: nothing stopped");
        assertEquals(OK, store.accept(started("inv-1")));
        assertEquals(OK, store.accept(stepStarted("inv-1", "Waiting for a stop")));
        assertEquals("inv-1", store.stoppedByUser(List.of("breakpoints removed")));
        ActivityStore.Investigation inv = store.current();
        assertTrue(inv.finished());
        assertEquals(ActivityStore.STOPPED, inv.outcome());
        assertEquals(List.of("breakpoints removed"), inv.cleanup());
        assertFalse(inv.steps().get(0).running(), "the running step is closed");
        assertEquals(OK, store.accept(finished("inv-1")));
        assertEquals(ActivityStore.STOPPED, store.current().outcome());
    }

    @Test @DisplayName("AS-U11 expired in Eclipse (OPEN-62): only the named open investigation ends as EXPIRED; a later finish does not overwrite it")
    void asU11() {
        ActivityStore store = new ActivityStore();
        assertEquals(OK, store.accept(started("inv-1")));
        assertFalse(store.expired("other", "x"), "another ID changes nothing");
        assertFalse(store.current().finished());
        assertTrue(store.expired("inv-1", "expired after 600 s"));
        assertEquals(ActivityStore.EXPIRED, store.current().outcome());
        assertFalse(store.expired("inv-1", "again"), "only once");
        assertEquals(OK, store.accept(finished("inv-1")));
        assertEquals(ActivityStore.EXPIRED, store.current().outcome());
    }
}
