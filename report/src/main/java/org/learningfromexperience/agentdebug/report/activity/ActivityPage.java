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
 *   <li>a banner, one colour per phase for the box and its dot (OPEN-48): while the agent works without the debugger
 *       ("Thinking": before the launch, or after the program ended) bright orange, while it debugs ("Debugging": launch,
 *       program running or paused, waiting for a stop) red, both with a breathing box and a pulsing "live" dot, what
 *       the agent is doing and a running timer; when finished green, when ended without the agent finishing it
 *       alarming bright yellow,
 *       with the time taken.
 *       While it runs, the banner also shows where the program is (OPEN-49): "Paused in OrderService.quote:24" or
 *       "Last stop OrderService.quote:24", linked to the source and updated at every stop or step; below it what the
 *       program does (running, paused, ended);</li>
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
            .dby{background:#EEEDFE;border:1px solid #AFA9EC;border-radius:8px;padding:8px 12px 7px;margin:4px 0 8px;
            color:#3C3489}
            .dby-h{font-size:13px;font-weight:700;color:#26215C}
            .dby>summary{list-style:none;cursor:pointer;font-size:12px;display:flex;align-items:baseline;gap:5px}
            .dby .facts{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .dby[open] .tog{flex:1}
            .dby>summary::-webkit-details-marker{display:none}
            .dby[open]>summary{margin-bottom:6px}
            .dby[open]>summary .facts{display:none}
            .dby .fa{color:#3C3489}
            .dby .fm{color:#185FA5;font-weight:600}
            .dby .fs{color:#0F6E56;font-weight:600}
            .dby .tog{color:#534AB7;font-size:11.5px;white-space:nowrap;text-decoration:underline dotted}
            .dby .tog::after{content:"more \\25B8"}
            .dby[open] .tog::after{content:"less \\25B4"}
            .dby:not([open]){padding:4px 12px}
            .dby .chips{display:flex;flex-wrap:wrap;gap:7px;margin-bottom:6px}
            .dby .chip{display:inline-flex;border-radius:6px;overflow:hidden;font-size:12px;border:1px solid var(--cb)}
            .dby .chip .k{background:var(--ck);color:#fff;padding:3px 8px}
            .dby .chip .v{background:var(--cv);color:var(--ct);padding:3px 9px}
            .dby .chip .v b{font-weight:700}
            .dby .chip .ver{opacity:.8}
            .dby .chip .v.none{font-style:italic;color:#5F5E5A;background:#F1EFE8}
            .dby .chip.agent{--cb:#534AB7;--ck:#534AB7;--cv:#3C3489;--ct:#fff}
            .dby .chip.model{--cb:#185FA5;--ck:#185FA5;--cv:#E6F1FB;--ct:#042C53}
            .dby .chip.skill{--cb:#0F6E56;--ck:#0F6E56;--cv:#E1F5EE;--ct:#04342C}
            .dby .chip.effort{--cb:#888780;--ck:#5F5E5A;--cv:#F1EFE8;--ct:#2C2C2A}
            .dby .said{color:#BA7517;font-weight:700}
            .dby-key{display:inline-block;background:#FAEEDA;border:1px solid #EF9F27;color:#633806;border-radius:6px;
            padding:1px 8px;font-size:11.5px;font-weight:600;margin-bottom:5px}
            .dby-more{border-top:1px solid #AFA9EC;padding-top:5px;font-size:11.5px;display:grid;gap:2px}
            .dby-more b{color:#26215C}
            .dby-more code{font:11.5px Consolas,'Cascadia Mono',monospace;background:#fff;border:1px solid #AFA9EC;
            border-radius:4px;padding:0 4px;color:#26215C}
            .dby-more .why{color:#534AB7}
            body.dark .dby{background:#26215C;border-color:#534AB7;color:#CECBF6}
            body.dark .dby-h,body.dark .dby-more b,body.dark .dby .fa{color:#EEEDFE}
            body.dark .dby .fm{color:#85B7EB}
            body.dark .dby .fs{color:#5DCAA5}
            body.dark .dby .tog{color:#AFA9EC}
            body.dark .dby .chip.agent{--cb:#7F77DD;--ck:#534AB7;--cv:#7F77DD;--ct:#fff}
            body.dark .dby .chip.model{--cb:#378ADD;--ck:#185FA5;--cv:#042C53;--ct:#B5D4F4}
            body.dark .dby .chip.skill{--cb:#1D9E75;--ck:#0F6E56;--cv:#04342C;--ct:#9FE1CB}
            body.dark .dby .chip.effort{--cb:#888780;--ck:#5F5E5A;--cv:#2C2C2A;--ct:#D3D1C7}
            body.dark .dby .chip .v.none{color:#B4B2A9;background:#2C2C2A}
            body.dark .dby .said{color:#EF9F27}
            body.dark .dby-key{background:#412402;border-color:#BA7517;color:#FAC775}
            body.dark .dby-more{border-top-color:#534AB7}
            body.dark .dby-more code{background:#3C3489;border-color:#534AB7;color:#EEEDFE}
            body.dark .dby-more .why{color:#AFA9EC}
            #pin{position:sticky;top:0;z-index:5;background:#fff;margin:-8px -10px 6px;padding:6px 10px 6px;
            box-shadow:0 2px 4px rgba(0,0,0,.06)}
            #bar{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:0 0 5px;font-size:12px;color:#374151}
            #jump{display:none;position:fixed;right:14px;bottom:12px;z-index:6;background:#1d4ed8;color:#fff;border-radius:14px;
            padding:4px 12px;font-size:12px;font-weight:600;text-decoration:none;box-shadow:0 2px 6px rgba(0,0,0,.25)}
            #bar label{display:flex;align-items:center;gap:4px;cursor:pointer;margin-right:4px}
            #bar button.stop{color:#b91c1c;border-color:#fca5a5}
            #bar button{font:inherit;font-size:11.5px;padding:2px 9px;border:1px solid #cbd5e1;border-radius:5px;
            background:#f8fafc;color:#1f2937;cursor:pointer}
            #bar button:hover{background:#e2e8f0}
            #toast{color:#166534;font-weight:600}
            .banner{display:flex;flex-wrap:wrap;align-items:center;gap:4px 8px;padding:5px 10px;border-radius:7px;margin:0;
            font-weight:600}
            .banner .what{flex:1 1 14em;min-width:14em}
            .banner .timer{font-variant-numeric:tabular-nums;font-weight:600;white-space:nowrap}
            .banner.running{background:#dbeafe;color:#1e40af;
            animation:ad-breathe 1.4s ease-in-out infinite,ad-glow 1.4s ease-in-out infinite}
            .banner.running .what{animation:ad-text 1.4s ease-in-out infinite}
            @keyframes ad-breathe{0%,100%{background:#dbeafe}50%{background:#bfdbfe}}
            .banner.running.thinking{background:#fff7ed;color:#c2410c;
            animation:ad-breathe-think 1.4s ease-in-out infinite,ad-glow-think 1.4s ease-in-out infinite}
            @keyframes ad-breathe-think{0%,100%{background:#fff7ed}50%{background:#fed7aa}}
            @keyframes ad-glow-think{0%,100%{box-shadow:0 0 0 0 rgba(249,115,22,.4)}50%{box-shadow:0 0 0 4px rgba(249,115,22,.18)}}
            .banner.thinking .live::before,.banner.thinking .live::after{background:#f97316}
            .banner.running.debugging{background:#fef2f2;color:#b91c1c;
            animation:ad-breathe-debug 1.4s ease-in-out infinite,ad-glow-debug 1.4s ease-in-out infinite}
            @keyframes ad-breathe-debug{0%,100%{background:#fef2f2}50%{background:#fecaca}}
            @keyframes ad-glow-debug{0%,100%{box-shadow:0 0 0 0 rgba(220,38,38,.4)}50%{box-shadow:0 0 0 4px rgba(220,38,38,.18)}}
            .banner.debugging .live::before,.banner.debugging .live::after{background:#dc2626}
            .banner .phase{flex:none;font-size:10.5px;font-weight:700;letter-spacing:.4px;text-transform:uppercase;
            border:1.5px solid currentColor;border-radius:9px;padding:0 7px}
            .banner .where{flex:none;font:600 12px Consolas,'Cascadia Mono',monospace;white-space:nowrap;
            background:rgba(255,255,255,.65);border-radius:5px;padding:1px 7px}
            .banner .where .wl{font:600 11px 'Segoe UI',system-ui,sans-serif;opacity:.8;margin-right:4px}
            .banner .where a.src{color:inherit}
            @keyframes ad-text{0%,100%{opacity:1}50%{opacity:.55}}
            .live{position:relative;width:12px;height:12px;flex:none;align-self:center}
            .live::before,.live::after{content:"";position:absolute;inset:0;border-radius:50%;background:#dc3545}
            .live::after{animation:ad-ripple 1.2s ease-out infinite}
            @keyframes ad-ripple{0%{transform:scale(1);opacity:.75}100%{transform:scale(2.8);opacity:0}}
            .live::before{animation:ad-blink 1s ease-in-out infinite}
            .banner.done{background:#dcfce7;color:#166534}
            .banner.ended{background:#fde047;color:#713f12;box-shadow:0 0 0 2px #eab308}
            .banner.ended .dot{background:#a16207}
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
            // Only the user's own scrolling pauses Follow (28 Nov: a page that changed height while being updated sent
            // scroll events too, Follow paused by itself and the newest step stayed out of sight).
            ['wheel','keydown','touchmove','mousedown'].forEach(function(t){
              window.addEventListener(t,function(){ad.user=Date.now();},{passive:true});});
            window.addEventListener('scroll',function(){if(ad.scrolling)return;
              if(!ad.user||Date.now()-ad.user>1500){if(!ad.paused)return;}
              ad.paused=!adAtBottom();document.getElementById('jump').style.display=ad.paused&&ad.follow?'block':'none';});
            function adJump(){ad.paused=false;document.getElementById('jump').style.display='none';adFollow();return false;}
            function adUpdate(u){
              var newId=u.id!==ad.id;
              if(newId){document.getElementById('head').innerHTML=u.head;ad.id=u.id;ad.state={};ad.all=true;ad.paused=false;}
              else{var h=document.querySelector('#head h1');if(h&&h.innerHTML!==u.title)h.innerHTML=u.title;}
              ['tools','banner','warn','stop','steps','flow','seq','analysis','note'].forEach(function(k){
                var e=document.getElementById(k);if(e)e.innerHTML=u[k]===undefined?'':u[k];});
              adApply();if(newId)ad.sq=null;adSqApply();
              ad.start=u.start;ad.end=u.end;ad.last=u.last;ad.wait=u.wait;ad.running=u.running;
              adTick();
              if(ad.timer){clearInterval(ad.timer);ad.timer=null;}
              if(ad.start>0&&ad.end<=0)ad.timer=setInterval(adTick,1000);
              // Follow: the page reads in time order, so the newest step, the current stop and at the end the
              // analysis are at the bottom; scrolled there once the browser has laid the new content out.
              adFollow();
              return true;}
            // Flow (default) or Steps: what the page shows; kept while the page lives.
            // Flow (default), Sequence (OPEN-65) or Steps: what the page shows; kept while the page lives.
            function adView(v){if(v===true)v='steps';if(v===false)v='flow';
              document.body.classList.toggle('vsteps',v==='steps');document.body.classList.toggle('vseq',v==='seq');
              ['flow','seq','steps'].forEach(function(n){var b=document.getElementById('v'+n);if(b)b.classList.toggle('on',n===v);});
              if(v==='seq')adSqApply();adFollow();return false;}
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
                + FlowPage.CSS + FlowPage.LIVE_CSS + FlowPage.DARK_CSS + SequencePage.CSS + "</style><script>" + SCRIPT
                + SequencePage.SCRIPT + "</script></head><body"
                + (dark ? " class=\"dark\"" : "") + ">"
                + "<div id=\"pin\"><div id=\"bar\"><span id=\"vbar\"><button id=\"vflow\" class=\"on\" onclick=\"return adView('flow')\""
                + " title=\"Where the program went: one box per place, a click shows what is known there\">Flow</button>"
                + "<button id=\"vseq\" onclick=\"return adView('seq')\" title=\"The stops by class, the moves between them"
                + " and a timeline; click a box, a line or a class\">Sequence</button>"
                + "<button id=\"vsteps\" onclick=\"return adView('steps')\" title=\"Every tool call in order\">Steps</button></span>"
                + "<label title=\"Keep the newest step in sight\"><input type=\"checkbox\""
                + " id=\"follow\" checked onchange=\"ad.follow=this.checked;if(this.checked)adJump()\">Follow</label>"
                + "<button class=\"xa\" onclick=\"return adSteps(true)\" title=\"Open the details of every step\">Expand All</button>"
                + "<button class=\"xa\" onclick=\"return adSteps(false)\" title=\"Close the details of every step\">Collapse All</button>"
                + "<button id=\"theme\" onclick=\"return adTheme()\" title=\"Switch between the dark and the light look\">"
                + (dark ? "Light" : "Dark") + "</button>"
                + "<span id=\"tools\"></span><span id=\"toast\"></span></div><div id=\"banner\"></div></div>"
                + "<div id=\"head\">" + waitingHead() + "</div><div id=\"flow\"></div><div id=\"seq\"></div>"
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
            for (String k : List.of("tools", "banner", "warn", "stop", "steps", "flow", "seq", "analysis")) u.put(k, "");
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
        u.put("seq", report ? "" : SequencePage.live(inv));
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
        // The "Agent details" block open: the report has the room, and it is read later.
        String head = u.get("head").replace("<details class=\"dby\">", "<details class=\"dby\" open>");
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
                + "<div id=\"head\">" + head + "</div>"
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
        out.append(debuggedBy(inv, zone));
        return out.toString();
    }

    /** Marks a value the agent reported about itself (model, effort, skill): Eclipse cannot check it. */
    static final String SAID = "\u25C6";

    /**
     * OPEN-41: the "Agent details" block under the request. Coloured chips for the agent (from the MCP handshake, so
     * reliable), the model, the skill and the effort (what the agent said about itself, each marked with
     * {@link #SAID} and explained once); then, in plain words, what happens when the investigation finishes (instead of
     * the mode names NORMAL and CONTROLLED), the start date and time, and the investigation ID with what it is for.
     */
    static String debuggedBy(ActivityStore.Investigation inv, ZoneId zone) {
        String[] agent = agent(inv.client());
        String model = blankToNull(inv.model()), effort = blankToNull(inv.effort()), skill = blankToNull(inv.skill());
        // Folded to one line in the Agent Activity window (a narrow view); open in the report (see report()).
        StringBuilder out = new StringBuilder("<details class=\"dby\"><summary><span class=\"dby-h\">Agent details</span>")
                .append("<span class=\"facts\"> <b class=\"fa\">").append(html(agent[0])).append("</b>");
        if (model != null) out.append(" · <span class=\"fm\">").append(html(model)).append("</span>");
        if (skill != null) out.append(" · <span class=\"fs\">").append(html(skill)).append("</span>");
        out.append("</span><span class=\"tog\"></span></summary><div class=\"chips\">");
        out.append("<span class=\"chip agent\"><span class=\"k\">Agent</span><span class=\"v\"><b>")
                .append(html(agent[0])).append("</b>")
                .append(agent[1] == null ? "" : " <span class=\"ver\">" + html(agent[1]) + "</span>").append("</span></span>");
        out.append(chip("model", "Model", model, "not stated"));
        out.append(chip("skill", "Skill", skill, "none reported"));
        out.append(chip("effort", "Effort", effort, "not stated"));
        out.append("</div>");
        if (model != null || effort != null || skill != null)
            out.append("<div class=\"dby-key\">").append(SAID)
                    .append(" Reported by the agent itself; Eclipse cannot verify it</div>");
        out.append("<div class=\"dby-more\">");
        String after = afterFinish(inv.mode());
        if (after != null) out.append("<div><b>After it finishes:</b> ").append(html(after)).append("</div>");
        if (inv.startedAt() != null)
            out.append("<div><b>Started:</b> ").append(html(dateTime(inv.startedAt(), zone))).append("</div>");
        if (inv.id() != null && !inv.id().isBlank())
            out.append("<div><b>Investigation ID:</b> <code title=\"").append(html(inv.id())).append("\">")
                    .append(html(shortId(inv.id()))).append("</code> <span class=\"why\">identifies this investigation;"
                    + " the agent passes it with its tool calls</span></div>");
        return out.append("</div></details>").toString();
    }

    private static String chip(String kind, String label, String value, String missing) {
        StringBuilder out = new StringBuilder("<span class=\"chip ").append(kind).append("\"><span class=\"k\">")
                .append(label).append("</span>");
        if (value == null) return out.append("<span class=\"v none\">").append(missing).append("</span></span>").toString();
        int space = "skill".equals(kind) ? value.lastIndexOf(' ') : -1;
        String name = space > 0 ? value.substring(0, space) : value;
        String version = space > 0 ? value.substring(space + 1) : null;
        out.append("<span class=\"v\"><b>").append(html(name)).append("</b>");
        if (version != null) out.append(" <span class=\"ver\">").append(html(version)).append("</span>");
        return out.append(" <span class=\"said\" title=\"Reported by the agent itself\">").append(SAID)
                .append("</span></span></span>").toString();
    }

    /** Known MCP client IDs and how people call them; anything else is shown as the client sent it. */
    private static final Map<String, String> AGENT_NAMES = Map.of(
            "claude-code", "Claude Code", "github-copilot", "GitHub Copilot", "copilot", "GitHub Copilot",
            "cursor", "Cursor", "windsurf", "Windsurf", "cline", "Cline", "codex-mcp-client", "Codex",
            "codex", "Codex", "gemini-cli", "Gemini CLI", "claude-ai", "Claude");

    /** {@code "claude-code 2.1.331"} -> {@code ["Claude Code", "2.1.331"]}; no client -> {@code ["unknown agent", null]}. */
    static String[] agent(String client) {
        String text = blankToNull(client);
        if (text == null) return new String[] {"unknown agent", null};
        int space = text.indexOf(' ');
        String id = space > 0 ? text.substring(0, space) : text;
        String version = space > 0 ? text.substring(space + 1).strip() : null;
        // OPEN-23: GitHub Copilot for Eclipse sends "Eclipse/copilot-eclipse <eclipse version>/<plugin version>",
        // such as "Eclipse/copilot-eclipse 4.41.0.v20260828-1142/0.22.0.202611251609_nightly" (26 Nov 2026).
        if (id.toLowerCase(Locale.ROOT).endsWith("copilot-eclipse")) {
            return new String[] {"GitHub Copilot", copilotVersion(version)};
        }
        return new String[] {AGENT_NAMES.getOrDefault(id.toLowerCase(Locale.ROOT), id), version};
    }

    /** {@code "4.41.0.v20260828-1142/0.22.0.202611251609_nightly"} -> {@code "0.22.0 (nightly)"}; null stays null. */
    static String copilotVersion(String version) {
        if (version == null) return null;
        String plugin = version.substring(version.lastIndexOf('/') + 1);
        var number = java.util.regex.Pattern.compile("^(\\d+\\.\\d+\\.\\d+)").matcher(plugin);
        String shown = number.find() ? number.group(1) : plugin;
        return plugin.toLowerCase(Locale.ROOT).contains("nightly") ? shown + " (nightly)" : shown;
    }

    /** The mode in plain words: what the end of the investigation does to the debug state. */
    static String afterFinish(String mode) {
        if (mode == null) return null;
        return "CONTROLLED".equals(mode)
                ? "its breakpoints and the paused debug session are kept, so you can inspect them."
                : "the agent's own breakpoints are removed and the debug session it started is stopped."
                        + " Your breakpoints and sessions are left alone.";
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm:ss", Locale.ENGLISH);

    static String dateTime(String isoInstant, ZoneId zone) {
        try {
            return DATE_TIME.format(Instant.parse(isoInstant).atZone(zone));
        } catch (RuntimeException unparsable) {
            return isoInstant;
        }
    }

    /** Action buttons; each one calls back into Eclipse ({@code adAction}). Open HTML Report once a report exists. */
    static String tools(ActivityStore.Investigation inv) {
        StringBuilder out = new StringBuilder();
        // OPEN-62: cancel from Eclipse; Copilot's own stop button is gone once the agent has ended its turn.
        if (!inv.finished())
            out.append("<button class=\"stop\" onclick=\"return adCall('stop')\" title=\"Stops the agent's own program"
                    + " and removes the agent's own breakpoints now. Your own debug sessions are not touched.\">"
                    + "Stop and clean up</button>");
        if (inv.reportFile() != null && !inv.reportFile().isBlank())
            out.append("<button onclick=\"return adCall('openReport')\" title=\"The same investigation as a standalone HTML"
                    + " file, to keep or share; opens in your browser: ").append(html(inv.reportFile()))
                    .append("\">Open HTML Report</button>");
        return out.toString();
    }

    static String banner(ActivityStore.Investigation inv) {
        String phase = phase(inv);
        String cls = switch (phase) {
            case PHASE_THINKING -> "running thinking";
            case PHASE_DEBUGGING -> "running debugging";
            case PHASE_FINISHED -> "done";
            default -> "ended";
        };
        long refused = inv.steps().stream().filter(s -> "FAILED".equals(s.status())).count();
        String extra = refused == 0 ? "" : " · " + refused + (refused == 1 ? " tool call" : " tool calls")
                + " refused, retried by the agent";
        String timer = inv.finished() ? "took <span id=\"timer\"></span>" : "<span id=\"timer\"></span>";
        StringBuilder out = new StringBuilder("<div class=\"banner ").append(cls)
                .append("\">").append(inv.finished() ? "<span class=\"dot\"></span>" : "<span class=\"live\"></span>");
        if (!inv.finished())
            out.append("<span class=\"phase\">").append(PHASE_THINKING.equals(phase) ? "Thinking" : "Debugging")
                    .append("</span>");
        out.append("<span class=\"what\">").append(links(html(statusLine(inv) + extra))).append("</span>");
        String where = where(inv);
        if (where != null)
            out.append("<span class=\"where\" title=\"Where the program is: class, method and line\"><span class=\"wl\">")
                    .append(ActivityStore.PROGRAM_PAUSED.equals(inv.program()) ? "Paused in" : "Last stop")
                    .append("</span>").append(links(html(where))).append("</span>");
        out.append("<span class=\"timer\">").append(timer).append("</span></div>");
        String program = programLine(inv);
        if (program != null) out.append("<div class=\"prog\">").append(program).append("</div>");
        return out.toString();
    }

    /** "Program: <b>paused</b>", "running" or "ended", or null before the launch. */
    static String programLine(ActivityStore.Investigation inv) {
        if (inv.program() == null) return null;
        return switch (inv.program()) {
            case ActivityStore.PROGRAM_RUNNING -> "Program: <b>running</b>";
            // Where it is paused is shown in the banner itself (OPEN-49).
            case ActivityStore.PROGRAM_PAUSED -> "Program: <b>paused</b>";
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

    /** {@link #phase}: the agent works without the debugger (before the launch, or after the program ended). */
    public static final String PHASE_THINKING = "THINKING";
    /** {@link #phase}: the debugger is in use (launch, program running or paused, waiting for a stop). */
    public static final String PHASE_DEBUGGING = "DEBUGGING";
    /** {@link #phase}: the agent finished the investigation. */
    public static final String PHASE_FINISHED = "FINISHED";
    /** {@link #phase}: the investigation ended without the agent finishing it. */
    public static final String PHASE_ENDED = "ENDED";

    /**
     * The phase of an investigation, for the banner colour and the status bar (OPEN-48): {@link #PHASE_DEBUGGING} while
     * a launch, a wait for a stop or a step is under way, or the program is running or paused (also when Eclipse has
     * not reported the program's state yet after a successful launch); {@link #PHASE_THINKING} otherwise while it runs
     * (before the launch; after the program ended, while the agent writes its answer); {@link #PHASE_FINISHED} or
     * {@link #PHASE_ENDED} at the end. Null for no investigation.
     */
    public static String phase(ActivityStore.Investigation inv) {
        if (inv == null) return null;
        if (inv.finished()) return "FINISHED".equals(inv.outcome()) ? PHASE_FINISHED : PHASE_ENDED;
        ActivityStore.Step running = runningStep(inv);
        if (running != null && ("LAUNCH".equals(running.kind()) || "STOP".equals(running.kind())
                || "STEP".equals(running.kind()))) return PHASE_DEBUGGING;
        String program = inv.program();
        if (ActivityStore.PROGRAM_RUNNING.equals(program) || ActivityStore.PROGRAM_PAUSED.equals(program))
            return PHASE_DEBUGGING;
        if (program == null)
            for (ActivityStore.Step step : inv.steps())
                if ("LAUNCH".equals(step.kind()) && "OK".equals(step.status())) return PHASE_DEBUGGING;
        return PHASE_THINKING;
    }

    /**
     * Where the program is, for the banner while the investigation runs (OPEN-49): "OrderService.quote:24" where it is
     * paused (from Eclipse), otherwise the latest stop; null before the first stop, after the program ended and once
     * the investigation finished (the report shows the last stop).
     */
    static String where(ActivityStore.Investigation inv) {
        if (inv.finished() || ActivityStore.PROGRAM_ENDED.equals(inv.program())) return null;
        if (ActivityStore.PROGRAM_PAUSED.equals(inv.program()) && inv.programWhere() != null
                && !inv.programWhere().isBlank()) return inv.programWhere();
        Stop stop = currentStop(inv);
        return stop == null ? null : stop.where();
    }

    /**
     * What the banner says. An investigation the agent did not finish also says why, from the MCP server's record
     * ("Finished by the MCP server: agent closed the MCP connection"), so it does not read like an error of the tool.
     */
    static String statusLine(ActivityStore.Investigation inv) {
        if (inv.finished()) {
            if ("FINISHED".equals(inv.outcome())) return "Finished";
            if (ActivityStore.STOPPED.equals(inv.outcome())) return "Stopped by you";
            if (ActivityStore.EXPIRED.equals(inv.outcome()))
                return "Ended by Eclipse: the agent stopped calling, so its program and breakpoints were cleaned up";
            String why = endReason(inv);
            return "Ended without the agent finishing it" + (why == null ? "" : ": " + why + ", so the MCP server finished it");
        }
        ActivityStore.Step running = runningStep(inv);
        if (running != null) return "Running: " + running.title();
        if (inv.steps().isEmpty()) return "Investigation started";
        if (ActivityStore.PROGRAM_PAUSED.equals(inv.program()))
            return "The agent is looking at the paused program";
        return "The agent is thinking";
    }

    /** Why the MCP server finished an investigation for the agent, or null when not recorded. */
    private static String endReason(ActivityStore.Investigation inv) {
        String prefix = "Finished by the MCP server: ";
        if (inv.cleanup() != null)
            for (String line : inv.cleanup())
                if (line != null && line.startsWith(prefix) && line.length() > prefix.length())
                    return line.substring(prefix.length()).strip();
        return null;
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

    /**
     * OPEN-41: the "Agent details" facts as Markdown lines (no colours there): agent, model, skill, effort, the
     * {@link #SAID} key, what happens when it finishes, and the investigation ID.
     */
    static String debuggedByText(ActivityStore.Investigation inv, ZoneId zone) {
        String[] agent = agent(inv.client());
        String model = blankToNull(inv.model()), effort = blankToNull(inv.effort()), skill = blankToNull(inv.skill());
        StringBuilder out = new StringBuilder("**Agent details:** ").append(join(" ", agent[0], agent[1]))
                .append(" · Model ").append(model == null ? "not stated" : model + " " + SAID)
                .append(" · Skill ").append(skill == null ? "none reported" : skill + " " + SAID)
                .append(" · Effort ").append(effort == null ? "not stated" : effort + " " + SAID).append("  \n");
        if (model != null || effort != null || skill != null)
            out.append(SAID).append(" reported by the agent itself; Eclipse cannot verify it  \n");
        String after = afterFinish(inv.mode());
        if (after != null) out.append("**After it finishes:** ").append(after).append("  \n");
        if (inv.startedAt() != null) out.append("**Started:** ").append(dateTime(inv.startedAt(), zone)).append("  \n");
        out.append("**Investigation ID:** ").append(shortId(inv.id())).append("\n\n");
        return out.toString();
    }

    /** The whole investigation as Markdown, for the text fallback (no embedded browser). */

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public static String markdown(ActivityStore.Investigation inv, ZoneId zone) {
        StringBuilder out = new StringBuilder("# ").append(title(inv)).append("\n\n");
        if (inv.request() != null && !inv.request().isBlank())
            out.append("**Request:** ").append(inv.request().strip()).append("\n\n");
        out.append("**Status:** ").append(statusLine(inv));
        long took = epoch(inv.finishedAt()) - epoch(inv.startedAt());
        if (inv.finished() && epoch(inv.startedAt()) > 0 && took >= 0) out.append(" in ").append(duration(took));
        out.append("\n\n").append(debuggedByText(inv, zone));
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

    /**
     * One line for the status bar: "Agent: thinking · 8 s", "Agent: running · 45 s", "Agent: paused",
     * "Agent: waiting for a stop", "Agent: finished · 1 min 05 s", or null when idle.
     */
    public static String statusBar(ActivityStore.Investigation inv, long nowMillis) {
        if (inv == null) return null;
        long start = epoch(inv.startedAt());
        long end = inv.finished() ? epoch(inv.finishedAt()) : nowMillis;
        String time = start > 0 && end >= start ? " · " + duration((end - start) / 1000 * 1000) : "";
        if (!inv.finished()) {
            ActivityStore.Step running = runningStep(inv);
            String doing = running != null && "STOP".equals(running.kind()) ? "waiting for a stop"
                    : ActivityStore.PROGRAM_PAUSED.equals(inv.program()) ? "paused"
                    : PHASE_THINKING.equals(phase(inv)) ? "thinking" : "running";
            return "Agent: " + doing + time;
        }
        return ("FINISHED".equals(inv.outcome()) ? "Agent: finished"
                : ActivityStore.STOPPED.equals(inv.outcome()) ? "Agent: stopped by you"
                : ActivityStore.EXPIRED.equals(inv.outcome()) ? "Agent: ended (no activity)" : "Agent: ended without finish") + time;
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
