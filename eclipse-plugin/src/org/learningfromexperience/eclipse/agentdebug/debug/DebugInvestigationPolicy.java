package org.learningfromexperience.eclipse.agentdebug.debug;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks debugging mode and ownership for explicit investigation completion.
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
        private Object ownedLaunch;
        private String launchName;
        private boolean complete;

        private Investigation(String id, Mode mode) {
            this.id = id;
            this.mode = mode;
        }
        public String id() { return id; }
        public Mode mode() { return mode; }
        public synchronized String launchName() { return launchName; }
        public synchronized Object ownedLaunch() { return ownedLaunch; }
        public synchronized Set<Breakpoint> breakpoints() { return Set.copyOf(ownedBreakpoints); }
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
        public synchronized void recordLaunch(String name, Object launch, boolean createdByAgent) {
            verifyLaunchTarget(name);
            if (ownedLaunch != null && ownedLaunch != launch) {
                throw new IllegalStateException("Investigation already owns another debug session");
            }
            launchName = name;
            if (createdByAgent) ownedLaunch = launch;
        }
        public synchronized void ensureActive() {
            if (complete) throw new IllegalStateException("Investigation already completed: " + id);
        }
        public synchronized void markComplete() {
            ensureActive();
            complete = true;
        }
    }

    private final Map<String, Investigation> investigations = new ConcurrentHashMap<>();

    public Investigation begin(String mode) {
        Mode selected = mode == null || mode.isBlank() ? Mode.NORMAL : Mode.valueOf(mode);
        Investigation investigation = new Investigation(UUID.randomUUID().toString(), selected);
        investigations.put(investigation.id(), investigation);
        return investigation;
    }

    public Investigation require(String id) {
        Investigation result = investigations.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown investigationId: " + id);
        result.ensureActive();
        return result;
    }

    public void finish(String id) {
        Investigation result = require(id);
        result.markComplete();
        investigations.remove(id, result);
    }
}
