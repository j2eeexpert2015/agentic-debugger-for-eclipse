package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import java.net.InetSocketAddress;
import java.net.Socket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

/**
 * Registers Claude Code MCP tools and forwards authenticated requests to Eclipse.
 *
 * @author Ayan Dutta
 */
public class DebugMcpServer {

    private static final String ECLIPSE_HOST = "127.0.0.1";

    private static final int ECLIPSE_PORT = Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765"));

    private static final Path TOKEN_FILE = Path.of(
            System.getProperty("user.home"),
            ".eclipse-agentdebug",
            ECLIPSE_PORT == 8765 ? "bridge.token" : "bridge-" + ECLIPSE_PORT + ".token"
    );

    public static void main(String[] args) throws InterruptedException {

        // STDOUT is reserved exclusively for MCP protocol messages.
        System.err.println(
                "[Eclipse Agentic Debugger] Starting MCP server..."
        );

        // OPEN-30: debugging session report. On by default; AGENTDEBUG_REPORT=off switches it off (ADR-009).
        // OPEN-36: the same events go to the Agent Activity view in Eclipse; AGENTDEBUG_LIVE=off switches it off.
        REPORT = SessionReport.fromEnvironment(System.getenv(), System.err, command -> sendToEclipse(command, true));

        // CORE-17: notice when the agent goes away (stdin closed) and finish its open investigations.
        System.setIn(new java.io.FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                int r = super.read();
                if (r < 0) onClientGone();
                return r;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int r = super.read(b, off, len);
                if (r < 0) onClientGone();
                return r;
            }
        });

        var transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper()
        );

        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false
        );

        Map<String, Object> breakpointSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "typeName", Map.of("type", "string", "description",
                                "Fully qualified Java class name"),
                        "lineNumber", Map.of("type", "integer", "minimum", 1),
                        "investigationId", Map.of("type", "string", "description", "Optional ID from begin_debug_investigation"),
                        "purpose", PURPOSE
                ),
                "required", List.of("typeName", "lineNumber"),
                "additionalProperties", false
        );

        // Tool 1: MCP server connectivity.
        var pingTool = SyncToolSpecification.builder()
                .tool(
                        Tool.builder("ping", inputSchema)
                                .description(
                                        "Checks whether the MCP server is running"
                                )
                                .build()
                )
                .callHandler((exchange, request) -> {

                    return success(
                            """
                            {
                              "status": "OK",
                              "server": "eclipse-debugger",
                              "message": "MCP server is running"
                            }
                            """
                    );
                })
                .build();

        // Tool 2: Eclipse plugin connectivity.
        var eclipsePingTool = SyncToolSpecification.builder()
                .tool(
                        Tool.builder("eclipse_ping", inputSchema)
                                .description(
                                        "Checks connectivity with the running Eclipse IDE"
                                )
                                .build()
                )
                .callHandler((exchange, request) -> {

                    try {

                        String response = sendToEclipse("PING", false);

                        return success(
                                "Eclipse bridge response: " + response
                        );

                    } catch (IOException e) {

                        return failure(
                                "Unable to communicate with Eclipse: "
                                + e.getMessage()
                        );
                    }
                })
                .build();

        // Tool 3: Retrieve live Java debugging sessions.
        var listDebugSessionsTool = SyncToolSpecification.builder()
                .tool(
                        Tool.builder("list_debug_sessions", inputSchema)
                                .description(
                                        "Lists active Java debugging sessions "
                                        + "in the running Eclipse IDE, including "
                                        + "launch names, debug targets and "
                                        + "execution states"
                                )
                                .build()
                )
                .callHandler((exchange, request) -> {

                    try {

                        String response = sendToEclipse(
                                "LIST_DEBUG_SESSIONS",
                                true
                        );

                        if (response.contains(
                                "\"status\":\"error\""
                        )) {

                            return failure(response);
                        }

                        return success(response);

                    } catch (IOException e) {

                        return failure(
                                "Failed to retrieve Eclipse debugging sessions: "
                                + e.getMessage()
                        );
                    }
                })
                .build();

        // Tool 4: Inspect existing Java breakpoints without changing them.
        var listBreakpointsTool = SyncToolSpecification.builder()
                .tool(Tool.builder("list_breakpoints", inputSchema)
                        .description("Lists Java line breakpoints in Eclipse, "
                                + "including line number, enabled state, and "
                                + "whether they were created by the agent or user")
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String response = sendToEclipse("LIST_BREAKPOINTS", true);
                        return bridgeResult(response);
                    } catch (IOException e) {
                        return failure("Failed to list breakpoints: " + e.getMessage());
                    }
                })
                .build();

        // Tool 5: Create a line breakpoint, preserving existing breakpoints.
        var setBreakpointTool = SyncToolSpecification.builder()
                .tool(Tool.builder("set_breakpoint", breakpointSchema)
                        .description("Sets a Java line breakpoint in Eclipse. "
                                + "Does not start, resume, or modify a debug session. "
                                + "Does not change an existing breakpoint.")
                        .build())
                .callHandler((exchange, request) -> {
                    Map<String, Object> arguments = request.arguments();
                    Object type = arguments.get("typeName");
                    Object line = arguments.get("lineNumber");
                    if (!(type instanceof String typeName)
                            || !(line instanceof Number lineNumber)
                            || !typeName.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
                        return failure("Invalid Java type name or line number");
                    }
                    int parsedLine = lineNumber.intValue();
                    if (parsedLine < 1 || parsedLine != lineNumber.doubleValue()) {
                        return failure("Line number must be a positive integer");
                    }
                    ATTACHED.remove();
                    try {
                        String response = sendToEclipse(
                                "SET_BREAKPOINT " + enc(typeName) + " " + parsedLine
                                        + ownedId(arguments), true);
                        return bridgeResult(noteAttachment(response));
                    } catch (IllegalArgumentException e) {
                        ATTACHED.remove();
                        return failure(e.getMessage());
                    } catch (IOException e) {
                        return failure("Failed to set breakpoint: " + e.getMessage());
                    }
                })
                .build();

        List<SyncToolSpecification> allTools = new ArrayList<>(List.of(
                pingTool, eclipsePingTool, listDebugSessionsTool,
                listBreakpointsTool, setBreakpointTool));

        // Investigation lifecycle: NORMAL by default; CONTROLLED preserves suspended state.
        allTools.add(bridgeTool("begin_debug_investigation",
                "Start a debugging investigation. Default mode NORMAL cleans up ONLY its own new session "
                        + "and its own breakpoints when finish_debug_investigation is called. "
                        + "Use CONTROLLED only when the user expressly requests a paused/step-by-step investigation. "
                        + "Pass the returned investigationId to set_breakpoint and the launch tool "
                        + "(with exactly one open investigation it is attached automatically; with several it is required). "
                        + "Always finish the investigation when done. If this session ends without finishing, "
                        + "the server finishes it; if the agent stops calling for 10 minutes, Eclipse does. "
                        + "Pass request: the user's request in their own words (verbatim when short); it opens the "
                        + "debugging session report.",
                Map.of("mode", Map.of("type", "string", "enum", List.of("NORMAL", "CONTROLLED")),
                        // Only for the session report (OPEN-30); never sent to Eclipse.
                        "request", Map.of("type", "string", "description",
                                "The user's request that started this investigation, in the user's own words")),
                List.of(), a -> "BEGIN_INVESTIGATION " + enc(mode(a))));
        allTools.add(bridgeTool("finish_debug_investigation",
                "Finish the exact investigationId. NORMAL terminates only the session it started, "
                        + "removes only its own newly created breakpoints, and leaves user sessions and breakpoints intact. "
                        + "CONTROLLED preserves the debug session suspended and breakpoints for inspection. "
                        + "Call once after reporting actual evidence, even if a debugger step fails. "
                        + "Pass summary: your complete analysis in Markdown, the same findings you will give the user "
                        + "(reproduced symptom, runtime evidence with frames and values, root cause or what remains "
                        + "uncertain, fix suggestion not applied); it is shown in full at the top of the debugging session "
                        + "report. Optionally also pass the structured fields (title, trackedValue, findings, "
                        + "rootCause, fix, notObserved, assumedPath): the report places them in the program flow, "
                        + "marked as your statements, and shows the source lines of rootCause read from the project. "
                        + "The reply confirms a verified cleanup (cleanup, ownBreakpointsLeft) and, when reports are on, "
                        + "names the report file (reportFile): no further call is needed to check either.",
                finishProperties(),
                List.of("investigationId"),
                a -> "FINISH_INVESTIGATION " + enc(arg(a,"investigationId"))));
        // OPEN-30: where the debugging session report of an investigation is (answered by the MCP server itself).
        allTools.add(SyncToolSpecification.builder()
                .tool(Tool.builder("get_investigation_report", schema(Map.of("investigationId", Map.of("type", "string",
                                "description", "Optional; default is the most recently finished investigation")), List.of()))
                        .description("Returns the full path of the HTML debugging session report of a finished "
                                + "investigation, or says that reports are off. Only needed when the finish reply has no reportFile; "
                                + "the reply of finish_debug_investigation already names it.")
                        .build())
                .callHandler((exchange, request) -> {
                    Object id = request == null || request.arguments() == null ? null : request.arguments().get("investigationId");
                    return success(REPORT.reportInfo(id instanceof String idText ? idText : null));
                })
                .build());
        allTools.add(bridgeTool("remove_breakpoint", "Remove only agent-owned line breakpoints.",
                Map.of("typeName",str(),"lineNumber",integer()), List.of("typeName","lineNumber"),
                a -> "REMOVE_BREAKPOINT " + enc(arg(a,"typeName")) + " " + intArg(a,"lineNumber")));
        // Phase 1: optional capabilities; legacy tool names and schemas remain unchanged.
        allTools.add(bridgeTool("set_conditional_breakpoint",
                "Set a new Java conditional line breakpoint. Eclipse suspends only when the Java condition is true. "
                + "Existing user/agent breakpoints at that line are never modified. Prefer for record-specific defects.",
                Map.of("typeName",str(), "lineNumber",integer(), "condition",str(), "investigationId",str(),
                        "purpose", PURPOSE),
                List.of("typeName", "lineNumber", "condition"),
                a -> "SET_CONDITIONAL_BREAKPOINT " + enc(arg(a,"typeName")) + " " + intArg(a,"lineNumber")
                        + " " + enc(arg(a,"condition")) + ownedId(a)));
        allTools.add(bridgeTool("set_exception_breakpoint",
                "Create an Eclipse Java exception breakpoint for a fully-qualified exception type. "
                + "Caught and/or uncaught must be true. Existing exception breakpoints are never modified.",
                Map.of("exceptionName",str(),"caught",bool(),"uncaught",bool(),"investigationId",str()),
                List.of("exceptionName","caught","uncaught"),
                a -> "SET_EXCEPTION_BREAKPOINT " + enc(arg(a,"exceptionName")) + " "
                        + enc(boolArg(a,"caught")) + " " + enc(boolArg(a,"uncaught")) + ownedId(a)));
        allTools.add(bridgeTool("list_exception_breakpoints",
                "List existing Java exception breakpoints and ownership without modifying them.",
                Map.of(), List.of(), a -> "LIST_EXCEPTION_BREAKPOINTS"));
        allTools.add(bridgeTool("remove_exception_breakpoint",
                "Remove only agent-created Java exception breakpoints with a matching exception type.",
                Map.of("exceptionName",str()), List.of("exceptionName"),
                a -> "REMOVE_EXCEPTION_BREAKPOINT " + enc(arg(a,"exceptionName"))));
        allTools.add(bridgeTool("set_tracepoint",
                "Log a Java expression at a line without suspending execution. Uses Eclipse's conditional "
                + "breakpoint evaluation and System.out.println; evaluation may execute methods/side effects. "
                + "Requires explicit user consent and allowSideEffects=true. Does not modify existing breakpoints.",
                Map.of("typeName",str(), "lineNumber",integer(),"expression",str(),
                        "allowSideEffects",bool(),"investigationId",str()),
                List.of("typeName","lineNumber","expression","allowSideEffects"),
                a -> {
                    if (!Boolean.TRUE.equals(a.get("allowSideEffects")))
                        throw new IllegalArgumentException("Explicit consent required for tracepoint evaluation");
                    return "SET_TRACEPOINT " + enc(arg(a,"typeName")) + " " + intArg(a,"lineNumber")
                            + " " + enc(arg(a,"expression")) + ownedId(a);
                }));
        allTools.add(bridgeTool("list_junit_tests",
                "Discover source test methods annotated with @Test, @ParameterizedTest or @RepeatedTest "
                + "in open Java projects. Method candidates are not proof that a JUnit runner is installed.",
                Map.of(),List.of(), a -> "LIST_JUNIT_TESTS"));
        allTools.add(bridgeTool("start_junit_test",
                "Launch an Eclipse JUnit test class or method in debug mode. "
                + "Requires Eclipse JUnit launch integration; methodName may be empty for class launch. "
                + "Returns launchName and does not overwrite user launch configurations.",
                Map.of("projectName",str(), "typeName",str(),"methodName",str(),"investigationId",str()),
                List.of("projectName","typeName","methodName"),
                a -> "START_JUNIT_TEST " + enc(arg(a,"projectName")) + " " + enc(arg(a,"typeName"))
                        + " " + enc(stringAllowEmpty(a,"methodName")) + ownedId(a)));
        allTools.add(bridgeTool("expand_frame_variable",
                "Read a selected local/field/array element and its immediate children with pagination. "
                + "Read-only; requires suspended frame. path example: order.customer.address or items[0].",
                Map.of("launchName",str(), "threadIndex",integer(),"frameIndex",integer(),
                        "path",str(),"offset",integer(),"limit",integer()),
                List.of("launchName","threadIndex","frameIndex","path","offset","limit"),
                a -> "EXPAND_VARIABLE " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")
                        + " " + intArg(a,"frameIndex") + " " + enc(arg(a,"path")) + " "
                        + intArg(a,"offset") + " " + intArg(a,"limit")));
        allTools.add(bridgeTool("restart_debug_session",
                "Restart ONLY a debug launch created and still owned by the supplied investigationId. "
                + "User-owned/reused sessions cannot be restarted through this tool.",
                Map.of("launchName",str(),"investigationId",str()),
                List.of("launchName","investigationId"),
                a -> "RESTART_DEBUG " + enc(arg(a,"launchName")) + " " + enc(arg(a,"investigationId"))));
        allTools.add(bridgeTool("configure_step_filters",
                "Set the current Java debug target's step-filter patterns and enabled state. "
                + "Changes target execution behavior, not global Eclipse preferences. "
                + "Examples: java.*, javax.*, sun.*. Explicit patterns replace this target's active list.",
                Map.of("launchName",str(),"enabled",bool(),"patterns",str()),
                List.of("launchName","enabled","patterns"),
                a -> "SET_STEP_FILTERS " + enc(arg(a,"launchName")) + " "
                        + enc(boolArg(a,"enabled")) + " " + enc(stringAllowEmpty(a,"patterns"))));
        allTools.add(bridgeTool("list_run_configurations", "List saved debug-capable Eclipse launch configurations.",
                Map.of(), List.of(), a -> "LIST_RUN_CONFIGURATIONS"));
        allTools.add(bridgeTool("list_java_applications",
                "Discover Java main classes in open Eclipse Java source projects without creating configurations.",
                Map.of(), List.of(), a -> "LIST_JAVA_APPLICATIONS"));
        allTools.add(bridgeTool("start_java_application",
                "Start a Java main class in debug mode without a preexisting launch configuration. "
                        + "Validates the open Java project and fully qualified main class, reuses an existing matching "
                        + "configuration or creates one when absent. Returns launchName for subsequent debugger tools.",
                Map.of("projectName", str(), "mainType", str(), "investigationId", str()),
                List.of("projectName", "mainType"),
                a -> "START_JAVA_APPLICATION " + enc(arg(a, "projectName")) + " "
                        + enc(arg(a, "mainType")) + ownedId(a)));
        allTools.add(bridgeTool("start_debug_session", "Start or reuse a saved Eclipse debug launch configuration.",
                Map.of("launchName",str(),"investigationId",str()), List.of("launchName"),
                a -> "START_DEBUG " + enc(arg(a,"launchName")) + ownedId(a)));
        allTools.add(bridgeTool("show_debug_location",
                "Select the agent-controlled suspended session in Eclipse Debug view and open its source at the current line. Does not step or resume.",
                Map.of("launchName",str()), List.of("launchName"),
                a -> "SHOW_DEBUG_LOCATION " + enc(arg(a,"launchName"))));
        allTools.add(bridgeTool("get_bridge_operation",
                "Check a command that Eclipse reported as BRIDGE_TIMEOUT (still running). Returns its state "
                        + "(RUNNING, COMPLETED, ABANDONED) and, once finished, its actual result. Read-only. "
                        + "Use this instead of repeating a launch, step or cleanup that timed out.",
                Map.of("operationId",integer()), List.of("operationId"),
                a -> "GET_OPERATION " + intArg(a,"operationId")));
        allTools.add(bridgeTool("get_bridge_diagnostics",
                "Troubleshooting only: list commands Eclipse is currently processing, how long each has "
                        + "been running, and bridge counters (timeouts, busy replies). Read-only.",
                Map.of(), List.of(),
                a -> "GET_BRIDGE_DIAGNOSTICS"));
        allTools.add(bridgeTool("get_debug_status", "Get current debug target and suspension state.",
                Map.of("launchName",str()), List.of("launchName"),
                a -> "GET_DEBUG_STATE " + enc(arg(a,"launchName"))));
        allTools.add(bridgeTool("get_debug_threads", "List threads and their current indexes and suspension states.",
                Map.of("launchName",str()), List.of("launchName"),
                a -> "GET_THREADS " + enc(arg(a,"launchName"))));
        allTools.add(bridgeTool("get_stack_frames", "Get call stack for a suspended thread.",
                Map.of("launchName",str(),"threadIndex",integer()), List.of("launchName","threadIndex"),
                a -> "GET_STACK " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")));
        allTools.add(bridgeTool("get_frame_variables", "Inspect locals in a suspended frame without running code.",
                Map.of("launchName",str(),"threadIndex",integer(),"frameIndex",integer()),
                List.of("launchName","threadIndex","frameIndex"),
                a -> "GET_VARIABLES " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")
                        + " " + intArg(a,"frameIndex")));
        allTools.add(bridgeTool("evaluate_expression", "Evaluate a Java expression. May invoke methods and change state: require explicit user consent and allowSideEffects=true.",
                Map.of("launchName",str(),"threadIndex",integer(),"frameIndex",integer(),
                        "expression",str(),"allowSideEffects",Map.of("type","boolean"),"purpose",PURPOSE),
                List.of("launchName","threadIndex","frameIndex","expression","allowSideEffects"),
                a -> {
                    if (!Boolean.TRUE.equals(a.get("allowSideEffects")))
                        throw new IllegalArgumentException("Explicit consent required for evaluation");
                    return "EVALUATE_EXPRESSION " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")
                            + " " + intArg(a,"frameIndex") + " " + enc(arg(a,"expression"));
                }));
        allTools.add(bridgeTool("control_debug_session", "PAUSE, RESUME, STEP_INTO, STEP_OVER, STEP_RETURN or STOP execution. "
                        + "For an investigation use finish_debug_investigation for safe completion; "
                        + "do not STOP or resume unrelated/user-owned sessions (refused outside an investigation).",
                Map.of("launchName",str(),"threadIndex",integer(),"action",Map.of("type","string",
                        "enum",List.of("PAUSE","RESUME","STEP_INTO","STEP_OVER","STEP_RETURN","STOP")),
                        "investigationId",str(),"purpose",PURPOSE),
                List.of("launchName","threadIndex","action"),
                a -> "CONTROL_DEBUG " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")
                        + " " + enc(arg(a,"action")) + ownedId(a)));
        allTools.add(bridgeTool("wait_for_stop",
                "Wait (1-60 s) for the next debugger stop in this launch - breakpoint, exception, step end or pause - "
                        + "and get everything about it in one call: reason (BREAKPOINT, EXCEPTION, STEP_END, PAUSED), "
                        + "thread name and index, location, top 5 stack frames and the top frame's local variables "
                        + "(and the exception type for EXCEPTION; localsNote explains missing locals, e.g. in a "
                        + "native method after a pause). Event-based: reports the thread that actually stopped. "
                        + "Each stop is reported once, including a stop that happened just before this call, so call it "
                        + "right after start_*, RESUME or a step. Returns stopped=false with reason TIMEOUT or TERMINATED "
                        + "otherwise. Does not resume or change anything. Prefer this over wait_for_debug_pause + "
                        + "get_debug_threads + get_stack_frames + get_frame_variables.",
                Map.of("launchName",str(),"timeoutSeconds",integer()), List.of("launchName","timeoutSeconds"),
                a -> {
                    int seconds = intArg(a,"timeoutSeconds");
                    if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("timeoutSeconds must be 1-60");
                    return "WAIT_FOR_STOP " + enc(arg(a,"launchName")) + " " + (seconds * 1000);
                }));
        allTools.add(bridgeTool("get_process_output",
                "Read captured stdout and stderr for a debug launch. Output up to 8000 characters is returned "
                        + "in full; longer output returns the first 4000 and the last 4000 characters with an "
                        + "omission marker, plus the total length (stdoutChars, stderrChars, consoleChars). "
                        + "When the Eclipse Console is open, program output is often only in the 'console' field "
                        + "and stdout/stderr are empty; read 'console' first.",
                Map.of("launchName",str()), List.of("launchName"),
                a -> "GET_PROCESS_OUTPUT " + enc(arg(a,"launchName"))));

        allTools.add(SyncToolSpecification.builder()
                .tool(Tool.builder("wait_for_debug_pause", schema(
                        Map.of("launchName",str(),"timeoutSeconds",integer()), List.of("launchName","timeoutSeconds")))
                        .description("Legacy: wait up to 60 seconds until any thread is suspended, without resuming it. "
                                + "Prefer wait_for_stop, which reports the exact stop and its details in one call.").build())
                .callHandler((exchange, request) -> {
                    try {
                        Map<String,Object> a = request.arguments();
                        String launchName = arg(a,"launchName");
                        int seconds = intArg(a,"timeoutSeconds");
                        if (seconds < 1 || seconds > 60) return failure("timeoutSeconds must be 1-60");
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
                        String last;
                        do {
                            last = sendToEclipse("GET_THREADS " + enc(launchName), true);
                            // A newly launched JVM may not expose a debug target immediately.
                            if (last.contains("\"status\":\"error\"")
                                    && !last.contains("No active Java debug target yet")) return failure(last);
                            if (last.contains("\"suspended\":true")) return success(last);
                            Thread.sleep(250);
                        } while (System.nanoTime() < deadline);
                        return success("{\"status\":\"timeout\"}");
                    } catch (IOException | InterruptedException | IllegalArgumentException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        return failure(e.getMessage());
                    }
                }).build());

        // Restricted, opt-in test fixture HTTP reproduction; disabled in normal MCP sessions.
        FixtureHttpTools.register(allTools, command -> sendToEclipse(command, true));

        // Initialize MCP server.
        var server = McpServer.sync(transport)
                .serverInfo("eclipse-debugger", "1.0.0")
                .capabilities(
                        McpSchema.ServerCapabilities.builder()
                                .tools(false)
                                .build()
                )
                // Every tool is also recorded in the session report; with reporting off the list is unchanged.
                .tools(ReportingTools.wrap(allTools, REPORT))
                .build();

        System.err.println(
                "[Eclipse Agentic Debugger] MCP server initialized."
        );

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    finishOpenInvestigations("MCP server shutting down");
                    server.close();
                })
        );

        new CountDownLatch(1).await();
    }

    /** Debugging session report of this MCP server process (OPEN-30); off until main() reads the settings. */
    private static volatile SessionReport REPORT = new SessionReport(org.learningfromexperience.agentdebug.report.ReportRecorder.off());

    private static final java.util.concurrent.atomic.AtomicBoolean CLIENT_GONE = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean CLEANUP_STARTED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final CountDownLatch CLEANUP_DONE = new CountDownLatch(1);

    /** stdin reached its end: the agent closed the connection or exited. */
    private static void onClientGone() {
        if (!CLIENT_GONE.compareAndSet(false, true)) return;
        Thread exit = new Thread(() -> {
            finishOpenInvestigations("agent closed the MCP connection");
            System.exit(0);
        }, "AgentDebug-ClientGone");
        exit.start();
    }

    /**
     * Cleanup path 2 of 4 (agent gone, Eclipse still running). The full overview is in the plugin's
     * DebugInvestigationPolicy class comment and docs/decisions.md ADR-008.
     * Finishes every investigation this session left open (NORMAL: Eclipse removes its own
     * breakpoints and ends its own JVM; CONTROLLED: kept, as finish would). Runs once; a second
     * caller waits for the first. If this process is killed hard, Eclipse's idle lease still
     * cleans up later.
     */
    static void finishOpenInvestigations(String why) {
        if (!CLEANUP_STARTED.compareAndSet(false, true)) {
            try { CLEANUP_DONE.await(150, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return;
        }
        try {
            for (String id : TRACKER.openIds()) {
                try {
                    String reply = sendToEclipse("FINISH_INVESTIGATION " + enc(id), true);
                    System.err.println("[Eclipse Agentic Debugger] " + why + "; finished open investigation " + id + ": " + reply);
                } catch (Exception e) {
                    System.err.println("[Eclipse Agentic Debugger] " + why + "; could not finish " + id + ": " + e.getMessage()
                            + " (Eclipse cleans it up when its idle lease ends)");
                }
                REPORT.endedWithoutFinish(id, why);
            }
        } finally {
            // The session ends here: write the report (bounded to 5 s, never throws).
            REPORT.close();
            CLEANUP_DONE.countDown();
        }
    }

    private static CallToolResult success(String message) {

        return CallToolResult.builder()
                .content(List.of(
                        new McpSchema.TextContent(message)
                ))
                .build();
    }

    private static CallToolResult failure(String message) {

        return CallToolResult.builder()
                .isError(true)
                .content(List.of(
                        new McpSchema.TextContent(message)
                ))
                .build();
    }

    private static CallToolResult bridgeResult(String response) {
        return response.contains("\"status\":\"error\"")
                ? failure(response) : success(response);
    }

    private static Map<String,Object> str() { return Map.of("type","string"); }

    /**
     * Optional argument of the breakpoint, evaluation and step tools, only for the session report (OPEN-30 R4 phase 2):
     * why the call helps; shown at the node as the agent's statement. Never sent to Eclipse.
     */
    static final Map<String, Object> PURPOSE = Map.of("type", "string", "description",
            "Optional. One short sentence: why this helps the investigation (shown in the debugging session report)");
    private static Map<String,Object> bool() { return Map.of("type","boolean"); }
    private static String boolArg(Map<String,Object> args, String key) {
        if (!(args.get(key) instanceof Boolean value)) throw new IllegalArgumentException("Invalid " + key);
        return Boolean.toString(value);
    }
    private static String stringAllowEmpty(Map<String,Object> args, String key) {
        if (!(args.get(key) instanceof String s)) throw new IllegalArgumentException("Invalid " + key);
        return s;
    }

    private static Map<String,Object> integer() { return Map.of("type","integer","minimum",0); }

    /**
     * Arguments of finish_debug_investigation: the investigation, and only for the session report (OPEN-30, never sent
     * to Eclipse) the Markdown summary and the optional structured findings of the program flow (R4 phase 2).
     */
    static Map<String, Object> finishProperties() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("investigationId", str());
        p.put("summary", Map.of("type", "string", "description",
                "Your complete analysis in Markdown (headings, lists, tables and code are rendered): "
                        + "symptom, runtime evidence, root cause, fix suggestion"));
        p.put("title", Map.of("type", "string", "description",
                "Optional. One line naming the bug, e.g. \"Discount lost by integer division\""));
        p.put("trackedValue", Map.of("type", "object", "description",
                "Optional. The value you followed: its variable name, the value it should have and the value seen",
                "properties", Map.of("name", text, "expected", text, "actual", text)));
        p.put("findings", Map.of("type", "array", "description",
                "Optional. What you learned at each stop: at = the stop's location as Class.method:line, text = one "
                        + "or two sentences",
                "items", Map.of("type", "object", "properties", Map.of("at", text, "text", text))));
        p.put("rootCause", Map.of("type", "object", "description",
                "Optional. Where the bug is: file (e.g. OrderService.java), lines (e.g. \"23\" or \"22-25\") and a "
                        + "short explanation; the tool reads those lines from the project and shows them",
                "properties", Map.of("file", text, "lines", text, "explanation", text)));
        p.put("fix", Map.of("type", "string", "description", "Optional. The suggested fix (not applied)"));
        p.put("notObserved", Map.of("type", "array", "items", text, "description",
                "Optional. What your conclusion relies on that you did not see in the debugger"));
        p.put("assumedPath", Map.of("type", "string", "description",
                "Optional. The call path between the request and the first stop as you understand it, not stepped"));
        return p;
    }

    private static Map<String,Object> schema(Map<String,Object> properties, List<String> required) {
        return Map.of("type","object","properties",properties,
                "required",required,"additionalProperties",false);
    }

    private static String arg(Map<String,Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String s) || s.isBlank())
            throw new IllegalArgumentException("Missing " + name);
        return s;
    }

    private static int intArg(Map<String,Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof Number n) || n.intValue() != n.doubleValue() || n.intValue() < 0)
            throw new IllegalArgumentException("Invalid " + name);
        return n.intValue();
    }

    private static String mode(Map<String,Object> args) {
        Object requested = args.get("mode");
        if (requested == null) return "NORMAL";
        if (!(requested instanceof String mode) ||
                (!"NORMAL".equals(mode) && !"CONTROLLED".equals(mode))) {
            throw new IllegalArgumentException("mode must be NORMAL or CONTROLLED");
        }
        return (String) requested;
    }

    /** This session's open investigations (CORE-17). */
    static final InvestigationTracker TRACKER = new InvestigationTracker();
    /** Set when a request was attached to the open investigation automatically. */
    private static final ThreadLocal<String> ATTACHED = new ThreadLocal<>();

    /**
     * investigationId for requests that create breakpoints or sessions or control a session: the given one, else the
     * session's only open investigation (attached automatically, so cleanup includes it), else none. Since OPEN-40
     * also for control_debug_session: the plugin refuses control without an investigation on a session no open
     * investigation started, so a model that leaves the ID out still works on its own program.
     */
    private static String ownedId(Map<String,Object> args) {
        Object given = args.get("investigationId");
        String explicit = given == null ? null : arg(args, "investigationId");
        String id = TRACKER.resolve(explicit);
        if (explicit == null && id != null) ATTACHED.set(id);
        return id == null ? "" : " " + enc(id);
    }

    /** Adds "attachedToInvestigation" to a JSON reply when the ID was supplied automatically. */
    private static String noteAttachment(String response) {
        String id = ATTACHED.get();
        ATTACHED.remove();
        if (id == null || response == null || !response.startsWith("{")) return response;
        return "{\"attachedToInvestigation\":\"" + id + "\"," + response.substring(1);
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static SyncToolSpecification bridgeTool(String name, String description,
            Map<String,Object> properties, List<String> required,
            Function<Map<String,Object>,String> factory) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder(name, schema(properties, required)).description(description).build())
                .callHandler((exchange, request) -> {
                    ATTACHED.remove();
                    try {
                        return bridgeResult(noteAttachment(sendToEclipse(factory.apply(request.arguments()), true)));
                    } catch (IOException | IllegalArgumentException e) {
                        ATTACHED.remove();
                        return failure(e.getMessage());
                    }
                }).build();
    }

    /**
     * Commands that launch, restart or terminate JVMs. Eclipse gives these up to 120 s before
     * replying BRIDGE_TIMEOUT, so the client waits slightly longer. Must match
     * BridgeCommands.LONG_RUNNING in the Eclipse plugin.
     */
    private static final java.util.Set<String> LONG_RUNNING_COMMANDS = java.util.Set.of(
            "START_JUNIT_TEST", "START_JAVA_APPLICATION", "START_DEBUG",
            "RESTART_DEBUG", "FINISH_INVESTIGATION");

    /** Socket read timeout: always longer than the matching Eclipse-side deadline. */
    static int readTimeoutMillis(String command) {
        int space = command.indexOf(' ');
        String name = space < 0 ? command : command.substring(0, space);
        if ("WAIT_FOR_STOP".equals(name)) return 75_000;   // Eclipse deadline 70 s (BridgeCommands.WAITING)
        return LONG_RUNNING_COMMANDS.contains(name) ? 125_000 : 30_000;
    }

    private static String sendToEclipse(
            String command,
            boolean authenticate
    ) throws IOException {

        String request = command;

        if (authenticate) {

            if (!Files.exists(TOKEN_FILE)) {

                throw new IOException(
                        "Eclipse authentication token not found. "
                        + "Make sure the Runtime Eclipse Workbench is running."
                );
            }

            String token = Files.readString(
                    TOKEN_FILE,
                    StandardCharsets.UTF_8
            ).trim();

            request = "AUTH " + token + " " + command;
        }

        try (Socket socket = new Socket()) {

            socket.connect(
                    new InetSocketAddress(
                            ECLIPSE_HOST,
                            ECLIPSE_PORT
                    ),
                    3000
            );

            socket.setSoTimeout(readTimeoutMillis(command));

            try (
                    PrintWriter writer = new PrintWriter(
                            new OutputStreamWriter(
                                    socket.getOutputStream(),
                                    StandardCharsets.UTF_8
                            ),
                            true
                    );

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(),
                                    StandardCharsets.UTF_8
                            )
                    )
            ) {

                writer.println(request);

                String response = reader.readLine();

                if (response == null) {

                    throw new IOException(
                            "Eclipse closed the connection without a response"
                    );
                }

                TRACKER.observe(command, response);
                return response;
            }
        }
    }
}
