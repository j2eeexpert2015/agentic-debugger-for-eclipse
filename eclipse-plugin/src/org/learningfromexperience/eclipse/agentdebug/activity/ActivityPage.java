package org.learningfromexperience.eclipse.agentdebug.activity;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.learningfromexperience.agentdebug.report.format.ReportFormat;

/**
 * The page of the Agent Activity view (OPEN-36, deliveries 4 and 5). Plain Java: unit-tested without a workbench.
 *
 * <p><b>Loaded once, then updated in place.</b> {@link #shell()} is the whole page with empty areas and a small
 * script; every change of the investigation becomes one script call built by {@link #update}, which replaces the
 * changing areas only. So the scroll position, an expanded request and opened steps stay as they are, and the timer,
 * the blinking and the time-based warnings run in the page without Eclipse redrawing anything. The header (title,
 * request, info line) is replaced only when another investigation is shown.
 *
 * <p><b>What it shows.</b>
 * <ul>
 *   <li>a short title ("Debugging OrdersApplication", from the launch step as in the report); the request cut to two
 *       lines, a click shows it in full; the local start time;</li>
 *   <li>a toolbar: Follow (keep the running step in sight), Copy as Markdown, Copy analysis, Open report;</li>
 *   <li>a banner: while running blue and pulsing, with what the agent is doing and a running timer; when finished
 *       green, when ended without the agent finishing it amber, with the time taken; below it what the program does
 *       (running, paused at Class.method:line, ended);</li>
 *   <li>warnings: the program ended without a stop; still waiting for a stop after 30 s; no agent activity for
 *       2 minutes (the agent may have stopped or lost its connection); live events dropped;</li>
 *   <li>the current stop: where, the call chain, and the variables, with values that changed since the previous
 *       stop highlighted and their previous value shown;</li>
 *   <li>the steps: a colour bar per kind, the running step blinking blue, refused calls amber; a click on a step
 *       shows what happened at it (values, output, HTTP) and, collapsed, its technical details;</li>
 *   <li>the agent's analysis, rendered from Markdown;</li>
 *   <li>everywhere, {@code Class.method:line}, {@code Class, line N} and {@code Class.java:N} are links that open the
 *       source file at that line in Eclipse (navigation only).</li>
 * </ul>
 * Escaping, Markdown and colours come from the shared {@code report-format} module. Every text from the agent or the
 * program is escaped.
 *
 * @author Ayan Dutta
 */
public final class ActivityPage {

    /** Characters of the request shown before it is expanded. */
    static final int REQUEST_PREVIEW = 180;
    /** Seconds of waiting for a stop before the page warns. */
    static final int WAIT_WARNING_SECONDS = 30;
    /** Seconds without any agent event (and nothing running) before the page warns. */
    static final int IDLE_WARNING_SECONDS = 120;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);

    /**
     * A source location in text: {@code OrderService.quote:24}, {@code OrderService.quote, line 24},
     * {@code OrderService, line 23}, {@code OrderService.java:23}, {@code OrderService.quote line 24}. Group 1 the
     * class (simple or with package), group 2 the line.
     */
    static final Pattern SOURCE = Pattern.compile(
            "\\b((?:[a-z_][a-z0-9_]*\\.)*[A-Z][A-Za-z0-9_$]*)(?:\\.java|\\.[a-z_$<][A-Za-z0-9_$<>]*)?(?::|,? line )(\\d{1,6})\\b");

    private static final String CSS = """
            *{box-sizing:border-box}
            body{font:13px/1.45 'Segoe UI',system-ui,sans-serif;margin:0;padding:8px 10px 14px;background:#fff;
            color:#1f2937;overflow-wrap:anywhere}
            #head h1{font-size:15px;margin:0 0 4px;color:#0b2545}
            .req{margin:0 0 4px;font-size:12.5px;color:#374151}
            .req summary{cursor:pointer;list-style:none;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;
            overflow:hidden}
            .req summary::-webkit-details-marker{display:none}
            .req .lbl{font-weight:600;color:#1d4ed8}
            .req .more{color:#1d4ed8;font-size:12px;white-space:nowrap}
            .req[open] summary{display:block}
            .req[open] .clip{display:none}
            .req .full{white-space:pre-wrap;background:#eef4ff;border-left:4px solid #0d6efd;border-radius:6px;
            padding:6px 10px;margin-top:4px}
            .info{color:#6b7280;font-size:11.5px;margin-bottom:6px}
            #bar{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:2px 0 6px;font-size:12px;color:#374151}
            #bar label{display:flex;align-items:center;gap:4px;cursor:pointer;margin-right:4px}
            #bar button{font:inherit;font-size:11.5px;padding:2px 9px;border:1px solid #cbd5e1;border-radius:5px;
            background:#f8fafc;color:#1f2937;cursor:pointer}
            #bar button:hover{background:#e2e8f0}
            #toast{color:#166534;font-weight:600}
            .banner{display:flex;align-items:center;gap:8px;padding:7px 10px;border-radius:7px;margin:4px 0 0;
            font-weight:600}
            .banner .what{flex:1}
            .banner .timer{font-variant-numeric:tabular-nums;font-weight:600;white-space:nowrap}
            .banner.running{background:#dbeafe;color:#1e40af;animation:ad-glow 1.6s ease-in-out infinite}
            .banner.done{background:#dcfce7;color:#166534}
            .banner.ended{background:#fef3c7;color:#92400e}
            .banner.waiting{background:#f3f4f6;color:#4b5563}
            .prog{font-size:12px;color:#4b5563;margin:3px 2px 0}
            .prog b{color:#1f2937}
            .dot{width:10px;height:10px;border-radius:50%;background:currentColor;flex:none}
            .banner.running .dot{animation:ad-blink 1s ease-in-out infinite}
            @keyframes ad-blink{0%,100%{opacity:1}50%{opacity:.2}}
            @keyframes ad-glow{0%,100%{box-shadow:0 0 0 0 rgba(13,110,253,.35)}50%{box-shadow:0 0 0 4px rgba(13,110,253,.15)}}
            .warn{margin:6px 0 0;padding:6px 10px;border-radius:6px;background:#fff7ed;border-left:4px solid #f59e0b;
            color:#7c2d12;font-size:12.5px}
            #stop:not(:empty){margin:10px 0 0;padding:7px 10px;border-radius:7px;background:#fff7ed;border-left:5px solid #fd7e14}
            #stop h2{margin:0 0 3px;color:#9a3412}
            #stop .where{font-weight:600}
            #stop .frames{font-size:11.5px;color:#6b7280;margin:2px 0 4px}
            table.vars{border-collapse:collapse;font-size:12px;width:100%}
            table.vars th{text-align:left;color:#6b7280;font-weight:600;border-bottom:1px solid #e5e7eb;padding:2px 8px 2px 0}
            table.vars td{padding:2px 8px 2px 0;vertical-align:top;font-family:Consolas,'Cascadia Mono',monospace}
            table.vars tr.changed td{background:#fef08a}
            table.vars .was{color:#92400e;font-size:11px;font-family:'Segoe UI',sans-serif}
            ol{list-style:none;margin:8px 0 0;padding:0}
            li.s{margin:3px 0;border-left:4px solid var(--k);border-radius:4px;background:#f9fafb}
            li.s .row{display:flex;align-items:baseline;gap:8px;padding:4px 8px}
            li.s details>summary{list-style:none;cursor:pointer}
            li.s details>summary::-webkit-details-marker{display:none}
            li.s details>summary .t::before{content:"\\25B8  ";color:#9ca3af}
            li.s details[open]>summary .t::before{content:"\\25BE  "}
            li.s .n{color:#9ca3af;font-size:11px;min-width:16px;text-align:right}
            li.s .st{flex:none;font-size:10.5px;font-weight:700;color:#fff;background:var(--c);border-radius:9px;
            padding:0 7px;min-width:58px;text-align:center}
            li.s .t{flex:1}
            li.s .k{font-size:10.5px;color:var(--k);font-weight:600}
            li.s .ms{font-size:11px;color:#9ca3af;white-space:nowrap}
            li.s .body{padding:2px 10px 8px 34px}
            li.s pre{white-space:pre-wrap;margin:0;font:12px/1.4 Consolas,'Cascadia Mono',monospace;color:#1f2937}
            li.s .tech{margin-top:4px;color:#6b7280}
            li.s .tech summary{cursor:pointer;font-size:11.5px;color:#6b7280;list-style:revert}
            li.s .tech pre{color:#6b7280}
            li.s.run{background:#eff6ff}
            li.s.run .st{animation:ad-blink 1s ease-in-out infinite}
            li.s.run .t{font-weight:600;color:#1e40af}
            li.s.refused{background:#fff8e8}
            li.s.refused .t::after{content:" (refused, the agent retried)";color:#92400e;font-size:11px}
            h2{font-size:13px;margin:14px 0 6px;color:#0b2545}
            .analysis{background:#ecf7f0;border-left:5px solid #198754;border-radius:6px;padding:8px 12px}
            .note{color:#6b7280;font-size:12px;margin-top:8px}
            a.src{color:#1d4ed8;text-decoration:underline dotted;cursor:pointer}
            a.src:hover{text-decoration:underline}
            """;

    private static final String SCRIPT = """
            var ad={id:null,start:0,end:0,last:0,wait:0,running:false,timer:null,follow:true,open:{}};
            function adPad(n){return n<10?'0'+n:''+n;}
            function adClock(ms){var s=Math.max(0,Math.floor(ms/1000));var m=Math.floor(s/60);
              return m>0?m+' min '+adPad(s%60)+' s':s+' s';}
            function adTick(){var now=Date.now();var t=document.getElementById('timer');
              if(t&&ad.start)t.textContent=adClock((ad.end>0?ad.end:now)-ad.start);
              var w=[];
              if(ad.end<=0&&ad.wait>0&&now-ad.wait>%WAIT%*1000)w.push('Still waiting for the program to stop ('
                +adClock(now-ad.wait)+'): the line with the breakpoint may not be reached by this run. The agent decides what to do next.');
              if(ad.end<=0&&!ad.running&&ad.last>0&&now-ad.last>%IDLE%*1000)w.push('No activity from the agent for '
                +adClock(now-ad.last)+'. It may still be thinking, or it may have stopped or lost its connection.');
              var box=document.getElementById('jswarn');var html='';
              for(var i=0;i<w.length;i++)html+='<div class="warn">'+w[i]+'</div>';
              if(box.innerHTML!==html)box.innerHTML=html;}
            function adUpdate(u){
              var newId=u.id!==ad.id;
              if(newId){document.getElementById('head').innerHTML=u.head;ad.id=u.id;ad.open={};}
              var wasRunning=ad.end<=0&&ad.start>0;
              ['tools','banner','warn','stop','steps','analysis','note'].forEach(function(k){
                document.getElementById(k).innerHTML=u[k];});
              var ds=document.querySelectorAll('#steps details[data-i]');
              for(var i=0;i<ds.length;i++)if(ad.open[ds[i].getAttribute('data-i')])ds[i].open=true;
              ad.start=u.start;ad.end=u.end;ad.last=u.last;ad.wait=u.wait;ad.running=u.running;
              adTick();
              if(ad.timer){clearInterval(ad.timer);ad.timer=null;}
              if(ad.start>0&&ad.end<=0)ad.timer=setInterval(adTick,1000);
              if(ad.follow){
                if(wasRunning&&ad.end>0&&!newId){var a=document.getElementById('analysis');if(a.firstChild)a.scrollIntoView({block:'start'});}
                else{var r=document.querySelector('#steps li.run')||document.querySelector('#steps li:last-child');
                  if(r)r.scrollIntoView({block:'nearest'});}}
              return true;}
            document.addEventListener('toggle',function(e){var d=e.target;
              if(!d.getAttribute)return;var i=d.getAttribute('data-i');if(i===null)return;
              if(d.open)ad.open[i]=1;else delete ad.open[i];},true);
            function adCall(name,a,b){
              if(typeof window.adAction!=='function')return adToast('Not available in this view');
              try{adToast(window.adAction(name,a===undefined?'':String(a),b===undefined?'':String(b))||'');}
              catch(x){adToast('Failed: '+x);}
              return false;}
            function adGo(e,cls,line){if(e){e.preventDefault();e.stopPropagation();}return adCall('open',cls,line);}
            function adToast(text){var t=document.getElementById('toast');if(!t)return false;t.textContent=text;
              if(text)setTimeout(function(){if(t.textContent===text)t.textContent='';},2500);return false;}
            """.replace("%WAIT%", Integer.toString(WAIT_WARNING_SECONDS)).replace("%IDLE%", Integer.toString(IDLE_WARNING_SECONDS));

    private ActivityPage() {
    }

    /** The whole page, loaded once; its areas are filled by {@link #update}. */
    public static String shell() {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" + CSS + ReportFormat.MARKDOWN_CSS
                + "</style><script>" + SCRIPT + "</script></head><body>"
                + "<div id=\"head\">" + waitingHead() + "</div>"
                + "<div id=\"bar\"><label title=\"Keep the running step in sight\"><input type=\"checkbox\" id=\"follow\" checked"
                + " onchange=\"ad.follow=this.checked\">Follow</label><span id=\"tools\"></span><span id=\"toast\"></span></div>"
                + "<div id=\"banner\"></div><div id=\"warn\"></div><div id=\"jswarn\"></div><div id=\"stop\"></div>"
                + "<ol id=\"steps\"></ol><div id=\"analysis\"></div><div id=\"note\"></div></body></html>";
    }

    /** The script call that shows this state of the investigation (null: nothing yet). */
    public static String update(ActivityStore.Investigation inv, long droppedEvents, ZoneId zone) {
        String note = droppedEvents > 0
                ? html(droppedEvents + " live events were dropped by the MCP server; some steps may be missing") : "";
        Map<String, String> u = new LinkedHashMap<>();
        if (inv == null) {
            u.put("id", "");
            u.put("head", waitingHead());
            for (String k : List.of("tools", "banner", "warn", "stop", "steps", "analysis")) u.put(k, "");
            u.put("note", note);
            return call(u, 0, 0, 0, 0, false);
        }
        long start = epoch(inv.startedAt());
        long end = inv.finished() ? Math.max(epoch(inv.finishedAt()), start) : 0;
        ActivityStore.Step running = runningStep(inv);
        long wait = running != null && "STOP".equals(running.kind()) ? epoch(running.startedAt()) : 0;
        u.put("id", inv.id());
        u.put("head", head(inv, zone));
        u.put("tools", tools(inv));
        u.put("banner", banner(inv));
        u.put("warn", warnings(inv));
        u.put("stop", stop(inv));
        u.put("steps", steps(inv));
        u.put("analysis", analysis(inv));
        u.put("note", note);
        return call(u, start, end, epoch(inv.lastActivity()), wait, running != null);
    }

    static String head(ActivityStore.Investigation inv, ZoneId zone) {
        StringBuilder out = new StringBuilder("<h1>").append(html(title(inv))).append("</h1>");
        String request = inv.request();
        if (request != null && !request.isBlank()) {
            String flat = request.strip().replaceAll("\\s+", " ");
            boolean longer = flat.length() > REQUEST_PREVIEW;
            String clip = longer ? flat.substring(0, REQUEST_PREVIEW).stripTrailing() + "…" : flat;
            out.append("<details class=\"req\"><summary><span class=\"lbl\">Request: </span><span class=\"clip\">")
                    .append(html(clip)).append(" <span class=\"more\">(click to show all)</span></span></summary>")
                    .append("<div class=\"full\">").append(html(request.strip())).append("</div></details>");
        }
        out.append("<div class=\"info\">").append(html(join(" · ", inv.client(),
                inv.mode() == null ? null : inv.mode() + " mode",
                inv.startedAt() == null ? null : "started " + clock(inv.startedAt(), zone),
                "Investigation " + shortId(inv.id())))).append("</div>");
        return out.toString();
    }

    /** Action buttons; each one calls back into Eclipse ({@code adAction}). */
    static String tools(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder(
                "<button onclick=\"return adCall('copyMarkdown')\" title=\"The whole investigation as Markdown\">Copy as Markdown</button>");
        if (inv.conclusion() != null && !inv.conclusion().isBlank())
            out.append("<button onclick=\"return adCall('copyAnalysis')\">Copy analysis</button>");
        if (inv.reportFile() != null && !inv.reportFile().isBlank())
            out.append("<button onclick=\"return adCall('openReport')\" title=\"").append(html(inv.reportFile()))
                    .append("\">Open report</button>");
        return out.toString();
    }

    static String banner(ActivityStore.Investigation inv) {
        String cls;
        if (!inv.finished()) cls = "running";
        else if ("FINISHED".equals(inv.outcome())) cls = "done";
        else cls = "ended";
        long refused = inv.steps().stream().filter(s -> "FAILED".equals(s.status())).count();
        String extra = refused == 0 ? "" : " · " + refused + (refused == 1 ? " tool call" : " tool calls")
                + " refused, retried by the agent";
        String timer = inv.finished() ? "took <span id=\"timer\"></span>" : "<span id=\"timer\"></span>";
        StringBuilder out = new StringBuilder("<div class=\"banner ").append(cls)
                .append("\"><span class=\"dot\"></span><span class=\"what\">").append(links(html(statusLine(inv) + extra)))
                .append("</span><span class=\"timer\">").append(timer).append("</span></div>");
        String program = programLine(inv);
        if (program != null) out.append("<div class=\"prog\">").append(program).append("</div>");
        return out.toString();
    }

    /** "Program: <b>paused</b> at OrderService.quote:24" (with a link), or null before the launch. */
    static String programLine(ActivityStore.Investigation inv) {
        if (inv.program() == null) return null;
        return switch (inv.program()) {
            case ActivityStore.PROGRAM_RUNNING -> "Program: <b>running</b>";
            case ActivityStore.PROGRAM_PAUSED -> "Program: <b>paused</b>"
                    + (inv.programWhere() == null ? "" : " at " + links(html(inv.programWhere())));
            case ActivityStore.PROGRAM_ENDED -> "Program: <b>ended</b>";
            default -> null;
        };
    }

    /** Warnings that follow from the events (time-based ones are computed in the page). */
    static String warnings(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder();
        if (!inv.finished() && ActivityStore.PROGRAM_ENDED.equals(inv.program()) && !stopped(inv))
            out.append("<div class=\"warn\">The program ended without stopping at a breakpoint. The line with the ")
                    .append("breakpoint may not have been reached, or the project may not have been built.</div>");
        return out.toString();
    }

    /** The current (or, when finished, the last) stop: where, the call chain, the variables with changes marked. */
    static String stop(ActivityStore.Investigation inv) {
        Stop stop = currentStop(inv);
        if (stop == null) return "";
        StringBuilder out = new StringBuilder("<h2>").append(inv.finished() ? "Last stop" : "Current stop").append("</h2>");
        out.append("<div class=\"where\">").append(links(html(stop.where()))).append("</div>");
        if (stop.frames() != null)
            out.append("<div class=\"frames\">").append(links(html(stop.frames()))).append("</div>");
        if (!stop.variables().isEmpty()) {
            out.append("<table class=\"vars\"><tr><th>Variable</th><th>Type</th><th>Value</th></tr>");
            for (Variable v : stop.variables()) {
                out.append(v.changed() ? "<tr class=\"changed\">" : "<tr>")
                        .append("<td>").append(html(v.name())).append("</td><td>").append(html(v.type()))
                        .append("</td><td>").append(html(v.value()));
                if (v.changed())
                    out.append(" <span class=\"was\">(was ").append(html(v.previous())).append(")</span>");
                out.append("</td></tr>");
            }
            out.append("</table>");
        }
        return out.toString();
    }

    static String steps(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder();
        int n = inv.droppedSteps();
        if (n > 0) out.append("<li class=\"note\">").append(n).append(" earlier steps not shown</li>");
        for (ActivityStore.Step step : inv.steps()) {
            n++;
            String status = step.status() == null ? "UNKNOWN" : step.status();
            String cls = step.running() ? " run" : "FAILED".equals(status) ? " refused" : "";
            StringBuilder row = new StringBuilder()
                    .append("<span class=\"n\">").append(n).append("</span>")
                    .append("<span class=\"st\">").append(html(label(status))).append("</span>")
                    .append("<span class=\"t\">").append(links(html(step.title()))).append("</span>")
                    .append("<span class=\"k\">").append(html(step.kind() == null ? "" : step.kind())).append("</span>");
            if (step.durationMs() >= 0)
                row.append("<span class=\"ms\">").append(duration(step.durationMs())).append("</span>");
            out.append("<li class=\"s").append(cls).append("\" style=\"--k:").append(ReportFormat.kindColour(step.kind()))
                    .append(";--c:").append(ReportFormat.statusColour(status)).append("\">");
            String body = stepBody(step);
            if (body.isEmpty()) {
                out.append("<div class=\"row\">").append(row).append("</div>");
            } else {
                out.append("<details data-i=\"").append(n).append("\"><summary class=\"row\">").append(row)
                        .append("</summary><div class=\"body\">").append(body).append("</div></details>");
            }
            out.append("</li>");
        }
        return out.toString();
    }

    /** What happened at a step (reader lines), then its technical details collapsed; empty when it has none. */
    static String stepBody(ActivityStore.Step step) {
        ReportFormat.Details details = ReportFormat.split(step.details());
        StringBuilder out = new StringBuilder();
        List<String> reader = trimBlank(details.reader());
        if (!reader.isEmpty()) out.append("<pre>").append(links(html(String.join("\n", reader)))).append("</pre>");
        List<String> technical = trimBlank(details.technical());
        if (!technical.isEmpty())
            out.append("<details class=\"tech\"><summary>Technical details</summary><pre>")
                    .append(links(html(String.join("\n", technical)))).append("</pre></details>");
        return out.toString();
    }

    static String analysis(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder();
        if (inv.conclusion() != null && !inv.conclusion().isBlank())
            out.append("<h2>From the agent</h2><div class=\"analysis\">")
                    .append(links(ReportFormat.markdown(inv.conclusion()))).append("</div>");
        if (inv.reportFile() != null)
            out.append("<div class=\"note\">Report: ").append(html(inv.reportFile())).append("</div>");
        return out.toString();
    }

    /** What the banner says. */
    static String statusLine(ActivityStore.Investigation inv) {
        if (inv.finished()) return "FINISHED".equals(inv.outcome()) ? "Finished" : "Ended without the agent finishing it";
        ActivityStore.Step running = runningStep(inv);
        if (running != null) return "Running: " + running.title();
        if (inv.steps().isEmpty()) return "Investigation started";
        if (ActivityStore.PROGRAM_PAUSED.equals(inv.program()))
            return "The agent is looking at the paused program";
        return "The agent is thinking";
    }

    /** "Debugging OrdersApplication" from the first launch step, as in the report; "Debugging session" before it. */
    static String title(ActivityStore.Investigation inv) {
        for (ActivityStore.Step step : inv.steps()) {
            if (!"LAUNCH".equals(step.kind()) || step.running() || step.title() == null) continue;
            int colon = step.title().lastIndexOf(": ");
            if (colon > 0 && colon + 2 < step.title().length()) return "Debugging " + step.title().substring(colon + 2).strip();
        }
        return "Debugging session";
    }

    // ---- current stop -------------------------------------------------------------------------------------------

    /** One variable at the current stop; {@code previous} is its value at the stop before, when it changed. */
    public record Variable(String name, String type, String value, String previous) {
        public boolean changed() {
            return previous != null;
        }
    }

    /** The current stop: the stop step's title, its call chain (may be null) and the variables last read there. */
    public record Stop(String where, String frames, List<Variable> variables) {
    }

    /**
     * The latest stop of the investigation ("Stopped at breakpoint: OrderService.quote, line 24" or "Stopped after
     * step: ..."), with the variables of the latest variables table at or after it (the stop itself, or a later
     * read of the variables). A value is marked changed when an earlier table had the same variable with another
     * value. Null when the program has not stopped yet.
     */
    public static Stop currentStop(ActivityStore.Investigation inv) {
        List<ActivityStore.Step> steps = inv.steps();
        int stopAt = -1;
        for (int i = steps.size() - 1; i >= 0; i--) {
            ActivityStore.Step s = steps.get(i);
            if ("STOP".equals(s.kind()) && "OK".equals(s.status()) && s.title() != null && s.title().startsWith("Stopped")) {
                stopAt = i;
                break;
            }
        }
        if (stopAt < 0) return null;
        String frames = null;
        for (String line : ReportFormat.split(steps.get(stopAt).details()).reader())
            if (line.startsWith("Frames: ")) frames = line.substring("Frames: ".length());
        // The latest table at or after the stop, and the latest table before that one.
        List<Variable> current = List.of();
        int currentAt = -1;
        for (int i = steps.size() - 1; i >= stopAt; i--) {
            List<Variable> table = variables(steps.get(i));
            if (!table.isEmpty()) {
                current = table;
                currentAt = i;
                break;
            }
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (int i = currentAt - 1; i >= 0 && currentAt >= 0; i--) {
            List<Variable> table = variables(steps.get(i));
            if (table.isEmpty()) continue;
            for (Variable v : table) before.put(v.name(), v.value());
            break;
        }
        List<Variable> marked = new ArrayList<>();
        for (Variable v : current) {
            String was = before.get(v.name());
            marked.add(new Variable(v.name(), v.type(), v.value(), was != null && !was.equals(v.value()) ? was : null));
        }
        return new Stop(steps.get(stopAt).title(), frames, List.copyOf(marked));
    }

    /**
     * The variables table in a step's reader lines, as the MCP server writes it: a header line
     * {@code Variable    Type    Value}, a line of dashes, then one aligned row per variable. Empty when there is none.
     */
    static List<Variable> variables(ActivityStore.Step step) {
        List<String> lines = ReportFormat.split(step.details()).reader();
        List<Variable> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String header = lines.get(i);
            if (!header.startsWith("Variable ")) continue;
            int type = header.indexOf("Type");
            int value = header.indexOf("Value", Math.max(type, 0));
            if (type < 4 || value < type + 8) continue;
            for (int r = i + 2; r < lines.size(); r++) {
                String row = lines.get(r);
                // A row has the column gaps where the header has them; anything else ("Note: ...") ends the table.
                if (row.isBlank() || row.length() < value || !row.substring(type - 4, type).isBlank()
                        || !row.substring(value - 4, value).isBlank()) break;
                out.add(new Variable(row.substring(0, type).strip(), row.substring(type, value).strip(),
                        row.substring(value), null));
            }
            break;
        }
        return out;
    }

    private static boolean stopped(ActivityStore.Investigation inv) {
        for (ActivityStore.Step s : inv.steps())
            if ("STOP".equals(s.kind()) && "OK".equals(s.status()) && s.title() != null && s.title().startsWith("Stopped"))
                return true;
        return false;
    }

    private static ActivityStore.Step runningStep(ActivityStore.Investigation inv) {
        List<ActivityStore.Step> steps = inv.steps();
        ActivityStore.Step last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        return last != null && last.running() && !inv.finished() ? last : null;
    }

    // ---- links to source --------------------------------------------------------------------------------------

    /**
     * Turns source locations in escaped HTML text into links that open the file at that line
     * ({@code adGo(event, 'OrderService', 24)}). Only text is changed: tags and their attributes are left alone.
     */
    static String links(String escapedHtml) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < escapedHtml.length()) {
            int tag = escapedHtml.indexOf('<', i);
            String text = tag < 0 ? escapedHtml.substring(i) : escapedHtml.substring(i, tag);
            Matcher m = SOURCE.matcher(text);
            int last = 0;
            while (m.find()) {
                out.append(text, last, m.start()).append("<a class=\"src\" href=\"#\" title=\"Open in the editor\" onclick=\"return adGo(event,'")
                        .append(m.group(1)).append("',").append(m.group(2)).append(")\">").append(m.group()).append("</a>");
                last = m.end();
            }
            out.append(text.substring(last));
            if (tag < 0) break;
            int close = escapedHtml.indexOf('>', tag);
            if (close < 0) {
                out.append(escapedHtml.substring(tag));
                break;
            }
            String element = escapedHtml.substring(tag, close + 1);
            out.append(element);
            i = close + 1;
            // Never link inside an existing link.
            if (element.startsWith("<a ")) {
                int end = escapedHtml.indexOf("</a>", i);
                if (end < 0) end = escapedHtml.length();
                out.append(escapedHtml, i, end);
                i = end;
            }
        }
        return out.toString();
    }

    // ---- Markdown export and text fallback -----------------------------------------------------------------

    /** The whole investigation as Markdown, for "Copy as Markdown". */
    public static String markdown(ActivityStore.Investigation inv, ZoneId zone) {
        StringBuilder out = new StringBuilder("# ").append(title(inv)).append("\n\n");
        if (inv.request() != null && !inv.request().isBlank())
            out.append("**Request:** ").append(inv.request().strip()).append("\n\n");
        out.append("**Status:** ").append(statusLine(inv));
        long took = epoch(inv.finishedAt()) - epoch(inv.startedAt());
        if (inv.finished() && epoch(inv.startedAt()) > 0 && took >= 0) out.append(" in ").append(duration(took));
        out.append("  \n**Investigation:** ").append(shortId(inv.id())).append(" · ")
                .append(join(" · ", inv.client(), inv.mode() == null ? null : inv.mode() + " mode",
                        inv.startedAt() == null ? null : "started " + clock(inv.startedAt(), zone))).append("\n\n");
        out.append("## Steps\n\n");
        int n = inv.droppedSteps();
        for (ActivityStore.Step step : inv.steps()) {
            out.append(++n).append(". **").append(label(step.status())).append("** ").append(step.title());
            if (step.durationMs() >= 0) out.append(" (").append(duration(step.durationMs())).append(')');
            out.append('\n');
        }
        Stop stop = currentStop(inv);
        if (stop != null && !stop.variables().isEmpty()) {
            out.append("\n## ").append(inv.finished() ? "Last stop" : "Current stop").append(": ").append(stop.where())
                    .append("\n\n| Variable | Type | Value |\n|---|---|---|\n");
            for (Variable v : stop.variables())
                out.append("| ").append(cell(v.name())).append(" | ").append(cell(v.type())).append(" | ")
                        .append(cell(v.value())).append(v.changed() ? " (was " + cell(v.previous()) + ")" : "").append(" |\n");
        }
        if (inv.conclusion() != null && !inv.conclusion().isBlank())
            out.append("\n## From the agent\n\n").append(inv.conclusion().strip()).append('\n');
        if (inv.reportFile() != null) out.append("\nReport: ").append(inv.reportFile()).append('\n');
        return out.toString();
    }

    /** Text of the notification at the end: what was debugged, how long it took, the analysis's first line. */
    public static String notification(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder(title(inv));
        long took = epoch(inv.finishedAt()) - epoch(inv.startedAt());
        if (epoch(inv.startedAt()) > 0 && took >= 0) out.append(" · took ").append(duration(took));
        String first = firstLine(inv.conclusion());
        if (first != null) out.append("\n").append(first.length() > 140 ? first.substring(0, 140).stripTrailing() + "…" : first);
        out.append("\nDetails in the Agent Activity view.");
        return out.toString();
    }

    /** The first line of the analysis with content, headings skipped and Markdown marks removed. */
    static String firstLine(String markdown) {
        if (markdown == null) return null;
        for (String line : markdown.split("\\R")) {
            if (line.strip().startsWith("#") || line.strip().startsWith("```")) continue;
            String plain = line.replaceFirst("^\\s*([-*]|\\d+[.)])\\s+", "").replace("**", "").replace("`", "").strip();
            if (!plain.isEmpty() && !plain.matches("[|:\\-\\s]+")) return plain;
        }
        return null;
    }

    /** One line for the status bar: "Agent: running · 45 s", "Agent: finished · 1 min 05 s", or null when idle. */
    public static String statusBar(ActivityStore.Investigation inv, long nowMillis) {
        if (inv == null) return null;
        long start = epoch(inv.startedAt());
        long end = inv.finished() ? epoch(inv.finishedAt()) : nowMillis;
        String time = start > 0 && end >= start ? " · " + duration((end - start) / 1000 * 1000) : "";
        if (!inv.finished()) {
            ActivityStore.Step running = runningStep(inv);
            String doing = running != null && "STOP".equals(running.kind()) ? "waiting for a stop"
                    : ActivityStore.PROGRAM_PAUSED.equals(inv.program()) ? "paused" : "running";
            return "Agent: " + doing + time;
        }
        return ("FINISHED".equals(inv.outcome()) ? "Agent: finished" : "Agent: ended without finish") + time;
    }

    /** The same content as plain text, for the fallback when no embedded browser is available. */
    public static String text(ActivityStore.Investigation inv, long droppedEvents, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        if (inv == null) out.append("Waiting for an agent debugging session.\n");
        else out.append(markdown(inv, zone));
        if (droppedEvents > 0) out.append('\n').append(droppedEvents).append(" live events were dropped\n");
        return out.toString();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static String waitingHead() {
        return "<h1>Agent Activity</h1><div class=\"banner waiting\"><span class=\"dot\"></span><span class=\"what\">"
                + "Waiting for an agent debugging session. Start a debugging prompt in Claude Code; its steps appear here."
                + "</span></div>";
    }

    private static String label(String status) {
        if (status == null) return "UNKNOWN";
        return "FAILED".equals(status) ? "REFUSED" : status;
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|");
    }

    private static List<String> trimBlank(List<String> lines) {
        int from = 0, to = lines.size();
        while (from < to && lines.get(from).isBlank()) from++;
        while (to > from && lines.get(to - 1).isBlank()) to--;
        return lines.subList(from, to);
    }

    static String duration(long ms) {
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        if (s < 60) return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        return s / 60 + " min " + String.format(Locale.ROOT, "%02d", s % 60) + " s";
    }

    static String clock(String isoInstant, ZoneId zone) {
        try {
            return CLOCK.format(Instant.parse(isoInstant).atZone(zone));
        } catch (RuntimeException unparsable) {
            return isoInstant;
        }
    }

    private static long epoch(String isoInstant) {
        try {
            return isoInstant == null ? 0 : Instant.parse(isoInstant).toEpochMilli();
        } catch (RuntimeException unparsable) {
            return 0;
        }
    }

    private static String call(Map<String, String> parts, long start, long end, long last, long wait, boolean running) {
        StringBuilder out = new StringBuilder("adUpdate({");
        parts.forEach((k, v) -> out.append(k).append(':').append(js(v)).append(','));
        return out.append("start:").append(start).append(",end:").append(end).append(",last:").append(last)
                .append(",wait:").append(wait).append(",running:").append(running).append("});").toString();
    }

    /** A JavaScript string literal. */
    static String js(String text) {
        StringBuilder out = new StringBuilder("'");
        for (char c : (text == null ? "" : text).toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '<' -> out.append("\\x3c");
                case ' ' -> out.append("\\u2028");
                case ' ' -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        return out.append('\'').toString();
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

    private static String html(String text) {
        return ReportFormat.html(text);
    }
}
