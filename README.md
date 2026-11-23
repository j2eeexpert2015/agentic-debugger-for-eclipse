# Agentic Debugger for Eclipse IDE

Debug Java, Spring Boot, and JUnit applications in Eclipse IDE using Claude Code and the Model Context Protocol (MCP). The plugin uses Eclipse's Java debugger for breakpoints, execution control, stack frames, variable inspection, and investigation lifecycle management.

**Project status (23 November 2026):** The repository-integrated Java E2E runner passed **19/19 direct installed-Eclipse scenarios in a single Windows run**, all reporting successful cleanup. This verifies the named Java, JUnit, Spring Boot, stepping and isolation scenarios—not autonomous Claude skill acceptance, Spring Tools compatibility or Linux/macOS operation. The optional skill exists, but activation and comparative verification remain incomplete.

## Contents

1. [Download or Build and Install the Eclipse Plugin](#1-download-or-build-and-install-the-eclipse-plugin)
2. [Features, Implementation and Test Status](#2-features-implementation-and-test-status)
3. [Feature Demonstration Videos](#3-feature-demonstration-videos)
4. [Limitations and Pending Work](#4-limitations-and-pending-work)
5. [For Developers — Architecture, Development and Testing](#5-for-developers--architecture-development-and-testing)
6. [Additional Documentation](#6-additional-documentation)

## 1. Download or Build and Install the Eclipse Plugin

### Requirements

- Eclipse IDE with Java Development Tools (JDT)
- JDK 21 or newer and an installed, authenticated Claude Code CLI
- **Only for building from source:** Apache Maven, Git, and access to Maven/Eclipse dependencies

### Step 1 — Get the installable ZIP

**Option A — Download from GitHub Releases (recommended when a release is published)**

Go to [GitHub Releases](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases) and download the Eclipse update-site ZIP from the latest release's **Assets** (expected naming: `agentic-debugger-update-site-*.zip`). **If no release asset is published yet, use Option B.** GitHub's automatic "Source code (zip)" is not an installable Eclipse update site.

**Option B — Build from source**

From Windows Command Prompt (CMD):

```cmd
git clone https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse.git
cd agentic-debugger-for-eclipse
mvn clean package
```

The successful build produces the installable ZIP under `eclipse-update-site/target/`. With the current Maven project version, the expected filename is:

```text
eclipse-update-site/target/agentic-debugger-update-site-1.0.0-SNAPSHOT.zip
```

Use the ZIP from your own successful build; the filename changes when the Maven version changes. **Use `mvn clean package`, not `mvn clean compile`**: `compile` does not create the update-site ZIP. The standard build packages artifacts but does **not** start Eclipse, install the plugin, register Claude Code, or invoke the optional installed-Eclipse/Claude E2E profiles. Maven may execute normal lifecycle tests and download dependencies.

### Install in Eclipse

1. Open **Help → Install New Software → Add → Archive**.
2. Select the **downloaded release ZIP or locally generated update-site ZIP**.
3. Select **Agentic Debugger for Eclipse IDE**, complete the wizard, and **restart Eclipse**.
4. Open **Window → Preferences → Agentic Debugger → Configure Claude Code**, then verify the registration.
5. Keep Eclipse running. Start `claude` in a terminal with your Java project as the working directory, check `/mcp` for `eclipse-debugger`, and ask Claude to call `eclipse_ping` (expected response: `PONG`).

### Start debugging

Open/import the Java project in Eclipse. For example:

> Debug my Java application in Eclipse. Find why the calculated total is wrong. Inspect actual runtime values, explain the root cause, do not modify source, and clean up only the resources created by this investigation.

**Debugger modes:** `NORMAL` (default) removes the investigation's newly created breakpoints and terminates its newly launched session when `finish_debug_investigation` is called. `CONTROLLED` intentionally preserves debugger state for further inspection. Both modes must preserve unrelated user-owned sessions and breakpoints; no automatic cleanup occurs if Claude does not finish the investigation. For applications requiring custom arguments, Spring profiles, or environment variables, configure an appropriate Eclipse launch configuration first.

**Optional Claude Code skill:** The plugin works without a skill. To use the separate skill in all Claude Code projects, copy the **entire** [`skills/eclipse-debugger/`](skills/eclipse-debugger/) directory, including its `references/` folder, to `%USERPROFILE%\.claude\skills\eclipse-debugger\`. Back up an existing installed skill before replacing it; start a new Claude session and confirm `/eclipse-debugger` is available. Installing a skill does **not** install or register the MCP server.

## 2. Features, Implementation and Test Status

**Latest direct E2E evidence:** **19/19 PASS**, with `cleanup=true` for every scenario, on 23 November 2026. This includes a dedicated `main()` → `sum()` → `main()` **Step Into/Step Out** assertion, two discovered Spring Boot mains, and two simultaneous Boot JVMs with verified owned-session cleanup. See [Java E2E commands](#52-preferred-java-e2e-runner) and [remaining priorities](#4-limitations-and-pending-work). The Claude/skill evaluation gates remain separate.

**Status interpretation:** **PASS** = verified for named scenario; **PARTIAL** = some scenario coverage or evidence missing; **NOT CONFIRMED** = insufficient agent-execution evidence; **NOT TESTED** = no completed test for that scope. Direct bridge tests establish debugger functionality independently of Claude; Claude tests additionally evaluate tool choice, runtime evidence, diagnosis, and cleanup.

| Feature | Implementation (main files) | Direct/Eclipse test | Claude/skill test | Current status |
|---|---|---|---|---|
| Java main discovery and launch | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | [ClaudeE2ERunner.java](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/ClaudeE2ERunner.java), [PhaseOneBridgeVerifier.java](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseOneBridgeVerifier.java) | Java scenario in [scenarios.json](evaluation/skill-automation/scenarios.json) | **PASS** — tested workflows |
| Line and conditional breakpoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | `PhaseOneBridgeVerifier.java` (`conditional`) | `Java_ConditionalBreakpoint_RootCause` | **PASS** |
| Exception breakpoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java) | `PhaseOneBridgeVerifier.java` (`exception`) — PASS | `Java_ExceptionBreakpoint` — PARTIAL | **PARTIAL** — agent's original throw-site proof pending |
| Stepping: Into / Over / Return | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | Core/bridge verification | `Java_StepInto_StepOver_StepReturn` — PASS | **PASS** (dedicated Java stepping acceptance also passed) |
| Stack frames, locals, nested objects | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | `PhaseOneBridgeVerifier.java` (`variables`) — PASS | Java/JUnit runtime scenarios | **PASS** — tested cases |
| JUnit 5 single-method debugging | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | `PhaseOneBridgeVerifier.java` (`junit`); [OrderCalculatorTest.java](examples/phase-one-debugging/src/test/java/org/learningfromexperience/debugfixtures/OrderCalculatorTest.java) | `JUnit_FailingTest_RootCause` — PASS | **PASS** — tested method selection |
| Spring Boot launch and request debugging | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | [PhaseTwoBridgeVerifier.java](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseTwoBridgeVerifier.java) — direct tests PASS | `SpringBoot_HTTPRequest_RootCause` — NOT RUN in latest skill suite | **PARTIAL** — broader acceptance pending |
| Tracepoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java) | `PhaseOneBridgeVerifier.java` (`trace`) — PASS | `Skill_TracepointConsent` — PASS (consent behavior) | **PASS** — tested scopes |
| Pause, resume, restart | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | `PhaseOneBridgeVerifier.java` (`pause-restart`) — PASS | Broader autonomous selection not fully established | **PASS** direct / **PARTIAL** agent coverage |
| Step filters | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | `PhaseOneBridgeVerifier.java` (`filters`) — PASS | Independent comprehensive skill coverage pending | **PASS** direct |
| Investigation ownership and cleanup | [DebugInvestigationPolicy.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/DebugInvestigationPolicy.java) | [OwnershipChecks.java](e2e-runner/ownership-tests/OwnershipChecks.java); `PhaseTwoBridgeVerifier.java`; `PendingAcceptanceVerifier.java`; `BootGapVerifier.java` | `Skill_ExistingSessionProtection` — PASS | **PASS** — tested isolation subset |
| Claude Code registration | [ClaudeRegistration.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/integration/ClaudeRegistration.java), [ClaudeIntegration.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/integration/ClaudeIntegration.java) | [ClaudeRegistrationTest.java](eclipse-plugin/tests/org/learningfromexperience/eclipse/agentdebug/integration/ClaudeRegistrationTest.java) | MCP ping/installed workflow | **PASS** — tested environment |
| Optional unified debugging skill | [SKILL.md](skills/eclipse-debugger/SKILL.md) and [references](skills/eclipse-debugger/references/) | Not applicable (instructions, not an MCP capability) | `Skill_ExplicitActivation`, `Skill_AutomaticActivation` — NOT CONFIRMED; other runtime/safety cases passed | **PARTIAL** — activation evidence pending |

Claude scenario IDs above are defined in [`evaluation/skill-automation/scenarios.json`](evaluation/skill-automation/scenarios.json) and executed by [`scripts/windows/test-skill.ps1`](scripts/windows/test-skill.ps1). Direct Java scenarios are implemented in [`PhaseOneBridgeVerifier.java`](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseOneBridgeVerifier.java).

### Not implemented / deferred

| Feature | Status |
|---|---|
| Run to Line | **NOT IMPLEMENTED** |
| Modify runtime variables | **NOT IMPLEMENTED** |
| Remote JVM attach (JDWP), field watchpoints, Hot Code Replace | **DEFERRED** — no verified MCP support |
| Specialized JPA/transaction diagnostics | **DEFERRED** |

## 3. Feature Demonstration Videos

*Placeholder: links will be added when the demonstrations are published.*

| Demonstration | Video |
|---|---|
| Installation and Claude Code integration | To be added |
| Java breakpoints, stepping, and variables | To be added |
| JUnit debugging | To be added |
| Spring Boot request debugging | To be added |
| Debugger-session safety | To be added |

## 4. Limitations and Pending Work

**Latest baseline:** **19/19 Java direct E2E tests PASS** in one Windows run (23 November 2026), all with successful cleanup. These tests do not establish complete agent-driven, Spring Tools, or cross-platform acceptance.

| Priority | Remaining work | Completion evidence required |
|---|---|---|
| **P0** | Preserve reproducible test evidence | Record tested commit/working tree, installed bundle, JDK, test date, command and six JSON reports before release sign-off. |
| **P0** | Spring Boot negative startup cases | Add Java E2E tests for failed startup, timeout and port/configuration errors; verify no false readiness or unintended cleanup. |
| **P0** | Advanced ownership isolation | Extend the passing two-service test to user-owned breakpoints, markers, saved launch configurations, ambiguous targets and failure-path cleanup. |
| **P1** | Spring Boot/JUnit gaps | Verify selected `@SpringBootTest` method, context-start failure, assertion failure, disabled/complex methods and safe rejection of unsupported selections. |
| **P1** | Claude skill and autonomous debugging | Independently verify explicit/automatic skill activation, exception throw-site evidence, approved HTTP investigation and symptom-only diagnosis; measure no-skill versus skill runs. |
| **P1** | Spring Tools compatibility | Test an Eclipse installation with Spring Tools-specific launch delegates; plain Eclipse JDT verification is not sufficient. |
| **P2** | Platform independence and legacy tooling | Prove the Java runner on Linux/macOS; migrate the remaining PowerShell-only skill/ownership/deploy capabilities before deleting their scripts. |
| **P3 — Deferred** | Advanced debugger capabilities | Remote JDWP attach, watchpoints, Run to Line, Hot Code Replace, variable mutation, JPA/transaction and advanced concurrent request diagnostics. |

**Scope:** Existing Java E2E `two-services` tests prove fixture JVM preservation, **not** every user-marker/configuration permutation. Do not confuse direct Eclipse tests with Claude agent tests. Preserve user sessions, breakpoints, and HTTP consent on every change.

## 5. For Developers — Architecture, Development and Testing

Use a **dedicated Eclipse installation and disposable workspace**, not an IDE containing important work. Use the **repository-integrated Java E2E runner** for the 19 direct scenarios. The legacy Windows scripts and specialized Claude skill harnesses are retained only where they provide additional coverage. The test runner is separate from the user installation in Section 1.

### 5.1 Architecture and module responsibilities

**Execution path:** Claude Code → MCP stdio server → authenticated local Eclipse bridge → Eclipse operations/services → Eclipse JDT debugger → Java/JUnit/Spring Boot JVM. The optional skill supplies Claude guidance; correctness and session protections belong in Java code, not in the skill.

| Module / component | Source entry points | What it owns / where to start changing code |
|---|---|---|
| Eclipse plugin lifecycle | [plugin.xml](eclipse-plugin/plugin.xml), [Activator.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/Activator.java), [AgentDebugStartup.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/AgentDebugStartup.java) | Eclipse startup and lifecycle, bridge initialization, first-run UI |
| Authenticated bridge | [EclipseBridgeServer.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/bridge/EclipseBridgeServer.java) | Loopback request transport, authentication, dispatch boundary |
| Eclipse operation router | [EclipseAgentOperations.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseAgentOperations.java) | Maps bridge commands to Eclipse JDT operations: launch, session, frames, variables, stepping, evaluate, breakpoint actions |
| Debug sessions | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | Debug session discovery and JDT session information |
| Breakpoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java) | Line/conditional/exception breakpoints, tracepoints, marker ownership |
| Investigation safety | [DebugInvestigationPolicy.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/DebugInvestigationPolicy.java) | NORMAL/CONTROLLED investigation ownership, resource cleanup constraints |
| Claude integration | [ClaudeIntegration.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/integration/ClaudeIntegration.java), [ClaudeRegistration.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/integration/ClaudeRegistration.java) | MCP registration and local Claude integration |
| MCP server | [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | MCP tool names/schemas, parameter mapping and bridge client; update both MCP and Eclipse dispatch when adding a tool |
| Packaging | [root POM](pom.xml), [plugin POM](eclipse-plugin/pom.xml), [feature](eclipse-feature/), [update site](eclipse-update-site/) | Tycho build, embedded MCP JAR, installable Eclipse ZIP |
| Optional skill | [SKILL.md](skills/eclipse-debugger/SKILL.md), [skill references](skills/eclipse-debugger/references/) | Claude strategy only; it must remain optional and removable |
| Test infrastructure | [Java runner](dev-e2e/DevE2E.java), [E2E Java sources](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/), [ownership tests](e2e-runner/ownership-tests/), [Windows scripts](scripts/windows/), [skill scenarios](evaluation/skill-automation/scenarios.json) | Direct bridge tests, isolated ownership checks, Claude trials and result evaluation |

**Modification rule:** For a new debugging capability, examine MCP tool contracts, bridge routing, Eclipse JDT operation implementation, investigation safety, and tests together. Do not add a skill instruction as a substitute for an actual MCP operation.

### 5.2 Preferred Java E2E runner

The maintained entry point is [`dev-e2e/DevE2E.java`](dev-e2e/DevE2E.java) ([full command guide](dev-e2e/README.md)). **Configure `dev-e2e/e2e.properties` once** using the checked-in example (`repository`, `eclipse`, `workspace`, `bridgePort`). Use a dedicated workspace and non-default port. The Java runner reuses the matching Eclipse bridge or starts the dedicated Eclipse instance automatically. `test` does **not** deploy; `all` builds, installs, starts Eclipse and tests, and requires the target Eclipse installation to be **closed first**.

```cmd
:: All 19 direct E2E scenarios (approved local fixture HTTP requests only)
java dev-e2e/DevE2E.java test --approve-fixture-post

:: Selected group(s)
java dev-e2e/DevE2E.java test --groups phase1,acceptance,boot-gaps

:: One individual scenario (no HTTP approval needed)
java dev-e2e/DevE2E.java test --tests boot-gaps:two-services

:: Multiple named scenarios
java dev-e2e/DevE2E.java test --tests phase1:trace,acceptance:core-step-into-out

:: Full clean build/deployment and direct E2E (close target Eclipse first)
java dev-e2e/DevE2E.java all --approve-fixture-post
```

| Group | Count | Scenarios / evidence report |
|---|---:|---|
| `phase1` | 7 | Conditional, exception, JUnit, nested variables, trace, pause/restart, filters — `phase1-java-live.json` |
| `phase2` | 2 | Validation HTTP 400 and owner/consent checks — `phase2-java-live.json` |
| `boot` | 2 | Boot discovery and successful readiness — `boot-direct-java-live.json` |
| `boot-quote` | 1 | Real request, `percent=10`, `rate=0`, HTTP 200 total `120.00` versus `108.00` — `boot-quote-java-live.json` |
| `acceptance` | 4 | Target selection, expired owner, borrowed session, Step Into/Out — `acceptance-java-live.json` |
| `boot-gaps` | 3 | Two mains, invalid JUnit method, two services — `boot-gaps-java-live.json` |
| **Total** | **19** | **19/19 PASS on Windows (23 November 2026)** |

Reports are generated in ignored `evaluation/reports/`. All 19 tests reported `cleanup=true`. The old PowerShell **HTTP fixture client** was removed after Java parity passed; unrelated PowerShell skill and ownership tools remain. This Java path requires **no Python**. Agent/skill tests are distinct and are **not** included in the 19.

### 5.3 Install prerequisites

- **Preferred Java E2E:** JDK **21+**, Maven and an Eclipse IDE installation with JDT/JUnit tooling (the verified Windows run used JDK 25). Git is recommended for source updates.
- **Retained legacy Windows tests only:** Windows CMD/PowerShell 5.1.
- Claude Code CLI installed and authenticated **for Claude-driven tests**. Direct bridge tests do not require the skill or Claude decision-making.
- Clone the repository (Section 1), then run all commands below from its **root directory**.

### 5.4 Legacy Windows-only path configuration

**Only for `RUN-WINDOWS-TESTS.cmd` and retained PowerShell tooling:** edit [`scripts/windows/dev-settings.cmd`](scripts/windows/dev-settings.cmd), or set equivalent environment variables. The preferred Java runner uses `dev-e2e/e2e.properties` instead. The defaults below reflect the original Windows development machine, not universal installation paths.

| Setting | Default in script | Developer action |
|---|---|---|
| `AGENTDEBUG_ECLIPSE_DIR` | `C:\All_Softwares\eclipse` | **CHECK / CHANGE** to the directory containing `eclipse.exe` |
| `AGENTDEBUG_WORKSPACE` | `%LOCALAPPDATA%\AgenticDebugger\dev-workspace` | **CHECK / CHANGE** to an isolated, disposable Eclipse workspace; do not use your personal workspace |
| `AGENTDEBUG_PORT` | `21765` | **CHECK** this unused, non-default local bridge port; plugin and test scripts must use the **same** port |
| `AGENTDEBUG_CLAUDE_EXE` | `%USERPROFILE%\.local\bin\claude.exe` | **CHECK / CHANGE** to the actual Claude Code executable for agent tests |
| `AGENTDEBUG_FIXTURE_SOURCE` | Derived from repository `examples/phase-one-debugging` and `examples/spring-boot-debugging` | Usually **no edit**; verify both directories exist and are imported/prepared in the test workspace |
| `AGENTDEBUG_REPO_DIR` | Automatically resolved from script location | **No edit**; works when the checkout is moved |

**Also verify:** `java -version`, `mvn -version`, and (if running Claude tests) `claude --version`. JDK 21+ is required; the recorded Windows E2E run used JDK 25.

**Separate legacy installer:** [`scripts/windows/deploy-eclipse-plugin.bat`](scripts/windows/deploy-eclipse-plugin.bat) has two **independently hardcoded** values, `ECLIPSE_DIR` and `PROJECT_DIR`, near its top. **If you choose that script instead of `RUN-WINDOWS-TESTS.cmd deploy`, edit BOTH paths first.** The retained `RUN-WINDOWS-TESTS.cmd` workflow uses `dev-settings.cmd` and does not require editing those legacy paths.

Check the effective configuration:

```cmd
RUN-WINDOWS-TESTS.cmd config
```

Confirm the printed **Eclipse path, workspace, port, and Claude path** before deployment.

### 5.5 Legacy Windows deployment and harness checks

**Close any Eclipse instance using the target installation before deploying** (the p2 installer must not update a running Eclipse installation).

```cmd
RUN-WINDOWS-TESTS.cmd deploy
RUN-WINDOWS-TESTS.cmd doctor
RUN-WINDOWS-TESTS.cmd skill selftest
RUN-WINDOWS-TESTS.cmd skill preflight
```

- `deploy` builds and installs the latest plugin, starts Eclipse in the configured disposable workspace, and checks readiness; you do **not** also need to run `mvn clean package` beforehand.
- `doctor` confirms the correct Eclipse installation, workspace, bridge, installed bundle, and Java/JUnit fixture discovery. It starts the intended test instance if needed, but refuses ambiguous/wrong instances.
- `skill selftest` checks the harness parser on synthetic evidence; `skill preflight` verifies tool/skill prerequisites. These are **not** live debugger acceptance tests.
- The test fixtures live at [`examples/phase-one-debugging/`](examples/phase-one-debugging/) and [`examples/spring-boot-debugging/`](examples/spring-boot-debugging/). If the doctor reports an undiscovered project/test in a manually configured workspace, import these via **File → Import → Maven → Existing Maven Projects**, let Eclipse finish building, and rerun `doctor`.

### 5.6 Legacy Windows direct tests (optional compatibility path)

If specifically checking the retained legacy harness, run its installed E2E and seven Java bridge scenarios:

```cmd
RUN-WINDOWS-TESTS.cmd test
```

Or run a focused Java scenario:

| Scenario | Command suffix after `RUN-WINDOWS-TESTS.cmd` |
|---|---|
| Conditional breakpoint | `conditional` |
| Exception breakpoint | `exception` |
| JUnit | `junit` |
| Nested variables | `variables` |
| Tracepoint | `trace` |
| Pause/restart | `pause-restart` |
| Step filters | `filters` |

For example: `RUN-WINDOWS-TESTS.cmd conditional`. This legacy path is not required for the preferred 19-scenario Java run.

**Spring Boot direct verifier:** Only after verifying the **disposable localhost fixture**, explicitly authorize fixture HTTP POSTs in the current CMD session:

```cmd
set "AGENTDEBUG_APPROVE_FIXTURE_POST=YES"
RUN-WINDOWS-TESTS.cmd phase2
```

This authorizes the controlled Phase 2 direct test, **not arbitrary network requests**. Use `set "AGENTDEBUG_APPROVE_FIXTURE_POST="` to clear the approval afterward. The direct Phase 2 verifier covers `validation` and `ownership` test cases; it does not establish complete Spring Boot acceptance by itself.

### 5.7 Dedicated ownership and session-isolation tests

The normal installed regression does **not** replace the specialized [ownership test runner](e2e-runner/ownership-tests/run.ps1). Run it against the **disposable** workspace only, and close Eclipse from the configured test installation first. The workspace must already have been prepared by `deploy`, including the `phase-one-debugging` fixture marker.

From PowerShell (substitute the paths/port configured in `dev-settings.cmd`):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "e2e-runner\ownership-tests\run.ps1" -EclipseDirectory "C:\All_Softwares\eclipse" -Workspace "$env:LOCALAPPDATA\AgenticDebugger\dev-workspace" -Port 21765
```

The runner builds an ownership test bundle, installs it into the **test Eclipse** `dropins/`, redeploys/starts that instance, and checks `ownership-results.json` in the test workspace. **This modifies the disposable installation and must not be run against a personal Eclipse installation.** It is separate from the Claude skill's existing-session-protection scenario.

### 5.8 Claude-driven tests (agent reasoning and cleanup)

The optional skill, when used, must first be installed as described in Section 1. Check skill visibility separately from MCP connectivity.

```cmd
RUN-WINDOWS-TESTS.cmd skill smoke
RUN-WINDOWS-TESTS.cmd skill full
```

`skill full` executes eligible scenarios in the disposable test environment and records MCP/Claude traces. Some scenarios are **NOT_RUN** unless separately authorized: the Spring Boot HTTP skill case requires fixture approval, while a disconnected-bridge case requires independent isolation. Do not interpret `NOT_RUN` as PASS. Claude-driven tests may consume subscription usage or API credits.

To run one named scenario:

```cmd
RUN-WINDOWS-TESTS.cmd skill selected Java_ConditionalBreakpoint_RootCause
```

The runner's source-of-truth scenario definitions are in [`evaluation/skill-automation/scenarios.json`](evaluation/skill-automation/scenarios.json). Advanced approval/isolation switches are defined in [`scripts/windows/test-skill.ps1`](scripts/windows/test-skill.ps1); do not use them without checking their safety conditions.

**Checker regression:** After you have existing Claude `.jsonl` recordings, run:

```cmd
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\windows\test-scoring.ps1"
```

This script validates scoring against previously recorded traces; it is **not** a first-run prerequisite and can fail when recordings do not exist.

### 5.9 Results and troubleshooting

For the **current Java runner**, use the six `*-java-live.json` files listed in 5.2. The `phase1-live.json` and `phase2-live.json` names below belong to earlier or legacy runners; do not overwrite a successful new report with old evidence.


| Location | Output |
|---|---|
| `evaluation/reports/phase1-live.json` | Direct Java debugger results |
| `evaluation/reports/phase2-live.json` | Direct Spring Boot verification |
| `evaluation/reports/skill-automation/<run-id>/trial-results.csv` | Claude scenario verdicts |
| `evaluation/reports/skill-automation/<run-id>/verified-results.json` | Evidence-check details (when generated) |
| `evaluation/reports/skill-automation/<run-id>/comparison.md` | Claude run summary |

Verdicts include **PASS**, **FAIL**, **PARTIAL**, **NOT_CONFIRMED**, **NOT_RUN**, and **BLOCKED**. A bridge/project readiness failure is an environment failure, not proof that a debugger feature is broken. If Eclipse retains an old installed bundle, check its version and redeploy to the disposable installation; Eclipse p2 can reuse a previously installed version. Keep raw traces and credentials out of Git: debug reports can contain application variables and request details.

### 5.10 Development workflow and verification checklist

1. Locate the relevant module in **5.1** and inspect the MCP ↔ bridge ↔ JDT contract and existing tests before editing.
2. Preserve authentication, explicit consent, existing launch configurations, user-owned breakpoints/sessions, and NORMAL/CONTROLLED cleanup semantics. Do not silently alter an unrelated debug target.
3. Make the smallest change and add/adjust corresponding deterministic tests; update skill scenarios only when agent behavior changes.
4. Run `mvn clean package`. For changes to the installed Eclipse plugin/MCP distribution, close the dedicated Eclipse installation and run `java dev-e2e/DevE2E.java all --approve-fixture-post` (build, deploy and direct regression).
5. Run affected direct Java scenarios using `--groups`/`--tests`, then the combined 19-scenario Java regression. Run specialized ownership and Claude skill scenarios separately when the change affects them.
6. Report the **exact commands, installed bundle/source revision, test outputs, and PASS/FAIL/PARTIAL/NOT_RUN**. A successful Maven build is not evidence of live Eclipse acceptance. The Java `test` command covers 19 **direct** scenarios, not every Claude, Spring Tools, or environmental gate.

## 6. Additional Documentation

**Current sources of truth:** this README for implementation/status/priorities; [`dev-e2e/README.md`](dev-e2e/README.md) and executable Java verifiers for test commands; [`docs/development-roadmap.md`](docs/development-roadmap.md) for longer-term planning. Keep detailed technical/evidence records but do not treat their dated status statements as current.

| Purpose | Reference |
|---|---|
| Architecture and safety rules | [Architecture decisions](docs/decisions.md) · [Supported capabilities](docs/capabilities.md) |
| Test execution and scenario contracts | [Java E2E guide](dev-e2e/README.md) · [Spring Boot acceptance scenarios](docs/spring-boot-debugging-scenarios.md) · [Evaluation plan](docs/evaluation-plan.md) |
| Roadmap and deferred capabilities | [Development roadmap](docs/development-roadmap.md) · [Feature-ID tracker](docs/feature-roadmap.md) |
| Optional skill | [Skill source](skills/eclipse-debugger/SKILL.md) · [Skill-specific requirements](docs/phase3-skill-requirements-and-design.md) |
| Earlier verification evidence | [Phase 1 results](docs/phase1-implementation-status.md) · [Phase 2 verification history](docs/phase2-verification-consolidation.md) · [Spring Boot audit](docs/spring-boot-tool-audit.md) |
| Fixtures | [Java fixtures](examples/README.md) · [Spring Boot fixture](examples/spring-boot-debugging/README.md) |

**Documentation cleanup:** historical setup guides, initial implementation backlog, and old verifier snapshots may overlap with this README, but should be archived or removed **only after** checking cross-references and retaining unique test procedures/evidence. No documentation is automatically deleted by this package. The detailed keep/merge/archive recommendation is in the included `DOCUMENTATION-AUDIT.txt`.

---

Agentic Debugger for Eclipse IDE is an independent open-source project and is not affiliated with the Eclipse Foundation.
