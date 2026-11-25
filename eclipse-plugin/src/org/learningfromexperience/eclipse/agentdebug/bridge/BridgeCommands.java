package org.learningfromexperience.eclipse.agentdebug.bridge;

import java.util.Set;

/**
 * Classifies bridge commands into execution lanes and deadline classes.
 *
 * <p>Read-only commands may run in parallel. Every other command changes debugger,
 * workspace or investigation state and runs one at a time, in arrival order.
 * <b>Unknown commands are always treated as state-changing</b>, so a newly added
 * command can never accidentally run in parallel before someone classifies it.
 *
 * <p>Plain Java on purpose: no Eclipse imports, so it can be unit-tested without Eclipse.
 *
 * @author Ayan Dutta
 */
public final class BridgeCommands {

    /** Execution lane. */
    public enum Lane { READ, MUTATE }

    /** Commands that only read state. Keep this list conservative. */
    static final Set<String> READ_ONLY = Set.of(
            "GET_E2E_INFO",
            "LIST_BREAKPOINTS",
            "LIST_DEBUG_SESSIONS",
            "LIST_EXCEPTION_BREAKPOINTS",
            "LIST_JUNIT_TESTS",
            "LIST_RUN_CONFIGURATIONS",
            "LIST_JAVA_APPLICATIONS",
            "GET_DEBUG_STATE",
            "GET_UI_STATUS",
            "GET_THREADS",
            "GET_STACK",
            "GET_VARIABLES",
            "EXPAND_VARIABLE",
            "GET_PROCESS_OUTPUT",
            "VERIFY_OWNED_FIXTURE_LAUNCH",
            // Test Eclipse only (OPEN-02/03): reports the test's user breakpoints and launch configuration.
            "TEST_USER_STATE",
            // Waits up to 60 s for a debugger stop but changes nothing (see WAITING below).
            "WAIT_FOR_STOP",
            // Bridge-level commands answered by the dispatcher itself.
            "GET_OPERATION",
            "GET_BRIDGE_DIAGNOSTICS");

    /**
     * Commands deliberately classified as state-changing. Listed explicitly (even though
     * unknown commands default to MUTATE) so the unit test can detect any command in
     * {@code EclipseAgentOperations.execute()} that nobody has classified yet.
     */
    static final Set<String> STATE_CHANGING = Set.of(
            "BEGIN_INVESTIGATION",
            "FINISH_INVESTIGATION",
            "SET_BREAKPOINT",
            "SET_CONDITIONAL_BREAKPOINT",
            "SET_TRACEPOINT",
            "SET_EXCEPTION_BREAKPOINT",
            "REMOVE_EXCEPTION_BREAKPOINT",
            "REMOVE_BREAKPOINT",
            "START_JUNIT_TEST",
            "START_JAVA_APPLICATION",
            "START_DEBUG",
            "RESTART_DEBUG",
            "SET_STEP_FILTERS",
            "SHOW_DEBUG_LOCATION",
            // Expressions can run arbitrary code in the debugged JVM.
            "EVALUATE_EXPRESSION",
            "CONTROL_DEBUG",
            "BRIDGE_TEST_SLEEP",
            // Test Eclipse only (OPEN-02/03): create, change and remove the test's "user" breakpoints and launches.
            "TEST_USER_SEED",
            "TEST_USER_REPLACE_BREAKPOINT",
            "TEST_USER_LAUNCH_AGAIN",
            "TEST_TERMINATE_LAUNCH",
            "TEST_USER_CLEANUP");

    /** Commands that launch, restart or terminate JVMs and may legitimately take long. */
    static final Set<String> LONG_RUNNING = Set.of(
            "START_JUNIT_TEST",
            "START_JAVA_APPLICATION",
            "START_DEBUG",
            "RESTART_DEBUG",
            "FINISH_INVESTIGATION");

    /**
     * Read-only commands that deliberately wait (up to 60 s). They run in the READ lane but get a
     * longer deadline. The MCP server's socket timeout for them must stay longer than this.
     */
    static final Set<String> WAITING = Set.of("WAIT_FOR_STOP");

    static final long READ_DEADLINE_MS = 15_000;
    static final long WAIT_DEADLINE_MS = 70_000;
    /** Below the 30 s socket timeout of older MCP servers, so they still receive a reply. */
    static final long MUTATE_DEADLINE_MS = 25_000;
    static final long LONG_DEADLINE_MS = 120_000;

    private BridgeCommands() { }

    /**
     * Returns the command name: the first space-separated token, restricted to a safe
     * character set. Arguments are never returned, so they can never reach a log.
     */
    public static String name(String command) {
        if (command == null || command.isEmpty()) return "INVALID";
        int space = command.indexOf(' ');
        String name = space < 0 ? command : command.substring(0, space);
        return name.matches("[A-Z0-9_]{1,48}") ? name : "INVALID";
    }

    public static Lane lane(String name) {
        return READ_ONLY.contains(name) ? Lane.READ : Lane.MUTATE;
    }

    public static long deadlineMillis(String name) {
        if (WAITING.contains(name)) return WAIT_DEADLINE_MS;
        if (READ_ONLY.contains(name)) return READ_DEADLINE_MS;
        return LONG_RUNNING.contains(name) ? LONG_DEADLINE_MS : MUTATE_DEADLINE_MS;
    }

    /** True if the command was explicitly classified (used by the classification test). */
    static boolean isClassified(String name) {
        return READ_ONLY.contains(name) || STATE_CHANGING.contains(name);
    }
}
