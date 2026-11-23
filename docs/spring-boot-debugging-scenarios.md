# Phase 2 — Spring Boot and JUnit Debugging Scenarios

<!-- JAVA-E2E-19-STATUS:BEGIN -->
**Current evidence (23 November 2026):** The initial Claude symptom-driven trial set has been reviewed: single JUnit diagnosis (final-result record) and two Spring Boot HTTP investigations (event-level runtime PASS). A historical uninterrupted 23/23 direct JDT regression passed; after the fixture HTTP change, first 16 direct scenarios passed together and all seven Boot-gap scenarios later passed separately, but the newest build has **not** completed a fresh uninterrupted 23/23 run. The opt-in Claude harness still reports `REVIEW_REQUIRED`, not automatic acceptance. Repeated no-skill reliability, optional skill A/B, independent cleanup inventories, STS-specific launch modes, and cross-platform coverage remain open. [Current acceptance evidence and boundaries](claude-acceptance-2026-11-23.md). The dated sections below are historical snapshots; their old `NOT RUN` cells refer to the time they were written, not current overall status. See [README](../README.md).
<!-- JAVA-E2E-19-STATUS:END -->


[README](../README.md) · [Development roadmap](development-roadmap.md) · [Feature tracker](feature-roadmap.md) · [Codex implementation backlog](codex-implementation-backlog.md) · [Existing Java/JUnit scenarios](phase1-claude-scenarios.md)

**State on 22 November 2026.** The [tool audit](spring-boot-tool-audit.md) records direct launch and JUnit checks, the direct and explicit Claude HTTP investigation, and the installed JUnit failure and skip results. The four acceptance gates below remain the criteria for full verification. The existing Phase 1 debugger already supports a selected standalone JUnit 5 method; this catalog adds Spring-context and application-level coverage, not a duplicate JUnit framework.


<!-- PHASE2-VERIFICATION-CONSOLIDATION:BEGIN -->
## Local Phase 2 Verification Update (22 November 2026)

**New local direct evidence:** On candidate `1.5.0.20261122084229515`, SB-08 stopped at `OrderService.java:17` and `OrderController.java:27`, returned HTTP 400 with `Quantity must be positive`, and cleaned up. SB-09's tested borrowed-launch, expired-ID and unapproved-POST subset passed with cleanup. These two checks passed sequentially; the full Phase 1 direct suite then passed 7/7. **Explicit Claude and symptom-only Boot gates were not rerun**. The [consolidated four-gate scenario matrix](phase2-verification-consolidation.md#4-scenario-acceptance-matrix--do-not-treat-unrun-trials-as-failures-or-successes) remains authoritative for completion decisions; this catalog's original acceptance requirements stay unchanged.
<!-- PHASE2-VERIFICATION-CONSOLIDATION:END -->

## 1. Evaluation method and fixture policy

Each scenario has **four independent gates**: (1) direct installed-Eclipse/JDT behavior (not Claude), (2) explicit Claude prompt naming objective and mechanism, (3) symptom-only natural-language prompt requiring the agent to select the method, and (4) negative/ownership/cleanup assertions. A prompt alone is not test evidence.

### Installed test fixture

The [standalone Maven fixture](../examples/spring-boot-debugging/README.md) uses Spring Boot 3.5.16, two mains, an in-memory repository, a plain JUnit service test and a real-port Spring-context test.

Ground-truth defects should be **intentional, independent and documented in a verifier-only oracle**, not telegraphed to Claude: e.g. request `POST /orders/quote` for quantity 2, unitPrice 60, discountPercent 10 should return 108.00, but truncating integer division produces 120.00. Another request with invalid quantity should produce a documented HTTP validation response; include one controlled caught-exception path. Use fixed ports chosen by test harness or an explicit ready-port handshake, not a hard-coded port that may conflict. Prevent calls to real external resources; no real email/payment/database writes.

Provide a tiny second Boot project/multiple main example to exercise ambiguity. Provide two unit-test styles: fast pure JUnit `OrderServiceTest` and Spring-context JUnit `OrderApiIntegrationTest` (`@SpringBootTest`); a `@WebMvcTest`/MockMvc test is a separate scenario because it does not represent a real listening HTTP server. At least one test deliberately fails for a known incorrect value. Never let the ordinary Maven root build fail accidentally because an intentionally failing fixture test was unconditionally included.

### Reusable evidence record

For every SB-ID record: test fixture revision, test-specific preconditions/IDE installation, launch config and profile, exact request+expected HTTP behavior, breakpoint type/location, real stack/thread/locals, explicit and symptom-only prompts, Eclipse bridge trace, Claude MCP trace, exit state, user-owned resource count before/after, elapsed time and errors. Track `PASS`, `FAIL`, `PARTIAL`, `NOT RUN` independently for direct/explicit/implicit/negative.

## 2. Phase 2 scenarios and explicit prompts

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

## 3. Acceptance matrix and release gating

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

## 4. Handoff for Codex

Start with [Codex Implementation Backlog](codex-implementation-backlog.md). Before coding, inspect existing Eclipse JDT main discovery/launch, JUnit tools, investigation ownership, bridge operations and Windows verifier. Confirm the working tree and previously recorded candidate identity. Implement one small vertical slice, run actual installed-Eclipse tests, then update this catalog with truthful results. Do not treat this planning document as proof a scenario passed.
