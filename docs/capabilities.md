# Supported Debugging Capabilities — Java, Spring Boot and JUnit

<!-- JAVA-E2E-19-STATUS:BEGIN -->
**Current evidence (23 November 2026):** The initial Claude symptom-driven trial set has been reviewed: single JUnit diagnosis (final-result record) and two Spring Boot HTTP investigations (event-level runtime PASS). A historical uninterrupted 23/23 direct JDT regression passed; after the fixture HTTP change, first 16 direct scenarios passed together and all seven Boot-gap scenarios later passed separately, but the newest build has **not** completed a fresh uninterrupted 23/23 run. The opt-in Claude harness still reports `REVIEW_REQUIRED`, not automatic acceptance. Repeated no-skill reliability, optional skill A/B, independent cleanup inventories, STS-specific launch modes, and cross-platform coverage remain open. [Current acceptance evidence and boundaries](claude-acceptance-2026-11-23.md). The dated sections below are historical snapshots; their old `NOT RUN` cells refer to the time they were written, not current overall status. See [README](../README.md).
<!-- JAVA-E2E-19-STATUS:END -->


[README](../README.md) · [Feature roadmap](feature-roadmap.md) · [Phase 1 evidence](phase1-implementation-status.md) · [Phase 2 consolidation](phase2-verification-consolidation.md) · [Spring Boot audit](spring-boot-tool-audit.md) · [Scenario catalog](spring-boot-debugging-scenarios.md) · [Evaluation plan](evaluation-plan.md)

**Reviewed: 22 November 2026.** The latest repository review used GitHub `main` at `77c43a08f72d7a4578718a92d8d0c8b9bce2377a`, together with the user's reported installed Windows run on candidate `1.5.0.20261122084229515`. These are **different evidence sources**; locally verified changes are not automatically proof of release or of code at the reviewed GitHub commit.

## At a glance

**Yes — Agentic Debugger for Eclipse IDE has working core debugging capabilities for standard Java applications, Spring Boot applications and individual JUnit 5 methods.** It uses the existing Eclipse Java Debug Tools (JDT), not a separate debugger per framework. **The optional debugging skill files exist, but full activation/comparative acceptance remains incomplete; core debugger operations do not depend on a skill.**

| Application type | What a developer can currently do | Evidence level |
|---|---|---|
| **Java application** | Discover `main()` entry points, launch/debug, set ordinary and advanced breakpoints, step, inspect locals/frames/objects, collect trace output, pause/restart owned launches | **Installed verification**: all seven advanced Phase 1 direct scenarios passed again on 22 November 2026 |
| **Spring Boot application** | Discover/select Boot main, launch with existing configuration, observe startup/readiness, reproduce an approved local HTTP request, stop in controller/service/repository code and inspect values or handled errors | **Installed verification for main workflows**: earlier HTTP defect trace plus latest SB-08 direct HTTP 400; not the full agent/compatibility matrix |
| **JUnit 5 test** | Discover and debug one selected method in a standard unit test or `@SpringBootTest` integration test; inspect suspended application frames and assertion/context failure evidence | **Installed verification**: Phase 1 single-test run, earlier Boot/JUnit ordinary and Spring-context cases and negative tests |

### Evidence labels used below

- **Verified — Java**: installed bridge and/or explicit agent tests exercised the named operation in a standard Java fixture.
- **Verified — Boot/JUnit**: an installed experiment exercised that operation or end-to-end workflow with its identified Boot or test fixture.
- **Supported via JDT; not separately verified**: the Java debugger API is available to these Java execution contexts, but there is no independent published test for that exact combination. Do **not** present this as a passing scenario.
- **Partial / environment-specific**: a subset is tested, or another Eclipse installation/launch mode needs validation.
- **Not implemented**: no verified MCP capability for the proposed operation.

## Capability matrix by execution context

| Capability | Standard Java application | Spring Boot application | JUnit 5 (including Spring tests) |
|---|---|---|---|
| Discover available mains or individual tests | **Verified** | **Verified** (two Boot mains) | **Verified** (single methods) |
| Launch a Java debug session | **Verified** | **Verified** (Boot main with existing config) | **Verified** (plain and Spring-context test) |
| Ordinary line breakpoint | **Verified** | **Verified** (controller, service, repository) | **Verified** (application code during tests) |
| Conditional breakpoint | **Verified** | Supported via JDT; **not separately verified** | Supported via JDT; **not separately verified** |
| Caught/uncaught exception breakpoint | **Verified** | Supported via JDT; handled-exception path **verified** with ordinary line stops | Supported via JDT; **not separately verified** for exception breakpoint |
| Step Into | **Dedicated Java E2E PASS (23 Nov 2026; `core-step-into-out`)** | Supported via JDT; not independently tested | Supported via JDT; not independently tested |
| Step Over | **Verified in installed calculator workflow** | Supported via JDT; not independently tested | Supported via JDT; not independently tested |
| Step Out | **Dedicated Java E2E PASS (23 Nov 2026; `core-step-into-out`)** | Supported via JDT; not independently tested | Supported via JDT; not independently tested |
| Resume | **Verified in installed Java workflows** | **Verified in HTTP request investigation** | **Verified in JUnit debugging workflow** |
| Pause / suspend a running thread | **Verified** (JAVA-06) | Supported via JDT; not independently tested | Supported via JDT; not independently tested |
| Read threads, stacks and local variables | **Verified** | **Verified** (request thread and service locals) | **Verified** (test target and application locals) |
| Expand nested variables, bounded field/array reads | **Verified** | Supported via JDT; **not separately verified** | Supported via JDT; **not separately verified** |
| Non-suspending tracepoint | **Verified** | Supported via JDT; **not separately verified** | Supported via JDT; **not separately verified** |
| Pause and restart investigation-owned process | **Verified** | Supported via Java launch path; **not separately verified** | Restart semantics **not separately verified** |
| Target-specific step filters | **Verified** | Supported via JDT; **not separately verified** | Supported via JDT; **not separately verified** |
| Debug approved live HTTP request and inspect response | Not a generic Java debugging operation | **Verified** (incorrect quote, validation HTTP 400) | **Verified** in a Spring integration test; differs from an independently launched service |
| Debug exactly one JUnit 5 method | Not applicable to ordinary main launch | **Verified** for `@SpringBootTest` method | **Verified** for standard and Spring-context JUnit method |
| Preserve reused/user-owned sessions during cleanup | **Verified** | **Verified subset** (SB-09 borrowed-launch checks) | Underlying policy tested; **not all test-context combinations verified** |
| Plain Eclipse JDT/JUnit without Spring Tools | **Verified** | **Verified for the tested fixture** | **Verified for the tested fixture** |
| Spring Tools-specific launch modes | Not applicable | **Not yet verified** | **Not yet verified** |

**Reading the matrix:** “Supported via JDT” is an engineering expectation based on the same Java debug target, not evidence that each combination passed its own acceptance test. Runtime inspection cannot proceed until a real target is suspended; debugging can also be blocked by missing project dependencies or launch configuration problems.

## Feature inventory and evidence

Keep these stable IDs aligned with the [feature roadmap](feature-roadmap.md), which tracks implementation, open decisions and future work.

| ID | Implemented capability | Current evidence boundary / outstanding check |
|---|---|---|
| CORE-01 | Authenticated local Eclipse↔MCP bridge | Installed loopback connection verified; keep negative authentication/port collision coverage. Concurrency/deadline rework tracked as [CORE-13](feature-roadmap.md#phase-0--core-eclipse-mcp): implemented in source, **installed verification pending** |
| CORE-02 | Java application discovery | Main discovery and multiple Boot mains verified; ambiguous agent selection needs more trials |
| CORE-03 | Start/reuse existing Java launch configuration | Installed owned and borrowed launches verified; custom-config negatives remain important |
| CORE-04 | Add/list/remove source line breakpoints | Installed runtime marker hits and cleanup; never delete user markers |
| CORE-05 | Pause, resume, Step Into, Step Over and Step Out | Dedicated Java E2E Step Into/Out passed on 23 Nov 2026; other cross-context negative coverage remains open |
| CORE-06 | Inspect JVM threads, stack frames and variables | Live Java, Boot and JUnit evidence; bounded reads recommended |
| CORE-07 | Navigate Eclipse editor to suspended source | Navigation operation verified; concurrent-session GUI focus needs manual validation |
| CORE-08 | Investigation lifecycle `NORMAL`/`CONTROLLED` | Previous 32/32 ownership checks, new SB-09 subset; full concurrent Boot matrix pending |
| CORE-09 | Managed Claude Code MCP registration | Implemented; installed preservation of unrelated registrations still needs coverage |
| CORE-10 | Source and installed binary integrity checks | Historical candidate checks passed; recheck source/artifact match for release |
| CORE-11 | Windows build/deploy/doctor/test scripts | Local installed deployment, doctor and regression passed; keep isolated test workspace |
| CORE-12 | Optional agent debugging skill | Skill source exists; live activation/comparative acceptance incomplete; core debugger independent |
| JAVA-01 | Conditional breakpoints | Phase 1 direct and explicit Claude verified |
| JAVA-02 | Caught/uncaught exception breakpoints | Phase 1 verified; Spring exception handler tested separately with line stops |
| JAVA-03 | Discover/debug individual JUnit 5 test method | Phase 1 and Boot/JUnit cases verified; parameterized/complex selection open |
| JAVA-04 | Nested variable expansion with limits | Phase 1 verified |
| JAVA-05 | Tracepoints | Phase 1 verified; symptom-only output coverage was partial |
| JAVA-06 | Pause/restart an owned debug launch | Phase 1 verified; symptom-only Claude restart was partial |
| JAVA-07 | Per-target step filters | Phase 1 verified |
| BOOT-01–BOOT-04 | Boot application selection, configuration reuse, startup/readiness, request tracing | Earlier installed fixture and HTTP investigation evidence; expanded agent matrix open |
| BOOT-06 | Selected Spring Boot service and context JUnit tests | Earlier installed checks passed, including failure/skip cases |
| BOOT-07 / SB-08 | Invalid quantity, handled error and HTTP response | **Local installed PASS**: service line 17; controller handler line 27; HTTP 400 |
| BOOT-10 / SB-09 | Ownership and authorization for Boot | **Local subset PASS**: borrowed launch, expired ID, unapproved POST; broader multi-service coverage open |
| BOOT-11 / SB-10 | Plain Eclipse vs Spring Tools compatibility | Plain JDT/JUnit path tested; Spring Tools-specific comparison open |

## Fundamental execution-control acceptance checklist (CORE-05)

These are **test cases under existing feature ID `CORE-05`**, not new features. `Implemented` confirms the operation is exposed by the existing debugger; it does not imply the focused live assertion has passed.

| Test case | Operation | Current evidence | Acceptance needed for standalone sign-off |
|---|---|---|---|
| CORE-05-01 | Resume a suspended thread | **Verified** in installed investigations | Correct selected thread resumes; unrelated targets remain unchanged |
| CORE-05-02 | Step Over | **Verified** in installed calculator scenario | Next suspend is in the expected caller method after executing current line; locals change as expected |
| CORE-05-03 | Step Into | **Dedicated Java E2E PASS (23 Nov 2026)** | From a caller, enter the expected application method and verify target frame, line and thread |
| CORE-05-04 | Step Out | **Dedicated Java E2E PASS (23 Nov 2026)** | From a callee, return to the expected caller and verify frame/line and state |
| CORE-05-05 | Pause / suspend | **Verified** in JAVA-06 | Stop the intended running thread, read the actual stack, leave other sessions untouched |
| CORE-05-06 | Ended/stale thread and invalid step | **Not fully acceptance-tested** | Report an actionable error without stepping an unrelated or terminated target |

**Scope:** These operations already exist in the Eclipse/JDT integration. Additional tests document confidence and guard regressions; do not implement duplicate stepping tools merely to satisfy this checklist. The Spring Boot and JUnit cross-context step matrix remains unverified and must be recorded separately if tested.

## Verified workflows

### 1. Standard Java application

A disposable installed Eclipse/JDT candidate executed all seven direct Java scenarios. These included conditional and exception stops, JUnit method selection, nested object inspection, a non-suspending tracepoint, pause/restart of an investigation-owned JVM and step filters. The later local `verify-phase1-eclipse.bat` run reported **7/7 PASS**, all with `cleanup=true`, and Maven **BUILD SUCCESS**. The earlier Phase 1 candidate also passed seven explicit Claude scenarios, as recorded in [Phase 1 status](phase1-implementation-status.md). These are separate test runs.

### 2. Spring Boot application with an incorrect HTTP quote

The earlier fixture investigation discovered `OrdersApplication`, waited for actual server readiness, issued one authorized POST to its loopback endpoint, and stopped in request-processing application code. The runtime showed `percent=10` but integer-divided `rate=0`, resulting in a response total of `120.00` rather than `108.00`. A direct bridge investigation and one explicit Claude investigation succeeded on a historical installed candidate. See [Spring Boot tool audit](spring-boot-tool-audit.md).

### 3. Spring Boot validation / handled exception

On local installed bundle `1.5.0.20261122084229515`, the SB-08 direct verifier observed the invalid-quantity execution at `OrderService.java:17`, then the controller's handler at `OrderController.java:27`. The approved local HTTP request returned **400** and body `{"error":"Quantity must be positive"}`. Cleanup passed. The initial client attempt failed because Windows PowerShell could not resolve `HttpClientHandler`; after explicitly loading `System.Net.Http` in the fixture client, the next direct runs passed. This was a test harness prerequisite, not evidence of a plugin failure.

### 4. Plain JUnit and Spring-context JUnit

The existing single-method JUnit operation launched the selected `OrderServiceTest` and `OrderApiIntegrationTest` methods through Eclipse JUnit. Earlier installed checks separately identified expected assertion failures, rejected a missing method, reported Spring context initialization failure and recognized a disabled method. The Spring-context integration test uses a Spring-managed application context; do not claim every JUnit test runs a standalone HTTP server or that a MockMvc test opens a real socket.

### 5. Session ownership and cleanup

Previous core ownership tests passed 32/32 checks. The more recent SB-09 direct verifier separately confirmed `borrowedLaunchPreserved=true`, `expiredInvestigationRejected=true`, `unapprovedPostRejected=true`, and `cleanup=true`. This is a **tested subset**, not proof of every possible multi-service interleaving or launch collision. The debugger's lifecycle policy is documented in the README.

## Not implemented versus not yet sufficiently tested

### Not implemented or deliberately deferred

| Candidate | Current state |
|---|---|
| Remote/local JDWP attach to an already-running JVM (`JAVA-08` / `BOOT-05`) | Deferred |
| Field access/modification watchpoints (`JAVA-09`) | Deferred |
| Run to line (`JAVA-11`) | Candidate, not verified as a supported MCP operation |
| Hot Code Replace (`JAVA-10A`) | Deferred; no verified user-facing operation |
| Runtime variable mutation (`JAVA-10B`) | Deferred; safety-sensitive |
| Deep JPA, persistence and transaction debugging (`BOOT-08`) | Deferred |
| Advanced concurrent/async request diagnostics (`BOOT-09`) | Deferred |
| Unified Java/JUnit/Spring Boot Claude skill (`SKILL-01`–`SKILL-03`) | Source exists; activation and controlled evaluation incomplete |

### Available workflows that still require acceptance work

- Symptom-only, **skill-free** Claude selection for Boot HTTP, ordinary JUnit, Spring-context JUnit and validation failures; repeat independent trials before claiming rates.
- Full SB-09 two-service/concurrent session isolation, launch ambiguity, user markers and cleanup under failure; earlier owner checks do not cover all new combinations.
- SB-10 comparison of plain Eclipse/JDT against Eclipse with Spring Tools installation and its specific launch delegates.
- Parameterized and otherwise complex JUnit method selection; don't extrapolate single-method JUnit results.
- Cross-context individual checks for conditional breakpoints, tracepoints, nested fields, pause/restart and step filters.
- Registered MCP client behavior in varied installations and final release source/artifact provenance.

## Repeat the deterministic checks (Windows CMD)

Use an **existing dedicated, disposable Eclipse test installation and workspace**, with the debugger plugin installed and the local bridge listening. The Phase 2 fixture client only calls a verified loopback server belonging to the fixture JVM, and requires explicit approval.

```cmd
scripts\windows\dev.bat doctor
scripts\windows\verify-phase2-eclipse.bat
scripts\windows\verify-phase1-eclipse.bat
```

For the Phase 2 command, set `AGENTDEBUG_WORKSPACE`, a non-default `AGENTDEBUG_PORT`, and `AGENTDEBUG_APPROVE_FIXTURE_POST=YES` **only for an approved disposable fixture**. Results are written to `evaluation/reports/phase2-live.json` and `evaluation/reports/phase1-live.json`. The direct verifiers do **not** run Claude and do not establish symptom-prompt quality.

Read [Phase 2 verification consolidation](phase2-verification-consolidation.md) for the exact candidate, prior evidence, failed initial attempt and remaining sign-off gates. Maintain the [scenario catalog](spring-boot-debugging-scenarios.md) and [feature tracker](feature-roadmap.md) separately; this file describes **current user-visible capabilities**, not future feature promises.

## Core regression invariant

**The Eclipse plugin and MCP server must remain usable with no skill installed.** Skills should only guide Claude's reasoning, investigation planning, tool selection, evidence quality and lifecycle consistency. Skills must not own debugger feature implementation, relax consent rules, change API contracts or silently modify other sessions.
