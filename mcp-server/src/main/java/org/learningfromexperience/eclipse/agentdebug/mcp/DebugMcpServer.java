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
                        "investigationId", Map.of("type", "string", "description", "Optional ID from begin_debug_investigation")
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
                    try {
                        String response = sendToEclipse(
                                "SET_BREAKPOINT " + enc(typeName) + " " + parsedLine
                                        + optionalId(arguments), true);
                        return bridgeResult(response);
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
                        + "Pass the returned investigationId to set_breakpoint and the launch tool. "
                        + "Always finish the investigation when done.",
                Map.of("mode", Map.of("type", "string", "enum", List.of("NORMAL", "CONTROLLED"))),
                List.of(), a -> "BEGIN_INVESTIGATION " + enc(mode(a))));
        allTools.add(bridgeTool("finish_debug_investigation",
                "Finish the exact investigationId. NORMAL terminates only the session it started, "
                        + "removes only its own newly created breakpoints, and leaves user sessions and breakpoints intact. "
                        + "CONTROLLED preserves the debug session suspended and breakpoints for inspection. "
                        + "Call once after reporting actual evidence, even if a debugger step fails.",
                Map.of("investigationId", str()), List.of("investigationId"),
                a -> "FINISH_INVESTIGATION " + enc(arg(a,"investigationId"))));
        allTools.add(bridgeTool("remove_breakpoint", "Remove only agent-owned line breakpoints.",
                Map.of("typeName",str(),"lineNumber",integer()), List.of("typeName","lineNumber"),
                a -> "REMOVE_BREAKPOINT " + enc(arg(a,"typeName")) + " " + intArg(a,"lineNumber")));
        // Phase 1: optional capabilities; legacy tool names and schemas remain unchanged.
        allTools.add(bridgeTool("set_conditional_breakpoint",
                "Set a new Java conditional line breakpoint. Eclipse suspends only when the Java condition is true. "
                + "Existing user/agent breakpoints at that line are never modified. Prefer for record-specific defects.",
                Map.of("typeName",str(), "lineNumber",integer(), "condition",str(), "investigationId",str()),
                List.of("typeName", "lineNumber", "condition"),
                a -> "SET_CONDITIONAL_BREAKPOINT " + enc(arg(a,"typeName")) + " " + intArg(a,"lineNumber")
                        + " " + enc(arg(a,"condition")) + optionalId(a)));
        allTools.add(bridgeTool("set_exception_breakpoint",
                "Create an Eclipse Java exception breakpoint for a fully-qualified exception type. "
                + "Caught and/or uncaught must be true. Existing exception breakpoints are never modified.",
                Map.of("exceptionName",str(),"caught",bool(),"uncaught",bool(),"investigationId",str()),
                List.of("exceptionName","caught","uncaught"),
                a -> "SET_EXCEPTION_BREAKPOINT " + enc(arg(a,"exceptionName")) + " "
                        + enc(boolArg(a,"caught")) + " " + enc(boolArg(a,"uncaught")) + optionalId(a)));
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
                            + " " + enc(arg(a,"expression")) + optionalId(a);
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
                        + " " + enc(stringAllowEmpty(a,"methodName")) + optionalId(a)));
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
                        + enc(arg(a, "mainType")) + optionalId(a)));
        allTools.add(bridgeTool("start_debug_session", "Start or reuse a saved Eclipse debug launch configuration.",
                Map.of("launchName",str(),"investigationId",str()), List.of("launchName"),
                a -> "START_DEBUG " + enc(arg(a,"launchName")) + optionalId(a)));
        allTools.add(bridgeTool("show_debug_location",
                "Select the agent-controlled suspended session in Eclipse Debug view and open its source at the current line. Does not step or resume.",
                Map.of("launchName",str()), List.of("launchName"),
                a -> "SHOW_DEBUG_LOCATION " + enc(arg(a,"launchName"))));
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
                        "expression",str(),"allowSideEffects",Map.of("type","boolean")),
                List.of("launchName","threadIndex","frameIndex","expression","allowSideEffects"),
                a -> {
                    if (!Boolean.TRUE.equals(a.get("allowSideEffects")))
                        throw new IllegalArgumentException("Explicit consent required for evaluation");
                    return "EVALUATE_EXPRESSION " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")
                            + " " + intArg(a,"frameIndex") + " " + enc(arg(a,"expression"));
                }));
        allTools.add(bridgeTool("control_debug_session", "PAUSE, RESUME, STEP_INTO, STEP_OVER, STEP_RETURN or STOP execution. "
                        + "For an investigation use finish_debug_investigation for safe completion; "
                        + "do not STOP or resume unrelated/user-owned sessions.",
                Map.of("launchName",str(),"threadIndex",integer(),"action",Map.of("type","string",
                        "enum",List.of("PAUSE","RESUME","STEP_INTO","STEP_OVER","STEP_RETURN","STOP")),
                        "investigationId",str()),
                List.of("launchName","threadIndex","action"),
                a -> "CONTROL_DEBUG " + enc(arg(a,"launchName")) + " " + intArg(a,"threadIndex")
                        + " " + enc(arg(a,"action")) + optionalId(a)));
        allTools.add(bridgeTool("get_process_output", "Read captured stdout and stderr for a debug launch.",
                Map.of("launchName",str()), List.of("launchName"),
                a -> "GET_PROCESS_OUTPUT " + enc(arg(a,"launchName"))));

        allTools.add(SyncToolSpecification.builder()
                .tool(Tool.builder("wait_for_debug_pause", schema(
                        Map.of("launchName",str(),"timeoutSeconds",integer()), List.of("launchName","timeoutSeconds")))
                        .description("Wait up to 60 seconds for a thread to suspend, without resuming it.").build())
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

        // Initialize MCP server.
        var server = McpServer.sync(transport)
                .serverInfo("eclipse-debugger", "1.0.0")
                .capabilities(
                        McpSchema.ServerCapabilities.builder()
                                .tools(false)
                                .build()
                )
                .tools(allTools)
                .build();

        System.err.println(
                "[Eclipse Agentic Debugger] MCP server initialized."
        );

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    server.close();
                })
        );

        new CountDownLatch(1).await();
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

    private static String optionalId(Map<String,Object> args) {
        Object id = args.get("investigationId");
        return id == null ? "" : " " + enc(arg(args, "investigationId"));
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
                    try {
                        return bridgeResult(sendToEclipse(factory.apply(request.arguments()), true));
                    } catch (IOException | IllegalArgumentException e) {
                        return failure(e.getMessage());
                    }
                }).build();
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

            socket.setSoTimeout(30000);

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

                return response;
            }
        }
    }
}
