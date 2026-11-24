package org.learningfromexperience.agentdebug.report;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * How reporting is configured for one MCP server process.
 *
 * @param writer          {@value #OFF} or the name of a writer, {@code html} (the HTML report)
 * @param outputDirectory folder for this session's report (the writer creates it)
 * @param projectName     shown in the report header
 * @author Ayan Dutta
 */
public record ReportSettings(String writer, Path outputDirectory, String projectName) {

    /** Reporting switched off: the debugger records into a no-op recorder. The default until R1a has passed. */
    public static final String OFF = "off";

    public ReportSettings {
        writer = writer == null || writer.isBlank() ? OFF : writer.strip().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        projectName = projectName == null ? "Agentic Debugger" : projectName;
    }

    public boolean off() {
        return OFF.equals(writer);
    }
}
