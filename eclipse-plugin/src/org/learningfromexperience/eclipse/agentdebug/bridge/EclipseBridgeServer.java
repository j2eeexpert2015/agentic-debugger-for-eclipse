package org.learningfromexperience.eclipse.agentdebug.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.security.MessageDigest;
import java.security.SecureRandom;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.learningfromexperience.agentdebug.report.activity.ActivityStore;
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseDebugService;
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseDebugService.DebugSessionInfo;
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseAgentOperations;

/**
 * Accepts authenticated local requests from the MCP server.
 *
 * <p>Each connection is handled on a bounded pool and every command goes through
 * {@link BridgeDispatcher} (parallel reads, ordered state changes, deadlines, timing log). The
 * earlier single-threaded mode ({@code AGENTDEBUG_BRIDGE_MODE=legacy}) was removed on 25 Nov 2026
 * (OPEN-38): the concurrent bridge passed every regression since CORE-13.
 *
 * @author Ayan Dutta
 */
public class EclipseBridgeServer implements AutoCloseable {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765"));

    private static final String HOST = "127.0.0.1";

    /** Live events for the Agent Activity view (OPEN-36); handled before the lanes. */
    static final String ACTIVITY_EVENT_PREFIX = "ACTIVITY_EVENT ";

    /** Test-only fault injection (BRIDGE_TEST_SLEEP); enabled only for the dedicated E2E Eclipse. */
    private static final boolean TEST_HOOKS = "1".equals(System.getenv("AGENTDEBUG_E2E"));

    /** Time allowed for a client to send its single request line (concurrent mode). */
    private static final int REQUEST_READ_TIMEOUT_MS = 5_000;

    private static final int CONNECTION_THREADS = 16;

    private final BridgeLog log = BridgeLog.forPort(PORT);

    private BridgeDispatcher dispatcher;

    private ThreadPoolExecutor connections;

    private final EclipseDebugService debugService =
            new EclipseDebugService();

    private final EclipseAgentOperations operations = new EclipseAgentOperations();

    private ServerSocket serverSocket;

    private Thread serverThread;

    private String authenticationToken;

    public synchronized void start() throws IOException {

        if (serverSocket != null && !serverSocket.isClosed()) {
            return;
        }

        // Create a secure random token for this Eclipse session.
        byte[] tokenBytes = new byte[32];

        new SecureRandom().nextBytes(tokenBytes);

        authenticationToken =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(tokenBytes);

        // Store the token for the local MCP process.
        Path tokenDirectory = Path.of(
                System.getProperty("user.home"),
                ".eclipse-agentdebug"
        );

        Files.createDirectories(tokenDirectory);

        Path tokenFile = tokenDirectory.resolve(PORT == 8765 ? "bridge.token" : "bridge-" + PORT + ".token");

        // Claim the port before publishing credentials for this instance.
        serverSocket = new ServerSocket(PORT, 10, InetAddress.getByName(HOST));
        try {
            Files.writeString(tokenFile, authenticationToken, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            serverSocket.close();
            throw failure;
        }

        dispatcher = new BridgeDispatcher(this::executeCommand, log,
                BridgeDispatcher.Config.defaults(TEST_HOOKS));
        connections = new ThreadPoolExecutor(CONNECTION_THREADS, CONNECTION_THREADS,
                30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "Eclipse-AgentDebug-Bridge-Connection");
                    thread.setDaemon(true);
                    return thread;
                });
        connections.allowCoreThreadTimeOut(true);
        log.event("BRIDGE_START port=" + PORT + " mode=concurrent testHooks=" + TEST_HOOKS);

        serverThread = new Thread(
                this::acceptConnections,
                "Eclipse-AgentDebug-Bridge"
        );

        serverThread.setDaemon(true);

        serverThread.start();

        System.out.println(
                "[Eclipse Agentic Debugger] Bridge server started on "
                + HOST + ":" + PORT
        );
    }

    private void acceptConnections() {

        while (!serverSocket.isClosed()) {

            try {

                Socket client = serverSocket.accept();

                dispatchConnection(client);

            } catch (IOException e) {

                if (!serverSocket.isClosed()) {

                    System.err.println(
                            "[Eclipse Agentic Debugger] Bridge error: "
                            + e.getMessage()
                    );
                }
            }
        }
    }

    private void handleClient(Socket client) {

        try (
                client;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                client.getInputStream(),
                                StandardCharsets.UTF_8
                        )
                );

                PrintWriter writer = new PrintWriter(
                        client.getOutputStream(),
                        true,
                        StandardCharsets.UTF_8
                )
        ) {

            // A client gets a few seconds to send its one request line, so an idle connection
            // cannot occupy a connection thread. Command time is governed by the dispatcher's
            // per-command deadlines, not by this socket timeout.
            client.setSoTimeout(REQUEST_READ_TIMEOUT_MS);

            String request = reader.readLine();

            if (request == null) {
                return;
            }

            // Preserve our existing connectivity test.
            if ("PING".equals(request)) {

                writer.println("PONG");

                return;
            }

            // Authenticated requests use:
            // AUTH <token> <command>

            String[] parts = request.split(" ", 3);

            if (parts.length != 3 || !"AUTH".equals(parts[0])) {

                writer.println(
                        "{\"status\":\"error\","
                        + "\"message\":\"Authentication required\"}"
                );

                return;
            }

            if (!isAuthorized(parts[1])) {

                writer.println(
                        "{\"status\":\"error\","
                        + "\"message\":\"Invalid authentication token\"}"
                );

                return;
            }

            String command = parts[2];

            // Agent Activity (OPEN-36): outside the READ/MUTATE lanes, so live events never wait
            // behind a debugger operation and never delay one. Memory update only; never throws.
            if (command.startsWith(ACTIVITY_EVENT_PREFIX)) {
                writer.println(ActivityStore.global().accept(command.substring(ACTIVITY_EVENT_PREFIX.length())));
                return;
            }

            // Lanes, deadlines, BUSY/TIMEOUT replies and timing log; never throws.
            writer.println(dispatcher.dispatch(command));

        } catch (IOException e) {

            System.err.println(
                    "[Eclipse Agentic Debugger] Client error: "
                    + e.getMessage()
            );
        }
    }

    /** Hands the connection to the pool; if the pool is full, says so and closes. */
    private void dispatchConnection(Socket client) {
        try {
            connections.execute(() -> handleClient(client));
        } catch (RejectedExecutionException full) {
            log.event("CONNECTION_REJECTED pool full");
            try (client; PrintWriter writer = new PrintWriter(
                    client.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println("{\"status\":\"error\",\"code\":\"BRIDGE_BUSY\","
                        + "\"message\":\"Eclipse bridge has too many open requests; retry shortly\"}");
            } catch (IOException ignored) {
                // The client is already gone.
            }
        }
    }

    /** The command executor behind the dispatcher. */
    private String executeCommand(String command) {
        return switch (command) {
            case "LIST_DEBUG_SESSIONS" -> listDebugSessionsAsJson();
            default -> operations.execute(command);
        };
    }

    private boolean isAuthorized(String suppliedToken) {

        return MessageDigest.isEqual(
                authenticationToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String listDebugSessionsAsJson() {

        List<DebugSessionInfo> sessions =
                debugService.listDebugSessions();

        StringBuilder json = new StringBuilder();

        json.append("{");
        json.append("\"status\":\"success\",");
        json.append("\"sessions\":[");

        for (int i = 0; i < sessions.size(); i++) {

            DebugSessionInfo session = sessions.get(i);

            if (i > 0) {
                json.append(",");
            }

            json.append("{");

            json.append("\"launchName\":\"")
                    .append(escapeJson(session.launchName()))
                    .append("\",");

            json.append("\"targetName\":\"")
                    .append(escapeJson(session.targetName()))
                    .append("\",");

            json.append("\"terminated\":")
                    .append(session.terminated())
                    .append(",");

            json.append("\"disconnected\":")
                    .append(session.disconnected())
                    .append(",");

            json.append("\"suspended\":")
                    .append(session.suspended());

            json.append("}");
        }

        json.append("]");
        json.append("}");

        return json.toString();
    }

    private String escapeJson(String value) {

        if (value == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (char character : value.toCharArray()) {

            switch (character) {

                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");

                default -> {

                    if (character < 0x20) {
                        result.append(
                                String.format("\\u%04x", (int) character)
                        );
                    } else {
                        result.append(character);
                    }
                }
            }
        }

        return result.toString();
    }

    @Override
    public synchronized void close() {
        log.event("BRIDGE_STOP port=" + PORT);
        if (connections != null) connections.shutdownNow();
        if (dispatcher != null) dispatcher.close();
        operations.close();

        if (serverSocket != null) {

            try {

                serverSocket.close();

                System.out.println(
                        "[Eclipse Agentic Debugger] Bridge server stopped."
                );

            } catch (IOException e) {

                System.err.println(
                        "[Eclipse Agentic Debugger] Shutdown error: "
                        + e.getMessage()
                );
            }
        }
    }
}
