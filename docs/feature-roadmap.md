# Feature Roadmap and Decision Tracker

[Documentation index](../README.md#documentation) · [Implemented capabilities](capabilities.md) · [Research](research-sources.md) · [Evaluation](evaluation-plan.md)

## Lifecycle and terminology

`Candidate → Investigating → Feasibility verified → Planned → Implemented in source → E2E verified`.

Possible alternative decisions: `Deferred`, `Not planned`, `Rejected`. **Feasibility verified** requires a code/API spike, not an assumption that an Eclipse UI feature is programmatically exposed. Each feature keeps a stable ID and points to an evidence source plus a test scenario. Do not mark implemented on documentation changes alone.

## Prioritized candidates

| ID | Feature and concrete benefit | Decision | Feasibility | Test required | Research |
|---|---|---|---|---|---|
| SKILL-01 | Optional investigation skill: encourage runtime evidence for requests explicitly asking to debug, without forcing unnecessary debug launches | Candidate, highest priority experiment | Plausible; activation needs verification | EV-01, EV-09, EV-10 | RS-01, RS-02, RS-03 |
| SKILL-02 | Hypothesis-guided investigation: select breakpoints and identify first incorrect value; reduce wasted stepping | Candidate | Plausible with current tools for basic cases | EV-02, EV-05 | RS-01, RS-02, RS-04 |
| SKILL-03 | Error recovery and timeout guidance: avoid endless waits or mistaken success | Candidate | Partial; may need additional MCP error detail | EV-07 | RS-01, RS-07 |
| SKILL-04 | Repeatable skill A/B suite: measure no skill vs stable vs candidate without changing core functionality | Planned documentation; runner implementation pending | Feasible in principle; runner integration unverified | EV-01–EV-10 | RS-05, RS-06 |
| JAVA-01 | Conditional breakpoints: stop only for targeted input | **Implemented in 1.5 candidate source; not E2E verified** | JDT condition API reviewed; live behavior pending | P1-COND | RS-01, RS-03 |
| JAVA-02 | Exception breakpoints: stop at caught/uncaught throw sites | **Implemented in 1.5 candidate source; not E2E verified** | JDT exception API reviewed; runtime pending | P1-EXC | RS-02, RS-03 |
| JAVA-03 | Debug JUnit test directly using test launch config | **Implemented in 1.5 candidate source; not E2E verified** | Eclipse JUnit launch API examined; runner compatibility pending | P1-JUNIT | RS-04 |
| JAVA-04 | Bounded nested variable browsing and array navigation | **Implemented in 1.5 candidate source; not E2E verified** | Eclipse IValue traversal implemented; runtime pending | P1-VARS | RS-01, RS-02 |
| JAVA-05 | Non-suspending tracepoints using JDT conditions | **Implemented in 1.5 candidate source; not E2E verified** | Official JDT tracing pattern used; console behavior pending | P1-TRACE | RS-02, RS-03 |
| JAVA-06 | Pause and owned-session restart | **Implemented in 1.5 candidate source; not E2E verified** | Existing pause plus new ownership-checked restart | P1-PAUSE | RS-03 |
| JAVA-07 | Per-target step filtering | **Implemented in 1.5 candidate source; not E2E verified** | Eclipse JDT target filtering API reviewed | P1-FILTER | RS-03 |
| JAVA-08 | JVM attach, remote debug | Deferred | Unknown; requires safe attach and launch settings | Design test when investigated | RS-03 |
| JAVA-09 | Field watchpoints | Deferred | Unknown; JDT may have facilities, not verified for MCP | Design test when investigated | RS-03 |
| JAVA-10 | Variable mutation and Hot Code Replace | Deferred; safety-sensitive | Unknown | Permission and rollback test first | RS-03 |
| BOOT-01 | Spring Boot-aware launch, profile, readiness and HTTP reproduction | Deferred until core Java skill evaluation | **Not explored in this snapshot** | Independent Boot fixtures required | — |

## Explicit non-goals for the current phase

| ID | Idea | Decision | Reason |
|---|---|---|---|
| NG-01 | Rewrite Java debugging engine | Not planned | Reuse Eclipse JDT debug engine rather than building competing JVM debugger |
| NG-02 | Embed an LLM/model inside the Eclipse plugin | Not planned | Keep external agent and MCP boundary; avoid dependency lock-in |
| NG-03 | Full profiler/heap analyzer | Not planned now | Different product scope; integration may be considered later |
| NG-04 | Broad non-Java debugger support | Not planned now | Focus on Java reliability and validation first |

## Feature card template

For each new candidate, add a subsection with: **ID; source; exact problem; developer-visible behavior; existing implementation; feasibility evidence or “unknown”; prerequisites; expected benefit; quantitative metric; qualitative criteria; regression risks; test ID; outcome; decision; version verified**.

A source suggestion does not make a feature planned. A planned feature does not become implemented until source changes and tests exist. A passed unit test does not establish an installed-plugin E2E result.


## Phase 1 candidate (source implementation, awaiting live release checks)

The seven requested operations are present in the 1.5.0 candidate source package; they are **not marked released or installed-E2E verified**. Track the explicit feature acceptance tests and negative cases in [Phase 1 Claude scenarios](phase1-claude-scenarios.md), and the validation status in [Phase 1 implementation status](phase1-implementation-status.md). The debugging skill remains independently replaceable and has not been implemented in Phase 1.
