package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ADDITIVE acceptance tests for gaps left open by earlier installed-Eclipse verification.
 * This class deliberately does not replace or call any PowerShell verifier.
 * Requires a dedicated already-running Eclipse installation and an imported fixture.
 */
public final class PendingAcceptanceVerifier {
    private static final String JAVA_PROJECT = "phase-one-debugging";
    private static final String BOOT_PROJECT = "spring-boot-debugging";
    private static final String JAVA_PKG = "org.learningfromexperience.debugfixtures.";
    private static final String BOOT_MAIN = "org.learningfromexperience.bootfixture.orders.OrdersApplication";
    private static final Set<String> TESTS = Set.of("core-step-into-out", "sb01-target-selection", "sb09-borrowed-session", "sb09-expired-owner");
    private final Path root, workspace, report;
    private final PhaseOneBridgeVerifier.Bridge bridge;
    private final Map<String, Object> results = new LinkedHashMap<>();

    private PendingAcceptanceVerifier(Path root, Path workspace, Path report) throws Exception {
        this.root = root;
        this.workspace = workspace.toRealPath();
        this.report = report;
        this.bridge = new PhaseOneBridgeVerifier.Bridge();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = arguments(args);
        Path root = Path.of(opt.getOrDefault("root", ".")).toAbsolutePath().normalize();
        Path workspace = Path.of(required(opt, "workspace")).toAbsolutePath().normalize();
        Path report = Path.of(opt.getOrDefault("output", root.resolve("evaluation/reports/pending-java-e2e.json").toString())).toAbsolutePath();
        String requested = opt.getOrDefault("scenario", "all");
        if (!"all".equals(requested) && !TESTS.contains(requested)) throw new IllegalArgumentException("Unknown scenario: " + requested);
        int port = Integer.parseInt(System.getenv().getOrDefault("AGENTDEBUG_PORT", "8765"));
        require(port != 8765 && port >= 1024 && port <= 65535, "Set AGENTDEBUG_PORT to a dedicated nondefault port");
        require(Files.isRegularFile(root.resolve("pom.xml")), "Not a repository root: " + root);
        require(Files.isDirectory(workspace), "Workspace does not exist: " + workspace);
        // The existing deploy process places this marker in a deliberately imported fixture.
        require(Files.isRegularFile(workspace.resolve("phase-one-debugging/.agentdebug-fixture")),
                "Refusing non-disposable workspace: missing phase-one-debugging/.agentdebug-fixture");
        PendingAcceptanceVerifier v = new PendingAcceptanceVerifier(root, workspace, report);
        v.preflight();
        v.run(requested);
    }

    private void preflight() throws Exception {
        Map<String, Object> info = bridge.call("GET_E2E_INFO", false);
        require(workspace.equals(Path.of(s(info.get("workspace"))).toRealPath()), "Bridge connected to a different Eclipse workspace");
        require(s(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"), "Debugger plugin not loaded from installed bundle");
        require(Files.isRegularFile(root.resolve("examples/phase-one-debugging/pom.xml")), "Java fixture missing");
    }

    private void run(String requested) throws Exception {
        List<String> selected = "all".equals(requested)
                ? List.of("sb01-target-selection", "sb09-expired-owner", "sb09-borrowed-session", "core-step-into-out")
                : List.of(requested);
        for (String scenario : selected) {
            Map<String, Object> result = new LinkedHashMap<>();
            String owner = null;
            try {
                if (!"sb01-target-selection".equals(scenario)) {
                    owner = s(bridge.call("BEGIN_INVESTIGATION", false, "NORMAL").get("investigationId"));
                    require(!owner.isBlank(), "Missing investigation identifier");
                }
                switch (scenario) {
                    case "sb01-target-selection" -> targetSelection(result);
                    case "sb09-expired-owner" -> expiredOwner(owner, result);
                    case "sb09-borrowed-session" -> borrowedSession(owner, result);
                    case "core-step-into-out" -> stepIntoOut(owner, result);
                    default -> throw new IllegalArgumentException(scenario);
                }
                result.put("status", "PASS");
            } catch (Exception | AssertionError e) {
                result.put("status", "FAIL");
                result.put("error", e.toString());
            } finally {
                if (owner != null) {
                    try {
                        result.put("cleanup", "success".equals(bridge.call("FINISH_INVESTIGATION", false, owner).get("status")));
                    } catch (Exception e) {
                        result.put("cleanup", false);
                        result.put("cleanupError", e.toString());
                    }
                } else result.put("cleanup", true);
                results.put(scenario, result);
                System.out.println(scenario + " => " + result);
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("generatedAt", Instant.now().toString());
        evidence.put("workspace", workspace.toString());
        evidence.put("scenarios", results);
        evidence.put("executions", bridge.traces);
        Files.createDirectories(report.getParent());
        Files.writeString(report, PhaseOneBridgeVerifier.Json.stringify(evidence), StandardCharsets.UTF_8);
        System.out.println("Report: " + report);
        require(results.values().stream().map(PendingAcceptanceVerifier::map)
                .allMatch(x -> "PASS".equals(x.get("status")) && Boolean.TRUE.equals(x.get("cleanup"))),
                "Pending Java acceptance failed; inspect " + report);
    }

    /** Read-only: verifies discovered Boot fixture and rejects an absent target without a launch. */
    private void targetSelection(Map<String, Object> result) throws Exception {
        List<Map<String, Object>> apps = records(bridge.call("LIST_JAVA_APPLICATIONS", false).get("applications"));
        long exact = apps.stream().filter(a -> BOOT_PROJECT.equals(a.get("project")) && BOOT_MAIN.equals(a.get("mainType"))).count();
        require(exact == 1, "Expected precisely one identified Boot Orders main, found " + exact);
        result.put("discoveredMains", apps.size());
        result.put("exactMatches", exact);
        // This is NOT an agent ambiguity test: only a bridge negative-target assertion.
        List<Map<String, Object>> before = records(bridge.call("LIST_DEBUG_SESSIONS", false).get("sessions"));
        Map<String, Object> missing = bridge.call("START_JAVA_APPLICATION", true,
                "agentdebug-nonexistent-project", "agentdebug.Nonexistent", "agentdebug-invalid-investigation");
        require("error".equals(missing.get("status")), "Invalid target/investigation unexpectedly accepted: " + missing);
        List<Map<String, Object>> after = records(bridge.call("LIST_DEBUG_SESSIONS", false).get("sessions"));
        require(before.equals(after), "Invalid target modified session list");
        result.put("invalidTargetRejected", true);
    }

    /** Verify a finished investigation cannot perform a later mutation against a living owned fixture. */
    private void expiredOwner(String owner, Map<String, Object> result) throws Exception {
        String launch = ownedJavaLaunch(owner, "LongRunningApplication");
        String borrower = begin();
        boolean finished = false;
        try {
            require("success".equals(bridge.call("FINISH_INVESTIGATION", false, borrower).get("status")), "Could not expire borrower");
            finished = true;
            Map<String, Object> rejected = bridge.call("RESTART_DEBUG", true, launch, borrower);
            require("error".equals(rejected.get("status")), "Expired investigation restarted another launch: " + rejected);
            Map<String, Object> state = bridge.call("GET_DEBUG_STATE", false, launch);
            require(!Boolean.TRUE.equals(state.get("terminated")), "Expired investigation terminated owner fixture");
            result.put("expiredOwnerRejected", true);
            result.put("ownerLaunchPreserved", true);
        } finally {
            if (!finished) bridge.call("FINISH_INVESTIGATION", true, borrower);
        }
    }

    /** A borrower must not terminate the JVM created by a different investigation. */
    private void borrowedSession(String owner, Map<String, Object> result) throws Exception {
        String launch = ownedJavaLaunch(owner, "LongRunningApplication");
        String borrower = begin();
        boolean finished = false;
        try {
            Map<String, Object> reused = bridge.call("START_JAVA_APPLICATION", false,
                    JAVA_PROJECT, JAVA_PKG + "LongRunningApplication", borrower);
            require(Boolean.TRUE.equals(reused.get("reusedSession")) && launch.equals(s(reused.get("launchName"))),
                    "Did not borrow the existing owned JVM: " + reused);
            require("success".equals(bridge.call("FINISH_INVESTIGATION", false, borrower).get("status")), "Borrower cleanup failed");
            finished = true;
            Map<String, Object> state = bridge.call("GET_DEBUG_STATE", false, launch);
            require(!Boolean.TRUE.equals(state.get("terminated")), "Borrower's cleanup terminated owner JVM");
            result.put("borrowedLaunchPreserved", true);
        } finally {
            if (!finished) bridge.call("FINISH_INVESTIGATION", true, borrower);
        }
    }

    /** Dedicated assertion that Step Into enters an application method and Step Out returns to caller. */
    private void stepIntoOut(String owner, Map<String, Object> result) throws Exception {
        Path source = root.resolve("examples/phase-one-debugging/src/main/java/")
                .resolve(JAVA_PKG.replace('.', '/')).resolve("StepFilterApplication.java");
        int line = sourceLine(source, "System.out.println(\"Result: \" + sum(values));");
        bridge.call("SET_BREAKPOINT", false, JAVA_PKG + "StepFilterApplication", line, owner);
        String launch = ownedJavaLaunch(owner, "StepFilterApplication");
        Frame atCall = awaitTop(launch, "StepFilterApplication", "main", Duration.ofSeconds(25));
        require(atCall.line == line, "Stopped on wrong caller line: " + atCall);
        bridge.call("CONTROL_DEBUG", false, launch, atCall.thread, "STEP_INTO", owner);
        Frame inSum = awaitTop(launch, "StepFilterApplication", "sum", Duration.ofSeconds(25));
        require(inSum.thread == atCall.thread, "Step Into changed selected request thread");
        bridge.call("CONTROL_DEBUG", false, launch, inSum.thread, "STEP_RETURN", owner);
        Frame back = awaitTop(launch, "StepFilterApplication", "main", Duration.ofSeconds(25));
        require(back.thread == inSum.thread, "Step Out returned on wrong thread");
        result.put("stepIntoMethod", inSum.method);
        result.put("stepOutMethod", back.method);
        result.put("callerLine", atCall.line);
    }

    private String ownedJavaLaunch(String owner, String main) throws IOException {
        Map<String, Object> reply = bridge.call("START_JAVA_APPLICATION", false, JAVA_PROJECT, JAVA_PKG + main, owner);
        require(!Boolean.TRUE.equals(reply.get("reusedSession")), "Test fixture JVM already running; refusing to borrow a user session");
        String name = s(reply.get("launchName"));
        require(!name.isBlank(), "Missing owned launch name");
        return name;
    }

    private Frame awaitTop(String launch, String simpleClass, String method, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> answer = bridge.call("GET_THREADS", true, launch);
            if ("success".equals(answer.get("status"))) {
                for (Map<String, Object> thread : records(answer.get("threads"))) {
                    if (!Boolean.TRUE.equals(thread.get("suspended"))) continue;
                    int index = integer(thread.get("index"));
                    Map<String, Object> stack = bridge.call("GET_STACK", true, launch, index);
                    if (!"success".equals(stack.get("status"))) continue;
                    List<Map<String, Object>> frames = records(stack.get("frames"));
                    if (frames.isEmpty()) continue;
                    Map<String, Object> top = frames.get(0);
                    if ((JAVA_PKG + simpleClass).equals(top.get("className")) && method.equals(top.get("methodName")))
                        return new Frame(index, method, integer(top.get("lineNumber")));
                }
            }
            Thread.sleep(150);
        }
        throw new AssertionError("No expected suspended frame for " + simpleClass + "." + method + " in " + launch);
    }

    private static int sourceLine(Path file, String snippet) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int found = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(snippet)) {
            require(found < 0, "Ambiguous source marker: " + snippet);
            found = i + 1;
        }
        require(found > 0, "Missing source marker: " + snippet);
        return found;
    }

    private String begin() throws IOException {
        String owner = s(bridge.call("BEGIN_INVESTIGATION", false, "NORMAL").get("investigationId"));
        require(!owner.isBlank(), "Could not start investigation");
        return owner;
    }
    private static Map<String,String> arguments(String[] args) {
        Map<String,String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("Expected --key value; mandatory --workspace PATH");
            out.put(args[i++].substring(2), args[i]);
        }
        return out;
    }
    private static String required(Map<String,String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required option --" + key);
        return value;
    }
    private static String s(Object value) { return value == null ? "" : value.toString(); }
    private static int integer(Object value) { return value instanceof Number n ? n.intValue() : Integer.parseInt(s(value)); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object x) { return (Map<String,Object>) x; }
    private static List<Map<String,Object>> records(Object value) {
        if (!(value instanceof List<?> entries)) return List.of();
        List<Map<String,Object>> records = new ArrayList<>();
        for (Object entry : entries) records.add(map(entry));
        return records;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Frame(int thread, String method, int line) { }
}
