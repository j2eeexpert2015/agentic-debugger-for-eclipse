# Architecture Decisions and Documentation Rules

[Documentation index](../README.md#documentation) · [Roadmap](feature-roadmap.md)

## ADR-001 — Skills are optional and replaceable

**Decision:** MCP tool contracts, auth, investigation IDs, session ownership, and Normal/Controlled cleanup remain in Java plugin/server code. The skill may advise but cannot enforce these policies. Core tests run with skill missing. A new skill version is tested independently and can be rolled back without rebuilding binaries.

## ADR-002 — Separate research from product claims

**Decision:** Source observations and competitor comparisons live in `research-sources.md`; candidates and decisions in `feature-roadmap.md`; actual product claims and evidence in `capabilities.md`. Never label a feature “implemented” just because Eclipse IDE or IntelliJ supports it manually.

## ADR-003 — Evidence gates before features

**Decision:** Each candidate has a precise developer benefit, test oracle, measurable outcome, required MCP/API feasibility assessment, and final disposition. `Unknown` is a valid conclusion. Tests must distinguish skill deficiencies from missing debugger functionality.

## ADR-004 — README as product entry point

**Decision:** README keeps the product pitch, installation, minimal quick start, contributor commands, and links. Background research, extended scenarios, feature planning, and scorecards belong in docs. Every new documentation page links back to the README documentation index.

## ADR-005 — Bind ownership to the launched JVM

An Eclipse launch configuration name can identify different JVMs over time. Investigations track the actual launch object, and the debugger validates the requested target before saving a new configuration. The installed ownership suite reproduced three failures on the preceding build and passed all 32 checks after the fixes. [Ownership evidence](phase1-implementation-status.md#installed-ownership-and-failure-checks-22-november-2026)

## ADR-006 — Bridge concurrency model (CORE-13)

The bridge previously handled one connection at a time on its accept thread, so one slow command (typically a synchronous JDT launch) delayed every queued command, callers hit their 30 s socket timeout, and queued commands still ran after the caller had given up. Decision:

- **Read-only commands run in parallel** (bounded). **State-changing commands run one at a time, in arrival order** — the previous ordering guarantee is kept on purpose, so ownership logic sees no new interleavings. Unknown commands are always state-changing.
- **Every command has a deadline measured from arrival** (reads 15 s, ordinary changes 25 s, launches/restarts/cleanup 120 s). Past it the caller gets `BRIDGE_TIMEOUT` with an operation ID; the command keeps running (a JDT launch cannot be interrupted safely) and its final result is available through `GET_OPERATION`.
- A state change that cannot get the lane within 5 s gets `BRIDGE_BUSY`, naming the running command, instead of waiting invisibly. State changes are **never retried automatically** by the MCP server.
- A watchdog logs slow commands (≥ 5 s) with the worker's stack trace and releases the lane of a command running longer than 5 minutes (for example, blocked behind a modal dialog).
- The log (`~/.eclipse-agentdebug/bridge-<port>.log`) records command names and timings only — **never arguments or tokens**.
- `AGENTDEBUG_BRIDGE_MODE=legacy` restores the previous behaviour exactly for one release, as a rollback path. The wire protocol is unchanged; only fields were added to error replies.

**Outcome (23 November 2026).** On the first installed run, the slow-command log showed `LIST_DEBUG_SESSIONS` blocked for 67 s inside `System.out.println`: DevE2E started Eclipse without redirecting its console, so once the unread pipe filled, every console write in Eclipse blocked. Under the old single-threaded bridge this froze every command, which matches the earlier intermittent `boot-gaps` timeouts. Fixes: diagnostic console output removed from `EclipseDebugService.listDebugSessions()`, and DevE2E now redirects Eclipse output to `e2e-runner/target/eclipse-console.log`. Rule going forward: **no `System.out` diagnostics on bridge command paths**; use the bridge log. Acceptance: 3 consecutive uninterrupted 23/23 runs, bridge group 4/4.

## Change protocol

For a feature change: update capability/roadmap status, add or extend the corresponding REG/EV scenario, run tests, note verification boundary. For new external research: create an RS entry with source URL and proven claim; link to candidates without declaring implementation. For a skill change: preserve core suite and compare stable/no-skill/candidate results.

## ADR-007 — Agent exception breakpoints and JDT's repeated-exception dialog (CORE-16)

**Context.** JDT (since 2019, Bug 284158) asks with a modal dialog what to do when the same exception instance hits an exception breakpoint again, unless the user chose a strategy (`org.eclipse.jdt.debug.PREF_SUSPEND_ON_RECURRENCE_STRATEGY`). The dialog runs while JDT's event thread for that JVM waits. On 23 Nov 2026 a Claude validation trial (Spring rethrows the `IllegalArgumentException`) left it open for 9 minutes: stops were not reported, terminate had no visible effect, and later runs inherited the JVM.

**Decision.** (1) Only if the user never chose (`RECURRENCE_UNCONFIGURED`), the plugin sets `SKIP_RECURRENCES` while agent exception breakpoints exist and restores the original value afterwards and on bridge stop; an explicit user choice is never touched. (2) Cleanup is judged by the OS processes, not by Eclipse's debug-target state, and escalates terminate → process terminate → OS kill; failure is reported (`completed=false`) instead of claimed. (3) `wait_for_stop` falls back to reporting a thread suspended without an event for 1.5 s.

**Consequences.** Agents never wait on a dialog nobody can see. A crash of Eclipse while an agent exception breakpoint exists could leave `SKIP_RECURRENCES` set (the default JDT setting would otherwise ask); this is visible on the Java > Debug preference page.

**Outcome (23 Nov 2026).** Installed build: `stops` 6/6 with `repeatedException=SKIP_RECURRENCES`, every cleanup ended the JVM on the first step (`launch-terminate`), regression 23/23, Claude validation trials 8/8 PASS (1/6 before the fix).
