package org.learningfromexperience.agentdebug.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests (JUnit 5) for {@link CompositeReportRecorder} and the new {@link ReportEvent.StepStarted} (OPEN-36): the
 * HTML report and the live Agent Activity view get the same events, each through its own recorder, and one consumer
 * that is off, failing or stuck never affects the other. No Eclipse needed; run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class CompositeReportRecorderTest {

    private final PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    /** Collects the events it receives; optionally fails or waits on every call. */
    private static final class Collecting implements DebugReportListener {
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        boolean fail;
        @Override public void investigationStarted(ReportEvent.InvestigationStarted e) { note("start"); }
        @Override public void stepStarted(ReportEvent.StepStarted e) { note("started:" + e.title()); }
        @Override public void stepRecorded(ReportEvent.StepRecorded e) { note("step:" + e.title()); }
        @Override public void investigationFinished(ReportEvent.InvestigationFinished e) { note("finish"); finished.countDown(); }
        private void note(String what) {
            if (fail) throw new IllegalStateException("broken consumer");
            seen.add(what);
        }
    }

    /** A report writer that does not know stepStarted: the interface's default ignores it. */
    private static final class OldWriter implements DebugReportListener {
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        @Override public void investigationStarted(ReportEvent.InvestigationStarted e) { seen.add("start"); }
        @Override public void stepRecorded(ReportEvent.StepRecorded e) { seen.add("step"); }
        @Override public void investigationFinished(ReportEvent.InvestigationFinished e) { seen.add("finish"); finished.countDown(); }
    }

    private static void investigation(ReportRecorder recorder) {
        Instant now = Instant.now();
        recorder.record(new ReportEvent.InvestigationStarted("inv-1", now, "NORMAL", "claude-code"));
        recorder.record(new ReportEvent.StepStarted("inv-1", now, ReportEvent.StepKind.STOP, "Waiting for the program to stop"));
        recorder.record(new ReportEvent.StepRecorded("inv-1", now, ReportEvent.StepKind.STOP, ReportEvent.StepStatus.OK,
                "Stopped at breakpoint: OrderService.quote, line 24", List.of(), List.of(), 12));
        recorder.record(new ReportEvent.InvestigationFinished("inv-1", now, ReportEvent.Outcome.FINISHED, null, List.of()));
    }

    @Test
    @DisplayName("RM-U11 report and live view both get every event, in order; a writer without stepStarted ignores it")
    void rmU11() throws Exception {
        OldWriter report = new OldWriter();
        Collecting live = new Collecting();
        ReportRecorder both = CompositeReportRecorder.of(new AsyncReportRecorder(report, "report", err, 100),
                new AsyncReportRecorder(live, "live", err, 100));
        investigation(both);
        assertTrue(report.finished.await(5, TimeUnit.SECONDS) && live.finished.await(5, TimeUnit.SECONDS));
        both.close(Duration.ofSeconds(5));
        assertEquals(List.of("start", "step", "finish"), report.seen);
        assertEquals(List.of("start", "started:Waiting for the program to stop",
                "step:Stopped at breakpoint: OrderService.quote, line 24", "finish"), live.seen);
    }

    @Test
    @DisplayName("RM-U12 a failing consumer does not affect the other one")
    void rmU12() throws Exception {
        Collecting broken = new Collecting();
        broken.fail = true;
        Collecting live = new Collecting();
        ReportRecorder both = CompositeReportRecorder.of(new AsyncReportRecorder(broken, "broken", err, 100),
                new AsyncReportRecorder(live, "live", err, 100));
        investigation(both);
        assertTrue(live.finished.await(5, TimeUnit.SECONDS), "the healthy consumer must get everything");
        both.close(Duration.ofSeconds(5));
        assertEquals(4, live.seen.size());
        assertTrue(broken.seen.isEmpty());
    }

    @Test
    @DisplayName("RM-U13 parts that are off are left out: none gives off, one gives that part itself")
    void rmU13() {
        assertFalse(CompositeReportRecorder.of(ReportRecorder.off(), null, ReportRecorder.off()).active());
        ReportRecorder single = new AsyncReportRecorder(new Collecting(), "single", err, 10);
        assertSame(single, CompositeReportRecorder.of(ReportRecorder.off(), single));
        single.close(Duration.ofSeconds(2));
    }
}
