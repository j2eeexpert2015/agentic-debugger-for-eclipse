package org.learningfromexperience.agentdebug.report;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One recorded fact about an agent investigation (OPEN-30). Events are small and immutable: the debugger creates
 * them on its own thread and hands them to a {@link ReportRecorder}, which delivers them to the report writer on a
 * background thread (ADR-009). Values are expected to be already shortened and masked ({@link Redaction}).
 *
 * <p>This model is deliberately independent of any report library: writers translate it into their own model.
 *
 * @author Ayan Dutta
 */
public sealed interface ReportEvent {

    /** Investigation the event belongs to. */
    String investigationId();

    /** When it happened. */
    Instant at();

    /**
     * A line of its own in {@link StepRecorded#details()}: the lines before it are for every reader (what happened,
     * values, output); the lines after it are technical (tool name, arguments, raw result). A writer may show the
     * technical part collapsed; a writer that does not know this line shows it as text.
     */
    String TECHNICAL_DETAILS = "--- technical details ---";

    /** What kind of debugging step a {@link StepRecorded} describes. */
    enum StepKind { LAUNCH, BREAKPOINT, STOP, STEP, INSPECT, HTTP, EXCEPTION, CLEANUP, OTHER }

    /** Outcome of one step: the tool call succeeded, failed, or is purely informational. */
    enum StepStatus { OK, FAILED, INFO }

    /** How an investigation ended. */
    enum Outcome {
        /** The agent called finish_debug_investigation. */
        FINISHED,
        /** Cleaned up without the agent finishing it: idle lease, MCP disconnect or restart cleanup. */
        ENDED_WITHOUT_FINISH
    }

    /**
     * begin_debug_investigation succeeded.
     *
     * @param request      what the user asked for, or null when unknown; shown first in the report
     * @param requestExact true when {@code request} is the exact prompt sent to the agent (known only to a test
     *                     harness); false when the agent passed it on in its own words
     */
    record InvestigationStarted(String investigationId, Instant at, String mode, String client,
                                String request, boolean requestExact) implements ReportEvent {
        public InvestigationStarted {
            Objects.requireNonNull(investigationId, "investigationId");
            Objects.requireNonNull(at, "at");
        }

        /** Without a request. */
        public InvestigationStarted(String investigationId, Instant at, String mode, String client) {
            this(investigationId, at, mode, client, null, false);
        }
    }

    /**
     * A step has started: the tool call is running and its result is not known yet (OPEN-36). Lets a live view show
     * "running now" and waits ("Waiting for the program to stop"); a report writer may ignore it, because the
     * {@link StepRecorded} that follows describes the finished step.
     *
     * @param title one line, in the same plain language as {@link StepRecorded#title()}
     */
    record StepStarted(String investigationId, Instant at, StepKind kind, String title) implements ReportEvent {
        public StepStarted {
            Objects.requireNonNull(investigationId, "investigationId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(title, "title");
        }
    }

    /**
     * One step the agent took (normally one tool call).
     *
     * @param title       one line, for example {@code set_breakpoint OrderService.java:24}
     * @param details     further lines (arguments, the source line, variable values, response), already shortened
     * @param attachments files to embed, for example a screenshot PNG; may be empty
     * @param durationMs  how long the step took, or -1 if unknown
     */
    record StepRecorded(String investigationId, Instant at, StepKind kind, StepStatus status, String title,
                        List<String> details, List<Path> attachments, long durationMs) implements ReportEvent {
        public StepRecorded {
            Objects.requireNonNull(investigationId, "investigationId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(title, "title");
            details = details == null ? List.of() : List.copyOf(details);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /**
     * The investigation ended.
     *
     * @param conclusion the agent's own summary when it gave one (finish_debug_investigation), otherwise null
     * @param cleanup    what cleanup removed or verified, one line per item
     * @param reportFile where the writer must write this investigation's report (decided by the recorder, see
     *                   {@link ReportFiles}); null lets the writer choose
     */
    record InvestigationFinished(String investigationId, Instant at, Outcome outcome, String conclusion,
                                 List<String> cleanup, Path reportFile) implements ReportEvent {
        public InvestigationFinished {
            Objects.requireNonNull(investigationId, "investigationId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(outcome, "outcome");
            cleanup = cleanup == null ? List.of() : List.copyOf(cleanup);
        }

        /** Without a chosen report file. */
        public InvestigationFinished(String investigationId, Instant at, Outcome outcome, String conclusion,
                                     List<String> cleanup) {
            this(investigationId, at, outcome, conclusion, cleanup, null);
        }
    }
}
