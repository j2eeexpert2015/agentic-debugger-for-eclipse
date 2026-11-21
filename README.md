# Agentic Debugger for Eclipse IDE

Debug Java applications inside Eclipse IDE using Claude Code through the Model Context Protocol (MCP). The Eclipse plugin provides access to Eclipse's debugger; a bundled Java MCP server connects Claude Code to that plugin.

> **Release status:** This repository is an **unverified source build candidate**, not a published, tested Eclipse release. The Maven/Tycho build, p2 installation, and first-run configuration still require end-to-end testing. The steps below describe the actual installation flow implemented in this source, not a claim that a release has passed verification.

## Start here — install and use the plugin

There are **two ways to obtain the Eclipse update-site ZIP**. Choose **A** if you have a prebuilt release ZIP. Choose **B** if you have this source repository. After obtaining the update-site ZIP, follow Steps 1–5.

### A. If you downloaded a prebuilt release

Download the **Eclipse update-site ZIP** from the project's published release (when one is available). **Do not select the source-code ZIP** in Eclipse's installer. Skip to **Step 1** below.

### B. If you downloaded this source repository (current testing method)

You need **JDK 21 or newer**, **Apache Maven**, and internet connectivity to resolve the build dependencies. The build currently targets the Eclipse **2026-09** p2 repository. From the repository root (the directory containing the top-level `pom.xml`), open Command Prompt and run:

```cmd
java -version
mvn -version
mvn clean package
```

Only continue if Maven prints `BUILD SUCCESS`. Then open:

```text
eclipse-update-site/target/
```

Find the generated **update-site `.zip`** (not the repository source ZIP). It should contain Eclipse p2 metadata such as `content.jar`/`content.xml` and `artifacts.jar`/`artifacts.xml`, plus `features/` and `plugins/` entries. **The ZIP filename can vary with Maven version/configuration; do not guess it.** If no installable ZIP was produced, the build or packaging needs correction before proceeding.

### Step 1 — Install into Eclipse IDE

1. Start your **regular Eclipse IDE for Java Developers** (the Eclipse instance in which you will debug Java code), not a separate PDE Runtime Eclipse instance.
2. Select **Help → Install New Software…**.
3. Click **Add…**.
4. For a local ZIP, click **Archive…**, select the generated **update-site ZIP** from `eclipse-update-site/target/`, and click **Add** or **OK**. **Do not extract the ZIP** before selecting it in Eclipse.
   - For a future published update-site URL, enter that URL in **Work with** instead of selecting an archive.
5. Wait for Eclipse to load the available software. Expand **Agentic Debugger** (the category) if necessary.
6. Select **Agentic Debugger for Eclipse IDE** (the installable feature).
7. Click **Next**, review the installation details and license prompts, and complete the installation. Accept any trust/security prompt **only if you trust the source**.
8. When Eclipse asks to restart, click **Restart Now**.

**If Eclipse says “There is no categorized item”:** uncheck **Group items by category** and look for **Agentic Debugger for Eclipse IDE**. If nothing appears, verify that you chose a generated p2 update-site ZIP, not the source repository ZIP.

### Step 2 — Configure Claude Code (once)

**Prerequisite:** Claude Code must already be installed on this computer. On Windows, our plugin checks PATH and the native per-user `.local/bin` directory. If you just installed Claude Code, restart Eclipse so it sees your environment.

1. After Eclipse restarts, look for a prompt titled **Agentic Debugger for Eclipse IDE** asking whether to configure the bundled MCP server for Claude Code.
2. Click **Yes** to configure. This is a one-time permission step; it is **not** a second JAR installation.
3. Wait for Eclipse's configuration result message. A successful result should report that the MCP server was configured at **user scope** and suggest restarting Claude Code.

**If you dismissed the prompt, or it did not appear:** In Eclipse open **Window → Preferences → Agentic Debugger**. Click **Configure Claude Code**. On macOS, the Preferences menu may appear under the Eclipse application menu.

**If a previous Agentic Debugger installation is detected:** The plugin shows the current command and asks whether to **Update Registration**. Click **Yes** only if you want to migrate it to the bundled MCP server. The plugin updates only the user-scoped `eclipse-debugger` entry, leaves other entries alone, and attempts to restore the previous command if migration fails. **No** keeps the old registration unchanged.

**If an unrecognized `eclipse-debugger` entry exists:** The plugin refuses to replace it. Inspect the entry with `claude mcp get eclipse-debugger` before making any manual change.

### Step 3 — Verify from Eclipse

1. Open **Window → Preferences → Agentic Debugger**.
2. Click **Verify registration**.
3. Confirm the status reads **Configured (user scope)**. If it reads **Update available — previous Agentic Debugger registration detected**, click **Configure Claude Code**, review the old command, and approve migration if appropriate. Then click **Verify registration** again.

This verifies the Claude Code **registration**. It does not by itself prove that the live Eclipse debugger connection works.

### Step 4 — Verify from Claude Code

1. Keep Eclipse open.
2. Open Command Prompt in your **Java project's directory** (or your normal Claude Code working directory).
3. Start Claude Code:

```cmd
claude
```

4. Enter `/mcp`. Confirm the **`eclipse-debugger`** server is present and connected. If Claude Code was already running before registration, close and reopen it first.
5. Ask Claude Code:

```text
Use the eclipse-debugger MCP server to call eclipse_ping. Show the response.
```

The expected bridge response is **`PONG`**. If registration succeeds but the live connection fails, ensure you started the **same Eclipse IDE installation** into which the plugin was installed, and verify that Eclipse is still running.

### Step 5 — Debug a Java application

1. Open or import a Java project into **that same Eclipse IDE**. Make sure it can run normally and has a Java launch/debug configuration (or a runnable `main` class).
2. Keep Eclipse running and open Claude Code from the relevant project directory.
3. Give Claude Code a task such as:

```text
Using the eclipse-debugger MCP server, find my available Java launch
configurations, start DebugAverageCalculation in debug mode, set a
breakpoint in calculateAverage, inspect the suspended thread and
variables, and explain the calculation. Do not modify source code or
remove my existing breakpoints.
```

Claude Code uses the MCP server to request debugger actions from **the running Eclipse IDE**. The example class is only an example: substitute a class or launch configuration that actually exists in your project. Individual debugging capabilities must be confirmed in the installed build.

**Everyday use after the initial setup:** Open Eclipse with your Java project → open Claude Code → give a debugging prompt. You do **not** rerun Maven, install a separate MCP JAR, or repeat configuration for each Java project.

### Startup performance behavior

- The Eclipse bridge still starts automatically in a background Job.
- On startup, the plugin **does not enumerate active Java debug sessions**; session discovery occurs only when a debugging request asks for it.
- If Claude Code was previously configured through this plugin, the bundled MCP JAR is checked against the installed copy using SHA-256. **Identical JARs are not copied** and the installed file is not touched. New/changed JARs are staged and replaced.
- Startup does **not** run `claude mcp get`, register a server, or change `.claude.json` automatically; setup requires user approval. The first-run prompt can still appear once.
- If an updated JAR cannot be replaced because it is in use, close Claude Code and restart Eclipse to retry; the plugin logs the refresh error rather than delaying bridge startup.

To run the standalone runtime-refresh test with JDK 21+ (from the repository root):

```cmd
javac -d eclipse-plugin/target/runtime-test-classes eclipse-plugin/src/org/learningfromexperience/eclipse/agentdebug/integration/RuntimeJarInstaller.java eclipse-plugin/tests/org/learningfromexperience/eclipse/agentdebug/integration/RuntimeJarInstallerTest.java
java -cp eclipse-plugin/target/runtime-test-classes org.learningfromexperience.eclipse.agentdebug.integration.RuntimeJarInstallerTest
```


## What gets installed or changed?

| Location | Action | Purpose |
|---|---|---|
| Eclipse's p2-managed installation | Installs the debugger plugin with bundled MCP JAR | Actual Eclipse feature |
| `~/.eclipse-agentdebug/agentic-debugger-mcp.jar` | Copies/refreshes the bundled JAR after configuration | Stable MCP executable path across Eclipse upgrades |
| `~/.claude.json` | Updated by **Claude Code's own CLI** | User-scoped `eclipse-debugger` MCP registration |
| `~/.eclipse-agentdebug/claude-registration.properties` | Created by the plugin | Records ownership of its own registration |
| `~/.eclipse-agentdebug/bridge.token` | Generated/refreshed by the Eclipse plugin | Authenticates local debugger bridge |
| Eclipse workspace preferences | Records whether first-run prompt appeared | Avoids repeated prompts |

On Windows, `~` normally means `C:\Users\<WindowsUser>`. **No `.mcp.json` is created inside your Java project.** Registration makes the debugger available to Claude Code across projects for the same user account.

### Conceptual Claude Code registration

Claude Code manages the actual user-level JSON. A representative entry is:

```json
{
  "mcpServers": {
    "eclipse-debugger": {
      "type": "stdio",
      "command": "C:\\path\\to\\java.exe",
      "args": ["-jar", "C:\\Users\\developer\\.eclipse-agentdebug\\agentic-debugger-mcp.jar"]
    }
  }
}
```

**Never replace your complete `.claude.json` with this example.** The real file can contain other MCP registrations and unrelated settings. The plugin calls Claude Code's CLI to add or remove only `eclipse-debugger`, rather than parsing and rewriting the JSON itself. It refuses to take over an existing entry it cannot identify as plugin-managed. For important configurations, keeping a backup of `.claude.json` is prudent.

## Modify, verify, remove, or uninstall

**Verify:** Eclipse → **Window → Preferences → Agentic Debugger → Verify registration**. For independent diagnosis, run `claude mcp get eclipse-debugger` in a terminal.

**Reconfigure:** Use **Configure Claude Code** on the same Preferences page. If the entry already points to the bundled JAR, the plugin reports **Already configured**, even if an older installation did not create a marker file. For a recognized previous standalone installation, it offers an explicit, confirmed migration. Unknown same-named entries are never replaced automatically.

**Remove only the Claude Code integration:** Open Eclipse → **Window → Preferences → Agentic Debugger → Remove managed registration**. The action checks ownership and removes only the plugin-managed **user-level** `eclipse-debugger` registration. It preserves unrelated MCP registrations and does not delete Java project files. Close and restart Claude Code afterward.

**Uninstall the Eclipse plugin:** First remove the managed Claude registration **while the plugin is still installed** if you no longer want it. Then choose **Help → About Eclipse IDE → Installation Details → Installed Software**, select Agentic Debugger, click **Uninstall**, and restart Eclipse.

**Residual local files:** After unregistering and closing Eclipse, `~/.eclipse-agentdebug/` may still contain the staged MCP JAR, marker, and token. Remove these only when you are sure they are no longer needed. The plugin does not promise to erase all per-user files during p2 uninstall.

## Troubleshooting

| Symptom | What to check |
|---|---|
| No installable feature appears | Confirm you selected the **p2 update-site ZIP**, not the source ZIP. Try disabling **Group items by category**. |
| “No repository found” / dependency resolution failure | Confirm the ZIP contains p2 metadata; check your Eclipse version and required Java debugging components. This source build currently targets Eclipse 2026-09. |
| First-run prompt does not appear | Open **Window → Preferences → Agentic Debugger** and select **Configure Claude Code** directly. |
| Claude Code CLI not found | Run `claude --version` in Command Prompt; if newly installed, restart Eclipse. On Windows, native installs commonly use `%USERPROFILE%\.local\bin\claude.exe`. |
| Older Agentic Debugger registration detected | Use **Configure Claude Code** and approve migration after reviewing its existing command. Other MCP entries are preserved. |
| Unrecognized `eclipse-debugger` entry | Run `claude mcp get eclipse-debugger`; the plugin will not overwrite it. |
| `Verify registration` fails | Read the Preferences status message. Check that Claude Code can be invoked and that no conflicting registration exists. |
| `/mcp` does not show the server | Close and relaunch Claude Code after configuration; check `claude mcp get eclipse-debugger`. |
| MCP listed but `eclipse_ping` fails | Keep the installed Eclipse IDE open; ensure its debugger bridge started and the same IDE owns the Java debugging sessions. |
| No Java debug session is found | Open/import the project in the **same running Eclipse instance** and check it has a runnable Java launch configuration. |

## Local development: end-to-end Claude Code debugging test

**This is the recommended test after every debugger change.** It exercises a real Claude Code prompt against the Eclipse plugin, rather than merely checking Maven compilation. No p2 ZIP reinstall is needed when developing through Eclipse PDE.

### One-time preparation

1. Have **Eclipse IDE with PDE and Java Development Tools (JDT)**, JDK 21+, and Claude Code available. Ensure the `eclipse-debugger` MCP server is registered with Claude Code (see the installation guide above). Keep **only one Eclipse bridge listening on `127.0.0.1:8765`**. Shut down the regular Eclipse instance if it has the installed Agentic Debugger plugin before launching the PDE runtime.
2. In your development Eclipse, import `eclipse-plugin` via **File → Import → General → Existing Projects into Workspace**, select the `eclipse-plugin` directory, and ensure the project is recognized as a **Plug-in Development** project. If PDE cannot find the bundled MCP JAR, perform a one-time `mvn package` from the repository root so that `eclipse-plugin/lib/agentic-debugger-mcp.jar` is generated; refresh the project.
3. In the development Eclipse, choose **Run → Run Configurations → Eclipse Application**. Create `Agentic Debugger - Development`, choose a **separate runtime workspace**, and ensure the workspace plugin and required Eclipse/JDT bundles are included in the launched Eclipse application. Click **Run**. This starts the *Runtime Eclipse* containing the plugin directly from source; it is not a p2 installation.
4. In **Runtime Eclipse**, choose **File → Import → General → Existing Projects into Workspace**. Select `examples/agentic-debugger-smoke-test` as the root directory. Import project `AgenticDebuggerSmokeTest` and wait for Eclipse to finish building it. The project includes a shared `TestDebug.launch` file with the correct fully qualified Main class `test.TestDebug`.
5. In Runtime Eclipse, confirm **Run → Debug Configurations → Java Application → TestDebug** has **Project** `AgenticDebuggerSmokeTest` and **Main class** `test.TestDebug`. Do not launch the program yet. If the shared launch does not appear, open `TestDebug.java` and use **Run As → Java Application** once, then verify the resulting launch configuration has the fully qualified main class.
6. If you previously registered the MCP server through an installed Eclipse version, its registration may still refer to that stable MCP JAR. You can test the current PDE bridge through that server if the protocol is compatible. To exercise MCP changes too, configure the current bundled MCP runtime and **restart Claude Code**. `/mcp` must show `eclipse-debugger` connected; `eclipse_ping` must reach the Runtime Eclipse, not another Eclipse instance.

### Run the genuine Claude-driven test

Open a terminal in the repository root and start Claude Code:

```cmd
claude
```

Paste the **complete test prompt from [`examples/CLAUDE_E2E_PROMPT.txt`](examples/CLAUDE_E2E_PROMPT.txt)** into Claude Code. It instructs Claude to:

1. Verify `eclipse_ping` returns `PONG`.
2. Discover the shared launch configuration `TestDebug`.
3. Set a breakpoint at **`test.TestDebug` line 8**, using the MCP `set_breakpoint` tool.
4. Launch `TestDebug` in debug mode and wait until execution suspends.
5. Inspect stack frames and local variables (`sum = 0`, `number = 10` before the first addition).
6. Step over once and inspect `sum = 10`.
7. Explain the deliberate **integer-division defect** and output `Average: 20.0` (the mathematically correct mean is approximately `20.3333`).
8. Return an explicit PASS/FAIL report for each operation, with evidence. The session is left suspended for inspection.

**Pass criterion:** The Claude transcript must show successful MCP tool calls with observed suspension, local values, and step-over—not only a successful Java launch or a connected `/mcp` status. Do not claim an end-to-end pass when a tool fails or when the breakpoint was never hit.

**After a source change:** Eclipse incrementally rebuilds the plugin; **terminate and relaunch the PDE Runtime Eclipse** to pick up changes that require bundle reinitialization. Repeat the same Claude prompt. **Do not rebuild an update-site ZIP or uninstall/reinstall Eclipse for every iteration.** If your change affects the MCP server JAR, rebuild that module and refresh the runtime/configuration as appropriate.

**Before a release:** Separately execute `mvn clean package`, install the generated p2 update-site ZIP into a clean test Eclipse, configure Claude Code, and repeat the same prompt. PDE tests validate development behavior; p2 installation tests validate packaging and first-run setup.

**Known limitation:** The current plugin discovers and launches **existing** Eclipse Java launch configurations; the fixture provides one. It does not yet create arbitrary new launch configurations on demand. The Claude prompt is a reproducible **manual E2E test**, not an unattended CI test; reliable fully automated MCP/IDE tests would need a dedicated test harness.

---

## Developer reference

```text
agentic-debugger-for-eclipse/
├── pom.xml                    # Maven/Tycho reactor
├── eclipse-plugin/             # Eclipse bridge + Preferences UI
├── mcp-server/                 # Java STDIO MCP server
├── eclipse-feature/            # p2 installable feature
├── eclipse-update-site/        # p2 repository and ZIP assembly
└── installer/                  # Legacy standalone installer; not in default reactor
```

The Eclipse bridge listens on **`127.0.0.1:8765`** and uses the local `bridge.token` for authenticated requests. Claude Code launches the Java MCP process on demand over STDIO; Eclipse does not run a separate, always-on MCP process.

The legacy standalone installer is **not** part of the default root build and is **not** needed for the one-installation workflow.

### Release validation required

- [ ] `mvn clean package` succeeds from a clean checkout with the intended JDK.
- [ ] A generated update-site ZIP contains valid p2 metadata, installable feature, and the bundled MCP JAR.
- [ ] Eclipse IDE installs and restarts successfully; **Agentic Debugger** Preferences page is visible.
- [ ] First-run prompt correctly configures Claude Code when authorized, including Windows native `claude.exe` detection.
- [ ] Other MCP entries in `.claude.json` remain intact after Configure, Verify and Remove.
- [ ] Existing unrelated `eclipse-debugger` entries are protected.
- [ ] Existing standalone Agentic Debugger installs are recognized, offer explicit migration, and preserve unrelated registrations.
- [ ] `/mcp`, `eclipse_ping`, launch, breakpoint, variables and stepping work end-to-end.
- [ ] Upgrade keeps the registered stable runtime functional; removal and reinstall work.
- [ ] Validate target Eclipse versions and Windows/Linux/macOS behavior separately.

## Trademark

Agentic Debugger is an independent open-source project and is not affiliated with or endorsed by the Eclipse Foundation. Eclipse is a trademark of Eclipse Foundation AISBL.
