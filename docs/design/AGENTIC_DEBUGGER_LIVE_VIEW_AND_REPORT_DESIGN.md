# Agentic Debugger for Eclipse — Live Investigation View and Debugging Workspace

**Status:** Design / implementation handoff only — **NOT IMPLEMENTED**  
**Baseline inspected:** [`j2eeexpert2015/agentic-debugger-for-eclipse`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse), `main`, commit [`775ac2dbf5170570d685e5ecc2085dd18b1b557f`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/commit/775ac2dbf5170570d685e5ecc2085dd18b1b557f), retrieved 24 November 2026.  
**Purpose:** An actionable handoff for another coding agent or engineer, documenting verified code facts, problems, product behavior, implementation options and files, feature contracts, execution order, acceptance tests and open decisions.  
**Scope:** Eclipse UI/perspective behavior, a **live, evidence-based Agentic Debugger Activity view**, and the **matching completed HTML investigation report presentation**, for ordinary Java and Spring Boot, with an explicitly bounded future microservice extension. Preserve the existing debugger, MCP protocols and ChainTest report architecture.

> **Non-goals:** Do not build a new AI reasoning layer; do not infer what Claude is thinking; do not replace Eclipse's native Debug/Variables/Breakpoints views; do not implement distributed tracing or multi-JVM support as part of V1; do not silently modify the existing `examples/` fixtures. This document is a plan, not a claim that the UI or transport has been implemented or empirically validated.

---

## 1. User problem and the outcome we want

### 1.1 Present problems

1. The agent launches/debugs Java code, and Eclipse sometimes requests a switch to the **Debug perspective**. The switch may hide the Eclipse Terminal containing Claude Code. If the user stays in Java perspective, Debug/Variables/Breakpoints may not all be visible.
2. Even while debugging is progressing, users must look at Claude's terminal to see the MCP activity. There is no dedicated, continuously updated view inside Eclipse showing actual breakpoint placement/hits, stepping, inspected values, current execution location, or investigation completion.
3. Users need an immediate, visually reliable indication of **what the debugger actually observed**, especially a named variable or nested variable the agent explicitly expands.
4. Users should understand whether an investigation is still active, suspended, running, waiting for a debugger result, completed or abandoned **without guessing from inactivity**.
5. For layered Spring Boot code, users should understand which classes/methods were *observed* during the investigation, but not be shown a fabricated call graph. For multiple microservices, do not imply unsupported cross-process tracing.

### 1.2 Target end-user experience

- The current perspective and Claude terminal remain available; on a suspended agent-controlled launch the Eclipse editor navigates to the current line and **Debug, Variables, Breakpoints**, and where appropriate **Console** are present.
- Variables is the primary visual inspection view, and displays the correct selected stack frame. **Visual foreground/selection is distinct from stealing keyboard focus**; preserve typing in Claude's Terminal when feasible.
- A dockable **Agentic Debugger Activity** view provides (a) truthful current debugger state, (b) current observed source location and thread, (c) recently inspected variables, (d) breakpoint inventory with configured-versus-hit status, (e) an ordered, expandable activity timeline, (f) investigation completion/cleanup outcome, and (g) a link/path to an available HTML report.
- The custom view highlights only the exact nested variable path requested in `expand_frame_variable`; a broad `get_frame_variables` or `wait_for_stop` marks a **frame snapshot**, not one arbitrarily chosen variable.
- The view remains useful when reports are disabled and when no agent is connected, and never prevents debugger operations from completing.

### 1.3 Success criteria

A viewer should be able to watch a multi-class Spring Boot investigation, a core-Java conditional-breakpoint investigation, and a swallowed-exception investigation **from Eclipse**, understand the actions that truly happened, and know when the investigation was finalized. The activity UI is **not** a second chat transcript or speculative diagnostic agent.

---

## 2. Confirmed source-code findings and implications

All paths relative to repository root. Validate again if the working revision changes.

| Code/file | Confirmed behavior at baseline | Consequence for design |
|---|---|---|
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/ui/AgentDebugUiCoordinator.java` | `navigate()` executes `showPerspective(IDebugUIConstants.ID_DEBUG_PERSPECTIVE, window)` if `autoDebugPerspective` is enabled (default `true`), then opens/selects the Debug view frame, calls `DebugUITools.lookupSource/displaySource` (lines ~79–107). Its JDT event listener follows only the selected launch on SUSPEND. | The plugin itself is one cause of perspective changes. Keep its launch-scoped navigation and frame selection, but decouple that from switching perspective and ensure other debugger views are visible. |
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/ui/ClaudeIntegrationPreferences.java` | Existing checkbox **Automatically switch to Debug perspective for agent-controlled sessions**, default `true` (lines ~37–46). | Change effective default for new users only; preserve explicit user preference, and clarify UI behavior. |
| `eclipse-plugin/plugin.xml` | Registers `org.eclipse.ui.startup` and `org.eclipse.ui.preferencePages` only; no custom view declaration. | Add `org.eclipse.ui.views` extension for a new SWT/JFace `ViewPart`. |
| `eclipse-plugin/META-INF/MANIFEST.MF` | Requires core Eclipse UI/debug/JDT bundles, execution environment Java 21; plugin bundle version `1.5.0.qualifier`. | Existing UI dependencies suffice for a standard `ViewPart`/JFace viewer; verify exact API versions against the project's target platform, and add only any actually necessary dependencies. |
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/DebugStopTracker.java` | Subscribes to `DebugPlugin` events; recognizes SUSPEND/RESUME/TERMINATE, maps BREAKPOINT/EXCEPTION/STEP_END/PAUSED; its pending queue intentionally discards older/unreported stops on RESUME or when newer stops occur. | **Do not** treat its queue as durable event history. Do not consume it for the UI. Observe events separately or publish immutable events at an appropriate boundary. |
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java` | Implements `WAIT_FOR_STOP`, `GET_STACK`, `GET_VARIABLES`, `EXPAND_VARIABLE`, stepping/control, launch/cleanup. `WAIT_FOR_STOP` returns actual stop reason, location, thread, up to 5 frames and 50 top-frame variables; `GET_STACK` returns up to 60 frames; `GET_VARIABLES` up to 100 variables; nested expansion uses an explicit path. | Data already exists to populate the live view, **if captured and delivered**. Values are bounded/truncated. Avoid automatically evaluating expressions or deeper object graphs. |
| `mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/ReportingTools.java` | Wraps MCP tool calls **only when reports are active**; runs original call and subsequently records result/duration in a catch-all. | Reuse the semantic classification code but **do not make the live view dependent on `AGENTDEBUG_REPORT`**. Add a separate always-available, opt-in/controllable live sink with failure isolation. To show in-flight calls, emit a start event *before* dispatch and a result event *after*. |
| `mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/SessionReport.java` | Maps tool calls into report steps with descriptions, variables, HTTP responses, and cleanup; `get_investigation_report` retrieves finished report location; report is disabled unless configured. | Reuse semantic event classification, masking and reporting IDs; avoid scraping formatted HTML strings as live data. Don't destabilize current ChainTest adapter. |
| `report-model/src/main/java/org/learningfromexperience/agentdebug/report/ReportEvent.java` | Defines `InvestigationStarted`, `StepRecorded`, `InvestigationFinished`, `StepKind`, `StepStatus` with titles and **string details**, not a complete typed live-UI schema. | Maintain report backwards compatibility. Introduce a typed `LiveDebugEvent` model or backwards-compatible typed extension, rather than attempting to parse human-readable report cards. |
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/bridge/EclipseBridgeServer.java` | Authenticated localhost **request/response** socket, bounded/concurrent dispatcher and existing bridge command handling. | Existing channel is *not* a push-event bus. Prefer a small explicit live-event transport design; no unauthenticated socket or unsolicited messages on current command/response channel. |
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/AgentDebugStartup.java` | Starts the authenticated bridge and registers its lifecycle. | Register/close live receiver/observer with this lifecycle; ensure UI view may be opened/closed independently. |
| `eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/DebugInvestigationPolicy.java` | Investigations have ID/mode, associate with one launch, ownership/cleanup/expiry. | Partition events by investigation; do not conflate application processes or report global status as agent-owned. Multi-JVM microservice flow is not within current verified capabilities. |

**Separate Eclipse behavior:** Eclipse's own **Run/Debug → Perspectives** preferences can prompt/switch on launch or suspend, independent of `showPerspective()` in our plugin. Changing our plugin preference alone does not guarantee all prompts disappear. Test native Eclipse setting combinations.

**Proven versus proposed:** Existing debugger actions and HTML reporting are implemented; no custom live Activity view or live event transport was found at this baseline. No functionality described below should be marked implemented without tests in the actual IDE.

---

## 3. Product decisions and interaction design

### 3.1 Perspective and standard views

**Selected product direction:** *Keep current perspective* by default for agent-controlled sessions; offer *Switch to Debug perspective* as an explicit option.

- When the agent's selected launch **suspends**, open/show the Eclipse **Debug**, **Variables**, and **Breakpoints** views in the current perspective and navigate source. Use API IDs from installed Eclipse `IDebugUIConstants`; confirm constants against target platform. Show **Console** where useful; do not force-show every optional debug view (Expressions, Registers, Memory, Modules) because it produces clutter.
- Keep the native **Variables** view visually selected when suspended, but do not force keyboard focus away from Claude's terminal if avoidable. View activation and visible tab selection are separate concerns: opening a view non-activating does **not** guarantee the Terminal remains the selected tab if they share the same stack. Recommend a saved layout with separate stacks.
- Open/preserve the Debug view's selection provider and set it to the **correct agent-controlled suspended stack frame**, as existing code already does. This feeds the native Variables view; verify focus/selection behavior with JDT in real Eclipse.
- Do not switch perspectives on every SUSPEND event. Any optional explicit switch should happen once per selected launch/when requested, not repeatedly.
- Users should be able to undo or restore the layout. Do not permanently rearrange or overwrite the user's existing perspective without their consent. Offer a one-time **Prepare debugging views** command or optional dedicated saved **Agentic Debugging perspective** in a later phase.
- Existing users with an explicitly saved `autoDebugPerspective=true` setting should not silently have their choice overridden by a new default. Use `preferences.get(...)`/migration policy to distinguish unset from explicitly set values.
- Explain Eclipse-native prompting separately in a settings/help tooltip; do not silently override global native perspective preferences.

### 3.2 Layout (conceptual, not fixed positions)

```
+--------------------------------------------------------+
| Java editor — source location/active line               |
+--------------------------+-----------------------------+
| Debug — threads/frames   | Variables — selected frame  |
| Breakpoints tab/list     | Agent Activity (dockable)   |
+--------------------------+-----------------------------+
| Claude Code Terminal     | Application Console         |
+--------------------------+-----------------------------+
```

Allow user resizing/docking. On small displays, make Activity a collapsible tab beside (not a replacement for) Variables. The interface should not steal terminal keyboard focus on each new event.

### 3.3 Live Activity view — primary regions

**Header/status:** Investigation/application label, launch and client if known; state `NOT_STARTED`, `ACTIVE_NO_TOOL_IN_FLIGHT`, `TOOL_IN_PROGRESS`, `TARGET_RUNNING`, `TARGET_SUSPENDED`, `FINISHED`, `ENDED_WITHOUT_FINISH`, `ERROR/UNKNOWN`. These are **orthogonal** dimensions where appropriate: an investigation may be active while the target is suspended. Never present absence of MCP calls as proof Claude finished or is idle. Show connection state separately.

**Current observed location:** class, method, line, thread, stop reason, and timestamp. Mark as **historical snapshot** after Resume. Do not claim source location is live after suspension ends.

**Inspected values:** latest bounded snapshot from `wait_for_stop`/`get_frame_variables`; when `expand_frame_variable` is called, highlight the explicitly requested path and returned child values. Broad frame reads highlight the *frame snapshot*, not an imagined individual variable. Distinguish values obtained from runtime from hypothetical conclusions.

**Breakpoint inventory:** condition/type/source line, created/reused/removed if known, number of **confirmed observed hits**, and last hit timestamp; a configured breakpoint is not a hit. An unhit breakpoint is not automatically a mistake.

**Chronological event timeline:** expandable entries with timestamp, event kind, requested/accepted/completed/failed, location/values where available. Separate command requested/accepted from runtime stop observed. Include inspection, exception, launch, clean-up and report-available events. Filter by event type/thread; avoid excessive animation.

**Observed code journey:** optional compact ordered list of distinct stopped class/method locations. Arrows mean order of *observations*, **not direct Java invocation**, unless a recorded call stack establishes actual caller–callee relation.

**Finish section:** investigation explicitly finished versus ended without finish, cleanup details (owned breakpoints/JVM), errors or incomplete operations; optional **Open HTML report** when the existing report writer returned a verified path. Do not call it "Agent answered" without a client completion signal.

### 3.4 Application-specific presentation from common evidence

| Application | Relevant extras | Hard boundary |
|---|---|---|
| Core Java | Conditional breakpoint condition, affected record, loop stop, value history, exception details, method/frame journey | A breakpoint hit does not mean all loop iterations were traced. |
| Spring Boot | Controller/service locations, request thread, request method/path/status **only if actually supplied by an instrumented/test request**, exception/rejection and console context | No universal HTTP request metadata from ordinary JDT alone. |
| Microservice | Clearly identify the *one* launched JVM/service, local thread and observed source frames | Current one-launch-per-investigation design does **not** prove multi-service call paths. Cross-JVM tracing needs a separate validated feature, request correlation and possibly OpenTelemetry. |

### 3.5 Variable history V2

Compare values only when identification is reliable: investigation + launch + thread **stable identity** (not just index) + stack-frame execution context/method + variable path + observation order. `threadIndex` and `frameIndex` are positional and can change; thread name alone is not unique. Initial version may show value history per observed stop without asserting cross-stop identity. Later use explicit stop IDs and suitable JDT identity/lifecycle metadata. For `String` or object display representations, show changed text, not numeric deltas. A different value is **not evidence of a bug** without business context.

### 3.6 Visual style and interaction

- Native Eclipse SWT/JFace look; respect light/dark Eclipse themes and high contrast, no bright always-on flashing.
- Only current/selected event receives a visual accent; errors and genuinely exceptional statuses are differentiated by icon/text, not color alone.
- Select an event to show details; optionally navigate to source on explicit click, not automatically switch editors for every historical entry.
- UI should retain recent history for the current/finished investigation in memory (bounded); HTML reports are separate optional durable artifacts. Indicate lost events if transport queue overflows.

---

## 4. Architecture and transport: exact proposed changes

### 4.1 Separate two streams of facts

**Stream A — MCP tool activity (MCP JVM):** Tool started/completed/failed; tool name, argument identifiers, duration and masked result summary. Observe at **`ReportingTools` / common tool instrumentation boundary** independently of whether HTML reporting is enabled.

**Stream B — Eclipse JDT runtime activity (Eclipse JVM):** Actual suspend/resume/terminate, selected launch/thread/stack frame. Existing `DebugStopTracker` is not an audit stream; add a separate observer or publish immutable events when handled. Do not dequeue `wait_for_stop` events, do not execute blocking socket I/O in a JDT listener. Correlate by launch/investigation in Eclipse.

**Caveat:** MCP server and Eclipse process both see overlapping events. Design **deduplication/causality**, not naïve append. A Step Into command accepted by the bridge and the later `STEP_END` suspend are **different** events and should both appear. An MCP `wait_for_stop` result and an Eclipse SUSPEND event may represent **the same stop**: correlate by a stable stop sequence/ID when available and render one stop with attached inspection evidence rather than two unexplained duplicate stops.

### 4.2 Event model (proposed, not present as-is)

Use typed, immutable, bounded events with at least:

```
LiveDebugEvent {
  schemaVersion, eventId, investigationId, timestamp, origin, sequence,
  clientSessionId?, launchId?, launchName?, projectName?,
  eventType, phase, outcome, operationId?, precedingOperationId?,
  source?: { typeName, methodName, fileName?, lineNumber },
  execution?: { threadIdentity?, threadIndex?, threadName?, frameIndex?, stopId? },
  breakpoint?: { typeName, line, condition?, exceptionType?, owned?, created?, hit? },
  inspection?: { requestedPath?, frameSnapshot?, redactedVariables?, truncated? },
  durationMs?, errorCode?, safeMessage?, reportPath?
}
```

- `?` means optional and must be omitted when unknown; never fabricate missing values.
- No new server-generated diagnostic narrative. Use data from real tool/JDT observations only.
- Separate user-facing sanitized fields from raw internal bridge payloads. Sanitize/redact **before** forwarding to UI and writing files.
- Java `ReportEvent` is a suitable *semantic reference* but lacks several typed fields. Prefer a parallel `LiveDebugEvent` DTO and converter to existing report events (or a compatible model extension after checking all consumers).
- Preserve HTML report behavior unchanged when live view disabled/unavailable.

### 4.3 Transport alternatives — choose after a spike

**Option A — Extend authenticated localhost bridge with bounded live event polling (preferred initial spike).**

- MCP server sends sanitized activity event batches via a **new, explicitly validated authenticated command** (or a distinct authenticated endpoint) to the Eclipse bridge. Eclipse enqueues into an in-memory event store for the view. For tool-start events, delivery must happen asynchronously; do **not** synchronously round-trip in the MCP critical path.
- Actual JDT suspend/resume events are generated directly inside Eclipse and can reach the same event store without network transport.
- Eclipse UI subscribes to the event store and refreshes on SWT UI thread; the Activity view never contacts the MCP tool directly.
- Benefit: reuses authentication/port lifecycle and existing installation; tradeoff: bridge commands currently request/response and have carefully bounded dispatcher behavior. **Do not** overload the same long-running command queue or introduce recursion/deadlocks. If that cannot be proven safe, use Option B.

**Option B — Separate authenticated loopback event channel.**

- Bounded producer queue in MCP process, batched outbound delivery, handshake scoped to the current Eclipse instance, no network binding beyond loopback, independent transport executor, explicit connect/disconnect/replay states.
- More isolation from debug command latency, but additional installer/network complexity, port management, token rotation and tests. Pick only if A cannot satisfy non-blocking requirements.

**Do not** write live updates solely by tailing the ChainTest HTML report: that file is generated at/after finish, can be disabled, and is not a correct real-time protocol. A bounded JSONL file may serve diagnostics/replay but is not the default live delivery mechanism.

### 4.4 Threading and lifecycle requirements

- MCP tool execution: publish start/finish events into a non-blocking bounded queue; dropped event count visible; never make tool success depend on live UI delivery.
- Eclipse JDT listener: record small immutable stop metadata; any expensive JDT value expansion is done only for *existing* tool inspections. Never invoke arbitrary Java expression evaluation for UI.
- Eclipse UI: `Display.asyncExec` for updates, coalesce/reduce refresh frequency, never touch disposed widget or uninstalled plugin state; keep current selected event and scroll position unless user opts into follow-latest.
- On Eclipse restart: receiver starts cleanly; old open investigations are not automatically asserted alive. If persistence/replay implemented later, mark replay distinctly.
- On MCP disconnect: show `Connection lost` separately from `Investigation ended`, and show authoritative cleanup status when received. On lease expiration, show `ENDED_WITHOUT_FINISH`.
- No UI view open: retain bounded events but no UI refresh; debugger continues unaffected.
- Multi-investigation: strict ID matching; never publish unrelated workspace breakpoints or sessions into another investigation.

---

## 5. Concrete code-change map

These are targets, **not patches already applied**. Names for new classes are suggestions, subject to target-platform API checks.

| File / module | Proposed change | Reason and expected outcome |
|---|---|---|
| `eclipse-plugin/.../ui/AgentDebugUiCoordinator.java` | Make explicit perspective switch optional and **unset default false**; add `ensureDebugViewsVisible(page, launch, frame)` that opens Debug, Variables, Breakpoints (Console when applicable) in the current perspective; retain existing frame selection/source navigation, throttle repeated UI work. | Terminal preserved; correct source and native Variables view remain available. |
| `eclipse-plugin/.../ui/ClaudeIntegrationPreferences.java` | Default new installs to *Keep current perspective*; add behavior description, optional **Prepare debugging views**, live-view enable/disable and follow-latest preference; preserve explicit saved `true`. | Predictable and user-controlled layout. |
| `eclipse-plugin/plugin.xml` | Register `<extension point="org.eclipse.ui.views">` and a view ID, e.g. `org.learningfromexperience.eclipse.agentdebug.views.activity`. | View appears in Window → Show View → Other and is dockable. |
| `eclipse-plugin/.../ui/AgentDebugActivityView.java` (**new**) | Implement Eclipse `ViewPart` with header, current location, inspected values, breakpoint summary, filterable timeline, finish/report link, selected event details. Use `TreeViewer`/`TableViewer` or SWT `Composite`, not web UI by default. | Developer sees live investigation without the terminal. |
| `eclipse-plugin/.../activity/LiveEventStore.java` (**new**) | Bounded, investigation-scoped event store; subscribers; deduplication; retention and state reduction; lost-event counter. | Correct temporal UI state even with no visible view. |
| `eclipse-plugin/.../activity/LiveEventReceiver.java` (**new**) | Authorized inbound event command handling and validation, no blocking display work. | Safely accepts MCP-originating events. |
| `eclipse-plugin/.../bridge/EclipseBridgeServer.java` | Add optional *authenticated* event-ingress command/path, with strict size/queue/capability limits and dispatcher isolation; measure latency. | Delivery into Eclipse without changing debugging tool request/response semantics. |
| `eclipse-plugin/.../debug/DebugStopTracker.java` or adjacent **new** JDT observer | Publish immutable observed suspend/resume/terminate facts into event store; do not change existing queue semantics. | Accurate hits, stop reasons and lifecycle transitions. |
| `eclipse-plugin/.../AgentDebugStartup.java` and `Activator.java` | Initialize/close live receiver/event services with bridge lifecycle, with UI-independent state. | Safe startup/shutdown and cleanup. |
| `mcp-server/.../ReportingTools.java` | Extract general tool instrumentation, independent of reporting switch, with *start* and *end* events; preserve existing HTML report callback/result. | Accurate current-op indicator and tool outcome even when HTML reporting off. |
| `mcp-server/.../SessionReport.java` | Reuse event classification and redaction functions carefully, without coupling live event delivery to renderer internals. | Semantic consistency between HTML and live view. |
| `report-model/.../LiveDebugEvent.java` (**new or dedicated shared model**) | Typed schema + sanitization/serialization tests. | Consistent, safe cross-process message contract. |
| Installer/package configs (as needed) | Ensure changed Eclipse bundle/update site and bundled MCP runtime ship matching protocol versions. | Prevent mixed-version live UI failures; old MCP remains usable for debugging. |
| `docs/` | User configuration, layout screenshots only after real IDE validation, status definitions, compatibility and troubleshooting. | Supportable feature; no inaccurate promises. |

**Critical compatibility note:** The MCP process may not be on the same plugin version as Eclipse during an upgrade. Perform protocol feature detection, tolerate unknown event fields and disable the live feed gracefully if unsupported. Never break normal debugging because the view is absent.

---

## 6. Detailed feature contracts and acceptance criteria

### F-01 — Perspective preservation and standard views (P0)

**Given** an agent-owned Java debug session and an Eclipse Java perspective containing Claude terminal, **when** execution suspends, **then** Debug, Variables and Breakpoints are available in the *current perspective* and the editor navigates to the actual stopped line. The terminal session remains open. Selecting the stack frame populates Variables. Avoid unsolicited keyboard-focus theft and repeated perspective changes.

**Tests:** Java perspective + terminal docked; native launch/suspend perspective preferences configured both `Prompt` and `Never`; explicit plugin preference false/true; source navigation; window focus; close/reopen views; reused debug session; no active page. Check on Eclipse 2024-03 and 2024-06 or whichever oldest targets the project actually supports, plus current Eclipse. Avoid claiming compatibility without running.

### F-02 — Investigations and honest status (P0)

Display investigation start/finish with exact ID and client, known launch, connection and JVM execution state separately. A running JVM is not necessarily an executing MCP command; a suspended JVM is not necessarily a finished investigation. Tool-start/completion events provide in-flight status; no events means **No debugger operation in progress**, not **Claude thinking**. When `finish_debug_investigation` succeeds, show `Investigation completed`; on timeout/disconnect/idle cleanup show the actual observed outcome, without falsely asserting final chat completion.

**Tests:** short normal run, controlled mode (leaves session suspended), exception, tool failure, bridge timeout followed by `get_bridge_operation`, agent disconnect, lease expiration, unsuccessful cleanup, two concurrent sessions. No false successful finish.

### F-03 — Breakpoint lifecycle (P0)

Show `CREATED`, `ALREADY_EXISTS`/`REUSED` (when returned), `HIT`, `REMOVED`/`PRESERVED` based on confirmed events. Count actual hits by correlated stop ID, not set commands; support line, conditional and exception breakpoints; tracepoint evaluation/output only when its supported operation returns corresponding facts.

**Tests:** duplicate breakpoint, user-owned breakpoint, agent-owned breakpoint, conditional false/true, repeated stop on same line, exception breakpoint, cleanup in NORMAL/CONTROLLED, resume before `wait_for_stop` call.

### F-04 — Runtime location and inspected values (P0)

Use exact `wait_for_stop`/`get_frame_variables`/`expand_frame_variable` evidence, source/line/thread, timestamps and bounds. On an explicit `expand_frame_variable(path)`, highlight that path and display actual returned children; a broad inspection shows a frame snapshot. If no locals are available, render the reason rather than a blank or guessed value. After resume, mark old snapshots as **captured previously**.

**Tests:** primitive, `null`, nested object, collection child index, large/bounded objects, missing local/native frame, a changed stack frame, invalid path and unreadable variable. Redaction test for keys/passwords/tokens and privacy-sensitive patterns.

### F-05 — History and observed journey (P1)

Expandable timeline chronological by time/sequence, one row per meaningful user-visible operation, filter by kind/thread; distinguish action request, acceptance, and JDT-observed outcome. Compact distinct stopped method/class path; **never label mere observation order as a proven invocation edge**. Clicking a historical event may navigate to source if still resolvable, but should not change debugger selection silently.

**Tests:** same class reached repeatedly; step across three methods; asynchronous stops on two threads; editor navigation when source missing; repeated/replayed events; event loss indicated clearly.

### F-06 — Value observations and comparisons (P1/V2)

Store bounded observations per stop. Permit selected path history where identity is stable; don't automatically equate same-named locals in separate method calls. Show text/value changes only between comparable observations. No arbitrary anomaly scoring or inferred business correctness.

**Tests:** mutable local across repeated stop, same local name in two frames, two threads with same name, object `toString()` truncation, loop iterations, nested paths and index shifts.

### F-07 — Exception and diagnostic context (P1)

Show exception breakpoint's type, observed location, thread and relevant stack; differentiate an exception thrown by application code from an MCP/bridge failure. Show bounded process output if agent retrieved it, never auto-tail every application console indiscriminately.

### F-08 — Optional HTML report integration (P1)

If a finished investigation has a report path via existing `get_investigation_report`/recorded report location, show an **Open report** action with verified local existence. Reporting disabled or failed should not disable the activity view. The live view and report need not have identical granular events but must never contradict core observations.

### F-09 — Application context and microservice boundaries (P2/P3)

- Java: loop/record values only as actually inspected.
- Spring: request info only from explicit safe HTTP reproduction tool/test or other verified instrumentation, never inferred from a thread name.
- Microservice: show identity of **current** debugged process/service; show `Not correlated` for other services. Explicitly defer multi-launch and distributed request correlation.

### F-10 — Performance, security and resilience (cross-cutting P0)

Debugger must function unchanged if live view is disabled, view closes mid-run, event sink is full, receiver is unavailable, event data malformed, or HTML renderer fails. Bound event count, payload size, value count/length, retention period and UI refresh frequency; log dropped counts. Use loopback + existing auth/isolated channel, never expose raw socket to LAN. No code evaluation triggered by the UI. No unapproved recording of unrelated Claude terminal content.

---

## 7. Recommended implementation order and gates

**Stage 0 — Baseline and test inventory (no changes).** Record commit, build/e2e status, target Eclipse version matrix, compare installed plugin/runtime versions, capture a reproducible S2 terminal-disappears scenario. Ensure `examples/` remain unchanged. **Gate:** baseline and exact reproduction documented.

**Stage 1 — Fix perspective behavior with native views only.** Update UI coordinator/preference handling; offer a safe layout path; test Debug/Variables/Breakpoints/editor/Terminal. **Gate:** demo works with no custom activity view; keyboard and visible-tab behavior checked manually.

**Stage 2 — Transport spike (no polished UI).** Publish one `TOOL_STARTED`, one `TOOL_COMPLETED`, and one `STOP_OBSERVED` from an MCP+Eclipse session into a diagnostic Eclipse-side bounded store. Verify no debugging latency regressions and no bypass of authentication. Pick A/B transport based on evidence. **Gate:** all three events have correct investigation/launch and order; debugger continues if transport disabled.

**Stage 3 — Minimal Activity view (V1).** Register `ViewPart`; header/current location, timeline, breakpoint status, returned variable snapshot, explicit variable-path highlight and completion. Status state machine with no inference. **Gate:** scenario-specific acceptance tests below passed.

**Stage 4 — Navigation/history (V2).** Observed method journey, filter controls, historical variable comparison with strong identity limits, navigate-to-source, HTML report integration. **Gate:** tests for duplicate hits, repeated invocations and multi-threading show no misleading comparisons.

**Stage 5 — Optional Spring request metadata and advanced contexts.** Only if existing reliable tools provide request and HTTP response facts. **Gate:** no fabricated cross-service trace; microservice support remains separately tracked.

Never combine all stages into one unreviewable change. Small commits and regression runs after each stage.

---

## 8. Concrete real-world validation scenarios

### T1 — Java batch: one wrong customer among 1,000

**Initial state:** Java perspective, Claude Terminal visible; Activity view open; no active debugging. Agent discovers/processes scenario and sets conditional breakpoint.

**Expected observation:** Condition, target class:line, `BREAKPOINT_CREATED` then actual hit with stopped thread, affected customer variables returned, explicit nested expansions highlighted. On resume, the captured values change to *historical*. End outcome explicit. **Failure:** all 1,000 records are misleadingly presented as inspected, or Variables tab empties/hides terminal.

### T2 — Java importer: swallowed exception

**Expected observation:** Specific exception breakpoint creation; an actual `EXCEPTION` stop with exception type, stack/source; inspected input as returned; normal Java output may say import completed but the view still shows the breakpoint evidence. **Failure:** wrongly shows a normal application exception as an MCP tool error, or claims an exception it never observed.

### T3 — Spring Boot shipping decision

**Expected observation:** Class/method timeline based on true stop evidence (`OrderController`, `OrderService`, `PricingService`, `ShippingService`, as actually encountered), runtime amounts displayed from retrieved variables, source/Debug/Variables visible, Claude terminal retained, breakpoint inventory created-versus-hit distinct, explicit finish and cleanup. **Failure:** shows a made-up Controller→Service call edge or says Claude has finished while investigation is still open.

### T4 — Concurrent Spring requests

Run two HTTP requests while different request threads suspend. **Expected:** per-thread stops, no mixing of variable snapshots; event correlation explicit. If request ID is unavailable, UI says **Request not correlated**. **Failure:** values from different threads shown as one ordered business transaction.

### T5 — Robustness

Disable live view, leave reporting disabled, force event receiver failure, close view during step, overload queue, restart Eclipse, disconnect MCP, trigger expiry and command timeout. **Expected:** existing debugger and cleanup work; errors/loss are displayed or logged accurately; no false completion or deadlock.

### T6 — Compatibility and release

Build Maven modules/update site with Java 21; install the built ZIP into the actual supported Eclipse target(s) including older supported versions; check runtime bundle identity and MCP server version; run existing 23 direct regressions plus bridge, stop and report tests, then live Claude trial(s) only with user approval when paid token usage is involved. The README's numbers are historical evidence, not substitutes for validating this new change.

---

## 9. Risks and decisions requiring explicit engineering review

1. **Eclipse Variables tab foreground versus Claude keyboard focus:** these can conflict in a shared tab stack. A suggested layout is a product recommendation; actual SWT/JFace behavior requires IDE validation. Never guarantee both if window/tab stacking prevents it.
2. **Transport bottleneck:** the bridge's existing command dispatcher has deadlines and busy control; a high-volume event feed could undermine it. Transport spike must demonstrate isolation and bounded loss.
3. **Deduplication and ordering:** timestamps from separate processes are not sufficient; use IDs/sequence and causal links where available. Missing/duplicated events must be visible, not silently normalized.
4. **State semantics:** `Investigation finished` is not the same as Claude's full response being complete. Current client-independent MCP cannot reliably know that.
5. **Data sensitivity:** variable snapshots and HTTP payloads may include credentials/PII. Reuse/extend redaction, masking and retention, and prefer default-limited capture.
6. **Compatibility:** Eclipse version and available debug/UI API constants must be checked against supported targets; do not use internal Eclipse viewer API just to color native Variables rows.
7. **Native perspective prompts:** Eclipse's own launch/suspend preferences remain distinct from the plugin's switch logic.
8. **Report lifecycle:** current HTML report is off unless `AGENTDEBUG_REPORT` is set; live UI must not depend on ChainTest or finished HTML.
9. **One launch per investigation:** current runtime policy does not support a truthful distributed microservice journey.
10. **Existing users:** a changed default cannot be treated as permission to change an explicitly configured preference or saved workspace layout.

---

## 10. Implementation checklist for the coding agent

- [ ] Verify repository HEAD and all code locations against this baseline; update document if changed.
- [ ] Capture and document current perspective/terminal failure reproducibly in Eclipse.
- [ ] Implement safe default-current-perspective behavior with migration-aware preference handling.
- [ ] Ensure native Debug, Variables, Breakpoints and source navigation within current perspective.
- [ ] Validate visual Variables foreground without unwanted terminal keyboard focus; document layout caveats.
- [ ] Design and unit-test typed bounded `LiveDebugEvent` contract and redaction.
- [ ] Implement async MCP command-start/finish event publisher independent of HTML reporting.
- [ ] Implement independently observed JDT stop/resume/terminate event feed and deduplication.
- [ ] Select and implement authenticated, failure-isolated live transport after spike.
- [ ] Implement activity event store, connection/status reduction, bounded history and dropped-event indication.
- [ ] Register and build dockable SWT/JFace Activity `ViewPart`.
- [ ] Show exactly observed location/values, explicit expanded path, breakpoint lifecycle, timeline and completion.
- [ ] Preserve source navigation and user-owned session/breakpoint isolation.
- [ ] Add optional observed code journey and historical value comparison only after identity correctness tests.
- [ ] Integrate optional completed HTML report opening without requiring reporting to be enabled.
- [ ] Run all specified scenarios and negative tests; record **observed outcomes**, screenshots after implementation, target versions and commit IDs.
- [ ] Keep `examples/` unchanged and refrain from unrelated debugging/MCP behavior changes.

---

## 11. Final scope decision

**Approved design direction for implementation planning:**

1. **P0:** Current perspective retained by default, standard Debug/Variables/Breakpoints/editor made visible, no terminal disappearance caused by our own forced switch; safe user preference.
2. **P0:** New independent **Agentic Debugger Activity** view with precise debugger status, verified source location, actual runtime observations, breakpoint hits, timeline and completion/cleanup.
3. **P1:** Observed method journey, scoped value history, navigation to source and optional HTML report link.
4. **Deferred:** Custom coloring of Eclipse's native Variables tree, continuous full JVM tracing, generic HTTP tracing and multi-JVM/microservice graph.

**Quality bar:** If another agent implements this, it must demonstrate the view using actual Eclipse MCP/JDT events, not prefilled demo data or inferred AI reasoning. It must distinguish **tool requested**, **tool completed**, **debugger actually stopped**, and **investigation finished**. UI recording must never delay, terminate or corrupt real debugging.

### Source links

- [Repository](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse)
- [Baseline commit](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/commit/775ac2dbf5170570d685e5ecc2085dd18b1b557f)
- [`AgentDebugUiCoordinator.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/ui/AgentDebugUiCoordinator.java)
- [`ClaudeIntegrationPreferences.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/ui/ClaudeIntegrationPreferences.java)
- [`EclipseBridgeServer.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/bridge/EclipseBridgeServer.java)
- [`EclipseAgentOperations.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java)
- [`DebugStopTracker.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/DebugStopTracker.java)
- [`ReportingTools.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/ReportingTools.java)
- [`SessionReport.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/SessionReport.java)
- [`ReportEvent.java`](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/blob/main/report-model/src/main/java/org/learningfromexperience/agentdebug/report/ReportEvent.java)

---

## 12. Detailed visual design — live investigation and completed HTML report

**Status:** Product/engineering specification, **not implemented by this document**. The earlier MVP patch, if applied, implements only a subset; verify the target branch before coding. This section expands F-02 through F-08 and is the source of truth for the visual content and evidence semantics of both surfaces.

### 12.1 Product distinction: a live workspace vs. an investigation report

Two views of **the same observations**, not two independently invented explanations:

| Question | Live Eclipse Activity view | Completed self-contained HTML report |
|---|---|---|
| What is happening? | Current **tool operation** (when observable), debugger target state, latest observed stop | Final session outcome, recorded events, duration, completeness |
| Where? | Current suspended location, otherwise **last observed location** with a historical label | Each observed stop with source, method, thread and timestamp; **last observed stop** summary |
| Which variables? | Most recent inspected or returned snapshot; expanded path emphasized | Per-stop snapshots plus optional comparable-value history |
| How did we get here? | Recent events, progressively accumulated | Full retained ordered timeline and optional observed-method path |
| What happened to breakpoints? | Created/hit/removed/current, as confirmed | Final inventory, hit counts, removed/preserved states |
| What was the result? | Investigation completed/abandoned, cleanup summary; may show report link | Conclusion **only if explicitly submitted**, cleanup facts and evidence trail |
| How is it delivered? | Eclipse `ViewPart` via bounded live feed | Existing ChainTest-backed self-contained report (when reporting is enabled) |

**Never** describe the last observed line as the JVM's current location after it has resumed or terminated. **Never** equate “investigation finished” to “Claude has finished replying.” The report's AI-provided `summary` field is an attributed statement, not a debugger-verified diagnosis.

### 12.2 Canonical example: Order Management / incorrect shipping charge

**Problem presented:** “An order of ₹600 is charged ₹50 shipping, although orders above ₹500 qualify for free shipping.” This is a *planned illustration*, not evidence of an actual run. The numbers, source locations and counts below must only appear in a real report if corresponding events/values were returned from the debugger. Placeholder line numbers are intentionally omitted.

**Preferred header:**

```text
AGENTIC DEBUGGER — INVESTIGATION REPORT                  [FINISHED]
Order Management / Spring Boot
Investigation: <short ID>     Duration: <measured>     Client: <observed>

REQUEST FROM USER
An order of ₹600 is charged ₹50 shipping, although orders above
₹500 qualify for free shipping.

LAST OBSERVED BREAKPOINT                                 [HISTORICAL]
ShippingService.calculateShipping()   ShippingService.java:<actual-line>
Stop reason: BREAKPOINT    Thread: <actual-thread>    Observed: <time>

INSPECTED VALUES (AT THAT STOP; NOT LIVE)
  originalSubtotal   600.00  (if actually present/inspected)
  eligibilityAmount  480.00  (if actually present/inspected)
  threshold          500.00  (if actually present/inspected)
  shippingCharge      50.00  (if actually present/inspected)

BREAKPOINTS: <N created> / <M hit> / <K removed>
STOPS: <actual count>    STEPS: <actual count>    FAILED CALLS: <actual count>

OBSERVED JOURNEY (OBSERVATION ORDER, NOT A CALL GRAPH)
[OrderController] --> [OrderService] --> [ShippingService]

EXPANDABLE EVENT TIMELINE
  - Breakpoint created (class:line)                         Accepted
  - Debug launch started                                   Accepted
  - JVM stopped (class:line, thread, reason)                Observed
  - Frame inspected (variable snapshot)                    Returned
  - Step/Resume requested                                  Accepted
  - JVM stopped at next location                           Observed
  - Investigation finished (cleanup details)              Confirmed

AGENT-PROVIDED CONCLUSION (OPTIONAL — NOT INDEPENDENTLY VERIFIED)
<summary explicitly passed to finish_debug_investigation, if present>

REPORT INTEGRITY
Events captured: <N>   Dropped: <N>   Missing fields: <explicit>
Evidence source: MCP results + JDT observations where instrumented
```

The real display should use responsive sections/cards and sensible typography, **not** a giant wall of console text. Keep the user request, last observed stop and final outcome above the long timeline. Empty fields are hidden or explicitly “Not captured”, never filled with sample values.

### 12.3 Section-by-section report behavior and value

#### RPT-01 — Investigation identity, user request and outcome (P0)

- Show the application/project or launch **only when returned by the launch command or associated investigation**. Record mode, client identifier when available, start time, finish time and elapsed duration.
- Show **the exact prompt** only when captured by an authorized client/test harness (`requestExact=true`); otherwise label the `begin_debug_investigation.request` text **Agent-supplied request summary**. Do not re-label a paraphrase as the original user prompt.
- Show `FINISHED`, `ENDED_WITHOUT_FINISH`, or `INCOMPLETE / UNKNOWN`, based on known lifecycle facts. A successfully finalized investigation may still contain failed tool calls; do not label the entire investigation “passed” unless a separately defined evaluation actually passed.
- Completion and cleanup are distinct: report *which* agent-owned breakpoints were removed, whether a JVM actually ended, and whether a controlled investigation intentionally preserved the session. If cleanup verification fails, show a warning rather than a green completion claim.

**Source:** Existing `ReportEvent.InvestigationStarted` and `InvestigationFinished`, `SessionReport` and cleanup responses. **New work:** polished header and integrity labels; no new AI call.

#### RPT-02 — Last observed breakpoint/stop (P0)

- Dedicated summary at the top, titled **Last observed stop**; if the reason was a breakpoint, display **Last breakpoint hit** as the subtype. Do not assume the latest stop was a breakpoint: it might be `STEP_END`, `EXCEPTION`, `PAUSED`, or `SUSPENDED`.
- Display actual class, method, line, stop reason, thread, relative/event time, and origin **only if present**.
- Distinguish **breakpoint created** from **breakpoint actually hit**. A hit must come from a correlated JDT stop or a successful stop result; a `set_breakpoint` response by itself never counts as a hit.
- Snapshot variable values tied to **that stop**. Inspection from a different thread or newer stop must not silently replace them. After resume or finish show **historical**; in the report everything is historical.
- If zero stops were observed, show **No debugger stop captured** rather than inventing a current location.

**Source:** `wait_for_stop` / `DebugStopTracker` and inspection replies. **New work:** typed correlation and the top-of-report card, using captured facts only.

#### RPT-03 — Key debugger metrics (P0)

Use measured counts, not proxy claims:

- Configured breakpoints; unique breakpoint locations actually hit; total **observed** stops (not all VM suspensions unless the recorder actually captures all of them); agent step/resume commands attempted and accepted; variable-inspection operations returned; tool failures/timeouts; duration.
- Keep “Breakpoints created: 4” distinct from “Locations hit: 3”. Include definitions via concise tooltips or report legend.
- If the source only records completed MCP tool calls, label them **MCP-reported stops**, not *every JDT stop*. When a JDT observer is added, deduplicate by stable stop/event ID.

#### RPT-04 — Inspection snapshots and explicitly expanded variables (P0)

- Group snapshots under the stop/stack frame/thread from which they were read, not under a global flat variable list.
- `wait_for_stop`: record its **returned** bounded top-frame locals; `get_frame_variables`: returned frame data; `expand_frame_variable`: show the explicitly requested path and actual returned children.
- A variable expansion gets a visible **Agent expanded: `order.customer`** label. A broad frame-read operation gets **Frame variables inspected**; do not highlight one variable as the agent's mental focus.
- Represent unavailable local variables, truncation, unreadable values and redaction explicitly. Avoid expanding object graphs automatically or evaluating expressions just for the report.
- When source and time identities are unambiguous, show adjacent snapshots and (V2) value changes; otherwise do not calculate misleading diffs across unrelated method invocations or threads.

**Suggested visual card:**

```text
Stop #3 — ShippingService.calculateShipping() — thread http-nio-... 
┌──── Inspected values (snapshot from stop #3) ───────────┐
│ subtotal            600.00                              │
│ eligibilityAmount   480.00     [explicitly inspected?]  │
│ freeThreshold       500.00                              │
│ order               {...}       [expanded path if asked]│
└──────────────────────────────────────────────────────────┘
```

The `[explicitly inspected?]` indicator must only be used when the tool argument proves a specific variable/path was requested; otherwise list it as a returned snapshot member.

#### RPT-05 — Breakpoint inventory with hit status (P0)

A compact table with: type (line/conditional/exception/trace), class/source line, condition if safely showable, ownership, **created/reused**, actual observed hit count, last hit time, removed/preserved. Separate source marker existence at the end from session history. Never mark a user-owned marker as removed by the agent. A tracepoint's logged output is not a suspension and must not count as a breakpoint hit unless separately observed as such.

#### RPT-06 — Observed class/method journey (P1)

- Show an **observed stops path**, e.g. `Controller → Service → ShippingService`, only if these locations were actually observed. Label arrows **next observed stop**, not **called next**.
- If the report has stack frames, offer a separate **Captured call stack at this stop** display to show call relationships backed by actual stack evidence.
- Repeated stops: group visually but preserve counts; do not hide differences across threads. On a different thread, show a clear thread boundary.
- For HTTP apps, show route/status only when captured from an approved request tool or explicit instrumentation. Thread names do not prove HTTP route or request identity.
- Multi-JVM distributed flow remains **unsupported** without launch correlation and tracing instrumentation; never invent service arrows.

#### RPT-07 — Chronological, expandable action timeline (P0)

Keep exact event order and, where available, an event sequence number. Each row shows timestamp, type, source, semantic title, outcome, optional duration and expandable redacted details. Distinguish:

1. **Requested:** agent issued a tool call (requires a start event if in-progress visibility is needed).
2. **Accepted / returned:** the MCP or Eclipse bridge returned successfully, including a command that was accepted without reaching an execution destination yet.
3. **Observed:** JDT actually suspended/stepped/resumed or returned a specific frame snapshot.
4. **Failed / timed out:** use the actual error or timeout result; after `BRIDGE_TIMEOUT`, an operation status check may resolve completion later.
5. **Ended:** investigation lifecycle and cleanup.

**Do not equate successful `control_debug_session(STEP_INTO)` with reaching a new method.** A subsequent observed location establishes that.

Filters for `All`, `Stops`, `Breakpoints`, `Inspections`, `Steps`, `Errors`; a thread selector when multiple threads observed; collapse repetitive routine status reads. Keep raw tool name in expandable details when useful for support, but human-readable action titles by default.

#### RPT-08 — Exceptions, errors and evidence completeness (P1)

- **Application exception** (`EXCEPTION` stop) and **debugger/MCP failure** are distinct types; never merge their counts.
- Show partial outcomes (`TIMEOUT`, `TERMINATED`, disappeared thread, missing frame, redacted variable) without false success.
- Count dropped or unrecorded events where measurable. Display a report-integrity footer with writer status, known dropped count and any missing user prompt/final conclusion.
- The completed report should still render when the investigation ends unexpectedly; mark it incomplete where appropriate.

#### RPT-09 — Agent-provided conclusion, with attribution (P0 where supplied)

Existing `finish_debug_investigation.summary` is the legitimate source for a closing analysis. Show as **Agent-provided conclusion**, visually separate from debugger observations. If omitted, show **No conclusion supplied** or omit the section. Never ask another model to create a conclusion as part of HTML rendering. Store/render only appropriately redacted information; escape HTML, Markdown and code markup to prevent script injection.

#### RPT-10 — Usable self-contained report (P0)

- Preserve existing single-file HTML behavior; no live server or remote CDN required to open it.
- At top show the problem, current/final status, **last observed stop**, meaningful metrics, key inspected snapshot, optional conclusion; timeline below.
- Fast browsing: sticky/minimal navigation, accordions for stop details, timeline filters; usable in both a browser and on a laptop screen.
- Avoid flashing animation, irrelevant dashboard charts, “ChainTest”/testing UI metadata not relevant to debugger users, and screenshots by default.
- Source path links require validation. An HTML opened on a different machine cannot be expected to open local Eclipse workspace resources; render source coordinates as text and avoid broken links or untrusted `file://` navigation.

### 12.4 Common data semantics and technical source of truth

**Do not build the report by scraping the live Eclipse widgets**, and do not build the live view by scraping rendered ChainTest HTML. Both should consume typed, versioned, redacted facts produced from existing commands and JDT observations.

Suggested immutable event identity (adapt to real APIs):

```text
investigationId   unique investigation identity
sequence          monotonic per investigation, where producer can supply it
timestamp         when event was observed/accepted (not necessarily JVM timestamp)
producer          MCP_SERVER | ECLIPSE_DEBUGGER
operationId       identifies a requested tool and its later result, when available
launchId          specific launch identity (not launch name alone)
threadId          stable within launch if available (name is display-only)
stopId            stable stop/observation ID for attaching variable snapshots
eventType         TOOL_STARTED | TOOL_FINISHED | BREAKPOINT_CREATED |
                  BREAKPOINT_HIT | STOP_OBSERVED | VARIABLE_READ |
                  VARIABLE_EXPANDED | RESUME_REQUESTED | STEP_REQUESTED |
                  TARGET_TERMINATED | INVESTIGATION_FINISHED | ...
source            class, method, line when known
payload           bounded typed facts (sanitized)
status            REQUESTED | ACCEPTED | OBSERVED | FAILED | INCOMPLETE
```

These are **proposed** fields, not claims that the current `ReportEvent.StepRecorded` already includes them. Current model fields are title + string details, and `ReportingTools` wraps only completed calls when HTML reporting is on. Preserve the existing reporting interface and writer tests; use adapters/optional fields rather than breaking its public event contract.

**Source ordering and deduplication:** MCP tool results are not a reliable total ordering of JDT events across threads. Record producer event sequences and causal correlation where supported; when not supported, show timestamp-order with an uncertainty note. Do not count one `wait_for_stop` plus the corresponding JDT `SUSPEND` as two stops.

**Data minimization:** Reuse redaction policy, cap string size, variable count, events per investigation and rendered report size. Mask obvious secret names and configurable sensitive patterns before publishing to either sink. Do not store full prompts/terminal content without an enabled capture policy and clear scope.

### 12.5 Expected behavior in three distinct applications

| Scenario | Primary report sections | User-visible value | False claim to avoid |
|---|---|---|---|
| **Core Java — one wrong customer among 1,000** | Condition and hit of customer-specific breakpoint; affected record locals; nested path expansion; actual steps | Shows how agent isolated one record efficiently | “Agent examined 1,000 records” when only one stop was observed |
| **Core Java — silent import failure** | Exception breakpoint, caught/uncaught details where available; actual stack and inspected offending input; normal output if explicitly retrieved | Makes a hidden runtime failure visible | Treating application exception as an MCP error |
| **Spring Boot — wrong shipping** | Last observed service/method; snapshots of subtotal, discounted amount and shipping eligibility; observed cross-class timeline; HTTP result if captured | Makes a multi-layer investigation understandable from evidence | Implying a distributed call graph or attributing unobserved variables |

The same report template adapts to Java and Spring Boot based on available evidence; no framework-specific hardcoded `OrderService` field or preprogrammed cause.

### 12.6 Implementation mapping — additions to Section 5

| Component | Required work for this visual contract | Acceptance signal |
|---|---|---|
| `mcp-server/.../SessionReport.java` | Expose typed, redacted facts for breakpoint, stop, variable snapshot, operation outcome and lifecycle; retain current textual report steps | Existing report tests remain green and new typed facts are available |
| `mcp-server/.../ReportingTools.java` | Capture operation start/end for live UI independently of HTML-report flag; ensure ordering/correlation, with failure isolation | Live view works with `AGENTDEBUG_REPORT` disabled |
| `report-model/.../ReportEvent.java` or new typed model | Add stable stop identity/correlation + bounded typed observation payload, without breaking old consumers | Two tool inspections at different stops never share values incorrectly |
| `report-chaintest/` | Extend HTML renderer with last-stop overview, breakpoint inventory, observed journey, snapshot display, timeline/status and missing-data labels | A standalone report opens offline and all visual claims match events |
| `eclipse-plugin/.../ui/AgentDebugActivityView.java` | Surface **current** location, latest inspection, last breakpoint hit, breakpoint state and timeline; keep keyboard focus behavior | Real Eclipse view remains readable and truthful during resume/finish |
| `eclipse-plugin/.../activity/` | Correlate tool events and JDT observations per investigation and launch | Multi-thread/repeated-hit scenario has no cross-contamination |
| Test fixtures and report prototype | Add pure synthetic typed-data unit tests and real Eclipse+Claude trials; no modifications to `examples/` without explicit need | Regression + scenario-specific report checks pass |

The first delivery should **not** redesign the ChainTest pipeline or require screenshots. Add the top-of-report last-stop card and structured snapshots using the existing event history first; add typed JDT correlation where report completeness requires it.

### 12.7 Explicit acceptance tests for report presentation

- **REPORT-01:** Report generated for S2 displays the exact/paraphrased request label correctly; no fabricated prompt content.
- **REPORT-02:** Top card shows last *observed* stop; if step/exception rather than breakpoint, title and reason are accurate. Zero-stop run says “No debugger stop captured.”
- **REPORT-03:** A breakpoint creation with no corresponding hit contributes zero to hit count. Two observations of the same stop are deduplicated.
- **REPORT-04:** `expand_frame_variable(order.customer)` highlights that *requested path* and lists only returned children; broad `get_frame_variables` doesn't label any one field as explicitly examined.
- **REPORT-05:** Resume or termination never leaves historical values labeled “live.”
- **REPORT-06:** Two threads and two method invocations with a local named `total` never receive a fabricated unified variable history.
- **REPORT-07:** Command accepted but no subsequent JVM stop is displayed as *accepted*, not *step completed at target*.
- **REPORT-08:** `finish_debug_investigation.summary` is presented as agent-supplied conclusion, not verified fact; omitted summary stays omitted/unknown.
- **REPORT-09:** Application exceptions and transport/tool errors are separated; timeout and unsuccessful cleanup remain visible.
- **REPORT-10:** Redaction, HTML escaping, offline single-file rendering and output-size bounds pass automated tests.
- **REPORT-11:** Rendering off, failed or slow does not break or measurably stall debugger commands; live Activity view continues independently.
- **REPORT-12:** Compare the resulting HTML against the actual saved tool/JDT event trace from one Java and one Spring Boot real-Eclipse run; no report claim exceeds observed evidence.

### 12.8 Delivery order and release gates

1. **R-UI1: Visual proof with existing report events.** Implement last observed stop, clear request/status, timeline filtering, inspected-value grouping and attributed conclusion in the existing HTML writer. No new debugging operations and no AI analysis. **Gate:** synthetic report fixtures and HTML-unit tests pass; confirm exactly what existing data can/cannot produce.
2. **R-UI2: Shared typed observations and correlation.** Add stop IDs, breakpoint hit confirmation, source/thread/frame context, bounded variable snapshots, live vs historical flags and breakpoints inventory with two-producer deduplication. **Gate:** controlled real Eclipse runs with successive stops, repeated hits and multiple threads agree with tool results and JDT.
3. **R-UI3: Live view/report parity.** Display recent/current state in Eclipse while report retains the final evidence; same labels/identifiers; Open Report action when file exists. **Gate:** no contradictions across surfaces; view works if HTML reporting is off.
4. **R-UI4: Value-history comparisons and observed journey polish.** Include only if stable observation identity is proven. **Gate:** repeated invocation and cross-thread tests do not produce false relationships.

**Stop condition:** Do not claim a complete “investigation visualization” if only MCP tool names are available. Mark that limited mode as **Tool activity**, and reserve **Runtime evidence** labels for confirmed stop/inspection observations.
