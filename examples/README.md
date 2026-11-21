# Java debugging fixtures

The [Spring Boot orders fixture](spring-boot-debugging/README.md) provides two Java mains, a local HTTP endpoint and selected plain and Spring-context JUnit tests for Phase 2.

`average-calculator-debug-sample` is an optional two-class Java project for manual demonstrations. Import it into Eclipse with **Existing Projects into Workspace**.

The automated `mvn verify -Pe2e` / `-Pinstalled-e2e` tests create their own isolated sample project; do not import this one for those tests.

To check the new automatic launch tools manually, create a Java project with a `main` class **without** using Run As first. Ask Claude to call `list_java_applications`, then `start_java_application` with the explicit project name and fully qualified main class. Set the breakpoint before launching to catch short-running programs.

## Phase 1 Maven fixture

`phase-one-debugging` supplies the applications and deliberately failing JUnit test used for the seven direct and Claude scenarios. `scripts\windows\dev.bat deploy` imports its POM and sources into the selected development workspace. The calculator regression separately prepares `AgenticDebuggerCalculatorE2E`. [Developer commands](../docs/eclipse-installation-and-validation.md#developer-commands) · [Verified outcomes](../docs/phase1-implementation-status.md)
