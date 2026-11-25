package org.learningfromexperience.agentdebug.e2e;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

/**
 * Group {@code user-resource-safety} (OPEN-02, OPEN-03): an agent investigation never takes over, changes or removes
 * what belongs to the user. Before the tests the test Eclipse gets the user's own things, created the way a person
 * creates them (test command {@code TEST_USER_SEED}, plugin class {@code TestUserResources}): a line breakpoint in
 * {@code LongRunningApplication}, an exception breakpoint for {@code ArithmeticException} (caught only), and a saved
 * launch configuration with program and VM arguments and an environment variable, running in debug mode and stopped at
 * the user's breakpoint. After the tests everything is removed again ({@code TEST_USER_CLEANUP}).
 *
 * <p>These are the 32 ownership checks of the former in-Eclipse harness {@code testing/ownership-tests} (started by
 * PowerShell), each still recorded under its old name in the report, plus the OPEN-02 check that the user's launch
 * configuration keeps every setting. The tests run in the order given (the user's program is borrowed, duplicated,
 * ended and restarted along the way).
 *
 * @author Ayan Dutta
 */
@DisplayName("User resource safety: the user's breakpoints and launches are never taken over, changed or removed")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class UserResourceSafetyTests extends EclipseIntegrationTest {
    private static final String USER_APP = JAVA_PKG + "LongRunningApplication";
    private static final String STEP_APP = JAVA_PKG + "StepFilterApplication";
    private static final String NESTED_APP = JAVA_PKG + "NestedObjectApplication";
    private static final String USER_EXCEPTION = "java.lang.ArithmeticException";
    private static final String JUNIT_CLASS = JAVA_PKG + "OrderCalculatorTest";

    /** The name of the user's launch configuration (also the name of its debug launches). */
    private String userLaunch;
    private int userLine;
    /** The checks of the running test: name → passed. */
    private Map<String, Boolean> checks;

    @Override String group() { return "user-resource-safety"; }

    @BeforeAll
    void seedUserResources() throws Exception {
        Map<String, Object> probe = bridge.call("TEST_USER_STATE", true);
        require("success".equals(probe.get("status")),
                "The test Eclipse has no TEST_USER_* commands: deploy a current build. Reply: " + probe);
        bridge.call("TEST_USER_CLEANUP", true); // leftovers of an interrupted earlier run
        userLine = javaLine("LongRunningApplication", "while (System.nanoTime() < deadline) {");
        Map<String, Object> seeded = bridge.call("TEST_USER_SEED", false, USER_APP, userLine, USER_EXCEPTION);
        userLaunch = str(seeded.get("configurationName"));
        require(!userLaunch.isBlank(), "No user launch configuration: " + seeded);
        awaitSuspended(userLaunch);
    }

    @AfterAll
    void removeUserResources() throws IOException {
        if (bridge == null) return;
        Map<String, Object> reply = bridge.call("TEST_USER_CLEANUP", true);
        System.out.println(group() + ": user resources removed: " + reply.get("status")
                + ("success".equals(reply.get("status")) ? "" : " - " + reply.get("message")));
    }

    /** The agent sees the user's breakpoints as the user's: it cannot replace, add a condition to or remove them. */
    @Test
    @Order(1)
    @DisplayName("The user's breakpoints cannot be taken over or removed")
    void userBreakpointsCannotBeTakenOver() throws Exception {
        start();
        String agent = begin("NORMAL");
        try {
            check("user line marker recognized",
                    "user".equals(bridge.call("SET_BREAKPOINT", false, USER_APP, userLine, agent).get("owner")));
            check("user condition collision rejected",
                    rejected("SET_CONDITIONAL_BREAKPOINT", USER_APP, userLine, "false", agent));
            check("user line marker deletion refused",
                    Boolean.FALSE.equals(bridge.call("REMOVE_BREAKPOINT", true, USER_APP, userLine).get("removed")));
            check("user exception collision rejected",
                    rejected("SET_EXCEPTION_BREAKPOINT", USER_EXCEPTION, false, true, agent));
            check("user exception deletion refused",
                    Boolean.FALSE.equals(bridge.call("REMOVE_EXCEPTION_BREAKPOINT", true, USER_EXCEPTION).get("removed")));
        } finally {
            finish(agent, evidence, "cleanup");
        }
        check("user markers retain settings", userBreakpointsUnchanged());
        verdict();
    }

    /** An agent may use the user's running program, but never stop or restart it; it stays stopped where it was. */
    @Test
    @Order(2)
    @DisplayName("A borrowed user program is never stopped or restarted")
    void borrowedUserProgramIsNeverStopped() throws Exception {
        start();
        // The loop counter of the user's program: if anything resumed it, it would stop again one round later with a
        // higher count. Same count before and after = never resumed (a single "suspended" read can catch Eclipse in
        // the middle of evaluating a variable for its views; run of 25 Nov 21:36 on Eclipse 2025-12).
        String countBefore = userLoopCount();
        put("userLoopCountBefore", countBefore);
        String borrower = begin("NORMAL");
        try {
            check("pre-existing launch reused",
                    Boolean.TRUE.equals(bridge.call("START_DEBUG", false, userLaunch, borrower).get("reused")));
            check("borrower cannot stop", rejected("CONTROL_DEBUG", userLaunch, 0, "STOP", borrower));
            check("borrower cannot restart", rejected("RESTART_DEBUG", userLaunch, borrower));
            int configurations = configurationCount();
            check("wrong JUnit target rejected",
                    rejected("START_JUNIT_TEST", JAVA_PROJECT, JUNIT_CLASS, "appliesTenPercentDiscount", borrower));
            check("rejection creates no launch configuration", configurations == configurationCount());
            check("invalid investigation rejected", rejected("CONTROL_DEBUG", userLaunch, 0, "STOP", "invalid-test-id"));
        } finally {
            finish(borrower, evidence, "cleanup");
        }
        String countAfter = userLoopCount();
        put("userLoopCountAfter", countAfter);
        check("reused session stays suspended", countBefore.equals(countAfter));
        verdict();
    }

    /**
     * Finishing a NORMAL investigation removes its own breakpoint and ends its own program, and nothing else: not the
     * user's program, and not a breakpoint the user set where the agent's was. Another investigation cannot resume it.
     */
    @Test
    @Order(3)
    @DisplayName("Cleanup removes only the agent's own breakpoints and program")
    void cleanupRemovesOnlyTheAgentsOwnThings() throws Exception {
        start();
        int stepLine = javaLine("StepFilterApplication", "values.add(10);");
        int nestedLine = javaLine("NestedObjectApplication", "double total = sum(order);");
        String owner = begin("NORMAL"), other = begin("NORMAL");
        String launch = null;
        boolean ownerFinished = false;
        try {
            check("owned marker created",
                    Boolean.TRUE.equals(bridge.call("SET_BREAKPOINT", false, STEP_APP, stepLine, owner).get("created")));
            launch = startProgram(JAVA_PROJECT, STEP_APP, owner);
            Frame stop = awaitStop(launch, STEP_APP, "main", stepLine, Duration.ofSeconds(25), null, null);
            check("wrong investigation cannot resume other session",
                    rejected("CONTROL_DEBUG", launch, stop.thread(), "RESUME", other));
            check("temporary owned marker created",
                    Boolean.TRUE.equals(bridge.call("SET_BREAKPOINT", false, NESTED_APP, nestedLine, owner).get("created")));
            // The user deletes the agent's breakpoint in the editor and sets their own on the same line.
            bridge.call("TEST_USER_REPLACE_BREAKPOINT", false, NESTED_APP, nestedLine);
            ownerFinished = finish(owner, evidence, "ownerFinish");
            Map<String, Object> state = userState();
            check("user replacement at old marker location survives cleanup",
                    Boolean.TRUE.equals(obj(state.get("replacementBreakpoint")).get("exists")));
            check("NORMAL removes its own marker", !breakpointAt(STEP_APP, stepLine));
            check("NORMAL terminates only its own launch", !alive(launch) && alive(userLaunch));
            check("completed ID rejected", rejected("FINISH_INVESTIGATION", owner));
        } finally {
            if (!ownerFinished && !evidence.containsKey("ownerFinish")) finish(owner, evidence, "ownerFinish");
            finish(other, evidence, "cleanup");
        }
        verdict();
    }

    /** Malformed or out-of-range requests are refused with an error, never half-executed. */
    @Test
    @Order(4)
    @DisplayName("Invalid requests are refused safely")
    void invalidRequestsAreRefused() throws Exception {
        start();
        int stepLine = javaLine("StepFilterApplication", "values.add(10);");
        bridge.call("SET_BREAKPOINT", false, STEP_APP, stepLine, investigation());
        String launch = startJava("StepFilterApplication", investigation());
        Frame stop = awaitStop(launch, STEP_APP, "main", stepLine, Duration.ofSeconds(25), null, null);
        check("invalid mode rejected", rejected("BEGIN_INVESTIGATION", "INVALID"));
        String fresh = begin("NORMAL");
        try {
            check("unknown JUnit method rejected",
                    rejected("START_JUNIT_TEST", JAVA_PROJECT, JUNIT_CLASS, "noSuchMethod", fresh));
        } finally {
            finish(fresh, evidence, "freshFinish");
        }
        check("invalid variable path rejected",
                rejected("EXPAND_VARIABLE", launch, stop.thread(), 0, "values.clear()", 0, 10));
        check("out-of-range variable page rejected",
                rejected("EXPAND_VARIABLE", launch, stop.thread(), 0, "values", 0, 51));
        check("malformed step filter rejected", rejected("SET_STEP_FILTERS", launch, true, "java.*!"));
        check("too many step filters rejected",
                rejected("SET_STEP_FILTERS", launch, true, String.join(",", Collections.nCopies(31, "java.*"))));
        verdict();
    }

    /** With two running programs of the same name, the agent is never left to guess which one is meant. */
    @Test
    @Order(5)
    @DisplayName("Two user programs of the same name are never guessed between")
    void sameNameProgramsAreNeverGuessed() throws Exception {
        start();
        bridge.call("TEST_USER_LAUNCH_AGAIN", false);
        String agent = begin("NORMAL");
        try {
            awaitTwoLaunches(userLaunch);
            check("same-name concurrent launches rejected", rejected("START_DEBUG", userLaunch, agent));
            check("ambiguous state lookup rejected", rejected("GET_DEBUG_STATE", userLaunch));
        } finally {
            finish(agent, evidence, "cleanup");
            bridge.call("TEST_TERMINATE_LAUNCH", false, userLaunch, "newest");
        }
        awaitSuspended(userLaunch);
        verdict();
    }

    /**
     * An investigation that used the user's program cannot control or finish against a new run of the same
     * configuration once the user ended the old one and started it again.
     */
    @Test
    @Order(6)
    @DisplayName("An old investigation cannot control the user's new run of the same program")
    void staleInvestigationCannotControlNewRun() throws Exception {
        start();
        // A short lease: this investigation cannot be finished (that is checked), so it expires by itself.
        String stale = str(bridge.call("BEGIN_INVESTIGATION", false, "CONTROLLED", 5).get("investigationId"));
        bridge.call("START_DEBUG", false, userLaunch, stale);
        bridge.call("TEST_TERMINATE_LAUNCH", false, userLaunch, "all");
        bridge.call("TEST_USER_LAUNCH_AGAIN", false);
        awaitSuspended(userLaunch);
        check("old investigation cannot control same-name replacement",
                rejected("CONTROL_DEBUG", userLaunch, 0, "PAUSE", stale));
        check("old investigation cannot finalize against replacement", rejected("FINISH_INVESTIGATION", stale));
        verdict();
    }

    /** A CONTROLLED investigation keeps its stopped program and its breakpoint when it finishes (by design). */
    @Test
    @Order(7)
    @DisplayName("A CONTROLLED investigation keeps its stopped program and breakpoint")
    void controlledInvestigationKeepsItsProgram() throws Exception {
        start();
        int stepLine = javaLine("StepFilterApplication", "values.add(10);");
        String controlled = begin("CONTROLLED");
        String launch = null;
        try {
            bridge.call("SET_BREAKPOINT", false, STEP_APP, stepLine, controlled);
            launch = startProgram(JAVA_PROJECT, STEP_APP, controlled);
            awaitStop(launch, STEP_APP, "main", stepLine, Duration.ofSeconds(25), null, null);
            Map<String, Object> finished = bridge.call("FINISH_INVESTIGATION", false, controlled);
            check("CONTROLLED retains suspended launch", "KEEP_SUSPENDED".equals(finished.get("action"))
                    && Boolean.TRUE.equals(bridge.call("GET_DEBUG_STATE", false, launch).get("suspended")));
            check("CONTROLLED retains its marker", breakpointAt(STEP_APP, stepLine));
        } finally {
            // What CONTROLLED deliberately kept: remove it, so the next test starts clean.
            bridge.call("REMOVE_BREAKPOINT", true, STEP_APP, stepLine);
            if (launch != null) bridge.call("TEST_TERMINATE_LAUNCH", true, launch, "all");
            boolean clean = !breakpointAt(STEP_APP, stepLine) && (launch == null || waitEnded(launch));
            put("cleanup", clean);
        }
        verdict();
    }

    /** OPEN-02: after all of the above, the user's breakpoints and launch configuration have every original setting. */
    @Test
    @Order(8)
    @DisplayName("The user's breakpoints and launch configuration keep every setting")
    void userSettingsAreUnchanged() throws Exception {
        start();
        check("user markers retain settings", userBreakpointsUnchanged());
        Map<String, Object> configuration = obj(userState().get("configuration"));
        put("configuration", configuration);
        check("user launch configuration still exists", Boolean.TRUE.equals(configuration.get("exists")));
        check("user launch configuration keeps its main class",
                str(configuration.get("expectedMainType")).equals(str(configuration.get("mainType"))));
        check("user launch configuration keeps its program arguments",
                "--user-argument kept".equals(configuration.get("programArguments")));
        check("user launch configuration keeps its VM arguments",
                "-Duser.setting=kept".equals(configuration.get("vmArguments")));
        check("user launch configuration keeps its environment",
                "{USER_SETTING=kept}".equals(configuration.get("environment")));
        check("user program still running", alive(userLaunch));
        verdict();
    }

    // ------------------------------------------------------------------ helpers

    private void start() {
        checks = new LinkedHashMap<>();
        put("checks", checks);
    }

    private void check(String name, boolean passed) { checks.put(name, passed); }

    /** Fails the test with the names of all failed checks (every check of the test still ran). */
    private void verdict() {
        List<String> failed = new ArrayList<>();
        checks.forEach((name, passed) -> { if (!passed) failed.add(name); });
        require(failed.isEmpty(), "Failed checks: " + failed);
    }

    private boolean rejected(String command, Object... args) throws IOException {
        return "error".equals(bridge.call(command, true, args).get("status"));
    }

    private Map<String, Object> userState() throws IOException {
        return bridge.call("TEST_USER_STATE", false);
    }

    private boolean userBreakpointsUnchanged() throws IOException {
        Map<String, Object> state = userState();
        Map<String, Object> line = obj(state.get("lineBreakpoint")), exception = obj(state.get("exceptionBreakpoint"));
        put("userBreakpoints", Map.of("line", line, "exception", exception));
        return Boolean.TRUE.equals(line.get("exists")) && Boolean.TRUE.equals(line.get("enabled"))
                && Boolean.FALSE.equals(line.get("conditionEnabled")) && num(line.get("lineNumber")) == userLine
                && Boolean.TRUE.equals(exception.get("exists")) && Boolean.TRUE.equals(exception.get("caught"))
                && Boolean.FALSE.equals(exception.get("uncaught"));
    }

    private int configurationCount() throws IOException {
        Map<String, Object> reply = bridge.call("LIST_RUN_CONFIGURATIONS", false);
        for (Object value : reply.values()) if (value instanceof List<?> list) return list.size();
        throw new AssertionError("LIST_RUN_CONFIGURATIONS has no list: " + reply);
    }

    /** True when any breakpoint (user or agent) is at {@code type:line}. */
    private boolean breakpointAt(String type, int line) throws IOException {
        for (Map<String, Object> bp : rows(bridge.call("LIST_BREAKPOINTS", false).get("breakpoints")))
            if (type.equals(bp.get("typeName")) && num(bp.get("lineNumber")) == line) return true;
        return false;
    }

    /** Waits until the launch of that name (exactly one running) is suspended. */
    private void awaitSuspended(String launch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Map<String, Object> state = Map.of();
        while (System.nanoTime() < deadline) {
            state = bridge.call("GET_DEBUG_STATE", true, launch);
            if (Boolean.TRUE.equals(state.get("suspended"))) return;
            Thread.sleep(200);
        }
        throw new AssertionError("The user's program " + launch + " did not stop at its breakpoint: " + state);
    }

    /**
     * The value of {@code count} in the user's program, read where it is stopped (waits up to 30 s for the stop).
     * {@code LongRunningApplication} stops at the user's breakpoint on its loop line, so every resume adds one.
     */
    private String userLoopCount() throws Exception {
        awaitSuspended(userLaunch);
        for (Map<String, Object> thread : rows(bridge.call("GET_THREADS", false, userLaunch).get("threads")))
            if ("main".equals(thread.get("name")) && Boolean.TRUE.equals(thread.get("suspended")))
                return local(userLaunch, num(thread.get("index")), "count");
        throw new AssertionError("The main thread of the user's program is not stopped");
    }

    /** Waits until two debug sessions of that name run (the lookup by name then becomes ambiguous). */
    private void awaitTwoLaunches(String launch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            int running = 0;
            for (Map<String, Object> s : rows(sessions()))
                if (launch.equals(s.get("launchName")) && !Boolean.TRUE.equals(s.get("terminated"))) running++;
            if (running >= 2) return;
            Thread.sleep(200);
        }
        throw new AssertionError("The second run of " + launch + " did not start");
    }

    private boolean waitEnded(String launch) throws Exception {
        for (int i = 0; i < 50; i++) {
            if (!alive(launch)) return true;
            Thread.sleep(200);
        }
        return false;
    }
}
