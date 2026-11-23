package org.learningfromexperience.eclipse.agentdebug.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Runs bridge commands with two lanes and per-command deadlines.
 *
 * <ul>
 * <li><b>READ lane</b>: read-only commands run in parallel (bounded), so a slow launch
 *     never blocks PING-like status checks.</li>
 * <li><b>MUTATE lane</b>: state-changing commands run one at a time, in arrival order
 *     (fair semaphore), exactly like the previous single-threaded bridge.</li>
 * <li>Every command has a deadline. When it passes, the caller receives an explicit
 *     {@code BRIDGE_TIMEOUT} reply with an operation ID instead of a silent socket timeout.
 *     The command keeps running (a JDT launch cannot be interrupted safely); its final
 *     result can be fetched with {@code GET_OPERATION <id>}.</li>
 * <li>If a lane stays occupied longer than the busy wait, the caller receives
 *     {@code BRIDGE_BUSY}, naming the running command, instead of waiting invisibly.</li>
 * <li>A watchdog logs a stack trace for slow commands and releases the lane of a command
 *     that appears stuck (for example, blocked behind a modal dialog).</li>
 * </ul>
 *
 * <p>Plain Java on purpose: no Eclipse imports, so it can be unit-tested without Eclipse.
 *
 * @author Ayan Dutta
 */
public final class BridgeDispatcher implements AutoCloseable {

    /** Tunable limits. {@link #defaults(boolean)} gives the production values. */
    public record Config(long busyWaitMillis, int readConcurrency, long slowMillis,
                         long abandonMillis, boolean testHooks,
                         ToLongFunction<String> deadlines) {
        public static Config defaults(boolean testHooks) {
            return new Config(
                    longEnv("AGENTDEBUG_BRIDGE_BUSY_WAIT_MS", 5_000),
                    8,
                    5_000,
                    longEnv("AGENTDEBUG_BRIDGE_ABANDON_MS", 300_000),
                    testHooks,
                    BridgeCommands::deadlineMillis);
        }
    }

    static final String TEST_SLEEP = "BRIDGE_TEST_SLEEP";
    static final long MAX_TEST_SLEEP_MS = 300_000;
    private static final int RECENT_LIMIT = 200;

    private final Function<String, String> delegate;
    private final BridgeLog log;
    private final Config config;
    private final Semaphore readPermits;
    private final Semaphore mutatePermit = new Semaphore(1, true);
    private final ExecutorService workers;
    private final ScheduledExecutorService watchdog;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Operation> inFlight = new ConcurrentHashMap<>();
    private final Map<Long, Operation> recent = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Operation> eldest) {
            return size() > RECENT_LIMIT;
        }
    };
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** One dispatched command. Fields are read by the watchdog and diagnostics. */
    static final class Operation {
        final long id;
        final String name;
        final BridgeCommands.Lane lane;
        final long createdNanos = System.nanoTime();
        final AtomicBoolean permitReleased = new AtomicBoolean();
        volatile long startNanos;
        volatile long endNanos;
        volatile Thread worker;
        volatile String state = "QUEUED";
        volatile String result;
        volatile boolean slowLogged;

        Operation(long id, String name, BridgeCommands.Lane lane) {
            this.id = id;
            this.name = name;
            this.lane = lane;
        }

        long ageMillis() {
            long end = endNanos == 0 ? System.nanoTime() : endNanos;
            return TimeUnit.NANOSECONDS.toMillis(end - createdNanos);
        }
    }

    public BridgeDispatcher(Function<String, String> delegate, BridgeLog log, Config config) {
        this.delegate = delegate;
        this.log = log;
        this.config = config;
        this.readPermits = new Semaphore(config.readConcurrency(), true);
        this.workers = Executors.newCachedThreadPool(daemon("AgentDebug-Bridge-Worker"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemon("AgentDebug-Bridge-Watchdog"));
        long period = Math.max(50, Math.min(1_000, config.slowMillis() / 2));
        watchdog.scheduleAtFixedRate(this::inspect, period, period, TimeUnit.MILLISECONDS);
    }

    /** Executes one command line (without the AUTH prefix) and always returns one JSON line. */
    public String dispatch(String command) {
        String name = BridgeCommands.name(command);
        if ("GET_OPERATION".equals(name)) return getOperation(command);
        if ("GET_BRIDGE_DIAGNOSTICS".equals(name)) return diagnostics();

        BridgeCommands.Lane lane = BridgeCommands.lane(name);
        long testSleep = -1;
        if (TEST_SLEEP.equals(name) && config.testHooks()) {
            String[] p = command.split(" ");
            if (p.length != 3) return error("BAD_REQUEST", "Usage: BRIDGE_TEST_SLEEP <ms> <READ|MUTATE>");
            try {
                testSleep = Long.parseLong(p[1]);
                lane = BridgeCommands.Lane.valueOf(p[2]);
            } catch (IllegalArgumentException invalid) {
                return error("BAD_REQUEST", "Usage: BRIDGE_TEST_SLEEP <ms> <READ|MUTATE>");
            }
            if (testSleep < 0 || testSleep > MAX_TEST_SLEEP_MS) return error("BAD_REQUEST", "Sleep out of range");
        }

        Operation op = new Operation(nextId.getAndIncrement(), name, lane);
        Semaphore permit = lane == BridgeCommands.Lane.READ ? readPermits : mutatePermit;
        try {
            if (!permit.tryAcquire(config.busyWaitMillis(), TimeUnit.MILLISECONDS)) {
                count("busy");
                log.command(op.id, name, lane, op.ageMillis(), 0, "BUSY");
                return busy(op);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("BRIDGE_INTERRUPTED", "Bridge interrupted while waiting for a free lane");
        }

        inFlight.put(op.id, op);
        long sleep = testSleep;
        Future<String> future;
        try {
            future = workers.submit(() -> run(op, permit, command, sleep));
        } catch (RuntimeException rejected) {
            release(op, permit);
            inFlight.remove(op.id);
            return error("BRIDGE_REJECTED", "Bridge is shutting down");
        }

        // The deadline covers lane waiting + execution, measured from arrival, so the reply always
        // reaches the client before its socket timeout (e.g. 5 s busy wait + 25 s deadline < 30 s).
        long deadline = config.deadlines().applyAsLong(name);
        long remaining = Math.max(1, deadline - op.ageMillis());
        try {
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            if (future.isDone()) return result(future);
            count("timeout");
            log.event("TIMEOUT op=" + op.id + " cmd=" + name + " deadlineMs=" + deadline
                    + " (command keeps running; result via GET_OPERATION " + op.id + ")");
            return "{\"status\":\"error\",\"code\":\"BRIDGE_TIMEOUT\",\"message\":"
                    + q("Eclipse did not finish " + name + " within " + deadline + " ms. It is still running; "
                        + "check its result with get_bridge_operation before retrying.")
                    + ",\"operationId\":" + op.id + ",\"command\":" + q(name)
                    + ",\"deadlineMs\":" + deadline + ",\"stillRunning\":true}";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("BRIDGE_INTERRUPTED", "Bridge interrupted while waiting for " + name);
        } catch (ExecutionException unexpected) {
            return error("BRIDGE_FAILURE", String.valueOf(unexpected.getCause()));
        }
    }

    private String run(Operation op, Semaphore permit, String command, long testSleep) {
        Thread current = Thread.currentThread();
        String originalName = current.getName();
        current.setName("AgentDebug-Bridge-Worker op=" + op.id + " " + op.name);
        op.worker = current;
        op.startNanos = System.nanoTime();
        op.state = "RUNNING";
        String response;
        String outcome;
        try {
            if (testSleep >= 0) {
                Thread.sleep(testSleep);
                response = "{\"status\":\"success\",\"slept\":" + testSleep + "}";
            } else {
                response = delegate.apply(command);
            }
            if (response == null) response = error("BRIDGE_FAILURE", "Command returned no response");
            outcome = response.contains("\"status\":\"error\"") ? "ERROR" : "OK";
        } catch (Throwable failure) {
            response = error("COMMAND_FAILED", failure.getClass().getName() + ": " + failure.getMessage());
            outcome = "EXCEPTION";
        } finally {
            op.endNanos = System.nanoTime();
            op.worker = null;
            current.setName(originalName);
            release(op, permit);
        }
        op.result = response;
        op.state = "ABANDONED".equals(op.state) ? "COMPLETED_AFTER_ABANDON" : "COMPLETED";
        inFlight.remove(op.id);
        synchronized (recent) { recent.put(op.id, op); }
        count("completed");
        long queue = TimeUnit.NANOSECONDS.toMillis(op.startNanos - op.createdNanos);
        long runtime = TimeUnit.NANOSECONDS.toMillis(op.endNanos - op.startNanos);
        log.command(op.id, op.name, op.lane, queue, runtime, outcome);
        return response;
    }

    /** Watchdog: log slow commands once with a stack trace; free the lane of stuck ones. */
    private void inspect() {
        try {
            for (Operation op : inFlight.values()) {
                if (!"RUNNING".equals(op.state)) continue;
                long age = op.ageMillis();
                if (!op.slowLogged && age >= config.slowMillis()) {
                    op.slowLogged = true;
                    count("slow");
                    log.event("SLOW op=" + op.id + " cmd=" + op.name + " lane=" + op.lane + " ageMs=" + age
                            + System.lineSeparator() + "  " + BridgeLog.stack(op.worker, 25));
                }
                if (age >= config.abandonMillis()) {
                    op.state = "ABANDONED";
                    count("abandoned");
                    // Log before releasing, so the log always shows why the lane became free.
                    log.event("ABANDONED op=" + op.id + " cmd=" + op.name + " ageMs=" + age
                            + " - lane released; the command thread is still running"
                            + System.lineSeparator() + "  " + BridgeLog.stack(op.worker, 25));
                    release(op, op.lane == BridgeCommands.Lane.READ ? readPermits : mutatePermit);
                }
            }
        } catch (RuntimeException ignored) {
            // The watchdog must never die.
        }
    }

    private static void release(Operation op, Semaphore permit) {
        if (op.permitReleased.compareAndSet(false, true)) permit.release();
    }

    private String busy(Operation rejected) {
        Operation holder = null;
        for (Operation op : inFlight.values()) {
            if (op.lane == rejected.lane && (holder == null || op.id < holder.id)) holder = op;
        }
        String detail = holder == null ? "another command"
                : holder.name + " (operation " + holder.id + ", running " + holder.ageMillis() + " ms)";
        return "{\"status\":\"error\",\"code\":\"BRIDGE_BUSY\",\"message\":"
                + q("Eclipse is still processing " + detail + ". Retry shortly or check get_bridge_diagnostics.")
                + ",\"command\":" + q(rejected.name)
                + (holder == null ? "" : ",\"runningOperationId\":" + holder.id
                        + ",\"runningCommand\":" + q(holder.name)) + "}";
    }

    private String getOperation(String command) {
        String[] p = command.split(" ");
        long id;
        try {
            id = Long.parseLong(p.length > 1 ? p[1] : "");
        } catch (NumberFormatException invalid) {
            return error("BAD_REQUEST", "Usage: GET_OPERATION <operationId>");
        }
        Operation op = inFlight.get(id);
        if (op == null) synchronized (recent) { op = recent.get(id); }
        if (op == null) return error("UNKNOWN_OPERATION", "Unknown or expired operation " + id);
        String result = op.result;
        return "{\"status\":\"success\",\"operation\":{\"operationId\":" + op.id
                + ",\"command\":" + q(op.name) + ",\"lane\":" + q(op.lane.name())
                + ",\"state\":" + q(op.state) + ",\"elapsedMs\":" + op.ageMillis()
                + ",\"result\":" + (result == null ? "null" : result) + "}}";
    }

    /** Read-only snapshot for troubleshooting: in-flight commands, their age and stacks. */
    public String diagnostics() {
        List<String> ops = new ArrayList<>();
        for (Operation op : inFlight.values()) {
            ops.add("{\"operationId\":" + op.id + ",\"command\":" + q(op.name)
                    + ",\"lane\":" + q(op.lane.name()) + ",\"state\":" + q(op.state)
                    + ",\"ageMs\":" + op.ageMillis() + ",\"stack\":" + q(BridgeLog.stack(op.worker, 25)) + "}");
        }
        StringBuilder c = new StringBuilder();
        for (var entry : new java.util.TreeMap<>(counters).entrySet()) {
            if (c.length() > 0) c.append(',');
            c.append(q(entry.getKey())).append(':').append(entry.getValue().get());
        }
        return "{\"status\":\"success\",\"bridge\":{\"mode\":\"concurrent\""
                + ",\"inFlight\":[" + String.join(",", ops) + "]"
                + ",\"mutateLaneFree\":" + (mutatePermit.availablePermits() > 0)
                + ",\"readPermitsAvailable\":" + readPermits.availablePermits()
                + ",\"counters\":{" + c + "}"
                + ",\"logFile\":" + q(log.file() == null ? null : log.file().toString()) + "}}";
    }

    private static String result(Future<String> done) {
        try {
            return done.get();
        } catch (InterruptedException | ExecutionException unexpected) {
            return error("BRIDGE_FAILURE", String.valueOf(unexpected));
        }
    }

    private void count(String key) {
        counters.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    static String error(String code, String message) {
        return "{\"status\":\"error\",\"code\":" + q(code) + ",\"message\":" + q(message) + "}";
    }

    /** JSON string literal; kept here so this class has no Eclipse-dependent imports. */
    static String q(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private static long longEnv(String name, long fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        workers.shutdownNow();
    }
}
