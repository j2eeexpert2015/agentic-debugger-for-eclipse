# Supported Debugging Capabilities — Java, Spring Boot and JUnit

[README](../README.md) · [Feature catalog](feature-roadmap.md) · [Spring Boot scenarios](#spring-boot-and-junit-scenarios-sb-01-to-sb-10) · [Test guide](../dev-e2e/README.md)

Current status and test counts are in the README; this page says what is implemented and how it was verified.

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
| CORE-01 | Authenticated local Eclipse↔MCP bridge | Installed loopback connection verified; keep negative authentication/port collision coverage. [CORE-13](feature-roadmap.md#phase-0--core-eclipse-mcp) concurrency and deadlines **verified on installed Windows build, 23 Nov 2026** (3×23/23, bridge group 4/4) |
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
| CORE-14 | Event-based stop waiting (`wait_for_stop`): reason, thread, location, frames and locals in one call; `TERMINATED`/`TIMEOUT` explicit | **Verified on installed Windows build, 23 Nov 2026** (`stops` group 5/5 stop scenarios, 23/23 regression). See [feature roadmap](feature-roadmap.md#phase-0--core-eclipse-mcp) |
| CORE-15 | Process output keeps beginning and end (first/last 4000 characters, total sizes) | **Verified on installed Windows build, 23 Nov 2026** (`stops:output`, now `stops-and-variables:program-output-read`); unit checks OUT-U1..U5 5/5 |
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

A disposable installed Eclipse/JDT candidate executed all seven direct Java scenarios. These included conditional and exception stops, JUnit method selection, nested object inspection, a non-suspending tracepoint, pause/restart of an investigation-owned JVM and step filters. The later local `verify-phase1-eclipse.bat` run reported **7/7 PASS**, all with `cleanup=true`, and Maven **BUILD SUCCESS**. The earlier Phase 1 candidate also passed seven explicit Claude scenarios, as recorded in Phase 1 status. These are separate test runs.

### 2. Spring Boot application with an incorrect HTTP quote

The earlier fixture investigation discovered `OrdersApplication`, waited for actual server readiness, issued one authorized POST to its loopback endpoint, and stopped in request-processing application code. The runtime showed `percent=10` but integer-divided `rate=0`, resulting in a response total of `120.00` rather than `108.00`. A direct bridge investigation and one explicit Claude investigation succeeded on a historical installed candidate. See Spring Boot tool audit.

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

Use the dedicated, disposable Eclipse test installation and workspace configured in `dev-e2e/e2e.properties`. The
fixture HTTP client only calls a verified loopback server belonging to the fixture JVM, and requires explicit approval.

```cmd
java dev-e2e/DevE2E.java config
java dev-e2e/DevE2E.java start
mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true
```

Results are written to `evaluation/reports/<group>.json` (groups and scenarios: [integration tests](integration-tests.md)). The direct tests do **not** run Claude and do not
establish symptom-prompt quality; see the [test guide](../dev-e2e/README.md) for Claude trials. This file describes
**current user-visible capabilities**, not future feature promises; feature IDs and their evidence are in the
[feature catalog](feature-roadmap.md).

## Core regression invariant

**The Eclipse plugin and MCP server must remain usable with no skill installed.** Skills should only guide Claude's reasoning, investigation planning, tool selection, evidence quality and lifecycle consistency. Skills must not own debugger feature implementation, relax consent rules, change API contracts or silently modify other sessions.

## Spring Boot and JUnit scenarios (SB-01 to SB-10)

The scenario catalog for the BOOT features (moved here from `spring-boot-debugging-scenarios.md`, 25 Nov 2026). Each scenario has an explicit prompt, a natural-language prompt and the evidence that counts as acceptance.

### SB-01 — Choose the correct Spring Boot application (BOOT-01)

**User problem:** Several Java projects/mains exist; Claude must not debug the wrong server.

**Explicit prompt:** “Use the Eclipse MCP tools to identify the Spring Boot applications in my workspace and show the available project/main choices. Select `boot-orders` only if it is uniquely identified. Do not start anything yet.”

**Natural-language prompt:** “I have two services open in Eclipse. Find the orders application I need to debug; don't start the other service.”

**Expected evidence:** Actual project ID/main type/available launch configurations; no side effects and no guessed choice under ambiguity. **Direct acceptance:** discovery is correct when both projects exist and when one has multiple mains. **Negative:** missing project and ambiguous matches return actionable errors. **Ownership:** user sessions unchanged.

### SB-02 — Launch with existing profile and environment (BOOT-02)

**User problem:** Debug session works only with specific profile, VM arguments and environment.

**Explicit prompt:** “Debug the existing Boot Orders launch configuration in Eclipse with its current `test` profile, VM/application arguments, working directory and environment unchanged. Confirm which configuration was used; do not create or overwrite another.”

**Natural-language prompt:** “This service works with its test profile in Eclipse but fails when launched with defaults. Debug it without losing the existing setup.”

**Expected evidence:** Profile observed through application endpoint or runtime value; real JVM process and selected launch config identity. **Direct acceptance:** exact existing config reused; no mutable config lost. **Negative:** multiple matches, invalid project and a missing required config. **Ownership:** a borrowed launch is never implicitly restarted/terminated.

### SB-03 — Application readiness and startup failure (BOOT-03)

**User problem:** Claude makes requests before the app is ready or incorrectly reports a successful start.

**Explicit prompt:** “Start the selected test Boot application in Eclipse. Confirm readiness from actual runtime evidence with a bounded wait before any HTTP call. If startup fails, report the failure and do not claim readiness.”

**Natural-language prompt:** “My Spring Boot service is starting, but calls keep failing. Determine whether it ever becomes ready and diagnose a startup error if present.”

**Expected evidence:** Observable readiness on the correct application/port or verified startup error/timeout; never assume `start_java_application` means server ready. **Negative:** port occupied, nonexistent profile/config property, unreachable endpoint. **Safety:** bounded retries, no indefinite loop.

### SB-04 — Reproduce an incorrect REST total (BOOT-04; Phase 2 flagship)

**User problem:** An API returns an incorrect amount; debugging should follow actual request execution.

**Explicit prompt:** “Using the Eclipse MCP debugger, reproduce the approved `POST /orders/quote` test request (quantity 2, unitPrice 60, discountPercent 10). Set breakpoints to inspect the controller and service, then identify the first incorrect runtime value and actual response. Do not change source or live data; finish the NORMAL investigation.”

**Natural-language prompt:** “An orders API quote is 120.00 when I expect 108.00. Debug the request in Eclipse and show where the value first goes wrong. Do not edit code.”

**Expected evidence:** Actual HTTP status/body, request-scoped suspended controller/service frames, observed discount input and first incorrect result. **Direct acceptance:** launch chosen correctly, readiness checked, HTTP request triggers breakpoint on the intended debug session, root cause evidenced in locals. **Negative:** unapproved target/side-effectful request, bad URL, wrong port, unknown parameter. **No fabricate:** repository frame only if fixture actually has that layer.

### SB-05 — Trace controller → service → repository, without excessive stepping (BOOT-04)

**User problem:** A wrong value may be introduced in an upstream layer.

**Explicit prompt:** “For the authorized local order lookup request, inspect runtime values at controller, service and repository boundaries. Use targeted breakpoints or step filters to show where the value changes; avoid stepping through Spring internals.”

**Natural-language prompt:** “The API response has the wrong order amount. Determine which application layer first creates the incorrect value.”

**Expected evidence:** At least two real application frames and each relevant boundary value. **Negative:** no repository call in the selected code path must be reported as such, not fabricated. **Efficiency:** bounded stops and no broad object dumps.

### SB-06 — Debug a single Spring Boot service unit test (BOOT-06)

**User problem:** A specific JUnit test fails; full Boot startup is unnecessary for a plain service unit test.

**Explicit prompt:** “Discover and debug only `OrderServiceTest.calculatesDiscount` in Eclipse. Set a breakpoint in `OrderService`, inspect the operands and explain the assertion mismatch without running the full test suite.”

**Natural-language prompt:** “My order service unit test fails on a discount calculation. Debug only the failing test and identify the wrong intermediate value.”

**Expected evidence:** Exact JUnit class/method and actual Java launch delegate; service breakpoint/locals; no unrelated tests launched. **Direct acceptance:** existing Phase 1 JUnit tools work; add a new API only for a proven gap. **Negative:** nonexistent method, ambiguous test declaration and duplicate config; preserve user configs.

### SB-07 — Debug a Spring-context JUnit test (BOOT-06)

**User problem:** Failure occurs only when Spring creates the application context, not in a plain JUnit test.

**Explicit prompt:** “Use Eclipse to debug only `OrderApiIntegrationTest.quoteAppliesDiscount`, which uses `@SpringBootTest`. Confirm the intended test is running under its Spring test context; stop in application code and inspect the first wrong calculation.”

**Natural-language prompt:** “The integration test fails while my plain service test passes. Debug the specific Spring Boot integration test and inspect the actual context-driven request flow.”

**Expected evidence:** Selected JUnit method plus initialized Spring context, relevant injected component/real stack and local values; distinguish context-loading failure from business assertion. **Negative:** missing test dependency, context initialization error, ambiguous class, parameterized selection not supported (report limitation). **Do not** claim a MockMvc test opened a network port.

### SB-08 — Validation and caught exception handling (BOOT-07)

**User problem:** Server returns 400 or a generic error while the throw site/invalid field is hidden.

**Explicit prompt:** “Reproduce the approved invalid-quantity request against the test Boot server. Inspect validation or exception handling using real Eclipse breakpoints, observe the application stack/values and report the actual HTTP status/body.”

**Natural-language prompt:** “My endpoint rejects a request with only a generic error. Find the originating invalid value or exception in the running Spring Boot application.”

**Expected evidence:** Request value, exact selected handler/service path where observable, response from actual server, handled versus uncaught exception behavior. **Negative:** do not mistake framework validation before controller invocation for a controller breakpoint failure; explain if no application line executes.

### SB-09 — Preserve users, consent and target isolation (BOOT-10)

**User problem:** Debugging one Boot service must not interrupt others or mutate real endpoints.

**Explicit prompt:** “Debug only the approved local test service and request using a NORMAL investigation. Do not modify any unrelated launch, user breakpoint or launch configuration; do not send unsafe requests without confirmation.”

**Natural-language prompt:** “I have two Spring Boot services running in Eclipse. Investigate the failing order API without interrupting the other service.”

**Expected evidence:** Before/after launch object identity, user markers, running second service, exact request target and cleanup. **Negative:** repeated launch name, borrowed session, stale investigation ID, wrong environment, missing consent, ambiguous host. **Required:** zero unauthorized changes.

### SB-10 — Plain Eclipse versus Spring Tools (BOOT-11)

**User problem:** Product behavior should not depend on untested STS assumptions.

**Explicit prompt:** “List precisely which Eclipse launch/test operations are available in this installed IDE. Use existing configs and report limitations; don't claim Spring Tools capabilities unless the plugin is installed.”

**Natural-language prompt:** “Can this Eclipse installation debug my Spring Boot application and Spring tests without installing another debugger?”

**Expected evidence:** Actual IDE/bundle state and working Java/Boot/STS launch behavior. **Matrix:** plain Eclipse with JDT/JUnit vs Eclipse with Spring Tools; state prerequisites and differences. **Negative:** absent STS or unavailable JUnit delegate reported honestly.

### Acceptance matrix and release gating

The table lists the minimum evidence for the initial installed Boot milestone. For **full Phase 2 verification**, record a passing direct check, explicit Claude trial, skill-free symptom trial and negative/ownership/cleanup check for each scenario from SB-01 through SB-10 on the identified candidate. Report any partial, failed or unrun check alongside its scenario and keep full verification open.

| Gate | Required evidence for the initial milestone |
|---|---|
| Existing regression | Installed calculator, seven Phase 1 direct scenarios, ownership suite still pass with **no skill** |
| Core Boot | SB-01–SB-04 direct assertions on same installed candidate; SB-04 explicit Claude observed live HTTP and JVM values |
| Request tracing | SB-05 records the controller and service frames and whether the request reached the repository |
| Boot JUnit | SB-06 plain JUnit and SB-07 Spring-context single method observed in real Eclipse debugger |
| Error paths | SB-03 failed startup and SB-08 validation/exception case recorded honestly |
| Safety | SB-09 cross-session safety/consent negative tests pass, actual launch object tracked |
| Environment | SB-10 prerequisite coverage documented (record unavailable IDE combinations as NOT RUN and identify the configuration actually tested) |
| Natural-language baseline | SB-04, SB-06/07, SB-08 run without skill; log mechanism selection, correctness, completeness; repeat before claiming rates |
| Evidence | Commit, candidate artifact/hash, actual report paths, real traces, stop/cleanup state included in release record |

**Phase 2 does not require:** remote attach, live code replacement, real production database access, deep JPA/transactions, a general HTTP client built into MCP, or any skill. These are separate decisions.
