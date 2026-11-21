# Feature Roadmap and Decision Tracker

[README](../README.md) · [Phases 0–5](development-roadmap.md) · [Capabilities](capabilities.md) · [External research](research-sources.md) · [Evaluation plan](evaluation-plan.md) · [Phase 1 evidence](phase1-implementation-status.md)

**Repository:** https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse.git  
**Reviewed:** 22 November 2026 (`main` examined through `ff28455`).

This is the **feature-level authoritative tracker**, not a second phase narrative. [development-roadmap.md](development-roadmap.md) specifies sequence and phase exit gates. This document records *what each capability provides, developer benefit, implementation decision, feasibility confidence, specific acceptance evidence, and remaining gaps*. Preserve stable IDs through changes.

## Contents

1. [Status vocabulary and evidence policy](#status-vocabulary-and-evidence-policy)
2. [Core platform — Phase 0](#core-platform--phase-0)
3. [Verified features — Phase 1](#verified-features--phase-1)
4. [Skill candidates — Phase 2](#skill-candidates--phase-2)
5. [Evaluation capabilities — Phase 3](#evaluation-capabilities--phase-3)
6. [Advanced Java candidates — Phase 4](#advanced-java-candidates--phase-4)
7. [Spring Boot candidates — Phase 5](#spring-boot-candidates--phase-5)
8. [Explicit non-goals](#explicit-non-goals)
9. [External source mapping](#external-source-mapping)
10. [Feature record template and change policy](#feature-record-template-and-change-policy)

## Status vocabulary and evidence policy

`Candidate → Investigating → Feasibility verified → Planned → Implemented in source → Tested on installed candidate → Released`.

Alternative states: **Deferred**, **Not planned**, **Rejected**, **Unknown**. “Feasibility verified” requires code or API evidence, not analogy with an IntelliJ/VS Code UI feature. “Tested on installed candidate” is not equivalent to published. “Agent observed the feature” is different from “agent chooses it reliably from symptoms”.

**Tested candidate:** Eclipse bundle `1.5.0.20261121184224374`, per [record](phase1-implementation-status.md). Reported: 7/7 Phase 1 bridge tests, 7/7 explicit Claude prompts, 32/32 ownership tests and installed calculator regression. Separately, 5 complete and 2 partial symptom-only answers from one trial each. These outcomes are recorded evidence, not independently reproduced in this document. Publishing remains pending.

## Core platform — Phase 0

| ID | Capability and actual benefit | Status / evidence | Open gap |
|---|---|---|---|
| CORE-01 | Local authenticated Eclipse–MCP bridge: agent can control a *real* Eclipse debugging session | Installed bridge reachable (`PONG`); auth checks to retain | Multi-instance/port conflict tests maintained |
| CORE-02 | Java main discovery: less need to know package/class in advance | Fixture applications discovered | Ambiguous multi-main selection |
| CORE-03 | Launch/reuse: create or reuse Java launch configurations | Installed regression and ownership scenarios | Specialized launch arguments/profiles use existing configs |
| CORE-04 | Basic line breakpoint CRUD | Live marker create/hit/cleanup; user markers preserved | Maintain duplicate/ownership tests |
| CORE-05 | Execution control: pause, resume, step into/over/out | Calculator stepping validated | All timing/termination edge cases |
| CORE-06 | Runtime inspection: threads, frames, locals | Observed stack and variables | Fast-changing/stale frames, bounded retrieval |
| CORE-07 | Eclipse source navigation | Navigation requested in regression | Visible editor focus with concurrent sessions still requires GUI verification |
| CORE-08 | NORMAL/CONTROLLED investigation lifecycle | 32 ownership/failure checks passed | Verify all newly introduced operations obey ownership |
| CORE-09 | Managed Claude MCP registration | Implemented; integration tests incomplete | Preserve unrelated registrations in installed test |
| CORE-10 | Binary identity / packaging checks | Loaded-versus-built class hashes validated | Bind tested artifact to exact committed source |
| CORE-11 | Windows `dev.bat` deployment/fixture automation | Fresh and repeat Windows deployments recorded as PASS | Maintain clean workspace and failure recovery |

**Regression rule:** The whole platform must remain usable if the skill is absent, disabled or replaced. Core tools and ownership semantics are product-enforced, not skill-enforced.

## Verified features — Phase 1

| ID / test | What the feature actually provides | Developer benefit | Evidence on installed candidate | Remaining limitation |
|---|---|---|---|---|
| JAVA-01 / P1-COND | Java condition attached to a line breakpoint | Stop at `orderId == 1057` instead of every iteration | Condition and matching variables verified by Java test and Claude | Invalid condition may fail only on evaluation at runtime |
| JAVA-02 / P1-EXC | JDT exception breakpoint with caught/uncaught flags | Locate original throw site even if exception is caught | Throwing stack and live message inspected | Type must be selected; duplicate markers protected |
| JAVA-03 / P1-JUNIT | Discover and debug a selected JUnit method | Debug failed test without manually launching full suite | `appliesTenPercentDiscount` launched, operands observed | Parameterized methods and full JUnit Platform discovery not fully covered |
| JAVA-04 / P1-VARS | Read-only nested field and array expansion; bounded page size | Inspect relevant object state without unbounded dumps | Nested address, array fields and values verified | JVM debugging metadata and paging limits apply |
| JAVA-05 / P1-TRACE | Non-suspending tracepoint via JDT evaluation/output | Gather repeated runtime observations without pressing Resume | `TRACE-ORDER 1057` observed | May evaluate expressions with side effects; consent required; output may truncate |
| JAVA-06 / P1-PAUSE | Pause existing running thread; restart **investigation-owned** launch | Investigate hangs and repeat controlled runs | Direct & explicit Claude verification; unauthorized restart denied | Reused/user-owned launch deliberately cannot be restarted |
| JAVA-07 / P1-FILTER | Enable per-target step filters | Avoid stepping into `java.*` implementation | Filter applied; step remained in app code | Rules do not change Eclipse global preferences |

**Detailed source of evidence:** [Phase 1 status](phase1-implementation-status.md) and [Claude scenarios](phase1-claude-scenarios.md). All seven are **tested on installed candidate**, but the source/artifact linkage and public release are not finished. Do not silently upgrade status to Released.

## Skill candidates — Phase 2

| ID | What it adds / why it matters | Feasibility & status | Direct evidence of improvement required |
|---|---|---|---|
| SKILL-01 | Tool-selection guidance: when someone says “Debug this application”, prefer runtime inspection where appropriate; *do not* run debugger for a code explanation | Candidate; no skill implemented; discoverability unknown | EV-01, EV-09, EV-10; skill activation proven; more appropriate runtime investigations without more unnecessary launches |
| SKILL-02 | Investigation playbook: form/revise hypotheses, select targeted stops, trace first bad value, minimize steps and irrelevant variables | Candidate; achievable in guidance in principle; improvement unmeasured | EV-02, EV-04, EV-05; root-cause correctness, extra stops, tool calls and time |
| SKILL-03 | Recovery and lifecycle guidance: stale frame, breakpoint not hit, wrong session, errors and explicit finish | Candidate; some cases may need richer tool errors | EV-06, EV-07, EV-08; fewer abandoned runs; **zero** unauthorized changes |

**Replacement contract:** Skill is independently versioned client-side guidance. Plugin and MCP behavior must not depend on skill files, format, filename, presence or version. Skill removal/upgrade requires CORE and Phase 1 regression recheck. No developer benefit is claimed until controlled comparison confirms it.

## Evaluation capabilities — Phase 3

| ID | Deliverable / benefit | Status | Validation |
|---|---|---|---|
| SKILL-04 | Repeatable no-skill / stable / candidate evaluation runner | **Not implemented**; preliminary Java E2E exists | Fresh runs, equal model/tools/fixture, actual skill activation recorded, cross-run summary |
| EVAL-01 | Fixed prompt + fixture + ground-truth catalog (EV-01–EV-10) | **Documented; execution automation incomplete** | Every feature has a distinct behavioral hypothesis and observable oracle |
| EVAL-02 | Quantitative metrics, qualitative rubric and safety gates | **Designed; automated scoring pending** | Accuracy, evidence, stop count, calls, elapsed time, tokens if available, cleanup; no hidden test reruns |
| EVAL-03 | Skill independence and rollback tests | **Planned** | Disable or replace skill, rerun old MCP schema and installed regression without product code changes |

**Baseline:** Single-trial symptom-only runs had 5 complete / 2 partial. Tracepoint partial because output was truncated; pause/restart partial because agent did not restart. These are concrete improvement candidates, not proofs of the effectiveness of a future skill. Explicit MCP invocation passed all seven. See [evaluation plan](evaluation-plan.md).

## Advanced Java candidates — Phase 4

| ID | Candidate and potential benefit | Decision | Feasibility / test gate |
|---|---|---|---|
| JAVA-08 | Attach to an already-running JDWP JVM (local or remote) | Deferred | **Unknown**; validate Eclipse JDT attach APIs, JVM settings, security and ownership |
| JAVA-09 | Field read/write watchpoints | Deferred | **Unknown**; inspect JDT marker APIs, hit semantics and read/write filters |
| JAVA-11 | Run to line | Candidate for future exploration | **Not investigated**; compare with temporary owned breakpoints and explicit program control |
| JAVA-10A | Hot Code Replace | Deferred | **Unknown**; compatibility and runtime-risk spike required |
| JAVA-10B | Modify variable values during suspension | Deferred; safety-sensitive | **Unknown**; consent, ownership, rollback and data-integrity tests before implementation |

These are not commitments. No implementation should proceed without a practical scenario, API spike, direct test, Claude prompt, and regression assessment.

## Spring Boot candidates — Phase 5

| ID | Proposed capability and concrete value | Status | Feasibility and intended acceptance test |
|---|---|---|---|
| BOOT-01 | Discover Boot main classes and relevant projects | Deferred; not explored | Discover among multiple workspace projects; require unambiguous selection |
| BOOT-02 | Launch Boot respecting active profiles/config/env | Deferred; not explored | Preserve existing launch settings; avoid changing user config |
| BOOT-03 | Detect readiness and startup failure | Deferred; not explored | Wait for actual readiness or explain timeout/errors; no fabricated readiness |
| BOOT-04 | HTTP request reproduction and controller→service tracing | Deferred; not explored | Known test request produces expected suspended app frame and root cause |
| BOOT-05 | Attach to running Boot JVM | Deferred; depends on JAVA-08 | Verify approved JDWP attach and session ownership before use |

Eclipse can debug Boot's JVM via Java tools today, but dedicated automated Spring Boot discovery and reproduction are **not verified product capabilities**. Do not treat this table as an implementation claim.

## Explicit non-goals

| ID | Idea | Decision / reason |
|---|---|---|
| NG-01 | Reimplement JVM debugger | **Not planned**; use Eclipse JDT |
| NG-02 | Bundle an LLM inside plugin | **Not planned**; external MCP agent remains separate |
| NG-03 | Full profiler / heap analyzer | **Not planned in current scope**; different product discipline |
| NG-04 | Non-Java debugging engines | **Not planned in current scope**; Java remains primary scope |

## External source mapping

| Reference | What we are examining | Our adoption decision |
|---|---|---|
| [JetBrains agentic debugging](https://www.jetbrains.com/help/idea/agentic-debugging.html) | Agent guidance plus IDE debugger operations | Study concepts for SKILL-01/02, not copy IntelliJ-specific tool syntax |
| [Microsoft DebugMCP skill](https://github.com/microsoft/DebugMCP/blob/main/skills/debug-live/SKILL.md) | Runtime evidence, investigation patterns, tool selection | Adapt original Eclipse guidance; never delete user-owned markers |
| [Microsoft DebugMCP implementation](https://github.com/microsoft/DebugMCP) | MCP debugger feature design | Compare per capability, not blanket parity claim |
| [Microsoft Java Debug Agent](https://github.com/microsoft/vscode-java-debug/blob/main/bundled/agents/README.md) | Java-specific JUnit and runtime scenarios | Use as ideas for tests; APIs differ |
| [Debug2Fix research](https://www.microsoft.com/en-us/research/publication/debug2fix-debug2fix-supercharging-coding-agents-with-interactive-debugging-capabilities/) | Specialized debug-agent evaluation | Study evidence; do not assume a separate subagent is needed |
| [Debugger evaluation study](https://arxiv.org/abs/2611.08666) | When interactive debugging does not outperform alternatives | Require accuracy, latency and cost comparison, not MCP call count |

See [research-sources.md](research-sources.md) for exact source claims, unknowns and follow-up research.

## Feature record template and change policy

When adding/updating a feature, record: **ID; phase; developer problem; what the tool/skill actually provides; source/reference; current status; feasibility evidence or unknown; permission/ownership risk; explicit and implicit prompts; deterministic test; measured agent behavior; version/commit and artifact hash; known limits; next decision.**

Update status in this tracker **only** after code and evidence support it. Keep phase-level milestone summaries in [development-roadmap.md](development-roadmap.md). Retain history and do not reuse an ID when an idea is dropped. Do not present early research as implemented functionality.
