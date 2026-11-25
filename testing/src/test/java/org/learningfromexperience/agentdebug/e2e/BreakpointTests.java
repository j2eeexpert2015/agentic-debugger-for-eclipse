package org.learningfromexperience.agentdebug.e2e;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code breakpoints}: conditional, exception and trace breakpoints in the plain Java fixture.
 *
 * @author Ayan Dutta
 */
@DisplayName("Breakpoints")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class BreakpointTests extends EclipseIntegrationTest {

    @Override String group() { return "breakpoints"; }

    /**
     * A breakpoint with condition {@code orderId == 1057} stops only for that order; a second condition on the same
     * line is refused instead of overwriting it; the breakpoint is listed with its condition.
     */
    @Test
    @DisplayName("Conditional breakpoint stops only when the condition matches")
    void conditionalStopsOnlyOnMatch() throws Exception {
        String investigation = investigation();
        String type = JAVA_PKG + "OrderCalculator";
        int line = javaLine("OrderCalculator", "double discountRate =");
        Map<String, Object> set = bridge.call("SET_CONDITIONAL_BREAKPOINT", false, type, line, "orderId == 1057", investigation);
        require(Boolean.TRUE.equals(set.get("created")) && Boolean.FALSE.equals(set.get("tracepoint")),
                "Conditional breakpoint not created: " + set);
        require("error".equals(bridge.call("SET_CONDITIONAL_BREAKPOINT", true, type, line, "orderId == 1000",
                investigation).get("status")), "An existing condition was overwritten");
        String launch = startJava("OrderBatchApplication", investigation);
        Frame stop = awaitStop(launch, type, "calculate", line, Duration.ofSeconds(20), null, null);
        requireLocal(launch, stop.thread(), "orderId", "1057");
        requireLocal(launch, stop.thread(), "discountPercent", "10");
        require(rows(bridge.call("LIST_BREAKPOINTS", false).get("breakpoints")).stream()
                .anyMatch(b -> type.equals(b.get("typeName")) && num(b.get("lineNumber")) == line
                        && "orderId == 1057".equals(b.get("condition"))), "Conditional breakpoint missing from the list");
        bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "RESUME", investigation);
        awaitProgramEnd(launch, Duration.ofSeconds(30));
        put("line", line);
    }

    /**
     * An exception breakpoint: settings that would never stop are refused, an existing one is not changed, it is
     * listed as the agent's, it never turns on Eclipse's blocking "Repeated exception occurrence" dialog (CORE-16),
     * the stop is reported as EXCEPTION with its type in the method that threw, and the exception's message can be
     * read. Stops inside the JDK that throw the same type first are skipped.
     */
    @Test
    @DisplayName("Exception breakpoint stops where the exception is thrown, without a blocking dialog")
    void exceptionStopsWhereThrown() throws Exception {
        String investigation = investigation();
        String type = "java.lang.IllegalStateException";
        require("error".equals(bridge.call("SET_EXCEPTION_BREAKPOINT", true, type, false, false, investigation).get("status")),
                "An exception breakpoint for neither caught nor uncaught was accepted");
        Map<String, Object> created = bridge.call("SET_EXCEPTION_BREAKPOINT", false, type, true, true, investigation);
        require(Boolean.TRUE.equals(created.get("created")), "Exception breakpoint not created: " + created);
        String recurrence = str(created.get("repeatedException"));
        require(!recurrence.isBlank() && !recurrence.equals("RECURRENCE_UNCONFIGURED"),
                "The blocking repeated-exception dialog is still possible: " + created);
        put("repeatedException", recurrence);
        require("error".equals(bridge.call("SET_EXCEPTION_BREAKPOINT", true, type, true, false, investigation).get("status")),
                "An existing exception breakpoint was changed");
        require(rows(bridge.call("LIST_EXCEPTION_BREAKPOINTS", false).get("breakpoints")).stream()
                .anyMatch(b -> type.equals(b.get("exceptionName")) && "agent".equals(b.get("owner"))),
                "The agent's exception breakpoint is not listed");
        String launch = startJava("ExceptionApplication", investigation);
        for (int attempt = 0; attempt < 5; attempt++) {
            Map<String, Object> stop = waitForStop(launch, 20_000);
            require(Boolean.TRUE.equals(stop.get("stopped")) && "EXCEPTION".equals(stop.get("reason")), "Exception stop: " + stop);
            require(type.equals(stop.get("exceptionType")), "Exception type reported: " + stop);
            Map<String, Object> location = obj(stop.get("location"));
            int thread = num(stop.get("threadIndex"));
            if (!(JAVA_PKG + "ExceptionApplication").equals(location.get("className"))) {
                bridge.call("CONTROL_DEBUG", false, launch, thread, "RESUME", investigation);
                continue;
            }
            require("processOrder".equals(location.get("methodName")), "Stopped in processOrder: " + location);
            String thrown = rows(bridge.call("GET_VARIABLES", false, launch, thread, 0).get("variables")).stream()
                    .filter(v -> type.equals(v.get("type"))).map(v -> str(v.get("name"))).findFirst()
                    .orElseThrow(() -> new AssertionError("The thrown exception is not among the stopped frame's variables"));
            List<Map<String, Object>> fields = rows(bridge.call("EXPAND_VARIABLE", false, launch, thread, 0, thrown, 0, 50)
                    .get("children"));
            require(fields.stream().anyMatch(f -> "detailMessage".equals(f.get("name"))
                    && str(f.get("value")).contains("Quantity must be positive")), "Exception message not readable: " + fields);
            put("skippedJdkStops", attempt);
            return;
        }
        throw new AssertionError("No exception stop inside ExceptionApplication after 5 stops");
    }

    /** A tracepoint prints "TRACE-ORDER 1057" to the program's output, and the program runs to its end. */
    @Test
    @DisplayName("Tracepoint prints its line and the program runs on")
    void tracepointPrintsWithoutStopping() throws Exception {
        String investigation = investigation();
        Map<String, Object> set = bridge.call("SET_TRACEPOINT", false, JAVA_PKG + "OrderCalculator",
                javaLine("OrderCalculator", "double discountRate ="), "\"TRACE-ORDER \" + orderId", investigation);
        require(Boolean.TRUE.equals(set.get("created")) && Boolean.TRUE.equals(set.get("tracepoint")), "Tracepoint not set: " + set);
        String launch = startJava("OrderBatchApplication", investigation);
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < end) {
            if (processOutput(launch).lines().anyMatch("TRACE-ORDER 1057"::equals)) {
                awaitProgramEnd(launch, Duration.ofSeconds(30));
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Trace line TRACE-ORDER 1057 missing from the program output");
    }

    /** The launch has ended (terminated, or already removed from the debug view). */
    private void awaitProgramEnd(String launch, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> state = bridge.call("GET_DEBUG_STATE", true, launch);
            if (Boolean.TRUE.equals(state.get("terminated"))
                    || ("IllegalArgumentException: Active debug launch not found: " + launch).equals(state.get("message"))) return;
            Thread.sleep(200);
        }
        throw new AssertionError("The program did not end: " + launch);
    }
}
