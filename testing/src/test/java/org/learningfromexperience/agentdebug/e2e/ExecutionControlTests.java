package org.learningfromexperience.agentdebug.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code execution-control}: stepping, step filters, pausing and restarting a program.
 *
 * @author Ayan Dutta
 */
@DisplayName("Execution control: stepping, pause and restart")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class ExecutionControlTests extends EclipseIntegrationTest {

    @Override String group() { return "execution-control"; }

    /** Step Into enters the called method {@code sum}; Step Return comes back to {@code main}, on the same thread. */
    @Test
    @DisplayName("Step Into enters the called method, Step Return comes back")
    void stepIntoAndReturn() throws Exception {
        String investigation = investigation();
        String type = JAVA_PKG + "StepFilterApplication";
        int line = javaLine("StepFilterApplication", "System.out.println(\"Result: \" + sum(values));");
        bridge.call("SET_BREAKPOINT", false, type, line, investigation);
        String launch = startJava("StepFilterApplication", investigation);
        Frame atCall = awaitStop(launch, type, "main", -1, Duration.ofSeconds(25), null, null);
        require(atCall.line() == line, "Stopped on the wrong line: " + atCall);
        bridge.call("CONTROL_DEBUG", false, launch, atCall.thread(), "STEP_INTO", investigation);
        Frame inSum = awaitStop(launch, type, "sum", -1, Duration.ofSeconds(25), null, null);
        require(inSum.thread() == atCall.thread(), "Step Into changed the thread");
        bridge.call("CONTROL_DEBUG", false, launch, inSum.thread(), "STEP_RETURN", investigation);
        Frame back = awaitStop(launch, type, "main", -1, Duration.ofSeconds(25), null, null);
        require(back.thread() == inSum.thread(), "Step Return came back on another thread");
        put("callerLine", atCall.line());
    }

    /**
     * Step Over stops on the next line and is reported as STEP_END by WAIT_FOR_STOP (no breakpoint named); resuming
     * then reports the program's end promptly instead of waiting for the timeout.
     */
    @Test
    @DisplayName("Step Over stops on the next line, then the program end is reported")
    void stepOverToNextLine() throws Exception {
        String investigation = investigation();
        int line = javaLine("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, JAVA_PKG + "StepFilterApplication", line, investigation);
        String launch = startJava("StepFilterApplication", investigation);
        Map<String, Object> first = waitForStop(launch, 20_000);
        require("BREAKPOINT".equals(first.get("reason")), "Breakpoint stop before stepping: " + first);
        bridge.call("CONTROL_DEBUG", false, launch, num(first.get("threadIndex")), "STEP_OVER", investigation);
        Map<String, Object> stepped = waitForStop(launch, 10_000);
        require(Boolean.TRUE.equals(stepped.get("stopped")) && "STEP_END".equals(stepped.get("reason")), "Step end: " + stepped);
        Map<String, Object> location = obj(stepped.get("location"));
        require((JAVA_PKG + "StepFilterApplication").equals(location.get("className")) && "main".equals(location.get("methodName"))
                && num(location.get("lineNumber")) == line + 1, "Step Over should stop on line " + (line + 1) + ": " + location);
        require(stepped.get("breakpoint") == null, "A step stop names no breakpoint: " + stepped);
        bridge.call("CONTROL_DEBUG", false, launch, num(stepped.get("threadIndex")), "RESUME", investigation);
        long t0 = System.nanoTime();
        Map<String, Object> end = waitForStop(launch, 20_000);
        long endMs = ms(t0);
        require(Boolean.FALSE.equals(end.get("stopped")) && "TERMINATED".equals(end.get("reason")), "Program end: " + end);
        require(endMs < 15_000, "Program end reported after " + endMs + " ms");
        put("terminatedAfterMs", endMs);
    }

    /** With step filters for java.*, javax.*, sun.*, Step Into stays in the program's own code; a bad filter is refused. */
    @Test
    @DisplayName("Step filters keep Step Into out of JDK code")
    void stepFiltersSkipJdkCode() throws Exception {
        String investigation = investigation();
        String type = JAVA_PKG + "StepFilterApplication";
        bridge.call("SET_BREAKPOINT", false, type, javaLine("StepFilterApplication", "values.add(10);"), investigation);
        String launch = startJava("StepFilterApplication", investigation);
        Frame stop = awaitStop(launch, type, null, -1, Duration.ofSeconds(20), null, null);
        Map<String, Object> filters = bridge.call("SET_STEP_FILTERS", false, launch, true, "java.*,javax.*,sun.*");
        require(Boolean.TRUE.equals(filters.get("enabled")) && str(filters.get("filters")).contains("java.*"),
                "Step filters not applied: " + filters);
        require("error".equals(bridge.call("SET_STEP_FILTERS", true, launch, true, "bad-prefix!").get("status")),
                "An invalid filter was accepted");
        bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "STEP_INTO", investigation);
        Frame after = awaitStop(launch, type, null, -1, Duration.ofSeconds(20), null, null);
        put("afterStepInto", after.method() + ":" + after.line());
    }

    /**
     * A running program reports no stop; Pause suspends its main thread and is reported as PAUSED for that thread;
     * a restart is refused for another investigation and works for the owning one.
     */
    @Test
    @DisplayName("Pause is reported, restart works only for the owning investigation")
    void pauseAndRestart() throws Exception {
        String investigation = investigation();
        String launch = startJava("LongRunningApplication", investigation);
        int main = mainThread(launch);
        Map<String, Object> none = waitForStop(launch, 1_000);
        require("TIMEOUT".equals(none.get("reason")), "A running program has no stop: " + none);
        bridge.call("CONTROL_DEBUG", false, launch, main, "PAUSE", investigation);
        Map<String, Object> paused = waitForStop(launch, 10_000);
        require(Boolean.TRUE.equals(paused.get("stopped")) && "PAUSED".equals(paused.get("reason")), "Pause stop: " + paused);
        require(num(paused.get("threadIndex")) == main, "The paused thread is reported: " + paused);
        require("error".equals(bridge.call("RESTART_DEBUG", true, launch, "some-other-investigation-id").get("status")),
                "A restart by another investigation was allowed");
        require(Boolean.TRUE.equals(bridge.call("RESTART_DEBUG", false, launch, investigation).get("restarted")), "Restart failed");
        require(alive(launch), "The restarted program is not running");
        put("pausedThread", paused.get("threadName"));
    }

    /**
     * OPEN-12: on a running thread, a step or resume is refused with a clear message (Eclipse itself ignores it
     * silently, and the agent then waited for a stop that never came); out-of-range thread and frame indexes are refused;
     * Pause on a thread that is already paused is accepted and says so.
     */
    @Test
    @DisplayName("Steps on a running thread and wrong indexes are refused clearly; a second Pause is harmless")
    void refusesStepsOnRunningThreads() throws Exception {
        String investigation = investigation();
        String launch = startJava("LongRunningApplication", investigation);
        int main = mainThread(launch);
        // Right after the start Eclipse still holds the thread while it installs breakpoints (27 Nov 02:33): wait until
        // the program really runs, as pause-and-restart does.
        Map<String, Object> none = waitForStop(launch, 1_000);
        require("TIMEOUT".equals(none.get("reason")), "A running program has no stop: " + none);
        for (String action : List.of("STEP_OVER", "STEP_INTO", "STEP_RETURN", "RESUME")) {
            Map<String, Object> refused = bridge.call("CONTROL_DEBUG", true, launch, main, action, investigation);
            require("error".equals(refused.get("status")) && str(refused.get("message")).contains("needs a suspended thread"),
                    action + " on a running thread: " + refused);
        }
        require(alive(launch), "The refused actions changed nothing: the program still runs");
        Map<String, Object> outOfRange = bridge.call("GET_STACK", true, launch, 999);
        require("error".equals(outOfRange.get("status")) && str(outOfRange.get("message")).contains("out of range"),
                "Thread index 999: " + outOfRange);
        bridge.call("CONTROL_DEBUG", false, launch, main, "PAUSE", investigation);
        Map<String, Object> paused = waitForStop(launch, 10_000);
        require("PAUSED".equals(paused.get("reason")), "Pause stop: " + paused);
        Map<String, Object> again = bridge.call("CONTROL_DEBUG", false, launch, main, "PAUSE", investigation);
        require(Boolean.TRUE.equals(again.get("alreadySuspended")), "A second Pause says the thread was already paused: " + again);
        Map<String, Object> frame = bridge.call("GET_VARIABLES", true, launch, main, 999);
        require("error".equals(frame.get("status")) && str(frame.get("message")).contains("Frame index out of range"),
                "Frame index 999: " + frame);
        put("refused", List.of("STEP_OVER", "STEP_INTO", "STEP_RETURN", "RESUME"));
    }

    /** OPEN-12: after the program has ended, control and inspection say so instead of a bare "not found". */
    @Test
    @DisplayName("After the program ends, control and inspection say that it has ended")
    void endedProgramIsReportedClearly() throws Exception {
        String investigation = investigation();
        String launch = startJava("StepFilterApplication", investigation);
        Map<String, Object> end = waitForStop(launch, 20_000);
        require("TERMINATED".equals(end.get("reason")), "The program runs to its end without breakpoints: " + end);
        for (Map<String, Object> reply : List.of(
                bridge.call("CONTROL_DEBUG", true, launch, 0, "STEP_OVER", investigation),
                bridge.call("GET_VARIABLES", true, launch, 0, 0))) {
            String message = str(reply.get("message"));
            require("error".equals(reply.get("status")) && message.contains("has ended"), "Ended program: " + reply);
            put("message", message);
        }
    }

    /** OPEN-12: Step Over inside a JUnit test run (debug mode) stops on the next line of the code under test. */
    @Test
    @DisplayName("Step Over works inside a JUnit test")
    void stepOverInJUnitTest() throws Exception {
        String investigation = investigation();
        String service = ORDERS_PKG + "OrderService";
        int line = bootLine(service, "int rate = percent / 100;");
        bridge.call("SET_BREAKPOINT", false, service, line, investigation);
        String launch = str(bridge.call("START_JUNIT_TEST", false, BOOT_PROJECT, ORDERS_PKG + "OrderServiceTest",
                "calculatesDiscount", investigation).get("launchName"));
        Frame stop = awaitStop(launch, service, "quote", line, Duration.ofSeconds(30), null, null);
        bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "STEP_OVER", investigation);
        Map<String, Object> stepped = waitForStop(launch, 10_000);
        Map<String, Object> location = obj(stepped.get("location"));
        require("STEP_END".equals(stepped.get("reason")) && service.equals(location.get("className"))
                && num(location.get("lineNumber")) == line + 1, "Step Over in the test stops on line " + (line + 1) + ": " + stepped);
        requireLocal(launch, num(stepped.get("threadIndex")), "rate", "0");
        put("steppedTo", "OrderService.quote:" + (line + 1));
    }

    /**
     * OPEN-12: Step Over in a Spring Boot request thread (POST /orders/quote) stops on the next line, and resuming
     * completes the request.
     */
    @Test
    @DisplayName("Step Over works in a Spring Boot request thread")
    void stepOverInSpringBootRequest() throws Exception {
        String investigation = investigation();
        String service = ORDERS_PKG + "OrderService";
        int line = bootLine(service, "int rate = percent / 100;");
        bridge.call("SET_BREAKPOINT", false, service, line, investigation);
        String launch = startProgram(BOOT_PROJECT, ORDERS_MAIN, investigation);
        Path folder = Files.createTempDirectory("agentdebug-step-");
        Path receipt = folder.resolve("result.json");
        Path log = folder.resolve("client.log");
        Process client = startFixtureClient(launch, receipt, true, false, log);
        try {
            Frame stop = awaitStop(launch, service, "quote", line, Duration.ofSeconds(155), client, log);
            bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "STEP_OVER", investigation);
            Map<String, Object> stepped = waitForStop(launch, 10_000);
            Map<String, Object> location = obj(stepped.get("location"));
            require("STEP_END".equals(stepped.get("reason")) && num(location.get("lineNumber")) == line + 1
                    && num(stepped.get("threadIndex")) == stop.thread(),
                    "Step Over in the request thread stops on line " + (line + 1) + " of the same thread: " + stepped);
            requireLocal(launch, stop.thread(), "rate", "0");
            bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "RESUME", investigation);
            require(client.waitFor(45, TimeUnit.SECONDS), "HTTP client timed out: " + clientLog(log));
            require(client.exitValue() == 0, "The request completed after resuming: " + clientLog(log));
            put("steppedTo", "OrderService.quote:" + (line + 1));
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            Files.deleteIfExists(receipt);
        }
    }

    private int mainThread(String launch) throws Exception {
        for (int i = 0; i < 50; i++) {
            for (Map<String, Object> thread : rows(bridge.call("GET_THREADS", true, launch).get("threads")))
                if ("main".equals(thread.get("name"))) return num(thread.get("index"));
            Thread.sleep(200);
        }
        throw new AssertionError("No main thread in " + launch);
    }
}
