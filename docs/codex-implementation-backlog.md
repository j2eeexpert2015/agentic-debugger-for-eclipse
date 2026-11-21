# Codex Implementation Backlog — Next Development Work

[README](../README.md) · [Development roadmap](development-roadmap.md) · [Feature tracker](feature-roadmap.md) · [Spring Boot scenario catalog](spring-boot-debugging-scenarios.md) · [Evaluation](evaluation-plan.md) · [Phase 1 results](phase1-implementation-status.md)

**Target repository:** `j2eeexpert2015/agentic-debugger-for-eclipse`; reviewed GitHub `main` commit `11e696624412725e2668fbe074cbf4e098b31055` on 22 November 2026. This is an **ordered task specification for Codex**, not evidence the tasks have been completed. Work against the freshest local tree and recheck Git status and latest remote commit before editing.

## Principles and non-negotiable constraints

- Inspect the working tree, branches, current source and existing tests; **preserve uncommitted work**. Do not reset or overwrite it. Do not push or publish without authorization.
- Preserve existing MCP names/schema where possible, installed Java behavior, ownership and NORMAL/CONTROLLED semantics. Never terminate a user-owned or reused session or delete an unrelated breakpoint.
- Java 21+/Maven/Tycho for product and deterministic tests, Windows batch/PowerShell for local orchestration; no Python dependency.
- Prefer existing JDT/JUnit launch and debugger functionality; introduce a new Boot-specific MCP tool only when a real test exposes a capability gap.
- Keep user consent for expression evaluation, side-effectful HTTP methods and other mutations; distinguish test fixture from production targets.
- Run tests and record output. If Eclipse, a real server or Claude is unavailable, mark exact checks **NOT RUN**, not passed.

## Work packages — perform sequentially

### P1-REL — Phase 1 verification and release hygiene (first)

**Inspect:** `README.md`, `docs/phase1-implementation-status.md`, `docs/capabilities.md`, `docs/phase1-claude-scenarios.md`, `scripts/windows/dev.bat`, `docs/evaluation-plan.md`, installed test results and current revision. **Deliver:** source SHA + bundle version + artifact hash release record, consistent documentation and a deliberate release decision. **Tests:** `dev.bat doctor`, `dev.bat test`, ownership suite on the disposable IDE and final installed candidate as available. Do not claim the old uncommitted candidate is byte-identical to current `main` without comparison.

### P2-01 — Audit existing API support for Boot and JUnit

**Inspect:** Java main discovery and `start_java_application`, saved launch reuse, JDT/STS compatibility, `list_junit_tests` and `start_junit_test`, bridge protocol, MCP tool schemas, trace/process-output APIs and verifier. **Deliver:** a preliminary matrix for BOOT-01/02/03/04/06/07 identifying existing operations, source references, Eclipse prerequisites and questions to test once the P2-02 fixture exists.

### P2-02 — Create safe, deterministic Boot fixture

**Deliver:** `examples/spring-boot-debugging` as a standalone Maven Spring Boot sample with an order endpoint, controller/service/in-memory repository, fixed deterministic discount defect, invalid-quantity error path, two candidate Boot mains/projects or test ambiguity setup, ordinary JUnit service test and `@SpringBootTest` test. Add distinct `@WebMvcTest` example only if useful, clearly labelled MockMvc. Document intended failure and root-cause oracle outside agent prompts. **Tests:** compile and run passing fixture setup/smoke checks, execute intentionally failing checks separately, ensure root package unaffected. Once the fixture builds, run the existing Java and JUnit tools against it in Eclipse. Update the P2-01 matrix with observed behavior and logs before proposing a Boot-specific API. **No real payments/databases/cloud accounts.**

### P2-03 — Boot launch, configuration and readiness

**Deliver:** safe and unambiguous BOOT-01/02 discovery/launch/config selection and BOOT-03 readiness/error determination, reusing existing operations if they pass. Verify profile/env/args and correct installed JDT launch. **Tests:** SB-01/02/03 with valid, wrong, ambiguous and start-failure cases. **Safety:** exact launch object ownership, bounded waits, no unauthorized configuration replacement.

### P2-04 — Real HTTP investigation vertical slice

**Deliver:** approved local/test HTTP request capability through a bounded, auditable mechanism (reuse existing allowed client/Java harness first), real breakpoint hit in controller/service and typed value inspection. **Tests:** SB-04/05 and negative permission/target cases. **Definition of done:** an installed-Eclipse direct test and one explicit Claude prompt show HTTP response, debug stack and first incorrect value; successful NORMAL cleanup. A plain unit test is insufficient.

### P2-05 — Spring-context and ordinary JUnit coverage

**Deliver:** verify and, only when necessary, extend individual method discovery and launch for `OrderServiceTest`, `OrderApiIntegrationTest` and, optionally, a targeted MockMvc test. **Tests:** SB-06/07, missing test, initialization failure and unexecuted-test negative. **Definition of done:** selected test actually ran under the correct JUnit/Spring context and suspended in expected application frame; unrelated tests/configuration untouched.

### P2-06 — Validation, sessions and installed acceptance

**Deliver:** SB-08 real validation/exception trace, SB-09 session/HTTP consent matrix, SB-10 IDE prerequisites. Expand Java-only verifier or separate Java Boot verifier and integrate optional mode into `dev.bat` without changing existing default regressions unless stable. **Tests:** Phase 0/1 existing + Boot direct + explicit Claude + ownership + no-skill symptom baseline. **Definition of done:** Phase 2 installed candidate report with source/hash/versions, negative-case results and the four outcomes for each of SB-01 through SB-10 required by the [full verification criteria](spring-boot-debugging-scenarios.md#3-acceptance-matrix-and-release-gating). Mark unverified environments openly.

### P3-01 — Independent unified debugging skill

**Prerequisite:** Phase 2 minimal vertical slice verified, no-skill baseline recorded. **Deliver:** optional original `SKILL.md` guidance for common Java investigation, JUnit target selection and Boot request-driven debugging; optional short context-specific reference files. **Tests:** skill discovery and actual activation, disable/remove, unchanged MCP operations, no unauthorized debugging, actual evidence and cleanup. **Do not** embed skill instructions into plugin or MCP correctness logic.

### P4-01 — Controlled no-skill/stable/candidate skill evaluator

**Deliver:** reproducible runner and reports for EV-01–EV-16 with model/fixture/permission/tool consistency, fresh sessions, actual skill-loaded signal, artifact identification, ratings and safety criteria. **Tests:** multiple independent trials per condition and predeclared scoring. A stable cohort does not exist until a skill has actually been accepted. **Do not** confuse seven historical single-run Java tests with a controlled trial.

### P5-TRIAGE — Advanced features (later, not automatically included)

**Candidate investigation only:** JAVA-08/BOOT-05 remote attach, JAVA-09 watchpoints, JAVA-11 run-to-line, JAVA-10A HCR, JAVA-10B variable mutation, BOOT-08 JPA/transactions and BOOT-09 request concurrency. Each requires developer value, JDT API proof, independent fixture, consent model and regression gate before implementation.

## Suggested Codex execution prompt

> Continue Agentic Debugger for Eclipse IDE in this local repository. Read `README.md`, `docs/development-roadmap.md`, `docs/feature-roadmap.md`, `docs/spring-boot-debugging-scenarios.md`, `docs/evaluation-plan.md`, `docs/codex-implementation-backlog.md` and `docs/phase1-implementation-status.md`. First inspect Git status and current code; preserve all uncommitted changes. Follow P1-REL then Phase 2 packages in order. Before creating Boot-specific APIs, prove whether existing Java and JUnit tools can satisfy each scenario. Implement narrowly, run installed Eclipse tests when available, record PASS/FAIL/NOT RUN separately, keep user-owned launches/breakpoints/configs safe, and avoid unauthorized requests or side effects. Use Java/Maven and batch/PowerShell, no Python. Do not publish or push without approval.

## Progress convention

For each work package record: **status** (NOT STARTED / IN PROGRESS / IMPLEMENTED / DIRECT TESTED / EXPLICIT CLAUDE TESTED / VERIFIED / BLOCKED), issue/commit and tested artifact, changed files, command(s), actual output/report path, known limits and next action. Do not mark tasks complete on a design description alone.
