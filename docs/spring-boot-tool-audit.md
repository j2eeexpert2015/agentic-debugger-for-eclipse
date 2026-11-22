# Spring Boot tool audit

The Phase 2 feasibility check uses the existing debugger with the [standalone orders fixture](../examples/spring-boot-debugging/README.md). The matrix identifies the operations under test and the checks still needed before claiming broader Boot support.

## Installed results, 22 November 2026

The direct bridge probes used debugger bundle `1.5.0.20261121194127738` in a separate Windows Eclipse 2026-09 JDT/JUnit installation with m2e. The debugger and MCP server came from Phase 1. A temporary workspace helper imported the fixture. These checks exercised the Eclipse bridge directly. The later [HTTP investigation](#p2-04-http-quote-investigation) includes an explicit Claude trial.

| Check | Observation |
|---|---|
| Fixture build | Maven's two default smoke tests passed. In a separate run both tagged discount assertions failed with expected 108.00 and actual 120.00. The parent Maven reactor passed validation with the fixture outside its modules. |
| Discovery | Eclipse returned both main types and all four fixture test methods. The probe explicitly selected the orders main. |
| Launch and readiness | The first launch created a Java Application configuration. A later launch reused that saved configuration and created a fresh JVM. The selected process reported its actual Tomcat listening port before the first HTTP request. |
| HTTP and validation | The local POST stopped in `OrderService.quote` with `OrderController.quote` on the stack. Observed locals included quantity 2, percent 10 and rate 0. After resume, the response was HTTP 200 with total 120.00. Quantity zero produced HTTP 400 and “Quantity must be positive”. |
| Individual JUnit methods | Eclipse launched `OrderServiceTest.calculatesDiscount` and `OrderApiIntegrationTest.quoteAppliesDiscount` separately. The former had the selected test in the service call stack. The latter reached the service through its HTTP controller in the embedded server. Both showed percent 10 and rate 0, and both completed NORMAL cleanup on the successful checks. |
| Repeat behavior | On the first plain-JUnit probe a thread resumed between listing threads and reading its stack. Retrying the observation reached the intended breakpoint. During a separate repeat of the main launch, `FINISH_INVESTIGATION` returned `IllegalStateException: destroy of current process not allowed`. The target subsequently had no active launch under that name. A fresh run completed cleanup. The cause and consistency of ownership cleanup remain open. |

## P2-03 saved configurations and readiness

See also the [request-level debugger evidence](#p2-04-http-quote-investigation) from P2-04.

Direct checks ran on the same Windows candidate in a fresh workspace. The fixture used three saved JDT Java Application configurations for the orders main. All checks used the existing bridge operations.

| Case | Runtime evidence |
|---|---|
| Target selection | Discovery returned the two fixture mains. Invalid project and main names were rejected. With three matching configurations, launching by project and main returned an ambiguity error. An unknown configuration name was rejected. Selecting the exact saved name succeeded. |
| Two projects | A second isolated workspace contained two copies of the fixture with distinct project names and identical Java main type names. Discovery returned all four project/type pairs. Selecting `inventory-service` explicitly created its own launch, reached readiness and completed cleanup. |
| Saved settings | The application argument activated the `test` profile. A Spring profile group loaded from the configured working directory resolved values supplied by the VM argument and environment. Spring reported all four expected profile names in its startup output. After testing, all three configurations retained their original launch type and settings. The comparison excluded Eclipse's termination timestamp. |
| Readiness | Two successful starts reported a loopback server port within the 60-second deadline. Only after the listening message did the verifier send a quote request and receive HTTP 200. With a breakpoint holding the app before Spring initialization, a separate three-second readiness check timed out after 17 polls and sent zero requests. |
| Startup failure | With the selected port occupied, Spring reported that the port was already in use. A launch requiring a nonexistent configuration file reported the missing file. The verifier read each diagnostic from the selected process and reported failure before sending HTTP. |
| Cleanup | On both successful starts, a second investigation borrowed the running launch. Its cleanup preserved the service for the owner's HTTP request. Owner cleanup completed and the listening port closed. The failed starts and suspended timeout case also completed cleanup. The earlier intermittent cleanup error did not recur here; its cause remains unknown. |

For this fixture, poll `get_process_output` on the selected launch with a deadline. A concrete listening message supplies the port, a Spring failure diagnostic supplies the cause, and an expired deadline is an unknown readiness result. These direct checks cover JDT launches with embedded Tomcat. Spring Tools delegates, other server implementations and agent-prompt acceptance require separate evidence.

| Feature | Existing operation and source behavior | Remaining question |
|---|---|---|
| BOOT-01 discovery | `list_java_applications` enumerates Java main methods in open Java projects. Project and main type identify a target. | Can an agent distinguish the intended Boot service from other Java mains when the user supplies only a symptom? |
| BOOT-02 launch | `start_java_application` checks the selected main and reuses a matching JDT Java Application configuration. Multiple matching configurations require selection by name. `list_run_configurations` and `start_debug_session` expose saved configurations. | Exercise Spring Tools launch delegates in an installation that includes them. |
| BOOT-03 readiness | `get_process_output` reads the selected launch's captured output. The bounded checks above distinguish ready, startup failure and timeout. | Check diagnostics that fall outside the output window or are first requested after the JVM has exited. |
| BOOT-04 HTTP debugging | A local HTTP client can issue the fixture request while Eclipse handles breakpoints, stack frames and locals. The request must remain in flight while the application thread is suspended. | Exercise wrong-target rejection, consent and concurrent services. |
| BOOT-06 JUnit | `list_junit_tests` scans source annotations. `start_junit_test` selects a project, type and method through the Eclipse JUnit launch delegate. Spring initializes the context inside the selected test. | Check context initialization failure, ambiguous selections and parameterized cases. |
| BOOT-07 validation | The existing Java exception breakpoint and stack inspection can target application exceptions. The HTTP client can check the response independently. | Observe the validation throw site and distinguish a framework rejection before controller entry. |

The [MCP tool definitions](../mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) expose the user-facing arguments. The [Eclipse operations](../eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java) implement main discovery, configuration selection and JUnit launch. The fixture requires Java 21+, its Maven dependencies, JDT and the Eclipse JUnit delegate. The installed check uses m2e for project import.


## P2-04 HTTP quote investigation

A direct bridge probe and one fresh Claude Sonnet 5.5 session each debugged a separate real POST on Windows candidate `1.5.0.20261121194127738`. The explicit prompt included numbered fixture source and named the three boundaries. The [fixture client](../e2e-runner/boot-tests/Invoke-FixtureQuote.ps1) sent each approved request and wrote its HTTP receipt. Claude controlled the Eclipse investigation and read the receipt after resuming the request.

| Boundary | Observed evidence |
|---|---|
| Controller | The request contained quantity 2 and discount percent 10. The HTTP request body supplied unit price 60. Claude saw a BigDecimal reference in the debugger; it read the numeric price from the receipt. |
| Repository | The request stopped at the return in `OrderRepository.findDiscount` with `requestedPercent=10`. |
| Service | After integer division, the suspended frame contained `percent=10` and `rate=0`. This was the first incorrect value. |
| HTTP response | Both runs received HTTP 200 with `{"total":120.00}`; applying the requested 10% discount to 120.00 gives 108.00. Both NORMAL investigations completed cleanup. |
| Negative checks | The client rejected missing approval, a foreign host, the wrong port, another route and an unknown input parameter. On the separate zero-quantity test, the debugger stopped at the service rejection and then the controller error handler. An enabled repository entry breakpoint received no intervening hit. |

These results cover the direct and explicit-prompt investigation of this fixture. Independent symptom-prompt trials and the full Phase 2 acceptance matrix remain pending.

## P2-05 JUnit failure and skip checks

Eclipse previously launched the selected plain and Spring-context JUnit methods and stopped in application code. The latest default Maven fixture run recorded two passing tests and one disabled test skipped. The broken-context fixture carries the `debug-defect` tag, which that run excludes.

The isolated Eclipse workspace reported a project-description error during import, before it produced JUnit results for the negative cases. Installed verification remains open for rejection of a missing method, context initialization failure before the test body, and a selected method skipped by JUnit.
