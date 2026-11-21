# Regression and Skill Evaluation Plan

[Documentation index](../README.md#documentation) · [Capabilities](capabilities.md) · [Roadmap](feature-roadmap.md)

## Baseline results

On Windows candidate `1.5.0.20261121184224374`, all seven direct checks and all seven explicit Claude scenarios passed. In the symptom-only assessment, five of seven answers were complete and two were partial. Each scenario ran once with relevant fixture source. Repeated trials and a stable-skill versus candidate-skill comparison remain future experiments. [Recorded outcomes](phase1-claude-scenarios.md#symptom-prompt-results-22-november-2026)

## Objective and separation of concerns

**Core debugger functionality must not depend on any skill.** A skill is optional, versioned guidance; replacing, disabling or removing it must not change MCP contracts or debugger safety guarantees. The existing E2E runner is a foundation, **not** a completed skill A/B evaluation framework.

**Suite A — deterministic core tests (no skill):** bridge; discovery; launch; breakpoints; execution control; variable/frame inspection; multi-session selection; source navigation; Normal/Controlled ownership and cleanup; error handling; registration; installed binary/p2 deployment. Validate the actual Eclipse state, not just Claude's summary. Extend the runner to cover missing cases. Source navigation needs GUI observation or UI automation beyond current assertions.

**Suite B — behavior evaluation:** hold agent model/version, permissions, tool definitions, test fixture, instructions and temperature settings as stable as possible. Compare (A) no skill, (B) current stable skill, (C) candidate skill. Fresh process/workspace state; no contaminated prior transcript. Capture whether the skill **actually loaded**. Run five independent trials per case for screening; increase sample size for borderline differences. Do not claim statistical significance based on five trials.

## Skill scenarios and scoring

| ID | Scenario and prompt intent | Oracle and observable evidence | Quantitative metric | Qualitative criterion |
|---|---|---|---|---|
| EV-01 | Vague “Debug this application and find the problem” | Real debugger used for runtime-debug request when applicable; breakpoint and suspension in trace | Appropriate debug-use rate | Did not claim to debug without debugging |
| EV-02 | Incorrect discount calculation with no giveaway comments | Observe `discountRate` before/after expression; identify integer division | Root-cause accuracy; relevant tool calls | First erroneous value supported by actual state |
| EV-03 | JUnit-specific failure | **Blocked by JAVA-03 until agent-exposed JUnit debugging exists** | N/A until tool ready | Never claim unavailable capability |
| EV-04 | Specific record fails in loop | First stop at relevant input; record hit count | Breakpoint hits, tool calls, duration | Avoid endless resume loops; conditional tool needed for ideal path |
| EV-05 | Multi-class propagated defect | Identify class/method where incorrect value originates | Correct-origin rate, unnecessary steps | Revises hypotheses based on evidence |
| EV-06 | Two active sessions | Correct `launchName`/investigationId; unaffected user session | Wrong-session operations (target zero) | Names selected target explicitly |
| EV-07 | Recoverable bridge/tool error or never-hit breakpoint | No false success; bounded exit or safe recovery | Recovery success and timeout rate | Accurate diagnosis of failure |
| EV-08 | Normal vs Controlled modes | Preserve/terminate the exact owned resources per mode | Unauthorized mutations (target zero) | Explicit completion report matches Eclipse state |
| EV-09 | “Explain this method” and “review without executing” | No debugger launched against user restriction | Unnecessary launches (target zero) | Answers correctly without needless operations |
| EV-10 | Skill activation and removal | Detect actual skill load and test no-skill fallback | Discovery rate; core pass rate | Skill uninstall changes guidance only, not MCP API |

For every scenario, record **expected application behavior**, defect location/value oracle, prerequisites, unsupported features, attempt logs, and post-run state. EV-03/EV-04 must not be counted as skill failures when required MCP tools are absent.

## Core test identifiers to implement or extend

- `REG-01` connection/authentication; `REG-02` main class discovery; `REG-03` launch/reuse; `REG-04` breakpoints/ownership; `REG-05` suspend/step; `REG-06` stack/values; `REG-07` two sessions/source navigation; `REG-08` Normal cleanup; `REG-09` Controlled preservation; `REG-10` error handling; `REG-11` registration; `REG-12` packaged-vs-loaded binary.
- Future capability tests: `REG-13` conditional breakpoints; `REG-14` exception breakpoints; `REG-15` JUnit launching; `REG-16` nested variable retrieval; `REG-17` logpoints; `REG-18` pause/restart. **Not executable as existing feature tests yet.**

## Metrics and interpretation

Record: correctness (exact bug/origin), runtime evidence (verified MCP outputs), wrong-session/ownership violations, tool call count, breakpoint hit count, elapsed time, tokens/cost when available, skill activation, and final cleanup state. Use a fixed rubric for explanation quality (root cause, evidence, precise corrective suggestion, unsupported claims) scored independently of model self-report.

Do not optimize debugger invocation frequency in isolation. An obvious bug can be fixed via static analysis; extra debugger calls may increase cost without increasing correctness. A skill succeeds only if it improves selected target outcomes **and does not regress safety, accuracy, or non-debug requests**.

## Release gates for a future skill

1. Core suite passes **with skill absent** (non-negotiable).
2. No unauthorized changes to user breakpoints/sessions in any trial (non-negotiable).
3. Candidate improves its prespecified target scenario versus no skill, and does not materially regress versus stable skill (prespecify tolerances before collecting results).
4. Candidate can be removed/replaced without changing the plugin, MCP server or tool schemas.
5. Publish the report with model/tool versions, sample sizes, failures and unknowns; untested items remain **not verified**.

## Proposed artifacts for skill comparisons

`evaluation/reports/<run-id>/manifest.json`, `trial-results.csv`, `tool-trace.jsonl`, `eclipse-state.json`, and `comparison.md`. Do not commit secrets from debugger variables or client configuration.
