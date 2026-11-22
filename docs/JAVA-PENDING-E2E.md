# Java-only tests for incomplete acceptance coverage — initial additive batch

This patch adds **one Java source file** to the existing `e2e-runner` module. It does **not** edit or replace any working PowerShell script, existing Java verifier, fixture, POM, or production plugin/MCP source. The new test class uses the repository's existing Java bridge protocol. It launches no shell scripts and uses no PowerShell.

## Scenarios implemented in this batch

| ID | New Java test | What is independently asserted | Not established by this test |
|---|---|---|---|
| `CORE-05-03/04` | `core-step-into-out` | Step Into reaches `StepFilterApplication.sum`; Step Return reaches `main` on same thread | All Boot/JUnit stepping combinations, separate Step Over and stale thread negatives |
| `SB-01` | `sb01-target-selection` | Exactly one identified `OrdersApplication` main; unknown target/investigation is rejected; session list remains unchanged | Claude autonomous choice with ambiguous applications |
| `SB-09` | `sb09-borrowed-session` | A secondary investigation reuses but cannot destroy an owner's long-running Java fixture | Two simultaneous Boot services and concurrency interleavings |
| `SB-09` | `sb09-expired-owner` | A finished investigation cannot restart another investigation's running fixture | Every malformed/expired ownership operation |

These are **new Java tests for gaps in acceptance coverage**; they do not duplicate or migrate the current passing PowerShell suites. They do not establish full `SB-01`/`SB-09` acceptance on their own.

## Requirements

1. Java 21+, Maven, and a working Eclipse Java/JDT installation with the agentic debugger installed.
2. Existing `phase-one-debugging` fixture imported into a **disposable dedicated** Eclipse workspace by the existing deployment procedure. The workspace must contain `phase-one-debugging/.agentdebug-fixture`.
3. Bridge listening on a dedicated port (for example `21765`, **not** the default `8765`). The correct Eclipse instance must already be running. The runner verifies the actual workspace and loaded bundle before any test.
4. `spring-boot-debugging` Maven fixture imported for the `sb01-target-selection` test.
5. No conflicting test JVMs from earlier test runs; the runner refuses to borrow a preexisting JVM for its mutating tests.

## Install

Copy the added Java file into the same path in the project. From the root of the repository, build the existing Maven modules and the E2E module:

```sh
mvn -Pe2e -DskipTests package
```

This invokes packaging, not the Maven `verify` lifecycle where some existing test executions are bound. Do not pass `-Pinstalled-e2e` or `-Pphase1-bridge` for this build step.

## Execute from Windows Command Prompt

```cmd
set "AGENTDEBUG_PORT=21765"
java -cp e2e-runner/target/classes org.learningfromexperience.agentdebug.e2e.PendingAcceptanceVerifier --root . --workspace "%LOCALAPPDATA%\AgenticDebugger\dev-workspace" --scenario all
```

## Execute from Linux/macOS shell

```sh
export AGENTDEBUG_PORT=21765
java -cp e2e-runner/target/classes org.learningfromexperience.agentdebug.e2e.PendingAcceptanceVerifier --root . --workspace "$HOME/agentdebug-test-workspace" --scenario all
```

The Linux/macOS example assumes that a suitable Eclipse installation, plugin, fixtures, isolated workspace, and bridge are already set up. This patch **does not** yet provide cross-platform Eclipse installation/deployment orchestration.

For a single scenario, replace `all` with one of:

```
core-step-into-out
sb01-target-selection
sb09-borrowed-session
sb09-expired-owner
```

Default report: `evaluation/reports/pending-java-e2e.json`. Override with `--output FILE`.

**Safety:** The runner uses NORMAL investigations and calls `FINISH_INVESTIGATION` in `finally`. It refuses a missing fixture marker, mismatched workspace, default bridge port, and borrowing of an already-running fixture. It does not send HTTP requests or run Claude CLI. Do not run in a workspace containing real user debugging sessions.

## Verification limits

- The Java source was compiled with Java 21 against a **signature-only bridge stub** because an installed Eclipse runtime and complete repository dependencies are unavailable in this execution environment. No real Eclipse/JDT test was executed; no acceptance scenario is claimed as PASS.
- Before accepting the patch, compile it in the actual repository and run each scenario against your disposable installed Eclipse instance. Review any failed run's JSON traces before changing production code.
- The current PowerShell/Java baseline remains untouched. This initial patch is not yet full coverage of all pending Spring Boot, Java, JUnit and Claude skill gates. In particular, **SB-04–SB-08 symptom-only agent tests, SB-10 Spring Tools matrix, cross-service SB-09, skill activation and skill JSONL scoring still need separate Java test implementation**.
- Do not replace existing passed-test reports with these outputs; the report path is deliberately new.
