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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code agent-eclipse-connection} (extended suite): the link the agent's MCP server uses to send every command
 * to the Eclipse plugin (the bridge) stays healthy under a slow command, a busy state-change lane, many parallel
 * requests and a wrong token. Uses the test-only BRIDGE_TEST_SLEEP command, which the bridge accepts only when the
 * test Eclipse was started with AGENTDEBUG_E2E=1 (DevE2E does this). Launches nothing and changes no project,
 * breakpoint or launch configuration.
 *
 * @author Ayan Dutta
 */
@DisplayName("Agent-Eclipse connection: the bridge under load and faults")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class AgentEclipseConnectionTests extends EclipseIntegrationTest {
    private String token;

    @Override String group() { return "agent-eclipse-connection"; }

    /** Reads the bridge token and refuses to run unless the bridge has its diagnostics and the test hook is enabled. */
    @BeforeAll
    void preflight() throws Exception {
        token = Files.readString(Path.of(System.getProperty("user.home"), ".eclipse-agentdebug",
                "bridge-" + env.port + ".token"), StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) throw new IOException("Empty Eclipse bridge authentication token");
        Map<String, Object> diag = call("GET_BRIDGE_DIAGNOSTICS", 5_000);
        require("success".equals(diag.get("status")), "The bridge has no GET_BRIDGE_DIAGNOSTICS: plugin not updated. Reply: " + diag);
        Map<String, Object> hook = call("BRIDGE_TEST_SLEEP 0 READ", 5_000);
        require("success".equals(hook.get("status")),
                "Test hook disabled. Close the test Eclipse and let DevE2E start it (sets AGENTDEBUG_E2E=1). Reply: " + hook);
    }

    /** Whether the bridge's state-change lane is free again after the test. */
    @AfterEach
    void recordLanes() {
        put("lanesIdleAfter", lanesIdle());
    }

    /** BR-E1, BR-E2, BR-E4, BR-E5: a 27 s state change must not block reads; it times out at
     *  the 25 s deadline with an operation ID, is visible in diagnostics and the log, and its
     *  real result is retrievable afterwards. */
    @Test
    @DisplayName("A slow command does not block other requests")
    void slowCommandDoesNotBlock() throws Exception {
        ExecutorService pool = daemonPool(1);
        try {
            long t0 = System.nanoTime();
            Future<Map<String, Object>> slow = pool.submit(() -> call("BRIDGE_TEST_SLEEP 27000 MUTATE", 40_000));
            Thread.sleep(1_000);

            // BR-E1: reads stay fast while the state-change lane is occupied.
            Map<String, Long> readMs = new LinkedHashMap<>();
            long r = System.nanoTime();
            require("PONG".equals(raw("PING", 5_000)), "PING answered");
            readMs.put("PING", ms(r));
            for (String read : List.of("LIST_DEBUG_SESSIONS", "GET_E2E_INFO", "LIST_BREAKPOINTS")) {
                r = System.nanoTime();
                Map<String, Object> reply = call(read, 5_000);
                readMs.put(read, ms(r));
                require("success".equals(reply.get("status")), read + " succeeded: " + reply);
            }
            put("readLatencyMs", readMs);
            for (var entry : readMs.entrySet())
                require(entry.getValue() < 1_000, entry.getKey() + " took " + entry.getValue() + " ms while a state change ran");

            // BR-E4: diagnostics show the in-flight command.
            Map<String, Object> diag = call("GET_BRIDGE_DIAGNOSTICS", 5_000);
            String diagText = Json.stringify(diag);
            require(diagText.contains("BRIDGE_TEST_SLEEP") && diagText.contains("\"lane\":\"MUTATE\""),
                    "diagnostics list the running command: " + diagText);

            // BR-E2: explicit timeout at the 25 s deadline, with an operation ID.
            Map<String, Object> timeout = slow.get(40, TimeUnit.SECONDS);
            long timeoutMs = ms(t0);
            put("timeoutReplyAfterMs", timeoutMs);
            require("BRIDGE_TIMEOUT".equals(timeout.get("code")), "BRIDGE_TIMEOUT reply: " + timeout);
            require(timeoutMs >= 24_000 && timeoutMs < 27_000, "timeout reply after " + timeoutMs + " ms");
            require(timeout.get("operationId") instanceof Number, "operationId present: " + timeout);
            // The shared test JSON parser returns numbers as Double; the bridge expects an integer.
            long id = ((Number) timeout.get("operationId")).longValue();

            // BR-E2/U3 on the real bridge: the late result is retrievable.
            Map<String, Object> done = null;
            for (int i = 0; i < 20; i++) {
                Map<String, Object> op = call("GET_OPERATION " + id, 5_000);
                String state = String.valueOf(((Map<?, ?>) op.get("operation")).get("state"));
                if (state.startsWith("COMPLETED")) { done = op; break; }
                Thread.sleep(500);
            }
            require(done != null, "operation completed within 10 s after the timeout");
            require(Json.stringify(done).contains("\"slept\":27000"), "late result returned: " + done);

            // BR-E5: the log has a SLOW entry with a stack trace and never contains the token.
            Path log = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "bridge-" + env.port + ".log");
            String text = Files.readString(log, StandardCharsets.UTF_8);
            require(text.contains("SLOW op=" + id) && text.contains("cmd=BRIDGE_TEST_SLEEP"), "slow command logged in " + log);
            require(text.contains("TIMEOUT op=" + id), "timeout logged");
            require(!text.contains(token), "token never written to the log");
            put("logFile", log.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    /** BR-E3: a real state-changing command waits briefly, then gets BRIDGE_BUSY naming the running
     *  command; after the lane frees, the same command succeeds. Cleans up its own investigation. */
    @Test
    @DisplayName("A second state change gets a clear busy answer")
    void busyCommandReported() throws Exception {
        ExecutorService pool = daemonPool(1);
        String investigation = null;
        try {
            Future<Map<String, Object>> holder = pool.submit(() -> call("BRIDGE_TEST_SLEEP 12000 MUTATE", 30_000));
            Thread.sleep(1_000);
            long t0 = System.nanoTime();
            Map<String, Object> busy = call("BEGIN_INVESTIGATION " + enc("NORMAL"), 15_000);
            long busyMs = ms(t0);
            put("busyReplyAfterMs", busyMs);
            require("BRIDGE_BUSY".equals(busy.get("code")), "BRIDGE_BUSY reply: " + busy);
            require("BRIDGE_TEST_SLEEP".equals(busy.get("runningCommand")), "names the running command: " + busy);
            require(busyMs >= 4_000 && busyMs < 8_000, "busy reply after " + busyMs + " ms (busy wait 5 s)");
            require("success".equals(holder.get(30, TimeUnit.SECONDS).get("status")), "holder finished normally");

            Map<String, Object> begun = call("BEGIN_INVESTIGATION " + enc("NORMAL"), 15_000);
            require("success".equals(begun.get("status")), "state change succeeds once the lane is free: " + begun);
            investigation = String.valueOf(begun.get("investigationId"));
        } finally {
            if (investigation != null) {
                Map<String, Object> finished = call("FINISH_INVESTIGATION " + enc(investigation), 30_000);
                put("cleanup", "success".equals(finished.get("status")));
            }
            pool.shutdownNow();
        }
        require(Boolean.TRUE.equals(evidence.get("cleanup")), "investigation cleaned up");
    }

    /** BR-E: 20 parallel read requests all succeed (no dropped connections, no BUSY). */
    @Test
    @DisplayName("Twenty parallel requests all succeed")
    void parallelRequestsSucceed() throws Exception {
        ExecutorService pool = daemonPool(20);
        try {
            List<Future<Map<String, Object>>> replies = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) replies.add(pool.submit(() -> call("GET_E2E_INFO", 15_000)));
            int ok = 0;
            for (Future<Map<String, Object>> reply : replies)
                if ("success".equals(reply.get(20, TimeUnit.SECONDS).get("status"))) ok++;
            put("successful", ok);
            put("totalMs", ms(t0));
            require(ok == 20, ok + "/20 parallel reads succeeded");
        } finally {
            pool.shutdownNow();
        }
    }

    /** BR-E7: authentication behaves exactly as before on every path. */
    @Test
    @DisplayName("A missing or wrong token is refused")
    void wrongTokenRejected() throws Exception {
        String missing = raw("GET_E2E_INFO", 5_000);
        String wrong = raw("AUTH not-the-token BRIDGE_TEST_SLEEP 0 READ", 5_000);
        put("missingAuth", missing);
        put("wrongToken", wrong);
        require(missing.contains("Authentication required"), "missing AUTH rejected: " + missing);
        require(wrong.contains("Invalid authentication token"), "wrong token rejected: " + wrong);
    }

    // ---------------------------------------------------------------- helpers

    private boolean lanesIdle() {
        try {
            String diag = Json.stringify(call("GET_BRIDGE_DIAGNOSTICS", 5_000));
            return diag.contains("\"mutateLaneFree\":true");
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** Authenticated command; arguments must already be in bridge wire format. */
    private Map<String, Object> call(String command, int timeoutMs) throws IOException {
        String reply = raw("AUTH " + token + " " + command, timeoutMs);
        Object parsed = Json.parse(reply);
        if (!(parsed instanceof Map<?, ?> map)) throw new IOException("Unexpected bridge reply: " + reply);
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("command", command.split(" ", 2)[0]);
        trace.put("response", result);
        synchronized (bridge.traces) { bridge.traces.add(trace); }
        return result;
    }

    private String raw(String line, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", env.port), 5_000);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = in.readLine();
            if (reply == null) throw new IOException("Bridge closed the connection without a reply");
            return reply;
        }
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ExecutorService daemonPool(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "bridge-fault-client");
            thread.setDaemon(true);
            return thread;
        });
    }
}
