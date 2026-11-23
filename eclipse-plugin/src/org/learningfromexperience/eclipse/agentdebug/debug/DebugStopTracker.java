package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;

/**
 * Records debugger stops (suspend events) per launch, so {@code WAIT_FOR_STOP} can report the
 * exact thread and reason of a real stop instead of polling for "any suspended thread".
 *
 * <p>Rules:
 * <ul>
 * <li>Each stop is reported once. A stop that happened before the caller started waiting is
 *     still reported (no race between "resume/step" and "wait").</li>
 * <li>Stops caused by expression evaluation are ignored (evaluation suspends and resumes
 *     the thread internally).</li>
 * <li>When a thread resumes, its unreported stops are discarded; when it stops again, only
 *     the newest stop for that thread is kept. A reported stop therefore always describes
 *     where the thread actually is.</li>
 * </ul>
 *
 * @author Ayan Dutta
 */
public final class DebugStopTracker implements IDebugEventSetListener, AutoCloseable {

    /** Why a thread stopped, as reported to the agent. */
    public enum Reason { BREAKPOINT, EXCEPTION, STEP_END, PAUSED, SUSPENDED }

    /** One debugger stop. {@code breakpoint*} and {@code exceptionType} are set when known. */
    public record Stop(long sequence, IThread thread, Reason reason, String breakpointType,
                       int breakpointLine, String exceptionType) { }

    /** Result of a wait: a stop, termination of the launch, or timeout. */
    public record Outcome(Stop stop, boolean terminated) {
        static final Outcome TIMEOUT = new Outcome(null, false);
        static final Outcome TERMINATED = new Outcome(null, true);
    }

    private static final int MAX_PENDING_PER_LAUNCH = 50;
    private static final int MAX_TRACKED_LAUNCHES = 100;

    private final AtomicLong sequence = new AtomicLong();
    private final Map<ILaunch, Deque<Stop>> pending = new LinkedHashMap<>();
    private final Map<ILaunch, Boolean> terminated = new LinkedHashMap<>();

    public DebugStopTracker() {
        DebugPlugin.getDefault().addDebugEventListener(this);
    }

    @Override
    public void handleDebugEvents(DebugEvent[] events) {
        for (DebugEvent event : events) {
            try {
                handle(event);
            } catch (RuntimeException ignored) {
                // A listener must never throw into Eclipse's event dispatch.
            }
        }
    }

    private void handle(DebugEvent event) {
        boolean evaluation = event.getDetail() == DebugEvent.EVALUATION
                || event.getDetail() == DebugEvent.EVALUATION_IMPLICIT;
        Object source = event.getSource();
        switch (event.getKind()) {
            case DebugEvent.SUSPEND -> {
                if (evaluation || !(source instanceof IThread thread)) return;
                ILaunch launch = thread.getLaunch();
                if (launch == null) return;
                Stop stop = describe(thread, event.getDetail());
                synchronized (this) {
                    Deque<Stop> queue = queue(launch);
                    queue.removeIf(old -> old.thread() == thread);   // newest stop per thread wins
                    queue.addLast(stop);
                    while (queue.size() > MAX_PENDING_PER_LAUNCH) queue.removeFirst();
                    notifyAll();
                }
            }
            case DebugEvent.RESUME -> {
                if (evaluation || !(source instanceof IThread thread) || thread.getLaunch() == null) return;
                synchronized (this) {
                    Deque<Stop> queue = pending.get(thread.getLaunch());
                    if (queue != null) queue.removeIf(old -> old.thread() == thread);
                }
            }
            case DebugEvent.TERMINATE -> {
                ILaunch launch;
                if (source instanceof IDebugTarget target) launch = target.getLaunch();
                else if (source instanceof IProcess process) launch = process.getLaunch();
                else return;
                if (launch == null) return;
                synchronized (this) {
                    terminated.put(launch, Boolean.TRUE);
                    trim();
                    notifyAll();
                }
            }
            default -> { }
        }
    }

    private Stop describe(IThread thread, int detail) {
        Reason reason = switch (detail) {
            case DebugEvent.STEP_END -> Reason.STEP_END;
            case DebugEvent.CLIENT_REQUEST -> Reason.PAUSED;
            case DebugEvent.BREAKPOINT -> Reason.BREAKPOINT;
            default -> Reason.SUSPENDED;
        };
        String breakpointType = null;
        int breakpointLine = -1;
        String exceptionType = null;
        if (reason == Reason.BREAKPOINT) {
            for (IBreakpoint breakpoint : thread.getBreakpoints()) {
                try {
                    if (breakpoint instanceof IJavaExceptionBreakpoint exception) {
                        reason = Reason.EXCEPTION;
                        exceptionType = exception.getExceptionTypeName();
                        break;
                    }
                    if (breakpoint instanceof IJavaLineBreakpoint line && breakpointType == null) {
                        breakpointType = line.getTypeName();
                        breakpointLine = line.getLineNumber();
                    }
                } catch (Exception unreadable) {
                    // Marker removed concurrently; the reason stays BREAKPOINT without details.
                }
            }
        }
        return new Stop(sequence.incrementAndGet(), thread, reason, breakpointType, breakpointLine, exceptionType);
    }

    /**
     * Waits for the next unreported stop of {@code launch}. Returns immediately if one is already
     * pending. Returns {@link Outcome#TERMINATED} once the launch has ended and no stop is pending.
     */
    public Outcome waitForStop(ILaunch launch, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (this) {
            while (true) {
                Deque<Stop> queue = pending.get(launch);
                if (queue != null) {
                    for (Iterator<Stop> it = queue.iterator(); it.hasNext(); ) {
                        Stop stop = it.next();
                        it.remove();
                        if (stop.thread().isSuspended() && !stop.thread().isTerminated()) return new Outcome(stop, false);
                    }
                }
                if (launch.isTerminated() || Boolean.TRUE.equals(terminated.get(launch))) return Outcome.TERMINATED;
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) return Outcome.TIMEOUT;
                wait(Math.min(remaining, 500));   // re-check periodically, e.g. for termination
            }
        }
    }

    private Deque<Stop> queue(ILaunch launch) {
        Deque<Stop> queue = pending.computeIfAbsent(launch, ignored -> new ArrayDeque<>());
        trim();
        return queue;
    }

    /** Bounds memory: forget the oldest launches once too many have been seen. */
    private void trim() {
        while (pending.size() > MAX_TRACKED_LAUNCHES) pending.remove(pending.keySet().iterator().next());
        while (terminated.size() > MAX_TRACKED_LAUNCHES) terminated.remove(terminated.keySet().iterator().next());
    }

    @Override
    public void close() {
        DebugPlugin.getDefault().removeDebugEventListener(this);
        synchronized (this) {
            pending.clear();
            terminated.clear();
            notifyAll();
        }
    }
}
