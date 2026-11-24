package org.learningfromexperience.eclipse.agentdebug.activity;

import java.util.List;

import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * The preview page of the Agent Activity view (OPEN-36, delivery 2): one investigation as a plain, readable list, to
 * check that events arrive and that the embedded browser works. Delivery 4 replaces it with the real view built on
 * the same module (colours, blinking active step, current stop, finish banner).
 *
 * <p>Escaping, the agent's Markdown analysis, and the colours per step kind and status come from the shared
 * {@code report-format} module (delivery 3), so the view shows them exactly like the report.
 *
 * <p>Plain Java: unit-tested without a workbench. Every text from the agent or the program is escaped.
 *
 * @author Ayan Dutta
 */
public final class ActivityPreviewHtml {

    private static final String CSS = """
            body{font:13px/1.45 'Segoe UI',system-ui,sans-serif;margin:0;padding:10px 12px;background:#fff;color:#1f2937;
            overflow-wrap:anywhere}
            h1{font-size:14px;margin:0 0 2px}
            .sub{color:#6b7280;font-size:12px;margin-bottom:8px}
            .banner{padding:6px 10px;border-radius:6px;margin:6px 0 10px;font-weight:600}
            .running{background:#dbeafe;color:#1e40af}
            .done{background:#dcfce7;color:#166534}
            .ended{background:#fef3c7;color:#92400e}
            ol{margin:0;padding-left:22px}
            li{margin:2px 0}
            .st{display:inline-block;min-width:62px;font-size:11px;font-weight:700}
            .kind{font-size:11px}
            .ms{color:#9ca3af;font-size:11px}
            .note{color:#6b7280;font-size:12px;margin-top:10px}
            .analysis{background:#ecf7f0;border-left:5px solid #198754;border-radius:6px;padding:8px 12px}
            """;

    private ActivityPreviewHtml() {
    }

    /** The page for one investigation, or a waiting page when there is none yet. */
    public static String page(ActivityStore.Investigation inv, long droppedEvents) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>")
                .append(CSS).append(ReportFormat.MARKDOWN_CSS).append("</style></head><body>");
        if (inv == null) {
            html.append("<h1>Agent Activity</h1><div class=\"sub\">Waiting for an agent debugging session.</div>")
                    .append("<div class=\"note\">Start a debugging prompt in Claude Code; its steps appear here.</div>");
        } else {
            html.append("<h1>").append(esc(inv.request() == null || inv.request().isBlank()
                    ? "Debugging session" : inv.request())).append("</h1>");
            html.append("<div class=\"sub\">").append(esc(join(" · ", inv.client(), inv.mode() == null ? null
                    : inv.mode() + " mode", inv.startedAt(), "Investigation " + shortId(inv.id())))).append("</div>");
            html.append(banner(inv));
            if (inv.droppedSteps() > 0)
                html.append("<div class=\"note\">").append(inv.droppedSteps()).append(" earlier steps not shown</div>");
            html.append("<ol>");
            for (ActivityStore.Step step : inv.steps()) {
                html.append("<li><span class=\"st ").append(cls(step.status())).append("\" style=\"color:")
                        .append(ReportFormat.statusColour(step.status())).append("\">")
                        .append(esc(step.status())).append("</span> ").append(esc(step.title()))
                        .append(" <span class=\"kind\" style=\"color:").append(ReportFormat.kindColour(step.kind()))
                        .append("\">").append(esc(step.kind())).append("</span>");
                if (step.durationMs() >= 0) html.append(" <span class=\"ms\">").append(step.durationMs()).append(" ms</span>");
                html.append("</li>");
            }
            html.append("</ol>");
            if (inv.conclusion() != null && !inv.conclusion().isBlank())
                html.append("<h1 style=\"margin-top:12px\">From the agent</h1><div class=\"analysis\">")
                        .append(ReportFormat.markdown(inv.conclusion())).append("</div>");
            if (inv.reportFile() != null)
                html.append("<div class=\"note\">Report: ").append(esc(inv.reportFile())).append("</div>");
        }
        if (droppedEvents > 0)
            html.append("<div class=\"note\">").append(droppedEvents).append(" live events were dropped by the MCP server</div>");
        return html.append("</body></html>").toString();
    }

    /** The same content as plain text, for the fallback when no embedded browser is available. */
    public static String text(ActivityStore.Investigation inv, long droppedEvents) {
        StringBuilder out = new StringBuilder();
        if (inv == null) {
            out.append("Waiting for an agent debugging session.\n");
        } else {
            out.append(inv.request() == null || inv.request().isBlank() ? "Debugging session" : inv.request()).append('\n');
            out.append(join(" · ", inv.client(), inv.mode(), inv.startedAt(), "Investigation " + shortId(inv.id()))).append('\n');
            out.append(statusLine(inv)).append("\n\n");
            int n = 1;
            for (ActivityStore.Step step : inv.steps()) {
                out.append(n++).append(". [").append(step.status()).append("] ").append(step.title());
                if (step.durationMs() >= 0) out.append(" (").append(step.durationMs()).append(" ms)");
                out.append('\n');
            }
            if (inv.conclusion() != null && !inv.conclusion().isBlank())
                out.append("\nFrom the agent:\n").append(inv.conclusion()).append('\n');
            if (inv.reportFile() != null) out.append("\nReport: ").append(inv.reportFile()).append('\n');
        }
        if (droppedEvents > 0) out.append('\n').append(droppedEvents).append(" live events were dropped\n");
        return out.toString();
    }

    private static String banner(ActivityStore.Investigation inv) {
        String cls = !inv.finished() ? "running" : "FINISHED".equals(inv.outcome()) ? "done" : "ended";
        return "<div class=\"banner " + cls + "\">" + esc(statusLine(inv)) + "</div>";
    }

    static String statusLine(ActivityStore.Investigation inv) {
        if (!inv.finished()) {
            List<ActivityStore.Step> steps = inv.steps();
            ActivityStore.Step last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
            return last != null && last.running() ? "Running: " + last.title() : "Running";
        }
        return "FINISHED".equals(inv.outcome()) ? "Finished" : "Ended without the agent finishing it";
    }

    private static String cls(String status) {
        return status == null ? "UNKNOWN" : status.replaceAll("[^A-Z]", "");
    }

    private static String shortId(String id) {
        return id == null ? "" : id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String join(String sep, String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (out.length() > 0) out.append(sep);
            out.append(part);
        }
        return out.toString();
    }

    static String esc(String text) {
        return ReportFormat.html(text);
    }
}
