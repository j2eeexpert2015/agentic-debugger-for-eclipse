package org.learningfromexperience.eclipse.agentdebug.mcp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.learningfromexperience.agentdebug.report.DebugReportListener;
import org.learningfromexperience.agentdebug.report.ReportEvent;

/**
 * Sends the investigation events to the Eclipse plugin for the live <b>Agent Activity</b> view (OPEN-36).
 *
 * <p><b>Decoupled like the report.</b> It is a {@link DebugReportListener} next to the HTML report writer, fed with the
 * same events by its own {@code AsyncReportRecorder}: its own queue and thread, so a slow or absent Eclipse view never
 * delays or changes a tool result, and it knows nothing of the report (ADR-009). Switched off with
 * {@code AGENTDEBUG_LIVE=off}, independently of {@code AGENTDEBUG_REPORT}.
 *
 * <p><b>Wire format.</b> One bridge command per event: {@code ACTIVITY_EVENT <base64url(JSON)>}, authenticated like
 * every bridge command. The JSON object has a {@code type} and the event's fields:
 * <ul>
 *   <li>{@code investigationStarted}: investigationId, at, mode, client, request, requestExact;</li>
 *   <li>{@code stepStarted}: investigationId, at, kind, title;</li>
 *   <li>{@code stepRecorded}: investigationId, at, kind, status, title, details (array), durationMs;</li>
 *   <li>{@code investigationFinished}: investigationId, at, outcome, conclusion, cleanup (array), reportFile;</li>
 *   <li>{@code eventsDropped}: count.</li>
 * </ul>
 * {@code at} is an ISO-8601 instant; absent values are JSON null. Details use the report's convention: the line
 * {@link ReportEvent#TECHNICAL_DETAILS} separates what a reader needs from tool, arguments and raw result.
 *
 * <p><b>Older plugins.</b> A plugin without the Agent Activity view answers "Unknown command": the forwarder then
 * stops sending for the rest of the session and says so once on stderr. When Eclipse cannot be reached the event is
 * dropped (said once); debugging itself reports that problem.
 *
 * @author Ayan Dutta
 */
final class ActivityForwarder implements DebugReportListener {

    /** Sends one bridge command and returns Eclipse's one-line reply. */
    @FunctionalInterface
    interface Bridge {
        String send(String command) throws IOException;
    }

    static final String COMMAND = "ACTIVITY_EVENT";
    /** Events larger than this are not sent (a bridge line must stay small); the view then misses one step. */
    static final int MAX_JSON_CHARS = 64_000;

    private final Bridge bridge;
    private final PrintStream log;
    private boolean unsupported;
    private boolean unreachableSaid;

    ActivityForwarder(Bridge bridge, PrintStream log) {
        this.bridge = bridge;
        this.log = log;
    }

    @Override
    public void investigationStarted(ReportEvent.InvestigationStarted e) {
        send(new Json("investigationStarted").field("investigationId", e.investigationId()).field("at", e.at().toString())
                .field("mode", e.mode()).field("client", e.client()).field("request", e.request())
                .flag("requestExact", e.requestExact()));
    }

    @Override
    public void stepStarted(ReportEvent.StepStarted e) {
        send(new Json("stepStarted").field("investigationId", e.investigationId()).field("at", e.at().toString())
                .field("kind", e.kind().name()).field("title", e.title()));
    }

    @Override
    public void stepRecorded(ReportEvent.StepRecorded e) {
        send(new Json("stepRecorded").field("investigationId", e.investigationId()).field("at", e.at().toString())
                .field("kind", e.kind().name()).field("status", e.status().name()).field("title", e.title())
                .list("details", e.details()).number("durationMs", e.durationMs()));
    }

    @Override
    public void investigationFinished(ReportEvent.InvestigationFinished e) {
        send(new Json("investigationFinished").field("investigationId", e.investigationId()).field("at", e.at().toString())
                .field("outcome", e.outcome().name()).field("conclusion", e.conclusion()).list("cleanup", e.cleanup())
                .field("reportFile", e.reportFile() == null ? null : e.reportFile().toString()));
    }

    @Override
    public void eventsDropped(long count) {
        send(new Json("eventsDropped").number("count", count));
    }

    /** True once Eclipse answered that it has no Agent Activity view (for tests and diagnostics). */
    boolean unsupported() {
        return unsupported;
    }

    private void send(Json json) {
        if (unsupported) return;
        String text = json.toString();
        if (text.length() > MAX_JSON_CHARS) return;
        String command = COMMAND + " " + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        try {
            String reply = bridge.send(command);
            if (reply != null && reply.contains("Unknown command")) {
                unsupported = true;
                log.println("[Eclipse Agentic Debugger] The Eclipse plugin has no Agent Activity view yet; "
                        + "live events are off for this session (update the plugin)");
            }
        } catch (IOException unreachable) {
            if (!unreachableSaid) {
                unreachableSaid = true;
                log.println("[Eclipse Agentic Debugger] Agent Activity: Eclipse not reachable, event dropped ("
                        + unreachable.getMessage() + ")");
            }
        }
    }

    /** A small JSON object writer: strings escaped, null for absent values. */
    static final class Json {
        private final StringBuilder out = new StringBuilder("{");

        Json(String type) {
            field("type", type);
        }

        Json field(String name, String value) {
            key(name);
            if (value == null) out.append("null");
            else quote(value);
            return this;
        }

        Json flag(String name, boolean value) {
            key(name);
            out.append(value);
            return this;
        }

        Json number(String name, long value) {
            key(name);
            out.append(value);
            return this;
        }

        Json list(String name, List<String> values) {
            key(name);
            if (values == null) {
                out.append("null");
                return this;
            }
            out.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) out.append(',');
                if (values.get(i) == null) out.append("null");
                else quote(values.get(i));
            }
            out.append(']');
            return this;
        }

        private void key(String name) {
            if (out.length() > 1) out.append(',');
            quote(name);
            out.append(':');
        }

        private void quote(String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }

        @Override
        public String toString() {
            return out + "}";
        }
    }
}
