# Windows Eclipse Development and Skill Testing — Single-Configuration Workflow

This is the canonical Windows procedure for the integrated test project. **Do not manually set workspace or port in every CMD session.** All entrypoints load `scripts/windows/dev-settings.cmd` and resolve the repository root relative to the script path. Set/change defaults in that single file if your computer differs, or override via environment variables.

## Default environment (change in one place)

| Setting | Default | Meaning |
|---|---|---|
| `AGENTDEBUG_ECLIPSE_DIR` | `C:\All_Softwares\eclipse` | Installed Eclipse used for development tests |
| `AGENTDEBUG_WORKSPACE` | `%LOCALAPPDATA%\AgenticDebugger\dev-workspace` | Disposable test workspace (not regular personal workspace) |
| `AGENTDEBUG_PORT` | `21765` | Eclipse bridge port; avoids accidentally connecting to the default 8765 |
| `AGENTDEBUG_REPO_DIR` | Derived from script directory | No hardcoded clone/extraction path |
| `AGENTDEBUG_CLAUDE_EXE` | `%USERPROFILE%\.local\bin\claude.exe` | Claude Code CLI |

## One-command entry point

From any CMD directory, execute the full path to `RUN-WINDOWS-TESTS.cmd` (or double-click via terminal). Commands automatically display **Repository / Eclipse / Workspace / Port / Claude CLI** before doing anything.

```cmd
C:\All_GitHub_Desktop\agentic-debugger-for-eclipse\RUN-WINDOWS-TESTS.cmd config
C:\All_GitHub_Desktop\agentic-debugger-for-eclipse\RUN-WINDOWS-TESTS.cmd build
C:\All_GitHub_Desktop\agentic-debugger-for-eclipse\RUN-WINDOWS-TESTS.cmd deploy
C:\All_GitHub_Desktop\agentic-debugger-for-eclipse\RUN-WINDOWS-TESTS.cmd doctor
```

The path in these *examples* assumes you extracted the repository there. If you extract elsewhere, use that location. **There is no `cd` step.** You can alternatively call `scripts\windows\dev.bat` relative to the repository when already there.

## Deployment sequence

1. For **deploy only**, close all Eclipse instances from the target installation (p2 requires this). For doctor and tests, the runner will start the dedicated instance automatically when needed.
2. Run `RUN-WINDOWS-TESTS.cmd deploy`. It invokes the existing Maven/Tycho build, installs the new feature, and starts Eclipse with `-clean -data <configured workspace>`; the configured bridge port is inherited.
3. It automatically invokes `doctor` using exactly the **same** settings and waits for readiness. **Do not issue a separate build before deploy unless you only want a compilation check.**
4. Only after doctor passes, test the skill runner: `scripts\windows\test-skill.bat -Mode selftest`, `-Mode preflight`, then `-Mode smoke`. The skill runner also loads the shared settings.
5. For installed bridge checks, `RUN-WINDOWS-TESTS.cmd test` runs the existing installed verification; `RUN-WINDOWS-TESTS.cmd phase2` runs Phase 2 only if the dedicated fixture approval variable was deliberately set.

## Fail-fast behavior and interpretation

- Doctor prints the loaded bundle and verifies installation directory, workspace, class hashes, Java fixture and JUnit discovery. **Wrong workspace/installation is a setup failure, not a debugging defect.**
- An older Eclipse instance on port 8765 does not become the test target merely because it is running. Target port 21765 explicitly.
- `AGENTDEBUG_APPROVE_FIXTURE_POST` is **never enabled by default**. Only use `set AGENTDEBUG_APPROVE_FIXTURE_POST=YES` when intentionally authorizing local disposable fixture HTTP POSTs for Phase 2 tests.
- `test-skill.bat -Mode smoke` exercises non-runtime checks. Runtime evaluations require an isolated Eclipse test instance and appropriate explicit authorizations. No test should change important personal sessions.
- A fresh source archive lacks generated `target/classes`; use `build` or `deploy` before doctor.

## Scope of this integration

This integration changes **developer scripts and documentation**, not Eclipse JDT debugger implementation or MCP schemas. It preserves the existing skill and uses the latest activation-probe runner available in the supplied integration.

## Automatic Eclipse startup and readiness (v5)

Run `RUN-WINDOWS-TESTS.cmd doctor` from any directory using a full path to the entrypoint. It now **automatically checks** whether the configured bridge is reachable. When neither a matching Eclipse process nor the bridge is found, it starts the configured Eclipse installation with `-clean -data <AGENTDEBUG_WORKSPACE>` and inherits the centralized `AGENTDEBUG_PORT`. Then it waits (bounded) for the bridge and verifies the actual running workspace, installation, plugin class hashes and imported test fixtures.

`RUN-WINDOWS-TESTS.cmd test` and Phase 1/Phase 2 test commands use the same readiness step. `test-skill.bat -Mode smoke`, `selftest` and `preflight` remain IDE-free. Runtime skill tests only auto-start Eclipse if `-AllowDisposableEclipse` is supplied.

**Safety:** When an Eclipse instance from the configured installation already exists but its workspace identity cannot be confirmed and the configured bridge is absent, the runner **fails rather than starting a competing instance or killing processes**. If another process owns the expected port, doctor rejects it unless it is the correct Eclipse installation/workspace/plugin. An unresponsive or wrong workspace is a setup failure, not a debugger test failure.

**Known prerequisite:** The installed plugin must read `AGENTDEBUG_PORT` inherited by the launched Eclipse process (this is the existing development contract). If an independently started Eclipse process is already running with different environment settings, close it yourself before using auto-start. Deployment still requires Eclipse to be closed because p2 installation must not modify a running installation.
