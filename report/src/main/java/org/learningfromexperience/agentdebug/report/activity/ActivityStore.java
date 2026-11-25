package org.learningfromexperience.agentdebug.report.activity;

import org.learningfromexperience.agentdebug.report.format.Json;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The investigations as the Agent Activity view and the HTML report show them (OPEN-36), built from the investigation
 * events in the {@link org.learningfromexperience.agentdebug.report.ActivityEvents} format: in Eclipse from the bridge
 * command {@code ACTIVITY_EVENT <base64url(JSON)>} sent by the MCP server, in the MCP server's
 * {@link org.learningfromexperience.agentdebug.report.HtmlReportWriter} from the same events directly. Part of the
 * {@code report} module, which the Eclipse plugin embeds ({@code lib/report.jar}).
 *
 * <p><b>Never in the way of debugging.</b> {@link #accept} is called on the bridge's connection thread, outside the
 * READ and MUTATE lanes: it only parses one small event, updates memory and notifies listeners, which must hand any UI
 * work to the UI thread themselves. It never throws; a malformed event gets an error reply and changes nothing.
 *
 * <p><b>Bounded.</b> At most {@value #MAX_INVESTIGATIONS} investigations (the oldest finished ones are dropped first)
 * and {@value #MAX_STEPS} steps per investigation (the oldest steps are dropped, and counted). Kept when the view is
 * closed, so opening it later still shows the current investigation.
 *
 * <p>Plain Java, no Eclipse classes: unit-tested without a workbench.
 *
 * @author Ayan Dutta
 */
public final class ActivityStore {

    static final int MAX_INVESTIGATIONS = 20;
    static final int MAX_STEPS = 1_000;
    static final int MAX_EVENT_CHARS = 100_000;

    /** Title of the completed finish step; the same words as the step in the report. */
    static final String FINISH_TITLE = "Finished investigation and cleaned up";
    /** Title of the cleanup the MCP server did by itself (idle lease, disconnect, restart). */
    static final String AUTO_CLEANUP_TITLE = "Cleaned up automatically";

    private static final ActivityStore GLOBAL = new ActivityStore();

    /** One step of the timeline: running until its result arrives. */
    public record Step(String kind, String title, String status, List<String> details, long durationMs,
                       String startedAt, String finishedAt) {
        public boolean running() {
            return "RUNNING".equals(status);
        }
    }

    /**
     * One investigation as the view shows it; an immutable copy. {@code program} is what Eclipse saw of the agent's
     * launch ({@link #PROGRAM_RUNNING}, {@link #PROGRAM_PAUSED}, {@link #PROGRAM_ENDED}, or null before the launch),
     * {@code programWhere} where it is paused ("OrderService.quote:24"); {@code lastActivity} when the last event of
     * this investigation arrived (receive time, ISO-8601); {@code analysis} the agent's structured findings at the
     * finish as a JSON object (see {@code ReportEvent.InvestigationFinished}), or null.
     */
    public record Investigation(String id, String mode, String client, String request, boolean requestExact,
                                String startedAt, List<Step> steps, int droppedSteps, String outcome,
                                String conclusion, List<String> cleanup, String reportFile, String finishedAt,
                                String program, String programWhere, String lastActivity, String analysis) {
        public boolean finished() {
            return outcome != null;
        }
    }

    /** States of the agent's program, set by the debug event listener in Eclipse. */
    public static final String PROGRAM_RUNNING = "RUNNING", PROGRAM_PAUSED = "PAUSED", PROGRAM_ENDED = "ENDED";

    private final Map<String, Mutable> investigations = new LinkedHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private long droppedEvents;

    /**
     * A store of its own: one {@link #global()} store in Eclipse for the view; the HTML report writer in the MCP
     * server builds each report in its own store; tests use their own.
     */
    public ActivityStore() {
    }

    /** The store of this Eclipse instance. */
    public static ActivityStore global() {
        return GLOBAL;
    }

    /** Notified with the investigation ID after every change, on the thread that delivered the event. */
    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    /**
     * Takes one event: the argument of {@code ACTIVITY_EVENT}. Returns the bridge reply, one JSON line. Never throws.
     */
    public String accept(String encoded) {
        String id;
        try {
            if (encoded == null || encoded.length() > MAX_EVENT_CHARS * 2)
                return error("Activity event missing or too large");
            String json = new String(Base64.getUrlDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
            Map<String, Object> event = Json.obj(Json.parse(json));
            String type = Json.str(event.get("type"));
            if (type == null) return error("Activity event without type");
            synchronized (this) {
                id = apply(type, event);
            }
        } catch (RuntimeException invalid) {
            return error("Invalid activity event: " + invalid.getClass().getSimpleName());
        }
        if (id != null) notifyListeners(id);
        return "{\"status\":\"success\"}";
    }

    /**
     * What Eclipse saw of the agent's launch (OPEN-36 B5): running, paused at a location, or ended. Applied to the
     * open investigation shown in the view. When none is open, only "ended" is applied, to the newest investigation:
     * a cleanup ends the program, and Eclipse may report that after the finish event has arrived (seen 25 Nov 21:30
     * on Eclipse 2025-12: the window said "paused" although the cleanup had ended the JVM). Never throws.
     */
    public void program(String state, String where) {
        String id = null;
        synchronized (this) {
            Mutable open = null, newest = null;
            for (Mutable m : investigations.values()) {
                if (m.outcome == null) open = m;
                newest = m;
            }
            if (open == null && PROGRAM_ENDED.equals(state)) open = newest;
            if (open != null && !(java.util.Objects.equals(open.program, state) && java.util.Objects.equals(open.programWhere, where))) {
                open.program = state;
                open.programWhere = PROGRAM_PAUSED.equals(state) ? where : null;
                id = open.id;
            }
        }
        if (id != null) notifyListeners(id);
    }

    /** All investigations, oldest first. */
    public synchronized List<Investigation> investigations() {
        List<Investigation> copy = new ArrayList<>();
        for (Mutable m : investigations.values()) copy.add(m.snapshot());
        return copy;
    }

    /** The investigation to show: the most recently started open one, otherwise the most recent one; null if none. */
    public synchronized Investigation current() {
        Mutable open = null;
        Mutable last = null;
        for (Mutable m : investigations.values()) {
            last = m;
            if (m.outcome == null) open = m;
        }
        Mutable chosen = open != null ? open : last;
        return chosen == null ? null : chosen.snapshot();
    }

    /** Events the MCP server could not deliver because its queue was full (reported by the MCP server). */
    public synchronized long droppedEvents() {
        return droppedEvents;
    }

    /** Forgets everything (for tests). */
    synchronized void clear() {
        investigations.clear();
        droppedEvents = 0;
    }

    private String apply(String type, Map<String, Object> e) {
        if (type.equals("eventsDropped")) {
            droppedEvents += Math.max(0, number(e.get("count")));
            return null;
        }
        String id = Json.str(e.get("investigationId"));
        if (id == null || id.isBlank()) throw new IllegalArgumentException("investigationId");
        String at = Json.str(e.get("at"));
        Mutable inv;
        switch (type) {
            case "investigationStarted" -> {
                inv = investigations.computeIfAbsent(id, Mutable::new);
                inv.mode = Json.str(e.get("mode"));
                inv.client = Json.str(e.get("client"));
                inv.request = Json.str(e.get("request"));
                inv.requestExact = Boolean.TRUE.equals(e.get("requestExact"));
                inv.startedAt = at;
                trim();
            }
            case "stepStarted" -> {
                inv = known(id, at);
                inv.add(new Step(Json.str(e.get("kind")), Json.str(e.get("title")), "RUNNING",
                        List.of(), -1, at, null));
            }
            case "stepRecorded" -> {
                inv = known(id, at);
                Step done = new Step(Json.str(e.get("kind")), Json.str(e.get("title")),
                        Json.str(e.get("status")), strings(e.get("details")), number(e.get("durationMs")),
                        null, at);
                inv.finishRunning(done);
            }
            case "investigationFinished" -> {
                inv = known(id, at);
                inv.outcome = Json.str(e.get("outcome"));
                inv.conclusion = Json.str(e.get("conclusion"));
                inv.cleanup = strings(e.get("cleanup"));
                inv.reportFile = Json.str(e.get("reportFile"));
                inv.analysis = Json.str(e.get("analysis"));
                inv.finishedAt = at;
                inv.endRunning("FINISHED".equals(inv.outcome));
            }
            default -> throw new IllegalArgumentException("type");
        }
        inv.lastActivity = Instant.now().toString();
        return id;
    }

    /** The investigation of an event; created when its start was missed (view opened late, event dropped). */
    private Mutable known(String id, String at) {
        Mutable inv = investigations.get(id);
        if (inv != null) return inv;
        inv = new Mutable(id);
        inv.startedAt = at;
        investigations.put(id, inv);
        trim();
        return inv;
    }

    /** Keeps at most MAX_INVESTIGATIONS: drops the oldest finished ones first, then the oldest. */
    private void trim() {
        while (investigations.size() > MAX_INVESTIGATIONS) {
            String drop = null;
            for (Mutable m : investigations.values()) {
                if (m.outcome != null) { drop = m.id; break; }
            }
            if (drop == null) drop = investigations.keySet().iterator().next();
            investigations.remove(drop);
        }
    }

    /**
     * Grows with every change. The view compares it once a second with what it shows, so it catches up even if a
     * notification was missed or arrived before its page was ready.
     */
    public long revision() {
        return revision.get();
    }

    private void notifyListeners(String id) {
        revision.incrementAndGet();
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(id);
            } catch (RuntimeException ignored) {
                // A broken listener must not affect the bridge or other listeners.
            }
        }
    }

    private static long number(Object value) {
        return value instanceof Double d ? d.longValue() : -1;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(value)) out.add(o == null ? "" : Json.str(o));
        return List.copyOf(out);
    }

    private static String error(String message) {
        return "{\"status\":\"error\",\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    /** The store's own, changeable form of an investigation. */
    private static final class Mutable {
        final String id;
        String mode, client, request, startedAt, outcome, conclusion, reportFile, finishedAt, analysis;
        String program, programWhere, lastActivity;
        boolean requestExact;
        List<String> cleanup = List.of();
        final Deque<Step> steps = new ArrayDeque<>();
        int droppedSteps;

        Mutable(String id) {
            this.id = id;
        }

        void add(Step step) {
            steps.addLast(step);
            while (steps.size() > MAX_STEPS) {
                steps.removeFirst();
                droppedSteps++;
            }
        }

        /**
         * A finished step replaces the last running one (tool calls of one agent arrive one at a time); without a
         * running step (its start was missed) it is added. The start time of the running step is kept.
         */
        void finishRunning(Step done) {
            Step last = steps.peekLast();
            if (last != null && last.running()) {
                steps.removeLast();
                done = new Step(done.kind(), done.title(), done.status(), done.details(), done.durationMs(),
                        last.startedAt(), done.finishedAt());
            }
            add(done);
        }

        /**
         * At the end no step can still be running. A successful finish_debug_investigation sends no step result of
         * its own, only the finish: its running CLEANUP step is completed as OK, with the report's title. Any other
         * step still running (a result that never came, or the investigation ended without the agent finishing it)
         * becomes UNKNOWN. A cleanup the MCP server did by itself is added as a "Cleaned up automatically" step.
         */
        void endRunning(boolean finishedByAgent) {
            Step last = steps.peekLast();
            if (last != null && last.running()) {
                steps.removeLast();
                if (finishedByAgent && "CLEANUP".equals(last.kind())) {
                    add(new Step(last.kind(), FINISH_TITLE, "OK", cleanup, millisBetween(last.startedAt(), finishedAt),
                            last.startedAt(), finishedAt));
                } else {
                    add(new Step(last.kind(), last.title(), "UNKNOWN", last.details(), -1, last.startedAt(), finishedAt));
                }
            }
            // Cleaned up by the MCP server itself (the agent never finished): its cleanup lines as a step of their own.
            if (!finishedByAgent && !cleanup.isEmpty())
                add(new Step("CLEANUP", AUTO_CLEANUP_TITLE, "OK", cleanup, -1, finishedAt, finishedAt));
        }

        private static long millisBetween(String from, String to) {
            try {
                return Math.max(0, Duration.between(Instant.parse(from), Instant.parse(to)).toMillis());
            } catch (RuntimeException unknown) {
                return -1;
            }
        }

        Investigation snapshot() {
            return new Investigation(id, mode, client, request, requestExact, startedAt, List.copyOf(steps),
                    droppedSteps, outcome, conclusion, cleanup, reportFile, finishedAt, program, programWhere,
                    lastActivity, analysis);
        }
    }
}
