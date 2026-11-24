package org.learningfromexperience.agentdebug.report;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The investigation events as JSON (OPEN-36): one format for the live Agent Activity view in Eclipse (sent by the MCP
 * server's {@code ActivityForwarder} with the bridge command {@code ACTIVITY_EVENT <base64url(JSON)>}) and for the
 * HTML report ({@link HtmlReportWriter}, which feeds its own {@code ActivityStore} with the same events). So the view
 * and the report are built from the same data in the same way.
 *
 * <p>The JSON object has a {@code type} and the event's fields:
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
 * @author Ayan Dutta
 */
public final class ActivityEvents {

    private ActivityEvents() {
    }

    public static String json(ReportEvent.InvestigationStarted e) {
        return new Writer("investigationStarted").field("investigationId", e.investigationId())
                .field("at", e.at().toString()).field("mode", e.mode()).field("client", e.client())
                .field("request", e.request()).flag("requestExact", e.requestExact()).toString();
    }

    public static String json(ReportEvent.StepStarted e) {
        return new Writer("stepStarted").field("investigationId", e.investigationId()).field("at", e.at().toString())
                .field("kind", e.kind().name()).field("title", e.title()).toString();
    }

    public static String json(ReportEvent.StepRecorded e) {
        return new Writer("stepRecorded").field("investigationId", e.investigationId()).field("at", e.at().toString())
                .field("kind", e.kind().name()).field("status", e.status().name()).field("title", e.title())
                .list("details", e.details()).number("durationMs", e.durationMs()).toString();
    }

    public static String json(ReportEvent.InvestigationFinished e) {
        return finished(e.investigationId(), e.at().toString(), e.outcome().name(), e.conclusion(), e.cleanup(),
                e.reportFile() == null ? null : e.reportFile().toString());
    }

    /** An end of an investigation, also for one the report closes itself (the session ended before the finish). */
    public static String finished(String investigationId, String at, String outcome, String conclusion,
                                  List<String> cleanup, String reportFile) {
        return new Writer("investigationFinished").field("investigationId", investigationId).field("at", at)
                .field("outcome", outcome).field("conclusion", conclusion).list("cleanup", cleanup)
                .field("reportFile", reportFile).toString();
    }

    public static String dropped(long count) {
        return new Writer("eventsDropped").number("count", count).toString();
    }

    /** The form {@code ActivityStore.accept} and the bridge command take: base64url of the UTF-8 JSON, no padding. */
    public static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A small JSON object writer: strings escaped, null for absent values. */
    static final class Writer {
        private final StringBuilder out = new StringBuilder("{");

        Writer(String type) {
            field("type", type);
        }

        Writer field(String name, String value) {
            key(name);
            if (value == null) out.append("null");
            else quote(value);
            return this;
        }

        Writer flag(String name, boolean value) {
            key(name);
            out.append(value);
            return this;
        }

        Writer number(String name, long value) {
            key(name);
            out.append(value);
            return this;
        }

        Writer list(String name, List<String> values) {
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
