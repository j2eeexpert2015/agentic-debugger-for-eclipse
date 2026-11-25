package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Wraps every MCP tool so that each call is also recorded in the debugging session report (OPEN-30 step R1) and
 * shown in the live Agent Activity view (OPEN-36: a "step started" event before the call, the step after it).
 *
 * <p>The wrapper calls the original tool first and returns its result unchanged; only afterwards does it describe
 * the call to {@link SessionReport}, inside a catch-all. So a report can never change, delay noticeably or break a
 * tool result (ADR-009); with report and live view both off the tools are returned as they are, without a wrapper.
 *
 * @author Ayan Dutta
 */
final class ReportingTools {

    private ReportingTools() { }

    static List<SyncToolSpecification> wrap(List<SyncToolSpecification> tools, SessionReport report) {
        if (!report.active()) return tools;
        List<SyncToolSpecification> wrapped = new ArrayList<>(tools.size());
        for (SyncToolSpecification tool : tools) wrapped.add(wrap(tool, report));
        return wrapped;
    }

    private static SyncToolSpecification wrap(SyncToolSpecification spec, SessionReport report) {
        String name = spec.tool().name();
        var original = spec.callHandler();
        return new SyncToolSpecification(spec.tool(), (exchange, request) -> {
            try {
                // OPEN-36: tell the live Agent Activity view the step is running; queued, never waits.
                report.toolStarting(name, request == null ? null : request.arguments());
            } catch (Throwable ignored) {
                // The tool runs whatever happens to the live view.
            }
            long start = System.nanoTime();
            McpSchema.CallToolResult result = original.apply(exchange, request);
            try {
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                Map<String, Object> arguments = request == null ? null : request.arguments();
                report.toolCalled(client(exchange), name, arguments,
                        result != null && Boolean.TRUE.equals(result.isError()), text(result), ms);
            } catch (Throwable ignored) {
                // The tool result is returned whatever happens to the report.
            }
            if (FINISH.equals(name)) result = withReportFile(result, report, request);
            return result;
        });
    }

    private static final String FINISH = "finish_debug_investigation";

    /**
     * OPEN-39 (token use): the finish reply also names the report file, so the agent needs no further
     * get_investigation_report call. Only a successful single-text reply is extended; any problem returns it unchanged.
     */
    static McpSchema.CallToolResult withReportFile(McpSchema.CallToolResult result, SessionReport report,
                                                   McpSchema.CallToolRequest request) {
        try {
            if (result == null || Boolean.TRUE.equals(result.isError()) || request == null || request.arguments() == null
                    || result.content() == null || result.content().size() != 1
                    || !(result.content().get(0) instanceof McpSchema.TextContent t)) return result;
            Object id = request.arguments().get("investigationId");
            String extended = report.withReportFile(id == null ? null : id.toString(), t.text());
            if (extended == null || extended.equals(t.text())) return result;
            return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(extended))).build();
        } catch (Throwable ignored) {
            return result;
        }
    }

    private static String client(McpSyncServerExchange exchange) {
        try {
            McpSchema.Implementation info = exchange == null ? null : exchange.getClientInfo();
            return info == null ? null : info.name();
        } catch (Throwable unknown) {
            return null;
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) return null;
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content())
            if (content instanceof McpSchema.TextContent t && t.text() != null) text.append(t.text());
        return text.toString();
    }
}
