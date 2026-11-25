package org.learningfromexperience.agentdebug.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test client for the Eclipse bridge: the authenticated local connection the agent's MCP server uses to send every
 * debugger command to the Eclipse plugin. The token comes from the bridge's token file for the port. Every call is
 * kept in {@link #traces} for the test report.
 *
 * @author Ayan Dutta
 */
final class Bridge {
    private final int port;
    private final String token;
    final List<Map<String, Object>> traces = new ArrayList<>();

    /** The bridge on AGENTDEBUG_PORT (8765 when unset); used by the ownership harness, which runs inside Eclipse. */
    Bridge() throws IOException {
        this(Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765")));
    }

    Bridge(int port) throws IOException {
        this.port = port;
        token = Files.readString(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug",
                port == 8765 ? "bridge.token" : "bridge-" + port + ".token"), StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) throw new IOException("Empty Eclipse bridge authentication token");
    }

    /**
     * Sends one command; fails on an error reply unless {@code tolerateError}. Numbers are sent as decimal text,
     * every other argument (also booleans and investigation IDs) as URL-safe Base64: a Base64 line number
     * caused a NumberFormatException (5 became "NQ").
     */
    Map<String, Object> call(String command, boolean tolerateError, Object... args) throws IOException {
        StringBuilder request = new StringBuilder("AUTH ").append(token).append(' ').append(command);
        for (Object value : args) {
            request.append(' ').append(value instanceof Number
                    ? value.toString()
                    : Base64.getUrlEncoder().withoutPadding().encodeToString(str(value).getBytes(StandardCharsets.UTF_8)));
        }
        String json;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(30000);
            try (OutputStream out = socket.getOutputStream(); BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.write((request + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                json = in.readLine();
            }
        }
        if (json == null) throw new IOException("Bridge returned no response for " + command);
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?>)) throw new IOException("Unexpected bridge response: " + json);
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) parsed;
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("command", command);
        trace.put("arguments", Arrays.stream(args).map(Bridge::str).toList());
        trace.put("response", result);
        synchronized (traces) { traces.add(trace); }
        if (!tolerateError && "error".equals(result.get("status"))) throw new IOException(command + " failed: " + json);
        return result;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
