package org.learningfromexperience.agentdebug.report;

import java.io.PrintStream;

/**
 * Creates the {@link ReportRecorder} for one MCP server process from its {@link ReportSettings}.
 *
 * <p>There is one report writer, {@link HtmlReportWriter} ({@value #HTML}; the earlier name {@value #CHAINTEST} is
 * accepted for it, so existing settings keep working). Until 25 Nov 2026 writers were found at runtime through a
 * service-provider interface built for several writers; with one writer that mechanism was removed (OPEN-38 step 4).
 *
 * <p>Never fails: reporting {@code off} gives {@link ReportRecorder#off()} silently; an unknown writer name or a writer
 * whose creation throws gives it with one warning on {@code err}. The writer always runs behind an
 * {@link AsyncReportRecorder}, so the isolation rules of ADR-009 hold.
 *
 * @author Ayan Dutta
 */
public final class ReportRecorders {

    /** The setting value of the HTML report writer. */
    public static final String HTML = "html";
    /** The earlier name of the report setting's writer (the ChainTest writer); now means {@value #HTML}. */
    public static final String CHAINTEST = "chaintest";

    /** Creates the writer for one session; may throw. */
    @FunctionalInterface
    interface WriterFactory {
        DebugReportListener create(ReportSettings settings) throws Exception;
    }

    private ReportRecorders() { }

    public static ReportRecorder create(ReportSettings settings, PrintStream err) {
        return create(settings, err, HtmlReportWriter::new, AsyncReportRecorder.DEFAULT_CAPACITY);
    }

    static ReportRecorder create(ReportSettings settings, PrintStream err, WriterFactory factory, int capacity) {
        if (settings.off()) return ReportRecorder.off();
        if (!HTML.equals(settings.writer()) && !CHAINTEST.equals(settings.writer())) {
            err.println("[agentdebug-report] report writer '" + settings.writer() + "' not found (available: " + HTML
                    + "); reporting is off");
            return ReportRecorder.off();
        }
        try {
            return new AsyncReportRecorder(factory.create(settings), HTML, err, capacity);
        } catch (Throwable failure) {
            err.println("[agentdebug-report] report writer '" + settings.writer() + "' could not start: " + failure
                    + "; reporting is off");
            return ReportRecorder.off();
        }
    }
}
