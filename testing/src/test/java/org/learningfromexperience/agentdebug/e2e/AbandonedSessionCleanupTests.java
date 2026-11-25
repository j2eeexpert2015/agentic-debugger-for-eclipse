package org.learningfromexperience.agentdebug.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code abandoned-session-cleanup} (extended suite): investigations an agent left open are cleaned up, and an
 * agent's changes always belong to an investigation. The investigations here use a 5 s lease (the time without
 * calls after which an investigation counts as abandoned) instead of the default 10 minutes.
 *
 * @author Ayan Dutta
 */
@DisplayName("Abandoned session cleanup: investigations left open by an agent")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class AbandonedSessionCleanupTests extends EclipseIntegrationTest {
    private static final int LEASE_SECONDS = 5;
    private static final String STEP_APP = JAVA_PKG + "StepFilterApplication";

    @Override String group() { return "abandoned-session-cleanup"; }

    @BeforeAll
    void preflight() throws Exception {
        Map<String, Object> probe = bridge.call("BEGIN_INVESTIGATION", false, "NORMAL", LEASE_SECONDS);
        require(probe.get("leaseSeconds") instanceof Number, "The bridge has no investigation lease: deploy a current build. Reply: " + probe);
        bridge.call("FINISH_INVESTIGATION", false, str(probe.get("investigationId")));
    }

    /** A NORMAL investigation stopped at a breakpoint and then left without calls: its breakpoint is removed and its
     *  JVM ended within 30 s; a late finish says it expired. */
    @Test
    @DisplayName("An idle investigation is cleaned up after its lease")
    void idleSessionCleanedUp() throws Exception {
        String investigation = beginLeased("NORMAL");
        int line = javaLine("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, STEP_APP, line, investigation);
        String launch = startJava("StepFilterApplication", investigation);
        require("BREAKPOINT".equals(bridge.call("WAIT_FOR_STOP", false, launch, 20_000).get("reason")), "Stopped at the breakpoint");
        // From here no call names the investigation; only neutral inventory reads.
        long seconds = waitUntil(() -> !agentBreakpoint(STEP_APP, line) && !live(launch), 30);
        require(seconds >= 0, "Breakpoint removed and JVM ended within 30 s of going idle");
        Map<String, Object> late = bridge.call("FINISH_INVESTIGATION", true, investigation);
        require(str(late.get("message")).contains("expired"), "A late finish reports the expiry: " + late);
        put("cleanedUpAfterSeconds", seconds);
    }

    /** A 12 s WAIT_FOR_STOP on a 5 s lease does not expire the investigation; it finishes normally afterwards. */
    @Test
    @DisplayName("A long wait keeps the investigation alive")
    void longWaitKeepsSession() throws Exception {
        String investigation = beginLeased("NORMAL");
        String launch = startJava("LongRunningApplication", investigation);
        Map<String, Object> wait = bridge.call("WAIT_FOR_STOP", false, launch, 12_000);
        require("TIMEOUT".equals(wait.get("reason")), "The long wait timed out as expected: " + wait);
        Map<String, Object> finish = bridge.call("FINISH_INVESTIGATION", false, investigation);
        require(Boolean.TRUE.equals(finish.get("completed")), "Finished normally after a wait longer than the lease: " + finish);
        put("waitSeconds", 12);
    }

    /** A CONTROLLED investigation expires, but its suspended session and breakpoint are kept for inspection. */
    @Test
    @DisplayName("A CONTROLLED investigation keeps its session after expiry")
    void controlledModeKeepsSession() throws Exception {
        String investigation = beginLeased("CONTROLLED");
        int line = javaLine("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, STEP_APP, line, investigation);
        String launch = startJava("StepFilterApplication", investigation);
        Map<String, Object> stop = bridge.call("WAIT_FOR_STOP", false, launch, 20_000);
        require("BREAKPOINT".equals(stop.get("reason")), "Stopped at the breakpoint");
        try {
            // Any call naming the investigation would keep it alive, so just wait: lease 5 s + checker 2 s + margin.
            Thread.sleep(15_000);
            require(agentBreakpoint(STEP_APP, line) && live(launch), "CONTROLLED: breakpoint and session kept");
            Map<String, Object> late = bridge.call("FINISH_INVESTIGATION", true, investigation);
            require(str(late.get("message")).contains("expired") && str(late.get("message")).contains("kept"),
                    "The CONTROLLED investigation expired and says its resources were kept: " + late);
            put("keptAfterExpiry", true);
        } finally {
            // Test cleanup of the kept resources (no investigation any more; since OPEN-40 a STOP without an
            // investigation is refused for a session no open investigation started, so the test command ends it).
            bridge.call("TEST_TERMINATE_LAUNCH", true, launch, "all");
            bridge.call("REMOVE_BREAKPOINT", true, STEP_APP, line);
        }
    }

    /**
     * Through a real MCP server process: a breakpoint and a launch sent without investigationId join the one open
     * investigation. The program is confirmed stopped at that breakpoint (read by the test through the bridge, not
     * by the agent), as when an agent crashes in the middle of an investigation; then the agent's connection closes
     * without a finish, and the breakpoint is removed and the stopped JVM ended.
     */
    @Test
    @DisplayName("Closing the agent connection cleans up a program stopped at a breakpoint")
    void agentDisconnectCleansUp() throws Exception {
        int line = javaLine("StepFilterApplication", "values.add(10);");
        String launch;
        try (Mcp mcp = new Mcp(env.root, env.port)) {
            String begin = mcp.call("begin_debug_investigation", "{\"mode\":\"NORMAL\"}");
            require(begin.contains("investigationId"), "begin: " + begin);
            String bp = mcp.call("set_breakpoint", "{\"typeName\":\"" + STEP_APP + "\",\"lineNumber\":" + line + "}");
            require(bp.contains("attachedToInvestigation"), "A breakpoint without ID joins the open investigation: " + bp);
            String start = mcp.call("start_java_application", "{\"projectName\":\"" + JAVA_PROJECT + "\",\"mainType\":\"" + STEP_APP + "\"}");
            require(start.contains("attachedToInvestigation"), "A launch without ID joins the open investigation: " + start);
            launch = str(obj(Json.parse(start.substring(start.indexOf('{')))).get("launchName"));
            require(!launch.isBlank(), "Launch name in " + start);
            Map<String, Object> stop = waitForStop(launch, 20_000);
            Map<String, Object> at = obj(stop.get("location"));
            require("BREAKPOINT".equals(stop.get("reason")) && STEP_APP.equals(at.get("className"))
                    && num(at.get("lineNumber")) == line, "The program is stopped at the breakpoint before the agent leaves: " + stop);
            require(live(launch), "The stopped program is still running before the agent leaves");
            put("stoppedAt", "StepFilterApplication:" + line);
            put("mcpExitCode", mcp.closeInputAndWait(60));
        }
        String ended = launch;
        long seconds = waitUntil(() -> !agentBreakpoint(STEP_APP, line) && !live(ended), 30);
        require(seconds >= 0, "After the agent's connection closed: breakpoint removed and the stopped JVM ended");
        put("cleanedUpAfterSeconds", seconds);
    }

    /** With two investigations open, a breakpoint without investigationId is refused (it is unclear which one owns it). */
    @Test
    @DisplayName("A call without investigation is refused when several are open")
    void ambiguousCallRejected() throws Exception {
        try (Mcp mcp = new Mcp(env.root, env.port)) {
            String a = mcp.call("begin_debug_investigation", "{\"mode\":\"NORMAL\"}");
            String b = mcp.call("begin_debug_investigation", "{\"mode\":\"NORMAL\"}");
            require(a.contains("investigationId") && b.contains("investigationId"), "Two investigations open");
            String bp = mcp.call("set_breakpoint", "{\"typeName\":\"" + STEP_APP + "\",\"lineNumber\":9}");
            require(bp.contains("Several investigations are open"), "A breakpoint without ID refused: " + bp);
            put("mcpExitCode", mcp.closeInputAndWait(60));   // also finishes both on the way out
        }
    }

    // ------------------------------------------------------------------ helpers

    private String beginLeased(String mode) throws IOException {
        String id = str(bridge.call("BEGIN_INVESTIGATION", false, mode, LEASE_SECONDS).get("investigationId"));
        require(!id.isBlank(), "Investigation ID missing");
        return id;
    }

    private boolean agentBreakpoint(String type, int line) throws IOException {
        return rows(bridge.call("LIST_BREAKPOINTS", false).get("breakpoints")).stream()
                .anyMatch(b -> type.equals(b.get("typeName")) && num(b.get("lineNumber")) == line && "agent".equals(b.get("owner")));
    }

    private boolean live(String launch) throws IOException {
        return rows(sessions()).stream().anyMatch(s -> launch.equals(s.get("launchName"))
                && !Boolean.TRUE.equals(s.get("terminated")) && !Boolean.TRUE.equals(s.get("disconnected")));
    }

    interface Condition { boolean holds() throws Exception; }

    /** Seconds until the condition held, or -1 after the limit. Polls once per second. */
    private static long waitUntil(Condition condition, int limitSeconds) throws Exception {
        long t0 = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) <= limitSeconds) {
            if (condition.holds()) return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0);
            Thread.sleep(1000);
        }
        return -1;
    }

    /** Minimal MCP stdio client for the built server JAR (newline-delimited JSON-RPC), acting as the agent. */
    static final class Mcp implements AutoCloseable {
        private final Process process;
        private final OutputStream in;
        private final BufferedReader out;
        private int nextId = 1;

        Mcp(Path root, int port) throws Exception {
            Path jar = root.resolve("mcp-server/target/agentic-debugger-mcp-1.0.0-SNAPSHOT.jar");
            require(Files.isRegularFile(jar), "Missing built MCP JAR " + jar + "; run 'mvn clean package' first");
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", jar.toString());
            builder.environment().put("AGENTDEBUG_PORT", Integer.toString(port));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            in = process.getOutputStream();
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"agentdebug-lease-test\",\"version\":\"1\"}}");
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        /** Calls a tool; returns the text content (also for tool errors). */
        String call(String tool, String argumentsJson) throws Exception {
            String reply = request("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}");
            Map<String, Object> result = obj(obj(Json.parse(reply)).get("result"));
            StringBuilder text = new StringBuilder();
            for (Object content : arr(result.get("content"))) text.append(str(obj(content).get("text")));
            return text.toString();
        }

        private String request(String method, String paramsJson) throws Exception {
            int id = nextId++;
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(150);
            while (System.nanoTime() < deadline) {
                String line = out.readLine();
                if (line == null) throw new IOException("MCP server closed its output");
                if (line.isBlank() || !line.startsWith("{")) continue;
                if (obj(Json.parse(line)).get("id") instanceof Number n && n.intValue() == id) return line;
            }
            throw new IOException("No MCP reply to " + method);
        }

        private void send(String json) throws IOException {
            in.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }

        /** Closes stdin like an agent that exits; returns the server's exit code. */
        int closeInputAndWait(int seconds) throws Exception {
            in.close();
            require(process.waitFor(seconds, TimeUnit.SECONDS), "MCP server exited within " + seconds + " s after stdin closed");
            return process.exitValue();
        }

        @Override public void close() {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
