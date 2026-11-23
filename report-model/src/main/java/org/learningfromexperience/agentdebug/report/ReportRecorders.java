package org.learningfromexperience.agentdebug.report;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Creates the {@link ReportRecorder} for one MCP server process from its {@link ReportSettings}.
 *
 * <p>Never fails: reporting {@code off}, an unknown writer name, a writer module missing from the class path or a
 * writer whose creation throws all give {@link ReportRecorder#off()}; the last three print one warning on {@code err}.
 * Writers are found with {@link ServiceLoader}, so this module never refers to any of them (ADR-009 rule 1).
 *
 * @author Ayan Dutta
 */
public final class ReportRecorders {

    private ReportRecorders() { }

    public static ReportRecorder create(ReportSettings settings, PrintStream err) {
        return create(settings, err, ServiceLoader.load(ReportWriterProvider.class, ReportRecorders.class.getClassLoader()),
                AsyncReportRecorder.DEFAULT_CAPACITY);
    }

    static ReportRecorder create(ReportSettings settings, PrintStream err, Iterable<ReportWriterProvider> providers,
                                 int capacity) {
        if (settings.off()) return ReportRecorder.off();
        List<String> available = new ArrayList<>();
        try {
            for (ReportWriterProvider provider : providers) {
                available.add(provider.name());
                if (!provider.name().equals(settings.writer())) continue;
                DebugReportListener writer = provider.create(settings);
                return new AsyncReportRecorder(writer, provider.name(), err, capacity);
            }
            err.println("[agentdebug-report] report writer '" + settings.writer() + "' not found (available: "
                    + (available.isEmpty() ? "none" : String.join(", ", available)) + "); reporting is off");
        } catch (Throwable failure) {
            err.println("[agentdebug-report] report writer '" + settings.writer() + "' could not start: " + failure
                    + "; reporting is off");
        }
        return ReportRecorder.off();
    }
}
