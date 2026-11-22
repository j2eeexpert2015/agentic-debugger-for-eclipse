# Agentic Debugger for Eclipse IDE

Debug Java, Spring Boot, and JUnit applications in Eclipse IDE using Claude Code and the Model Context Protocol (MCP). The plugin uses Eclipse's Java debugger for breakpoints, execution control, stack frames, variable inspection, and investigation lifecycle management.

**Project status (23 November 2026):** Core Java debugging and seven advanced Java bridge scenarios have passed installed-Windows testing. Java/JUnit Claude scenarios have also passed selected live evaluations. Spring Boot direct debugging is demonstrated, but full Spring Boot and skill acceptance remain open. **A passing test applies to the named scenario, not every application or environment.**

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

**Status interpretation:** **PASS** = verified for named scenario; **PARTIAL** = some scenario coverage or evidence missing; **NOT CONFIRMED** = insufficient agent-execution evidence; **NOT TESTED** = no completed test for that scope. Direct bridge tests establish debugger functionality independently of Claude; Claude tests additionally evaluate tool choice, runtime evidence, diagnosis, and cleanup.

| Feature | Implementation (main files) | Direct/Eclipse test | Claude/skill test | Current status |
|---|---|---|---|---|
| Java main discovery and launch | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | [ClaudeE2ERunner.java](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/ClaudeE2ERunner.java), [PhaseOneBridgeVerifier.java](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseOneBridgeVerifier.java) | Java scenario in [scenarios.json](evaluation/skill-automation/scenarios.json) | **PASS** — tested workflows |
| Line and conditional breakpoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | `PhaseOneBridgeVerifier.java` (`conditional`) | `Java_ConditionalBreakpoint_RootCause` | **PASS** |
| Exception breakpoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java) | `PhaseOneBridgeVerifier.java` (`exception`) — PASS | `Java_ExceptionBreakpoint` — PARTIAL | **PARTIAL** — agent's original throw-site proof pending |
| Stepping: Into / Over / Return | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | Core/bridge verification | `Java_StepInto_StepOver_StepReturn` — PASS | **PASS** |
| Stack frames, locals, nested objects | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | `PhaseOneBridgeVerifier.java` (`variables`) — PASS | Java/JUnit runtime scenarios | **PASS** — tested cases |
| JUnit 5 single-method debugging | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | `PhaseOneBridgeVerifier.java` (`junit`); [OrderCalculatorTest.java](examples/phase-one-debugging/src/test/java/org/learningfromexperience/debugfixtures/OrderCalculatorTest.java) | `JUnit_FailingTest_RootCause` — PASS | **PASS** — tested method selection |
| Spring Boot launch and request debugging | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java), [DebugMcpServer.java](mcp-server/src/main/java/org/learningfromexperience/eclipse/agentdebug/mcp/DebugMcpServer.java) | [PhaseTwoBridgeVerifier.java](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseTwoBridgeVerifier.java) — direct tests PASS | `SpringBoot_HTTPRequest_RootCause` — NOT RUN in latest skill suite | **PARTIAL** — broader acceptance pending |
| Tracepoints | [EclipseBreakpointService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseBreakpointService.java) | `PhaseOneBridgeVerifier.java` (`trace`) — PASS | `Skill_TracepointConsent` — PASS (consent behavior) | **PASS** — tested scopes |
| Pause, resume, restart | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | `PhaseOneBridgeVerifier.java` (`pause-restart`) — PASS | Broader autonomous selection not fully established | **PASS** direct / **PARTIAL** agent coverage |
| Step filters | [EclipseDebugService.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/EclipseDebugService.java) | `PhaseOneBridgeVerifier.java` (`filters`) — PASS | Independent comprehensive skill coverage pending | **PASS** direct |
| Investigation ownership and cleanup | [DebugInvestigationPolicy.java](eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/debug/DebugInvestigationPolicy.java) | [OwnershipChecks.java](e2e-runner/ownership-tests/OwnershipChecks.java); `PhaseTwoBridgeVerifier.java` | `Skill_ExistingSessionProtection` — PASS | **PASS** — tested isolation cases |
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

| Priority | Item | What remains |
|---|---|---|
| **P1** | Skill activation evidence | Confirm explicit and automatic activation in actual Claude traces |
| **P1** | Exception root-cause scenario | Prove the original throwing frame, not just an exception mention |
| **P1** | Spring Boot acceptance | Complete approved Claude-driven HTTP scenario and outstanding Spring Boot/JUnit cases |
| **P1** | Existing-session usability | Improve safe targeting/selection of already-running applications beyond proven preservation tests |
| **P2** | Negative/safety scenarios | Finish disconnected-bridge and unsupported-feature evaluations |
| **P2** | Cross-environment coverage | Test more Spring Tools setups, multi-service scenarios, and complex JUnit cases |
| **P2** | New debugger actions | Evaluate Run to Line and authorized runtime variable modification |
| **P3 — Deferred** | Advanced MCP operations | Remote attach, field watchpoints, Hot Code Replace, deeper JPA/transaction diagnostics |

Never terminate an unrelated session, remove another investigation's breakpoint, or send side-effecting requests without explicit authorization. Public release and full acceptance remain pending.

## 5. For Developers — Architecture, Development and Testing

Use these steps to test changes against a **disposable Windows Eclipse installation/workspace**, not an IDE containing important work. The automation is separate from the manual ZIP installation in Section 1.

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
| Test infrastructure | [E2E Java sources](e2e-runner/src/main/java/org/learningfromexperience/agentdebug/e2e/), [ownership tests](e2e-runner/ownership-tests/), [Windows scripts](scripts/windows/), [skill scenarios](evaluation/skill-automation/scenarios.json) | Direct bridge tests, isolated ownership checks, Claude trials and result evaluation |

**Modification rule:** For a new debugging capability, examine MCP tool contracts, bridge routing, Eclipse JDT operation implementation, investigation safety, and tests together. Do not add a skill instruction as a substitute for an actual MCP operation.

### 5.2 Install prerequisites

- Windows CMD / Windows PowerShell 5.1, JDK **25**, Maven, Eclipse IDE with JDT/JUnit tooling, Git.
- Claude Code CLI installed and authenticated **for Claude-driven tests**. Direct bridge tests do not require the skill or Claude decision-making.
- Clone the repository (Section 1), then run all commands below from its **root directory**.

### 5.3 REQUIRED: check local paths and constants before running anything

**Edit [`scripts/windows/dev-settings.cmd`](scripts/windows/dev-settings.cmd) for your machine, or set equivalent environment variables before invoking the commands.** The defaults below reflect the original Windows development machine, not universal installation paths.

| Setting | Default in script | Developer action |
|---|---|---|
| `AGENTDEBUG_ECLIPSE_DIR` | `C:\All_Softwares\eclipse` | **CHECK / CHANGE** to the directory containing `eclipse.exe` |
| `AGENTDEBUG_WORKSPACE` | `%LOCALAPPDATA%\AgenticDebugger\dev-workspace` | **CHECK / CHANGE** to an isolated, disposable Eclipse workspace; do not use your personal workspace |
| `AGENTDEBUG_PORT` | `21765` | **CHECK** this unused, non-default local bridge port; plugin and test scripts must use the **same** port |
| `AGENTDEBUG_CLAUDE_EXE` | `%USERPROFILE%\.local\bin\claude.exe` | **CHECK / CHANGE** to the actual Claude Code executable for agent tests |
| `AGENTDEBUG_FIXTURE_SOURCE` | Derived from repository `examples/phase-one-debugging` and `examples/spring-boot-debugging` | Usually **no edit**; verify both directories exist and are imported/prepared in the test workspace |
| `AGENTDEBUG_REPO_DIR` | Automatically resolved from script location | **No edit**; works when the checkout is moved |

**Also verify:** `java -version`, `mvn -version`, and (if running Claude tests) `claude --version` in CMD. The JDK used for local verification should be 25.

**Separate legacy installer:** [`scripts/windows/deploy-eclipse-plugin.bat`](scripts/windows/deploy-eclipse-plugin.bat) has two **independently hardcoded** values, `ECLIPSE_DIR` and `PROJECT_DIR`, near its top. **If you choose that script instead of `RUN-WINDOWS-TESTS.cmd deploy`, edit BOTH paths first.** The recommended `RUN-WINDOWS-TESTS.cmd` workflow uses `dev-settings.cmd` and does not require editing those legacy paths.

Check the effective configuration:

```cmd
RUN-WINDOWS-TESTS.cmd config
```

Confirm the printed **Eclipse path, workspace, port, and Claude path** before deployment.

### 5.4 First run: deploy, verify readiness, and check the harness

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

### 5.5 Direct debugger tests (without Claude skill)

Run the installed E2E and all seven Java bridge scenarios:

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

For example: `RUN-WINDOWS-TESTS.cmd conditional`.

**Spring Boot direct verifier:** Only after verifying the **disposable localhost fixture**, explicitly authorize fixture HTTP POSTs in the current CMD session:

```cmd
set "AGENTDEBUG_APPROVE_FIXTURE_POST=YES"
RUN-WINDOWS-TESTS.cmd phase2
```

This authorizes the controlled Phase 2 direct test, **not arbitrary network requests**. Use `set "AGENTDEBUG_APPROVE_FIXTURE_POST="` to clear the approval afterward. The direct Phase 2 verifier covers `validation` and `ownership` test cases; it does not establish complete Spring Boot acceptance by itself.

### 5.6 Dedicated ownership and session-isolation tests

The normal installed regression does **not** replace the specialized [ownership test runner](e2e-runner/ownership-tests/run.ps1). Run it against the **disposable** workspace only, and close Eclipse from the configured test installation first. The workspace must already have been prepared by `deploy`, including the `phase-one-debugging` fixture marker.

From PowerShell (substitute the paths/port configured in `dev-settings.cmd`):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "e2e-runner\ownership-tests\run.ps1" -EclipseDirectory "C:\All_Softwares\eclipse" -Workspace "$env:LOCALAPPDATA\AgenticDebugger\dev-workspace" -Port 21765
```

The runner builds an ownership test bundle, installs it into the **test Eclipse** `dropins/`, redeploys/starts that instance, and checks `ownership-results.json` in the test workspace. **This modifies the disposable installation and must not be run against a personal Eclipse installation.** It is separate from the Claude skill's existing-session-protection scenario.

### 5.7 Claude-driven tests (agent reasoning and cleanup)

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

### 5.8 Results and troubleshooting

| Location | Output |
|---|---|
| `evaluation/reports/phase1-live.json` | Direct Java debugger results |
| `evaluation/reports/phase2-live.json` | Direct Spring Boot verification |
| `evaluation/reports/skill-automation/<run-id>/trial-results.csv` | Claude scenario verdicts |
| `evaluation/reports/skill-automation/<run-id>/verified-results.json` | Evidence-check details (when generated) |
| `evaluation/reports/skill-automation/<run-id>/comparison.md` | Claude run summary |

Verdicts include **PASS**, **FAIL**, **PARTIAL**, **NOT_CONFIRMED**, **NOT_RUN**, and **BLOCKED**. A bridge/project readiness failure is an environment failure, not proof that a debugger feature is broken. If Eclipse retains an old installed bundle, check its version and redeploy to the disposable installation; Eclipse p2 can reuse a previously installed version. Keep raw traces and credentials out of Git: debug reports can contain application variables and request details.

### 5.9 Development workflow and verification checklist

1. Locate the relevant module in **5.1** and inspect the MCP ↔ bridge ↔ JDT contract and existing tests before editing.
2. Preserve authentication, explicit consent, existing launch configurations, user-owned breakpoints/sessions, and NORMAL/CONTROLLED cleanup semantics. Do not silently alter an unrelated debug target.
3. Make the smallest change and add/adjust corresponding deterministic tests; update skill scenarios only when agent behavior changes.
4. Run `mvn clean package`. For changes to the installed Eclipse plugin/MCP distribution, close test Eclipse and run `RUN-WINDOWS-TESTS.cmd deploy`, then `doctor`.
5. Run the affected direct Java scenario(s), `RUN-WINDOWS-TESTS.cmd test`, the explicitly approved Spring Boot checks if relevant, and ownership tests for lifecycle changes. Run Claude scenarios separately for tool choice/diagnostic behavior.
6. Report the **exact commands, installed bundle/source revision, test outputs, and PASS/FAIL/PARTIAL/NOT_RUN**. A successful Maven build is not evidence of live Eclipse acceptance; no single command currently covers every acceptance gate.

## 6. Additional Documentation

The README is the entry point. The documents below preserve **detailed requirements, test evidence, scenarios, or design history** that should not be lost during consolidation. They are not additional prerequisites for normal plugin installation.

| Topic | Reference documents |
|---|---|
| Debugger capability matrix and architecture decisions | [Supported capabilities](docs/capabilities.md) · [Architecture decisions](docs/decisions.md) |
| Installation, development and Windows troubleshooting | [Eclipse installation and validation](docs/eclipse-installation-and-validation.md) · [Windows test quickstart](docs/windows-test-quickstart.md) |
| Java tests, scenarios and evidence | [Phase 1 Java test runner](docs/phase1-java-test-runner.md) · [Claude scenario acceptance](docs/phase1-claude-scenarios.md) · [Phase 1 implementation results](docs/phase1-implementation-status.md) |
| Spring Boot and JUnit scenarios and evidence | [Spring Boot scenarios](docs/spring-boot-debugging-scenarios.md) · [Tool audit](docs/spring-boot-tool-audit.md) · [Bridge verification](docs/phase2-bridge-verification.md) · [Verification consolidation](docs/phase2-verification-consolidation.md) |
| Unified skill design, usage and evaluation | [Skill design and requirements](docs/phase3-skill-requirements-and-design.md) · [Skill candidate verification snapshot](docs/phase3-skill-v0.1-validation.md) · [Skill installation and test guide](README-INSTALL-AND-TEST.md) · [Evaluation plan](docs/evaluation-plan.md) |
| Future work and research history | [Development roadmap](docs/development-roadmap.md) · [Feature tracker](docs/feature-roadmap.md) · [Codex backlog](docs/codex-implementation-backlog.md) · [Research sources](docs/research-sources.md) |
| Fixture-specific setup and test oracle | [Java fixtures](examples/README.md) · [Spring Boot fixture](examples/spring-boot-debugging/README.md) |

**Documentation note:** Some linked documents are historical snapshots and may describe earlier implementation or test status. The current README and executable source/test scripts should be checked before relying on an older status statement.

---

Agentic Debugger for Eclipse IDE is an independent open-source project and is not affiliated with the Eclipse Foundation.
