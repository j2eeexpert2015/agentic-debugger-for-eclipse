# Phase 1 Java-only verification

The Phase 1 direct bridge verifier is `PhaseOneBridgeVerifier` in the Java `e2e-runner` module. It uses Java's standard networking and JSON codec, **not Python**. It neither starts Claude nor depends on an agentic skill.

## Prerequisites

1. Install a JDK (21 or newer), Maven, Eclipse with the current Phase 1 plugin, and the Java/Maven and JUnit tooling needed by the sample project.
2. Build/install the plugin using the existing Windows installation procedure in the README; restart Eclipse, and ensure only the target Eclipse instance listens on `127.0.0.1:8765`.
3. Import `examples/phase-one-debugging` into the **same** running Eclipse using **File > Import > Maven > Existing Maven Projects** and wait for classpath resolution and compilation. The intentionally failing JUnit test is expected to fail when run normally.
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

The Java code was syntax-compiled outside Eclipse. Full Maven/Tycho compilation, live Eclipse integration, and Claude E2E remain **unverified** until executed on the Windows test machine. The `trace` scenario expects trace output via `GET_PROCESS_OUTPUT` and may identify a real implementation issue if output is only available in another Eclipse console; do not classify it as passed without evidence.
