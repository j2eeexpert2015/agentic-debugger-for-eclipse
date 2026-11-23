package org.learningfromexperience.eclipse.agentdebug.bridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for the bridge dispatcher (BR-U1 to BR-U13). No Eclipse needed: run by
 * {@code mvn clean package} (maven-surefire-plugin, see eclipse-plugin/pom.xml). The display names
 * keep the BR-U IDs used in the docs and acceptance records.
 *
 * @author Ayan Dutta
 */
public final class BridgeDispatcherTest {

    /** Sources are read relative to the module directory, which is surefire's working directory. */
    private static final Path OPERATIONS_SOURCE =
            Path.of("src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java");
    private static final Path MCP_SOURCE =
            Path.of("../mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java");

    @Test @DisplayName("BR-U1 slow state change does not block reads")
    void brU1() throws Exception { slowMutateDoesNotBlockReads(); }

    @Test @DisplayName("BR-U2 deadline returns BRIDGE_TIMEOUT on time")
    void brU2() throws Exception { deadlineReturnsTimeout(); }

    @Test @DisplayName("BR-U3 GET_OPERATION returns the late result")
    void brU3() throws Exception { lateResultIsRetrievable(); }

    @Test @DisplayName("BR-U4 second state change gets BRIDGE_BUSY")
    void brU4() throws Exception { secondMutateIsBusy(); }

    @Test @DisplayName("BR-U5 state changes keep arrival order")
    void brU5() throws Exception { mutationsKeepOrder(); }

    @Test @DisplayName("BR-U6 exceptions become error replies")
    void brU6() throws Exception { exceptionBecomesError(); }

    @Test @DisplayName("BR-U7 log has names and timings, never arguments")
    void brU7() throws Exception { logNeverContainsArguments(); }

    @Test @DisplayName("BR-U8 every Eclipse command is classified; MCP long-running list matches")
    void brU8() throws Exception { classification(OPERATIONS_SOURCE, MCP_SOURCE); }

    @Test @DisplayName("BR-U9 50 parallel reads succeed and leave no leftovers")
    void brU9() throws Exception { parallelReads(); }

    @Test @DisplayName("BR-U10 slow command is logged with a stack trace")
    void brU10() throws Exception { slowCommandLogged(); }

    @Test @DisplayName("BR-U11 stuck command releases its lane")
    void brU11() throws Exception { stuckCommandReleasesLane(); }

    @Test @DisplayName("BR-U12 test hook only works when enabled")
    void brU12() throws Exception { testHookGated(); }

    @Test @DisplayName("BR-U13 deadline includes time spent waiting for the lane")
    void brU13() throws Exception { deadlineIncludesQueueTime(); }

    // ---------------------------------------------------------------- tests

    private static void slowMutateDoesNotBlockReads() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (BridgeDispatcher d = dispatcher(cmd -> {
            if (cmd.startsWith("START_DEBUG")) { started.countDown(); sleep(1_500); }
            return ok(cmd);
        }, null, 300, 60_000, false)) {
            ExecutorService pool = pool(1);
            Future<String> slow = pool.submit(() -> d.dispatch("START_DEBUG abc"));
            require(started.await(2, TimeUnit.SECONDS), "slow command started");
            long t0 = System.nanoTime();
            String read = d.dispatch("GET_THREADS abc");
            long ms = millisSince(t0);
            require(read.contains("\"status\":\"success\""), "read succeeded: " + read);
            require(ms < 300, "read answered in " + ms + " ms while a state change was running");
            require(slow.get(5, TimeUnit.SECONDS).contains("success"), "slow command still completed");
            pool.shutdownNow();
        }
    }

    private static void deadlineReturnsTimeout() throws Exception {
        try (BridgeDispatcher d = dispatcher(cmd -> { sleep(1_500); return ok(cmd); }, null, 300, 60_000, false)) {
            long t0 = System.nanoTime();
            String reply = d.dispatch("CONTROL_DEBUG x");
            long ms = millisSince(t0);
            require(reply.contains("\"code\":\"BRIDGE_TIMEOUT\""), "timeout reply: " + reply);
            require(reply.contains("\"operationId\":"), "timeout reply has operationId");
            require(ms >= 450 && ms < 1_000, "timeout after " + ms + " ms (deadline 500)");
        }
    }

    private static void lateResultIsRetrievable() throws Exception {
        try (BridgeDispatcher d = dispatcher(cmd -> { sleep(800); return "{\"status\":\"success\",\"payload\":\"late\"}"; },
                null, 300, 60_000, false)) {
            String reply = d.dispatch("CONTROL_DEBUG x");
            long id = operationId(reply);
            String running = d.dispatch("GET_OPERATION " + id);
            require(running.contains("\"state\":\"RUNNING\""), "still running: " + running);
            sleep(600);
            String done = d.dispatch("GET_OPERATION " + id);
            require(done.contains("\"state\":\"COMPLETED\""), "completed: " + done);
            require(done.contains("\"payload\":\"late\""), "late result returned: " + done);
            require(d.dispatch("GET_OPERATION 999999").contains("UNKNOWN_OPERATION"), "unknown id rejected");
            require(d.dispatch("GET_OPERATION abc").contains("BAD_REQUEST"), "bad id rejected");
        }
    }

    private static void secondMutateIsBusy() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (BridgeDispatcher d = dispatcher(cmd -> {
            if (cmd.startsWith("START_DEBUG")) { started.countDown(); sleep(1_500); }
            return ok(cmd);
        }, null, 300, 60_000, false)) {
            ExecutorService pool = pool(1);
            Future<String> first = pool.submit(() -> d.dispatch("START_DEBUG a"));
            require(started.await(2, TimeUnit.SECONDS), "first command started");
            long t0 = System.nanoTime();
            String second = d.dispatch("SET_BREAKPOINT b 5");
            long ms = millisSince(t0);
            require(second.contains("\"code\":\"BRIDGE_BUSY\""), "busy reply: " + second);
            require(second.contains("\"runningCommand\":\"START_DEBUG\""), "names running command: " + second);
            require(ms >= 250 && ms < 800, "busy after " + ms + " ms (busy wait 300)");
            first.get(5, TimeUnit.SECONDS);
            require(d.dispatch("SET_BREAKPOINT b 5").contains("success"), "lane free again afterwards");
            pool.shutdownNow();
        }
    }

    private static void mutationsKeepOrder() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        try (BridgeDispatcher d = dispatcher(cmd -> { order.add(cmd); sleep(60); return ok(cmd); },
                null, 5_000, 60_000, false)) {
            ExecutorService pool = pool(5);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                String command = "SET_BREAKPOINT T" + i;
                results.add(pool.submit(() -> d.dispatch(command)));
                sleep(20); // arrival order 1..5
            }
            for (Future<String> f : results) require(f.get(5, TimeUnit.SECONDS).contains("success"), "all succeeded");
            require(order.equals(List.of("SET_BREAKPOINT T1", "SET_BREAKPOINT T2", "SET_BREAKPOINT T3",
                    "SET_BREAKPOINT T4", "SET_BREAKPOINT T5")), "executed in arrival order: " + order);
            pool.shutdownNow();
        }
    }

    private static void exceptionBecomesError() throws Exception {
        try (BridgeDispatcher d = dispatcher(cmd -> { throw new IllegalStateException("boom"); },
                null, 300, 60_000, false)) {
            String reply = d.dispatch("SET_BREAKPOINT a 1");
            require(reply.contains("\"status\":\"error\"") && reply.contains("COMMAND_FAILED")
                    && reply.contains("boom"), "error reply: " + reply);
        }
        try (BridgeDispatcher d = dispatcher(cmd -> null, null, 300, 60_000, false)) {
            String nullReply = d.dispatch("GET_THREADS a");
            require(nullReply.contains("\"status\":\"error\""), "null response becomes error: " + nullReply);
        }
    }

    private static void logNeverContainsArguments() throws Exception {
        Path file = Files.createTempFile("bridge-test", ".log");
        try (BridgeDispatcher d = dispatcher(BridgeDispatcherTest::ok, new BridgeLog(file), 300, 60_000, false)) {
            d.dispatch("SET_BREAKPOINT ZZSECRETARGZZ 4242");
            d.dispatch("EVALUATE_EXPRESSION cGFzc3dvcmQ9aHVudGVyMg 1 0 dHJ1ZQ");
            String log = Files.readString(file, StandardCharsets.UTF_8);
            require(log.contains("cmd=SET_BREAKPOINT") && log.contains("runMs="), "timing line written: " + log);
            require(log.contains("cmd=EVALUATE_EXPRESSION"), "second command logged");
            require(!log.contains("ZZSECRETARGZZ") && !log.contains("4242")
                    && !log.contains("cGFzc3dvcmQ9aHVudGVyMg"), "arguments never logged: " + log);
            require(BridgeCommands.name("lower-case-name arg").equals("INVALID"), "unsafe names sanitised");
            require(BridgeCommands.name("GET_THREADS abc").equals("GET_THREADS"), "name is the first token only");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void classification(Path operationsSource, Path mcpSource) throws Exception {
        require(BridgeCommands.lane("SOMETHING_NEW") == BridgeCommands.Lane.MUTATE, "unknown commands are MUTATE");
        require(BridgeCommands.lane("GET_THREADS") == BridgeCommands.Lane.READ, "GET_THREADS is READ");
        require(BridgeCommands.lane("EVALUATE_EXPRESSION") == BridgeCommands.Lane.MUTATE, "evaluation is MUTATE");
        require(BridgeCommands.deadlineMillis("START_JAVA_APPLICATION") == BridgeCommands.LONG_DEADLINE_MS,
                "launch has long deadline");
        require(BridgeCommands.deadlineMillis("SET_BREAKPOINT") < 30_000, "ordinary change answers before old 30 s clients");
        require(BridgeCommands.isClassified("LIST_DEBUG_SESSIONS"), "server-side command classified");
        require(BridgeCommands.lane("WAIT_FOR_STOP") == BridgeCommands.Lane.READ, "WAIT_FOR_STOP changes nothing: READ lane");
        require(BridgeCommands.deadlineMillis("WAIT_FOR_STOP") == BridgeCommands.WAIT_DEADLINE_MS
                && BridgeCommands.WAIT_DEADLINE_MS > 60_000 && BridgeCommands.WAIT_DEADLINE_MS < 75_000,
                "WAIT_FOR_STOP deadline covers a 60 s wait and stays below the MCP 75 s socket timeout");
        require(Files.isRegularFile(operationsSource), "EclipseAgentOperations.java found at " + operationsSource.toAbsolutePath());
        // Only the command switch inside execute(); other switches (e.g. CONTROL_DEBUG actions) are not commands.
        String source = Files.readString(operationsSource);
        int start = source.indexOf("public String execute(String command)");
        int end = source.indexOf("default -> error(\"Unknown command\")", start);
        require(start >= 0 && end > start, "found execute() command switch in " + operationsSource);
        Matcher m = Pattern.compile("case \"([A-Z0-9_]+)\"\\s*->").matcher(source.substring(start, end));
        int seen = 0;
        while (m.find()) {
            seen++;
            require(BridgeCommands.isClassified(m.group(1)),
                    "Unclassified bridge command " + m.group(1) + ": add it to BridgeCommands READ_ONLY or STATE_CHANGING");
        }
        require(seen >= 20, "found the command switch (" + seen + " commands)");

        // The MCP server waits longer for long-running commands; its list must match the plugin's.
        require(Files.isRegularFile(mcpSource), "DebugMcpServer.java found at " + mcpSource.toAbsolutePath());
        {
            Matcher set = Pattern.compile("LONG_RUNNING_COMMANDS = java\\.util\\.Set\\.of\\(([^)]*)\\)", Pattern.DOTALL)
                    .matcher(Files.readString(mcpSource));
            require(set.find(), "LONG_RUNNING_COMMANDS found in DebugMcpServer.java");
            java.util.Set<String> mcpLong = new java.util.TreeSet<>();
            Matcher names = Pattern.compile("\"([A-Z0-9_]+)\"").matcher(set.group(1));
            while (names.find()) mcpLong.add(names.group(1));
            require(mcpLong.equals(new java.util.TreeSet<>(BridgeCommands.LONG_RUNNING)),
                    "MCP LONG_RUNNING_COMMANDS " + mcpLong + " must equal plugin LONG_RUNNING " + BridgeCommands.LONG_RUNNING);
        }
    }

    private static void parallelReads() throws Exception {
        Map<String, Boolean> threads = new ConcurrentHashMap<>();
        try (BridgeDispatcher d = dispatcher(cmd -> { threads.put(Thread.currentThread().getName(), true); sleep(20); return ok(cmd); },
                null, 5_000, 60_000, false)) {
            ExecutorService pool = pool(50);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 50; i++) results.add(pool.submit(() -> d.dispatch("GET_STACK x 0")));
            for (Future<String> f : results) require(f.get(10, TimeUnit.SECONDS).contains("success"), "read ok");
            pool.shutdownNow();
            String diag = d.dispatch("GET_BRIDGE_DIAGNOSTICS");
            require(diag.contains("\"inFlight\":[]"), "no operations left in flight: " + diag);
            require(diag.contains("\"readPermitsAvailable\":8"), "all read permits returned: " + diag);
            require(diag.contains("\"mutateLaneFree\":true"), "state-change lane untouched: " + diag);
        }
    }

    private static void slowCommandLogged() throws Exception {
        Path file = Files.createTempFile("bridge-slow", ".log");
        try (BridgeDispatcher d = dispatcher(cmd -> { sleep(700); return ok(cmd); }, new BridgeLog(file), 300, 60_000, false)) {
            d.dispatch("START_DEBUG x");
            String log = Files.readString(file, StandardCharsets.UTF_8);
            require(log.contains("SLOW op=") && log.contains("cmd=START_DEBUG"), "slow line written: " + log);
            require(log.contains("at java.") || log.contains("at org."), "stack trace included: " + log);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void stuckCommandReleasesLane() throws Exception {
        Path file = Files.createTempFile("bridge-stuck", ".log");
        CountDownLatch started = new CountDownLatch(1);
        try (BridgeDispatcher d = dispatcher(cmd -> {
            if (cmd.startsWith("START_DEBUG")) { started.countDown(); sleep(2_000); }
            return ok(cmd);
        }, new BridgeLog(file), 3_000, 400, false)) {
            ExecutorService pool = pool(1);
            Future<String> stuck = pool.submit(() -> d.dispatch("START_DEBUG x"));
            require(started.await(2, TimeUnit.SECONDS), "stuck command started");
            long t0 = System.nanoTime();
            String next = d.dispatch("SET_BREAKPOINT y 3");
            require(next.contains("success"), "lane released for next command: " + next);
            require(millisSince(t0) < 1_500, "released by watchdog, not by completion");
            require(Files.readString(file).contains("ABANDONED op="), "abandon logged");
            stuck.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void testHookGated() throws Exception {
        Function<String, String> unknown = cmd -> "{\"status\":\"error\",\"message\":\"Unknown command\"}";
        try (BridgeDispatcher off = dispatcher(unknown, null, 300, 60_000, false)) {
            String reply = off.dispatch("BRIDGE_TEST_SLEEP 10 READ");
            require(reply.contains("Unknown command"), "hook disabled behaves as unknown command: " + reply);
        }
        try (BridgeDispatcher on = dispatcher(unknown, null, 300, 60_000, true)) {
            require(on.dispatch("BRIDGE_TEST_SLEEP 10 READ").contains("\"slept\":10"), "hook sleeps when enabled");
            require(on.dispatch("BRIDGE_TEST_SLEEP 999999999 READ").contains("BAD_REQUEST"), "sleep bounded");
            require(on.dispatch("BRIDGE_TEST_SLEEP x").contains("BAD_REQUEST"), "usage enforced");
        }
    }

    /** A command that waits ~400 ms for the lane, then runs long, must still reply at its
     *  500 ms deadline measured from arrival, not 900 ms. */
    private static void deadlineIncludesQueueTime() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (BridgeDispatcher d = dispatcher(cmd -> {
            if (cmd.startsWith("SET_STEP_FILTERS")) { started.countDown(); sleep(400); }
            else sleep(2_000);
            return ok(cmd);
        }, null, 1_000, 60_000, false)) {
            ExecutorService pool = pool(1);
            Future<String> holder = pool.submit(() -> d.dispatch("SET_STEP_FILTERS a"));
            require(started.await(2, TimeUnit.SECONDS), "holder started");
            long t0 = System.nanoTime();
            String reply = d.dispatch("CONTROL_DEBUG b");
            long ms = millisSince(t0);
            require(reply.contains("BRIDGE_TIMEOUT"), "timed out: " + reply);
            require(ms >= 450 && ms < 750, "replied after " + ms + " ms; deadline must include queue time");
            holder.get(5, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Deadlines: reads 2 s, launches (START_*) 5 s, other state changes 500 ms. */
    private static BridgeDispatcher dispatcher(Function<String, String> delegate, BridgeLog log,
                                               long busyWait, long abandon, boolean hooks) {
        var config = new BridgeDispatcher.Config(busyWait, 8, 300, abandon, hooks, name ->
                BridgeCommands.lane(name) == BridgeCommands.Lane.READ ? 2_000
                        : name.startsWith("START_") ? 5_000 : 500);
        return new BridgeDispatcher(delegate, log == null ? new BridgeLog(null) : log, config);
    }

    /** Daemon pools, so a failed check can never keep the JVM alive. */
    private static ExecutorService pool(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "bridge-test-client");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static String ok(String cmd) {
        return "{\"status\":\"success\",\"echo\":" + BridgeDispatcher.q(cmd) + "}";
    }

    private static long operationId(String reply) {
        Matcher m = Pattern.compile("\"operationId\":(\\d+)").matcher(reply);
        require(m.find(), "operationId in " + reply);
        return Long.parseLong(m.group(1));
    }

    private static long millisSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
