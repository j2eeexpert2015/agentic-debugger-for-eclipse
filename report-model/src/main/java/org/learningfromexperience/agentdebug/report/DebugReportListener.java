package org.learningfromexperience.agentdebug.report;

/**
 * A report writer (OPEN-30). Implemented by report modules such as {@code report-chaintest}; never called by the
 * debugger directly. The debugger only talks to a {@link ReportRecorder}, which calls these methods on its own
 * background thread, one event at a time and in order, and catches everything they throw (ADR-009).
 *
 * <p>So an implementation may be slow and may fail without affecting debugging; it does not need to be thread-safe.
 * It must never write to {@code System.out}: in the MCP server, stdout carries the MCP protocol.
 *
 * @author Ayan Dutta
 */
public interface DebugReportListener {

    void investigationStarted(ReportEvent.InvestigationStarted event) throws Exception;

    void stepRecorded(ReportEvent.StepRecorded event) throws Exception;

    void investigationFinished(ReportEvent.InvestigationFinished event) throws Exception;

    /** Called once before {@link #close()} when the recorder had to drop events because its queue was full. */
    default void eventsDropped(long count) throws Exception { }

    /** Last call: write anything still pending (the report must be complete afterwards). */
    default void close() throws Exception { }
}
