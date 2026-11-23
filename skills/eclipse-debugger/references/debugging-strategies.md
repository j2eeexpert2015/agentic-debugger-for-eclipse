# General debugging strategies (DBG-01 to DBG-04, DBG-06)

This reference contains the **methodology**. It is independent of the Java framework, but debugger operations and permissions remain defined by the Eclipse MCP API.

## A. Symptom → hypothesis → test → evidence → revised hypothesis

1. State the observed failure and expected behavior, noting the exact input, context and reproducibility. Do not treat the reported symptom as proven root cause.
2. Select a hypothesis that is **falsifiable** by one or two concrete JVM observations. Examples: input was already wrong at service entry; wrong numeric conversion occurs inside calculation; a caught exception originates below the controller.
3. Choose the earliest relevant observation point where competing explanations differ. A breakpoint with no clear question is low-value.
4. Observe actual execution and compare against the hypothesis. If falsified, revise the hypothesis; if confirmed, trace backward to where the incorrect input/assumption arose.
5. Stop when the causal chain is sufficiently established for the user's question. If not possible, give the smallest missing fact or next diagnostic action instead of guessing.

## B. Strategic breakpoint selection (DBG-01)

| Condition | Strategy | Avoid |
|---|---|---|
| Short, deterministic path | One line stop before a branch/calculation, then relevant stepping | Breakpoints on every statement |
| One failing record among many | Conditional breakpoint using an in-scope, validated Java condition | Resume hundreds of times |
| Caught exception loses origin | Java exception breakpoint on the exact type, caught/uncaught as necessary | Debugging only the final log message |
| Repeated values needed without stops | Tracepoint **if explicitly authorized**, bounded output and possible JVM side effects acknowledged | Enabling side-effecting evaluation without consent |
| Uncertain location | Start at input/output boundary, narrow toward first divergence | Starting with every possible class |
| Test likely exits quickly | Set stop before starting exact test | Launching and then setting stop after completion |

Validate the Java fully-qualified type name and 1-based source line. After source edits, lines may shift; do not reuse stale line numbers. Do not set or delete user-owned breakpoints.

## C. First-bad-value root-cause analysis (DBG-02)

Look for the earliest observable departure from expected data or control flow:

- **Observe output**: e.g., total 120 rather than expected 108.
- **Inspect its immediate producer**: `total` and `discount`, not unrelated locals.
- **Inspect producer inputs**: `subtotal`, `discountPercent`, computed `rate` and their actual types.
- **Trace the first incorrect transformation**: e.g. Java integer division before conversion to floating point.
- **Trace upstream if needed**: distinguish bad request input from incorrect conversion/configuration. The first wrong value in a method is not automatically the ultimate origin.
- **Explain a causal chain**: observed input → operation/branch → wrong intermediate value → symptom; support each link where evidence exists.

For exceptions: actual throwing frame → method that created invalid state → caller/inputs, or distinguish thrown from translated/handled exception. Do not infer a Spring advice method ran without seeing it.

## D. Intelligent stepping and selective inspection (DBG-03)

- Use `STEP_OVER` to follow transformations without entering known framework/library methods.
- Use `STEP_INTO` for a specific suspect method; avoid traversing infrastructure without a hypothesis.
- Use `STEP_RETURN` to observe what a suspect method returns to its caller.
- Use `RESUME` for the next meaningful breakpoint, not a blind infinite loop.
- For running/hung tasks, `PAUSE` can reveal a momentary stack but **one pause is not proof of a deadlock**. Collect more evidence or explicitly report the limitation.
- Read only relevant locals/frames. Expand an object one level at a time with bounded paging. Sensitive values must not be copied into the final answer or trace files.
- `evaluate_expression` is potentially effectful in this implementation; prefer read-only stack and variables unless consent is explicit.

## E. Efficient iteration and bounded recovery (DBG-04)

Before retrying a failed stop, inspect status and process output: wrong launch? JVM terminated? method never called? wrong line? app server not ready? another session suspended? JUnit method misselected? Retrying an identical action without changing a failed assumption is wasteful.

Prefer 1–3 strategic stops initially, adding another only to resolve a live hypothesis. Use `wait_for_stop` with a bounded timeout (server accepts 1–60 seconds); it reports the stop reason, thread, location and locals in one call. Check state between resumes. Avoid collecting entire collections/logs or repeatedly evaluating an expression.

When evidence is insufficient: write `Observed`, `Inferred`, `Not established`. Explicitly stop rather than assert a root cause not witnessed. Tool-call count is secondary to correctness and safety.

## F. Completing the investigation (DBG-06)

Before ending, answer:

- What failed and under which exact input/context?
- Which JVM launch, thread, frame and values were actually observed?
- What first causal divergence explains the symptom? What remains uncertain?
- Did the debugger or external reproduction alter any state? Was that authorized?
- Was `finish_debug_investigation` successful for the specific ID, without affecting others?

The analysis itself should not silently change code. If user requests a fix, distinguish diagnosis from implementation and ask for authorization only when needed for changes outside the request's granted scope.
