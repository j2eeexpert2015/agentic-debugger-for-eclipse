package org.learningfromexperience.agentdebug.report;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers report events to a writer on one background thread (ADR-009).
 *
 * <p><b>Runtime isolation.</b> {@link #record} only offers the event to a bounded queue and returns: it never waits,
 * whatever the writer does. When the queue is full the event is dropped and counted; the writer is told the count
 * before it closes, so the report can say it is incomplete. Rendering happens only on the background thread.
 *
 * <p><b>Failure isolation.</b> Every writer call runs inside a catch-all on the background thread, so nothing a
 * writer throws can reach the caller. Errors are reported on {@code err} (stderr in the MCP server; never stdout,
 * which carries the MCP protocol). After {@value #MAX_CONSECUTIVE_FAILURES} failures in a row the writer is disabled
 * for the rest of the session and later events are discarded.
 *
 * <p><b>Shutdown.</b> {@link #close(Duration)} lets the thread write what is queued, then calls the writer's
 * {@code close()}, waiting at most the given time; a writer that hangs is abandoned (the thread is a daemon).
 *
 * @author Ayan Dutta
 */
public final class AsyncReportRecorder implements ReportRecorder {

    /** Queue size: far more than one investigation produces; reached only when the writer is stuck or very slow. */
    public static final int DEFAULT_CAPACITY = 10_000;
    static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Marks the end of the queue; never shown to the writer. */
    private static final ReportEvent END = new ReportEvent.InvestigationFinished("<end>", java.time.Instant.EPOCH,
            ReportEvent.Outcome.FINISHED, null, null);

    private final DebugReportListener writer;
    private final String writerName;
    private final PrintStream err;
    private final BlockingQueue<ReportEvent> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean disabled;
    /** Set when close() gave up waiting: the thread stops at the next event instead of writing on. */
    private volatile boolean abandoned;
    private int consecutiveFailures;

    public AsyncReportRecorder(DebugReportListener writer, String writerName, PrintStream err, int capacity) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.writerName = writerName;
        this.err = Objects.requireNonNull(err, "err");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::deliverAll, "agentdebug-report-" + writerName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void record(ReportEvent event) {
        if (event == null || closed.get() || disabled) return;
        if (!queue.offer(event)) dropped.incrementAndGet();
    }

    @Override
    public void close(Duration timeout) {
        if (!closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // The end marker must get in even when the queue is full: wait for room, but never past the deadline.
            if (!queue.offer(END, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                warn("report writer did not keep up; closing without the remaining events");
                abandoned = true;
                worker.interrupt();
                return;
            }
            worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            if (worker.isAlive()) {
                warn("report writer did not finish within " + timeout.toMillis() + " ms; report may be incomplete");
                abandoned = true;
                worker.interrupt();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean active() {
        return !disabled && !closed.get();
    }

    /** Events dropped so far because the queue was full (for tests and diagnostics). */
    public long droppedEvents() {
        return dropped.get();
    }

    /** True once the writer has been switched off after repeated failures. */
    public boolean disabled() {
        return disabled;
    }

    private void deliverAll() {
        try {
            while (true) {
                ReportEvent event = queue.take();
                if (event == END || abandoned) break;
                if (!disabled) deliver(event);
                if (abandoned) return;
            }
            if (!disabled && !abandoned) {
                long lost = dropped.get();
                if (lost > 0) call(() -> writer.eventsDropped(lost));
                call(writer::close);
            }
        } catch (InterruptedException interrupted) {
            // close() gave up waiting; the session is ending, nothing else to do.
        }
    }

    private void deliver(ReportEvent event) {
        switch (event) {
            case ReportEvent.InvestigationStarted started -> call(() -> writer.investigationStarted(started));
            case ReportEvent.StepRecorded step -> call(() -> writer.stepRecorded(step));
            case ReportEvent.InvestigationFinished finished -> call(() -> writer.investigationFinished(finished));
        }
    }

    private interface WriterCall {
        void run() throws Exception;
    }

    /** Runs one writer call; any Throwable is contained here (failure isolation). */
    private void call(WriterCall call) {
        try {
            call.run();
            consecutiveFailures = 0;
        } catch (Throwable failure) {
            consecutiveFailures++;
            warn("report writer failed: " + failure);
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                disabled = true;
                warn("report writer disabled after " + consecutiveFailures + " failures in a row; debugging is not affected");
            }
        }
    }

    private void warn(String message) {
        try {
            err.println("[agentdebug-report] " + writerName + ": " + message);
        } catch (Throwable ignored) {
            // Diagnostics must never become a failure themselves.
        }
    }
}
