package org.learningfromexperience.agentdebug.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Base of the integration tests. They drive the installed plugin in the running test Eclipse through its bridge,
 * without an AI agent and without Claude usage (start the test Eclipse with {@code java dev-e2e/DevE2E.java start}
 * or {@code deploy}).
 *
 * <p>One subclass per product feature; each test method is named after the behaviour it checks. A test that calls
 * {@link #investigation()} runs inside its own NORMAL investigation, which is finished after the test; the test fails
 * when that cleanup was incomplete (own breakpoints left, own JVM still running). Before the first test of a class, it
 * fails when the test projects already have breakpoints (left by an earlier run or another tool, OPEN-47). Each class writes a JSON report with
 * the evidence and duration of every test and every bridge call to {@code evaluation/reports/<group>.json}, and prints
 * one line per test ({@code breakpoints/conditional-stops-only-on-match: PASS (2310 ms)}).
 *
 * @author Ayan Dutta
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EclipseIntegrationTest {
    static final String JAVA_PROJECT = "phase-one-debugging";
    static final String JAVA_PKG = "org.learningfromexperience.debugfixtures.";
    static final String BOOT_PROJECT = "spring-boot-debugging";
    static final String ORDERS_PKG = "org.learningfromexperience.bootfixture.orders.";
    static final String ORDERS_MAIN = ORDERS_PKG + "OrdersApplication";
    static final String INVENTORY_MAIN = "org.learningfromexperience.bootfixture.inventory.InventoryApplication";
    static final String FAILURES_PKG = "org.learningfromexperience.bootfixture.failures.";
    static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");

    /** A stop: the suspended thread and the method and line it stopped in. */
    record Frame(int thread, String method, int line) { }

    TestEnvironment env;
    Bridge bridge;
    /** The evidence of the running test, written to the report. */
    Map<String, Object> evidence;
    private String investigation;
    private String scenario;
    private long started;
    private final Map<String, Object> results = new LinkedHashMap<>();

    /** The group name used in reports and printed lines, for example {@code breakpoints}. */
    abstract String group();

    @RegisterExtension
    final TestWatcher resultLine = new TestWatcher() {
        @Override public void testSuccessful(ExtensionContext context) { record(context, "PASS", null); }
        @Override public void testFailed(ExtensionContext context, Throwable cause) { record(context, "FAIL", cause.toString()); }
        @Override public void testAborted(ExtensionContext context, Throwable cause) { record(context, "SKIPPED", cause.getMessage()); }
        @Override public void testDisabled(ExtensionContext context, Optional<String> reason) {
            record(context, "SKIPPED", reason.orElse(null));
        }
    };

    private void record(ExtensionContext context, String status, String detail) {
        String name = scenarioName(context.getRequiredTestMethod().getName());
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) results.computeIfAbsent(name, n -> new LinkedHashMap<String, Object>());
        entry.put("status", status);
        if (detail != null) entry.put("detail", detail);
        Object ms = entry.get("durationMs");
        System.out.println(group() + "/" + name + ": " + status + (ms == null ? "" : " (" + ms + " ms)")
                + (detail == null || status.equals("PASS") ? "" : " - " + detail));
    }

    @BeforeAll
    final void connect() throws Exception {
        env = TestEnvironment.get();
        bridge = new Bridge(env.port);
        Map<String, Object> info = bridge.call("GET_E2E_INFO", false);
        require(Path.of(str(info.get("workspace"))).toRealPath().equals(env.workspace),
                "The bridge on port " + env.port + " belongs to another Eclipse workspace: " + info.get("workspace"));
        require(str(info.get("loadedFrom")).contains("org.learningfromexperience.eclipse.agentdebug_"),
                "The debugger plugin is not loaded from the installed bundle: " + info.get("loadedFrom"));
        for (String project : List.of(JAVA_PROJECT, BOOT_PROJECT))
            require(Files.isRegularFile(env.workspace.resolve(project).resolve(".agentdebug-fixture")),
                    "Missing disposable-fixture marker in " + project + "; refusing to test (run DevE2E deploy first)");
        List<String> left = leftoverBreakpoints(rows(bridge.call("LIST_BREAKPOINTS", false).get("breakpoints")));
        require(left.isEmpty(), "Leftover breakpoints in the test projects: " + String.join(", ", left)
                + ". A test did not set them, and they change where the programs stop (OPEN-47). In the test Eclipse:"
                + " Window > Show View > Breakpoints, Remove All Breakpoints, then run the tests again."
                + " Owner 'agent' = left by an agent investigation that did not finish; 'user' = set by hand or by"
                + " another tool (for example Copilot's own java_debugger).");
        // OPEN-58: the plugin asks the user before code runs in the program (evaluation, tracepoints). The tests run
        // unattended, so they preset "allow"; the consent tests switch to "deny" themselves. Reset to "ask" at the end.
        bridge.call("TEST_SET_CONSENT", true, "allow");
    }

    /**
     * Line breakpoints in the two test projects, as "OrderService:23 (user)", in the order Eclipse lists them (OPEN-47).
     * Each test sets its own breakpoints and removes them at its finish, so any breakpoint here before a test class
     * starts was left behind: by an investigation that did not finish (owner agent), by hand or by another tool such as
     * Copilot's java_debugger (owner user). Seen 26 Nov: seven at OrderService:23 made FullSuite fail 42/43.
     */
    static List<String> leftoverBreakpoints(List<Map<String, Object>> breakpoints) {
        List<String> left = new ArrayList<>();
        for (Map<String, Object> bp : breakpoints) {
            String project = str(bp.get("projectName"));
            if (!project.equals(JAVA_PROJECT) && !project.equals(BOOT_PROJECT)) continue;
            String type = str(bp.get("typeName"));
            // num(): the JSON reader gives numbers as doubles ("22.0" before, seen 26 Nov 23:02).
            left.add(type.substring(type.lastIndexOf('.') + 1) + ":" + num(bp.get("lineNumber"))
                    + " (" + (bp.get("owner") == null ? "user" : str(bp.get("owner"))) + ")");
        }
        return left;
    }

    @BeforeEach
    final void beginTest(TestInfo info) {
        evidence = new LinkedHashMap<>();
        investigation = null;
        scenario = scenarioName(info.getTestMethod().orElseThrow().getName());
        started = System.nanoTime();
    }

    @AfterEach
    final void endTest() {
        if (investigation != null) finish(investigation, evidence, "cleanup");
        else evidence.putIfAbsent("cleanup", true);
        evidence.put("durationMs", ms(started));
        results.put(scenario, evidence);
        assertEquals(Boolean.TRUE, evidence.get("cleanup"), "Cleanup incomplete: " + evidence);
    }

    @AfterAll
    final void writeReport() throws IOException {
        if (env == null) return;
        bridge.call("TEST_SET_CONSENT", true, "ask");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("group", group());
        report.put("generatedAt", Instant.now().toString());
        report.put("workspace", env.workspace.toString());
        report.put("tests", results);
        synchronized (bridge.traces) { report.put("bridgeCalls", new ArrayList<>(bridge.traces)); }
        Path file = env.root.resolve("evaluation/reports/" + group() + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.stringify(report), StandardCharsets.UTF_8);
        System.out.println("Report: " + file);
    }

    /** {@code conditionalStopsOnlyOnMatch} becomes {@code conditional-stops-only-on-match}; digits start a word. */
    static String scenarioName(String method) {
        return method.replaceAll("([a-z])([A-Z0-9])", "$1-$2").replaceAll("([0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    // ------------------------------------------------------------------ investigations and launches

    /** The test's own NORMAL investigation, begun on first use and finished after the test. */
    String investigation() throws IOException {
        if (investigation == null) investigation = begin("NORMAL");
        return investigation;
    }

    void put(String key, Object value) { evidence.put(key, value); }

    String begin(String mode) throws IOException {
        String id = str(bridge.call("BEGIN_INVESTIGATION", false, mode).get("investigationId"));
        require(!id.isBlank(), "Investigation ID missing");
        return id;
    }

    /**
     * Finishes an investigation and records under {@code key} whether its cleanup was complete: success, own JVM
     * ended ({@code processesEnded} not false) and no own breakpoint left ({@code ownBreakpointsLeft} 0 when given).
     */
    boolean finish(String id, Map<String, Object> into, String key) {
        try {
            Map<String, Object> reply = bridge.call("FINISH_INVESTIGATION", true, id);
            boolean complete = "success".equals(reply.get("status"))
                    && !Boolean.FALSE.equals(reply.get("processesEnded"))
                    && !(reply.get("ownBreakpointsLeft") instanceof Number left && left.intValue() != 0);
            into.put(key, complete);
            if (!complete) into.put(key + "Reply", reply);
            return complete;
        } catch (Exception failure) {
            into.put(key, false);
            into.put(key + "Error", failure.toString());
            return false;
        }
    }

    /** Starts a program for an investigation; never borrows a session that is already running. Returns the launch. */
    String startProgram(String project, String mainType, String id) throws IOException {
        Map<String, Object> reply = bridge.call("START_JAVA_APPLICATION", false, project, mainType, id);
        require(!Boolean.TRUE.equals(reply.get("reusedSession")),
                "A debug session of " + mainType + " was already running; refusing to borrow it");
        String launch = str(reply.get("launchName"));
        require(!launch.isBlank(), "Launch name missing: " + reply);
        return launch;
    }

    String startJava(String simpleName, String id) throws IOException {
        return startProgram(JAVA_PROJECT, JAVA_PKG + simpleName, id);
    }

    boolean alive(String launch) throws IOException {
        Map<String, Object> state = bridge.call("GET_DEBUG_STATE", true, launch);
        return "success".equals(state.get("status")) && !Boolean.TRUE.equals(state.get("terminated"));
    }

    Object sessions() throws IOException {
        return bridge.call("LIST_DEBUG_SESSIONS", false).get("sessions");
    }

    // ------------------------------------------------------------------ stops

    /** One WAIT_FOR_STOP call; the test client waits 30 s for a reply, so one wait is at most 25 s. */
    Map<String, Object> waitForStop(String launch, int timeoutMs) throws IOException {
        require(timeoutMs <= 25_000, "wait longer than the 30 s test client allows");
        return bridge.call("WAIT_FOR_STOP", false, launch, timeoutMs);
    }

    /**
     * Waits with WAIT_FOR_STOP, as the agent does, until the program stops, and requires the stop in {@code className}
     * (and {@code method} and {@code line} when given). Fails when the program ends first or stops somewhere else.
     * With {@code client}, waits in 5 s steps and fails early when that HTTP client process has already exited.
     */
    Frame awaitStop(String launch, String className, String method, int line, Duration timeout,
                    Process client, Path clientLog) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String expected = className + (method == null ? "" : "." + method) + (line < 0 ? "" : ":" + line);
        while (System.nanoTime() < deadline) {
            if (client != null && !client.isAlive())
                throw new AssertionError("HTTP client exited (" + client.exitValue() + ") before a stop at "
                        + expected + "; output: " + clientLog(clientLog));
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            int step = (int) Math.max(1, Math.min(left, client != null ? 5_000 : 25_000));
            Map<String, Object> stop = waitForStop(launch, step);
            String reason = str(stop.get("reason"));
            if ("TIMEOUT".equals(reason)) continue;
            require(!"TERMINATED".equals(reason), "The program ended before stopping at " + expected + ": " + stop);
            require(Boolean.TRUE.equals(stop.get("stopped")), "Unexpected wait result: " + stop);
            Map<String, Object> location = obj(stop.get("location"));
            boolean match = className.equals(location.get("className"))
                    && (method == null || method.equals(location.get("methodName")))
                    && (line < 0 || num(location.get("lineNumber")) == line);
            require(match, "Expected a stop at " + expected + " but it stopped (" + reason + ") at " + location);
            return new Frame(num(stop.get("threadIndex")), str(location.get("methodName")), num(location.get("lineNumber")));
        }
        throw new AssertionError("No stop at " + expected + " within " + timeout.toSeconds() + " s"
                + (client == null ? "" : "; HTTP client output: " + clientLog(clientLog)));
    }

    String local(String launch, int thread, String name) throws IOException {
        for (Object item : arr(bridge.call("GET_VARIABLES", false, launch, thread, 0).get("variables"))) {
            Map<String, Object> variable = obj(item);
            if (name.equals(variable.get("name"))) return str(variable.get("value"));
        }
        throw new AssertionError("No local variable " + name + " in the stopped frame");
    }

    void requireLocal(String launch, int thread, String name, String expected) throws IOException {
        String actual = local(launch, thread, name);
        require(expected.equals(actual), name + " should be " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------ output and Spring Boot

    /** stdout, stderr and console of every process of the launch, as one text; empty when not available. */
    String processOutput(String launch) throws IOException {
        Map<String, Object> reply = bridge.call("GET_PROCESS_OUTPUT", true, launch);
        if (!"success".equals(reply.get("status"))) return "";
        StringBuilder text = new StringBuilder();
        for (Object item : arr(reply.get("processes"))) {
            Map<String, Object> process = obj(item);
            text.append(str(process.get("stdout"))).append('\n').append(str(process.get("stderr")))
                    .append('\n').append(str(process.get("console"))).append('\n');
        }
        return text.toString();
    }

    /** Waits until the Spring Boot application prints "Tomcat started on port N"; returns N. */
    int waitReady(String launch, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String text = processOutput(launch);
            Matcher ready = TOMCAT_PORT.matcher(text);
            if (ready.find()) return Integer.parseInt(ready.group(1));
            if (text.contains("APPLICATION FAILED TO START") || text.contains("APPLICATION FAILED"))
                throw new AssertionError("Spring Boot application failed to start: "
                        + text.substring(Math.max(0, text.length() - 1000)));
            Thread.sleep(250);
        }
        throw new AssertionError("Spring Boot application not ready within " + timeout.toSeconds() + " s: " + launch);
    }

    /**
     * Starts the test HTTP client ({@link JavaFixtureQuoteClient}) that posts a quote request to the fixture of this
     * launch. Without {@code approved} it must refuse before any network call; {@code invalidQuantity} sends quantity 0.
     */
    Process startFixtureClient(String launch, Path receipt, boolean approved, boolean invalidQuantity, Path log)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        // The client is a class of these tests: run it from the same compiled classes folder.
        String classes = Path.of(JavaFixtureQuoteClient.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", classes,
                JavaFixtureQuoteClient.class.getName(), "--workspace", env.workspace.toString(),
                "--bridge-port", Integer.toString(env.port), "--launch-name", launch, "--receipt", receipt.toString()));
        if (approved) command.add("--approve-fixture-post");
        if (invalidQuantity) command.add("--invalid-quantity");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    static String clientLog(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) return "(no client output file)";
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.length() > 4000 ? text.substring(text.length() - 4000) : text;
        } catch (IOException e) {
            return "(cannot read HTTP client output: " + e.getMessage() + ")";
        }
    }

    // ------------------------------------------------------------------ source lines

    /** The line of {@code fragment} in a class of the plain Java fixture project. */
    int javaLine(String simpleName, String fragment) throws IOException {
        return sourceLine(env.root.resolve("examples/phase-one-debugging/src/main/java/")
                .resolve(JAVA_PKG.replace('.', '/')).resolve(simpleName + ".java"), fragment);
    }

    /** The line of {@code fragment} in a class of the Spring Boot fixture project. */
    int bootLine(String qualifiedName, String fragment) throws IOException {
        return sourceLine(env.root.resolve("examples/spring-boot-debugging/src/main/java/")
                .resolve(qualifiedName.replace('.', '/') + ".java"), fragment);
    }

    static int sourceLine(Path file, String fragment) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int found = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(fragment)) {
            require(found == -1, "Ambiguous source fragment in " + file + ": " + fragment);
            found = i + 1;
        }
        require(found > 0, "Source fragment missing in " + file + ": " + fragment);
        return found;
    }

    // ------------------------------------------------------------------ small helpers

    static void require(boolean condition, String message) { assertTrue(condition, message); }
    static String str(Object o) { return o == null ? "" : o.toString(); }
    static int num(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o)); }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }
    @SuppressWarnings("unchecked") static List<Object> arr(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
    static List<Map<String, Object>> rows(Object o) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : arr(o)) rows.add(obj(item));
        return rows;
    }
    static long ms(long t0) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0); }
}
