# Agent Activity view and related changes — agreed requirements

**Status:** requirements agreed 24 Nov 2026, not implemented. Tracked as OPEN-35, OPEN-36 and OPEN-37 in the [README](../../README.md#status-and-tracking), done in that order.
**Background:** the external design in [AGENTIC_DEBUGGER_LIVE_VIEW_AND_REPORT_DESIGN.md](AGENTIC_DEBUGGER_LIVE_VIEW_AND_REPORT_DESIGN.md) and its [review notes](README.md). Where this document and the external design differ, this document applies.

## Why

During an agentic investigation the user sees nothing in Eclipse about what the agent is doing, and does not know when it is finished without reading the Claude terminal. Eclipse may also switch to the Debug perspective and hide that terminal. The view must give a clear, live picture: what has happened, what is happening now, and when it is finished, with the result.

## Order of work

1. **OPEN-35 Keep the current perspective** (small, plugin).
2. **OPEN-36 Activity view V1** (large, MCP server + plugin).
3. **OPEN-37 Report: what actually happened** (medium, report writer + plugin for the source line).

---

## 1. OPEN-35 Keep the current perspective

- R35-1 For agent sessions the plugin no longer switches to the Debug perspective by default. Today `AgentDebugUiCoordinator` calls `showPerspective(ID_DEBUG_PERSPECTIVE)` when the preference `autoDebugPerspective` is on, default `true`.
- R35-2 On the first stop of an agent launch, these views are opened in the current perspective, as one group of tabs: **Variables** (in front), **Breakpoints**, **Expressions**, **Debug** (its selected frame decides what Variables shows), and **Console** where it already is or opens. The editor goes to the stopped line and the stopped frame is selected. On later stops views the user closed are opened again, but Variables is not forced to the front again.
- R35-3 None of these views is activated: "in front" means the visible tab, the **keyboard focus stays where it was**, because Claude may ask the user something in the terminal during the run.
- R35-4 A user who explicitly saved "switch to Debug perspective" keeps that behaviour (a value never saved means "keep the perspective"). The preference page explains the setting and that Eclipse's own Run/Debug → Perspectives settings can still ask to switch.
- R35-5 No switching on every stop: when the option is on, at most once per launch.
- R35-6 The plugin declares where the views go in the **Java perspective** (`perspectiveExtensions`, placeholders only, so the perspective looks unchanged until debugging starts): their own group under the editor, never the terminal's group. Takes effect for a new Java perspective or after Window → Perspective → Reset Perspective; documented.
- R35-7 Check during the manual test whether Eclipse's own "Switch to the Debug perspective?" dialog appears in agent sessions; if it does, document the setting (Run/Debug → Perspectives → "Open the associated perspective when an application suspends": Never) and consider a one-click option in our preference page; never change it silently.
- R35-8 (V2) An optional "Agentic Debugging" perspective that contains the editor, the Terminal, the debug views, Console and the Activity view; chosen explicitly by the user, nothing is hidden because the terminal is part of it.

Layout in the Java perspective (the Activity view column comes with OPEN-36):

```
+-------------+-----------------------------------+----------------------+
| Package     |  Editor (stops at the line)       |  Agentic Debugger    |
| Explorer    |                                   |  Activity (OPEN-36)  |
|             +-----------------------------------+                      |
|             | Variables* | Breakpoints |        |                      |
|             | Expressions | Debug               |                      |
+-------------+-----------------------------------+                      |
| Terminal (Claude)        | Console              |                      |
+--------------------------+----------------------+----------------------+
                                     * in front; keyboard stays in the terminal
```

**Check:** unit tests PP-U1 to PP-U3 (`PerspectivePolicy`); manual, in a Java perspective (after Reset Perspective) with the Claude terminal docked at the bottom: a stop shows the line, Variables in front with Breakpoints, Expressions and Debug as tabs under the editor, the terminal stays visible and typeable; option on switches once per launch; R35-7 dialog checked.

---

## 2. OPEN-36 Agent Activity view

**Name:** **Agent Activity**, in Window → Show View under the category **Agentic Debugger**. It shows what the AI agent is doing while it debugs.

### 2.1 What the user sees

**Behaviour**

- A1 The view opens by itself when an investigation starts and is pinned. It comes to the front of its area without taking the keyboard focus from the Claude terminal. It can also be opened with Window → Show View → Agentic Debugger → Agent Activity.
- A2 It works with reports on or off, and when the agent is not connected (then it says so).

**Status banner** (top, always visible)

- A3 One line saying what is happening, with what is debugged, the step number and a running timer, for example "Debugging OrdersApplication · Paused at OrderService.quote:24 · step 7 · 0:32".
- A4 Phases, each from a real event, never guessed: Ready · Preparing (breakpoints) · Starting (application or test) · Running, waiting for a breakpoint (with the wait time) · Paused at Class.method:line · Inspecting · Cleaning up · **Finished** · **Ended without finish**.
- A5 While the investigation is active the banner's marker pulses blue; when finished it turns solid green; ended without finish is orange; a real problem is red.

**Request** — A6 the user's request (exact prompt when known, otherwise as passed on by the agent), collapsible.

**Live timeline** (the main part)

- A7 One row per step, with the same plain-language titles as the report, in order, newest at the bottom, auto-scrolling unless the user has scrolled up.
- A8 Step states and colours: **running now** = blue, pulsing (only one row at a time); **done** = green; **refused, the agent retried** = amber; **failed** = red. Users with "reduce motion" get steady colours.
- A9 A click on a step expands it: a stop step shows its location, thread and the variable values **as they were at that stop** (labelled as an earlier stop, never as the current state); a breakpoint step shows class and line; tool name, arguments and raw result stay under "Technical details".
- A10 A click on a class and line (`OrderService.quote:24`) in a step, in the current-stop panel or in any frame of a call stack opens the Java editor at that line, highlighted. It only navigates: no resume, no change of the debugger's selected thread, so the user can look back while the agent works. JDK classes open in Eclipse's class viewer; a class not found in the workspace gives a short note in the view; changed code opens at the recorded line number. The navigation code is shared with OPEN-33.
- A10a **Liveliness**: while the investigation runs, the banner marker, the running step (a soft light sweeping across its row, blinking status text and its duration counting up) and the status-bar indicator pulse blue. When it finishes, all blinking stops at once and the colours change (green finished, orange ended without finish). Only one step pulses at a time; "reduce motion" gives steady colours.
- A10b **Status-bar indicator** at the bottom of Eclipse: "Agent debugging · step 7 · 0:32", then "Agent finished"; a click opens the view. Visible also when the view is hidden or another perspective is open.
- A10c **Follow** toggle in the view toolbar: on, the view scrolls to the running step and highlights the current stop; off, the user reads earlier steps undisturbed.
- A10d **Copy as Markdown**: the timeline as a numbered list, for a chat, an issue or a blog post.
- A10e Follows Eclipse's light or dark theme.

**Current stop**

- A11 While paused: class, method, line (clickable), thread, and the variables as a small table. Values that changed since the previous stop in the same method are highlighted (for example `rate = 0`). After a resume the panel is labelled "previous stop"; it never shows an old location as current.

**Finish**

- A12 When the investigation finishes: the banner turns green ("Finished"), the agent's full analysis is shown in the view (headings, tables and code rendered), with buttons **Open report** (when a report was written) and **Copy analysis**.
- A13 An Eclipse notification pop-up "Investigation finished" appears, also when the user is looking elsewhere.
- A14 "Ended without finish" (agent gone, idle lease, restart) is shown as such, with the automatic cleanup result; it is never shown as finished.

**Warnings** (red or amber, plain words)

- A15 "The program ended without reaching a breakpoint" (a launch ended with no stop while breakpoints were set).
- A16 "Waiting for a breakpoint: 25 s …" while a wait is running.
- A17 "No activity from the agent for 2 minutes" (not "Claude is thinking": the view only knows tool calls).
- A18 "Connection to Eclipse lost" / "agent disconnected".

**V2** (after V1 is in use)

- A19 Breakpoint panel: set by the agent, hit or never hit, removed at cleanup.
- A20 Earlier investigations of the session in a drop-down, each with its report.
- A21 "Stop and clean up" button: the user ends the agent's investigation from Eclipse; the agent's next call is told why it fails.
- A22 The agent's breakpoints labelled "agent" in the editor.
- A23 One tab per investigation when the agent runs two in parallel.

### 2.2 Architecture: decoupled, from the same events as the report

```
MCP server:  tool calls ──► SessionReport ──► ReportEvents ──► recorder (background thread, bounded)
                                                                 ├─► ChainTest writer  → HTML report      (exists)
                                                                 └─► Activity forwarder → Eclipse bridge (new)
Eclipse:     Activity store (per investigation) ◄── forwarded events
                                                ◄── JDT listener: program running / paused / ended
             Activity view ◄── store
```

- B1 **Same events as the report, fully independent consumers.** The view does not know the report and the report does not know the view; the view works with reports off, the report with the view closed. Each has its own switch (`AGENTDEBUG_REPORT`, `AGENTDEBUG_LIVE`, both on by default) and its own recorder: `CompositeReportRecorder` hands each event to an `AsyncReportRecorder` per consumer, each with its own queue, thread and failure counting. The only shared things:

  | Shared | What | Where |
  |---|---|---|
  | Event contract | request, step started, step, finish, cleanup, conclusion | `report-model` (`ReportEvent`) |
  | Plain titles and details | "Set breakpoint: OrderService, line 24", variables as a table, technical details after `ReportEvent.TECHNICAL_DETAILS` | made once in `SessionReport`, delivered to both |
  | Masking of secrets | `Redaction` | applied when events are made |
  | Isolation machinery | bounded queue, background thread, failure isolation | `AsyncReportRecorder`, one per consumer |
  | Presentation pieces | HTML escaping, Markdown rendering of the analysis, colour per step kind, technical-details split | new small module **`report-format`** (plain Java and a stylesheet, no ChainTest), used by the report writer and the view |

  Not shared: ChainTest, file writing, the report's layout and switch.
- B2 **One new event** `StepStarted` (sent before a tool runs), so the view can show "running now" and waits. The report writer ignores it.
- B3 **ADR-009 rules apply unchanged**: the debugger only publishes; the forwarder runs on the recorder's background thread with a bounded queue; a slow, failing or absent Eclipse view never delays or changes a tool result; failures go to stderr; it can be switched off independently of the report.
- B4 **Bridge**: a new authenticated command `ACTIVITY_EVENT <base64url(JSON)>` only puts the event into the store and returns at once (no UI work, no waiting). It is answered **outside the READ and MUTATE lanes** of `BridgeDispatcher` (like `GET_OPERATION`), so it can never wait behind, or delay, a debugging command; events over 64 000 characters are not sent. JSON fields per event type are listed in `ActivityForwarder` (mcp-server).
- B5 **JDT listener** in the plugin for the agent's launch: running, paused, ended, between tool calls. It does not read from `DebugStopTracker`'s queue.
- B6 **Store**: bounded per investigation, kept when the view is closed; the view subscribes and updates on the UI thread.
- B7 **Versions**: an older MCP server without the forwarder, or an older plugin without the command, must not break debugging. An older plugin answers "Unknown command" to the first event; the forwarder then stops for the session and says so once on stderr. The view says "no live data from this MCP server version" when it gets nothing.

### 2.2a Deliveries

1. **MCP server side** (done 24 Nov, build and trial PASS): `StepStarted`, `CompositeReportRecorder`, `ActivityForwarder`, `AGENTDEBUG_LIVE`; tests RM-U11..U13, AF-U1..U4, SR-U14.
2. **Eclipse side, data** (done 24 Nov, 19:59 trial): `ACTIVITY_EVENT` handled in `EclipseBridgeServer` before the lanes; `ActivityStore` per investigation (at most 20 investigations, oldest finished dropped first; at most 1000 steps each; a step result replaces the running step; a successful finish completes its running cleanup step as OK (the finish tool sends no step result of its own), any other step still running at the end becomes UNKNOWN; events of a missed start still shown; never throws); preview view `AgentActivityView` (SWT `Browser`, JavaScript off, text fallback on `SWTError`, the view description names which one is used; updates coalesced, never takes focus); tests AS-U1..U8. The JDT listener (running, paused, ended) moves to delivery 4, where the banner uses it.
3. **`report-format`** module (done 24 Nov, 19:59 trial): plain Java, no dependencies; `ReportFormat` with `html`, `markdown` and `codeSpans` (moved unchanged from the ChainTest writer), `MARKDOWN_CSS`, colour per step kind (`kindColour`, `kindCss`, `KINDS`) and per status (`statusColour`: running blue, OK green, refused amber, error red, other grey), `split` for reader and technical details. Report writer uses it (same output). The plugin embeds it as `lib/report-format.jar` (Bundle-ClassPath); the preview view now renders the agent's analysis from Markdown, colours steps like the report and wraps long lines. Tests RF-U1..U4 (RF-U1, RF-U2 = old RC-U4, RC-U6), RC-U4 (model and format agree), AS-U7 extended.
4. and 5. **The view, navigation and finishing touches** (delivered together 24 Nov at the user's request, waiting for check; the user's review of the preview drove the first part):
   - Opening and place: opens by itself at the start of an investigation with `VIEW_VISIBLE` (never activated, keyboard stays in the terminal), once per investigation (`ActivityViewOpener`); own area to the right of the Console in the Java and Debug perspectives (plugin.xml; a new or reset perspective), so neither the Console (front when the program prints) nor Variables (front at a stop) hides it.
   - Header: short title from the launch step, request cut to two lines and expanded on a click, local start time.
   - Live state: banner running (blue, pulsing, what the agent is doing, running timer) / finished (green, time taken) / ended without finish (amber); "The agent is thinking" or "looking at the paused program" between tool calls; program line from a JDT listener in `AgentDebugUiCoordinator` (running, paused at Class.method:line, ended; evaluations ignored) through `ActivityStore.program`.
   - Steps: colour bar per kind, running step blinking, refused calls amber; a click opens what happened at the step and, collapsed, its technical details (opened steps stay open across updates).
   - Current stop (last stop when finished): title, call chain, variables from the latest table at or after the stop, values changed since the previous table highlighted with "(was …)".
   - Links: `Class.method:line`, `Class, line N`, `Class.java:N`, with or without package, in titles, details, the stop, the program line and the analysis; a click opens the file at the line (`SourceOpener`: found by name in open projects, output folders skipped, `src/main/java` first; navigation only).
   - Warnings: program ended without a stop (from Eclipse); still waiting for a stop after 30 s and no agent activity for 2 minutes (computed in the page every second); live events dropped.
   - Toolbar: Follow (keeps the running step in sight, jumps to the analysis at the finish), Copy as Markdown (whole investigation, with the stop's variables), Copy analysis, Open report (system browser). Eclipse notification at the end (`org.eclipse.jface.notifications`, optional). Status bar: "Agent: waiting for a stop · 12 s", click opens the view (`AgentStatusControl`).
   - Page loaded once and updated in place by one script call (`ActivityPage.update`); links cannot navigate the view; text fallback shows the Markdown export. Tests AP-U1..U9, AS-U9.
   - Fix round 1 (24 Nov, user check: still stacked with Variables, no live update seen): `ActivityViewPlacement` moves the view into a stack of its own right of the Console's stack when it shares a stack (Eclipse 4 model, `EModelService.move`, as dragging a tab does), before it is shown for an investigation and on a status-bar click, so no perspective reset is needed; the view checks once a second that it shows the store's latest `revision()` and asks the page whether its script is there (`evaluate`), instead of relying only on the page-loaded event; script calls are no longer made inside the browser's own callbacks.
   - Fix round 2 (24 Nov, user's Claude trial at 20:30: live view works; "Program: running" while a thread was suspended; no current stop after `wait_for_debug_pause`): `follow()` reports the launch's real state (paused with location when an application thread is suspended, running otherwise, ended when terminated) instead of assuming running; a stop is any OK STOP step titled "Stopped…", "Paused…" or "Thread suspended…", its call chain from the stop or a later "Read call stack"; the panel is headed by the top frame.
   - Fix round 3 (24 Nov, user check at 22:50, Java EE perspective: view still in the Console's tab group): `ActivityViewPlacement` gets the model window and `EModelService` from the window, the workbench or the model window's context; placement also runs at the finish; every reason for not moving is logged to the Error Log with the manual alternative (drag the tab once).

### 2.3 Technology

- C1 The view's content is an embedded page (SWT `Browser`), styled like the report, with a native toolbar. Reason: same look and the same Markdown rendering as the report, quick visual changes. Check first: Edge (WebView2) on Windows inside an Eclipse view (same check as OPEN-33); fallback to native widgets if it fails.
- C2 Respect Eclipse light and dark themes.

### 2.4 Checks

- Unit tests: forwarder (queue full, Eclipse absent, slow bridge: tool results unchanged), store (bounds, per-investigation separation), event to view state (phases, finish, ended without finish).
- Manual with real trials: boot-quote and the calculator example; a run where the program ends without a stop (Spring Boot project not built, 24 Nov); agent disconnect; view closed during a run; reports off.
- Regression suite unchanged (23/23).

---

## 3. OPEN-37 Report: what actually happened

Replaces section 12 of the external design, of which only the parts below add value to the current report (decided 24 Nov):

- R37-1 **Breakpoints set versus hit**: a short summary (for example "OrderService:24 hit once; OrderController:21 never hit").
- R37-2 **"No stop was captured in this investigation"** when breakpoints were set but the program never stopped.
- R37-3 **Source line** under breakpoint and stop steps (`> 24 | int rate = percent / 100;`); needs the plugin to return the line text.
- R37-4 **Changed values highlighted** after a step, as in the view.
- R37-5 The analysis card labelled "Agent-provided conclusion, not independently verified" (a quiet subtitle).

Not taken from the external design: "last observed stop" card, key counts, grouping snapshots by stop (already the case), timeline filters, integrity footer.

## Terms

- **Current stop**: only while the agent's program is paused. After a resume it is the **previous stop**. The report shows all stops in order, never a "last breakpoint" card.
- **Finished** means `finish_debug_investigation` succeeded; it does not mean Claude has finished its answer.
