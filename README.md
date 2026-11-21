# Agentic Debugger for Eclipse IDE

Debug Java applications in Eclipse using Claude Code and an MCP server. **Development build:** the complete Eclipse/Claude integration still needs Windows verification.

## 1. Install the plugin in Eclipse

**Requirements:** Eclipse IDE with Java Development Tools (JDT), JDK 21+, Maven, and Claude Code CLI.

1. Obtain the **Eclipse update-site ZIP** from a published release. Until a release is published, clone this source repository and run `mvn clean package` in the repository root; the installable ZIP is generated under `eclipse-update-site/target/`.
2. In Eclipse, choose **Help → Install New Software → Add**.
3. Enter **Name:** `Agentic Debugger for Eclipse IDE`. Click **Archive**, select the **generated update-site ZIP** (not the source ZIP), and confirm **Add**.
4. Expand **Agentic Debugger**, select **Agentic Debugger for Eclipse IDE**, click **Next**, complete the installation and restart Eclipse. If Eclipse reports *All items are installed*, you already have this version; use a higher-version build to update it.
5. When prompted, choose **Configure Claude Code**. Or open **Window → Preferences → Agentic Debugger → Configure Claude Code** and then **Verify registration**. Existing unrelated MCP registrations are preserved.
6. Open a fresh Claude Code terminal (`claude`), enter `/mcp`, confirm `eclipse-debugger` is connected, and ask Claude to call `eclipse_ping` (expected `PONG`).

To debug, open a Java project in the same Eclipse, then ask Claude to discover launch configurations, set a breakpoint, start debugging, and inspect variables.

## 2. Automated developer test

**One command from the repository root:**

```cmd
mvn verify -Pe2e
```

The E2E runner builds the latest MCP server, connects to **an already-running development Eclipse** with the current plugin, creates/reuses the reserved project `AgenticDebuggerCalculatorE2E` with `AverageCalculatorApplication` and an `AverageCalculator` service, invokes Claude Code non-interactively with a temporary *isolated* MCP configuration, and captures the debugger tool trace. The test leaves the session suspended for inspection and writes results under `e2e-runner/target/e2e-report/`.

**One-time prerequisite:** launch an Eclipse PDE Runtime Workbench containing the latest plugin source (**Run → Run Configurations → Eclipse Application**) with a dedicated test workspace. This prerequisite is not automated yet; without it, the command fails clearly rather than pretending an end-to-end test passed. Use a dedicated runtime to keep personal projects and debugger sessions separate. Ensure no other Eclipse instance owns bridge port `8765`.

You **do not** import a sample project, create a launch configuration, edit `.claude.json`, paste a prompt, or install a ZIP for each test. Claude Code must already be authenticated, and the run may consume Claude usage quota.

**Output:** `e2e-runner/target/e2e-report/result.txt`, `preflight.txt`, `trace.jsonl` and (on success) `runtime-evidence.txt`. Nonzero Maven exit means failure. The runner checks the expected fixture protocol, exact launch name and breakpoint-listing response **before** invoking Claude, so a stale Runtime Eclipse is reported immediately. The runner checks required tool calls, reported tool errors, a suspended test session, the `AverageCalculator.calculateAverage` stack frame, and the final `sum=10` value directly from Eclipse. The test uses the breakpoint at `com.learningfromexperience.debugsample.service.AverageCalculator:10`. Initially `sum=0` and `number=10`; after one step over `sum=10`. The E2E prompt includes the exact checked-in Java fixture source, so Claude can explain integer division without the consent-gated expression-evaluation tool. The runner separately checks tool errors, that exactly one step-over occurred, final live Eclipse variables, and the defect explanation. Review `trace.jsonl` for full evidence.

**If preflight fails:** Restart **Runtime Eclipse** from the PDE launch configuration, ensuring the workspace plugin is selected (not an older installed binary) and that only this runtime owns port `8765`. A successful `PONG` only proves bridge connectivity, not that the correct plugin version is loaded. Check `preflight.txt` and the Runtime Eclipse console for the exact error. No changes to Claude's user-level MCP configuration are needed.

## 3. Files and safe removal

| Location | Purpose |
|---|---|
| Eclipse-managed plugin installation | Eclipse debugger integration and bundled MCP runtime |
| `~/.eclipse-agentdebug/` | Bridge token, bundled MCP runtime and registration marker |
| `~/.claude.json` | User-scoped Claude Code MCP registration (managed through Claude CLI) |

No project-level `.mcp.json` is needed for normal installation. In **Window → Preferences → Agentic Debugger**, use **Verify registration** or **Remove managed registration**; only plugin-managed registrations may be removed. Before editing configuration manually, back up `.claude.json` and preserve other MCP entries. Uninstall the Eclipse feature using **Help → About Eclipse IDE → Installation Details → Installed Software → Uninstall**.

## Build and testing status

The E2E runner source compiles under Java 21. The complete Maven/Tycho build, automated Claude run, and Eclipse p2 installation have **not** been executed in this environment. `mvn verify -Pe2e` is an implementation candidate that must be validated on the Windows development machine.
