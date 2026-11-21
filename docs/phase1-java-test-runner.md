# Phase 1 Java-only verification

The Phase 1 direct bridge verifier is `PhaseOneBridgeVerifier` in the Java `e2e-runner` module. It uses Java's standard networking and JSON codec, **not Python**. It neither starts Claude nor depends on an agentic skill.

## Prerequisites

1. Install a JDK (21 or newer), Maven, Eclipse with the current Phase 1 plugin, and the Java/Maven and JUnit tooling needed by the sample project.
2. Use `scripts\windows\dev.bat deploy` to build the plugin, start the selected Eclipse workspace and import the Maven fixture. [Deployment settings](eclipse-installation-and-validation.md#developer-commands)
3. For a manually installed workspace, import `examples/phase-one-debugging` as an existing Maven project and wait for the build. The fixture includes a deliberately failing JUnit test.
4. Run the Windows verification script from the repository root (or use the Maven commands directly):

```cmd
scripts\windows\verify-phase1-eclipse.bat
```

Equivalent Maven command (the installed plugin must already match the build):

```cmd
mvn verify -Pphase1-bridge
```

To run just one named scenario (seven names: `conditional`, `exception`, `junit`, `variables`, `trace`, `pause-restart`, `filters`):

```cmd
scripts\windows\verify-phase1-eclipse.bat conditional
```

Equivalent Maven command:

```cmd
mvn verify -Pphase1-bridge -Dagentdebug.phase1.scenario=conditional
```

To override the Eclipse project name:

```cmd
mvn verify -Pphase1-bridge -Dagentdebug.phase1.project=phase-one-debugging
```

## Reports and release gate

The Java runner writes `evaluation/reports/phase1-live.json` containing a result per scenario and actual bridge request/response traces. `PASS` requires both the checks and investigation cleanup. A failed scenario causes the Maven verification step to fail.

These checks do not establish that Claude selects the new tools; use the explicit and natural-language prompts in [phase1-claude-scenarios.md](phase1-claude-scenarios.md) separately.

All seven scenarios passed with cleanup on installed build `1.5.0.20261121184224374`. The trace verifier read the target process console and confirmed `TRACE-ORDER 1057`. [Same-build results and known limits](phase1-implementation-status.md)

## Windows troubleshooting: November 21, 2026 verifier corrections

- `NumberFormatException: For input string: "NQ"` while creating a conditional breakpoint was caused by the **Java verifier**, not the Eclipse breakpoint implementation. `NQ` is URL-safe Base64 for the digit `5`. Bridge numeric parameters must be sent as plain decimal text; string parameters remain Base64-encoded. This source package corrects the verifier. **Redeploying the Eclipse plugin is not required solely for this verifier fix**, provided the installed plugin already matches v1.5.0.
- `Open Java project not found: phase-one-debugging` means the Phase 1 Maven fixture is not an **open Java project in the Eclipse instance serving port 8765**. Import `examples/phase-one-debugging` through **File > Import > Maven > Existing Maven Projects**, confirm the project name `phase-one-debugging`, let the workspace finish building, and use the same Eclipse instance for tests. Maven building the repository does not import the fixture into Eclipse.
- `JUnit test not discovered` can follow from the missing fixture. Check the JUnit dependency/classpath, the project's source/test source recognition, and Eclipse JUnit support if it persists **after** import. The runner now records this assertion failure in its report and continues with remaining scenarios.
- `: was unexpected at this time.` was caused by an unescaped parenthesis in an `echo` statement inside a Windows batch block. Corrected in `scripts/windows/verify-phase1-eclipse.bat`.

Run `scripts\windows\verify-phase1-eclipse.bat conditional` first, then `scripts\windows\verify-phase1-eclipse.bat all` after the fixture has been imported. The Java verifier creates `evaluation/reports/phase1-live.json`; individual scenario FAILs must not be mistaken for a successful test run. The subsequent Windows runs verified the corrected behavior.
