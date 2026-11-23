# Failure-fixture lifecycle diagnostic patch

## Why this patch

Installed Eclipse produced both genuine Spring Boot startup-failure markers, but `GET_DEBUG_STATE` continued to report `terminated=false`. It is not yet established whether the JVM was still running or the JDI target was delayed/stale. This patch checks the fixture JVM by its own printed PID, and records thread suspension, debugger state and cleanup without relaxing the test assertion.

## Files

- `e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/BootGapVerifier.java` — PID-aware failure assertions, bounded inspection and resumed execution of **only a test-owned suspended failure JVM** after the specific failure marker, with at most 3 resumes; preserves existing scenarios.
- `examples/spring-boot-debugging/src/main/java/org/learningfromexperience/bootfixture/failures/StartupFailureApplication.java` — prints `AGENTDEBUG_FIXTURE_PID` near the startup failure marker.
- `examples/spring-boot-debugging/src/main/java/org/learningfromexperience/bootfixture/failures/PortConflictApplication.java` — prints the same PID marker near port-conflict evidence.
- `dev-e2e/SyncFailureFixtures.java` — optional Java-only snapshot synchronizer, restricted to the dedicated `AgenticDebugger/java-e2e-workspace`, validated fixture marker and **two named test files**. Refuses a detected running bridge. Does not modify production plugin code.

## Apply and run (Windows CMD)

1. Extract the ZIP **over** `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse`. Do not delete the old repository or other files.
2. Close the dedicated **Java E2E Eclipse** instance (not your personal Eclipse instance). Confirm it is completely shut down.
3. From the repository root:

```cmd
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse
java dev-e2e/SyncFailureFixtures.java
```

If it says the bridge is active, close that Eclipse instance. If it refuses a marker, do NOT bypass it; inspect which workspace/repository you are using.

4. Re-run only the two tests:

```cmd
java dev-e2e/DevE2E.java test --tests boot-gaps:startup-failure,boot-gaps:port-conflict
```

5. If both pass, run all 23 direct Java tests:

```cmd
java dev-e2e/DevE2E.java test --approve-fixture-post
```

6. Before committing:

```cmd
git status
git diff --check
git diff --stat
```

## Interpretation

A passing result must include `markerObserved=true`, `osProcessExitObserved=true`, a positive `fixturePid`, `unexpectedSuccessObserved=false`, `cleanup=true`. `controlledResumes` may be 0–3. If the process remains alive, the test **must fail**, with `lastDebugState`, `lastSuspension`, and a diagnostic excerpt. The patch cannot be called live-PASS until executed on the installed Eclipse test environment.

The synchronizer compiles with JDK 21 or newer. No Python, PowerShell, Eclipse plugin source change, Maven POM update or new external dependency is required.
