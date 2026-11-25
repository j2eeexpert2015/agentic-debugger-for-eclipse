package org.learningfromexperience.agentdebug.report.activity;

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
 * The page of the Agent Activity view (OPEN-36, deliveries 4 and 5), and the same page as the debugging session
 * report ({@link #report}, OPEN-38 step 4: ChainTest replaced). Plain Java: unit-tested without a workbench.
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
 *   <li>pinned at the top while the page scrolls: a toolbar (Follow: keep the newest step in sight, pausing while the
 *       user scrolls up, with a "Newest" button to resume; Expand All; Collapse All; Open HTML Report, the same
 *       investigation as the standalone report file, in the browser) and the status banner;</li>
 *   <li>a banner: while running blue, breathing, with a pulsing red "live" dot, with what the agent is doing and a running timer; when finished
 *       green, when ended without the agent finishing it amber, with the time taken; below it what the program does
 *       (running, paused at Class.method:line, ended);</li>
 *   <li>warnings: the program ended without a stop; still waiting for a stop after 30 s; no agent activity for
 *       2 minutes (the agent may have stopped or lost its connection); live events dropped;</li>
 *   <li>the current stop, after the steps (the page reads in time order): where, the call chain, and the variables,
 *       with values that changed since the previous stop highlighted and their previous value shown; folded to one
 *       line ("Last stop: ...") once the investigation finished;</li>
 *   <li>the steps: a colour bar per kind, the running step red with a pulsing dot and a breathing background, done
 *       steps green, refused calls amber; a click on a step
 *       shows what happened at it (values, output, HTTP) and, collapsed, its additional details (tool, arguments,
 *       raw result); steps are expanded by default, also while they arrive; Expand All / Collapse All open or close
 *       every step at once, and a click on one step keeps that step as the user left it;</li>
 *   <li>the agent's analysis, rendered from Markdown;</li>
 *   <li>everywhere, {@code Class.method:line}, {@code Class, line N} and {@code Class.java:N} are links that open the
 *       source file at that line in Eclipse (navigation only).</li>
 * </ul>
 * Escaping, Markdown and colours come from {@link ReportFormat} in the same module. Every text from the agent or the
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
            #pin{position:sticky;top:0;z-index:5;background:#fff;margin:-8px -10px 6px;padding:6px 10px 6px;
            box-shadow:0 2px 4px rgba(0,0,0,.06)}
            #bar{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:0 0 5px;font-size:12px;color:#374151}
            #jump{display:none;position:fixed;right:14px;bottom:12px;z-index:6;background:#1d4ed8;color:#fff;border-radius:14px;
            padding:4px 12px;font-size:12px;font-weight:600;text-decoration:none;box-shadow:0 2px 6px rgba(0,0,0,.25)}
            #bar label{display:flex;align-items:center;gap:4px;cursor:pointer;margin-right:4px}
            #bar button{font:inherit;font-size:11.5px;padding:2px 9px;border:1px solid #cbd5e1;border-radius:5px;
            background:#f8fafc;color:#1f2937;cursor:pointer}
            #bar button:hover{background:#e2e8f0}
            #toast{color:#166534;font-weight:600}
            .banner{display:flex;align-items:center;gap:8px;padding:5px 10px;border-radius:7px;margin:0;
            font-weight:600}
            .banner .what{flex:1}
            .banner .timer{font-variant-numeric:tabular-nums;font-weight:600;white-space:nowrap}
            .banner.running{background:#dbeafe;color:#1e40af;
            animation:ad-breathe 1.4s ease-in-out infinite,ad-glow 1.4s ease-in-out infinite}
            .banner.running .what{animation:ad-text 1.4s ease-in-out infinite}
            @keyframes ad-breathe{0%,100%{background:#dbeafe}50%{background:#bfdbfe}}
            @keyframes ad-text{0%,100%{opacity:1}50%{opacity:.55}}
            .live{position:relative;width:12px;height:12px;flex:none;align-self:center}
            .live::before,.live::after{content:"";position:absolute;inset:0;border-radius:50%;background:#dc3545}
            .live::after{animation:ad-ripple 1.2s ease-out infinite}
            @keyframes ad-ripple{0%{transform:scale(1);opacity:.75}100%{transform:scale(2.8);opacity:0}}
            .live::before{animation:ad-blink 1s ease-in-out infinite}
            .banner.done{background:#dcfce7;color:#166534}
            .banner.ended{background:#fef3c7;color:#92400e}
            .banner.waiting{background:#f3f4f6;color:#4b5563}
            .prog{font-size:11.5px;color:#4b5563;margin:2px 2px 0}
            .prog b{color:#1f2937}
            .dot{width:10px;height:10px;border-radius:50%;background:currentColor;flex:none}
            .banner.running .dot{animation:ad-blink 1s ease-in-out infinite}
            @keyframes ad-blink{0%,100%{opacity:1}50%{opacity:.2}}
            @keyframes ad-glow{0%,100%{box-shadow:0 0 0 0 rgba(13,110,253,.35)}50%{box-shadow:0 0 0 4px rgba(13,110,253,.15)}}
            .warn{margin:6px 0 0;padding:6px 10px;border-radius:6px;background:#fff7ed;border-left:4px solid #f59e0b;
            color:#7c2d12;font-size:12.5px}
            #stop:not(:empty){margin:8px 0 0;padding:7px 10px;border-radius:7px;background:#fff7ed;border-left:5px solid #fd7e14}
            #stop h2{margin:0;color:#9a3412;display:inline}
            #stop details.stopbox>summary{list-style:none;cursor:pointer}
            #stop details.stopbox>summary::-webkit-details-marker{display:none}
            #stop details.stopbox>summary::after{content:"  \\25B8";color:#9a3412}
            #stop details.stopbox[open]>summary::after{content:"  \\25BE"}
            #stop details.stopbox[open]>summary{margin-bottom:3px}
            #stop .where{font-size:12px;color:#6b7280}
            #stop .frames{font-size:11.5px;color:#6b7280;margin:2px 0 4px}
            #stop .frames details.more{display:inline}
            #stop .frames details.more summary{display:inline;cursor:pointer;color:#6b7280;text-decoration:underline dotted}
            #stop .frames details.more[open] summary{display:none}
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
            li.s.run{background:#fef2f2;animation:ad-breathe-red 1.2s ease-in-out infinite}
            @keyframes ad-breathe-red{0%,100%{background:#fef2f2}50%{background:#fecaca}}
            li.s.run .st{animation:ad-blink 1s ease-in-out infinite}
            li.s.run .t{font-weight:600;color:#b91c1c}
            li.s.refused{background:#fff8e8}
            li.s.refused .t::after{content:" (refused, the agent retried)";color:#92400e;font-size:11px}
            h2{font-size:13px;margin:14px 0 6px;color:#0b2545}
            .analysis{background:#ecf7f0;border-left:5px solid #198754;border-radius:6px;padding:8px 12px}
            .note{color:#6b7280;font-size:12px;margin-top:8px}
            a.src{color:#1d4ed8;text-decoration:underline dotted;cursor:pointer}
            a.src:hover{text-decoration:underline}
            """;

    private static final String SCRIPT = """
            var ad={id:null,start:0,end:0,last:0,wait:0,running:false,timer:null,follow:true,paused:false,all:true,state:{}};
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
            // Open or closed: the user's own click on that step wins; otherwise the server's default for it (data-d,
            // the stop panel), otherwise Expand All / Collapse All (steps are expanded by default).
            function adApply(){var ds=document.querySelectorAll('details[data-i]');
              for(var i=0;i<ds.length;i++){var d=ds[i],k=d.getAttribute('data-i'),def=d.getAttribute('data-d');
                var want=(k in ad.state)?ad.state[k]:(def!==null?def==='1':ad.all);if(d.open!==want)d.open=want;}}
            function adSteps(on){ad.all=on;var st={};for(var k in ad.state)if(k==='stop'||k.charAt(0)==='f')st[k]=ad.state[k];
              ad.state=st;adApply();return false;}
            document.addEventListener('click',function(e){var s=e.target.closest?e.target.closest('summary'):null;if(!s)return;
              var d=s.parentElement;if(!d||!d.getAttribute)return;var k=d.getAttribute('data-i');if(k===null)return;
              ad.state[k]=!d.open;});
            function adAtBottom(){return window.innerHeight+window.scrollY>=document.documentElement.scrollHeight-30;}
            function adFollow(){if(!ad.follow||ad.paused)return;
              requestAnimationFrame(function(){requestAnimationFrame(function(){
                ad.scrolling=true;window.scrollTo(0,document.documentElement.scrollHeight);
                setTimeout(function(){ad.scrolling=false;},50);});});}
            window.addEventListener('scroll',function(){if(ad.scrolling)return;
              ad.paused=!adAtBottom();document.getElementById('jump').style.display=ad.paused&&ad.follow?'block':'none';});
            function adJump(){ad.paused=false;document.getElementById('jump').style.display='none';adFollow();return false;}
            function adUpdate(u){
              var newId=u.id!==ad.id;
              if(newId){document.getElementById('head').innerHTML=u.head;ad.id=u.id;ad.state={};ad.all=true;ad.paused=false;}
              else{var h=document.querySelector('#head h1');if(h&&h.innerHTML!==u.title)h.innerHTML=u.title;}
              ['tools','banner','warn','stop','steps','flow','analysis','note'].forEach(function(k){
                document.getElementById(k).innerHTML=u[k];});
              adApply();
              ad.start=u.start;ad.end=u.end;ad.last=u.last;ad.wait=u.wait;ad.running=u.running;
              adTick();
              if(ad.timer){clearInterval(ad.timer);ad.timer=null;}
              if(ad.start>0&&ad.end<=0)ad.timer=setInterval(adTick,1000);
              // Follow: the page reads in time order, so the newest step, the current stop and at the end the
              // analysis are at the bottom; scrolled there once the browser has laid the new content out.
              adFollow();
              return true;}
            // Flow (default) or Steps: what the page shows; kept while the page lives.
            function adView(steps){document.body.classList.toggle('vsteps',steps);
              document.getElementById('vflow').classList.toggle('on',!steps);
              document.getElementById('vsteps').classList.toggle('on',steps);adFollow();return false;}
            function adTheme(){var d=!document.body.classList.contains('dark');document.body.classList.toggle('dark',d);
              var b=document.getElementById('theme');if(b)b.textContent=d?'Light':'Dark';
              if(typeof window.adAction==='function'){try{window.adAction('theme',d?'dark':'light','');}catch(x){}}
              return false;}
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

    /**
     * The whole page, loaded once; its areas are filled by {@link #update}. In time order from top to bottom:
     * header, steps (oldest first), the current stop, warnings, the agent's analysis. Above them, pinned so it stays in sight
     * while the page scrolls: the toolbar and the status banner (Running with the live dot and timer, Finished).
     */
    public static String shell() {
        return shell(false);
    }

    /**
     * The page in the dark or the light look ({@code dark}); the Dark / Light button switches it in place and tells
     * Eclipse ({@code adAction('theme', 'dark'|'light')}), which keeps the choice for the next time the page loads.
     */
    public static String shell(boolean dark) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" + CSS + ReportFormat.MARKDOWN_CSS
                + FlowPage.CSS + FlowPage.LIVE_CSS + FlowPage.DARK_CSS + "</style><script>" + SCRIPT + "</script></head><body"
                + (dark ? " class=\"dark\"" : "") + ">"
                + "<div id=\"pin\"><div id=\"bar\"><span id=\"vbar\"><button id=\"vflow\" class=\"on\" onclick=\"return adView(false)\""
                + " title=\"Where the program went: one box per place, a click shows what is known there\">Flow</button>"
                + "<button id=\"vsteps\" onclick=\"return adView(true)\" title=\"Every tool call in order\">Steps</button></span>"
                + "<label title=\"Keep the newest step in sight\"><input type=\"checkbox\""
                + " id=\"follow\" checked onchange=\"ad.follow=this.checked;if(this.checked)adJump()\">Follow</label>"
                + "<button class=\"xa\" onclick=\"return adSteps(true)\" title=\"Open the details of every step\">Expand All</button>"
                + "<button class=\"xa\" onclick=\"return adSteps(false)\" title=\"Close the details of every step\">Collapse All</button>"
                + "<button id=\"theme\" onclick=\"return adTheme()\" title=\"Switch between the dark and the light look\">"
                + (dark ? "Light" : "Dark") + "</button>"
                + "<span id=\"tools\"></span><span id=\"toast\"></span></div><div id=\"banner\"></div></div>"
                + "<div id=\"head\">" + waitingHead() + "</div><div id=\"flow\"></div>"
                + "<ol id=\"steps\"></ol><div id=\"stop\"></div>"
                // Warnings are about now, so they sit with the newest events, where Follow keeps the view.
                + "<div id=\"warn\"></div><div id=\"jswarn\"></div><div id=\"analysis\"></div><div id=\"note\"></div>"
                + "<a id=\"jump\" href=\"#\" onclick=\"return adJump()\" title=\"Follow the newest steps again\">&#8595; Newest</a>"
                + "</body></html>";
    }

    /** The script call that shows this state of the investigation (null: nothing yet). */
    public static String update(ActivityStore.Investigation inv, long droppedEvents, ZoneId zone) {
        Parts p = parts(inv, droppedEvents, zone, false);
        return call(p.areas(), p.start(), p.end(), p.last(), p.waitStart(), p.running());
    }

    /** The areas of the page and the numbers its script needs, for the view or ({@code report}) the HTML report. */
    private record Parts(Map<String, String> areas, long start, long end, long last, long waitStart, boolean running) {
    }

    private static Parts parts(ActivityStore.Investigation inv, long droppedEvents, ZoneId zone, boolean report) {
        String note = droppedEvents > 0 ? html(droppedEvents + (report
                ? " events of this session were not recorded; the report is incomplete"
                : " live events were dropped by the MCP server; some steps may be missing")) : "";
        Map<String, String> u = new LinkedHashMap<>();
        if (inv == null) {
            u.put("id", "");
            u.put("title", "Agent Activity");
            u.put("head", waitingHead());
            for (String k : List.of("tools", "banner", "warn", "stop", "steps", "flow", "analysis")) u.put(k, "");
            u.put("note", note);
            return new Parts(u, 0, 0, 0, 0, false);
        }
        long start = epoch(inv.startedAt());
        long end = inv.finished() ? Math.max(epoch(inv.finishedAt()), start) : 0;
        ActivityStore.Step running = runningStep(inv);
        long wait = running != null && "STOP".equals(running.kind()) ? epoch(running.startedAt()) : 0;
        u.put("id", inv.id());
        // The title can change after the header was drawn ("Debugging session" until the launch names the program).
        u.put("title", html(title(inv)));
        u.put("head", head(inv, zone));
        u.put("tools", report ? "" : tools(inv));
        u.put("banner", banner(inv));
        u.put("warn", warnings(inv));
        u.put("stop", stop(inv));
        u.put("steps", steps(inv));
        u.put("flow", report ? "" : FlowPage.live(inv));
        u.put("analysis", analysis(inv, !report));
        u.put("note", note);
        return new Parts(u, start, end, epoch(inv.lastActivity()), wait, running != null);
    }

    /** Extra styles of the HTML report: no live parts, and source references are plain text (no Eclipse to open them). */
    private static final String REPORT_CSS = """
            #bar .rep{flex:1;color:#6b7280;font-size:11.5px}
            body.report a.src{color:inherit;text-decoration:none;cursor:text;pointer-events:none}
            body.report #jump{display:none!important}
            """;

    /**
     * The debugging session report (OPEN-30, OPEN-38 step 4): one self-contained HTML file. Everything is in the file
     * itself (styles, script, content; nothing is loaded from anywhere), and the content is in the HTML, so the report
     * reads without its script too; the script only adds the interactions.
     *
     * <p>Two views of the same investigation (OPEN-30 R4, phase 1): <b>Program flow</b> (the default, {@link FlowPage}):
     * where the program went, one place at a time, with Previous, Next and Replay; and <b>All steps</b>: the page of
     * the Agent Activity view, every tool call in order, with Expand All and Collapse All. Differences of All steps
     * from the view: no Follow and no Open HTML Report, source references are text, the time taken is written in,
     * every step's details and the last stop are open. Without the script both views are shown, one after the other.
     *
     * @param product shown next to "Debugging session report" (the report setting's project name)
     */
    public static String report(ActivityStore.Investigation inv, long droppedEvents, ZoneId zone, String product) {
        Parts p = parts(inv, droppedEvents, zone, true);
        Map<String, String> u = p.areas();
        String took = p.start() > 0 && p.end() >= p.start() ? duration((p.end() - p.start()) / 1000 * 1000) : "";
        String banner = u.get("banner").replace("<span id=\"timer\"></span>", "<span id=\"timer\">" + took + "</span>");
        // Steps open: without the script (mail preview, some viewers) the details are still there to read.
        String steps = u.get("steps").replaceAll("<details data-i=\"(\\d)", "<details open data-i=\"$1");
        // The last stop open too: in a report it is a result to read, not a live panel to keep small.
        String stop = u.get("stop").replace("<details class=\"stopbox\" data-i=\"stop\" data-d=\"0\">",
                "<details class=\"stopbox\" data-i=\"stop\" data-d=\"1\" open>");
        String started = inv == null || inv.startedAt() == null ? "" : clock(inv.startedAt(), zone);
        String title = (inv == null ? "Agent Activity" : title(inv)) + " - debugging session report"
                + (started.isEmpty() ? "" : " - " + started);
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>" + html(title)
                + "</title><style>" + CSS + ReportFormat.MARKDOWN_CSS + REPORT_CSS + FlowPage.CSS + FlowPage.DARK_CSS + "</style><script>" + SCRIPT
                + FlowPage.SCRIPT + "</script></head><body class=\"report dark\">"
                + "<div id=\"pin\"><div id=\"bar\"><span class=\"rep\">" + html("Debugging session report · "
                + (product == null ? "Agentic Debugger" : product)) + "</span>"
                + "<button class=\"xa\" onclick=\"return adSteps(true)\" title=\"Open the details of every step\">Expand All</button>"
                + "<button class=\"xa\" onclick=\"return adSteps(false)\" title=\"Close the details of every step\">Collapse All</button>"
                + (inv == null ? "" : "<button id=\"ftheme\" onclick=\"return flTheme()\" title=\"Switch between the dark"
                + " and the light look\">Light theme</button>")
                + "<span id=\"toast\"></span></div><div id=\"banner\">" + banner + "</div></div>"
                + "<div id=\"head\">" + u.get("head") + "</div>"
                + (inv == null ? "" : FlowPage.render(inv))
                + "<div id=\"all\"><h2 class=\"fallh\">All steps</h2>"
                + "<ol id=\"steps\">" + steps + "</ol><div id=\"stop\">" + stop + "</div>"
                + "<div id=\"warn\">" + u.get("warn") + "</div><div id=\"jswarn\"></div>"
                + "<div id=\"analysis\">" + u.get("analysis") + "</div><div id=\"note\">" + u.get("note") + "</div></div>"
                + "<a id=\"jump\" href=\"#\"></a>"
                + "<script>ad.follow=false;ad.id=" + js(inv == null ? "" : inv.id()) + ";"
                + (inv == null ? "" : "flInit();") + "</script>"
                + "</body></html>";
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

    /** Action buttons; each one calls back into Eclipse ({@code adAction}). Open HTML Report once a report exists. */
    static String tools(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder();
        if (inv.reportFile() != null && !inv.reportFile().isBlank())
            out.append("<button onclick=\"return adCall('openReport')\" title=\"The same investigation as a standalone HTML"
                    + " file, to keep or share; opens in your browser: ").append(html(inv.reportFile()))
                    .append("\">Open HTML Report</button>");
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
                .append("\">").append(inv.finished() ? "<span class=\"dot\"></span>" : "<span class=\"live\"></span>")
                .append("<span class=\"what\">").append(links(html(statusLine(inv) + extra)))
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
        // Open while the agent works; after the finish folded to one line (the analysis is what to read then).
        StringBuilder out = new StringBuilder("<details class=\"stopbox\" data-i=\"stop\" data-d=\"")
                .append(inv.finished() ? "0\">" : "1\" open>").append("<summary><h2>")
                .append(inv.finished() ? "Last stop: " : "Current stop: ").append(links(html(stop.where()))).append("</h2></summary>");
        if (!stop.title().equals(stop.where()))
            out.append("<div class=\"where\">").append(links(html(stop.title()))).append("</div>");
        if (stop.frames() != null) out.append("<div class=\"frames\">").append(frames(stop.frames())).append("</div>");
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
        return out.append("</details>").toString();
    }

    /** Frames shown before the rest of the call chain is folded away. */
    static final int FRAMES_SHOWN = 3;

    /**
     * The call chain with links: the first frames, the rest (framework and JDK calls, often many) folded under
     * "N more", so the variables stay in sight.
     */
    static String frames(String chain) {
        String[] frames = chain.split("\\s+<\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(FRAMES_SHOWN, frames.length); i++)
            out.append(i == 0 ? "" : " &lt; ").append(links(html(frames[i].strip())));
        if (frames.length > FRAMES_SHOWN) {
            out.append(" <details class=\"more\"><summary>").append(frames.length - FRAMES_SHOWN).append(" more</summary>");
            for (int i = FRAMES_SHOWN; i < frames.length; i++)
                out.append(i == FRAMES_SHOWN ? "" : " &lt; ").append(links(html(frames[i].strip())));
            out.append("</details>");
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
                    .append(step.running() ? "<span class=\"live\"></span>" : "")
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
            out.append("<details class=\"tech\"><summary>").append(ReportFormat.MORE_DETAILS_LABEL).append("</summary><pre>")
                    .append(links(html(String.join("\n", technical)))).append("</pre></details>");
        return out.toString();
    }

    static String analysis(ActivityStore.Investigation inv) {
        return analysis(inv, true);
    }

    /** The agent's analysis; {@code withReportPath}: also where the report file is (not in the report itself). */
    static String analysis(ActivityStore.Investigation inv, boolean withReportPath) {
        StringBuilder out = new StringBuilder();
        if (inv.conclusion() != null && !inv.conclusion().isBlank())
            out.append("<h2>From the agent</h2><div class=\"analysis\">")
                    .append(links(ReportFormat.markdown(inv.conclusion()))).append("</div>");
        if (withReportPath && inv.reportFile() != null)
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
    public static String title(ActivityStore.Investigation inv) {
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

    /**
     * The current stop: where (the top frame, "OrderService.quote:24", when known), the stop step's title, its call
     * chain (may be null) and the variables last read there.
     */
    public record Stop(String where, String title, String frames, List<Variable> variables) {
    }

    /** Frame lines of a "Read call stack" step: "  OrderService.quote:24". */
    private static final Pattern FRAME_LINE = Pattern.compile("^\\s+([\\w$.<>]+\\.[\\w$<>]+:\\d+)\\s*$");

    /**
     * The latest stop of the investigation: a stop step ("Stopped at breakpoint: OrderService.quote, line 24",
     * "Stopped after step: ...", "Paused: ...", or "Thread suspended: ..." after a pause), with the call chain from the
     * stop itself or from a later "Read call stack", and the variables of the latest variables table at or after it
     * (the stop itself, or a later read of the variables). A value is marked changed when an earlier table had the
     * same variable with another value. Tables of "Expanded variable" steps do not count: they list the fields of one
     * object, not the frame's variables (trial 25 Nov 09:47: the stop showed the fields of a BigDecimal instead of
     * quantity, percent and rate). Null when the program has not stopped yet.
     */
    public static Stop currentStop(ActivityStore.Investigation inv) {
        List<ActivityStore.Step> steps = inv.steps();
        int stopAt = -1;
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (isStop(steps.get(i))) {
                stopAt = i;
                break;
            }
        }
        if (stopAt < 0) return null;
        String frames = null;
        for (String line : ReportFormat.split(steps.get(stopAt).details()).reader())
            if (line.startsWith("Frames: ")) frames = line.substring("Frames: ".length());
        for (int i = stopAt + 1; i < steps.size() && frames == null; i++) {
            List<String> chain = new ArrayList<>();
            for (String line : ReportFormat.split(steps.get(i).details()).reader()) {
                Matcher m = FRAME_LINE.matcher(line);
                if (m.matches()) chain.add(m.group(1));
            }
            if (!chain.isEmpty()) frames = String.join("  <  ", chain);
        }
        String where = frames != null ? frames.split("\\s+<\\s+")[0].strip()
                : ActivityStore.PROGRAM_PAUSED.equals(inv.program()) && inv.programWhere() != null ? inv.programWhere()
                : steps.get(stopAt).title();
        // The latest table at or after the stop, and the latest table before that one.
        List<Variable> current = List.of();
        int currentAt = -1;
        for (int i = steps.size() - 1; i >= stopAt; i--) {
            List<Variable> table = frameVariables(steps.get(i));
            if (!table.isEmpty()) {
                current = table;
                currentAt = i;
                break;
            }
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (int i = currentAt - 1; i >= 0 && currentAt >= 0; i--) {
            List<Variable> table = frameVariables(steps.get(i));
            if (table.isEmpty()) continue;
            for (Variable v : table) before.put(v.name(), v.value());
            break;
        }
        List<Variable> marked = new ArrayList<>();
        for (Variable v : current) {
            String was = before.get(v.name());
            marked.add(new Variable(v.name(), v.type(), v.value(), was != null && !was.equals(v.value()) ? was : null));
        }
        return new Stop(where, steps.get(stopAt).title(), frames, List.copyOf(marked));
    }

    /** The frame's variables in a step: its variables table, except for an expanded object's fields. */
    static List<Variable> frameVariables(ActivityStore.Step step) {
        return step.title() != null && step.title().startsWith("Expanded variable") ? List.of() : variables(step);
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
        for (ActivityStore.Step s : inv.steps()) if (isStop(s)) return true;
        return false;
    }

    /** A step where the program was found stopped: by a breakpoint, a step, an exception or a pause. */
    static boolean isStop(ActivityStore.Step s) {
        if (!"STOP".equals(s.kind()) || !"OK".equals(s.status()) || s.title() == null) return false;
        String t = s.title();
        return t.startsWith("Stopped") || t.startsWith("Paused") || t.startsWith("Thread suspended");
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

    /** The whole investigation as Markdown, for the text fallback (no embedded browser). */
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
    public static String js(String text) {
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
