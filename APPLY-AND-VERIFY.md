# Focused fix for the two new failing Boot E2E scenarios

Observed baseline on 23 November 2026:
- `boot-gaps:startup-failure`: FAIL, expected startup marker not observed
- `boot-gaps:readiness-timeout`: PASS
- `boot-gaps:port-conflict`: FAIL, marker observed but JVM remained running
- `boot-gaps:owned-isolation`: PASS
- Previous combined direct regression: 19/19 PASS

This patch changes **only** the two deliberately failing Spring Boot fixture classes and `BootGapVerifier.java`. It does not alter production debugger/MCP code, `DevE2E.java`, other test cases, `.gitignore`, any local settings, or the regular Orders application.

## Install (Windows CMD)

1. Extract this ZIP over the existing repository root, preserving paths.
2. Close **only** the dedicated Java E2E Eclipse instance. The test runner leaves that Eclipse instance running between tests.
3. The Eclipse test workspace is a snapshot. Copy the two changed fixture files into the dedicated workspace, **only after confirming** that the workspace is disposable and the `.agentdebug-fixture` marker points to your repository's `examples/spring-boot-debugging` source. Example commands below use the known checkout and workspace from the reported output.

```cmd
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse

type "C:\Users\vmadmin\AppData\Local\AgenticDebugger\java-e2e-workspace\spring-boot-debugging\.agentdebug-fixture"

copy /Y "examples\spring-boot-debugging\src\main\java\org\learningfromexperience\bootfixture\failures\StartupFailureApplication.java" "C:\Users\vmadmin\AppData\Local\AgenticDebugger\java-e2e-workspace\spring-boot-debugging\src\main\java\org\learningfromexperience\bootfixture\failures\StartupFailureApplication.java"

copy /Y "examples\spring-boot-debugging\src\main\java\org\learningfromexperience\bootfixture\failures\PortConflictApplication.java" "C:\Users\vmadmin\AppData\Local\AgenticDebugger\java-e2e-workspace\spring-boot-debugging\src\main\java\org\learningfromexperience\bootfixture\failures\PortConflictApplication.java"
```

Alternatively, rename the **closed, disposable** `java-e2e-workspace` to a fresh *unused* backup name and let `DevE2E.java` recreate it. Do not delete or rename personal Eclipse workspaces.

4. Run only the previously failing cases:

```cmd
java dev-e2e/DevE2E.java test --tests boot-gaps:startup-failure,boot-gaps:port-conflict
```

5. If both pass, rerun the four new scenarios, then the 23-scenario direct regression:

```cmd
java dev-e2e/DevE2E.java test --tests boot-gaps:startup-failure,boot-gaps:readiness-timeout,boot-gaps:port-conflict,boot-gaps:owned-isolation
java dev-e2e/DevE2E.java test --approve-fixture-post
```

Do not claim 23/23 until the final live run passes.

## Explanation

- The bridge's `GET_PROCESS_OUTPUT` returns up to the first 4,000 characters of each process stream. An exception marker printed late in a Spring Boot failure trace may therefore be unavailable to the verifier.
- The verifier formerly replaced the observed output on every poll, losing a previously observed marker if the process exited.
- `StartupFailureApplication` now prints its deliberate bean-initialization failure marker *before* the long Spring exception trace and explicitly exits with a nonzero status after a short observation delay.
- `PortConflictApplication` now prints the diagnostic, then explicitly exits nonzero after a short delay so fixture-owned JVM background threads cannot survive the intentional startup failure.
- `BootGapVerifier` now retains observations across polls, rejects unexpected successful startup, and records useful evidence for future failures.

## Verification boundaries

The patched Java files compiled with `javac --release 21` using Spring API stubs (syntax check only). Live Spring Boot / Eclipse outcomes require the above Windows execution. No Python runtime or scripts are included.
