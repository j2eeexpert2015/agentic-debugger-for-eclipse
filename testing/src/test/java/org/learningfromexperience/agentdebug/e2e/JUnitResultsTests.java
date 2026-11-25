package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code junit-results} (extended suite, OPEN-25): the agent gets a JUnit run's outcome, as Eclipse's JUnit view
 * shows it, and can run a test without the debugger to read that outcome first. Uses the fixture's
 * {@code OrderServiceTest}: {@code quotesFullPrice} passes, {@code calculatesDiscount} fails (expected 108.00, was
 * 120.00, at line 19).
 *
 * @author Ayan Dutta
 */
@DisplayName("JUnit results: the agent reads which test failed, expected and actual, and where")
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class JUnitResultsTests extends EclipseIntegrationTest {
    private static final String TEST_CLASS = ORDERS_PKG + "OrderServiceTest";

    @Override String group() { return "junit-results"; }

    /**
     * Run without the debugger: a breakpoint does not stop it, and the failure comes back whole. Then the debug run in
     * the same investigation is allowed and stops at that breakpoint (the run must not take the investigation's one
     * debug session; trial 26 Nov 02:25).
     */
    @Test
    @DisplayName("A test run without the debugger reports the failure; the debug run in the same investigation follows")
    void runModeReportsTheFailure() throws Exception {
        String investigation = investigation();
        bridge.call("SET_BREAKPOINT", false, ORDERS_PKG + "OrderService",
                bootLine(ORDERS_PKG + "OrderService", "int rate = percent / 100;"), investigation);
        Map<String, Object> started = bridge.call("START_JUNIT_TEST", false, BOOT_PROJECT, TEST_CLASS,
                "calculatesDiscount", investigation, "RUN");
        put("started", started);
        require("run".equals(started.get("mode")), "Started in run mode: " + started);
        require(Boolean.TRUE.equals(started.get("resultsAvailable")), "Eclipse's JUnit results are available: " + started);
        Map<String, Object> results = finishedResults(str(started.get("launchName")));
        require("FAILED".equals(results.get("result")) && num(results.get("total")) == 1 && num(results.get("failed")) == 1,
                "One test, failed: " + results);
        Map<String, Object> test = obj(arr(results.get("tests")).get(0));
        int assertionLine = sourceLine(env.root.resolve("examples/spring-boot-debugging/src/test/java/")
                .resolve(TEST_CLASS.replace('.', '/') + ".java"), "isEqualByComparingTo(\"108.00\")");
        put("failure", test);
        require("calculatesDiscount".equals(test.get("methodName")) && "FAILED".equals(test.get("result")),
                "calculatesDiscount failed: " + test);
        require(str(test.get("expected")).contains("108") && str(test.get("actual")).contains("120"),
                "Expected 108.00 and actual 120.00 given: " + test);
        require(("OrderServiceTest.calculatesDiscount:" + assertionLine).equals(test.get("location")),
                "The failure location is the assertion line " + assertionLine + ": " + test);
        Map<String, Object> debug = bridge.call("START_JUNIT_TEST", true, BOOT_PROJECT, TEST_CLASS,
                "calculatesDiscount", investigation);
        put("debugAfterRun", debug);
        require("success".equals(debug.get("status")), "The debug run after the run is allowed in the same investigation: " + debug);
        Frame stop = awaitStop(str(debug.get("launchName")), ORDERS_PKG + "OrderService", "quote", -1,
                Duration.ofSeconds(30), null, null);
        put("debugStoppedAt", stop.method() + ":" + stop.line());
    }

    /** The whole class: two tests, one passed and one failed, the failed one listed first. */
    @Test
    @DisplayName("A whole test class reports each method, failed ones first, with counts")
    void wholeClassCountsEachTest() throws Exception {
        Map<String, Object> started = bridge.call("START_JUNIT_TEST", false, BOOT_PROJECT, TEST_CLASS, "",
                investigation(), "RUN");
        Map<String, Object> results = finishedResults(str(started.get("launchName")));
        put("counts", "total " + results.get("total") + ", passed " + results.get("passed") + ", failed " + results.get("failed"));
        require(num(results.get("total")) == 2 && num(results.get("passed")) == 1 && num(results.get("failed")) == 1,
                "Two tests, one passed, one failed: " + results);
        List<Object> tests = arr(results.get("tests"));
        require("calculatesDiscount".equals(obj(tests.get(0)).get("methodName"))
                && "quotesFullPrice".equals(obj(tests.get(1)).get("methodName"))
                && "PASSED".equals(obj(tests.get(1)).get("result")), "Failed test first, then the passed one: " + tests);
    }

    /** In debug mode the run is not finished while it is paused; after resuming, the same result comes back. */
    @Test
    @DisplayName("In debug mode the result says 'in progress' while paused; wait_for_stop's end brings it; a new debug run may follow")
    void debugModeResultAfterResume() throws Exception {
        String investigation = investigation();
        bridge.call("SET_BREAKPOINT", false, ORDERS_PKG + "OrderService",
                bootLine(ORDERS_PKG + "OrderService", "int rate = percent / 100;"), investigation);
        String launch = str(bridge.call("START_JUNIT_TEST", false, BOOT_PROJECT, TEST_CLASS, "calculatesDiscount",
                investigation).get("launchName"));
        Frame stop = awaitStop(launch, ORDERS_PKG + "OrderService", "quote", -1, Duration.ofSeconds(30), null, null);
        Map<String, Object> paused = bridge.call("GET_JUNIT_RESULTS", false, launch, 0);
        put("whilePaused", Map.of("finished", String.valueOf(paused.get("finished")), "note", String.valueOf(paused.get("note"))));
        require(Boolean.FALSE.equals(paused.get("finished")) && str(paused.get("note")).contains("still in progress"),
                "Not finished while paused at the breakpoint: " + paused);
        bridge.call("REMOVE_BREAKPOINT", false, ORDERS_PKG + "OrderService",
                bootLine(ORDERS_PKG + "OrderService", "int rate = percent / 100;"));
        bridge.call("CONTROL_DEBUG", false, launch, stop.thread(), "RESUME", investigation);
        // The end of the run brings the result along, without a separate call (OPEN-25).
        Map<String, Object> ended = bridge.call("WAIT_FOR_STOP", false, launch, 20_000);
        put("endOfRun", ended);
        Map<String, Object> testResult = obj(ended.get("testResult"));
        require("TERMINATED".equals(ended.get("reason")) && "FAILED".equals(testResult.get("result"))
                && str(obj(testResult.get("firstFailure")).get("location")).startsWith("OrderServiceTest.calculatesDiscount:"),
                "wait_for_stop TERMINATED carries the failed test and its location: " + ended);
        Map<String, Object> results = finishedResults(launch);
        require("FAILED".equals(results.get("result")), "After resuming, the test failed as in run mode: " + results);
        // The debug run has ended: a new debug run in the same investigation is allowed (before 26 Nov it was refused
        // with "Investigation already targets another debug session" and agents finished and began again).
        bridge.call("SET_BREAKPOINT", false, ORDERS_PKG + "OrderService",
                bootLine(ORDERS_PKG + "OrderService", "int rate = percent / 100;"), investigation);
        String again = str(bridge.call("START_JUNIT_TEST", false, BOOT_PROJECT, TEST_CLASS, "calculatesDiscount",
                investigation).get("launchName"));
        require(!again.equals(launch), "The second debug run is a new launch: " + again);
        Frame second = awaitStop(again, ORDERS_PKG + "OrderService", "quote", -1, Duration.ofSeconds(30), null, null);
        put("secondDebugRun", Map.of("launchName", again, "stoppedIn", "OrderService.quote"));
        require(second != null, "The second debug run in the same investigation stopped at the breakpoint");
    }

    /** Waits up to 2 x 20 s (the bridge socket allows 30 s per call) for a finished run. */
    private Map<String, Object> finishedResults(String launch) throws IOException {
        Map<String, Object> results = bridge.call("GET_JUNIT_RESULTS", false, launch, 20_000);
        if (!Boolean.TRUE.equals(results.get("finished"))) results = bridge.call("GET_JUNIT_RESULTS", false, launch, 20_000);
        put("results", results);
        require(Boolean.TRUE.equals(results.get("finished")), "The test run finished: " + results);
        return results;
    }
}
