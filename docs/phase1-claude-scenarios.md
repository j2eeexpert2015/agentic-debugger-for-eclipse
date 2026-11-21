# Phase 1 — Claude Prompt Scenarios and Acceptance Tests

[README](../README.md#documentation) · [Eclipse setup and validation](eclipse-installation-and-validation.md) · [Roadmap](feature-roadmap.md) · [Validation](evaluation-plan.md)

## Purpose

Every feature has **three different assessments**:

1. **Direct bridge correctness:** Invoke the real Eclipse bridge without Claude. Confirm actual debugger state, not just response text. Use [`PhaseOneBridgeVerifier.java`](../e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseOneBridgeVerifier.java).
2. **Explicit Claude usage:** Give Claude a prompt naming the MCP feature. Prove it invokes the correct tool and obtains real JVM evidence. Save full MCP transcript.
3. **Implicit Claude selection:** Give Claude a realistic symptom *without naming the feature*. Measure whether it selects an effective debugging strategy. It is not a feature-correctness gate, because model choice varies.

**No skill is required for any test.** Use an otherwise identical configuration if a skill is introduced later. The baseline is intentionally skill-free.

## Prerequisites and reproducibility

- Build a 1.5.0 feature using `mvn clean package` in the repository root, install the generated update-site ZIP into a disposable Eclipse, and run `mvn verify -Pinstalled-e2e` first.
- `dev.bat deploy` imports the fixture automatically. For a manual setup, import [`examples/phase-one-debugging`](../examples/phase-one-debugging) with **File → Import → Maven → Existing Maven Projects** into the same running Eclipse workbench. Wait for Maven classpath resolution and successful Java build.
- This fixture's **JUnit test is intentionally failing**. For packaging it without executing tests use `mvn -DskipTests package` inside its folder. Don't modify or reveal the defect in test prompts.
- Confirm the intended Eclipse owns the selected bridge port (default 8765). Verify `eclipse_ping` and `list_java_applications` in Claude.
- Run the direct bridge checks on this same Windows environment: `mvn verify -Pphase1-bridge`. Reports are saved under `evaluation/reports/` (ignored by Git). If one scenario fails, capture the trace and label the feature unverified.
- Run explicit prompts in **fresh Claude sessions**; reset investigation-owned debugger state between runs. Use the same model and configuration for comparison. Never remove user-owned breakpoints.

## Feature scenarios

### P1-COND — Conditional breakpoints

**Fixture:** `OrderBatchApplication` invokes `OrderCalculator` 1,000 times; only one order receives 10% discount.

**Explicit prompt:**
> Using the Eclipse MCP debugger, set a conditional line breakpoint on the calculation of `discountRate` in `OrderCalculator` with condition `orderId == 1057`. Launch `OrderBatchApplication`, verify the first pause has `orderId=1057` and `discountPercent=10`, then resume. Do not modify code. Finish in Normal mode.

**Implicit prompt:**
> In this order-processing application, order 1057 has the wrong discounted total, but there are hundreds of other orders. Debug it in Eclipse while avoiding irrelevant stops. Do not modify code.

**Direct acceptance:** Marker stores the exact condition, first hit has matching `orderId`, no unrelated suspension before/after that record, other user markers untouched. Negative: invalid condition, duplicate existing breakpoint, no matching type. **Metrics:** first-hit correctness; irrelevant stops; total stepping/resume calls; root-cause accuracy.

### P1-EXC — Exception breakpoints

**Fixture:** `ExceptionApplication` contains an exception that is caught by an application handler, plus an uncaught path available using a program argument.

**Explicit prompt:**
> Using Eclipse MCP, set a Java exception breakpoint for `java.lang.IllegalStateException` with caught=true and uncaught=true. Debug `ExceptionApplication`; show the throwing stack frame and message. Do not modify source. Finish in Normal mode.

**Implicit prompt:**
> This application catches an exception and prints only a message. I need to know the original throwing method and runtime call stack. Debug it using Eclipse.

**Direct acceptance:** Exception breakpoint exists with caught/uncaught flags, stops on throw, stack contains `ExceptionApplication`, cleanup deletes only agent-created marker. Negative: both flags false; duplicate user marker. **Metrics:** first relevant exception observed; stack correctness; protected-marker count.

### P1-JUNIT — JUnit single-test debugging

**Fixture:** `OrderCalculatorTest.appliesTenPercentDiscount` (JUnit Jupiter), intentionally failing assertion.

**Explicit prompt:**
> In Eclipse, discover JUnit tests and debug only `OrderCalculatorTest.appliesTenPercentDiscount`. Set a breakpoint in `OrderCalculator.calculate`, inspect discount operands, and explain why the assertion fails. Do not change the source. Finish in Normal mode.

**Implicit prompt:**
> The discount test is failing. Debug the failing test with Eclipse and use runtime variable evidence to identify the cause. Do not edit files.

**Direct acceptance:** Eclipse discovers the method, launches it through installed JUnit delegate (not ordinary Java main), stops in calculator with relevant values, retains unrelated test configs. Negative: missing JUnit integration, nonexistent test method. **Metrics:** test selection accuracy; correct diagnosis; time to first pause.

### P1-VARS — Nested variable inspection

**Fixture:** `NestedObjectApplication` has nested `Order → Customer → Address` records and an array of items.

**Explicit prompt:**
> Debug `NestedObjectApplication` in Eclipse. Break just before calling `sum(order)` and use `expand_frame_variable` to inspect `order.customer.address` and the item array. Use bounded reads; do not evaluate arbitrary Java expressions.

**Implicit prompt:**
> The displayed total is incorrect. Debug it and trace the amounts in the nested order object without dumping every variable into the conversation.

**Direct acceptance:** Correct nested city `Kolkata`, bounded child pages, no code evaluation. Negative: unknown path, invalid offset/limit, resumed frame. **Metrics:** fields read; requested-vs-returned field ratio; evidence accuracy.

### P1-TRACE — Tracepoints without suspension

**Fixture:** `OrderBatchApplication` repeatedly calls the calculation method.

**Explicit prompt:**
> I authorize a tracepoint in Eclipse that evaluates `"TRACE-ORDER " + orderId` and logs the value without suspending at `OrderCalculator.calculate`. Use `set_tracepoint` with allowSideEffects=true, launch the application and verify recorded output. Do not change source.

**Implicit prompt:**
> I need the order IDs passing through the calculator while the application continues running. Collect runtime observations without stopping on every order. I authorize printing these values to the debugged application's output.

**Direct acceptance:** Tracepoint condition uses JDT non-suspending tracing construct; no breakpoint suspensions; trace output is observable; marker cleaned up. Negative: missing consent, invalid Java expression, collision with existing breakpoint. **Metrics:** traced records; unintended suspensions; emitted log count. **Important:** Eclipse can route evaluation output differently depending on its console configuration; require an installed-IDE observation before marking this passed.

### P1-PAUSE — Pause and restart

**Fixture:** `LongRunningApplication` runs for several seconds without an initial breakpoint.

**Explicit prompt:**
> Start `LongRunningApplication` in Eclipse using a Normal investigation. Pause its main thread, inspect the stack, then call `restart_debug_session` for the investigation-owned launch. Verify the new session is running and finish. Do not alter other sessions.

**Implicit prompt:**
> This Java application seems stuck while running. Pause it in Eclipse, inspect where it is executing, and repeat the run to confirm. Don't affect my other running debugging sessions.

**Direct acceptance:** Pause yields suspended stack; restart replaces the same investigation-owned launch, ownership retained, cleanup terminates replacement only. Negative: attempt restart of reused/user-owned launch is rejected. **Metrics:** pause latency; stack correctness; unauthorized terminations = 0.

### P1-FILTER — Step filtering

**Fixture:** `StepFilterApplication` calls `java.util` APIs from application code.

**Explicit prompt:**
> Debug `StepFilterApplication` with Eclipse. Set step filtering enabled with `java.*,javax.*,sun.*` on this debug target, step into the call on `values.add(10)`, and verify the resulting suspended frame is application code. Do not change global Eclipse preferences.

**Implicit prompt:**
> Trace my application's business logic, but avoid stepping through Java collections and library implementation details. Use Eclipse debugging and show where execution moves.

**Direct acceptance:** Target receives specified filters, flag enabled, stepping stays in application code; no global preference modification. Negative: malformed filter and more than 30 filters. **Metrics:** application-vs-library frames; unnecessary steps; target-filter match.

## Core regression and isolation gates

Run the existing installed-plugin E2E, the dependency-free `DebugInvestigationPolicyTest`, and all seven direct bridge tests **without any skill installed**. Re-run after any skill update/removal. Verify the old `set_breakpoint` schema and existing run/debug/step tools still work unchanged.

## Recording qualitative results

For each prompt preserve: prompt, model/version, skill configuration, fixture commit, Claude transcript, MCP call list and tool results, screenshot or machine evidence of JVM suspension, true root cause, finish state, elapsed wall time, and token/cost information if exposed. Do not turn a single run into a success-rate claim. Repeat identical prompts for agent-selection studies; keep direct bridge tests deterministic.

## Current verification status

Seven fresh Claude Sonnet 5.5 sessions ran the explicit prompts on installed build `1.5.0.20261121184224374`. All seven met their requested observations, and all seven returned completed NORMAL cleanup. The tracepoint prompt verified recorded output for order 1057. The console response was truncated. [Per-feature release record](phase1-implementation-status.md#same-build-explicit-claude-verification)

## Symptom-prompt results, 22 November 2026

One fresh Claude Sonnet 5.5 session per documented implicit prompt ran against installed build 1.5.0.20261121184224374. Each received the project name, numbered relevant fixture source, and NORMAL lifecycle instructions. No prompt named an MCP tool. Only the Eclipse MCP tools were available. All seven investigations returned completed cleanup.

| Scenario | Observed strategy and evidence | Outcome |
|---|---|---|
| Conditional | Broke on order 1057 and observed discountRate become 0.0 after stepping | Complete |
| Exception | Stopped on the caught exception and identified processOrder:15 and main:9 | Complete |
| JUnit | Launched the individual failing test and observed 10 percent become a rate of 0.0 | Complete |
| Variables | Expanded two items and read the accumulated total of 70.0. A consent-denied evaluation attempt was followed by a successful read-only inspection. | Complete |
| Trace | Used a tracepoint and read IDs 1000 through 1283. Remaining IDs were outside the observed output. | Partial coverage |
| Pause/restart | Two pauses in one launch showed the loop counter advancing from 20,600,000 to 31,600,000. Claude did not repeat the run. | Partial |
| Step filters | Applied launch-specific filters and traced main into sum, observing total 30 | Complete |

Six sessions selected the scenario-specific mechanism, counting restart as required for the pause/restart scenario. These are single-run observations with fixture source supplied.
