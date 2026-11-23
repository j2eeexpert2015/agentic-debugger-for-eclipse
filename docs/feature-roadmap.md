# Feature Roadmap and Decision Tracker

<!-- CLAUDE-FIRST-PRIORITY:BEGIN -->
**Current delivery priority (23 November 2026):** Three initial symptom-driven Claude investigations were reviewed; Boot wrong-quote and HTTP validation have event-level MCP evidence, while JUnit has final-result evidence. **Next P0**: automated verdicts, independent cleanup verification and repeatable reliability tests; then fixture synchronization, event-correlated pause and full isolation. No controlled skill A/B exists yet. [Evidence ledger](claude-acceptance-2026-11-23.md) · [Development roadmap](development-roadmap.md).
<!-- CLAUDE-FIRST-PRIORITY:END -->

<!-- JAVA-E2E-19-STATUS:BEGIN -->
**Current evidence (23 November 2026):** The initial Claude symptom-driven trial set has been reviewed: single JUnit diagnosis (final-result record) and two Spring Boot HTTP investigations (event-level runtime PASS). A historical uninterrupted 23/23 direct JDT regression passed; after the fixture HTTP change, first 16 direct scenarios passed together and all seven Boot-gap scenarios later passed separately, but the newest build has **not** completed a fresh uninterrupted 23/23 run. The opt-in Claude harness still reports `REVIEW_REQUIRED`, not automatic acceptance. Repeated no-skill reliability, optional skill A/B, independent cleanup inventories, STS-specific launch modes, and cross-platform coverage remain open. [Current acceptance evidence and boundaries](claude-acceptance-2026-11-23.md). The dated sections below are historical snapshots; their old `NOT RUN` cells refer to the time they were written, not current overall status. See [README](../README.md).
<!-- JAVA-E2E-19-STATUS:END -->


[README](../README.md) · [Phase-level roadmap](development-roadmap.md) · [Spring Boot scenarios](spring-boot-debugging-scenarios.md) · [Evaluation plan](evaluation-plan.md) · [Codex backlog](codex-implementation-backlog.md) · [Phase 1 evidence](phase1-implementation-status.md)

**Reviewed:** 22 November 2026, GitHub `main` `77c43a08f72d7a4578718a92d8d0c8b9bce2377a`. **Scope:** feature-level benefit, status, feasibility and acceptance. Phase numbering is revised; existing feature identifiers remain stable.

<!-- PHASE2-VERIFICATION-CONSOLIDATION:BEGIN -->
## Local Phase 2 Verification Update (22 November 2026)

**Post-audit local test update:** BOOT-07 / SB-08 (invalid-quantity exception/handler) passed direct installed Eclipse checks; BOOT-10 / SB-09 passed a **subset** covering borrowed launch preservation, expired investigation rejection and refusal of unapproved fixture POST. Phase 1's seven capabilities passed direct regression on the same installation. The broader BOOT-10 cross-service matrix and BOOT-11 / SB-10 Spring Tools compatibility remain pending. No Boot-specific natural-language symptom-only trial or skill A/B result is claimed. [Evidence ledger](phase2-verification-consolidation.md).
<!-- PHASE2-VERIFICATION-CONSOLIDATION:END -->

## Contents

- [Status and evidence rules](#status-and-evidence-rules)
- [Phase 0 — Core Eclipse MCP](#phase-0--core-eclipse-mcp)
- [Phase 1 — Verified Java features](#phase-1--verified-java-features)
- [Phase 2 — Essential Spring Boot and JUnit](#phase-2--essential-spring-boot-and-junit)
- [Phase 3 — Unified debugging skill](#phase-3--unified-debugging-skill)
- [Phase 4 — Evaluation and quality](#phase-4--evaluation-and-quality)
- [Phase 5 — Advanced candidates](#phase-5--advanced-candidates)
- [Non-goals, prioritization and change rules](#non-goals-prioritization-and-change-rules)

## Status and evidence rules

`Candidate → Investigation → Feasibility verified → Planned → Implemented in source → Tested on installed candidate → Released`; alternatively **Deferred**, **Not planned**, **Rejected**, **Unknown**. A feasible Eclipse UI feature is not necessarily an agent-exposed MCP feature; proof needs actual code/API evidence. A documented Claude prompt is **not** a test pass.

**Tested Phase 1 candidate:** Eclipse bundle `1.5.0.20261121184224374`. Reports: 7/7 direct checks, 7/7 explicit Claude, 32/32 ownership/failure, and installed calculator regression. Symptom-only *single* trials (fixture source and NORMAL lifecycle guidance supplied): mechanism selected 6/7, complete answers 5/7, partial 2/7 (trace output limited, pause without restart). The installed plug-in contents match a rebuild of `ff28455`. The [new distribution ZIP](phase1-implementation-status.md#verified-distribution-zip) passed installed verification. Publication remains pending. [Record](phase1-implementation-status.md) · [Prompt results](phase1-claude-scenarios.md).

## Phase 0 — Core Eclipse MCP

| ID | User-facing capability / benefit | Current status | Outstanding regression |
|---|---|---|---|
| CORE-01 | Authenticated loopback Eclipse-to-MCP bridge | Implemented; installed bridge tested | Auth, port collision, multiple IDEs |
| CORE-02 | Discover Java `main()` entry points | Implemented; fixture discovery passed | Ambiguity and multi-project selection |
| CORE-03 | Reuse/start Java launch without destroying user settings | Implemented; ownership tests | Custom configurations and fail-before-save |
| CORE-04 | Set/list/remove ordinary breakpoints | Implemented; marker assertions | Duplicate and user marker safety |
| CORE-05 | Pause/resume/step into/over/out | Implemented; installed resume, Step Over, pause evidence; dedicated Step Into/Step Out acceptance pending | Individually test five operations; stale thread/frame and termination |
| CORE-06 | Read threads/stacks/locals | Implemented; runtime checks | Bounded output, stale frames |
| CORE-07 | Navigate Eclipse editor to suspended source | Implemented; navigation request verified | Real GUI focus with concurrent sessions |
| CORE-08 | NORMAL/CONTROLLED lifecycle, precise ownership/cleanup | 32 live negative/ownership checks passed | Preserve invariants for new operations |
| CORE-09 | Managed Claude MCP registration | Implemented; installed preservation coverage pending | Existing unrelated registrations |
| CORE-10 | Installed binary verification | Loaded class hashes matched | Match source revision and release ZIP |
| CORE-11 | Windows build/deploy/doctor/test with fixture import | Fresh and repeat deployment recorded | Failure recovery, version changes |
| CORE-13 | Bridge concurrency, deadlines and diagnostics: a slow launch never blocks status reads; every command answers with a result, `BRIDGE_TIMEOUT` or `BRIDGE_BUSY`; slow commands are logged with stack traces | **Tested on installed candidate (23 Nov 2026):** 3 consecutive uninterrupted 23/23 runs, bridge fault-injection 4/4, unit checks 13/13. Root cause of earlier timeouts found and fixed (Eclipse console output blocking on an unread pipe) | Legacy-mode comparison run; ownership 32/32; overnight loop; old/new MCP version mix |
| CORE-14 | Event-based stop waiting: `WAIT_FOR_STOP` / `wait_for_stop` records suspend events per launch (`DebugStopTracker`) and returns reason (`BREAKPOINT`, `EXCEPTION`, `STEP_END`, `PAUSED`, `SUSPENDED`), thread, breakpoint or exception type, location, top 5 frames and up to 50 locals in one call; `TERMINATED` and `TIMEOUT` are explicit; each stop is reported once; evaluation suspends are ignored | **Tested on installed build (23 Nov 2026):** `stops` group breakpoint, step, exception, pause, terminated all PASS; unchanged 23/23 regression. A pause inside a native method (`Thread.sleep`) is reported with empty locals and `localsNote` | Multi-threaded stops (several threads at one breakpoint); Spring Boot request threads; Claude trial using `wait_for_stop` |
| CORE-15 | Process output keeps the end: `GET_PROCESS_OUTPUT` returns the whole text up to 8000 characters, otherwise the first and last 4000 with an omission marker, plus `stdoutChars`/`stderrChars`/`consoleChars` | **Tested on installed build (23 Nov 2026):** unit checks OUT-U1..U5 5/5; `stops:output` PASS. Observed: with the Eclipse Console attached, output is only in `console` (`stdoutChars=0`); tool description tells agents to read `console` first | Output of a launch that already terminated (still requires an active launch) |
| CORE-16 | Exception breakpoints never block on JDT's modal "Repeated exception occurrence" dialog, and cleanup proves the JVM ended. (a) When an agent creates an exception breakpoint and the user never chose a recurrence strategy (`org.eclipse.jdt.debug.PREF_SUSPEND_ON_RECURRENCE_STRATEGY=RECURRENCE_UNCONFIGURED`), set `SKIP_RECURRENCES`; restore the original value when no agent exception breakpoint remains or the bridge stops; a user choice is never changed. (b) `finish_debug_investigation` removes breakpoints first, then terminates and **verifies** the JVM processes ended (Eclipse terminate, process terminate, OS kill, 5 s each); reply adds `processesEnded`, `terminationSteps`, and `completed=false` with a warning if the JVM survives. (c) `wait_for_stop` also reports a thread that stays suspended 1.5 s without a SUSPEND event | **Tested on installed build (23 Nov 2026):** `stops` 6/6 (`repeatedException=SKIP_RECURRENCES`, every cleanup `processesEnded`), regression 23/23, Claude validation trials 8/8 PASS after the fix (1/6 before). The automatic switch from `RECURRENCE_UNCONFIGURED` was not exercised: the test workspace already had Skip remembered from the dialog. Cause found 23 Nov 2026 from Claude validation trials: the dialog held JDT's event thread from about 22:27 to 22:36 (Eclipse log: deferred breakpoint handling at the moment it was clicked); during that time stops were not reported, terminate had no effect and later runs reused the leftover JVM | `stops` group (exception scenario checks the setting; every scenario checks `processesEnded`); repeat the Claude validation trials |
| CORE-17 | Cleanup after an abandoned investigation, and no unowned agent changes. (a) **Idle lease** in Eclipse: an investigation whose agent sends no command naming it (its ID or its launch) for its lease (default 10 min, `AGENTDEBUG_INVESTIGATION_LEASE_MINUTES`; `BEGIN_INVESTIGATION mode leaseSeconds` for tests, min 5 s) and has no command running is expired: NORMAL gets the same cleanup as finish (own breakpoints, own JVM verified), CONTROLLED keeps its session and breakpoints; a late call gets "expired …". (b) **MCP disconnect**: the server tracks the investigations its agent opened and finishes the open ones when the agent closes the connection or the server shuts down. (c) **Auto-attach**: a breakpoint or launch without `investigationId` is attached to the session's only open investigation (reply field `attachedToInvestigation`), rejected when several are open. (d) **Orphans after restart**: investigations live in memory but breakpoints persist in the workspace, so 5 s after Eclipse starts the plugin removes agent breakpoints linked to an investigation that no longer exists (found 23 Nov 2026: an interrupted Claude run left an `IllegalArgumentException` exception breakpoint that paused every Spring Boot startup after the next restart, failing phase2 with a readiness timeout) | **Tested on installed build (24 Nov 2026):** unit LE-U1..U7 and TR-U1..U5 (30/30 unit total); `lease` 5/5 (idle investigation cleaned up 7 s after a 5 s lease; CONTROLLED kept; real MCP server disconnect cleaned up immediately; several open investigations rejected); regression 23/23, which also confirmed (d): the leftover `IllegalArgumentException` breakpoint that had failed phase2 was removed at startup; `stops` 6/6 | Hard kill of the MCP process on Windows (only the lease applies then); several agents sharing one MCP server |

### CORE-05 — Fundamental execution-control acceptance

The debugger implements these operations, but a general passing regression does **not** prove that each action passed a separate, targeted assertion. Track tests as children of `CORE-05` rather than inventing new feature IDs.

| Test ID | Operation | Status | Required focused evidence |
|---|---|---|---|
| CORE-05-01 | Resume | Installed workflow verified | Selected suspended thread resumes; other sessions unchanged |
| CORE-05-02 | Step Over | Installed calculator workflow verified | Expected next application line and changed locals |
| CORE-05-03 | Step Into | Dedicated Java E2E PASS (23 Nov 2026) | Enter expected application callee; verify frame, line, thread |
| CORE-05-04 | Step Out | Dedicated Java E2E PASS (23 Nov 2026) | Return to expected caller; verify frame, line, thread |
| CORE-05-05 | Pause | Installed JAVA-06 workflow verified | Selected running thread suspends; stack is readable |
| CORE-05-06 | Ended/stale/invalid thread operations | Broader negative coverage pending | Fail safely with no action on another debug target |

Step Into and Step Out now have a dedicated installed Java E2E assertion (caller `main()` to callee `sum()` and return); cross-context tests remain open. Cross-context Spring Boot and JUnit stepping checks remain open. The corresponding user-facing status matrix is in [capabilities.md](capabilities.md#fundamental-execution-control-acceptance-checklist-core-05).

## Phase 1 — Verified Java features

| ID / test | Capability / developer benefit | Evidence | Follow-up (not a Phase 1 absence) |
|---|---|---|---|
| JAVA-01 / P1-COND | Conditional line breakpoint, stop for selected record | `orderId==1057` and operands inspected | Expression evaluation failure handling |
| JAVA-02 / P1-EXC | Caught/uncaught exception breakpoints, identify throw site | Live stack, message inspected | Unrelated marker protection |
| JAVA-03 / P1-JUNIT | Discover/debug single JUnit 5 method | `appliesTenPercentDiscount` debugged | Parameterized tests, JUnit Platform selection, Boot/JUnit contexts |
| JAVA-04 / P1-VARS | Bounded nested field/array reads | Nested Kolkata/address and item values | Stale frame, pagination |
| JAVA-05 / P1-TRACE | Authorized non-suspending tracepoint | `TRACE-ORDER 1057` observed | Output truncated after exit, side-effect consent |
| JAVA-06 / P1-PAUSE | Pause, investigate and restart owned JVM | New owned launch observed; negative ownership passed | Natural-language Claude restart selection |
| JAVA-07 / P1-FILTER | Per-target step filtering | Application-level frame observed | No global preference changes |

**Do not call JUnit debugging missing.** Core single-test support and one Spring-context method have installed debugger evidence. Missing-method, context-error and disabled-method checks passed on the orders fixture. More complex discovery remains open. [Phase 1 scenarios](phase1-claude-scenarios.md).

## Phase 2 — Essential Spring Boot and JUnit

The [installed fixture audit](spring-boot-tool-audit.md) records direct checks for discovery, launch, readiness and JUnit, plus direct and explicit Claude evidence for the HTTP quote. Full scenario acceptance remains open. `BOOT-05` remains deferred to Phase 5 and its original ID is retained. Detailed real-world prompts, fixtures, direct assertions, negatives and evidence: [spring-boot-debugging-scenarios.md](spring-boot-debugging-scenarios.md).

| ID / scenarios | Developer need / proposed capability | Reuse-first hypothesis (verify, not assume) | Required acceptance / priority |
|---|---|---|---|
| BOOT-01 / SB-01 | Identify correct Boot app among Java workspace projects | Existing `list_java_applications` may see Boot main | Two boot apps, no guessed selection; **P0** |
| BOOT-02 / SB-02 | Debug Boot with correct profile, args, env and config | Existing Java/STS launch config may suffice | Assert profile/source/working directory are retained; **P0** |
| BOOT-03 / SB-03 | Wait for readiness and diagnose failed start | Process-output or authorized bounded HTTP health probe may suffice | Ready vs failed vs timeout differentiated; **P0** |
| BOOT-04 / SB-04, SB-05 | Reproduce HTTP defect and trace controller→service (+ repository if present) | JDT breakpoints and approved HTTP tool/Java harness | Real request triggers app breakpoint and identifies first wrong value; **P0** |
| BOOT-06 / SB-06, SB-07 | Debug selected Spring Boot service test and `@SpringBootTest` JUnit method | Existing `start_junit_test` first | Exact method, context startup, suspended application frame; **P0** |
| BOOT-07 / SB-08 | Inspect validation, handled exception or error translation | Existing exception breakpoint and variable reads | Throw-site/request chain and response evidence; **P0** |
| BOOT-10 / SB-09 | Negative/ownership matrix for Boot requests/launches | Investigation ID and session safeguards | No unauthorized HTTP mutation, launch replacement, or borrowed-session cleanup; **P0** |
| BOOT-11 / SB-10 | Verify both Spring Tools and plain Eclipse prerequisites | Test installed environment permutations | Declare which launch modes actually pass; **P1** |

**Scope boundary:** Boot-specific project discovery/launch/readiness/MCP operations only if actual fixture evidence reveals a gap. An external HTTP request is not the debugger itself; never invent a debugger tool solely for conceptual symmetry. A local `@WebMvcTest`/MockMvc case does not substitute for an actual HTTP request across a running socket.

## Phase 3 — Unified debugging skill

| ID | Guidance target | State | Measurement |
|---|---|---|---|
| SKILL-01 | Appropriate selection of runtime investigation versus static-only explanation; choose Java/JUnit/Boot context | Skill source exists; activation/agent acceptance incomplete | EV-01, EV-03, EV-09, EV-10, EV-11, EV-13 |
| SKILL-02 | Hypothesis, targeted stops, first-bad-value tracing, efficient tools, bounded observations | Skill source exists; controlled comparison incomplete | EV-02, EV-04, EV-05, EV-12, EV-15 |
| SKILL-03 | Error handling, correct target/session, explicit consent and investigation finish | Skill source exists; full behavior acceptance incomplete | EV-06, EV-07, EV-08, EV-14, EV-16 |

**Requirement:** Independently replaceable versioned guidance, optionally split into topic-specific reference documents if long. The plugin/MCP server cannot depend on it. No skill benefit claims without comparative tests. Research: [research-sources.md](research-sources.md).

## Phase 4 — Evaluation and quality

| ID | Work | State | Acceptance |
|---|---|---|---|
| SKILL-04 | A/B runner for no skill / stable / candidate | Not implemented | Repeatable isolated sessions, real skill activation detection and full reports |
| EVAL-01 | Reproducible prompt/fixture/oracle matrix, EV-01–EV-16 | Existing Java scenarios defined; Boot extension specified | Validated fixtures and ground truths, fixed input visibility |
| EVAL-02 | Quantitative and qualitative scoring | Plan documented; automation pending | Correctness, evidence, wrong-target count, stop/call count, elapsed time, token use when available |
| EVAL-03 | Skill install/remove/rollback invariants | Planned | Base Java/Boot tests work with no skill and after skill removal |
| EVAL-04 | Boot no-skill benchmark | Planned; not yet run | At least SB-04, SB-06/07 and SB-08 with repeat trials |
| EVAL-06 | Repeated trials and controlled skill comparison: `DevE2E … --groups claude --repeat N [--no-skill]` runs each scenario N times in one batch; `--no-skill` adds `--disallowedTools Skill` to the Claude CLI; every verdict records `batch` and `skillMode`; metrics record `skillsInvoked` and `skillFileReads` (skill files opened with Read/Grep/Glob, so a "no-skill" run that still reads the skill is visible); `ClaudeTranscriptEvaluator --summary` prints and writes per-scenario pass rate, skill/`wait_for_stop` use, cleanup, average calls/turns/time and total cost | **Done (23 Nov 2026):** with skill 9/9 PASS, without skill 17/18 (one run where the Claude CLI ended early is graded FAIL). Self-test EV-U1..EV-U18. Harness removes leftover fixture JVMs and agent-owned breakpoints before each run; runs whose CLI failed are graded and counted | Equal model and fixture for both arms; enough runs per arm before claiming a difference |
| EVAL-05 | Automatic Claude verdicts: `dev-e2e/ClaudeTranscriptEvaluator.java` reads the stream-json transcript and grades from successful MCP **results** only (stop frame, variable values, required calls, HTTP result), the final answer only for the diagnosis, ownership (changes inside an investigation, only own launches/breakpoints touched, approved HTTP only, no side-effect evaluation, no Bash/Edit/Write) and cleanup (finish with `CLEANUP` plus the independent before/after inventory `ClaudeSymptomTrial` now captures). Verdicts PASS/PARTIAL/FAIL/BLOCKED/UNSAFE, plus metrics (tool calls, waits, turns, cost). Scenario oracles in `evaluation/claude-grading/expectations.json` | **Tested (23 Nov 2026):** self-test 13/13; fresh `claude:boot-quote` trial graded PASS from actual results (stop via `wait_for_stop` at `OrderService:24`, `percent=10`, `rate=0`, HTTP 200 `120.00`, `CLEANUP`) with all four inventory checks PASS. The three earlier transcripts were not on the test machine, so they were not regraded | Real-transcript format differences; oracles for further scenarios; repeated trials |

**Current natural-language baseline:** seven one-off Java scenarios; 6/7 intended mechanisms and 5/7 complete outputs, with supplied fixture source and NORMAL guidance. It is a starting observation, not a general success rate or skill effect. [Evaluation plan](evaluation-plan.md).

## Phase 5 — Advanced candidates

| ID | Developer benefit | Decision | Feasibility/permission gate |
|---|---|---|---|
| JAVA-08 / BOOT-05 | JDWP attach to an existing local or remote JVM, including Boot | Deferred | Prove attach API and safe target authentication/selection/ownership |
| JAVA-09 | Field access/modification watchpoints | Deferred | Test JDT semantics and user-marker preservation |
| JAVA-11 | Run to line | Candidate, not verified | Compare owned breakpoint alternative with direct JDT behavior |
| JAVA-10A | Hot Code Replace | Deferred | Runtime compatibility + explicit user approval |
| JAVA-10B | Runtime variable modification | Deferred, safety-sensitive | Explicit user authorization + mutation/failure contract |
| BOOT-08 | JPA/transaction and persistence diagnostics | Deferred | Controlled DB fixture, transaction path and real runtime evidence |
| BOOT-09 | Concurrent requests, async threads and advanced Boot diagnostics | Deferred | Correct thread/request correlation and cancellation handling |

## Non-goals, prioritization and change rules

- **NG-01:** Do not reimplement JDT/JVM debugger. **NG-02:** No bundled LLM. **NG-03:** No full profiler/heap analyzer in current scope. **NG-04:** No broad non-Java debug engine.
- Source observations, IDE documentation, an API spike, installed direct test and explicit/implicit agent result are different evidence levels.
- Every new feature requires **feature ID, user problem, expected runtime observation, fixture, explicit prompt, symptom-only prompt, direct test, negative/safety tests, limitations, source revision and tested artifact**.
- Keep old feature IDs unchanged after moving phases, never reuse a rejected ID, and link [implementation backlog](codex-implementation-backlog.md) to the acceptance case.
- Maintain [Phase 0–5 development roadmap](development-roadmap.md) separately: phase sequencing/exit gates live there, feature evidence lives here.
