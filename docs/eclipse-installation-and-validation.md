# Eclipse Installation, Deployment, and Phase 1 Verification (Windows)

[README](../README.md#documentation) · [Phase 1 status](phase1-implementation-status.md) · [Java test runner](phase1-java-test-runner.md) · [Claude scenarios](phase1-claude-scenarios.md)

This guide is for **the Eclipse IDE side** of Agentic Debugger for Eclipse IDE. It distinguishes (1) a normal **installed Eclipse IDE**, (2) a **PDE-launched Runtime Eclipse**, and (3) Claude Code. The Phase 1 candidate is **source-implemented, not yet certified on a running Eclipse**. An installation command succeeding does not prove debugger behavior is correct.

## Contents

1. [Which Eclipse instance should I use?](#1-which-eclipse-instance-should-i-use)
2. [Prerequisites and project paths](#2-prerequisites-and-project-paths)
3. [Deploy to normal Eclipse with the Windows script](#3-deploy-to-normal-eclipse-with-the-windows-script)
4. [Manual Eclipse installation from an update-site ZIP](#4-manual-eclipse-installation-from-an-update-site-zip)
5. [Verify the plugin and Claude connection](#5-verify-the-plugin-and-claude-connection)
6. [Import the Phase 1 Java fixture](#6-import-the-phase-1-java-fixture)
7. [Run installed-plugin regression and all seven feature checks](#7-run-installed-plugin-regression-and-all-seven-feature-checks)
8. [Validate Claude invocation of the features](#8-validate-claude-invocation-of-the-features)
9. [Review evidence and interpret failures](#9-review-evidence-and-interpret-failures)
10. [Troubleshooting and cleanup](#10-troubleshooting-and-cleanup)

## 1. Which Eclipse instance should I use?

| Environment | Purpose | Plugin installation | Maven profile |
|---|---|---|---|
| **Normal Eclipse IDE** (`C:\All_Softwares\eclipse`) | Validate the artifact actual users install; recommended for Phase 1 | Install p2 feature using the Windows deployment script or **Help → Install New Software** | `-Pinstalled-e2e` and `-Pphase1-bridge` |
| **PDE Runtime Eclipse** launched from Eclipse RCP (`C:\All_Softwares\eclipse-rcp`) | Developer testing of workspace plugin before packaging | No p2 installation into Runtime Eclipse; launch workspace plugin via **Run → Eclipse Application** | `-Pe2e` |
| **Claude Code in Eclipse terminal or external terminal** | Agent-level evaluation, **after** plugin/bridge tests pass | None; connects to `eclipse-debugger` MCP registration | Use prompts in `phase1-claude-scenarios.md` |

**Only one Eclipse process may own `127.0.0.1:8765`.** Do not leave PDE Runtime Eclipse open when testing the installed normal Eclipse, or vice versa. A terminal and Claude Code are clients, not replacements for a running Eclipse IDE.

## 2. Prerequisites and project paths

- Windows with **Java 21+**, `java` and `mvn` available on `PATH`; the contributor setup uses JDK 25.
- Normal Eclipse IDE with **Eclipse Java Development Tools (JDT)** and Maven integration (m2e). For the JUnit scenario, Eclipse JUnit launch support must also be installed.
- A full checkout/extracted **source repository** (not merely the p2 update-site ZIP).
- Claude Code CLI is needed for the agent E2E and manual Claude prompts, **not** for the Java-only Phase 1 bridge verifier.
- The local developer deployment script currently uses these defaults:

```bat
set "ECLIPSE_DIR=C:\All_Softwares\eclipse"
set "PROJECT_DIR=C:\All_GitHub_Desktop\agentic-debugger-for-eclipse"
```

Edit the two assignments in `scripts\windows\deploy-eclipse-plugin.bat` when paths differ. **The verification script derives the repository path from its own location** and does not require those hard-coded values. Run commands from the **repository root**, the folder containing the parent `pom.xml`.

## 3. Deploy to normal Eclipse with the Windows script

The Windows script builds and installs the updated feature **into the Eclipse installation specified by `ECLIPSE_DIR`**; this is not a per-workspace installation.

1. Save any work and **fully close the target Eclipse IDE**. Also close PDE Runtime Eclipse so it cannot occupy the bridge port.
2. Open **Command Prompt** in the repository root and run:

   ```cmd
   scripts\windows\deploy-eclipse-plugin.bat
   ```

3. Answer the script's confirmation prompt only after Eclipse is closed.
4. The script performs `mvn clean package`, checks the currently installed feature, removes **our existing feature** if present, installs the freshly built local p2 repository using `eclipsec.exe`, verifies installation, and normally starts Eclipse (`START_ECLIPSE=YES`). Logs are written under `deploy-logs/`.
5. In the launched Eclipse, wait for startup and workspace compilation. Check **Help → About Eclipse IDE → Installation Details → Installed Software** for Agentic Debugger. Verify the expected feature version rather than assuming that a Maven build updates the installed binary.

**Important:** An uninstall followed by a failed reinstall can leave the plugin absent; use the deployment log to diagnose this before using the debugger. Use a test installation rather than an IDE containing valuable unrelated customizations. The script has not been executed in the current execution environment; treat its Windows behavior as a verification step.

## 4. Manual Eclipse installation from an update-site ZIP

Use this path instead of the batch installer when you prefer interactive installation:

1. Build from the **repository root**:

   ```cmd
   mvn clean package
   ```

2. Locate the **generated update-site ZIP** in `eclipse-update-site\target\`. This is **not** the full repository source ZIP.
3. Open the normal Eclipse IDE and choose **Help → Install New Software → Add → Archive**.
4. Choose that update-site ZIP, supply a name such as `Agentic Debugger for Eclipse IDE`, select the installable feature, proceed through the installation dialogs, and restart Eclipse.
5. Confirm the installation under **Help → About Eclipse IDE → Installation Details → Installed Software**.

Manual installation may not replace an existing identical Eclipse feature version. For local iterative testing, prefer the deployment script's uninstall/reinstall workflow and validate the installed binary with `-Pinstalled-e2e`.

## 5. Verify the plugin and Claude connection

With the target **normal Eclipse running**:

1. Check **Window → Preferences → Agentic Debugger** for plugin preferences.
2. If configuring Claude Code for the first time, accept the plugin's first-run registration prompt or use its **Configure Claude Code / Verify registration** preference actions.
3. In Eclipse Terminal (or a separate terminal with Claude available), run `claude` and use `/mcp` to check the `eclipse-debugger` server registration.
4. Ask Claude to call `eclipse_ping`; the expected bridge response is `PONG`.

For **Java-only feature verification** you do not need to launch Claude. The bridge verifier connects directly to the already running Eclipse. Keep only **one instance** active on port `8765`.

## 6. Import the Phase 1 Java fixture

1. In the **same normal Eclipse** receiving the plugin, choose **File → Import → Maven → Existing Maven Projects**.
2. Browse to `examples\phase-one-debugging` under the repository root.
3. Select the detected `pom.xml`, finish, and allow Maven dependency resolution, project import, and Eclipse Java compilation to complete.
4. Verify the project appears in **Package Explorer** and that source packages are recognized as Java, not ordinary text files. Do not silently rename the project: the verification runner expects the project identity documented in `phase1-java-test-runner.md` (or override it via `-Dagentdebug.phase1.project=...`).
5. The JUnit fixture includes a deliberate failing test; that is **expected when executed**, but project configuration/classpath errors are not. Do not remove the bug or annotate it with the root cause.

The fixture is **not** the older automatically provisioned `AgenticDebuggerCalculatorE2E` project. The installed baseline E2E runner creates its own sample; Phase 1 tests require the additional fixture import above.

## 7. Run installed-plugin regression and all seven feature checks

Run these in **Command Prompt from the repository root**, keeping the installed normal Eclipse open:

**A. Existing installed-plugin regression (including binary integrity and Claude E2E):**

```cmd
mvn verify -Pinstalled-e2e
```

This profile requires Claude Code and can incur agent usage charges. The runner does **not** launch or install Eclipse.

**B. All seven Phase 1 bridge tests (Java only; no Claude):**

```cmd
scripts\windows\verify-phase1-eclipse.bat
```

**C. Isolate an individual feature:**

```cmd
scripts\windows\verify-phase1-eclipse.bat conditional
```

The script accepts `all`, `conditional`, `exception`, `junit`, `variables`, `trace`, `pause-restart`, and `filters`. It invokes the Maven `phase1-bridge` profile and propagates a nonzero failure exit code. The equivalent direct command is:

```cmd
mvn verify -Pphase1-bridge -Dagentdebug.phase1.scenario=all
```

| Scenario | What the live Eclipse test is intended to confirm |
|---|---|
| `conditional` | Breakpoint condition recorded and stop occurs on the matching order |
| `exception` | Caught/uncaught Java exception breakpoint behavior and throwing stack |
| `junit` | Individual test launch into Eclipse debugger |
| `variables` | Read-only nested field/array expansion and bounded results |
| `trace` | Tracepoint output without normal application suspension |
| `pause-restart` | Pause behavior and restart limited to investigation-owned session |
| `filters` | Configured step filters applied to target debug session |

**Passing `mvn clean package` is not sufficient**: Eclipse JDT functionality must be tested in a live installed IDE. The Java-only verifier does **not** prove Claude will choose these capabilities by itself.

## 8. Validate Claude invocation of the features

After the direct bridge checks pass, use the **explicit prompts first**, then symptom-only prompts in [Phase 1 Claude scenarios](phase1-claude-scenarios.md). Start fresh sessions and reset only investigation-owned debugger state between trials.

For example, the conditional-breakpoint explicit prompt requests that Claude set `orderId == 1057`, launch the fixture, inspect the matching values, and complete the investigation. The natural-language prompt describes a single problematic order without naming a breakpoint type. **Record both the MCP tool calls and actual Eclipse state.** Do not count a correct explanation found by reading source code as proof of debugger integration.

The debugging **skill is not installed or needed** for this phase. The tests establish the pre-skill behavioral baseline.

## 9. Review evidence and interpret failures

| Evidence | Location | Meaning |
|---|---|---|
| Deployment log | `deploy-logs/` | Maven/p2 execution and installation outcomes |
| Existing installed E2E | `e2e-runner/target/e2e-report/` | `installed-binary.txt`, `preflight.txt`, `trace.jsonl`, `result.txt` |
| Phase 1 direct bridge result | `evaluation/reports/phase1-live.json` | Scenario-level result and bridge request/response evidence |
| Claude prompt observations | Record separately with each scenario | Tool-selection, actual suspension/variables, correctness, latency and tool calls |

Use **PASS** only when the actual test assertions succeed. If the bridge verifier says PASS but visible Eclipse behavior disagrees, investigate the mismatch rather than assuming the tool response is sufficient. Do not promote the v1.5 candidate until **Maven/Tycho build, installed baseline regression, all seven live bridge tests, and ownership-safety regression** pass. Claude prompt selection is reported separately because it is nondeterministic.

## 10. Troubleshooting and cleanup

| Problem | Checks |
|---|---|
| `mvn` or `java` not recognized | Install/configure JDK and Maven; confirm `java -version` and `mvn -version` |
| Eclipse plugin missing after deploying | Inspect `deploy-logs/`; check `Installed Software`; ensure `ECLIPSE_DIR` points to the intended instance |
| Bridge connection fails | Keep installed Eclipse running, close other Eclipse instances, verify `eclipse_ping`; check for port `8765` conflict |
| Stale plugin reported | The source build is newer than loaded Eclipse plugin; close Eclipse and redeploy, then rerun installed E2E |
| JUnit test launch unavailable | Confirm JDT JUnit support, fixture Maven classpath, imported Eclipse project, and correct test method discovery |
| Breakpoint does not suspend | Confirm the targeted source line, matching Java launch, running/suspended state, and condition expression scope |
| Tracepoint output not visible | Inspect Eclipse Console and the verifier's `trace` evidence; do not mark the feature verified based on a successful setup response alone |
| Maven fixture builds but Phase 1 verifier fails | Review `phase1-live.json` and compare tool response to real Eclipse state; capture exact failure |
| Claude identifies bug without MCP calls | Correct static diagnosis is **not** the same as successful agentic debugging; use explicit prompt and inspect trace |

To uninstall the feature manually use **Help → About Eclipse IDE → Installation Details → Installed Software → Uninstall**. Remove managed Claude registration only through the plugin's preferences where possible; do not overwrite unrelated entries in `.claude.json`. Normal/Controlled modes must preserve user-owned sessions and breakpoints.

## Verification boundary

This guide documents the intended workflow and the actual repository scripts. The scripts and Phase 1 additions have **not** been run against the user's Windows Eclipse installation in this environment. Report all live results explicitly; never infer them from source inspection alone.
