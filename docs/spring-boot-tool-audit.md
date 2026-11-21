# Spring Boot tool audit

The Phase 2 feasibility check uses the existing debugger with the [standalone orders fixture](../examples/spring-boot-debugging/README.md). The matrix identifies the operations under test and the checks still needed before claiming broader Boot support.

## Installed results, 22 November 2026

The direct bridge probes used debugger bundle `1.5.0.20261121194127738` in a separate Windows Eclipse 2026-09 JDT/JUnit installation with m2e. The debugger and MCP server came from Phase 1. A temporary workspace helper imported the fixture. These checks exercised the Eclipse bridge directly. Boot-specific Claude trials remain pending.

| Check | Observation |
|---|---|
| Fixture build | Maven's two default smoke tests passed. In a separate run both tagged discount assertions failed with expected 108.00 and actual 120.00. The parent Maven reactor passed validation with the fixture outside its modules. |
| Discovery | Eclipse returned both main types and all four fixture test methods. The probe explicitly selected the orders main. |
| Launch and readiness | The first launch created a Java Application configuration. A later launch reused that saved configuration and created a fresh JVM. The selected process reported its actual Tomcat listening port before the first HTTP request. |
| HTTP and validation | The local POST stopped in `OrderService.quote` with `OrderController.quote` on the stack. Observed locals included quantity 2, percent 10 and rate 0. After resume, the response was HTTP 200 with total 120.00. Quantity zero produced HTTP 400 and “Quantity must be positive”. |
| Individual JUnit methods | Eclipse launched `OrderServiceTest.calculatesDiscount` and `OrderApiIntegrationTest.quoteAppliesDiscount` separately. The former had the selected test in the service call stack. The latter reached the service through its HTTP controller in the embedded server. Both showed percent 10 and rate 0, and both completed NORMAL cleanup on the successful checks. |
| Repeat behavior | On the first plain-JUnit probe a thread resumed between listing threads and reading its stack. Retrying the observation reached the intended breakpoint. During a separate repeat of the main launch, `FINISH_INVESTIGATION` returned `IllegalStateException: destroy of current process not allowed`. The target subsequently had no active launch under that name. A fresh run completed cleanup. The cause and consistency of ownership cleanup remain open. |

The existing operations suffice for this fixture's basic Java launch and single-method JUnit cases. P2-03 should cover saved configuration settings, ambiguous matches, startup failures and the observed cleanup error before extending the tool surface.

| Feature | Existing operation and source behavior | Remaining question |
|---|---|---|
| BOOT-01 discovery | `list_java_applications` enumerates Java main methods in open Java projects. Project and main type identify a target. | Can an agent distinguish the intended Boot service from other Java mains when the user supplies only a symptom? |
| BOOT-02 launch | `start_java_application` checks the selected main and reuses a matching JDT Java Application configuration. Multiple matching configurations require selection by name. `list_run_configurations` and `start_debug_session` expose saved configurations. | Verify preconfigured profiles, arguments, environment and working directory. Exercise Spring Tools launch delegates in an installation that includes them. |
| BOOT-03 readiness | `get_process_output` reads the selected launch's captured output. A verifier can wait for a concrete ready signal with a deadline. | Check startup failure, occupied ports, missing readiness messages and truncated output. |
| BOOT-04 HTTP debugging | A local HTTP client can issue the fixture request while Eclipse handles breakpoints, stack frames and locals. The request must remain in flight while the application thread is suspended. | Exercise wrong-target rejection, consent and concurrent services. |
| BOOT-06 JUnit | `list_junit_tests` scans source annotations. `start_junit_test` selects a project, type and method through the Eclipse JUnit launch delegate. Spring initializes the context inside the selected test. | Check context initialization failure, ambiguous selections and parameterized cases. |
| BOOT-07 validation | The existing Java exception breakpoint and stack inspection can target application exceptions. The HTTP client can check the response independently. | Observe the validation throw site and distinguish a framework rejection before controller entry. |

The [MCP tool definitions](../mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) expose the user-facing arguments. The [Eclipse operations](../eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java) implement main discovery, configuration selection and JUnit launch. The fixture requires Java 21+, its Maven dependencies, JDT and the Eclipse JUnit delegate. The installed check uses m2e for project import.
