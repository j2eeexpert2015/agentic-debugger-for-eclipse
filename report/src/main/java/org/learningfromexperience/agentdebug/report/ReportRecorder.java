package org.learningfromexperience.agentdebug.report;

import java.time.Duration;

/**
 * What the debugger calls to record an investigation (OPEN-30). The contract makes the ADR-009 isolation rules part
 * of the API:
 * <ul>
 *   <li>{@link #record} never blocks and never throws, whatever the writer does;</li>
 *   <li>{@link #close} returns within the given time limit, also when the writer hangs.</li>
 * </ul>
 * Obtain one through {@link ReportRecorders#create}.
 *
 * @author Ayan Dutta
 */
public interface ReportRecorder extends AutoCloseable {

    /** Hands one event to the report. Non-blocking; dropped (and counted) if the report cannot keep up. */
    void record(ReportEvent event);

    /** Writes what is pending and stops, waiting at most {@code timeout}. Later events are ignored. */
    void close(Duration timeout);

    /** Closes with the default limit of 5 seconds. */
    @Override
    default void close() {
        close(Duration.ofSeconds(5));
    }

    /** True when events actually reach a writer; false for reporting switched off. */
    boolean active();

    /** The recorder used when reporting is off or no writer could be created: does nothing. */
    static ReportRecorder off() {
        return NoOpReportRecorder.INSTANCE;
    }
}
