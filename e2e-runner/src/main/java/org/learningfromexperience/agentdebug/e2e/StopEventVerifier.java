package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
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
 * Event-based stop detection (CORE-14, WAIT_FOR_STOP) and process output head+tail (CORE-15)
 * against the real installed Eclipse bridge. Uses only the existing phase-one-debugging fixture
 * classes; every scenario runs inside its own NORMAL investigation, so its launches and
 * breakpoints are removed afterwards.
 *
 * <p>Scenarios: breakpoint, step, exception, pause, terminated, output.
 *
 * @author Ayan Dutta
 */
public final class StopEventVerifier {
    private static final String PKG = "org.learningfromexperience.debugfixtures";
    private static final String PROJECT = "phase-one-debugging";
    private static final List<String> ALL = List.of("breakpoint", "step", "exception", "pause", "terminated", "output");

    private final Path root;
    private final Path output;
    private final PhaseOneBridgeVerifier.Bridge bridge = new PhaseOneBridgeVerifier.Bridge();
    private final Map<String, Object> results = new LinkedHashMap<>();

    private StopEventVerifier(Path root, Path output) throws IOException {
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
        Path root = Path.of(required(opts, "root")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) throw new IllegalArgumentException("--root must be the repository root");
        new StopEventVerifier(root, Path.of(required(opts, "output"))).run(scenario);
    }

    private void run(String requested) throws Exception {
        Map<String, Object> probe = bridge.call("WAIT_FOR_STOP", true, "agentdebug-no-such-launch", 0);
        require(!String.valueOf(probe.get("message")).contains("Unknown command"),
                "Bridge has no WAIT_FOR_STOP: the installed plugin is older than this source. Deploy first. Reply: " + probe);
        List<String> failures = new ArrayList<>();
        for (String scenario : requested.equals("all") ? ALL : List.of(requested)) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            String investigation = null;
            long t0 = System.nanoTime();
            try {
                investigation = str(bridge.call("BEGIN_INVESTIGATION", false, "NORMAL").get("investigationId"));
                switch (scenario) {
                    case "breakpoint" -> breakpoint(investigation, evidence);
                    case "step" -> step(investigation, evidence);
                    case "exception" -> exception(investigation, evidence);
                    case "pause" -> pause(investigation, evidence);
                    case "terminated" -> terminated(investigation, evidence);
                    case "output" -> output(investigation, evidence);
                    default -> throw new IllegalArgumentException(scenario);
                }
                evidence.put("result", "PASS");
            } catch (Exception | AssertionError failure) {
                evidence.put("result", "FAIL");
                evidence.put("error", failure.toString());
                failures.add(scenario + ": " + failure.getMessage());
            } finally {
                if (investigation != null) {
                    try {
                        evidence.put("cleanup", "success".equals(str(
                                bridge.call("FINISH_INVESTIGATION", false, investigation).get("status"))));
                    } catch (Exception cleanup) {
                        evidence.put("cleanup", false);
                        evidence.put("cleanupError", cleanup.toString());
                    }
                }
                if (!Boolean.TRUE.equals(evidence.get("cleanup")) && "PASS".equals(evidence.get("result")))
                    failures.add(scenario + ": cleanup failed");
                evidence.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
                results.put(scenario, evidence);
                System.out.println(scenario + ": " + evidence);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("scope", "CORE-14 wait_for_stop and CORE-15 process output; direct bridge test, not an agent test");
        report.put("scenarios", results);
        report.put("executions", bridge.traces);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, PhaseOneBridgeVerifier.Json.stringify(report), StandardCharsets.UTF_8);
        if (!failures.isEmpty()) throw new IllegalStateException("Stop checks failed: " + String.join(" | ", failures));
    }

    /** ST-E1: one call returns the breakpoint stop with location, frames and locals;
     *  the same stop is never reported twice (the next wait times out). */
    private void breakpoint(String investigation, Map<String, Object> evidence) throws Exception {
        int line = line("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, PKG + ".StepFilterApplication", line, investigation);
        String launch = start("StepFilterApplication", investigation);

        long t0 = System.nanoTime();
        Map<String, Object> stop = waitForStop(launch, 20_000);
        evidence.put("stopReplyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        require(Boolean.TRUE.equals(stop.get("stopped")) && "BREAKPOINT".equals(stop.get("reason")), "breakpoint stop: " + stop);
        Map<String, Object> bp = obj(stop.get("breakpoint"));
        require((PKG + ".StepFilterApplication").equals(bp.get("typeName")) && number(bp.get("lineNumber")) == line,
                "breakpoint identified: " + bp);
        requireLocation(stop, "StepFilterApplication", "main", line);
        require(number(stop.get("threadIndex")) >= 0 && !str(stop.get("threadName")).isBlank(), "thread identified: " + stop);
        require(!arr(stop.get("frames")).isEmpty() && number(stop.get("totalFrames")) >= 1, "frames included: " + stop);
        require(hasLocal(stop, "values") && hasLocal(stop, "args"), "locals included: " + stop.get("locals"));

        Map<String, Object> again = waitForStop(launch, 2_000);
        require(Boolean.FALSE.equals(again.get("stopped")) && "TIMEOUT".equals(again.get("reason")),
                "an already reported stop is not reported again: " + again);
        evidence.put("line", line);
    }

    /** ST-E2: a step ends with STEP_END on the next line, reported by the same call. */
    private void step(String investigation, Map<String, Object> evidence) throws Exception {
        int line = line("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, PKG + ".StepFilterApplication", line, investigation);
        String launch = start("StepFilterApplication", investigation);
        Map<String, Object> first = waitForStop(launch, 20_000);
        require("BREAKPOINT".equals(first.get("reason")), "breakpoint stop before stepping: " + first);

        bridge.call("CONTROL_DEBUG", false, launch, number(first.get("threadIndex")), "STEP_OVER", investigation);
        Map<String, Object> stepped = waitForStop(launch, 10_000);
        require(Boolean.TRUE.equals(stepped.get("stopped")) && "STEP_END".equals(stepped.get("reason")), "step end: " + stepped);
        requireLocation(stepped, "StepFilterApplication", "main", line + 1);
        require(stepped.get("breakpoint") == null, "a step stop names no breakpoint: " + stepped);

        // Resuming to the end reports TERMINATED instead of waiting for the full timeout.
        bridge.call("CONTROL_DEBUG", false, launch, number(stepped.get("threadIndex")), "RESUME", investigation);
        long t0 = System.nanoTime();
        Map<String, Object> end = waitForStop(launch, 20_000);
        long endMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        require(Boolean.FALSE.equals(end.get("stopped")) && "TERMINATED".equals(end.get("reason")), "program end: " + end);
        require(endMs < 15_000, "TERMINATED reported promptly, after " + endMs + " ms");
        evidence.put("terminatedAfterMs", endMs);
    }

    /** ST-E3: an exception breakpoint stop is reported as EXCEPTION with the exception type. */
    private void exception(String investigation, Map<String, Object> evidence) throws Exception {
        String type = "java.lang.IllegalStateException";
        bridge.call("SET_EXCEPTION_BREAKPOINT", false, type, true, true, investigation);
        String launch = start("ExceptionApplication", investigation);
        // The JVM itself may throw the same type internally before the fixture does; skip those stops.
        for (int attempt = 0; attempt < 5; attempt++) {
            Map<String, Object> stop = waitForStop(launch, 20_000);
            require(Boolean.TRUE.equals(stop.get("stopped")) && "EXCEPTION".equals(stop.get("reason")), "exception stop: " + stop);
            require(type.equals(stop.get("exceptionType")), "exception type reported: " + stop);
            Map<String, Object> location = obj(stop.get("location"));
            if ((PKG + ".ExceptionApplication").equals(location.get("className"))) {
                require("processOrder".equals(location.get("methodName")), "stopped in processOrder: " + location);
                evidence.put("skippedInternalStops", attempt);
                return;
            }
            bridge.call("CONTROL_DEBUG", false, launch, number(stop.get("threadIndex")), "RESUME", investigation);
        }
        throw new AssertionError("No exception stop inside ExceptionApplication after 5 stops");
    }

    /** ST-E4: a user pause is reported as PAUSED. */
    private void pause(String investigation, Map<String, Object> evidence) throws Exception {
        String launch = start("LongRunningApplication", investigation);
        int main = mainThread(launch);
        Map<String, Object> none = waitForStop(launch, 1_000);
        require("TIMEOUT".equals(none.get("reason")), "a running program has no stop: " + none);
        bridge.call("CONTROL_DEBUG", false, launch, main, "PAUSE", investigation);
        Map<String, Object> paused = waitForStop(launch, 10_000);
        require(Boolean.TRUE.equals(paused.get("stopped")) && "PAUSED".equals(paused.get("reason")), "pause stop: " + paused);
        require(number(paused.get("threadIndex")) == main, "the paused thread is reported: " + paused);
        evidence.put("thread", paused.get("threadName"));
    }

    /** ST-E5: a program that ends without stopping gives TERMINATED, not a timeout. */
    private void terminated(String investigation, Map<String, Object> evidence) throws Exception {
        String launch = start("StepFilterApplication", investigation);
        long t0 = System.nanoTime();
        Map<String, Object> end = waitForStop(launch, 25_000);
        long endMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        require(Boolean.FALSE.equals(end.get("stopped")) && "TERMINATED".equals(end.get("reason")), "program end: " + end);
        require(endMs < 20_000, "TERMINATED reported before the timeout, after " + endMs + " ms");
        evidence.put("terminatedAfterMs", endMs);
    }

    /** OUT-E1: process output reports total sizes next to the (head+tail) text. */
    private void output(String investigation, Map<String, Object> evidence) throws Exception {
        int line = line("OrderBatchApplication", "Thread.sleep(3000);");
        bridge.call("SET_BREAKPOINT", false, PKG + ".OrderBatchApplication", line, investigation);
        String launch = start("OrderBatchApplication", investigation);
        Map<String, Object> stop = waitForStop(launch, 25_000);
        require("BREAKPOINT".equals(stop.get("reason")), "stopped after the output was written: " + stop);

        // Eclipse keeps program output in the console document; once the Console is attached the
        // stream monitors stop buffering, so stdout may be empty. The console is filled
        // asynchronously, so retry briefly.
        Map<String, Object> process = Map.of();
        String where = null;
        for (int i = 0; i < 25 && where == null; i++) {
            Map<String, Object> reply = bridge.call("GET_PROCESS_OUTPUT", false, launch);
            List<Object> processes = arr(reply.get("processes"));
            require(!processes.isEmpty(), "a process is listed: " + reply);
            process = obj(processes.get(0));
            if (str(process.get("stdout")).contains("Order 1057 total")) where = "stdout";
            else if (str(process.get("console")).contains("Order 1057 total")) where = "console";
            else Thread.sleep(200);
        }
        for (String field : List.of("stdoutChars", "stderrChars", "consoleChars"))
            require(process.get(field) instanceof Number, field + " present: " + process.keySet());
        require(where != null, "program output captured in stdout or console: " + process);
        require(number(process.get(where + "Chars")) == str(process.get(where)).length(),
                "short output is returned whole, without an omission marker");
        evidence.put("outputIn", where);
        evidence.put("stdoutChars", process.get("stdoutChars"));
        evidence.put("consoleChars", process.get("consoleChars"));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> waitForStop(String launch, int timeoutMs) throws IOException {
        // The shared Bridge client waits 30 s for a reply, so every wait here stays below that.
        require(timeoutMs <= 25_000, "wait longer than the 30 s test client allows");
        return bridge.call("WAIT_FOR_STOP", false, launch, timeoutMs);
    }

    private String start(String main, String investigation) throws IOException {
        return str(bridge.call("START_JAVA_APPLICATION", false, PROJECT, PKG + "." + main, investigation).get("launchName"));
    }

    private int mainThread(String launch) throws Exception {
        for (int i = 0; i < 50; i++) {
            Map<String, Object> reply = bridge.call("GET_THREADS", true, launch);
            for (Object value : arr(reply.get("threads"))) {
                Map<String, Object> thread = obj(value);
                if ("main".equals(thread.get("name"))) return number(thread.get("index"));
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No main thread in " + launch);
    }

    private static void requireLocation(Map<String, Object> stop, String type, String method, int line) {
        Map<String, Object> location = obj(stop.get("location"));
        require((PKG + "." + type).equals(location.get("className")) && method.equals(location.get("methodName"))
                && number(location.get("lineNumber")) == line, "location " + type + "." + method + ":" + line + " but was " + location);
    }

    private static boolean hasLocal(Map<String, Object> stop, String name) {
        for (Object local : arr(stop.get("locals"))) if (name.equals(obj(local).get("name"))) return true;
        return false;
    }

    private int line(String name, String fragment) throws IOException {
        Path file = root.resolve("examples/phase-one-debugging/src/main/java/")
                .resolve(PKG.replace('.', '/')).resolve(name + ".java");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int found = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(fragment)) {
            require(found == -1, "Ambiguous source fragment in " + file + ": " + fragment);
            found = i + 1;
        }
        require(found > 0, "Source fragment missing in " + file + ": " + fragment);
        return found;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static int number(Object o) { return o instanceof Number n ? n.intValue() : Integer.parseInt(str(o)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> obj(Object o) { return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of(); }
    @SuppressWarnings("unchecked") private static List<Object> arr(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(usage());
        return value;
    }

    private static String usage() {
        return "Usage: --root <repository root> --output <report.json> [--scenario all|" + String.join("|", ALL) + "]";
    }
}
