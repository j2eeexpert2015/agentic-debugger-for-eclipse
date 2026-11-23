package org.learningfromexperience.agentdebug.report;

/**
 * Service-provider interface through which report writers are found at runtime ({@link java.util.ServiceLoader},
 * file {@code META-INF/services/org.learningfromexperience.agentdebug.report.ReportWriterProvider}).
 * This keeps the dependency direction of ADR-009: the MCP server compiles only against this module and selects a
 * writer by {@link #name()}; writer modules are on its runtime class path only.
 *
 * @author Ayan Dutta
 */
public interface ReportWriterProvider {

    /** The setting value that selects this writer, for example {@code chaintest}. Lower case, no spaces. */
    String name();

    /** Creates the writer for one MCP server process (one agent session). May throw; the caller falls back to off. */
    DebugReportListener create(ReportSettings settings) throws Exception;
}
