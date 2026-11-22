# Java application debugging (DBG-05 / Java)

Prerequisites: installed Eclipse JDT, a Java project built in Eclipse, connected `eclipse-debugger` MCP bridge, and an exact main class or existing launch configuration.

## Target selection

- `list_java_applications` lists available Java mains; `list_run_configurations` finds saved launch settings. Confirm project, fully qualified main type and configuration before launch.
- Prefer an appropriate saved configuration when VM args, program args, classpath, JDK or environment settings matter; do not replace it with a fresh default launch.
- `list_debug_sessions` and `list_breakpoints` help avoid interfering with other work. Use investigation IDs for new stops/launches.

## Techniques

1. **Wrong calculation or branch:** compare inputs against intermediate values. For an intermittent record use `set_conditional_breakpoint` with a valid in-scope expression; avoid hundreds of ordinary stops.
2. **Caught/uncaught exception:** use `set_exception_breakpoint` for the exact Java exception type and explicit caught/uncaught settings, then inspect throwing stack and relevant inputs. A broad `Exception` breakpoint can create irrelevant stops.
3. **Unexpected nested value:** read frame locals and use `expand_frame_variable` with bounded `offset` and `limit`; validate path and suspended state.
4. **Loop or hot path observation:** a conditional stop is preferred when there is one target record; tracepoints require explicit user consent due to evaluation/printing side effects and output limits.
5. **Library-heavy call stacks:** target-specific `configure_step_filters` is available. Apply deliberately; patterns replace active target filters. Do not globally change Eclipse preferences.
6. **Long-running main:** `PAUSE` can sample its executing stack. Only use `restart_debug_session` when the exact launch is investigation-owned; borrowed sessions cannot be restarted by this operation.
7. **Collections, streams and virtual threads:** inspect only frames/locals exposed by Eclipse JDT. No dedicated stream-pipeline introspection, virtual-thread pinning detector, heap analyzer, deadlock analyzer or remote JDWP attach has been verified as an MCP capability.

## Evidence example

A correct explanation distinguishes `discountPercent = 10`, the actual `rate` value, the numeric operation producing it, and the resulting total. Do not claim 'integer division' merely from the printed total without runtime observations if the user requested live debugging.

## Recovery

If a stop is not reached, determine whether the selected main exited, the desired line is executable, the condition can become true, or a different launch is active. For early exit, put the breakpoint in place before the next authorized launch. If the test requires JVM input or external services not available, report the blocker.
