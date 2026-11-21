# Development Roadmap — Phases 0–5

[README](../README.md) · [Feature-level tracker](feature-roadmap.md) · [Spring Boot scenarios](spring-boot-debugging-scenarios.md) · [Agent evaluation](evaluation-plan.md) · [Codex work queue](codex-implementation-backlog.md) · [Phase 1 evidence](phase1-implementation-status.md)

**Planning baseline:** GitHub `main` at `11e696624412725e2668fbe074cbf4e098b31055`, reviewed 22 November 2026. This document **reorders the proposed phases**; it does not claim the roadmap changes or proposed features have been implemented. Source of truth for actual installed testing remains [Phase 1 Implementation Status](phase1-implementation-status.md).

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
| **1** | Conditional and exception breakpoints, single JUnit test, nested inspection, tracepoints, owned restart and step filters | **All seven direct and explicit Claude tests passed on installed Windows candidate; publication pending** | Identify and validate the distribution ZIP before publication |
| **2** | Reliable Boot launch/configuration, readiness, HTTP reproduction and practical Boot/JUnit debugging | **Planned, not verified as framework-specific functionality** | One complete real HTTP debugging path, Boot unit/integration test paths, negative/ownership tests and installed regression |
| **3** | Optional, replaceable Java + JUnit + Spring Boot agentic debugging skill | Research and initial Java behavior baseline exist; **skill not implemented** | Demonstrate correct activation and cross-context guidance without coupling debugger to skill |
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

**Remaining release work:** The [source and artifact audit](phase1-implementation-status.md#source-and-artifact-audit) identified `ff28455` as a match for the installed plug-in contents. Identify and validate the distribution ZIP, or verify a newly versioned candidate before publishing. Track full JUnit Platform discovery/parameterized cases and post-exit output as follow-ups, not as unimplemented Phase 1 fundamentals.

## Phase 2 — Essential Spring Boot and JUnit debugging

**Objective:** Debug a real Spring Boot application *through Eclipse*, including a failing HTTP call and targeted JUnit tests, with the existing Java tools reused wherever possible. This is a bounded, testable milestone, not an indefinite Spring Tools replacement.

**Important distinction:** Phase 1 already supports an individual ordinary JUnit 5 test. Phase 2 must *verify* that existing discovery/launch works for Spring-context tests and add only necessary changes; do not create a second JUnit subsystem prematurely.

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

**Exit gate:** Successful activation and cross-context trial runs, reproducible skill installation/removal, no safety regression and a credible comparative benefit; metrics evaluated under Phase 4. **Not implemented yet.**

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

1. **Release hygiene:** the installed plug-in audit is complete. Distribution ZIP verification remains open before publication.
2. **Boot feasibility + test fixture:** verify existing Java/JUnit tools against a small Boot application; capture the real gaps before adding APIs.
3. **Core Boot vertical slice:** safe project/launch selection, readiness, approved HTTP reproduction, suspended controller/service evidence and ownership cleanup.
4. **Boot/JUnit slice:** ordinary service unit test, one Spring-context JUnit test and an HTTP/validation failure scenario; assert real Eclipse evidence.
5. **Phase 2 hardening:** ambiguous launch, wrong request, startup failure, concurrent sessions, consent and reproducible `dev.bat`/Java-only acceptance.
6. **No-skill baseline:** repeated plain Java, JUnit and Boot symptom prompts with recorded test evidence and realistic context visibility.
7. **Optional skill + A/B:** implement the unified skill, verify activation, then the three-cohort evaluation runner and scoring.
8. **Advanced features:** only after individual benefits and feasibility are demonstrated.

[Detailed ordered Codex tasks](codex-implementation-backlog.md) · [Feature matrix](feature-roadmap.md).
