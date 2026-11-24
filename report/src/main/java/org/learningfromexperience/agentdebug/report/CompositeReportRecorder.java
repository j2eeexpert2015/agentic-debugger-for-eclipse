package org.learningfromexperience.agentdebug.report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands every event to several recorders, for example the HTML report and the live Agent Activity view (OPEN-36).
 *
 * <p>Each part is a complete recorder of its own, normally an {@link AsyncReportRecorder} with its own queue, thread
 * and failure counting, so the consumers are independent: one that is off, slow, failing or disabled never affects
 * the others or the debugger (ADR-009). This class only forwards; it never throws.
 *
 * @author Ayan Dutta
 */
public final class CompositeReportRecorder implements ReportRecorder {

    private final List<ReportRecorder> parts;

    /** The parts that are not off; null parts are ignored. */
    public CompositeReportRecorder(List<ReportRecorder> parts) {
        List<ReportRecorder> active = new ArrayList<>();
        if (parts != null) for (ReportRecorder part : parts) if (part != null && part.active()) active.add(part);
        this.parts = List.copyOf(active);
    }

    /** One recorder for the given parts: off when none is active, the part itself when only one is. */
    public static ReportRecorder of(ReportRecorder... parts) {
        CompositeReportRecorder composite = new CompositeReportRecorder(parts == null ? null : java.util.Arrays.asList(parts));
        if (composite.parts.isEmpty()) return ReportRecorder.off();
        return composite.parts.size() == 1 ? composite.parts.get(0) : composite;
    }

    @Override
    public void record(ReportEvent event) {
        for (ReportRecorder part : parts) {
            try {
                part.record(event);
            } catch (Throwable ignored) {
                // A recorder never throws by contract; this guard keeps the other parts going if one breaks it.
            }
        }
    }

    @Override
    public void close(Duration timeout) {
        // The parts close one after the other; each waits at most the given time.
        for (ReportRecorder part : parts) {
            try {
                part.close(timeout);
            } catch (Throwable ignored) {
                // Never affects shutdown.
            }
        }
    }

    @Override
    public boolean active() {
        for (ReportRecorder part : parts) if (part.active()) return true;
        return false;
    }
}
