package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.learningfromexperience.agentdebug.report.Redaction;
import org.learningfromexperience.agentdebug.report.ReportEvent;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepKind;
import org.learningfromexperience.agentdebug.report.ReportEvent.StepStatus;
import org.learningfromexperience.agentdebug.report.ReportRecorder;
import org.learningfromexperience.agentdebug.report.ReportRecorders;
import org.learningfromexperience.agentdebug.report.ReportSettings;

/**
 * Turns the agent's MCP tool calls into report events for the debugging session report (OPEN-30 step R1).
 *
 * <p>Called by {@link ReportingTools} after every tool call, with the tool name, its arguments, whether it failed
 * and the text it returned. It decides which investigation the call belongs to and describes it as one step:
 * kind (launch, breakpoint, stop, ...), a one-line title, and detail lines (arguments, stop location, variables as a
 * table, HTTP result, error). It records nothing itself: events go to a {@link ReportRecorder}, which hands them to
 * the report writer on its own thread (ADR-009), so this class only builds small immutable events.
 *
 * <p><b>Which investigation.</b> In order: the call's {@code investigationId} argument; the investigation named in
 * the reply ({@code attachedToInvestigation}, {@code investigationId}); the investigation that launched the
 * {@code launchName} the call names; the only open investigation. Calls outside any open investigation (ping, lists
 * before {@code begin_debug_investigation}) are not recorded: the report is about investigations.
 *
 * <p><b>Settings</b> (R1; a Preferences setting follows in R2): environment variable {@code AGENTDEBUG_REPORT}
 * ({@code off} by default, or {@code chaintest}) and {@code AGENTDEBUG_REPORT_DIR} (default
 * {@code ~/.eclipse-agentdebug/reports}); each MCP server process writes into its own time-stamped subfolder.
 *
 * <p><b>Never fails.</b> Every public method catches everything: a report problem must never change a tool result.
 * Not used concurrently in practice (MCP tool calls of one session arrive one at a time), but synchronized anyway.
 *
 * @author Ayan Dutta
 */
final class SessionReport {

    static final String ENV_WRITER = "AGENTDEBUG_REPORT";
    static final String ENV_DIR = "AGENTDEBUG_REPORT_DIR";
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static final Set<String> LAUNCH = Set.of("start_java_application", "start_junit_test",
            "start_debug_session", "restart_debug_session");
    private static final Set<String> BREAKPOINT = Set.of("set_breakpoint", "set_conditional_breakpoint",
            "set_exception_breakpoint", "set_tracepoint", "remove_breakpoint", "remove_exception_breakpoint",
            "list_breakpoints", "list_exception_breakpoints");
    private static final Set<String> STOP = Set.of("wait_for_stop", "wait_for_debug_pause");
    private static final Set<String> INSPECT = Set.of("get_frame_variables", "expand_frame_variable",
            "get_stack_frames", "get_debug_threads", "get_debug_status", "evaluate_expression",
            "show_debug_location", "get_process_output");
    /** Arguments already shown in the title or not useful to a reader. */
    private static final Set<String> HIDDEN_ARGUMENTS = Set.of("investigationId");
    /** Launch configurations the plugin creates are named "Agentic Debugger - project - MainClass". */
    private static final String LAUNCH_PREFIX = "Agentic Debugger - ";
    /** Frames listed for get_stack_frames; the rest is counted. */
    static final int MAX_FRAMES = 10;
    /** Lines of program output kept per process: the last ones, where the interesting part usually is. */
    static final int OUTPUT_TAIL = 8;
    /** Eclipse shows this pseudo-variable after a step; it is never useful in a report. */
    private static final String NO_RETURN_VALUE = "no method return value";

    private final ReportRecorder recorder;
    private final Set<String> open = new LinkedHashSet<>();
    private final Map<String, String> launchOwner = new HashMap<>();
    private final Map<String, List<String>> ownBreakpoints = new HashMap<>();

    SessionReport(ReportRecorder recorder) {
        this.recorder = recorder;
    }

    /** Reads the settings from the environment; reporting is off unless {@code AGENTDEBUG_REPORT} names a writer. */
    static SessionReport fromEnvironment(Map<String, String> env, PrintStream err) {
        try {
            String writer = env.getOrDefault(ENV_WRITER, ReportSettings.OFF);
            ReportSettings probe = new ReportSettings(writer, Path.of("."), null);
            if (probe.off()) return new SessionReport(ReportRecorder.off());
            Path base = env.containsKey(ENV_DIR) && !env.get(ENV_DIR).isBlank()
                    ? Path.of(env.get(ENV_DIR))
                    : Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "reports");
            Path folder = base.resolve(FOLDER.format(Instant.now()) + "-" + ProcessHandle.current().pid());
            ReportRecorder recorder = ReportRecorders.create(
                    new ReportSettings(writer, folder, "Agentic Debugger for Eclipse"), err);
            if (recorder.active()) err.println("[Eclipse Agentic Debugger] Session report: " + folder.resolve("index.html"));
            return new SessionReport(recorder);
        } catch (Throwable failure) {
            err.println("[Eclipse Agentic Debugger] Session report disabled: " + failure);
            return new SessionReport(ReportRecorder.off());
        }
    }

    boolean active() {
        return recorder.active();
    }

    /** Records one finished tool call. Never throws. */
    synchronized void toolCalled(String client, String tool, Map<String, Object> arguments, boolean failed,
                                 String text, long durationMs) {
        if (!recorder.active()) return;
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            Map<String, Object> reply = MiniJson.obj(MiniJson.parse(text));
            if (tool.equals("begin_debug_investigation")) {
                String id = MiniJson.str(reply.get("investigationId"));
                if (!failed && id != null) {
                    open.add(id);
                    String mode = MiniJson.str(reply.getOrDefault("mode", args.get("mode")));
                    recorder.record(new ReportEvent.InvestigationStarted(id, Instant.now(),
                            mode == null ? "NORMAL" : mode, client));
                }
                return;
            }
            String id = investigationFor(args, reply);
            if (id == null) return;
            if (tool.equals("finish_debug_investigation") && !failed) {
                recorder.record(new ReportEvent.InvestigationFinished(id, Instant.now(), ReportEvent.Outcome.FINISHED,
                        MiniJson.str(args.get("summary")), cleanupLines(id, reply)));
                forget(id);
                return;
            }
            if (!failed) remember(id, tool, args, reply);
            recorder.record(new ReportEvent.StepRecorded(id, Instant.now(), kind(tool),
                    failed ? StepStatus.FAILED : StepStatus.OK, title(tool, args, reply),
                    details(tool, args, reply, failed, text), List.of(), durationMs));
        } catch (Throwable ignored) {
            // A report problem must never affect the tool result.
        }
    }

    /** The MCP server finished an investigation the agent left open (agent gone, shutdown). Never throws. */
    synchronized void endedWithoutFinish(String id, String why) {
        if (!recorder.active() || !open.contains(id)) return;
        try {
            List<String> lines = new ArrayList<>();
            lines.add("Finished by the MCP server: " + why);
            lines.addAll(cleanupLines(id, Map.of()));
            recorder.record(new ReportEvent.InvestigationFinished(id, Instant.now(),
                    ReportEvent.Outcome.ENDED_WITHOUT_FINISH, null, lines));
            forget(id);
        } catch (Throwable ignored) {
            // Never affects shutdown.
        }
    }

    /** Writes the report and stops, within 5 seconds. Never throws. */
    synchronized void close() {
        try {
            recorder.close();
        } catch (Throwable ignored) {
            // Never affects shutdown.
        }
    }

    // ------------------------------------------------------------------ attribution

    private String investigationFor(Map<String, Object> args, Map<String, Object> reply) {
        for (Object candidate : new Object[]{args.get("investigationId"), reply.get("attachedToInvestigation"),
                reply.get("investigationId")}) {
            String id = MiniJson.str(candidate);
            if (id != null && open.contains(id)) return id;
        }
        String launch = MiniJson.str(args.get("launchName"));
        if (launch != null && launchOwner.containsKey(launch)) return launchOwner.get(launch);
        return open.size() == 1 ? open.iterator().next() : null;
    }

    private void remember(String id, String tool, Map<String, Object> args, Map<String, Object> reply) {
        String launch = MiniJson.str(reply.get("launchName"));
        if (LAUNCH.contains(tool) && launch != null) launchOwner.put(launch, id);
        if (tool.equals("set_breakpoint") || tool.equals("set_conditional_breakpoint") || tool.equals("set_tracepoint"))
            ownBreakpoints.computeIfAbsent(id, k -> new ArrayList<>()).add(where(args));
        if (tool.equals("set_exception_breakpoint"))
            ownBreakpoints.computeIfAbsent(id, k -> new ArrayList<>()).add(simple(MiniJson.str(args.get("exceptionName"))));
    }

    private void forget(String id) {
        open.remove(id);
        ownBreakpoints.remove(id);
        launchOwner.values().removeIf(id::equals);
    }

    // ------------------------------------------------------------------ description of a step

    static StepKind kind(String tool) {
        if (LAUNCH.contains(tool)) return StepKind.LAUNCH;
        if (BREAKPOINT.contains(tool)) return StepKind.BREAKPOINT;
        if (STOP.contains(tool)) return StepKind.STOP;
        if (tool.equals("control_debug_session") || tool.equals("configure_step_filters")) return StepKind.STEP;
        if (INSPECT.contains(tool)) return StepKind.INSPECT;
        if (tool.contains("http")) return StepKind.HTTP;
        if (tool.equals("finish_debug_investigation")) return StepKind.CLEANUP;
        return StepKind.OTHER;
    }

    static String title(String tool, Map<String, Object> a, Map<String, Object> reply) {
        String detail = switch (tool) {
            case "start_java_application" -> simple(MiniJson.str(a.get("mainType")));
            case "start_junit_test" -> simple(MiniJson.str(a.get("typeName")))
                    + (blank(MiniJson.str(a.get("methodName"))) ? "" : "." + MiniJson.str(a.get("methodName")));
            case "start_debug_session", "restart_debug_session" -> launchLabel(MiniJson.str(a.get("launchName")));
            case "set_breakpoint", "remove_breakpoint", "set_tracepoint" -> where(a);
            case "set_conditional_breakpoint" -> where(a) + " if " + MiniJson.str(a.get("condition"));
            case "set_exception_breakpoint", "remove_exception_breakpoint" -> simple(MiniJson.str(a.get("exceptionName")));
            case "wait_for_stop" -> stopTitle(reply);
            case "wait_for_debug_pause" -> pauseTitle(reply);
            case "control_debug_session" -> MiniJson.str(a.get("action")) + " thread " + MiniJson.str(a.get("threadIndex"));
            case "get_frame_variables" -> "thread " + MiniJson.str(a.get("threadIndex")) + ", frame " + MiniJson.str(a.get("frameIndex"));
            case "expand_frame_variable" -> MiniJson.str(a.get("path"));
            case "evaluate_expression" -> Redaction.shorten(MiniJson.str(a.get("expression")));
            case "start_fixture_http_request" -> "scenario=" + MiniJson.str(a.get("scenario"));
            case "get_fixture_http_request" -> MiniJson.str(a.get("requestId"));
            default -> null;
        };
        return blank(detail) || "null".equals(detail) ? tool
                : STOP.contains(tool) ? tool + ": " + detail : tool + " " + detail;
    }

    private static String stopTitle(Map<String, Object> reply) {
        if (!Boolean.TRUE.equals(reply.get("stopped")))
            return "no stop (" + Text.firstNonNull(MiniJson.str(reply.get("reason")), "no reply") + ")";
        Map<String, Object> at = MiniJson.obj(reply.get("location"));
        return MiniJson.str(reply.get("reason")) + (at.isEmpty() ? "" : " at " + frame(at));
    }

    private static String pauseTitle(Map<String, Object> reply) {
        for (Object t : MiniJson.arr(reply.get("threads"))) {
            Map<String, Object> thread = MiniJson.obj(t);
            if (Boolean.TRUE.equals(thread.get("suspended")))
                return "suspended " + MiniJson.str(thread.get("name")) + " (thread " + MiniJson.str(thread.get("index")) + ")";
        }
        return "no stop (" + Text.firstNonNull(MiniJson.str(reply.get("status")), "no reply") + ")";
    }

    /** "Agentic Debugger - spring-boot-debugging - OrdersApplication" -> "OrdersApplication"; other names unchanged. */
    static String launchLabel(String launch) {
        if (launch == null || !launch.startsWith(LAUNCH_PREFIX)) return launch;
        int last = launch.lastIndexOf(" - ");
        return last >= LAUNCH_PREFIX.length() - 3 ? launch.substring(last + 3) : launch;
    }

    static List<String> details(String tool, Map<String, Object> a, Map<String, Object> reply, boolean failed,
                                String text) {
        List<String> lines = new ArrayList<>();
        for (var entry : a.entrySet()) {
            if (HIDDEN_ARGUMENTS.contains(entry.getKey())) continue;
            String value = entry.getKey().equals("launchName")
                    ? launchLabel(MiniJson.str(entry.getValue())) : MiniJson.str(entry.getValue());
            lines.add(entry.getKey() + ": " + Redaction.value(entry.getKey(), value));
        }
        if (failed) {
            lines.add("");
            lines.add("Error: " + Redaction.shorten(errorText(reply, text)));
            return lines;
        }
        // A reply that is not JSON (an old plugin, a plain message) is shown as it is, whatever the tool.
        switch (reply.isEmpty() ? "" : tool) {
            case "wait_for_stop" -> stopDetails(reply, lines);
            case "get_frame_variables", "expand_frame_variable" -> variables(MiniJson.arr(
                    reply.containsKey("variables") ? reply.get("variables") : reply.get("children")), lines);
            case "get_stack_frames" -> {
                lines.add("");
                List<Object> frames = MiniJson.arr(reply.get("frames"));
                for (int i = 0; i < Math.min(MAX_FRAMES, frames.size()); i++) lines.add("  " + frame(MiniJson.obj(frames.get(i))));
                if (frames.size() > MAX_FRAMES) lines.add("  ... (" + (frames.size() - MAX_FRAMES) + " more frames)");
            }
            case "wait_for_debug_pause" -> {
                lines.add("");
                lines.add(pauseTitle(reply).startsWith("no stop") ? "No thread suspended" : "Suspended: " + pauseTitle(reply).substring(10));
            }
            case "get_debug_threads" -> {
                lines.add("");
                for (Object t : MiniJson.arr(reply.get("threads"))) {
                    Map<String, Object> thread = MiniJson.obj(t);
                    lines.add("  " + MiniJson.str(thread.get("index")) + "  " + MiniJson.str(thread.get("name"))
                            + (Boolean.TRUE.equals(thread.get("suspended")) ? "  (suspended)" : ""));
                }
            }
            case "get_process_output" -> processOutput(reply, lines);
            case "start_fixture_http_request" -> {
                lines.add("");
                lines.add(Text.firstNonNull(MiniJson.str(reply.get("method")), "POST") + " " + MiniJson.str(reply.get("url")));
                if (reply.get("bodySent") != null) lines.add("Body sent: " + Redaction.shorten(MiniJson.str(reply.get("bodySent"))));
                lines.add("Request id: " + MiniJson.str(reply.get("requestId")) + " (" + MiniJson.str(reply.get("status")) + ")");
            }
            case "get_fixture_http_request" -> {
                lines.add("");
                if (reply.get("httpStatus") != null) lines.add("HTTP " + MiniJson.str(reply.get("httpStatus")));
                else lines.add("Request " + MiniJson.str(reply.get("status")));
                if (reply.get("body") != null) lines.add("Body: " + Redaction.shorten(MiniJson.str(reply.get("body"))));
                if (reply.get("error") != null) lines.add("Error: " + Redaction.shorten(MiniJson.str(reply.get("error"))));
            }
            default -> {
                String result = resultSummary(a, reply, text);
                if (!result.isEmpty()) {
                    lines.add("");
                    lines.add("Result: " + result);
                }
            }
        }
        return lines;
    }

    private static void stopDetails(Map<String, Object> reply, List<String> lines) {
        lines.add("");
        if (!Boolean.TRUE.equals(reply.get("stopped"))) {
            lines.add("No stop: " + MiniJson.str(reply.get("reason")));
            return;
        }
        lines.add("Thread: " + MiniJson.str(reply.get("threadName")) + " (index " + MiniJson.str(reply.get("threadIndex")) + ")");
        if (reply.get("exceptionType") != null) lines.add("Exception: " + MiniJson.str(reply.get("exceptionType")));
        List<String> frames = new ArrayList<>();
        for (Object f : MiniJson.arr(reply.get("frames"))) frames.add(frame(MiniJson.obj(f)));
        if (!frames.isEmpty()) {
            Object total = reply.get("totalFrames");
            int more = total instanceof Double d ? d.intValue() - frames.size() : 0;
            lines.add("Frames: " + String.join("  <  ", frames) + (more > 0 ? "  <  (" + more + " more)" : ""));
        }
        variables(MiniJson.arr(reply.get("locals")), lines);
        if (reply.get("localsNote") != null) lines.add("Note: " + MiniJson.str(reply.get("localsNote")));
    }

    /** Variables as an aligned three-column table, secrets masked and long values shortened. */
    static void variables(List<Object> items, List<String> lines) {
        if (items.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Variable", "Type", "Value"});
        for (Object item : items) {
            Map<String, Object> v = MiniJson.obj(item);
            String name = Text.firstNonNull(MiniJson.str(v.get("name")), "?");
            if (name.equals(NO_RETURN_VALUE)) continue;
            rows.add(new String[]{name, Text.firstNonNull(simple(MiniJson.str(v.get("type"))), ""),
                    Redaction.value(name, MiniJson.str(v.get("value")))});
        }
        int w0 = 0, w1 = 0;
        for (String[] r : rows) { w0 = Math.max(w0, r[0].length()); w1 = Math.max(w1, r[1].length()); }
        lines.add("");
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            lines.add(pad(r[0], w0) + "    " + pad(r[1], w1) + "    " + r[2]);
            if (i == 0) lines.add("-".repeat(w0) + "    " + "-".repeat(Math.max(w1, 4)) + "    " + "-----");
        }
    }

    private List<String> cleanupLines(String id, Map<String, Object> reply) {
        List<String> lines = new ArrayList<>();
        if (reply.get("action") != null) lines.add("Action: " + MiniJson.str(reply.get("action")));
        String action = MiniJson.str(reply.get("action"));
        for (String bp : ownBreakpoints.getOrDefault(id, List.of()))
            lines.add(("KEEP_SUSPENDED".equals(action) ? "Kept own breakpoint " : "Removed own breakpoint ") + bp);
        if (reply.get("launchName") != null && Boolean.TRUE.equals(reply.get("ownedSession")))
            lines.add("Own launch: " + launchLabel(MiniJson.str(reply.get("launchName"))));
        if (reply.get("processesEnded") != null)
            lines.add("JVM ended: " + MiniJson.str(reply.get("processesEnded")));
        List<String> steps = new ArrayList<>();
        for (Object step : MiniJson.arr(reply.get("terminationSteps"))) steps.add(render(step));
        if (!steps.isEmpty()) lines.add("Termination: " + Redaction.shorten(String.join(", ", steps)));
        if (reply.get("warning") != null) lines.add("Warning: " + MiniJson.str(reply.get("warning")));
        return lines;
    }

    /** Last lines of each process's output (console first, else stdout and stderr). */
    private static void processOutput(Map<String, Object> reply, List<String> lines) {
        for (Object item : MiniJson.arr(reply.get("processes"))) {
            Map<String, Object> process = MiniJson.obj(item);
            lines.add("");
            String label = Text.firstNonNull(MiniJson.str(process.get("label")), "process");
            int pid = label.indexOf("[pid:");
            lines.add("Process" + (pid >= 0 ? " " + label.substring(pid) : ""));
            String output = MiniJson.str(process.get("console"));
            if (blank(output)) output = Text.firstNonNull(MiniJson.str(process.get("stdout")), "")
                    + Text.firstNonNull(MiniJson.str(process.get("stderr")), "");
            List<String> all = new ArrayList<>();
            for (String line : output.split("\\R")) if (!line.isBlank()) all.add(line);
            if (all.isEmpty()) { lines.add("  (no output)"); continue; }
            int from = Math.max(0, all.size() - OUTPUT_TAIL);
            if (from > 0) lines.add("  ... last " + OUTPUT_TAIL + " of " + all.size() + " lines");
            for (String line : all.subList(from, all.size())) lines.add("  | " + Redaction.shorten(line));
        }
        if (MiniJson.arr(reply.get("processes")).isEmpty()) {
            lines.add("");
            lines.add("(no process output)");
        }
    }

    /**
     * The reply as "key=value" pairs, without the status, without values the arguments already show, and with
     * whole numbers and nested values written readably.
     */
    static String resultSummary(Map<String, Object> args, Map<String, Object> reply, String text) {
        if (reply.isEmpty()) return Redaction.shorten(text == null ? "" : text.strip());
        Set<String> shownInArguments = new java.util.HashSet<>();
        for (Object v : args.values()) shownInArguments.add(MiniJson.str(v));
        List<String> parts = new ArrayList<>();
        for (var e : reply.entrySet()) {
            if (e.getKey().equals("status") || e.getKey().equals("attachedToInvestigation")) continue;
            String value = e.getKey().equals("launchName") ? launchLabel(MiniJson.str(e.getValue())) : render(e.getValue());
            if (shownInArguments.contains(MiniJson.str(e.getValue()))) continue;
            parts.add(e.getKey() + "=" + Redaction.value(e.getKey(), value));
        }
        return Redaction.shorten(String.join(", ", parts));
    }

    /** A JSON value as text: whole numbers without ".0", objects as {k=v}, arrays as [a, b]. */
    static String render(Object value) {
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (var e : map.entrySet()) parts.add(e.getKey() + "=" + render(e.getValue()));
            return "{" + String.join(", ", parts) + "}";
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) parts.add(render(item));
            return "[" + String.join(", ", parts) + "]";
        }
        return MiniJson.str(value);
    }

    private static String errorText(Map<String, Object> reply, String text) {
        String message = MiniJson.str(reply.get("message"));
        if (message == null) message = MiniJson.str(reply.get("error"));
        return message != null ? message : text == null ? "" : text.strip();
    }

    private static String where(Map<String, Object> a) {
        return simple(MiniJson.str(a.get("typeName"))) + ":" + MiniJson.str(a.get("lineNumber"));
    }

    private static String frame(Map<String, Object> f) {
        return simple(MiniJson.str(f.get("className"))) + "." + MiniJson.str(f.get("methodName"))
                + ":" + MiniJson.str(f.get("lineNumber"));
    }

    /** org.example.OrderService -> OrderService (inner classes and generics kept as they are). */
    static String simple(String className) {
        if (className == null) return null;
        int dot = className.lastIndexOf('.', className.indexOf('<') < 0 ? className.length() - 1 : className.indexOf('<'));
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** First non-null of two texts (java.util.Objects.requireNonNullElse throws when both are null). */
    private static final class Text {
        static String firstNonNull(String a, String b) { return a != null ? a : b; }
    }
}
