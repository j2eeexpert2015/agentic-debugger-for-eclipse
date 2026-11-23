package org.learningfromexperience.eclipse.agentdebug.debug;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CORE-17 unit checks LE-U1..LE-U7 for the investigation idle lease. Plain Java, controllable
 * clock, no Eclipse. Run through {@code java dev-e2e/BridgeUnitTests.java}.
 *
 * @author Ayan Dutta
 */
public final class InvestigationLeaseTest {

    private static final AtomicLong NOW = new AtomicLong(1_000_000_000L);
    private static int passed;

    public static void main(String[] args) {
        check("LE-U1 a new investigation is not abandoned before its lease ends", () -> {
            var policy = policy();
            var inv = policy.begin("NORMAL", 10_000);
            advance(9_000);
            return !inv.abandoned() && policy.abandoned().isEmpty();
        });
        check("LE-U2 idle longer than the lease is abandoned", () -> {
            var policy = policy();
            var inv = policy.begin("NORMAL", 10_000);
            advance(10_001);
            return inv.abandoned() && policy.abandoned().contains(inv);
        });
        check("LE-U3 a running command (e.g. a 60 s wait) is never abandoned; idle counts from its end", () -> {
            var policy = policy();
            var inv = policy.begin("NORMAL", 10_000);
            inv.enter();
            advance(60_000);
            boolean keptWhileRunning = !inv.abandoned();
            inv.exit();
            advance(5_000);
            boolean keptAfter = !inv.abandoned();
            advance(6_000);
            return keptWhileRunning && keptAfter && inv.abandoned();
        });
        check("LE-U4 commands naming the investigation ID or its launch are recognised; others are not", () -> {
            var policy = policy();
            var inv = policy.begin("NORMAL", 10_000);
            inv.recordLaunch("Agentic Debugger - p - Main", new Object(), true);
            var other = policy.begin("NORMAL", 10_000);
            boolean byId = policy.namedBy(new String[]{"SET_BREAKPOINT", enc("a.B"), "12", enc(inv.id())}).contains(inv);
            boolean byLaunch = policy.namedBy(new String[]{"WAIT_FOR_STOP", enc("Agentic Debugger - p - Main") + "=", "5000"})
                    .equals(java.util.List.of(inv));
            boolean notOthers = !policy.namedBy(new String[]{"SET_BREAKPOINT", enc(inv.id())}).contains(other)
                    && policy.namedBy(new String[]{"LIST_BREAKPOINTS"}).isEmpty();
            return byId && byLaunch && notOthers;
        });
        check("LE-U5 an expired investigation answers with its reason; an unknown ID stays unknown", () -> {
            var policy = policy();
            var inv = policy.begin("NORMAL", 10_000);
            policy.expire(inv.id(), "Investigation expired after 10 s without calls");
            boolean reason = message(() -> policy.require(inv.id())).equals("IllegalStateException: Investigation expired after 10 s without calls");
            boolean unknown = message(() -> policy.require("nope")).startsWith("IllegalArgumentException: Unknown investigationId");
            return reason && unknown && policy.abandoned().isEmpty();
        });
        check("LE-U6 any call that uses the investigation resets its idle time", () -> {
            var policy = policy();
            var inv = policy.begin("NORMAL", 10_000);
            advance(9_000);
            policy.require(inv.id());
            advance(9_000);
            return !inv.abandoned();
        });
        check("LE-U7 leases shorter than 5 s are rejected; 5 s is accepted", () -> {
            var policy = policy();
            boolean rejected = message(() -> policy.begin("NORMAL", 4_999)).startsWith("IllegalArgumentException");
            return rejected && policy.begin("NORMAL", 5_000).leaseMillis() == 5_000;
        });
        System.out.println("PASS: " + passed + " investigation lease checks");
    }

    private static DebugInvestigationPolicy policy() { return new DebugInvestigationPolicy(NOW::get); }
    private static void advance(long millis) { NOW.addAndGet(millis * 1_000_000L); }
    private static String enc(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private static String message(Runnable action) {
        try {
            action.run();
            return "no exception";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void check(String name, java.util.function.BooleanSupplier test) {
        if (!test.getAsBoolean()) throw new AssertionError("FAIL " + name);
        passed++;
        System.out.println("PASS " + name);
    }
}
