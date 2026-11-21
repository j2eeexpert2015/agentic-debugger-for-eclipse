# Phase 1 — Implementation Status and Release Checklist

[README](../README.md#documentation) · [Eclipse setup and validation](eclipse-installation-and-validation.md) · [Prompt tests](phase1-claude-scenarios.md) · [Capabilities](capabilities.md)

This document separates **source present** from **verified in an installed Eclipse**. Reference baseline: v1.4.0. Candidate: v1.5.0.

| ID | Capability added in source | MCP tool(s) | Verification state | Caveats |
|---|---|---|---|---|
| P1-COND | JDT line-breakpoint condition and evaluation policy | `set_conditional_breakpoint`; existing `list_breakpoints` includes condition | Not live verified | Conditional expression errors occur at runtime; duplicate markers are preserved |
| P1-EXC | Eclipse JDT caught/uncaught exception breakpoints; owned cleanup | `set_exception_breakpoint`, `list_exception_breakpoints`, `remove_exception_breakpoint` | Not live verified | Exception type is supplied explicitly; duplicate markers rejected |
| P1-JUNIT | JUnit source discovery and JUnit-launch configuration | `list_junit_tests`, `start_junit_test` | Not live verified | JUnit must be installed; JUnit 4/5 runner selection uses test source; parameterized methods need separate coverage |
| P1-VARS | Read-only nested fields and array-element expansion, limit 1–50 | `expand_frame_variable` | Not live verified | Variable names depend on JVM debug information; no evaluation; output values clipped |
| P1-TRACE | JDT conditional evaluation with `System.out.println(...); return false;` | `set_tracepoint` | Not live verified | Expression evaluation may have side effects and requires user consent; verify Eclipse console output |
| P1-PAUSE | Reuse existing PAUSE control; new investigation-owned restart | existing `control_debug_session`, `restart_debug_session` | Ownership policy test passed; no live restart verification | Reused/user-owned launches cannot be restarted by new tool |
| P1-FILTER | Per-Java-target filter configuration | `configure_step_filters` | Not live verified | Respects target; 30 patterns max; does not change global Java Debug preferences |

## Checks performed in this environment

- Java 21+ example main classes compiled with `javac` and four standalone examples executed with expected sample output.
- Modified dependency-free `DebugInvestigationPolicyTest` compiled and passed with `javac` and `java`.
- Java 21 Phase 1 bridge verifier compiled with `javac` (syntax/standard-library check only).
- Java-only p2 ZIP validation utility compiled with `javac`.
- **Not performed:** Maven/Tycho build, installed Eclipse MCP calls, JUnit execution in Eclipse, tracepoint output verification, or automated Claude prompt runs. Maven and Eclipse target dependencies are not available in this execution environment.

## Required verification on Windows before publishing

1. `mvn clean package` at repository root; must compile every new Java operation against actual Eclipse JDT APIs and package an update-site ZIP.
2. Install candidate into disposable normal Eclipse; confirm bundle/feature 1.5.0, restart; ensure only that Eclipse holds bridge port.
3. `mvn verify -Pinstalled-e2e` against same installed instance; require baseline PASS and exact installed binary check.
4. Import `examples/phase-one-debugging` as Existing Maven Projects. The JUnit fixture is intentionally failing; this is expected. Allow workspace build to complete.
5. `mvn verify -Pphase1-bridge`; confirm seven PASS results and successful cleanup (tests may need adaptation to actual Eclipse console behavior, but report failures accurately).
6. Run explicit Claude prompts for every feature and preserve full MCP traces; independently verify runtime states.
7. Run natural-language prompts on the same fixture and record feature-selection rate, correct diagnosis, tool calls, time, and failures. **These probabilistic outcomes should not block a correct deterministic plugin feature release**, but must be reported.
8. Run negative permission/ownership tests and the core suite without any skill.

## Known gaps that should not be concealed

- The existing bridge returns `get_process_output` only while a launch is active. Fast-exiting programs require an inspection window or a separate output-capture improvement.
- The new JUnit discovery recognizes familiar test annotations by name; it is not yet a complete JUnit Platform test-plan discovery implementation.
- Tracepoint output and nested JVM object representations must be confirmed against the installed Eclipse version.
- A skill and A/B evaluation runner have **not** been introduced in this phase, by design.
