# Architecture Decisions and Documentation Rules

[Documentation index](../README.md#documentation) · [Roadmap](feature-roadmap.md)

## ADR-001 — Skills are optional and replaceable

**Decision:** MCP tool contracts, auth, investigation IDs, session ownership, and Normal/Controlled cleanup remain in Java plugin/server code. The skill may advise but cannot enforce these policies. Core tests run with skill missing. A new skill version is tested independently and can be rolled back without rebuilding binaries.

## ADR-002 — Separate research from product claims

**Decision:** Source observations and competitor comparisons live in the External research section of [feature-roadmap.md](feature-roadmap.md#external-research-rs-01-to-rs-07); candidates and decisions in `feature-roadmap.md`; actual product claims and evidence in `capabilities.md`. Never label a feature “implemented” just because Eclipse IDE or IntelliJ supports it manually.

## ADR-003 — Evidence gates before features

**Decision:** Each candidate has a precise developer benefit, test oracle, measurable outcome, required MCP/API feasibility assessment, and final disposition. `Unknown` is a valid conclusion. Tests must distinguish skill deficiencies from missing debugger functionality.

## ADR-004 — README as product entry point

**Decision:** README keeps the product pitch, installation, minimal quick start, contributor commands, and links. Background research, extended scenarios, feature planning, and scorecards belong in docs. Every new documentation page links back to the README documentation index.

## ADR-005 — Bind ownership to the launched JVM

An Eclipse launch configuration name can identify different JVMs over time. Investigations track the actual launch object, and the debugger validates the requested target before saving a new configuration. The installed ownership suite reproduced three failures on the preceding build and passed all 32 checks after the fixes. Ownership evidence (in the git history)

## ADR-006 — Bridge concurrency model (CORE-13)

The bridge previously handled one connection at a time on its accept thread, so one slow command (typically a synchronous JDT launch) delayed every queued command, callers hit their 30 s socket timeout, and queued commands still ran after the caller had given up. Decision:

- **Read-only commands run in parallel** (bounded). **State-changing commands run one at a time, in arrival order** — the previous ordering guarantee is kept on purpose, so ownership logic sees no new interleavings. Unknown commands are always state-changing.
- **Every command has a deadline measured from arrival** (reads 15 s, ordinary changes 25 s, launches/restarts/cleanup 120 s). Past it the caller gets `BRIDGE_TIMEOUT` with an operation ID; the command keeps running (a JDT launch cannot be interrupted safely) and its final result is available through `GET_OPERATION`.
- A state change that cannot get the lane within 5 s gets `BRIDGE_BUSY`, naming the running command, instead of waiting invisibly. State changes are **never retried automatically** by the MCP server.
- A watchdog logs slow commands (≥ 5 s) with the worker's stack trace and releases the lane of a command running longer than 5 minutes (for example, blocked behind a modal dialog).
- The log (`~/.eclipse-agentdebug/bridge-<port>.log`) records command names and timings only — **never arguments or tokens**.
- `AGENTDEBUG_BRIDGE_MODE=legacy` restored the previous behaviour for one release, as a rollback path; it was removed on 25 Nov 2026 (OPEN-38 step 3) after the concurrent bridge passed every regression. The wire protocol is unchanged; only fields were added to error replies.

**Outcome (23 November 2026).** On the first installed run, the slow-command log showed `LIST_DEBUG_SESSIONS` blocked for 67 s inside `System.out.println`: DevE2E started Eclipse without redirecting its console, so once the unread pipe filled, every console write in Eclipse blocked. Under the old single-threaded bridge this froze every command, which matches the earlier intermittent `boot-gaps` timeouts. Fixes: diagnostic console output removed from `EclipseDebugService.listDebugSessions()`, and DevE2E now redirects Eclipse output to `e2e-runner/target/eclipse-console.log`. Rule going forward: **no `System.out` diagnostics on bridge command paths**; use the bridge log. Acceptance: 3 consecutive uninterrupted 23/23 runs, bridge group 4/4.

## Change protocol

For a feature change: update capability/roadmap status, add or extend the corresponding REG/EV scenario, run tests, note verification boundary. For new external research: create an RS entry with source URL and proven claim; link to candidates without declaring implementation. For a skill change: preserve core suite and compare stable/no-skill/candidate results.

## ADR-007 — Agent exception breakpoints and JDT's repeated-exception dialog (CORE-16)

**Context.** JDT (since 2019, Bug 284158) asks with a modal dialog what to do when the same exception instance hits an exception breakpoint again, unless the user chose a strategy (`org.eclipse.jdt.debug.PREF_SUSPEND_ON_RECURRENCE_STRATEGY`). The dialog runs while JDT's event thread for that JVM waits. On 23 Nov 2026 a Claude validation trial (Spring rethrows the `IllegalArgumentException`) left it open for 9 minutes: stops were not reported, terminate had no visible effect, and later runs inherited the JVM.

**Decision.** (1) Only if the user never chose (`RECURRENCE_UNCONFIGURED`), the plugin sets `SKIP_RECURRENCES` while agent exception breakpoints exist and restores the original value afterwards and on bridge stop; an explicit user choice is never touched. (2) Cleanup is judged by the OS processes, not by Eclipse's debug-target state, and escalates terminate → process terminate → OS kill; failure is reported (`completed=false`) instead of claimed. (3) `wait_for_stop` falls back to reporting a thread suspended without an event for 1.5 s.

**Consequences.** Agents never wait on a dialog nobody can see. A crash of Eclipse while an agent exception breakpoint exists could leave `SKIP_RECURRENCES` set (the default JDT setting would otherwise ask); this is visible on the Java > Debug preference page.

**Outcome (23 Nov 2026).** Installed build: `stops` 6/6 with `repeatedException=SKIP_RECURRENCES`, every cleanup ended the JVM on the first step (`launch-terminate`), regression 23/23, Claude validation trials 8/8 PASS (1/6 before the fix).

## ADR-008 — Cleanup of agent resources: four paths (CORE-08, CORE-17)

**Context.** An agent investigation owns the breakpoints it created and the JVM it launched. On 23 Nov 2026 two cases left them behind: the Claude CLI died mid-run (nothing called `finish`), and after the next Eclipse restart that run's `IllegalArgumentException` exception breakpoint paused every Spring Boot start. Investigations live in memory; breakpoint markers live in the workspace and survive restarts, so no single mechanism can see every case.

**Decision.** Four paths, each covering what the earlier ones cannot:

| # | Situation | Cleanup |
|---|---|---|
| 1 | Agent finishes | `finish_debug_investigation`: own breakpoints first, then own JVM, verified ended |
| 2 | Agent exits or crashes, MCP connection closes, Eclipse running | MCP server finishes the investigations its agent left open (stdin end or shutdown) |
| 3 | Agent hangs or is killed hard, Eclipse running | Idle lease in Eclipse: no command naming the investigation for 10 min (configurable) and none running → same cleanup as 1 |
| 4 | Eclipse closes or crashes mid-investigation | 5 s after the next start, agent markers whose investigation no longer exists are removed |

Ownership is decided only by labels the plugin wrote (`owner=agent` plus investigation ID on each marker; the launch recorded by the investigation). User breakpoints, borrowed sessions and agent markers without an investigation are never removed. CONTROLLED investigations keep their paused session and breakpoints in paths 1–3. A breakpoint or launch sent without an investigation ID is attached by the MCP server to the session's only open investigation, or rejected if several are open, so nothing the agent creates escapes the four paths.

**Consequences.** A long wait never triggers path 3 (a running command counts as activity). Several agents sharing one MCP server process are not distinguished (one process per agent is the normal setup). The test harness additionally clears leftovers before each Claude trial; that is test isolation, not a product path. Code overview: `DebugInvestigationPolicy` class comment.

**Outcome (24 Nov 2026).** Installed build: `lease` 5/5 (path 3: cleaned up 7 s after a 5 s lease; CONTROLLED kept; path 2: real MCP server disconnect cleaned up immediately; ambiguous ownership rejected); path 4 removed the real leftover from 23 Nov at startup, after which the regression passed 23/23 again; unit checks 30/30.

## ADR-009 — Debugging session report: event model, isolated reporters (OPEN-30)

**Context.** Every step an agent takes in an investigation should be recorded and written as a report (ChainTest first). The report is a product feature, but debugging is the product: a report library must never slow down, change or break a debugging command, and must be replaceable or switched off without touching the debugger.

**Decision.** Three rules, each enforced by structure or by a test, not by convention:

| Rule | Design | Enforced by |
|---|---|---|
| **Dependency direction:** the ChainTest module depends on the event model; the event model knows nothing about ChainTest | Three Maven modules: `report-model` (event records, `DebugReportListener` interface, no-op listener, dispatcher; no third-party dependencies), `report-chaintest` (ChainTest writer; depends on `report-model` and ChainTest), `mcp-server` (depends on `report-model` at compile scope, on `report-chaintest` only at **runtime** scope so it is shaded into the jar but cannot be imported). The writer is found at runtime through `java.util.ServiceLoader` by its name (`chaintest`, later `html`) | The compiler: `report-model` has no ChainTest dependency, and `mcp-server` code cannot reference a runtime-scope class |
| **Runtime isolation:** report rendering happens outside the time-critical debugging path | Recording a step only creates a small immutable event and offers it to a bounded in-memory queue (non-blocking; when full, the event is dropped and counted, and the report states how many). One background daemon thread delivers events to the writer and renders the report. Rendering happens in the MCP server process, never inside Eclipse. The only Eclipse-side part, the optional screenshot (R3), is taken asynchronously on the UI thread with a time limit and never delays the command that triggered it. On finish or disconnect the queue is flushed with a time limit (5 s), so a slow writer cannot hold up shutdown | Unit tests: with a writer that sleeps 5 s per event, recording a step still returns within a few milliseconds and the command result is unchanged; queue overflow is counted, not blocking |
| **Failure isolation:** a reporter failure never reaches the command result or the debugging lifecycle | Every writer call runs on the background thread inside a catch-all; errors go to stderr (never stdout, which carries the MCP protocol) and the report notes "report incomplete". A writer that fails repeatedly is disabled for the rest of the session. A missing writer or invalid setting falls back to the no-op listener with one warning. Reporting `off` uses the no-op listener | Unit tests: a writer that throws on every event, or is missing, gives the same tool results and investigation lifecycle as reporting `off`; integration: regression 23/23 with reporting `off` and with `chaintest` |

**Consequences.** One report per MCP server process (one agent session), holding every investigation of that session; this matches ChainTest's per-process build model. Events are a few hundred bytes; variable values are truncated and secrets masked before an event is created, so the queue never holds large or sensitive payloads. Reporting starts `off` by default until the ChainTest prototype (R1a) has passed its checks.

**Implementation (24 Nov 2026).** R1a: `report-model` (events, `AsyncReportRecorder`, `ReportRecorders`, `Redaction`; tests RM-U1..U8) and `report-chaintest` (`ChainTestReportWriter`, ChainTest core 1.0.12 with the cloud SDKs excluded; tests RC-U1..U4); MCP server jar 11.3 MB with ChainTest included. R1: in the MCP server, `ReportingTools` wraps every tool *after* it has produced its result, and `SessionReport` turns the call into events (attribution: `investigationId` argument, the investigation named in the reply, the owner of the `launchName`, the only open investigation; calls outside an investigation are not recorded); switched on with `AGENTDEBUG_REPORT=chaintest` (folder `AGENTDEBUG_REPORT_DIR`, default `~/.eclipse-agentdebug/reports`), off by default; tests SR-U1..U8.

**R2a (24 Nov 2026).** One report per investigation instead of per agent session, named `<application>_<yyyy-MM-dd>_<HH-mm-ss>.html` by `ReportFiles` in `report-model` (a second report of the same application in the same second gets `_2`; existing files are never overwritten). The MCP server chooses the file when the investigation ends and passes it to the writer in `InvestigationFinished.reportFile`, so it can print the full path in its log and return it from the new tool `get_investigation_report`; tool results themselves stay unchanged (rule 3). The first card shows the user's request: the exact prompt when a test harness provides it (`AGENTDEBUG_REPORT_PROMPT`), otherwise the agent's `request` argument of `begin_debug_investigation`, labelled as such. The last card shows the agent's conclusion from the `summary` argument of `finish_debug_investigation`. Both arguments are used only for the report and never sent to Eclipse.
