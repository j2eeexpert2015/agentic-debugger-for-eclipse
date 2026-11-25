package org.learningfromexperience.agentdebug.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code stops-and-variables}: what the agent sees when the program stops or ends (WAIT_FOR_STOP), nested
 * objects, and the program's output.
 *
 * @author Ayan Dutta
 */
@DisplayName("Stops and variables: what the agent sees when the program stops")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class StopsAndVariablesTests extends EclipseIntegrationTest {

    @Override String group() { return "stops-and-variables"; }

    /**
     * One WAIT_FOR_STOP call returns the breakpoint stop with the breakpoint, location, thread, frames and local
     * variables; the same stop is never reported twice (the next wait times out).
     */
    @Test
    @DisplayName("A stop reports location, thread, frames and variables, once")
    void stopReportsLocationAndVariables() throws Exception {
        String investigation = investigation();
        String type = JAVA_PKG + "StepFilterApplication";
        int line = javaLine("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, type, line, investigation);
        String launch = startJava("StepFilterApplication", investigation);
        long t0 = System.nanoTime();
        Map<String, Object> stop = waitForStop(launch, 20_000);
        put("stopReplyMs", ms(t0));
        require(Boolean.TRUE.equals(stop.get("stopped")) && "BREAKPOINT".equals(stop.get("reason")), "Breakpoint stop: " + stop);
        Map<String, Object> breakpoint = obj(stop.get("breakpoint"));
        require(type.equals(breakpoint.get("typeName")) && num(breakpoint.get("lineNumber")) == line, "Breakpoint named: " + breakpoint);
        Map<String, Object> location = obj(stop.get("location"));
        require(type.equals(location.get("className")) && "main".equals(location.get("methodName"))
                && num(location.get("lineNumber")) == line, "Location: " + location);
        require(num(stop.get("threadIndex")) >= 0 && !str(stop.get("threadName")).isBlank(), "Thread named: " + stop);
        require(!arr(stop.get("frames")).isEmpty() && num(stop.get("totalFrames")) >= 1, "Frames included: " + stop);
        require(hasLocal(stop, "values") && hasLocal(stop, "args"), "Local variables included: " + stop.get("locals"));
        Map<String, Object> again = waitForStop(launch, 2_000);
        require(Boolean.FALSE.equals(again.get("stopped")) && "TIMEOUT".equals(again.get("reason")),
                "An already reported stop was reported again: " + again);
        put("line", line);
    }

    /** A program that ends without stopping is reported as TERMINATED well before the wait's timeout. */
    @Test
    @DisplayName("A program that ends without stopping is reported promptly")
    void programEndReported() throws Exception {
        String launch = startJava("StepFilterApplication", investigation());
        long t0 = System.nanoTime();
        Map<String, Object> end = waitForStop(launch, 25_000);
        long endMs = ms(t0);
        require(Boolean.FALSE.equals(end.get("stopped")) && "TERMINATED".equals(end.get("reason")), "Program end: " + end);
        require(endMs < 20_000, "Program end reported after " + endMs + " ms");
        put("terminatedAfterMs", endMs);
    }

    /** order.customer.address can be expanded level by level down to its value; a page size of 0 is refused. */
    @Test
    @DisplayName("Nested objects expand level by level")
    void nestedObjectsExpand() throws Exception {
        String investigation = investigation();
        String type = JAVA_PKG + "NestedObjectApplication";
        bridge.call("SET_BREAKPOINT", false, type, javaLine("NestedObjectApplication", "double total = sum(order);"), investigation);
        String launch = startJava("NestedObjectApplication", investigation);
        Frame stop = awaitStop(launch, type, null, -1, Duration.ofSeconds(20), null, null);
        require("error".equals(bridge.call("EXPAND_VARIABLE", true, launch, stop.thread(), 0, "order.customer", 0, 0).get("status")),
                "Page size 0 was accepted");
        require(rows(bridge.call("EXPAND_VARIABLE", false, launch, stop.thread(), 0, "order.customer", 0, 20).get("children"))
                .stream().anyMatch(c -> str(c.get("name")).contains("address")), "customer.address not listed");
        require(rows(bridge.call("EXPAND_VARIABLE", false, launch, stop.thread(), 0, "order.customer.address", 0, 20).get("children"))
                .stream().anyMatch(c -> str(c.get("value")).contains("Kolkata")), "The nested address value is missing");
    }

    /** The program's output is returned with its total sizes; short output comes back whole. */
    @Test
    @DisplayName("Program output is read with its sizes")
    void programOutputRead() throws Exception {
        String investigation = investigation();
        bridge.call("SET_BREAKPOINT", false, JAVA_PKG + "OrderBatchApplication",
                javaLine("OrderBatchApplication", "Thread.sleep(3000);"), investigation);
        String launch = startJava("OrderBatchApplication", investigation);
        Map<String, Object> stop = waitForStop(launch, 25_000);
        require("BREAKPOINT".equals(stop.get("reason")), "Stopped after the output was written: " + stop);
        // Eclipse keeps program output in the console document; once the Console is attached the stream monitors
        // stop buffering, so stdout may be empty. The console is filled asynchronously, so retry briefly.
        Map<String, Object> process = Map.of();
        String where = null;
        for (int i = 0; i < 25 && where == null; i++) {
            List<Object> processes = arr(bridge.call("GET_PROCESS_OUTPUT", false, launch).get("processes"));
            require(!processes.isEmpty(), "No process listed for " + launch);
            process = obj(processes.get(0));
            if (str(process.get("stdout")).contains("Order 1057 total")) where = "stdout";
            else if (str(process.get("console")).contains("Order 1057 total")) where = "console";
            else Thread.sleep(200);
        }
        for (String field : List.of("stdoutChars", "stderrChars", "consoleChars"))
            require(process.get(field) instanceof Number, field + " missing: " + process.keySet());
        require(where != null, "Program output not in stdout or console: " + process);
        require(num(process.get(where + "Chars")) == str(process.get(where)).length(),
                "Short output should come back whole, without an omission marker");
        put("outputIn", where);
    }

    private static boolean hasLocal(Map<String, Object> stop, String name) {
        for (Object local : arr(stop.get("locals"))) if (name.equals(obj(local).get("name"))) return true;
        return false;
    }
}
