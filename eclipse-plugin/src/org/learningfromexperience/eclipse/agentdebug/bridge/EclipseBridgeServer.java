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

import org.learningfromexperience.eclipse.agentdebug.debug.EclipseDebugService;
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseDebugService.DebugSessionInfo;
import org.learningfromexperience.eclipse.agentdebug.debug.EclipseAgentOperations;

/**
 * Accepts authenticated local requests from the MCP server.
 *
 * @author Ayan Dutta
 */
public class EclipseBridgeServer implements AutoCloseable {

    private static final int PORT = 8765;

    private static final String HOST = "127.0.0.1";

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

        Path tokenFile = tokenDirectory.resolve("bridge.token");

        Files.writeString(
                tokenFile,
                authenticationToken,
                StandardCharsets.UTF_8
        );

        // Listen only on the local IPv4 loopback interface.
        serverSocket = new ServerSocket(
                PORT,
                10,
                InetAddress.getByName(HOST)
        );

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

                handleClient(client);

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

            client.setSoTimeout(30000);

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

            // Always answer the client, including when a debugger operation throws an
            // unchecked exception or a linkage error. Otherwise callers only see EOF.
            try {
                String response = switch (command) {
                    case "LIST_DEBUG_SESSIONS" -> listDebugSessionsAsJson();
                    default -> operations.execute(command);
                };
                writer.println(response);
            } catch (Throwable failure) {
                System.err.println("[Eclipse Agentic Debugger] Bridge command failed: " + command);
                failure.printStackTrace(System.err);
                String detail = failure.getClass().getName() + ": "
                        + String.valueOf(failure.getMessage());
                writer.println("{\"status\":\"error\",\"message\":"
                        + EclipseAgentOperations.q(detail) + "}");
            }

        } catch (IOException e) {

            System.err.println(
                    "[Eclipse Agentic Debugger] Client error: "
                    + e.getMessage()
            );
        }
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
