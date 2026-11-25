package org.learningfromexperience.eclipse.agentdebug.mcp;

import org.learningfromexperience.agentdebug.report.format.Json;
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
 * <p><b>Settings</b> (a Preferences setting follows): environment variable {@code AGENTDEBUG_REPORT}
 * ({@code html} by default, {@code off} to switch reports off; {@code chaintest}, the earlier name, still means {@code html}) and {@code AGENTDEBUG_REPORT_DIR}.
 * {@code AGENTDEBUG_REPORT_PROMPT}, set only by a test harness, is the exact prompt sent to the agent.
 *
 * <p><b>Where a report goes.</b> {@code AGENTDEBUG_REPORT_DIR} when set (trials use it); otherwise the
 * {@value #PROJECT_REPORTS} folder inside the Eclipse project of the application the investigation launched (the
 * plugin returns the project's location with every launch); otherwise, when nothing was launched or the project
 * folder is not writable, {@code ~/.eclipse-agentdebug/reports}.
 *
 * <p><b>One report per investigation</b>, named by {@link ReportFiles}
 * ({@code OrdersApplication_2026-11-24_10-39-31.html}). This class chooses the file when the investigation ends,
 * passes it to the writer, prints the full path on stderr (the MCP server's log) and remembers it for the
 * {@code get_investigation_report} tool. What the user asked comes from the exact prompt when known, otherwise from
 * the agent's {@code request} argument of {@code begin_debug_investigation}; the agent's conclusion comes from the
 * {@code summary} argument of {@code finish_debug_investigation}.
 *
 * <p><b>Never fails.</b> Every public method catches everything: a report problem must never change a tool result.
 * Not used concurrently in practice (MCP tool calls of one session arrive one at a time), but synchronized anyway.
 *
 * @author Ayan Dutta
 */
final class SessionReport {

    static final String ENV_WRITER = "AGENTDEBUG_REPORT";
    static final String ENV_DIR = "AGENTDEBUG_REPORT_DIR";
    static final String ENV_PROMPT = "AGENTDEBUG_REPORT_PROMPT";
    /** {@code off} switches the live Agent Activity events off (OPEN-36); on by default. */
    static final String ENV_LIVE = "AGENTDEBUG_LIVE";
    /** Writer used when {@code AGENTDEBUG_REPORT} is not set: reports are on by default. */
    static final String DEFAULT_WRITER = "html";
    /** Folder created inside the Eclipse project for its reports. */
    static final String PROJECT_REPORTS = "debug-reports";

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

    /** The HTML report (off when {@code AGENTDEBUG_REPORT=off}). */
    private final ReportRecorder recorder;
    /** Every consumer of the events: the report and the live Agent Activity view, each isolated (ADR-009). */
    private final ReportRecorder events;
    /** Fixed folder for all reports ({@code AGENTDEBUG_REPORT_DIR}); null: each report goes to its project. */
    private final Path folder;
    /** Folder when the project is unknown or not writable. */
    private final Path fallback;
    /** Location of the Eclipse project each open investigation launched, as returned by the plugin. */
    private final Map<String, Path> projects = new HashMap<>();
    /** The exact prompt sent to the agent, when a test harness knows it; otherwise null. */
    private final String exactPrompt;
    private final PrintStream log;
    private final Set<String> open = new LinkedHashSet<>();
    private final Map<String, Instant> starts = new HashMap<>();
    private final Map<String, String> targets = new HashMap<>();
    /** Report file of every ended investigation of this session, in order. */
    private final Map<String, Path> reports = new LinkedHashMap<>();
    private final Set<Path> taken = new java.util.HashSet<>();
    private final Map<String, String> launchOwner = new HashMap<>();
    private final Map<String, List<String>> ownBreakpoints = new HashMap<>();

    SessionReport(ReportRecorder recorder) {
        this(recorder, null, null, System.err);
    }

    SessionReport(ReportRecorder recorder, Path folder, String exactPrompt, PrintStream log) {
        this(recorder, ReportRecorder.off(), folder, exactPrompt, log);
    }

    /**
     * @param recorder the HTML report's recorder
     * @param live     the live Agent Activity view's recorder (OPEN-36); off when the view is switched off
     */
    SessionReport(ReportRecorder recorder, ReportRecorder live, Path folder, String exactPrompt, PrintStream log) {
        this.recorder = recorder;
        this.events = org.learningfromexperience.agentdebug.report.CompositeReportRecorder.of(recorder, live);
        this.folder = folder;
        this.fallback = Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "reports");
        this.exactPrompt = exactPrompt == null || exactPrompt.isBlank() ? null : exactPrompt;
        this.log = log;
    }

    /**
     * Reads the settings from the environment. Reporting is on by default ({@value #DEFAULT_WRITER});
     * {@code AGENTDEBUG_REPORT=off} switches it off.
     */
    static SessionReport fromEnvironment(Map<String, String> env, PrintStream err) {
        return fromEnvironment(env, err, null);
    }

    /**
     * As {@link #fromEnvironment(Map, PrintStream)}, plus the live Agent Activity view (OPEN-36): on by default when a
     * bridge is given, {@code AGENTDEBUG_LIVE=off} switches it off. Report and view are independent: either can be
     * off while the other is on.
     */
    static SessionReport fromEnvironment(Map<String, String> env, PrintStream err, ActivityForwarder.Bridge bridge) {
        ReportRecorder live = ReportRecorder.off();
        try {
            if (bridge != null && !"off".equalsIgnoreCase(String.valueOf(env.get(ENV_LIVE)).strip())) {
                live = new org.learningfromexperience.agentdebug.report.AsyncReportRecorder(
                        new ActivityForwarder(bridge, err), "agent-activity", err,
                        org.learningfromexperience.agentdebug.report.AsyncReportRecorder.DEFAULT_CAPACITY);
            }
        } catch (Throwable failure) {
            err.println("[Eclipse Agentic Debugger] Agent Activity events disabled: " + failure);
        }
        try {
            String writer = env.get(ENV_WRITER) == null || env.get(ENV_WRITER).isBlank()
                    ? DEFAULT_WRITER : env.get(ENV_WRITER);
            ReportSettings probe = new ReportSettings(writer, Path.of("."), null);
            if (probe.off()) return new SessionReport(ReportRecorder.off(), live, null, env.get(ENV_PROMPT), err);
            Path fixed = env.containsKey(ENV_DIR) && !env.get(ENV_DIR).isBlank()
                    ? Path.of(env.get(ENV_DIR)).toAbsolutePath().normalize() : null;
            Path writerFolder = fixed != null ? fixed
                    : Path.of(System.getProperty("user.home"), ".eclipse-agentdebug", "reports");
            ReportRecorder recorder = ReportRecorders.create(
                    new ReportSettings(writer, writerFolder, "Agentic Debugger for Eclipse"), err);
            if (recorder.active()) err.println("[Eclipse Agentic Debugger] Debugging session reports go to "
                    + (fixed != null ? fixed : "the " + PROJECT_REPORTS + " folder of each launched Eclipse project"));
            return new SessionReport(recorder, live, fixed, env.get(ENV_PROMPT), err);
        } catch (Throwable failure) {
            err.println("[Eclipse Agentic Debugger] Session report disabled: " + failure);
            return new SessionReport(ReportRecorder.off(), live, null, null, err);
        }
    }

    /** True when the report or the live view receives events. */
    boolean active() {
        return events.active();
    }

    /**
     * A tool call is about to run (OPEN-36): records a {@link ReportEvent.StepStarted} for the live view, so it can
     * show the step as running ("Waiting for the program to stop"). Calls outside an investigation are ignored, like
     * in {@link #toolCalled}. Never throws.
     */
    synchronized void toolStarting(String tool, Map<String, Object> arguments) {
        if (!events.active() || tool == null || tool.equals("begin_debug_investigation")) return;
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String id = investigationFor(args, Map.of());
            if (id == null) return;
            events.record(new ReportEvent.StepStarted(id, Instant.now(), kind(tool), startedTitle(tool, args)));
        } catch (Throwable ignored) {
            // A live-view problem must never affect the tool call.
        }
    }

    /** The plain-language title of a step that is still running. */
    static String startedTitle(String tool, Map<String, Object> args) {
        return switch (tool) {
            case "wait_for_stop", "wait_for_debug_pause" -> "Waiting for the program to stop";
            case "start_java_application" -> "Starting application in debug mode: " + simple(Json.str(args.get("mainType")));
            case "start_junit_test" -> "Starting JUnit test in debug mode: " + simple(Json.str(args.get("typeName")));
            case "start_fixture_http_request" -> "Sending HTTP request (" + Json.str(args.get("scenario")) + " scenario)";
            case "finish_debug_investigation" -> "Finishing the investigation and cleaning up";
            default -> title(tool, args, Map.of());
        };
    }

    /** Records one finished tool call. Never throws. */
    synchronized void toolCalled(String client, String tool, Map<String, Object> arguments, boolean failed,
                                 String text, long durationMs) {
        if (!events.active()) return;
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            Map<String, Object> reply = Json.obj(Json.parse(text));
            if (tool.equals("begin_debug_investigation")) {
                String id = Json.str(reply.get("investigationId"));
                if (!failed && id != null) {
                    open.add(id);
                    Instant now = Instant.now();
                    starts.put(id, now);
                    String mode = Json.str(reply.getOrDefault("mode", args.get("mode")));
                    String request = exactPrompt != null ? exactPrompt : Json.str(args.get("request"));
                    events.record(new ReportEvent.InvestigationStarted(id, now, mode == null ? "NORMAL" : mode, client,
                            request, exactPrompt != null, Json.str(args.get("skill")), Json.str(args.get("model")),
                            Json.str(args.get("effort"))));
                }
                return;
            }
            String id = investigationFor(args, reply);
            if (id == null) return;
            if (tool.equals("finish_debug_investigation") && !failed) {
                // The structured findings (OPEN-30 R4 phase 2), with the root cause's source lines read from the project.
                String analysis = FinishAnalysis.json(args, projects.get(id));
                events.record(new ReportEvent.InvestigationFinished(id, Instant.now(), ReportEvent.Outcome.FINISHED,
                        Json.str(args.get("summary")), cleanupLines(id, reply), chooseReport(id), analysis));
                forget(id);
                return;
            }
            if (!failed) remember(id, tool, args, reply);
            events.record(new ReportEvent.StepRecorded(id, Instant.now(), kind(tool),
                    failed ? StepStatus.FAILED : StepStatus.OK, title(tool, args, reply),
                    details(tool, args, reply, failed, text), List.of(), durationMs));
        } catch (Throwable ignored) {
            // A report problem must never affect the tool result.
        }
    }

    /** The MCP server finished an investigation the agent left open (agent gone, shutdown). Never throws. */
    synchronized void endedWithoutFinish(String id, String why) {
        if (!events.active() || !open.contains(id)) return;
        try {
            List<String> lines = new ArrayList<>();
            lines.add("Finished by the MCP server: " + why);
            lines.addAll(cleanupLines(id, Map.of()));
            events.record(new ReportEvent.InvestigationFinished(id, Instant.now(),
                    ReportEvent.Outcome.ENDED_WITHOUT_FINISH, null, lines, chooseReport(id)));
            forget(id);
        } catch (Throwable ignored) {
            // Never affects shutdown.
        }
    }

    /** Writes the report, delivers the last live events and stops, within 5 seconds each. Never throws. */
    synchronized void close() {
        try {
            events.close();
        } catch (Throwable ignored) {
            // Never affects shutdown.
        }
    }

    /**
     * Where the report of an investigation is, for the get_investigation_report tool: the given investigation, or the
     * most recently ended one. JSON. Never throws.
     */
    synchronized String reportInfo(String investigationId) {
        try {
            if (!recorder.active())
                return "{\"status\":\"success\",\"enabled\":false,\"message\":"
                        + quote("Debugging session reports are switched off for this MCP server (AGENTDEBUG_REPORT=off).") + "}";
            Path file = null;
            String id = investigationId;
            if (id != null && !id.isBlank()) file = reports.get(id);
            else if (!reports.isEmpty()) {
                for (var e : reports.entrySet()) { id = e.getKey(); file = e.getValue(); }
            }
            if (file == null) {
                boolean stillOpen = id != null && open.contains(id);
                return "{\"status\":\"success\",\"enabled\":true"
                        + (folder != null ? ",\"reportFolder\":" + quote(folder.toString()) : "")
                        + ",\"message\":" + quote(stillOpen || (id == null && !open.isEmpty())
                        ? "The report is written when the investigation is finished: call finish_debug_investigation first."
                        : "No finished investigation in this session yet.") + "}";
            }
            return "{\"status\":\"success\",\"enabled\":true,\"investigationId\":" + quote(id)
                    + ",\"reportFile\":" + quote(file.toString()) + ",\"reportFolder\":" + quote(file.getParent().toString())
                    + ",\"message\":" + quote("Tell the user the full path of the report: " + file) + "}";
        } catch (Throwable failure) {
            return "{\"status\":\"error\",\"message\":" + quote("Report location unavailable: " + failure.getMessage()) + "}";
        }
    }

    /**
     * OPEN-39 (token use): the reply of finish_debug_investigation with the report file added
     * ({@code reportFile}, {@code reportMessage}), so the agent can tell the user the path without another call.
     * Returns the reply unchanged when reports are off, no report was chosen for the investigation, or the reply is
     * not one JSON object. Never throws.
     */
    synchronized String withReportFile(String investigationId, String reply) {
        try {
            if (!recorder.active() || investigationId == null || reply == null) return reply;
            Path file = reports.get(investigationId);
            String text = reply.strip();
            if (file == null || text.length() < 3 || !text.startsWith("{") || !text.endsWith("}") || text.contains("\"reportFile\""))
                return reply;
            return text.substring(0, text.length() - 1) + ",\"reportFile\":" + quote(file.toString())
                    + ",\"reportMessage\":" + quote("Tell the user the full path of the report: " + file
                    + ". No further call is needed for it.") + "}";
        } catch (Throwable ignored) {
            return reply;
        }
    }

    /** The report file of an ending investigation; printed on stderr so it appears in the MCP server's log. */
    private Path chooseReport(String id) {
        if (!recorder.active()) return null;
        Path file = org.learningfromexperience.agentdebug.report.ReportFiles.reserve(reportFolder(id), targets.get(id),
                starts.getOrDefault(id, Instant.now()), ZoneId.systemDefault(), taken);
        reports.put(id, file);
        log.println("[Eclipse Agentic Debugger] Debugging session report: " + file);
        return file;
    }

    /**
     * The fixed folder when set; otherwise {@value #PROJECT_REPORTS} in the project the investigation launched, when
     * that project folder exists and is writable; otherwise the fallback folder in the user's home.
     */
    Path reportFolder(String id) {
        if (folder != null) return folder;
        Path project = projects.get(id);
        if (project != null && java.nio.file.Files.isDirectory(project) && java.nio.file.Files.isWritable(project))
            return project.resolve(PROJECT_REPORTS).toAbsolutePath().normalize();
        return fallback;
    }

    private static String quote(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ attribution

    private String investigationFor(Map<String, Object> args, Map<String, Object> reply) {
        for (Object candidate : new Object[]{args.get("investigationId"), reply.get("attachedToInvestigation"),
                reply.get("investigationId")}) {
            String id = Json.str(candidate);
            if (id != null && open.contains(id)) return id;
        }
        String launch = Json.str(args.get("launchName"));
        if (launch != null && launchOwner.containsKey(launch)) return launchOwner.get(launch);
        return open.size() == 1 ? open.iterator().next() : null;
    }

    private void remember(String id, String tool, Map<String, Object> args, Map<String, Object> reply) {
        String launch = Json.str(reply.get("launchName"));
        if (LAUNCH.contains(tool) && launch != null) launchOwner.put(launch, id);
        String location = Json.str(reply.get("projectLocation"));
        if (LAUNCH.contains(tool) && location != null && !location.isBlank() && !projects.containsKey(id)) {
            try { projects.put(id, Path.of(location)); } catch (RuntimeException invalid) { /* fallback folder */ }
        }
        if (LAUNCH.contains(tool) && !targets.containsKey(id)) {
            // The application the report file is named after: main class, or test class and method.
            String title = title(tool, args, reply);
            int colon = title.lastIndexOf(": ");
            if (colon > 0) targets.put(id, title.substring(colon + 2));
        }
        if (tool.equals("set_breakpoint") || tool.equals("set_conditional_breakpoint") || tool.equals("set_tracepoint"))
            ownBreakpoints.computeIfAbsent(id, k -> new ArrayList<>()).add(where(args));
        if (tool.equals("set_exception_breakpoint"))
            ownBreakpoints.computeIfAbsent(id, k -> new ArrayList<>()).add(simple(Json.str(args.get("exceptionName"))));
    }

    private void forget(String id) {
        open.remove(id);
        starts.remove(id);
        targets.remove(id);
        projects.remove(id);
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

    /**
     * A plain-language title for the step: what happened, not which tool was called (the tool name is the first
     * detail line). Examples: "Set breakpoint: OrderService, line 24", "Stopped at breakpoint: OrderService.quote,
     * line 24", "Sent HTTP request: POST /orders/quote", "HTTP request not sent (discount scenario)",
     * "Received HTTP response: 200".
     */
    static String title(String tool, Map<String, Object> a, Map<String, Object> reply) {
        String type = simple(Json.str(a.get("typeName")));
        String line = Json.str(a.get("lineNumber"));
        String launch = launchLabel(Json.str(a.get("launchName")));
        return switch (tool) {
            case "start_java_application" -> "Started application in debug mode: " + simple(Json.str(a.get("mainType")));
            case "start_junit_test" -> "Started JUnit test in debug mode: " + type
                    + (blank(Json.str(a.get("methodName"))) ? "" : "." + Json.str(a.get("methodName")));
            case "start_debug_session" -> "Started debug session: " + launch;
            case "restart_debug_session" -> "Restarted debug session: " + launch;
            case "set_breakpoint" -> "Set breakpoint: " + type + ", line " + line;
            case "set_conditional_breakpoint" -> "Set conditional breakpoint: " + type + ", line " + line
                    + " (when " + Json.str(a.get("condition")) + ")";
            case "set_tracepoint" -> "Set tracepoint: " + type + ", line " + line;
            case "remove_breakpoint" -> "Removed breakpoint: " + type + ", line " + line;
            case "set_exception_breakpoint" -> "Break on exception: " + simple(Json.str(a.get("exceptionName")));
            case "remove_exception_breakpoint" -> "Stopped breaking on exception: " + simple(Json.str(a.get("exceptionName")));
            case "list_breakpoints", "list_exception_breakpoints" -> "Listed breakpoints";
            case "wait_for_stop" -> stopTitle(reply);
            case "wait_for_debug_pause" -> pauseTitle(reply);
            case "control_debug_session" -> controlTitle(Json.str(a.get("action")));
            case "get_frame_variables" -> "Read variables (frame " + Json.str(a.get("frameIndex")) + ")";
            case "expand_frame_variable" -> "Expanded variable: " + Json.str(a.get("path"));
            case "get_stack_frames" -> "Read call stack";
            case "get_debug_threads" -> "Listed threads";
            case "get_debug_status" -> "Checked debug status";
            case "evaluate_expression" -> "Evaluated expression: " + Redaction.shorten(Json.str(a.get("expression")));
            case "show_debug_location" -> "Showed the current line in the editor";
            case "get_process_output" -> "Read program output";
            case "configure_step_filters" -> "Configured step filters";
            // No request id in the reply: the request was refused before anything was sent (fixture not ready).
            case "start_fixture_http_request" -> reply.get("requestId") == null
                    ? "HTTP request not sent (" + Json.str(a.get("scenario")) + " scenario)"
                    : "Sent HTTP request: "
                    + Text.firstNonNull(Json.str(reply.get("method")), "POST")
                    + (reply.get("url") != null ? " " + path(Json.str(reply.get("url"))) : "")
                    + " (" + Json.str(a.get("scenario")) + " scenario)";
            case "get_fixture_http_request" -> reply.get("httpStatus") != null
                    ? "Received HTTP response: " + Json.str(reply.get("httpStatus"))
                    : "COMPLETE".equals(Json.str(reply.get("status"))) || reply.isEmpty() ? "Checked HTTP response"
                    : "HTTP response: " + Text.firstNonNull(Json.str(reply.get("status")), "unknown").toLowerCase(Locale.ROOT);
            case "finish_debug_investigation" -> "Finished investigation and cleaned up";
            default -> "Called " + tool;
        };
    }

    private static String controlTitle(String action) {
        if (action == null) return "Controlled execution";
        return switch (action) {
            case "RESUME" -> "Resumed execution";
            case "STEP_OVER" -> "Stepped over the current line";
            case "STEP_INTO" -> "Stepped into the method call";
            case "STEP_RETURN" -> "Stepped out of the method";
            case "SUSPEND" -> "Paused execution";
            case "TERMINATE" -> "Terminated the program";
            default -> "Execution control: " + action;
        };
    }

    /** "http://127.0.0.1:61956/orders/quote" -> "/orders/quote". */
    private static String path(String url) {
        if (url == null) return "";
        int scheme = url.indexOf("://");
        int slash = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        return slash < 0 ? url : url.substring(slash);
    }

    private static String stopTitle(Map<String, Object> reply) {
        if (!Boolean.TRUE.equals(reply.get("stopped"))) {
            String reason = Text.firstNonNull(Json.str(reply.get("reason")), "no reply");
            return reason.equals("TERMINATED") ? "Program ended without stopping" : "Waited for a stop: none (" + reason + ")";
        }
        Map<String, Object> at = Json.obj(reply.get("location"));
        String where = at.isEmpty() ? "" : ": " + simple(Json.str(at.get("className"))) + "."
                + Json.str(at.get("methodName")) + ", line " + Json.str(at.get("lineNumber"));
        String reason = Text.firstNonNull(Json.str(reply.get("reason")), "");
        return switch (reason) {
            case "BREAKPOINT" -> "Stopped at breakpoint" + where;
            case "EXCEPTION" -> "Stopped on exception " + Text.firstNonNull(simple(Json.str(reply.get("exceptionType"))), "") + where;
            case "STEP_END" -> "Stopped after step" + where;
            case "PAUSED" -> "Paused" + where;
            default -> "Stopped" + where;
        };
    }

    private static String pauseTitle(Map<String, Object> reply) {
        for (Object t : Json.arr(reply.get("threads"))) {
            Map<String, Object> thread = Json.obj(t);
            if (Boolean.TRUE.equals(thread.get("suspended")))
                return "Thread suspended: " + Json.str(thread.get("name")) + " (thread " + Json.str(thread.get("index")) + ")";
        }
        return "Waited for a pause: no thread suspended";
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
        lines.add("Tool: " + tool);
        for (var entry : a.entrySet()) {
            if (HIDDEN_ARGUMENTS.contains(entry.getKey())) continue;
            String value = entry.getKey().equals("launchName")
                    ? launchLabel(Json.str(entry.getValue())) : Json.str(entry.getValue());
            lines.add(entry.getKey() + ": " + Redaction.value(entry.getKey(), value));
        }
        int technicalEnd = lines.size();
        if (failed) {
            lines.add("Error: " + Redaction.shorten(errorText(reply, text)));
            return readerFirst(lines, technicalEnd, List.of());
        }
        // A reply that is not JSON (an old plugin, a plain message) is shown as it is, whatever the tool.
        switch (reply.isEmpty() ? "" : tool) {
            case "wait_for_stop" -> stopDetails(reply, lines);
            case "get_frame_variables", "expand_frame_variable" -> variables(Json.arr(
                    reply.containsKey("variables") ? reply.get("variables") : reply.get("children")), lines);
            case "get_stack_frames" -> {
                lines.add("");
                List<Object> frames = new ArrayList<>();
                for (Object f : Json.arr(reply.get("frames"))) if (!hiddenFrame(Json.obj(f))) frames.add(f);
                for (int i = 0; i < Math.min(MAX_FRAMES, frames.size()); i++) lines.add("  " + frame(Json.obj(frames.get(i))));
                if (frames.size() > MAX_FRAMES) lines.add("  ... (" + (frames.size() - MAX_FRAMES) + " more frames)");
            }
            case "wait_for_debug_pause" -> {
                lines.add("");
                lines.add(pauseTitle(reply));
            }
            case "get_debug_threads" -> {
                lines.add("");
                for (Object t : Json.arr(reply.get("threads"))) {
                    Map<String, Object> thread = Json.obj(t);
                    lines.add("  " + Json.str(thread.get("index")) + "  " + Json.str(thread.get("name"))
                            + (Boolean.TRUE.equals(thread.get("suspended")) ? "  (suspended)" : ""));
                }
            }
            case "get_process_output" -> processOutput(reply, lines);
            case "start_fixture_http_request" -> {
                lines.add("");
                lines.add(Text.firstNonNull(Json.str(reply.get("method")), "POST") + " " + Json.str(reply.get("url")));
                if (reply.get("bodySent") != null) lines.add("Body sent: " + Redaction.shorten(Json.str(reply.get("bodySent"))));
                lines.add("Request id: " + Json.str(reply.get("requestId")) + " (" + Json.str(reply.get("status")) + ")");
            }
            case "get_fixture_http_request" -> {
                lines.add("");
                if (reply.get("httpStatus") != null) lines.add("HTTP " + Json.str(reply.get("httpStatus")));
                else lines.add("Request " + Json.str(reply.get("status")));
                if (reply.get("body") != null) lines.add("Body: " + Redaction.shorten(Json.str(reply.get("body"))));
                if (reply.get("error") != null) lines.add("Error: " + Redaction.shorten(Json.str(reply.get("error"))));
            }
            default -> {
                String result = resultSummary(a, reply, text);
                if (!result.isEmpty()) {
                    lines.add("");
                    lines.add("Result: " + result);
                }
                if (reply.get("projectLocation") != null) lines.add("Project folder: " + Json.str(reply.get("projectLocation")));
            }
        }
        return readerFirst(lines, technicalEnd, plainResult(tool, reply));
    }

    /**
     * Orders a step's lines for the reader: first what happened (stop location, values, output, HTTP, project
     * folder, or a plain sentence about the result), then {@link ReportEvent#TECHNICAL_DETAILS} and the technical
     * lines: tool name, arguments and the raw "Result:" line.
     */
    static List<String> readerFirst(List<String> lines, int technicalEnd, List<String> plain) {
        List<String> technical = new ArrayList<>(lines.subList(0, technicalEnd));
        List<String> reader = new ArrayList<>();
        for (String line : lines.subList(technicalEnd, lines.size())) {
            if (line.startsWith("Result: ")) technical.add(line);
            else reader.add(line);
        }
        while (!reader.isEmpty() && reader.get(0).isBlank()) reader.remove(0);
        while (!reader.isEmpty() && reader.get(reader.size() - 1).isBlank()) reader.remove(reader.size() - 1);
        List<String> result = new ArrayList<>(plain);
        result.addAll(reader);
        result.add(ReportEvent.TECHNICAL_DETAILS);
        result.addAll(technical);
        return result;
    }

    /** One plain sentence about a result whose raw form says little to a reader ("created=true, owner=agent"). */
    static List<String> plainResult(String tool, Map<String, Object> reply) {
        boolean created = !Boolean.FALSE.equals(reply.get("created"));
        return switch (tool) {
            case "set_breakpoint", "set_conditional_breakpoint", "set_tracepoint", "set_exception_breakpoint" ->
                    reply.isEmpty() ? List.of() : created
                            ? List.of("New breakpoint owned by the agent; it is removed again at cleanup.")
                            : List.of("This breakpoint already existed; the agent leaves it in place.");
            case "start_java_application" -> List.of(Boolean.TRUE.equals(reply.get("reusedSession"))
                    ? "The application was already running in debug mode; the agent attached to it."
                    : Boolean.TRUE.equals(reply.get("createdConfiguration"))
                    ? "Started a new JVM in debug mode with a new launch configuration."
                    : "Started a new JVM in debug mode with the existing launch configuration.");
            case "start_junit_test" -> List.of("Started the test in a new JVM in debug mode.");
            case "start_debug_session" -> List.of(Boolean.TRUE.equals(reply.get("reused"))
                    ? "The launch was already running in debug mode; the agent attached to it."
                    : "Started the saved launch configuration in debug mode.");
            case "restart_debug_session" -> List.of("Restarted the agent's own debug session.");
            default -> List.of();
        };
    }

    private static void stopDetails(Map<String, Object> reply, List<String> lines) {
        lines.add("");
        if (!Boolean.TRUE.equals(reply.get("stopped"))) {
            lines.add("No stop: " + Json.str(reply.get("reason")));
            return;
        }
        lines.add("Thread: " + Json.str(reply.get("threadName")) + " (index " + Json.str(reply.get("threadIndex")) + ")");
        if (reply.get("exceptionType") != null) lines.add("Exception: " + Json.str(reply.get("exceptionType")));
        List<String> frames = new ArrayList<>();
        for (Object f : Json.arr(reply.get("frames")))
            if (!hiddenFrame(Json.obj(f))) frames.add(frame(Json.obj(f)));
        if (!frames.isEmpty()) {
            Object total = reply.get("totalFrames");
            int more = total instanceof Double d ? d.intValue() - frames.size() : 0;
            lines.add("Frames: " + String.join("  <  ", frames) + (more > 0 ? "  <  (" + more + " more)" : ""));
        }
        variables(Json.arr(reply.get("locals")), lines);
        if (reply.get("localsNote") != null) lines.add("Note: " + Json.str(reply.get("localsNote")));
    }

    /** Variables as an aligned three-column table, secrets masked and long values shortened. */
    static void variables(List<Object> items, List<String> lines) {
        if (items.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Variable", "Type", "Value"});
        for (Object item : items) {
            Map<String, Object> v = Json.obj(item);
            String name = Text.firstNonNull(Json.str(v.get("name")), "?");
            if (name.equals(NO_RETURN_VALUE)) continue;
            rows.add(new String[]{name, Text.firstNonNull(simple(Json.str(v.get("type"))), ""),
                    Redaction.value(name, Json.str(v.get("value")))});
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
        if (reply.get("action") != null) lines.add("Action: " + Json.str(reply.get("action")));
        String action = Json.str(reply.get("action"));
        for (String bp : ownBreakpoints.getOrDefault(id, List.of()))
            lines.add(("KEEP_SUSPENDED".equals(action) ? "Kept own breakpoint " : "Removed own breakpoint ") + bp);
        if (reply.get("launchName") != null && Boolean.TRUE.equals(reply.get("ownedSession")))
            lines.add("Own launch: " + launchLabel(Json.str(reply.get("launchName"))));
        if (reply.get("processesEnded") != null)
            lines.add("JVM ended: " + Json.str(reply.get("processesEnded")));
        List<String> steps = new ArrayList<>();
        for (Object step : Json.arr(reply.get("terminationSteps"))) steps.add(render(step));
        if (!steps.isEmpty()) lines.add("Termination: " + Redaction.shorten(String.join(", ", steps)));
        if (reply.get("warning") != null) lines.add("Warning: " + Json.str(reply.get("warning")));
        return lines;
    }

    /** Last lines of each process's output (console first, else stdout and stderr). */
    private static void processOutput(Map<String, Object> reply, List<String> lines) {
        for (Object item : Json.arr(reply.get("processes"))) {
            Map<String, Object> process = Json.obj(item);
            lines.add("");
            String label = Text.firstNonNull(Json.str(process.get("label")), "process");
            int pid = label.indexOf("[pid:");
            lines.add("Process" + (pid >= 0 ? " " + label.substring(pid) : ""));
            String output = Json.str(process.get("console"));
            if (blank(output)) output = Text.firstNonNull(Json.str(process.get("stdout")), "")
                    + Text.firstNonNull(Json.str(process.get("stderr")), "");
            List<String> all = new ArrayList<>();
            for (String line : output.split("\\R")) if (!line.isBlank()) all.add(line);
            if (all.isEmpty()) { lines.add("  (no output)"); continue; }
            int from = Math.max(0, all.size() - OUTPUT_TAIL);
            if (from > 0) lines.add("  ... last " + OUTPUT_TAIL + " of " + all.size() + " lines");
            for (String line : all.subList(from, all.size())) lines.add("  | " + Redaction.shorten(line));
        }
        if (Json.arr(reply.get("processes")).isEmpty()) {
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
        for (Object v : args.values()) shownInArguments.add(Json.str(v));
        List<String> parts = new ArrayList<>();
        for (var e : reply.entrySet()) {
            if (e.getKey().equals("status") || e.getKey().equals("attachedToInvestigation")
                    || e.getKey().equals("projectLocation")) continue;   // the project folder gets its own line
            String value = e.getKey().equals("launchName") ? launchLabel(Json.str(e.getValue())) : render(e.getValue());
            if (shownInArguments.contains(Json.str(e.getValue()))) continue;
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
        return Json.str(value);
    }

    private static String errorText(Map<String, Object> reply, String text) {
        String message = Json.str(reply.get("message"));
        if (message == null) message = Json.str(reply.get("error"));
        return message != null ? message : text == null ? "" : text.strip();
    }

    private static String where(Map<String, Object> a) {
        return simple(Json.str(a.get("typeName"))) + ":" + Json.str(a.get("lineNumber"));
    }

    /**
     * JVM-internal frames without source (hidden lambda and method-handle frames such as
     * "0x000000008d24c000.invokeVirtual:-1" or "Invokers$Holder.invokeExact_MT:-1"): left out of the report and counted.
     */
    static boolean hiddenFrame(Map<String, Object> f) {
        String className = Text.firstNonNull(Json.str(f.get("className")), "");
        String line = Json.str(f.get("lineNumber"));
        return className.startsWith("0x") || "-1".equals(line);
    }

    private static String frame(Map<String, Object> f) {
        return simple(Json.str(f.get("className"))) + "." + Json.str(f.get("methodName"))
                + ":" + Json.str(f.get("lineNumber"));
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
