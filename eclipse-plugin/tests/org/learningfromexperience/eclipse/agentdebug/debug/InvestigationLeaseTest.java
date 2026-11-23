package org.learningfromexperience.eclipse.agentdebug.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 unit tests for the investigation idle lease, CORE-17 (LE-U1 to LE-U7). A controllable
 * clock makes the lease testable instantly; no Eclipse needed. Run by {@code mvn clean package}.
 *
 * @author Ayan Dutta
 */
class InvestigationLeaseTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final DebugInvestigationPolicy policy = new DebugInvestigationPolicy(now::get);

    private void advance(long millis) { now.addAndGet(millis * 1_000_000L); }

    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test @DisplayName("LE-U1 a new investigation is not abandoned before its lease ends")
    void leU1() {
        var inv = policy.begin("NORMAL", 10_000);
        advance(9_000);
        assertFalse(inv.abandoned());
        assertTrue(policy.abandoned().isEmpty());
    }

    @Test @DisplayName("LE-U2 idle longer than the lease is abandoned")
    void leU2() {
        var inv = policy.begin("NORMAL", 10_000);
        advance(10_001);
        assertTrue(inv.abandoned());
        assertTrue(policy.abandoned().contains(inv));
    }

    @Test @DisplayName("LE-U3 a running command (e.g. a 60 s wait) is never abandoned; idle counts from its end")
    void leU3() {
        var inv = policy.begin("NORMAL", 10_000);
        inv.enter();
        advance(60_000);
        assertFalse(inv.abandoned(), "kept while the command runs");
        inv.exit();
        advance(5_000);
        assertFalse(inv.abandoned(), "idle time counts from the end of the command");
        advance(6_000);
        assertTrue(inv.abandoned());
    }

    @Test @DisplayName("LE-U4 commands naming the investigation ID or its launch are recognised; others are not")
    void leU4() {
        var inv = policy.begin("NORMAL", 10_000);
        inv.recordLaunch("Agentic Debugger - p - Main", new Object(), true);
        var other = policy.begin("NORMAL", 10_000);
        assertTrue(policy.namedBy(new String[]{"SET_BREAKPOINT", enc("a.B"), "12", enc(inv.id())}).contains(inv), "by ID");
        assertEquals(List.of(inv), policy.namedBy(new String[]{"WAIT_FOR_STOP", enc("Agentic Debugger - p - Main") + "=", "5000"}),
                "by launch name, also with Base64 padding");
        assertFalse(policy.namedBy(new String[]{"SET_BREAKPOINT", enc(inv.id())}).contains(other), "not another investigation");
        assertTrue(policy.namedBy(new String[]{"LIST_BREAKPOINTS"}).isEmpty(), "commands without arguments name none");
    }

    @Test @DisplayName("LE-U5 an expired investigation answers with its reason; an unknown ID stays unknown")
    void leU5() {
        var inv = policy.begin("NORMAL", 10_000);
        policy.expire(inv.id(), "Investigation expired after 10 s without calls");
        var expired = assertThrows(IllegalStateException.class, () -> policy.require(inv.id()));
        assertEquals("Investigation expired after 10 s without calls", expired.getMessage());
        var unknown = assertThrows(IllegalArgumentException.class, () -> policy.require("nope"));
        assertTrue(unknown.getMessage().startsWith("Unknown investigationId"));
        assertTrue(policy.abandoned().isEmpty());
    }

    @Test @DisplayName("LE-U6 any call that uses the investigation resets its idle time")
    void leU6() {
        var inv = policy.begin("NORMAL", 10_000);
        advance(9_000);
        policy.require(inv.id());
        advance(9_000);
        assertFalse(inv.abandoned());
    }

    @Test @DisplayName("LE-U7 leases shorter than 5 s are rejected; 5 s is accepted")
    void leU7() {
        assertThrows(IllegalArgumentException.class, () -> policy.begin("NORMAL", 4_999));
        assertEquals(5_000, policy.begin("NORMAL", 5_000).leaseMillis());
    }
}
