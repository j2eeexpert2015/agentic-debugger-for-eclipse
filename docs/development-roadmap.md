# Development Roadmap — Phases 0–5

[README](../README.md) · [Feature-level tracker](feature-roadmap.md) · [Verified capabilities](capabilities.md) · [Test evidence](phase1-implementation-status.md) · [Evaluation methodology](evaluation-plan.md)

**Repository:** https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse.git  
**Status reviewed:** 22 November 2026, GitHub `main` (latest examined commit `ff28455bcdd8be96901d0629533da6fb61d18843`).  
**Purpose:** Define the *order of work, phase-level outcomes, dependencies, and release gates*. Individual feature decisions and feasibility belong in [feature-roadmap.md](feature-roadmap.md). This is not a claim of public release.

## Contents

1. [Roadmap overview](#roadmap-overview)
2. [Phase 0 — Core Eclipse MCP debugger](#phase-0--core-eclipse-mcp-debugger)
3. [Phase 1 — Advanced core Java debugging](#phase-1--advanced-core-java-debugging)
4. [Phase 2 — Optional agentic debugging skill](#phase-2--optional-agentic-debugging-skill)
5. [Phase 3 — Skill evaluation and refinement](#phase-3--skill-evaluation-and-refinement)
6. [Phase 4 — Advanced Java debugging](#phase-4--advanced-java-debugging)
7. [Phase 5 — Spring Boot debugging](#phase-5--spring-boot-debugging)
8. [Cross-phase engineering rules](#cross-phase-engineering-rules)
9. [Immediate work queue](#immediate-work-queue)

## Roadmap overview

| Phase | Objective | Current state | Exit / next decision |
|---|---|---|---|
| 0 | Eclipse JDT ↔ authenticated bridge ↔ Java MCP server; discovery, debugging, ownership | **Implemented; essential installed regression verified** | Maintain regression and security guarantees |
| 1 | Conditional and exception breakpoints, JUnit, nested inspection, tracepoints, pause/restart, step filters | **Seven features tested on installed Windows candidate** | Record exact tested source/artifact, resolve documentation drift, decide publication |
| 2 | Separate, replaceable skill teaching agents when and how to debug | **Not implemented; research and scenarios documented** | Demonstrate benefit without coupling skill to product |
| 3 | Repeatable comparison: no skill / stable skill / candidate skill | **Evaluation design exists; runner and replicated trials pending** | Quantify uplift, cost, safety, and non-regression |
| 4 | Watchpoints, remote attach, run-to-line, HCR, controlled mutation | **Deferred; feature feasibility not established** | Investigate and prioritize individually, not automatically commit to all |
| 5 | Spring Boot discovery, profile-aware launch, readiness, HTTP reproduction | **Deferred; specific feasibility work not begun** | Design Spring Boot fixture, integration and acceptance tests |

**Interpretation:** A feature being present in source, a tool being callable, a successful installed-Eclipse test, and a public release are different milestones. Only the documented tests on the identified installed candidate are claimed here. See [test evidence](phase1-implementation-status.md).

## Phase 0 — Core Eclipse MCP debugger

**Goal:** Enable an external AI client such as Claude Code to control the *real Eclipse Java debugger*, not a custom JVM debug engine.

**Delivered:** Java/OSGi Eclipse plugin and JDT debugger integration; authenticated loopback bridge; standalone Java MCP server; managed Claude MCP registration; discovery and launch of Java `main` applications; line breakpoints; suspend/resume/step; threads, stack, locals; source navigation; NORMAL/CONTROLLED investigation lifecycle; ownership-aware cleanup; installed binary comparison; installed Claude calculator E2E. Core functionality operates without an optional skill.

**Acceptance:** Installed plugin corresponds to compiled/packaged classes; Claude debugger trace shows actual suspension and `sum=10` after stepping; user-owned breakpoints and reused launches remain untouched. **Remaining coverage:** GUI-level visible source selection with simultaneous sessions, registration preservation under all cases, and future security regression.

**Detailed evidence:** [capabilities.md](capabilities.md), [phase1-implementation-status.md](phase1-implementation-status.md).

## Phase 1 — Advanced core Java debugging

**Goal:** Expose the important Eclipse debugging techniques through stable MCP operations, with independent plugin tests and explicit Claude invocation tests.

| Feature ID | Delivered capability | Core evidence on installed candidate |
|---|---|---|
| JAVA-01 / P1-COND | Conditional line breakpoints, e.g. `orderId == 1057` | Suspended on order 1057 and inspected its discount |
| JAVA-02 / P1-EXC | Caught/uncaught exception breakpoints | Observed throwing frame and live `detailMessage` |
| JAVA-03 / P1-JUNIT | Discover and debug one JUnit test | Launched `appliesTenPercentDiscount` in debug mode |
| JAVA-04 / P1-VARS | Bounded nested field/array expansion | Inspected nested address and item values |
| JAVA-05 / P1-TRACE | Authorized non-suspending tracepoints | Saw `TRACE-ORDER 1057`; full output still limited |
| JAVA-06 / P1-PAUSE | Pause and restart an investigation-owned JVM | Verified pause, stack, replacement run |
| JAVA-07 / P1-FILTER | Target-specific Java step filters | Verified application-level stepping |

**Verification recorded:** Candidate `1.5.0.20261121184224374` passed the installed calculator regression, 7/7 direct bridge scenarios, 7/7 explicit Claude scenarios, and 32/32 ownership/failure checks. Fresh-workspace setup and repeat deployment succeeded on Windows. In **single-trial** implicit prompts, 5/7 were complete and 2/7 partial; these are not statistically reliable success rates. The tests are documented in [Phase 1 status](phase1-implementation-status.md) and [Claude scenarios](phase1-claude-scenarios.md).

**Still needed to close the phase:**

1. Ensure *committed* source uniquely identifies the tested candidate, including local modifications; retain version and artifact digest.
2. Run candidate verification again if the binary changes; make a deliberate release decision, rather than saying the ZIP is already published.
3. Resolve stale roadmap and README sentences that contradict installed test results.
4. Track follow-ups separately: post-exit process output, parameterized JUnit, multiple-session editor focus, and managed-registration behavior.

**Automated Windows entry point:** `scripts\windows\dev.bat` (full deploy and test). `dev.bat doctor`, `dev.bat deploy`, `dev.bat test`, and individual scenarios are also available. PowerShell is used for Windows orchestration; debugger and tests are Java. A skill is not required.

## Phase 2 — Optional agentic debugging skill

**Goal:** Improve the agent's *decisions*, not the set of debugger commands. Implement an original Eclipse-specific, independently replaceable skill. Study [JetBrains `ij-debugger`](https://www.jetbrains.com/help/idea/agentic-debugging.html), [Microsoft DebugMCP `debug-live`](https://github.com/microsoft/DebugMCP/blob/main/skills/debug-live/SKILL.md), [Microsoft Java Debug Agent](https://github.com/microsoft/vscode-java-debug/blob/main/bundled/agents/README.md) and relevant research, without automatically copying their text or assuming IDE/tool parity.

**Candidate behaviors:**

- Recognize explicit runtime-debugging intent while avoiding gratuitous debugger launches for explanation-only tasks.
- Choose conditional, exception, JUnit, tracepoint, pause/restart, variable expansion or step filters according to the observed problem.
- Form a hypothesis, gather runtime evidence, revise when contradicted, locate the *first* incorrect value and explain the cause.
- Minimize unnecessary stops, repeated stepping, broad variable dumps and context usage.
- Recover from stale frames, unavailable sessions, unhit breakpoints and timeout conditions without false success.
- Respect consent, project/session ownership, and NORMAL/CONTROLLED cleanup rules.

**Required architecture:** `SKILL.md` is optional client-side guidance, versioned independently; no MCP contract, correctness invariant, permissions policy or product logic depends on it. It can be replaced, disabled or removed without recompiling the Eclipse plugin.

**Phase exit:** A demonstrated improvement on pre-registered target scenarios, with no safety regressions or meaningful loss on non-debug requests. **Current state:** not implemented.

**Feature IDs:** SKILL-01–03. Detailed scenarios: [evaluation-plan.md](evaluation-plan.md).

## Phase 3 — Skill evaluation and refinement

**Goal:** Provide an objective basis for adopting, changing, or rejecting the skill.

**Conditions:** A = no skill; B = current stable skill (once one exists); C = candidate skill. Use matching model/version, permissions, MCP tools, fixtures, prompts and fresh sessions. Capture whether the skill *actually activated*, not just whether its file was installed.

**Two independently enforced suites:**

1. **Deterministic product regression (skill absent):** bridge, debug operations, installed binary, launch ownership, NORMAL/CONTROLLED, seven Phase 1 features, errors and cleanup. Failures here are product defects, not skill scores.
2. **Agent behavior trials:** explicit and implicit prompts, negative prompts, repeated runs, correct diagnosis backed by actual JVM evidence, tool-selection effectiveness, steps/hits, time, token usage when available, forbidden actions, cleanup correctness.

**Minimum candidate evaluation set:** EV-01 vague debug request; EV-02 incorrect discount; EV-03 failing JUnit; EV-04 single record within loop; EV-05 multi-class defect; EV-06 concurrent sessions; EV-07 recoverable failures; EV-08 NORMAL/CONTROLLED; EV-09 explanation-only; EV-10 load/uninstall/replace skill. Expand only when a new behavior needs a distinct oracle. Start with five independent trials per condition for screening; larger samples may be required. A single successful run does not prove improvement.

**Must-pass invariants:** Zero unauthorized changes to user-owned breakpoints/sessions; no skill dependency for basic debugging; truthful reports about what was actually inspected; no regression in non-debug requests. Keep predetermined pass criteria and complete traces.

**Current state:** The Java E2E runner and documented single-trial no-skill results exist. The reusable A/B runner, replicated trials, skill activation checks and scored comparison reports have not been built.

## Phase 4 — Advanced Java debugging

**Goal:** Consider additional *product* capabilities only when real developer situations and Eclipse API feasibility justify them. These are not Phase 2 skill features.

| Candidate | Benefit | Current decision |
|---|---|---|
| JAVA-08 — JVM attach / remote debug | Inspect an existing JDWP-enabled JVM without relaunch | Deferred; feasibility not established |
| JAVA-09 — Field access/modification watchpoints | Stop when a critical object field is read or written | Deferred; feasibility not established |
| JAVA-11 — Run to line | Resume toward a particular source location | Candidate for later assessment; no implementation claim |
| JAVA-10A — Hot Code Replace | Apply supported class changes during an active debug session | Deferred; compatibility research required |
| JAVA-10B — Runtime variable modification | Test a hypothesis by altering suspended state | Deferred; explicit permission and safety evaluation required |

**Entry gate:** A source-backed Eclipse/JDT spike, explicit user benefit, independent fixture, direct bridge test, explicit Claude prompt, implicit-selection prompt, ownership tests and a non-regression plan. **No commitment** to implement all candidates.

## Phase 5 — Spring Boot debugging

**Goal:** Move from ordinary Java debugger control to reliable Spring Boot workflows without duplicating the Eclipse debugger or Spring Tools.

**Proposed capabilities (none yet verified as Boot-specific MCP features):**

- Identify the right Boot project and entry point; reuse existing Spring Tools or Java launch configuration when appropriate.
- Respect profiles, VM arguments, environment variables, application arguments, dependent services and workspace configuration.
- Confirm application readiness; capture startup failure honestly.
- Reproduce a reported REST defect using a specified endpoint/request and trace controller → service → repository where observable.
- Assess attaching to a running JDWP-enabled Boot JVM via the separate remote-attach feature.
- Provide explicit and natural-language agent tests, negative cases and safe cleanup.

**Example acceptance:** For a deliberately defective order endpoint, the agent boots with the correct profile, issues the known failing request (with appropriate permission and test data), suspends in relevant application frames, identifies the first incorrect runtime value and preserves unrelated sessions. No hard-coded endpoint or inferred profile is allowed.

**Current state:** Requirements-level planning only; Spring Boot-specific discovery/launch/readiness/reproduction remain unimplemented and unverified.

## Cross-phase engineering rules

- Preserve stable MCP operations; add capabilities without silently changing existing tool schemas or ownership semantics.
- For each new feature require a real scenario, direct Java/Eclipse acceptance test, explicit Claude prompt, and natural-language tool-selection measurement. Measure *what actually happened* via MCP traces and Eclipse JVM state.
- Keep user-created resources safe; forbid unauthorized debugging mutations and require consent for evaluation/side effects.
- Keep an optional skill independent of the plugin, and rerun core tests with it uninstalled.
- Track implementation, test evidence, candidate artifact, and public release as distinct statuses.
- Use Java/Maven for implementation/test infrastructure and Windows batch/PowerShell for environment orchestration; do not introduce Python just for verification.
- Maintain concise README and detailed docs; log research and decisions in their existing files.

## Immediate work queue

| Priority | Deliverable | Definition of done |
|---|---|---|
| 1 | Phase 1 source-to-binary release audit | Tested source revision and candidate artifact uniquely identified; no unreviewed edits |
| 2 | Documentation consistency | README, capability inventory, feature tracker and evaluation plan agree on seven verified features and remaining limits |
| 3 | Baseline for Phase 2 | Repeated no-skill trials; pre-registered scoring and fixture resets |
| 4 | Minimal replaceable skill | Skill loads/activates correctly, can be removed, preserves MCP and safety invariants |
| 5 | Phase 3 A/B runner | Produces comparable traces, metrics and no-regression decision for each skill version |
| 6 | Phase 4/5 proposals | Only after feature-specific feasibility and value reviews |

For granular ownership of each feature, see [Feature Roadmap and Decision Tracker](feature-roadmap.md).
