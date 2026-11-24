package org.learningfromexperience.agentdebug.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for the ADR-009 isolation rules of the report layer (OPEN-30): a slow, failing, missing or
 * stuck report writer never blocks, changes or breaks the code that records events. No Eclipse needed; run by
 * {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class AsyncReportRecorderTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void captureStdout() {
        // stdout carries the MCP protocol in the MCP server: the report layer must never write to it.
        originalOut = System.out;
        System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8), "the report layer wrote to stdout");
    }

    private static ReportEvent step(int n) {
        return new ReportEvent.StepRecorded("inv-1", Instant.now(), ReportEvent.StepKind.OTHER,
                ReportEvent.StepStatus.OK, "step " + n, List.of(), List.of(), 1);
    }

    private static ReportSettings settings(String writer) {
        return new ReportSettings(writer, Path.of("target", "unused"), "test");
    }

    /** Writer that records what it receives, optionally slowly or by failing. */
    private static class FakeWriter implements DebugReportListener {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong droppedReported = new AtomicLong(-1);
        long sleepMillis;
        boolean fail;
        CountDownLatch gate;

        private void work(String call) throws Exception {
            if (gate != null) gate.await();
            if (sleepMillis > 0) Thread.sleep(sleepMillis);
            if (fail) throw new IllegalStateException("writer broken");
            calls.add(call);
        }

        @Override public void investigationStarted(ReportEvent.InvestigationStarted e) throws Exception { work("start " + e.investigationId()); }
        @Override public void stepRecorded(ReportEvent.StepRecorded e) throws Exception { work(e.title()); }
        @Override public void investigationFinished(ReportEvent.InvestigationFinished e) throws Exception { work("finish " + e.outcome()); }
        @Override public void eventsDropped(long count) { droppedReported.set(count); }
        @Override public void close() { calls.add("close"); }
    }

    private static ReportRecorders.WriterFactory factory(DebugReportListener writer) {
        return settings -> writer;
    }

    @Test
    @DisplayName("RM-U1 a writer that takes 5 s per event does not slow down recording, and close keeps its time limit")
    void rmU1() {
        FakeWriter slow = new FakeWriter();
        slow.sleepMillis = 5_000;
        AsyncReportRecorder recorder = new AsyncReportRecorder(slow, "slow", err, 1_000);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) recorder.record(step(i));
        long recordMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(recordMillis < 500, "recording 100 events took " + recordMillis + " ms");

        start = System.nanoTime();
        recorder.close(Duration.ofMillis(300));
        long closeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(closeMillis < 2_000, "close took " + closeMillis + " ms despite a 300 ms limit");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("did not finish"), errBytes::toString);
    }

    @Test
    @DisplayName("RM-U2 a writer that always throws never reaches the caller and is disabled after 5 failures")
    void rmU2() {
        FakeWriter broken = new FakeWriter();
        broken.fail = true;
        AsyncReportRecorder recorder = new AsyncReportRecorder(broken, "broken", err, 1_000);
        for (int i = 0; i < 10; i++) recorder.record(step(i));   // must not throw
        recorder.close(Duration.ofSeconds(5));
        assertTrue(recorder.disabled(), "writer should be disabled");
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("disabled after 5 failures"), errBytes::toString);
        assertFalse(broken.calls.contains("close"), "a disabled writer is not called again");
    }

    @Test
    @DisplayName("RM-U3 a full queue drops and counts events instead of blocking, and the writer is told the count")
    void rmU3() throws Exception {
        FakeWriter stuck = new FakeWriter();
        stuck.gate = new CountDownLatch(1);
        AsyncReportRecorder recorder = new AsyncReportRecorder(stuck, "stuck", err, 5);
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) recorder.record(step(i));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "recording blocked on a full queue");
        long dropped = recorder.droppedEvents();
        assertTrue(dropped >= 44, "expected at least 44 dropped events, got " + dropped);
        stuck.gate.countDown();
        recorder.close(Duration.ofSeconds(5));
        assertEquals(dropped, stuck.droppedReported.get(), "writer must be told how many events were dropped");
        assertTrue(stuck.calls.contains("close"));
    }

    @Test
    @DisplayName("RM-U4 reporting off gives the no-op recorder without any message")
    void rmU4() {
        ReportRecorder recorder = ReportRecorders.create(settings("off"), err, factory(new FakeWriter()), 10);
        assertSame(ReportRecorder.off(), recorder);
        assertFalse(recorder.active());
        recorder.record(step(1));
        recorder.close();
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
        assertSame(ReportRecorder.off(), ReportRecorders.create(settings(" "), err, factory(new FakeWriter()), 10), "blank means off");
    }

    @Test
    @DisplayName("RM-U5 an unknown writer gives the no-op recorder and one warning naming what is available")
    void rmU5() {
        ReportRecorder recorder = ReportRecorders.create(settings("pdf"), err, factory(new FakeWriter()), 10);
        assertSame(ReportRecorder.off(), recorder);
        String warning = errBytes.toString(StandardCharsets.UTF_8);
        assertTrue(warning.contains("'pdf' not found") && warning.contains("html"), warning);
    }

    @Test
    @DisplayName("RM-U6 a writer whose creation throws gives the no-op recorder and a warning")
    void rmU6() {
        ReportRecorders.WriterFactory failing = s -> { throw new java.io.IOException("report folder not writable"); };
        assertSame(ReportRecorder.off(), ReportRecorders.create(settings("html"), err, failing, 10));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("could not start"), errBytes::toString);
    }

    @Test
    @DisplayName("RM-U7 a working writer receives every event in order, then close once; the old setting name still works")
    void rmU7() {
        FakeWriter writer = new FakeWriter();
        // "chaintest" was the setting of the earlier writer; it now means the HTML writer.
        ReportRecorder recorder = ReportRecorders.create(settings("ChainTest"), err, factory(writer), 100);
        assertTrue(recorder.active());
        recorder.record(new ReportEvent.InvestigationStarted("inv-1", Instant.now(), "NORMAL", "test"));
        recorder.record(step(1));
        recorder.record(step(2));
        recorder.record(new ReportEvent.InvestigationFinished("inv-1", Instant.now(), ReportEvent.Outcome.FINISHED, "done", null));
        recorder.close(Duration.ofSeconds(5));
        recorder.record(step(3));   // after close: ignored
        assertEquals(List.of("start inv-1", "step 1", "step 2", "finish FINISHED", "close"), writer.calls);
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("RM-U8 secret-looking names are masked and long values shortened before they enter a report")
    void rmU8() {
        assertEquals(Redaction.MASK, Redaction.value("dbPassword", "hunter2"));
        assertEquals(Redaction.MASK, Redaction.value("Authorization", "Bearer abc"));
        assertEquals(Redaction.MASK, Redaction.value("api_key", "k"));
        assertEquals("108.00", Redaction.value("total", "108.00"));
        assertEquals("null", Redaction.value("x", null));
        String longValue = Redaction.value("body", "x".repeat(1_000));
        assertTrue(longValue.startsWith("x".repeat(Redaction.MAX_VALUE_LENGTH)) && longValue.endsWith("(700 more characters)"),
                longValue);
    }
}
