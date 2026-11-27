package org.learningfromexperience.eclipse.agentdebug.debug;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks debugging mode and ownership of agent investigations, and is the reference for how agent
 * resources are cleaned up.
 *
 * <h2>What an investigation owns</h2>
 * The breakpoints, tracepoints and exception breakpoints it created, and the JVM it launched. Each
 * such breakpoint marker is labelled {@code owner=agent} plus the investigation ID
 * ({@link EclipseBreakpointService#OWNER_ATTRIBUTE}, {@link EclipseBreakpointService#INVESTIGATION_ATTRIBUTE}).
 * User breakpoints and sessions the agent only borrowed are never owned and never cleaned up.
 *
 * <h2>The four cleanup paths (CORE-08, CORE-17)</h2>
 * Investigations live in this registry, in memory; breakpoint markers live in the workspace and
 * survive restarts. Each path covers a case the previous ones cannot see:
 * <table>
 * <caption>Cleanup paths</caption>
 * <tr><th>Situation</th><th>Who cleans up</th><th>Where</th></tr>
 * <tr><td>1. The agent finishes normally</td>
 *     <td>{@code finish_debug_investigation}</td>
 *     <td>{@code EclipseAgentOperations.finishInvestigation} / {@code cleanUp}</td></tr>
 * <tr><td>2. The agent exits or crashes and its MCP connection closes; Eclipse keeps running</td>
 *     <td>The MCP server finishes the investigations its agent left open, within seconds</td>
 *     <td>{@code DebugMcpServer.finishOpenInvestigations}, {@code InvestigationTracker}</td></tr>
 * <tr><td>3. The agent hangs, or is killed so hard that the MCP server cannot react; Eclipse keeps running</td>
 *     <td>This registry's idle lease (default 10 min without a command naming the investigation, none running)</td>
 *     <td>{@link #abandoned()}, {@code EclipseAgentOperations.expireAbandoned}</td></tr>
 * <tr><td>4. Eclipse itself closes or crashes while an investigation is open</td>
 *     <td>5 s after the next start: agent markers whose investigation no longer exists are removed
 *         (the registry starts empty, so they are leftovers). The JVM ended with Eclipse.</td>
 *     <td>{@code EclipseBreakpointService.removeOrphanedAgentBreakpoints},
 *         {@code EclipseAgentOperations.removeOrphanedBreakpoints}</td></tr>
 * </table>
 * NORMAL investigations get the full cleanup in paths 1-3 (own breakpoints first, then own JVM,
 * verified ended). CONTROLLED investigations keep their paused session and breakpoints in paths 1-3
 * because the user asked to inspect them; path 4 still removes their markers, since after a restart
 * there is no session left to inspect. Path 4 was found on 23 Nov 2026: an interrupted Claude run
 * left an {@code IllegalArgumentException} exception breakpoint that paused every Spring Boot start
 * after the next restart. Design record: docs/decisions.md, ADR-008.
 *
 * <p>A breakpoint or launch the agent sends without an investigation ID would belong to nobody and
 * escape all four paths; the MCP server therefore attaches it to the session's only open
 * investigation, or rejects it when several are open ({@code InvestigationTracker#resolve}).
 *
 * @author Ayan Dutta
 */
public final class DebugInvestigationPolicy {
    public enum Mode { NORMAL, CONTROLLED }

    /** A breakpoint belongs to a specific investigation, not to every agent session. */
    public record Breakpoint(String typeName, int lineNumber) {}

    public static final class Investigation {
        private final String id;
        private final Mode mode;
        private final Set<Breakpoint> ownedBreakpoints = new LinkedHashSet<>();
        private final Set<String> ownedExceptions = new LinkedHashSet<>();
        /** OPEN-25: test runs without the debugger; owned (ended at cleanup) but not this investigation's session. */
        private final java.util.List<Object> ownedRuns = new java.util.ArrayList<>();
        private Object associatedLaunch;
        private boolean ownsLaunch;
        private String launchName;
        private boolean complete;
        private final long leaseMillis;
        private final java.util.function.LongSupplier clock;
        private long lastActivityNanos;
        private int inFlight;

        private Investigation(String id, Mode mode, long leaseMillis, java.util.function.LongSupplier clock) {
            this.id = id;
            this.mode = mode;
            this.leaseMillis = leaseMillis;
            this.clock = clock;
            this.lastActivityNanos = clock.getAsLong();
        }
        public long leaseMillis() { return leaseMillis; }
        /** A command naming this investigation (or its launch) starts. */
        public synchronized void enter() { inFlight++; lastActivityNanos = clock.getAsLong(); }
        /** That command ended; the idle time is counted from here. */
        public synchronized void exit() { inFlight = Math.max(0, inFlight - 1); lastActivityNanos = clock.getAsLong(); }
        public synchronized void touch() { lastActivityNanos = clock.getAsLong(); }
        public synchronized long idleMillis() { return (clock.getAsLong() - lastActivityNanos) / 1_000_000L; }
        /** Abandoned: idle longer than the lease and no command of it still running. */
        public synchronized boolean abandoned() { return !complete && inFlight == 0 && idleMillis() > leaseMillis; }
        public String id() { return id; }
        public Mode mode() { return mode; }
        public synchronized String launchName() { return launchName; }
        public synchronized Object ownedLaunch() { return ownsLaunch ? associatedLaunch : null; }
        public synchronized void verifySession(Object launch) {
            ensureActive();
            if (associatedLaunch == null || launch != associatedLaunch)
                throw new IllegalStateException("Session is not associated with this investigation");
        }
        public synchronized void verifyNewLaunch(String name) {
            verifyLaunchTarget(name);
            if (associatedLaunch != null)
                throw new IllegalStateException("Investigation is already bound to a debug session");
        }
        public synchronized Set<Breakpoint> breakpoints() { return Set.copyOf(ownedBreakpoints); }
        public synchronized Set<String> exceptions() { return Set.copyOf(ownedExceptions); }
        public synchronized void recordException(String name) { ensureActive(); ownedExceptions.add(name); }
        /**
         * OPEN-25: a JUnit run without the debugger, started to read its result. It does not bind the investigation
         * to a session, so the debug launch that follows in the same investigation is still allowed; cleanup ends it.
         */
        public synchronized void recordRun(Object launch) { ensureActive(); ownedRuns.add(launch); }
        public synchronized java.util.List<Object> runs() { return java.util.List.copyOf(ownedRuns); }
        /**
         * OPEN-25: this investigation's own debug launch has ended (a test run that terminated without stopping, for
         * example after moving a breakpoint). It no longer binds the investigation, so the next debug launch in the
         * same investigation is allowed; the ended launch stays owned and is still checked at cleanup. Before this,
         * the agent had to finish and begin a new investigation for every new attempt (trials 26 Nov 03:00-03:44).
         *
         * @param ended whether the bound launch is terminated (decided by the caller, which knows Eclipse launches)
         * @return true when the ended launch was released
         */
        public synchronized boolean releaseEndedLaunch(boolean ended) {
            ensureActive();
            if (!ended || !ownsLaunch || associatedLaunch == null) return false;
            ownedRuns.add(associatedLaunch);
            associatedLaunch = null;
            launchName = null;
            ownsLaunch = false;
            return true;
        }
        public synchronized boolean complete() { return complete; }

        public synchronized void recordBreakpoint(String type, int line) {
            ensureActive();
            ownedBreakpoints.add(new Breakpoint(type, line));
        }
        public synchronized void verifyLaunchTarget(String name) {
            ensureActive();
            if (launchName != null && !launchName.equals(name)) {
                throw new IllegalStateException("Investigation already targets another debug session");
            }
        }
        public synchronized void replaceOwnedLaunch(String name, Object previous, Object replacement) {
            ensureActive();
            if (!name.equals(launchName) || ownedLaunch() != previous) {
                throw new IllegalStateException("Cannot restart a session this investigation does not own");
            }
            associatedLaunch = replacement;
        }
        public synchronized void recordLaunch(String name, Object launch, boolean createdByAgent) {
            verifyLaunchTarget(name);
            if (associatedLaunch != null && associatedLaunch != launch) {
                throw new IllegalStateException("Investigation already targets another debug session");
            }
            launchName = name;
            associatedLaunch = launch;
            ownsLaunch |= createdByAgent;
        }
        public synchronized void ensureActive() {
            if (complete) throw new IllegalStateException("Investigation already completed: " + id);
        }
        public synchronized void markComplete() {
            ensureActive();
            complete = true;
        }
    }

    /** Default idle lease; override with environment variable AGENTDEBUG_INVESTIGATION_LEASE_MINUTES. */
    public static final long DEFAULT_LEASE_MILLIS = leaseFromEnvironment();
    public static final long MIN_LEASE_MILLIS = 5_000;
    public static final long MAX_LEASE_MILLIS = 24L * 60 * 60 * 1000;

    private final Map<String, Investigation> investigations = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;
    /** Recently expired investigations and why, so a late call gets a clear answer (bounded). */
    private final Map<String, String> expired = new LinkedHashMap<>();

    public DebugInvestigationPolicy() {
        this(System::nanoTime);
    }

    /** For tests: a controllable nanosecond clock. */
    DebugInvestigationPolicy(java.util.function.LongSupplier nanoClock) {
        this.clock = nanoClock;
    }

    public Investigation begin(String mode) {
        return begin(mode, DEFAULT_LEASE_MILLIS);
    }

    public Investigation begin(String mode, long leaseMillis) {
        Mode selected = mode == null || mode.isBlank() ? Mode.NORMAL : Mode.valueOf(mode);
        if (leaseMillis < MIN_LEASE_MILLIS || leaseMillis > MAX_LEASE_MILLIS)
            throw new IllegalArgumentException("Lease must be " + MIN_LEASE_MILLIS / 1000 + "-" + MAX_LEASE_MILLIS / 1000 + " seconds");
        Investigation investigation = new Investigation(UUID.randomUUID().toString(), selected, leaseMillis, clock);
        investigations.put(investigation.id(), investigation);
        return investigation;
    }

    /**
     * OPEN-23: "Unknown investigationId" explained, so the agent can recover and the user sees a refusal, not a fault
     * (Copilot run 26 Nov: the model mistyped its own ID, "g5ff"). Starts as before (the MCP server and tests match the
     * prefix). Names no other investigation.
     */
    static String unknownMessage(String id) {
        boolean wellFormed = id != null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        return "Unknown investigationId: " + id + ". Refused; nothing was changed."
                + (wellFormed ? "" : " This is not a valid investigation ID (check for a copying mistake).")
                + " Use exactly the investigationId that begin_debug_investigation returned.";
    }

    public Investigation require(String id) {
        Investigation result = investigations.get(id);
        if (result == null) {
            String reason;
            synchronized (expired) { reason = expired.get(id); }
            if (reason != null) throw new IllegalStateException(reason);
            throw new IllegalArgumentException(unknownMessage(id));
        }
        result.ensureActive();
        result.touch();
        return result;
    }

    /** Active investigations named by a command: its ID or launch name appears among the raw arguments. */
    public List<Investigation> namedBy(String[] rawParts) {
        List<Investigation> result = new ArrayList<>();
        if (rawParts.length < 2 || investigations.isEmpty()) return result;
        for (Investigation investigation : investigations.values()) {
            String id = encoded(investigation.id());
            String launch = investigation.launchName() == null ? null : encoded(investigation.launchName());
            for (int i = 1; i < rawParts.length; i++) {
                String part = rawParts[i].replace("=", "");
                if (part.equals(id) || part.equals(launch)) { result.add(investigation); break; }
            }
        }
        return result;
    }

    /**
     * OPEN-40: the open investigation that started this launch (owns it), or null. A control request without an
     * investigationId is allowed only on such a launch; a session the agent did not start (the user's own program, or
     * one an investigation only borrowed) is controlled only inside an investigation that names it.
     */
    public Investigation ownerOf(Object launch) {
        if (launch == null) return null;
        for (Investigation investigation : investigations.values())
            if (!investigation.complete() && investigation.ownedLaunch() == launch) return investigation;
        return null;
    }

    /** True while the investigation is open (not finished, not expired). */
    public boolean isActive(String id) {
        Investigation result = investigations.get(id);
        return result != null && !result.complete();
    }

    /** OPEN-62: the open investigations (not finished, not expired). */
    public List<Investigation> active() {
        List<Investigation> result = new ArrayList<>();
        for (Investigation investigation : investigations.values()) if (!investigation.complete()) result.add(investigation);
        return result;
    }

    public List<Investigation> abandoned() {
        List<Investigation> result = new ArrayList<>();
        for (Investigation investigation : investigations.values()) if (investigation.abandoned()) result.add(investigation);
        return result;
    }

    /** Completes an abandoned investigation; later calls with its ID get {@code reason}. */
    public void expire(String id, String reason) {
        Investigation result = investigations.remove(id);
        if (result != null && !result.complete()) result.markComplete();
        synchronized (expired) {
            expired.put(id, reason);
            while (expired.size() > 200) expired.remove(expired.keySet().iterator().next());
        }
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long leaseFromEnvironment() {
        try {
            String minutes = System.getenv("AGENTDEBUG_INVESTIGATION_LEASE_MINUTES");
            if (minutes != null && !minutes.isBlank()) {
                long millis = Long.parseLong(minutes.trim()) * 60_000L;
                if (millis >= 60_000L && millis <= 24L * 60 * 60 * 1000) return millis;
            }
        } catch (RuntimeException ignored) {
            // fall back to the default
        }
        return 10 * 60_000L;
    }

    public void finish(String id) {
        Investigation result = require(id);
        result.markComplete();
        investigations.remove(id, result);
    }

    /**
     * OPEN-39: the sentence the NORMAL finish reply adds when its cleanup is verified (no own breakpoint left, own JVM
     * ended), so the agent does not check it again with list_breakpoints or list_debug_sessions; null when something
     * was left, then the counts and the warning in the reply say what.
     */
    public static String cleanupNote(int ownBreakpointsLeft, boolean ended, boolean ownLaunch) {
        if (ownBreakpointsLeft != 0 || !ended) return null;
        return "Verified: no own breakpoints left" + (ownLaunch ? ", own JVM ended" : "")
                + ", user sessions and breakpoints untouched. No list_breakpoints or list_debug_sessions call "
                + "is needed to confirm it.";
    }
}
