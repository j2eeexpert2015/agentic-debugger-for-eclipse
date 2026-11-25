---
name: eclipse-debugger
description: Investigates Java, Spring Boot and JUnit 5 problems at runtime with the Agentic Debugger for Eclipse IDE (the eclipse-debugger MCP server). Use when the user asks to debug, and whenever the user reports a bug or symptom in a Java project open in Eclipse even without asking to debug - a wrong, unexpected or null value, total or result; a failing or erroring test; an exception, crash or wrong HTTP status; unexpected output; something that "seems broken" or "does not work" - or asks why something happens or what is going wrong. Observes the real values with breakpoints instead of guessing from the source or adding print statements or logging. Not for code explanations, reviews or generation with no problem to investigate.
argument-hint: "[debugging problem or failing test]"
---

# Eclipse debugger — skill v0.1.4 (candidate)

Use the existing `eclipse-debugger` MCP server to investigate **real JVM execution**, not just source-code symptoms. This skill contains guidance; it does not add tools or change the Eclipse plugin. It applies to Java, Spring Boot and JUnit 5; it is not a general Eclipse/CDT debugger.

## Activation and boundaries

- Respond to the user's actual request. If the user merely asks to explain or review code, do not launch or alter a debug session. If runtime evidence is requested, plan a focused investigation.
- Do not invent observations. Distinguish source reasoning, actual debugger evidence, and unverified hypotheses.
- Verify `eclipse_ping` before relying on Eclipse. If `eclipse-debugger` tools are absent or the bridge is unreachable, explain the block; do **not** claim a successful runtime diagnosis.
- Identify the exact Eclipse project and execution target from discovered candidates. If multiple plausible targets remain, ask for a choice rather than guess.
- Read `references/debugging-strategies.md` for diagnostic strategy; load **only** the relevant `java-debugging.md`, `spring-boot-debugging.md` or `junit-debugging.md` for the request; read `references/eclipse-mcp-tools.md` when exact tool names/parameters or capability limits are needed.

## Investigation contract

1. **Define the problem.** Record actual symptom, expected behavior, reproducible inputs and a falsifiable hypothesis. For a generic performance or concurrency complaint, recognize that the current MCP tools may be insufficient for a full diagnosis.
2. **Select the exact target.** Discover mains, saved launch configurations or JUnit tests. Inspect existing debug sessions and breakpoints before changing state if they might affect the investigation. Respect existing JVM args, Spring profiles and environment settings; do not create an inferior default config over an applicable saved config.
3. **Begin** with `begin_debug_investigation` in `NORMAL` mode (default). Choose `CONTROLLED` only if the user explicitly asks to leave the debugging session/breakpoints for inspection. Pass `request`: the user's request in their own words (verbatim when it is short); it opens the debugging session report. Pass `skill`: `eclipse-debugger 0.1.4` (this skill and its version). Always pass `model`: your exact model ID as your system instructions state it (for example `claude-sonnet-5-5`). Pass `effort` (your effort or thinking level) only if your instructions state it. The report and the Agent Activity view show these as what ran the investigation. Keep the returned `investigationId`; pass it to supported breakpoint/launch/control operations.
4. **Choose one or a few precise stops.** Prefer a line breakpoint for straightforward flows, a conditional breakpoint for record-specific failures, or an exception breakpoint when throw origin is hidden. Put breakpoints **before** launching a short-lived main or test. Avoid tracepoints unless the user explicitly authorizes their potentially side-effecting evaluation. Give each breakpoint, evaluation and step a short `purpose` (one sentence: why this stop or call helps); the report shows it at that place, as your statement.
5. **Launch or inspect** only the selected target using Eclipse. An HTTP request is an additional action requiring a known, explicitly authorized target and method; the Eclipse MCP server does **not** itself provide a general HTTP request tool.
6. **Observe, then refine.** After launching, resuming or stepping, call `wait_for_stop` with a bounded timeout. One call returns the stop reason (breakpoint, exception, step end, pause), the exact thread, the top frames and the locals, or `TERMINATED`/`TIMEOUT`; call `get_stack_frames`/`get_frame_variables` only when you need deeper frames or other threads. Compare values at caller/callee boundaries to find the **first causal divergence**, not only the final wrong result. Use Step Into/Over/Return only to test a specific hypothesis; do not churn through hundreds of stops.
7. **Recover once with a reason.** If a breakpoint does not hit, check launch identity, JVM readiness/termination, breakpoint location, selected request/test and process output. Correct a documented cause; don't repeat the same action indefinitely. For unavailable features, state the limitation.
8. **Finish safely.** Call `finish_debug_investigation` for the precise investigation ID, including when the investigation is incomplete. Pass `summary`: your **complete analysis in Markdown**, with the same findings you will report to the user in step 9 (a to d and f): reproduced symptom, runtime evidence with frames and values, causal chain or root cause (or what remains uncertain), reproduction context and the fix suggestion. It is shown in full at the top of the debugging session report, so do not shorten it to a sentence; the cleanup result is added to the report by the server. Also pass the short structured fields the report places in its program flow (each optional, never instead of `summary`): `title` (one line naming the bug), `trackedValue` (`name`, `expected`, `actual` of the value you followed), `findings` (one `{at, text}` per stop you used, `at` as `Class.method:line` exactly as the stop showed it), `rootCause` (`file`, `lines`, `explanation`; the tool reads those lines from the project and shows them), `fix` (not applied), `notObserved` (what your conclusion relies on that you did not see in the debugger) and `assumedPath` (the call path from the request to the first stop if you did not step it). State only what you saw or clearly mark it in `notObserved`: the report checks `actual` against the recorded values and marks every field as yours. In `NORMAL`, cleanup must affect only investigation-owned resources. Never `STOP`, restart, resume, or remove resources belonging to another user's session. If cleanup fails, report this explicitly rather than claiming success.
9. **Report** (a) reproduced symptom, (b) runtime evidence with frame/values, (c) causal chain/root cause or uncertainty, (d) reproduction context, (e) cleanup state, and (f) optional fix suggestion **without editing code unless separately authorized**. The finish reply already confirms the cleanup (`cleanup`, `ownBreakpointsLeft`) and, when reports are on, names the report file (`reportFile`): give the user that full path, and do not call `list_breakpoints`, `list_debug_sessions` or `get_investigation_report` again to check. Call `get_investigation_report` only if the reply has no `reportFile` and reports were not reported off; when reports are off, say nothing about reports. If the reply has a `warning` or `ownBreakpointsLeft` above 0, report that cleanup was incomplete.

## Before you conclude

- Did you **see** the wrong value in the debugger (a stop, a variable), not only in the source or in a test result?
- Is this the **first** place where the value goes wrong, not just where the failure shows?
- Can you explain the chain from that place to the symptom?
- Did you call `finish_debug_investigation` for your investigation?

If any answer is no, observe more or say clearly what was not observed.

## Tool-choice quick rules

| Symptom / objective | Preferred operation or strategy |
|---|---|
| One record/loop iteration fails | `set_conditional_breakpoint` on a validated Java expression |
| Caught or uncaught exception origin is unclear | `set_exception_breakpoint` with correct `caught`/`uncaught` flags |
| Determine which method is entered | `control_debug_session`: `STEP_INTO` |
| Advance through current method without entering call | `control_debug_session`: `STEP_OVER` |
| Return to the caller | `control_debug_session`: `STEP_RETURN` (**not** `STEP_OUT`) |
| Continue to a different stop | `control_debug_session`: `RESUME`; use bounded wait and inspect stop reason |
| Unexpected nested object/array | `get_frame_variables`, then bounded `expand_frame_variable` |
| Long-running target needs interruption | `control_debug_session`: `PAUSE` only on verified authorized target |
| Need repeated non-suspending runtime observations | `set_tracepoint` **only with explicit user consent** and `allowSideEffects=true` |
| One failing JUnit test | `list_junit_tests`; breakpoint where the wrong value is produced; `start_junit_test` for the exact method (debug mode); when the run ends, `wait_for_stop` returns the test result (expected, actual, location) |
| Failing test or failure not known yet | `start_junit_test` with `mode=run` (whole class if needed), then `get_junit_results`; then debug the failed method in the same investigation |
| Boot HTTP failure | Verify selected Boot main/config and readiness, set app breakpoint, then reproduce only an authorized request |

## Mandatory safety deviations from other debugger skills

- **Never clear all breakpoints.** Our tool has scoped ownership and `finish_debug_investigation`; preserve every unrelated marker.
- `evaluate_expression` is **not** a read-only alternative to variables: Java evaluation may run code. Use it only with explicit consent and `allowSideEffects=true`.
- `set_tracepoint` also requires explicit authorization for evaluating/printing an expression; do not fabricate authorization from a general 'debug this' request.
- An arbitrary local HTTP `POST`, database write, fixture reset or source edit is **not** implicitly authorized by 'debug this'. Confirm environment, destination and intended request before using any external HTTP client.
- No remote attach, field watchpoint, hot code replace, runtime mutation, or run-to-line MCP operation is established in the current product. Do not promise one.

## Evidence quality and stopping rule

Stop when you can explain the root cause through actual observations, or when further progress needs unsupported tooling, missing authorization, absent test setup or user clarification. Avoid unnecessary data dumps; do not expose secrets surfaced in variables or process output. One concise, grounded diagnosis is better than a long speculative trace.
