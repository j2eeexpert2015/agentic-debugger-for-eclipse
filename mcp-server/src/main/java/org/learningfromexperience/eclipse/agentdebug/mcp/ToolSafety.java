package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

/**
 * OPEN-58: MCP tool annotations, so an MCP client can tell which of our tools only read and which change something.
 * GitHub Copilot, for example, can approve read-only tools automatically ("Trust MCP tool annotations") and still ask
 * the user before the others. The annotations are hints for the client; the safety itself stays in code (the plugin
 * asks the user in Eclipse before code runs in the debugged program, {@code EvaluationConsent}).
 * <ul>
 *   <li>{@link #READ_ONLY}: reads Eclipse or the debugged program, changes nothing ({@code readOnlyHint=true}).</li>
 *   <li>{@link #RUNS_CODE}: evaluates Java code inside the debugged program, which can change its state
 *       ({@code destructiveHint=true}).</li>
 *   <li>Every other known tool changes debugger state in a way the investigation's cleanup undoes (breakpoints,
 *       launches, stepping): {@code readOnlyHint=false, destructiveHint=false}.</li>
 *   <li>A tool in none of the lists gets no annotations, so a client treats it with its own caution (the MCP default
 *       for a tool without annotations is "may be destructive").</li>
 * </ul>
 * Tests: TS-U1 to TS-U4 ({@code ToolSafetyTest}).
 *
 * @author Ayan Dutta
 */
final class ToolSafety {

    /** Tools that only read. Keep this list conservative: when in doubt, a tool is not read-only. */
    static final Set<String> READ_ONLY = Set.of(
            "ping", "eclipse_ping", "list_debug_sessions", "list_breakpoints", "list_exception_breakpoints",
            "list_junit_tests", "get_junit_results", "list_run_configurations", "list_java_applications",
            "get_investigation_report", "get_bridge_operation", "get_bridge_diagnostics", "get_debug_status",
            "get_debug_threads", "get_stack_frames", "get_frame_variables", "expand_frame_variable",
            "get_process_output", "wait_for_stop", "wait_for_debug_pause", "get_fixture_http_request");

    /** Tools that run Java code inside the debugged program. */
    static final Set<String> RUNS_CODE = Set.of("evaluate_expression", "set_tracepoint");

    /** Tools that change debugger state that the investigation's cleanup undoes. */
    static final Set<String> CHANGES_DEBUG_STATE = Set.of(
            "begin_debug_investigation", "finish_debug_investigation", "set_breakpoint", "set_conditional_breakpoint",
            "set_exception_breakpoint", "remove_breakpoint", "remove_exception_breakpoint", "start_junit_test",
            "start_java_application", "start_debug_session", "restart_debug_session", "configure_step_filters",
            "control_debug_session", "show_debug_location", "start_fixture_http_request",
            "cancel_fixture_http_request");

    private ToolSafety() { }

    /** The annotations for a tool name, or null for a tool in no list. */
    static ToolAnnotations annotationsFor(String name) {
        if (READ_ONLY.contains(name))
            return ToolAnnotations.builder().readOnlyHint(true).destructiveHint(false).idempotentHint(true)
                    .openWorldHint(false).build();
        if (RUNS_CODE.contains(name))
            return ToolAnnotations.builder().readOnlyHint(false).destructiveHint(true).idempotentHint(false)
                    .openWorldHint(false).build();
        if (CHANGES_DEBUG_STATE.contains(name))
            return ToolAnnotations.builder().readOnlyHint(false).destructiveHint(false).idempotentHint(false)
                    .openWorldHint(false).build();
        return null;
    }

    /**
     * The same tools with their annotations added (a tool that already has annotations keeps them). The order and the
     * call handlers are unchanged.
     */
    static List<SyncToolSpecification> annotate(List<SyncToolSpecification> tools) {
        List<SyncToolSpecification> annotated = new ArrayList<>(tools.size());
        for (SyncToolSpecification spec : tools) {
            Tool tool = spec.tool();
            ToolAnnotations annotations = tool.annotations() != null ? null : annotationsFor(tool.name());
            if (annotations == null) {
                annotated.add(spec);
                continue;
            }
            Tool withAnnotations = new Tool(tool.name(), tool.title(), tool.description(), tool.inputSchema(),
                    tool.outputSchema(), annotations, tool.meta(), tool.icons());
            annotated.add(new SyncToolSpecification(withAnnotations, spec.callHandler()));
        }
        return annotated;
    }
}
