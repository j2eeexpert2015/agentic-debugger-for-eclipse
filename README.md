# Agentic Debugger for Eclipse IDE

Use Claude Code to set Java breakpoints, start debugging in Eclipse, inspect local variables, and step through code. Independent open-source integration; not affiliated with the Eclipse Foundation.

## 1. Install and use (end users)

**Prerequisites:** Eclipse IDE with Java Development Tools (JDT), Java 21+, and an installed Claude Code CLI. Maven is needed **only if building from source**.

1. Download the **Eclipse update-site ZIP** from GitHub Releases (when published). To build it yourself instead, run `mvn clean package` from the repository root and select the ZIP in `eclipse-update-site/target/`. **Do not use the repository source ZIP.**
2. In Eclipse, select **Help → Install New Software → Add**. Enter **Name:** `Agentic Debugger for Eclipse IDE`; click **Archive** and select the update-site ZIP; click **Add**.
3. Expand **Agentic Debugger**, select the installable feature, complete installation, and **restart Eclipse**.
4. Accept the first-run **Configure Claude Code** prompt. Alternatively open **Window → Preferences → Agentic Debugger → Configure Claude Code → Verify registration**. Existing unrelated MCP registrations are preserved.
5. In an Eclipse terminal, run `claude`, use `/mcp` to confirm `eclipse-debugger`, and ask Claude to call `eclipse_ping` (expect `PONG`). **Keep Eclipse running.**

**Normal debugging (default, cleans up its own resources when finished):** Open a Java project in Eclipse, launch Claude Code, and ask:

> Debug `PricingApplication` in my Eclipse project with `eclipse-debugger`. Find why its order total is wrong, use real breakpoint and variable evidence, and explain the cause. Do not modify source code or affect other debug sessions.

Claude should call `begin_debug_investigation` (default `NORMAL`), pass its `investigationId` to breakpoint and launch tools, and call `finish_debug_investigation` after reporting findings. The final operation terminates **only that investigation's newly started launch** and removes **only its tagged, newly created breakpoints**. An existing reused session is never automatically terminated. If the agent fails to call the finish tool, nothing is auto-terminated; finish explicitly or stop the session in Eclipse.

**Controlled debugging (keep suspended for inspection):**

> Debug `PricingApplication` in controlled mode. Set a breakpoint, inspect variables, step over once, and leave the session suspended so I can inspect Eclipse. Do not change source code.

Claude uses `begin_debug_investigation` with `mode: CONTROLLED`, and finishes with `finish_debug_investigation` while preserving the paused session and breakpoints. This mode is chosen only on explicit request. No project JSON file is created; JSON tool arguments travel through MCP. Existing tools remain compatible if no investigation ID is passed, but **automatic investigation cleanup requires the lifecycle ID**. User sessions and user breakpoints are preserved in both modes.

The tools `list_java_applications` and `start_java_application` discover main classes and start a selected project/class, creating a standard Java launch configuration only when no matching one exists. Subsequent tools use the `launchName` returned by `start_java_application`. When the agent reaches a breakpoint or steps, Eclipse follows its active session and opens the suspended source location. This behavior uses the Eclipse Debug view and source-lookup mechanism; it does not modify execution. `show_debug_location` can explicitly reselect that frame without resuming. In **Window → Preferences → Agentic Debugger**, uncheck automatic Debug perspective switching if preferred. Existing, unrelated debugging sessions are not selected automatically. **Use existing configurations** for applications needing special VM/program arguments, environment settings, Spring Boot profiles, or other launch customization. If several main classes or matching configurations exist, explicitly choose one; the plugin does not guess.

To uninstall: **Help → About Eclipse IDE → Installation Details → Installed Software → Uninstall**. To remove the Claude integration, use **Window → Preferences → Agentic Debugger → Remove managed registration** (preserves unrelated entries). The registration is user-scoped in `~/.claude.json`; Eclipse bridge resources live under `~/.eclipse-agentdebug/`. Do not manually overwrite `.claude.json`.

## 2. Develop and test (contributors)

**Prerequisites:** JDK 25, Maven, Eclipse RCP/PDE with JDT, authenticated Claude Code CLI.

**Test source navigation:** Start two Java debug sessions in the same Eclipse instance, with one suspended in a different project. Ask Claude to debug the second using `start_java_application` (or `start_debug_session`), hit a breakpoint, and step. Confirm the Debug view selects the second session and Eclipse opens its source at the suspended line; the first session must remain suspended and unchanged. For deterministic validation, invoke `show_debug_location` with the returned `launchName`. These GUI observations are not yet automated by the existing E2E runner.

**Build:** From the repository root:

```cmd
mvn clean package
```

**Development E2E — no plugin installation required:**

1. In Eclipse RCP/PDE, import `eclipse-plugin` with **File → Import → General → Existing Projects into Workspace**.
2. **Run → Run Configurations → Eclipse Application**: create/run an Eclipse Application configuration with our workspace plugin and a dedicated runtime workspace. Keep that **Runtime Eclipse** open; only it should own `127.0.0.1:8765`.
3. From the repository root run:

   ```cmd
   mvn verify -Pe2e
   ```

The command builds the MCP server, prepares the sample calculator project and saved launch automatically, and tests the CONTROLLED investigation lifecycle, invokes Claude non-interactively with an isolated temporary MCP config, and checks breakpoint, suspension, stack, variables, step-over and explanation. **No manual sample import, Claude prompt paste, or user JSON editing.** Report: `e2e-runner/target/e2e-report/result.txt` and `trace.jsonl`. Claude usage may incur charges. The E2E command does **not** start Runtime Eclipse automatically.

**Installed ZIP E2E — validates what users install:**

1. Build with `mvn clean package`; install the generated `eclipse-update-site/target/*.zip` into a **separate normal Eclipse IDE** through **Help → Install New Software → Add → Archive**; restart and keep that Eclipse open.
2. Close PDE Runtime Eclipse so the installed instance alone owns port `8765`.
3. From the repository root run:

   ```cmd
   mvn verify -Pinstalled-e2e
   ```

The test compares installed debugger class bytes with the freshly built distribution and tests breakpoint creation before running Claude. Reports: `e2e-runner/target/e2e-report/` (`installed-binary.txt`, `preflight.txt`, `trace.jsonl`, `result.txt`). Maven **does not install or launch Eclipse** in this profile. A new public ZIP needs a higher Eclipse bundle/feature version; reinstalling the same version may reuse old plugin code.

## 3. Local Windows deployment (developers)

Use `scripts/windows/deploy-eclipse-plugin.bat` to **build, uninstall the currently installed Agentic Debugger feature, install the newest local p2 build, verify, and optionally start Eclipse**. This changes the configured Eclipse installation; use it only for a development/test Eclipse.

1. Edit the **first two paths** in `scripts/windows/deploy-eclipse-plugin.bat`:

   ```bat
   set "ECLIPSE_DIR=C:\All_Softwares\eclipse"
   set "PROJECT_DIR=C:\All_GitHub_Desktop\agentic-debugger-for-eclipse"
   ```

2. **Close the target Eclipse IDE**, then run from the repository root:

   ```cmd
   scripts\windows\deploy-eclipse-plugin.bat
   ```

3. Confirm the prompt. The script builds with `mvn clean package`, uses Eclipse **p2 Director** to remove only our installed feature and install its latest build from `eclipse-update-site/target/repository`, verifies installation, then starts Eclipse if `START_ECLIPSE=YES`. Each stage reports progress directly in Command Prompt. Failed Maven commands print `[ERROR]` lines; failed Eclipse p2 commands print their full output immediately. Complete command output is also saved under `deploy-logs/`. **Do not run it concurrently with Eclipse or other installers.** If uninstall succeeds but installation fails, repair the installation before restarting Eclipse.

4. Once Eclipse is open and the bridge is listening, run the installed E2E test from the repository root:

   ```cmd
   mvn verify -Pinstalled-e2e
   ```

The script does not edit Claude Code's `.claude.json` and does not uninstall other Eclipse features. The entire script has **not yet been executed on Windows**; validate the first deployment against a disposable Eclipse installation.

## 4. CI/CD (prepared; inactive)

`.github/workflows/ci.yml.disabled` and `release.yml.disabled` are complete workflow templates. **GitHub does not run them while the `.disabled` suffix is present.** To enable CI, rename `ci.yml.disabled` to `ci.yml` and commit. To enable release publishing, separately rename `release.yml.disabled` to `release.yml` and commit. CI builds/tests/packages on pushes and pull requests; release publishing runs on version tags. Release tags require consistent, increasing Eclipse bundle and feature versions; these templates do not automatically bump project versions. **Neither workflow executes Claude Code or modifies a user's MCP registration.**

## 5. Debugging modes and safety

- `begin_debug_investigation`: optional `mode` (`NORMAL` by default; `CONTROLLED` when explicitly requested). Returns an `investigationId`.
- Supply `investigationId` to `set_breakpoint` and `start_debug_session` or `start_java_application` to associate work with that investigation.
- `finish_debug_investigation`: pass `investigationId`. In NORMAL, it terminates only a launch created by the investigation and removes only breakpoints it created. In CONTROLLED, it retains the current debugger state.
- Existing user breakpoints, prior agent breakpoints, existing launch configurations, and reused debug sessions are preserved. For concurrent investigations, always keep their IDs separate. No implicit inactivity cleanup is performed.
- `CONTROLLED` describes lifecycle behavior; the agent must still follow explicit stepping/resume instructions. The plugin cannot establish whether a natural-language user instruction authorized a step.

## 6. Current verification boundary

Previous source revisions passed the Maven/Claude installed-plugin test in a Windows Eclipse setup. **The new investigation lifecycle has not yet been tested in a live Eclipse installation.** Validate those changes before publishing a release.
