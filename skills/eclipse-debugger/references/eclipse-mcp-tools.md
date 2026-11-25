# Verified MCP tool reference — Eclipse Agentic Debugger

**Source:** `mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java`, GitHub `main` reviewed 22 November 2026. Tool names/required parameters below are implementation-derived. Parameters marked `?` are optional in their schemas. All Java `typeName` values must be correct fully qualified names, where appropriate. Use results from live tools rather than guessing `launchName`, `threadIndex`, `frameIndex`, IDs or line numbers.

## Connectivity, discovery, lifecycle

| Tool | Parameters | Behavior / constraint |
|---|---|---|
| `ping` | none | MCP process alive, **not** Eclipse connectivity |
| `eclipse_ping` | none | Actual running IDE bridge; expect PONG |
| `list_debug_sessions` | none | Existing debugger sessions |
| `list_breakpoints` | none | Existing Java line breakpoints and ownership |
| `list_java_applications` | none | Find Java mains (including Boot mains) |
| `list_run_configurations` | none | Find saved debug-capable Eclipse configurations |
| `list_junit_tests` | none | Discover source JUnit candidate methods |
| `begin_debug_investigation` | `mode?` = `NORMAL` or `CONTROLLED` | Returns `investigationId`; defaults NORMAL. With one open investigation, breakpoints and launches without `investigationId` are attached to it; with several, pass it. If the session ends or stops calling for 10 minutes, it is finished automatically |
| `finish_debug_investigation` | `investigationId` | NORMAL scoped cleanup; CONTROLLED preserves debug state. The reply confirms the cleanup (`cleanup`, `breakpointsRemoved`, `ownBreakpointsLeft`) and names the report file (`reportFile`) when reports are on |

## Breakpoints and launches

| Tool | Required parameters | Optional | Notes |
|---|---|---|---|
| `set_breakpoint` | `typeName`, `lineNumber` | `investigationId` | Ordinary line stop, does not start JVM |
| `remove_breakpoint` | `typeName`, `lineNumber` | — | Remove only agent-owned stop |
| `set_conditional_breakpoint` | `typeName`, `lineNumber`, `condition` | `investigationId` | Condition evaluated by JVM/JDT when line executes |
| `set_exception_breakpoint` | `exceptionName`, `caught`, `uncaught` | `investigationId` | At least one of caught/uncaught must be true |
| `list_exception_breakpoints` | none | — | Inspect existing exception stops |
| `remove_exception_breakpoint` | `exceptionName` | — | Agent-created only |
| `set_tracepoint` | `typeName`, `lineNumber`, `expression`, `allowSideEffects` | `investigationId` | **Requires explicit consent** and `allowSideEffects=true`; prints without normal suspension |
| `start_java_application` | `projectName`, `mainType` | `investigationId` | Java/Boot main; returns `launchName`. Refused when the project has compile errors or is not built: report that to the user, do not retry |
| `start_debug_session` | `launchName` | `investigationId` | Start saved configuration; retains its settings |
| `start_junit_test` | `projectName`, `typeName`, `methodName` | `investigationId` | `methodName` can be empty for class launch; verify exact selection |
| `restart_debug_session` | `launchName`, `investigationId` | — | Only an investigation-owned launch; borrowed launches rejected |
| `configure_step_filters` | `launchName`, `enabled`, `patterns` | — | String of target-specific patterns accepted by the installed MCP implementation; replaces target filters; not global |

## Live debug observations and control

| Tool | Required parameters | Notes |
|---|---|---|
| `get_debug_status` | `launchName` | Check current debug target and suspension state |
| `get_debug_threads` | `launchName` | Get actual indexes and suspended states |
| `get_stack_frames` | `launchName`, `threadIndex` | Inspect suspended thread stack |
| `get_frame_variables` | `launchName`, `threadIndex`, `frameIndex` | Read-only frame locals |
| `expand_frame_variable` | `launchName`, `threadIndex`, `frameIndex`, `path`, `offset`, `limit` | Bounded read-only expansion |
| `wait_for_stop` | `launchName`, `timeoutSeconds` | **Preferred.** Wait 1–60 s for the next stop event of this launch. Returns `reason` (`BREAKPOINT`, `EXCEPTION`, `STEP_END`, `PAUSED`, `SUSPENDED`), `threadIndex`, `breakpoint` or `exceptionType`, `location`, top 5 `frames` and up to 50 `locals` (`localsNote` explains when locals are unavailable, e.g. a native method after a pause); or `stopped:false` with `TERMINATED` or `TIMEOUT`. Each stop is reported once; never resumes. |
| `wait_for_debug_pause` | `launchName`, `timeoutSeconds` | Legacy polling wait; use `wait_for_stop` instead |
| `get_process_output` | `launchName` | Captured stdout/stderr/console. Output over 8000 characters returns the first and last 4000 with an omission marker; `stdoutChars`/`stderrChars`/`consoleChars` give the full sizes. With the Eclipse Console open, output is usually only in `console`; read it first. Don't leak secrets |
| `show_debug_location` | `launchName` | Navigate Eclipse to source; no execution control |
| `control_debug_session` | `launchName`, `threadIndex`, `action` | `investigationId?`; actions `PAUSE`, `RESUME`, `STEP_INTO`, `STEP_OVER`, `STEP_RETURN`, `STOP`. `STEP_RETURN` means Step Out. Never STOP a borrowed/user-owned JVM. Without an investigation (none open, or the session not started by one) the call is refused: begin an investigation first. |
| `evaluate_expression` | `launchName`, `threadIndex`, `frameIndex`, `expression`, `allowSideEffects` | Requires explicit consent and `allowSideEffects=true`; may mutate state. Prefer locals/expansion. |

## No corresponding verified MCP tool

Do **not** use imaginary commands for arbitrary HTTP requests, JDWP remote attach, data watchpoints, Run to Line, Hot Code Replace, variable mutation, Spring bean graph inspection, transaction tracing, complete stream pipeline views or deadlock/pinning detection. No `clear_all_breakpoints` operation should be emulated using `remove_breakpoint` in a loop; use investigation cleanup.

## Tool schema guidance

Only call tools advertised by the current connected server; if the installed artifact differs from the reviewed source, trust the live tool schema and treat mismatches as compatibility issues to document. Before a test claim, require real Eclipse session suspension, stack, variables and correct cleanup—not only a tool success response. The skill is not permitted to override MCP server authorization constraints.
