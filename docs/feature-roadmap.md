# Feature catalog

Every feature ID (CORE, JAVA, BOOT, SKILL, EVAL), what it gives the developer, its status and its evidence, plus the
agent behaviour scenarios (EV) and the external research (RS) behind them. Feature IDs are stable and never reused.

> **Tracking:** open work and its priority are tracked only in the README [Status and tracking](../README.md#status-and-tracking)
> table (OPEN-xx IDs). This file holds feature definitions and evidence. Spring Boot scenarios (SB) are in
> [capabilities](capabilities.md#spring-boot-and-junit-scenarios-sb-01-to-sb-10). Dated phase reports that used to be
> linked here were removed on 25 Nov 2026 (OPEN-38); they are in the git history.

## Status and evidence rules

`Candidate → Investigation → Feasibility verified → Planned → Implemented in source → Tested on installed candidate → Released`; alternatively **Deferred**, **Not planned**, **Rejected**, **Unknown**. A feasible Eclipse UI feature is not necessarily an agent-exposed MCP feature; proof needs actual code/API evidence. A documented Claude prompt is **not** a test pass.

**Tested Phase 1 candidate:** Eclipse bundle `1.5.0.20261121184224374`. Reports: 7/7 direct checks, 7/7 explicit Claude, 32/32 ownership/failure, and installed calculator regression. Symptom-only *single* trials (fixture source and NORMAL lifecycle guidance supplied): mechanism selected 6/7, complete answers 5/7, partial 2/7 (trace output limited, pause without restart). The installed plug-in contents match a rebuild of `ff28455`. The new distribution ZIP passed installed verification. Publication remains pending. Record · Prompt results.

## Phase 0 — Core Eclipse MCP

| ID | User-facing capability / benefit | Current status | Outstanding regression |
|---|---|---|---|
| CORE-01 | Authenticated loopback Eclipse-to-MCP bridge | Implemented; installed bridge tested | Auth, port collision, multiple IDEs |
| CORE-02 | Discover Java `main()` entry points | Implemented; fixture discovery passed | Ambiguity and multi-project selection |
| CORE-03 | Reuse/start Java launch without destroying user settings | Implemented; ownership tests | Custom configurations and fail-before-save |
| CORE-04 | Set/list/remove ordinary breakpoints | Implemented; marker assertions | Duplicate and user marker safety |
| CORE-05 | Pause/resume/step into/over/out | Implemented; installed resume, Step Over, pause evidence; dedicated Step Into/Step Out passed 23 Nov 2026 | Individually test five operations; stale thread/frame and termination |
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

**Do not call JUnit debugging missing.** Core single-test support and one Spring-context method have installed debugger evidence. Missing-method, context-error and disabled-method checks passed on the orders fixture. More complex discovery remains open. Phase 1 scenarios.

## Phase 2 — Essential Spring Boot and JUnit

The installed fixture audit (in the git history) records direct checks for discovery, launch, readiness and JUnit, plus direct and explicit Claude evidence for the HTTP quote. Full scenario acceptance remains open. `BOOT-05` remains deferred to Phase 5 and its original ID is retained. Detailed real-world prompts, fixtures, direct assertions, negatives and evidence: [the SB scenarios](capabilities.md#spring-boot-and-junit-scenarios-sb-01-to-sb-10).

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

**Requirement:** Independently replaceable versioned guidance, optionally split into topic-specific reference documents if long. The plugin/MCP server cannot depend on it. No skill benefit claims without comparative tests. Research: [External research](#external-research-rs-01-to-rs-07) below.

## Phase 4 — Evaluation and quality

| ID | Work | State | Acceptance |
|---|---|---|---|
| SKILL-04 | A/B runner for no skill / stable / candidate | **Done for no skill vs skill** (EVAL-06: `--no-skill`, skill use detected from Skill calls and skill-file reads); stable vs candidate skill versions not implemented | Repeatable isolated sessions, real skill activation detection and full reports |
| EVAL-01 | Reproducible prompt/fixture/oracle matrix, EV-01–EV-16 | Existing Java scenarios defined; Boot extension specified | Validated fixtures and ground truths, fixed input visibility |
| EVAL-02 | Quantitative and qualitative scoring | **Automated** (EVAL-05 verdicts and metrics: calls, waits, turns, time, cost; EVAL-06 batch summary); wrong-target count not separately reported | Correctness, evidence, wrong-target count, stop/call count, elapsed time, token use when available |
| EVAL-03 | Skill install/remove/rollback invariants | Planned | Base Java/Boot tests work with no skill and after skill removal |
| EVAL-04 | Boot no-skill benchmark | Planned; not yet run | At least SB-04, SB-06/07 and SB-08 with repeat trials |
| EVAL-06 | Repeated trials and controlled skill comparison: `ClaudeAgentEndToEndTests` (since 25 Nov 2026 a JUnit class; before: `DevE2E … --groups claude --repeat N [--no-skill]`), with `-Dclaude.skill=false` for the comparison; each run is one batch; `--no-skill` adds `--disallowedTools Skill` to the Claude CLI; every verdict records `batch` and `skillMode`; metrics record `skillsInvoked` and `skillFileReads` (skill files opened with Read/Grep/Glob, so a "no-skill" run that still reads the skill is visible); `ClaudeTranscriptEvaluator --summary` prints and writes per-scenario pass rate, skill/`wait_for_stop` use, cleanup, average calls/turns/time and total cost | **Done (23 Nov 2026):** with skill 9/9 PASS, without skill 17/18 (one run where the Claude CLI ended early is graded FAIL). Self-test EV-U1..EV-U18. Harness removes leftover fixture JVMs and agent-owned breakpoints before each run; runs whose CLI failed are graded and counted | Equal model and fixture for both arms; enough runs per arm before claiming a difference |
| EVAL-05 | Automatic Claude verdicts: `dev-e2e/ClaudeTranscriptEvaluator.java` reads the stream-json transcript and grades from successful MCP **results** only (stop frame, variable values, required calls, HTTP result), the final answer only for the diagnosis, ownership (changes inside an investigation, only own launches/breakpoints touched, approved HTTP only, no side-effect evaluation, no Bash/Edit/Write) and cleanup (finish with `CLEANUP` plus the independent before/after inventory `ClaudeSymptomTrial` now captures). Verdicts PASS/PARTIAL/FAIL/BLOCKED/UNSAFE, plus metrics (tool calls, waits, turns, cost). Scenario oracles in `evaluation/claude-grading/expectations.json` | **Tested (23 Nov 2026):** self-test 13/13; fresh `claude:boot-quote` trial graded PASS from actual results (stop via `wait_for_stop` at `OrderService:24`, `percent=10`, `rate=0`, HTTP 200 `120.00`, `CLEANUP`) with all four inventory checks PASS. The three earlier transcripts were not on the test machine, so they were not regraded | Real-transcript format differences; oracles for further scenarios; repeated trials |

**Current natural-language baseline:** seven one-off Java scenarios; 6/7 intended mechanisms and 5/7 complete outputs, with supplied fixture source and NORMAL guidance. It is a starting observation, not a general success rate or skill effect. Evaluation plan.

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
- Keep old feature IDs unchanged after moving phases, and never reuse a rejected ID.
- Order and priority live only in the README tracking table (OPEN-xx); feature evidence lives here.

## Agent behaviour scenarios (EV-01 to EV-16)

The scenarios the SKILL and EVAL items are measured against (moved here from `evaluation-plan.md`, 25 Nov 2026).

| ID | Context and user intention | Expected measurable agent behavior |
|---|---|---|
| EV-01 | “Debug this application and find the problem” | When runtime evidence is necessary/requested, actually inspect running JVM; do not invent observations |
| EV-02 | Java discount math wrong | Observe first incorrect `discountRate` / operands, explain integer division from real state |
| EV-03 | Ordinary failing JUnit 5 method | Discover/launch only requested test, observe breakpoint and actual values — **now supported by Phase 1** |
| EV-04 | Wrong record within a large loop | Select conditional breakpoint where appropriate, avoid hundreds of stops |
| EV-05 | Multi-class defect | Follow causal values across frames and identify first wrong producer |
| EV-06 | Two sessions coexist | Target correct launch object/ID, unrelated session untouched |
| EV-07 | Bridge failure, stale frame or never-hit breakpoint | Bounded recovery or truthful stop; no invented completion |
| EV-08 | NORMAL/CONTROLLED requested | End exactly the owned resources and preserve selected user session state |
| EV-09 | Static explanation, no execution requested | Do not start debugger or unnecessary services |
| EV-10 | Skill activation/removal | Prove skill was loaded, compare outcomes, and show core debugger still works after removal |
| EV-11 | Find right Boot server among multiple projects | Select appropriate project/config, ask when ambiguous; no wrong launch (SB-01/02) |
| EV-12 | Boot REST order quote returns incorrect value | Confirm readiness, send approved local HTTP request, inspect controller/service and identify wrong runtime value (SB-03/04/05) |
| EV-13 | Spring Boot service/unit or `@SpringBootTest` failing | Pick individual JUnit method, distinguish plain test from Spring context; no irrelevant full suite (SB-06/07) |
| EV-14 | Boot startup fails or endpoint inaccessible | Distinguish startup failure, bind error, timeout and wrong port; bounded checks (SB-03) |
| EV-15 | Boot validation/handled exception or wrong HTTP response | Capture actual request, application exception/validation path and HTTP status without assuming controller ran (SB-08) |
| EV-16 | Two Boot services/unsafe HTTP operation | Correct session/request target, explicit consent, user resources untouched and successful scoped cleanup (SB-09/10) |

**Required primary artifacts:** repeatable fixture; exact prompt; fixture version; oracle; explicit vs symptom-only classification; tool and JVM traces; request/HTTP evidence for Boot; before/after launch+marker state; verdict; evaluator notes and model/version. A skill must not simply memorize known fixture source/line numbers; hold source visibility fixed across all cohorts and include at least one held-out defect scenario.

## External research (RS-01 to RS-07)

Sources that shaped the features and the skill (moved here from `research-sources.md`, 25 Nov 2026).

### RS-01 — Microsoft DebugMCP `debug-live` skill

- **Source:** [skill](https://github.com/microsoft/DebugMCP/blob/main/skills/debug-live/SKILL.md) · [repository](https://github.com/microsoft/DebugMCP)
- **What it is:** a separate agent skill providing procedural guidance for interactive VS Code debugging, distinct from the MCP debugger tools themselves.
- **Value:** guide agents to collect runtime evidence, choose breakpoints, trace values, handle short-lived programs and minimize unproductive stepping.
- **Our status:** **No equivalent installed skill yet**. Basic MCP breakpoints and inspection exist, but systematic guidance and measurable automatic selection do not.
- **Can we implement?** Independent original skill is plausible. Actual activation, effectiveness and license obligations for any reused text still require verification.
- **Track:** SKILL-01/02/03; EV-01/02/07/09/10. **Important:** do not copy blanket “remove all breakpoints” guidance; only investigation-owned resources may be cleaned up.

### RS-02 — IntelliJ IDEA `ij-debugger`

- **Source:** [JetBrains agentic debugging documentation](https://www.jetbrains.com/help/idea/agentic-debugging.html) · [JetBrains agent skills overview](https://blog.jetbrains.com/idea/2026/08/agent-skills-in-intellij-idea/)
- **What it is:** IDE-integrated MCP debugger access plus a bundled skill teaching agents how to operate it; JetBrains documentation describes agent-initiated debugger workflows and debugger tools.
- **Value:** the agent can move beyond source-only reasoning to actual state inspection using its IDE.
- **Our status:** core Java launch/breakpoint/step/inspect capability exists; **equivalent skill does not**. No verified feature parity for conditional/exception breakpoints, logpoints, JUnit or other advanced functions.
- **Can we implement?** General workflow is plausible; any individual capability requires an Eclipse API and MCP exposure assessment. Exact bundled skill contents and copying rights are not yet verified.
- **Track:** SKILL-01/02; JAVA-01/02/05.

### RS-03 — Microsoft DebugMCP debugger implementation

- **Source:** [DebugMCP repository](https://github.com/microsoft/DebugMCP) · [server design](https://github.com/microsoft/DebugMCP/blob/main/docs/architecture/debugMCPServer.md)
- **What it is:** an MCP bridge exposing debugger operations to coding agents in VS Code. It is **not the same thing** as the `debug-live` skill.
- **Value:** concrete API/UX ideas for launch, breakpoint management, inspection, stepping, and client integration.
- **Our status:** basic equivalent operations exist; advanced per-feature parity remains unverified. Different platform and tool contracts.
- **Can we implement?** Requires assessment per tool, not direct copying of its VS Code implementation.
- **Track:** JAVA-01/02/05/06 and CORE regression suite.

### RS-04 — Microsoft Java Debug Agent / VS Code Java Debugger

- **Source:** [Java Debug Agent guide](https://github.com/microsoft/vscode-java-debug/blob/main/bundled/agents/README.md) · [Java debugger repository](https://github.com/microsoft/vscode-java-debug)
- **What it is:** Java-specific AI-assisted debugging guidance and functionality integrated with VS Code/Copilot; separate from the general DebugMCP skill.
- **Value:** Java launch/test workflows, Java-specific debug operation patterns, and practical fixtures.
- **Our status:** standalone Java `main()` debugging implemented. Agent-driven individual JUnit debugging, no-config advanced Java launch and attach are **not established** in our snapshot.
- **Can we implement?** Requires per-feature feasibility spikes, especially JUnit launch handling.
- **Track:** JAVA-03; EV-03 and REG-15.

### RS-05 — Debug2Fix, Microsoft Research

- **Source:** [paper](https://arxiv.org/abs/2602.18571) · [research page](https://www.microsoft.com/en-us/research/publication/debug2fix-debug2fix-supercharging-coding-agents-with-interactive-debugging-capabilities/)
- **What it is:** research integrating interactive debugging into a coding agent through a specialized debugging subagent.
- **Value:** evaluation design and separation of interactive-debugging benefit from other agent architecture changes.
- **Our status:** **Not implemented** as an architecture; no evidence that adding a subagent is necessary for this Eclipse product.
- **Can we implement?** Technically conceivable, but **not selected**; compare simple skill guidance first.
- **Track:** SKILL-04; EV-02/05.

### RS-06 — Controlled evaluations of debugger utility

- **Source:** [Pdb or Not to Be (arXiv 2611.08666)](https://arxiv.org/abs/2611.08666)
- **What it is:** a study comparing debugging interfaces for coding agents, including the possibility that more debugging tools do not improve outcomes in a given setup.
- **Value:** avoid equating “more debugger calls” with “better defect resolution”; compare accuracy, evidence, time and resource cost.
- **Our status:** comparison framework still needs implementation.
- **Track:** SKILL-04; EV-09 (avoid unnecessary debugging).

### RS-07 — Real-world tool and skill loading failures

- **Source:** [DebugMCP skill discovery issue #105](https://github.com/microsoft/DebugMCP/issues/105) · [DebugMCP issues](https://github.com/microsoft/DebugMCP/issues)
- **What it is:** maintainer/user reports, not proof of frequency or general behavior.
- **Value:** tests should distinguish installed skill from skill actually loaded, and assess wrong-session selection and stalled waits.
- **Our status:** no automatic skill discovery tests; current E2E has bridge preflight and separate launch state validation.
- **Track:** EV-01/06/07 and skill activation telemetry.

### Future sources

Add a new RS-ID only for sources with identifiable provenance and concrete relevance. Record: title, URL, date checked, source type (docs/code/issue/paper/forum), claim actually supported, idea, feature ID, feasibility, evidence gap, evaluation case and disposition. Never equate forum anecdotes with verified implementation.
