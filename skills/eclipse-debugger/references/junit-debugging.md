# JUnit 5 debugging (DBG-05 / JUnit)

Prerequisites: imported and built Eclipse Java/Maven project, JUnit/JDT integration available, exact test class and method.

## Core workflow

1. Use `list_junit_tests` for source-level test candidates. Discovery is not proof that JUnit launch integration works or a method will execute. Confirm unique project, fully qualified class and method.
2. Inspect source/test for a small candidate breakpoint location. Place the breakpoint in test or application method **before** launching, especially for fast tests.
3. Use `start_junit_test` with the selected `projectName`, `typeName`, `methodName` and, if applicable, `investigationId`. Select **one method** when possible; avoid an entire suite unless requested.
4. Check `get_debug_status` / `get_debug_threads` and wait for actual suspension. Inspect `get_stack_frames` and `get_frame_variables`, stepping to a producer or returning to the assertion if helpful.
5. Distinguish what failed: a failed **assertion**, a thrown application exception, test setup/teardown, disabled test, or Spring context initialization. A startup/context failure may prevent reaching the test breakpoint.
6. Explain expected/actual values and first causal divergence using observed evidence. Preserve unrelated configurations and finish the owned investigation.

## Typical cases

| Case | Diagnostic priority |
|---|---|
| Assertion mismatch | Inspect operands at method producing actual result; trace the earliest wrong intermediate |
| Caught exception | Check actual throw origin; exception breakpoint may help when type is known |
| Test method never runs | Verify exact method/class, discovery, JUnit launch, disabled/conditional annotation and initialization output |
| `@SpringBootTest` | Verify Spring-context startup and distinguish context failure from assertion failure |
| HTTP integration test | Trace request application thread if a real server socket is actually used; MockMvc is not equivalent to a live socket |
| Parameterized/repeated/nested JUnit cases | Source discovery may list annotations, but precise per-invocation selection is **not verified**; avoid claiming support |

`start_junit_test` is a real Eclipse JUnit debugger launch, not a standard `main()` launch. Do not substitute Maven `test` output for JVM debugger evidence. Do not edit failing fixtures to make an acceptance test pass.
