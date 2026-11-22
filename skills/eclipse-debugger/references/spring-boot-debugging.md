# Spring Boot application debugging (DBG-05 / Spring Boot)

A Spring Boot app is debugged through the **existing Eclipse Java/JDT launch**, not a special Spring Boot MCP command. The current evidence covers selected mains, saved configs, startup output, real HTTP request tracing, and handled exception paths on test fixtures. Spring Tools-specific launch delegates are **not yet verified**.

## Select and launch correctly

1. Use `list_java_applications` to identify the intended Boot main (possibly among several). Use `list_run_configurations` to inspect saved settings. Ambiguous mains, profiles or environments require clarification; never guess and call the wrong service.
2. Prefer `start_debug_session` when an existing saved launch carries necessary active profiles, VM args, program args, env vars or working directory. Otherwise use `start_java_application` for the explicitly selected project/main.
3. Read `get_process_output` to distinguish ready, exception at startup, bind conflict, missing property, or failed context initialization. Do not assert ready merely because a JVM exists. HTTP readiness must come from trustworthy output or an explicitly authorized bounded probe.

## Request debugging

1. Determine exact route, method, expected behavior, parameters, and where service is running. Request reproduction is **not** a built-in Eclipse MCP tool; it needs a separate authorized local HTTP client or an existing test runner.
2. Validate host, port and environment before sending any request. Do not run non-idempotent `POST`/`PUT`/`DELETE` or change production state without explicit approval. `localhost` alone is not enough if the selected process is uncertain.
3. Place one or a few relevant breakpoints **before** triggering a request. Choose controller, service, validator or repository only where source evidence shows those components.
4. Trigger the single approved request or ask user to do so. Because the request thread may be suspended, the HTTP client may wait for resume; this is expected, not automatically a timeout defect.
5. Use `wait_for_debug_pause`, `get_debug_threads`, `get_stack_frames`, `get_frame_variables` and targeted stepping to follow **the actual suspended request thread**. Never assume the most recently selected thread belongs to the request.
6. Observe the response status/body after resuming the request. Correlate response and exact tested request. Diagnose first wrong value/branch in the actual observed path; don't assume code executes in a fixed controller → service → repository order.
7. Finish only investigation-owned resources. For long-lived services, ensure user-owned/borrowed servers remain running.

## Symptom-specific branches

| Symptom | Investigation priority |
|---|---|
| App never starts | Process output, configuration/profile, port conflict, Spring context errors; do not send HTTP yet |
| Endpoint returns wrong value | Check input binding, controller and business calculation; use selected request thread |
| HTTP 400 | Determine whether validation happened before controller entry or in application code; look for actual handler/exception frame |
| HTTP 500 | Inspect real exception/throwing frame, source and response mapping; avoid guessing handler behavior |
| Multiple Boot services | Confirm exact main/profile/port/session identity, preserve unrelated launch and markers |
| JPA/transaction inconsistency | Ordinary Java frames can be inspected; deep ORM, transactional and database diagnostics are not a dedicated verified MCP feature |
| Concurrent/async request | Inspect exposed threads/stacks cautiously; request correlation beyond observed evidence remains unverified |

The Eclipse server cannot itself run arbitrary HTTP traffic, detect Spring bean graphs, trace JPA queries or infer production authorization. Park scenarios beyond existing verified tooling instead of inventing MCP commands.
