package org.learningfromexperience.agentdebug.e2e;

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

    private int mainThread(String launch) throws Exception {
        for (int i = 0; i < 50; i++) {
            for (Map<String, Object> thread : rows(bridge.call("GET_THREADS", true, launch).get("threads")))
                if ("main".equals(thread.get("name"))) return num(thread.get("index"));
            Thread.sleep(200);
        }
        throw new AssertionError("No main thread in " + launch);
    }
}
