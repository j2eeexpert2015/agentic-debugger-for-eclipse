# Phase 3 — eclipse-debugger skill candidate v0.1.0

**Status:** IMPLEMENTED AS FILES / STATICALLY REVIEWED; **LIVE VALIDATION NOT RUN**.

## Deliverable

`skills/eclipse-debugger/SKILL.md` plus five references: `debugging-strategies.md`, `java-debugging.md`, `spring-boot-debugging.md`, `junit-debugging.md`, `eclipse-mcp-tools.md`.

## Requirement mapping

| Requirement | Implemented location | Evaluation |
|---|---|---|
| DBG-01 Breakpoint strategy | SKILL.md; debugging-strategies.md | T-03, T-06, T-08 |
| DBG-02 Root-cause chain | SKILL.md; debugging-strategies.md | T-03 to T-05 |
| DBG-03 Stepping/inspection | SKILL.md; eclipse-mcp-tools.md | T-07 |
| DBG-04 Efficiency/recovery | debugging-strategies.md; framework references | T-03 to T-05, T-11 |
| DBG-05 Java/Boot/JUnit | Three context-specific references | T-03 to T-05 |
| DBG-06 Safety/completion | SKILL.md; debugging-strategies.md | T-08, T-09 |

## Checked against MCP source

Tool names/schemas including `set_conditional_breakpoint`, `set_exception_breakpoint`, `set_tracepoint`, `start_junit_test`, `start_java_application`, `start_debug_session`, `get_stack_frames`, `get_frame_variables`, `expand_frame_variable`, `wait_for_debug_pause`, `control_debug_session`, `finish_debug_investigation` match `DebugMcpServer.java`. `control_debug_session` uses `STEP_RETURN` for step out. The skill does **not** assume a `clear_all_breakpoints` or arbitrary HTTP request tool.

## Live validation status

| Check | Status |
|---|---|
| YAML frontmatter and local reference-file integrity | Static check only |
| Global Claude Code installation and `/eclipse-debugger` recognition | NOT RUN |
| Automatic activation | NOT RUN |
| Java/JUnit/Spring Boot agent execution | NOT RUN |
| Permission/ownership behavior in real sessions | NOT RUN |
| Improved accuracy, cost or efficiency versus no skill | NOT RUN |

See `README-INSTALL-AND-TEST.md` for the test plan. No change to the underlying Eclipse debugger is required to test this skill. Do **not** mark Phase 3 complete until the live gates are recorded. Retain the independent Phase 2 scenario acceptance status.
