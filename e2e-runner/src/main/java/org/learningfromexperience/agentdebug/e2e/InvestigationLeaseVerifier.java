package org.learningfromexperience.agentdebug.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CORE-17 against the installed bridge: abandoned investigations are cleaned up, and an agent's
 * changes always belong to an investigation. Uses only phase-one-debugging fixture classes.
 *
 * <ul>
 *   <li>idle-expiry: NORMAL investigation with a 5 s lease, stopped at a breakpoint, then no calls:
 *       Eclipse removes its breakpoint and ends its JVM; a late finish says "expired".</li>
 *   <li>wait-keeps-alive: a 12 s WAIT_FOR_STOP on a 5 s lease does not expire the investigation.</li>
 *   <li>controlled-kept: CONTROLLED investigation expires but its suspended session and breakpoint stay.</li>
 *   <li>mcp-disconnect: a real MCP server process; breakpoint and launch sent WITHOUT investigationId are
 *       attached to the open investigation; closing the MCP connection cleans everything up.</li>
 *   <li>mcp-several: with two open investigations, a breakpoint without investigationId is rejected.</li>
 * </ul>
 *
 * @author Ayan Dutta
 */
public final class InvestigationLeaseVerifier {
    private static final String PKG = "org.learningfromexperience.debugfixtures";
    private static final String PROJECT = "phase-one-debugging";
    private static final List<String> ALL = List.of("idle-expiry", "wait-keeps-alive", "controlled-kept", "mcp-disconnect", "mcp-several");
    private static final int LEASE_SECONDS = 5;

    private final Path root;
    private final Path output;
    private final PhaseOneBridgeVerifier.Bridge bridge = new PhaseOneBridgeVerifier.Bridge();

    private InvestigationLeaseVerifier(Path root, Path output) throws IOException {
        this.root = root;
        this.output = output;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException(usage());
            opts.put(args[i++].substring(2), args[i]);
        }
        String scenario = opts.getOrDefault("scenario", "all");
        if (!scenario.equals("all") && !ALL.contains(scenario)) throw new IllegalArgumentException(usage());
        new InvestigationLeaseVerifier(Path.of(opts.get("root")).toAbsolutePath().normalize(), Path.of(opts.get("output"))).run(scenario);
    }

    private void run(String requested) throws Exception {
        Map<String, Object> probe = bridge.call("BEGIN_INVESTIGATION", false, "NORMAL", LEASE_SECONDS);
        require(probe.get("leaseSeconds") instanceof Number, "Bridge has no investigation lease: deploy the CORE-17 build first. Reply: " + probe);
        bridge.call("FINISH_INVESTIGATION", false, str(probe.get("investigationId")));

        Map<String, Object> results = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (String scenario : requested.equals("all") ? ALL : List.of(requested)) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            long t0 = System.nanoTime();
            try {
                switch (scenario) {
                    case "idle-expiry" -> idleExpiry(evidence);
                    case "wait-keeps-alive" -> waitKeepsAlive(evidence);
                    case "controlled-kept" -> controlledKept(evidence);
                    case "mcp-disconnect" -> mcpDisconnect(evidence);
                    case "mcp-several" -> mcpSeveral(evidence);
                    default -> throw new IllegalArgumentException(scenario);
                }
                evidence.put("result", "PASS");
            } catch (Exception | AssertionError failure) {
                evidence.put("result", "FAIL");
                evidence.put("error", failure.toString());
                failures.add(scenario + ": " + failure.getMessage());
            } finally {
                evidence.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                results.put(scenario, evidence);
                System.out.println(scenario + ": " + evidence);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("scope", "CORE-17 investigation lease, MCP auto-attach and disconnect cleanup; direct test, not an agent test");
        report.put("scenarios", results);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, PhaseOneBridgeVerifier.Json.stringify(report), StandardCharsets.UTF_8);
        if (!failures.isEmpty()) throw new IllegalStateException("Lease checks failed: " + String.join(" | ", failures));
    }

    /** LE-E1 */
    private void idleExpiry(Map<String, Object> evidence) throws Exception {
        String inv = begin("NORMAL");
        int line = line("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, PKG + ".StepFilterApplication", line, inv);
        String launch = start("StepFilterApplication", inv);
        require("BREAKPOINT".equals(bridge.call("WAIT_FOR_STOP", false, launch, 20_000).get("reason")), "stopped at the breakpoint");
        // No call names the investigation from here on; only neutral inventory reads.
        long seconds = waitUntil(() -> !agentBreakpoint(PKG + ".StepFilterApplication", line) && !live(launch), 30);
        require(seconds >= 0, "breakpoint removed and JVM ended within 30 s of going idle");
        Map<String, Object> late = bridge.call("FINISH_INVESTIGATION", true, inv);
        require(str(late.get("message")).contains("expired"), "a late finish reports the expiry: " + late);
        evidence.put("cleanedUpAfterSeconds", seconds);
        evidence.put("lateFinish", late.get("message"));
    }

    /** LE-E2 */
    private void waitKeepsAlive(Map<String, Object> evidence) throws Exception {
        String inv = begin("NORMAL");
        String launch = start("LongRunningApplication", inv);
        Map<String, Object> wait = bridge.call("WAIT_FOR_STOP", false, launch, 12_000);
        require("TIMEOUT".equals(wait.get("reason")), "the long wait timed out as expected: " + wait);
        Map<String, Object> finish = bridge.call("FINISH_INVESTIGATION", false, inv);
        require(Boolean.TRUE.equals(finish.get("completed")), "finished normally after a wait longer than the lease: " + finish);
        evidence.put("waitSeconds", 12);
    }

    /** LE-E3 */
    private void controlledKept(Map<String, Object> evidence) throws Exception {
        String inv = begin("CONTROLLED");
        int line = line("StepFilterApplication", "values.add(10);");
        String type = PKG + ".StepFilterApplication";
        bridge.call("SET_BREAKPOINT", false, type, line, inv);
        String launch = start("StepFilterApplication", inv);
        Map<String, Object> stop = bridge.call("WAIT_FOR_STOP", false, launch, 20_000);
        require("BREAKPOINT".equals(stop.get("reason")), "stopped at the breakpoint");
        try {
            // Any call naming the investigation would keep it alive, so just wait: lease 5 s + checker 2 s + margin.
            Thread.sleep(15_000);
            require(agentBreakpoint(type, line) && live(launch), "CONTROLLED: breakpoint and session were kept");
            Map<String, Object> late = bridge.call("FINISH_INVESTIGATION", true, inv);
            require(str(late.get("message")).contains("expired") && str(late.get("message")).contains("kept"),
                    "CONTROLLED investigation expired and says its resources were kept: " + late);
            evidence.put("keptAfterExpiry", true);
            evidence.put("lateFinish", late.get("message"));
        } finally {
            // Test cleanup of the kept resources (no investigation any more).
            bridge.call("CONTROL_DEBUG", true, launch, Json.num(stop.get("threadIndex")), "STOP");
            bridge.call("REMOVE_BREAKPOINT", true, type, line);
        }
    }

    /** LE-E4: real MCP server process, no investigationId on breakpoint and launch, then disconnect. */
    private void mcpDisconnect(Map<String, Object> evidence) throws Exception {
        int line = line("StepFilterApplication", "values.add(10);");
        String type = PKG + ".StepFilterApplication";
        String[] launchHolder = new String[1];
        try (Mcp mcp = new Mcp(root)) {
            String begin = mcp.call("begin_debug_investigation", "{\"mode\":\"NORMAL\"}");
            require(begin.contains("investigationId"), "begin: " + begin);
            String bp = mcp.call("set_breakpoint", "{\"typeName\":\"" + type + "\",\"lineNumber\":" + line + "}");
            require(bp.contains("attachedToInvestigation"), "breakpoint without ID attached to the open investigation: " + bp);
            String start = mcp.call("start_java_application", "{\"projectName\":\"" + PROJECT + "\",\"mainType\":\"" + type + "\"}");
            require(start.contains("attachedToInvestigation"), "launch without ID attached to the open investigation: " + start);
            String started = Json.str(Json.obj(Json.parse(start.substring(start.indexOf('{')))).get("launchName"));
            require(!started.isBlank(), "launch name in " + start);
            launchHolder[0] = started;
            require(waitUntil(() -> live(started) || agentBreakpoint(type, line), 10) >= 0, "launch running");
            evidence.put("exitCode", mcp.closeInputAndWait(60));
        }
        String launch = launchHolder[0];
        long seconds = waitUntil(() -> !agentBreakpoint(type, line) && !live(launch), 30);
        require(seconds >= 0, "after the MCP connection closed: breakpoint removed and JVM ended");
        evidence.put("cleanedUpAfterSeconds", seconds);
    }

    /** LE-E5 */
    private void mcpSeveral(Map<String, Object> evidence) throws Exception {
        try (Mcp mcp = new Mcp(root)) {
            String a = mcp.call("begin_debug_investigation", "{\"mode\":\"NORMAL\"}");
            String b = mcp.call("begin_debug_investigation", "{\"mode\":\"NORMAL\"}");
            require(a.contains("investigationId") && b.contains("investigationId"), "two investigations open");
            String bp = mcp.call("set_breakpoint", "{\"typeName\":\"" + PKG + ".StepFilterApplication\",\"lineNumber\":9}");
            require(bp.contains("Several investigations are open"), "breakpoint without ID rejected: " + bp);
            evidence.put("rejected", true);
            evidence.put("exitCode", mcp.closeInputAndWait(60));   // also finishes both on the way out
        }
    }

    // ------------------------------------------------------------------ helpers

    private String begin(String mode) throws IOException {
        return str(bridge.call("BEGIN_INVESTIGATION", false, mode, LEASE_SECONDS).get("investigationId"));
    }

    private String start(String main, String inv) throws IOException {
        return str(bridge.call("START_JAVA_APPLICATION", false, PROJECT, PKG + "." + main, inv).get("launchName"));
    }

    private boolean agentBreakpoint(String type, int line) throws IOException {
        for (Object o : Json.arr(bridge.call("LIST_BREAKPOINTS", false).get("breakpoints"))) {
            Map<String, Object> bp = Json.obj(o);
            if (type.equals(bp.get("typeName")) && Json.num(bp.get("lineNumber")) == line && "agent".equals(bp.get("owner"))) return true;
        }
        return false;
    }

    private boolean live(String launch) throws IOException {
        for (Object o : Json.arr(bridge.call("LIST_DEBUG_SESSIONS", false).get("sessions"))) {
            Map<String, Object> s = Json.obj(o);
            if (launch.equals(s.get("launchName")) && !Boolean.TRUE.equals(s.get("terminated"))
                    && !Boolean.TRUE.equals(s.get("disconnected"))) return true;
        }
        return false;
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

    private int line(String name, String fragment) throws IOException {
        Path file = root.resolve("examples/phase-one-debugging/src/main/java/").resolve(PKG.replace('.', '/')).resolve(name + ".java");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(fragment)) return i + 1;
        throw new AssertionError("Source fragment missing in " + file + ": " + fragment);
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /** Small helpers over the shared JSON parser. */
    static final class Json {
        static Object parse(String s) { return PhaseOneBridgeVerifier.Json.parse(s); }
        @SuppressWarnings("unchecked") static Map<String, Object> obj(Object o) { return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of(); }
        @SuppressWarnings("unchecked") static List<Object> arr(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
        static String str(Object o) { return o == null ? "" : o.toString(); }
        static int num(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o)); }
    }

    /** Minimal MCP stdio client for the built server JAR (newline-delimited JSON-RPC). */
    static final class Mcp implements AutoCloseable {
        private final Process process;
        private final OutputStream in;
        private final BufferedReader out;
        private int nextId = 1;

        Mcp(Path root) throws Exception {
            Path jar = root.resolve("mcp-server/target/agentic-debugger-mcp-1.0.0-SNAPSHOT.jar");
            require(Files.isRegularFile(jar), "Missing built MCP JAR " + jar + "; run DevE2E all first");
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            ProcessBuilder pb = new ProcessBuilder(java.toString(), "-jar", jar.toString());
            pb.environment().put("AGENTDEBUG_PORT", System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765"));
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            in = process.getOutputStream();
            out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"agentdebug-lease-test\",\"version\":\"1\"}}");
            send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        }

        /** Calls a tool; returns the text content (also for tool errors). */
        String call(String tool, String argumentsJson) throws Exception {
            String reply = request("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}");
            Map<String, Object> result = Json.obj(Json.obj(Json.parse(reply)).get("result"));
            StringBuilder text = new StringBuilder();
            for (Object c : Json.arr(result.get("content"))) text.append(Json.str(Json.obj(c).get("text")));
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
                Object idValue = Json.obj(Json.parse(line)).get("id");
                if (idValue instanceof Number n && n.intValue() == id) return line;
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

        @Override
        public void close() {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String usage() {
        return "Usage: --root <repository root> --output <report.json> [--scenario all|" + String.join("|", ALL) + "]";
    }
}
