# Development Roadmap — Phases 0–5

<!-- CLAUDE-FIRST-PRIORITY:BEGIN -->
## Current implementation order — 23 November 2026

**First: P0 — Claude Code symptom-only acceptance.** Do not start new debugger features until an actual no-skill agent baseline has been evaluated. The 23/23 installed Java E2E PASS result proves direct debugger scenarios, **not Claude's autonomous choices**.

| Order | Priority | Target / gate |
|---|---|---|
| **1** | **P0** | **Claude symptom-only no-skill baseline:** JUnit, wrong Boot quote, handled HTTP 400; independently review tool calls, actual frames/values, diagnosis, consent and cleanup; add Java calculation and concurrent-service prompts as *planned* cases |
| 2 | P0 | Fixture synchronization: marker-verified, safe sync of dedicated Eclipse workspace; preserve unrelated files |
| 3 | P0 | Event-correlated pause waiting: correct JVM, thread, breakpoint reason, no stale/wrong stop |
| 4 | P0 | User-resource isolation: user-owned breakpoint/configuration preservation and concurrent launches |
| 5 | P1 | Run to Line: scoped debug operation, correct thread and cleanup |
| 6 | P1 | Change Variable Value: explicit permission, owned session and safe failure |
| 7 | P1 | Repeated Claude trials, skill activation and no-skill/with-skill comparisons |
| 8 | P1 | Other MCP clients including Codex: verify real interoperability |
| 9 | P2 | Thread dumps, local attach, method breakpoints and watchpoints: targeted feasibility |
| 10 | P2 | Spring Tools launch modes and expanded Spring-context JUnit coverage |
| 11 | P2 | Linux/macOS and remaining PowerShell harness parity |
| 12 | P3 | Remote/Docker attach and Hot Code Replace, subject to user demand and safety |

**Evidence policy:** Existing `claude` Java launcher captures transcript artifacts as `REVIEW_REQUIRED`, never automatic PASS. Start with an individual JUnit smoke trial, then the Boot quote and validation trials. Repeat on fresh sessions before estimating reliability. Keep direct 23/23 as the regression gate; capture exact Git revision, bundle, tools and reports.

**Selection of new features should follow observed agent failure modes**, rather than treating IDE GUI features as proven agent MCP support. See [evaluation plan](evaluation-plan.md) and [README](../README.md#next-development-priority-claude-symptom-only-acceptance).
<!-- CLAUDE-FIRST-PRIORITY:END -->

<!-- JAVA-E2E-19-STATUS:BEGIN -->
**Latest installed-Eclipse result (23 November 2026): 23/23 direct Java E2E scenarios PASS in one combined Windows run, with successful cleanup for each scenario.** Groups: Phase 1 Java/JUnit (7), Phase 2 validation/ownership (2), Boot discovery/readiness (2), HTTP quote defect (1), core acceptance (4), Boot failure/isolation acceptance (7). Dedicated startup-failure, readiness-timeout and port-conflict tests passed; startup and port failures were verified using actual JVM PIDs and OS process exit evidence. The combined result was reported from a local working tree, not a separately verified published release. Autonomous Claude, skill activation/comparison, STS-specific launch modes, Linux/macOS and full user-owned marker/configuration preservation remain OPEN. **Current implementation, exact runner commands and prioritized next steps:** [root README](../README.md#next-priorities). Older status tables below describe their dated milestones and must not be interpreted as the latest 23-scenario direct-test status.
<!-- JAVA-E2E-19-STATUS:END -->


[README](../README.md) · [Feature-level tracker](feature-roadmap.md) · [Spring Boot scenarios](spring-boot-debugging-scenarios.md) · [Agent evaluation](evaluation-plan.md) · [Codex work queue](codex-implementation-backlog.md) · [Phase 1 evidence](phase1-implementation-status.md)

**Planning baseline:** GitHub `main` at `11e696624412725e2668fbe074cbf4e098b31055`, reviewed 22 November 2026. This document **reorders the proposed phases**; it does not claim the roadmap changes or proposed features have been implemented. Source of truth for actual installed testing remains [Phase 1 Implementation Status](phase1-implementation-status.md).


<!-- PHASE2-VERIFICATION-CONSOLIDATION:BEGIN -->
## Local Phase 2 Verification Update (22 November 2026)

**Newest local installed evidence:** SB-08 direct invalid-quantity request debugging and SB-09 bounded ownership/consent checks both **PASS** together, followed by **7/7 Phase 1 bridge regression PASS**, all with reported cleanup. The local installed bundle was `1.5.0.20261122084229515`. These results extend, but do not replace, the separate historical P2-01â€“P2-05 audit evidence. P2-06 remains **in progress**, because full SB-01â€“SB-10 explicit/symptom/negative gates and STS coverage are not complete. [Consolidated evidence](phase2-verification-consolidation.md).

**Immediate order:** archive successful results and reproducible local harness fixes â†’ finish essential multi-service and environment checks â†’ obtain bounded skill-free Java/JUnit/Boot symptom baseline â†’ author optional unified skill (Phase 3) â†’ controlled A/B evaluation (Phase 4). Do not imply that an optional skill is required for core debugging.
<!-- PHASE2-VERIFICATION-CONSOLIDATION:END -->

## Contents

- [Roadmap overview](#roadmap-overview)
- [Phase 0 — Core Eclipse MCP debugger](#phase-0--core-eclipse-mcp-debugger)
- [Phase 1 — Advanced core Java debugging](#phase-1--advanced-core-java-debugging)
- [Phase 2 — Essential Spring Boot and JUnit debugging](#phase-2--essential-spring-boot-and-junit-debugging)
- [Phase 3 — Unified agentic debugging skill](#phase-3--unified-agentic-debugging-skill)
- [Phase 4 — Skill evaluation and optimization](#phase-4--skill-evaluation-and-optimization)
- [Phase 5 — Advanced Java and Spring Boot capabilities](#phase-5--advanced-java-and-spring-boot-capabilities)
- [Cross-phase engineering rules](#cross-phase-engineering-rules)
- [Immediate implementation order](#immediate-implementation-order)

## Roadmap overview

| Phase | Goal | Present evidence/status | Gate to next phase |
|---|---|---|---|
| **0** | Real Eclipse JDT debugger controlled by a Java MCP server | Implemented; installed calculator E2E and ownership evidence | Preserve base regression and resource isolation |
| **1** | Conditional and exception breakpoints, single JUnit test, nested inspection, tracepoints, owned restart and step filters | **All seven direct and explicit Claude tests passed on installed Windows candidate; publication pending** | Decide publication of the verified ZIP |
| **2** | Reliable Boot launch/configuration, readiness, HTTP reproduction and practical Boot/JUnit debugging | Initial installed Boot and JUnit checks recorded; full acceptance pending | One complete real HTTP debugging path, Boot unit/integration test paths, negative/ownership tests and installed regression |
| **3** | Optional, replaceable Java + JUnit + Spring Boot agentic debugging skill | Research and initial Java behavior baseline exist; skill files exist; activation/comparison incomplete | Demonstrate correct activation and cross-context guidance without coupling debugger to skill |
| **4** | Repeatable no-skill / stable-skill / candidate-skill testing | Evaluation specification exists; **A/B runner and repeated trials not built** | Measurable value with predeclared thresholds, no safety or non-debug regressions |
| **5** | Remote attach, watchpoints, run-to-line, HCR, mutation and deeper Boot/JPA/transaction diagnostics | **Deferred; feasibility and user value to be established** | Feature-specific API spike, fixture, proof, safety review and deliberate prioritization |

**Why Spring Boot precedes the skill:** A full skill developed only against plain Java could prematurely assume a `main()` invocation or single debugged thread. Spring Boot introduces long-running server lifecycle, configured launch, HTTP reproduction, multiple request threads and Spring test launches. First establish a *bounded* Boot baseline, then teach one skill to select the appropriate workflow. Do not implement every Spring/Java capability before beginning the skill.

**Stable identifiers:** `CORE-*`, `JAVA-*`, `BOOT-*`, `SKILL-*`, `EVAL-*`, `P1-*` and `EV-*` remain unchanged; only phase assignments change. A former Phase 5 Boot feature is now Phase 2; a former Phase 2 skill is now Phase 3; evaluation moves to Phase 4. Previously deferred Java features move to Phase 5. Do not renumber IDs to match phase numbers.

## Phase 0 — Core Eclipse MCP debugger

**Delivered:** Eclipse JDT/PDE plugin, authenticated localhost bridge, Java MCP server, Claude Code registration, Java main discovery, launch/reuse, breakpoint CRUD, stack and locals, thread control, source navigation and ownership-aware NORMAL/CONTROLLED investigations. Maven/Tycho packaging and Windows developer deployment/doctor commands exist.

**Evidence:** Installed Claude calculator regression; class-hash match; ownership tests. **Remaining regression coverage:** multi-session editor focus, thorough managed-registration preservation and edge cases. These are not reasons to relabel the whole core as unimplemented.

**Exit invariant:** No skill is required. Every new phase must preserve user breakpoints, borrowed sessions and launch configurations. [Capabilities](capabilities.md).

## Phase 1 — Advanced core Java debugging

| Feature ID | Implemented capability | Documented verification |
|---|---|---|
| JAVA-01 / P1-COND | Conditional line breakpoints | Targeted record `1057` with runtime variables |
| JAVA-02 / P1-EXC | Caught/uncaught exception breakpoints | Throwing stack and live `detailMessage` |
| JAVA-03 / P1-JUNIT | Discover and debug a selected JUnit 5 method | Single failing `appliesTenPercentDiscount` debug launch and operands |
| JAVA-04 / P1-VARS | Bounded nested field/array inspection | Nested address, items and values |
| JAVA-05 / P1-TRACE | Non-suspending tracepoints with explicit consent | `TRACE-ORDER 1057` observed; full output not proven |
| JAVA-06 / P1-PAUSE | Pause and investigation-owned restart | Replacement process observed; unauthorized restart blocked |
| JAVA-07 / P1-FILTER | Per-target Java stepping filters | Application frame after stepping |

**Candidate evidence:** Bundle `1.5.0.20261121184224374`: 7/7 direct bridge, 7/7 explicit Claude, 32/32 ownership/failure checks, installed calculator regression; fresh and repeat Windows deployment recorded. In seven **single-run** natural-language trials with relevant fixture source and lifecycle guidance, 6/7 selected intended mechanism, 5/7 complete and 2/7 partial. The partial cases were truncated trace retrieval and pause without requested restart. Do not translate single trials into a population success rate. [Full records](phase1-implementation-status.md) · [Prompts](phase1-claude-scenarios.md).

**Remaining release work:** The [source and artifact audit](phase1-implementation-status.md#source-and-artifact-audit) identified `ff28455` as a match for the installed plug-in contents. The [new distribution ZIP](phase1-implementation-status.md#verified-distribution-zip) passed installed verification. Publication awaits approval. Track full JUnit Platform discovery/parameterized cases and post-exit output as follow-ups, not as unimplemented Phase 1 fundamentals.

## Phase 2 — Essential Spring Boot and JUnit debugging

**Objective:** Debug a real Spring Boot application *through Eclipse*, including a failing HTTP call and targeted JUnit tests, with the existing Java tools reused wherever possible. This is a bounded, testable milestone, not an indefinite Spring Tools replacement.

**Important distinction:** Phase 1 already supports an individual ordinary JUnit 5 test. The existing JUnit launcher also reached application code from a selected Spring-context method. Installed checks passed for a missing method, a context startup error and a disabled method. [JUnit verification](spring-boot-tool-audit.md#p2-05-junit-failure-and-skip-checks)

The [tool audit](spring-boot-tool-audit.md) records the fixture results, observed limits and remaining configuration checks.

### Milestone 2A — Tool and environment feasibility

1. Inspect Eclipse/JDT/STS launch and existing `start_java_application`, `list_java_applications`, `start_junit_test` support using real fixtures. Record whether a new tool is actually needed.
2. Create a deliberately small, standalone Spring Boot Maven fixture with deterministic defects: request calculation, validation failure and one failing Spring/JUnit test. Prefer an in-memory dependency and fixed test data; no cloud account or external database required for the first milestone.
3. Run Java and JUnit discovery with plain Eclipse and with Spring Tools if present; document actual minimum IDE prerequisites. Do not claim STS support without installation testing.

### Milestone 2B — Essential live debugging

| ID | Outcome | Minimum acceptance |
|---|---|---|
| BOOT-01 | Select correct Boot project/main class | Distinguish two projects/mains; explicit selection on ambiguity |
| BOOT-02 | Launch/reuse safely | Preserve existing VM args, application args, profiles, environment, working directory; refuse ambiguous matches |
| BOOT-03 | Confirm readiness/error | Observe a real ready signal or bounded probe; distinguish connection refused, failed startup and timeout |
| BOOT-04 | Reproduce REST defect and debug service chain | Approved HTTP request suspends at expected controller/service; inspect first wrong runtime value; do not modify application code |
| BOOT-06 | Debug selected Spring Boot JUnit test | Targeted service test and one Spring-context test; verify right method, actual launched configuration and breakpoint/stack evidence |
| BOOT-07 | Diagnose HTTP validation/exception path | Trigger known failing request, capture actual exception/validation path, avoid invented result |

**Scope limits:** Remote JVM attach (`JAVA-08` / `BOOT-05`), deep transaction/JPA debugging, distributed tracing and changing live data are Phase 5 candidates. Spring MVC tests using MockMvc and real-port HTTP tests must be distinguished; `@WebMvcTest` and `@SpringBootTest` are not equivalent runtime configurations.

**Safety:** Avoid unsolicited HTTP writes; send only user-authorized requests against explicitly selected local/test targets. Require approval for requests with side effects, preserve user sessions/markers/configurations, never replace running production applications, retain a bounded exit and honest failure reporting.

**Exit gate:** One installed candidate running all Phase 0/1 checks plus deterministic Spring Boot direct tests and explicit Claude scenarios; targeted Boot/JUnit tests; negative cases and cleanup; skill-free natural-language baseline. Details, exact prompts and expected JVM evidence: [Spring Boot Debugging Scenarios](spring-boot-debugging-scenarios.md). Implementation tasks: [Codex backlog](codex-implementation-backlog.md).

## Phase 3 — Unified agentic debugging skill

**Objective:** A single *optional* original skill for three contexts: plain Java, JUnit (including Spring-context tests), and Spring Boot request-driven debugging. Keep common investigation guidance (intent, hypotheses, observation, first incorrect value, bounded steps, ownership and truthfulness) and context-specific procedures separately organized within the skill if needed.

**Behaviors:**
- Detect when to use runtime debugging versus static explanation only; avoid unnecessary launches.
- Choose relevant mechanism (conditional/exception breakpoint, JUnit, tracepoint, nested inspection, pause/restart, filters) from symptoms, not simply obey a tool-naming prompt.
- For Boot, select project/launch, establish readiness, reproduce *approved* requests and inspect actual request thread through controller/service/repository if observable.
- Handle stale frames, missing targets, tool failures, timeouts and process exits, reporting evidence without fabrication.
- Finish the correct NORMAL or CONTROLLED investigation, keeping all user-owned resources intact.

**Architecture contract:** Skill is independent/versioned guidance; Eclipse plugin and MCP schemas do not import or require it. Remove the skill and the same core debugger must still function. Research JetBrains `ij-debugger`, Microsoft DebugMCP `debug-live`, Java Debug Agent and Debug2Fix as **ideas**, not as copied skills or assumed feature parity. [Research](research-sources.md).

**Exit gate:** Successful activation and cross-context trial runs, reproducible skill installation/removal, no safety regression and a credible comparative benefit; metrics evaluated under Phase 4. **Skill source exists; activation, cross-context and comparative acceptance incomplete.**

## Phase 4 — Skill evaluation and optimization

**Objective:** Compare **A: no skill**, **B: stable skill** (once established) and **C: candidate skill**, with fixed model, permissions, fixture/source visibility, prompt, configuration, environment and reset process. Confirm the skill actually loaded.

**Deterministic regression:** Re-run base, seven Phase 1, Boot/Boot-JUnit, auth, consent, multi-session and ownership tests with skill absent. Tests should inspect Eclipse state rather than grading Claude's prose.

**Behavior evaluation:** Existing EV-01–EV-10 plus Spring Boot cases EV-11–EV-16 in [evaluation-plan.md](evaluation-plan.md). Distinguish tool-selection, complete diagnosis, evidence correctness, cost/latency, unsafe effects and cleanup. Start with several fresh trials per condition (e.g. five for screening), then enlarge based on observed variability; do not claim significance from a handful.

**Exit gate:** Prespecified uplift in targeted behavior, **zero unauthorized mutations**, no material degradation on non-debug tasks, reproducible experiment manifest and published caveats. **Runner and repeated A/B trials not implemented.**

## Phase 5 — Advanced Java and Spring Boot capabilities

| Feature ID | Candidate | Gate |
|---|---|---|
| JAVA-08 / BOOT-05 | Attach local/remote JDWP JVM | Eclipse/JDT API feasibility; ownership, permissions, transport and timeout tests |
| JAVA-09 | Field watchpoints | JDT marker spike, read/write behavior, user-marker safety |
| JAVA-11 | Run to line | Prove useful difference from temporary owned line breakpoints |
| JAVA-10A | Hot Code Replace | Compatibility/rollback tests, explicit consent and constraints |
| JAVA-10B | Variable mutation | Consent, no unapproved side effects, safe failure reporting |
| BOOT-08 | Spring Data JPA and transaction-aware diagnosis | Small reproducible test service and independent runtime evidence |
| BOOT-09 | Advanced multi-request/concurrent diagnostics | Target correct request/thread without disturbing unrelated work |

All deferred. These are *candidates*, not an implementation promise.

## Cross-phase engineering rules

- **Evidence before status:** separate proposed, implemented source, tested installed plugin, agent-observed and publicly released.
- **Stable IDs:** move a feature to another phase without renumbering or erasing historical test IDs.
- **Safety:** own the exact actual launch/marker, not a launch name alone; no unauthorized request or expression evaluation; protect user resources.
- **Reusability:** do not duplicate JDT debugging engines, Java launch functionality, or JUnit discovery when a real reuse path suffices.
- **Testing:** each new Boot feature needs a real fixture, deterministic bridge assertion, explicit Claude prompt, symptom-only prompt, negative test and cleanup verification.
- **Product independence:** no skill may change MCP contract or be needed by debugger logic.
- **Build discipline:** Java/Maven for code/verification; Windows batch/PowerShell allowed for development orchestration; no Python dependency.
- **Documentation:** update README, this roadmap, feature tracker, scenario catalog, evaluation plan and acceptance evidence together.

## Immediate implementation order

**Superseded by [Current implementation order](#current-implementation-order--23-november-2026) above.** The former list began with already completed Spring Boot feasibility; it is not an active work queue.

[Detailed historical Codex tasks](codex-implementation-backlog.md) · [Feature matrix](feature-roadmap.md).
