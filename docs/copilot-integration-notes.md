# GitHub Copilot for Eclipse: how it treats our server and agent (OPEN-23)

What we found in Copilot's own code on 26 Nov 2026, and what we decided from it. Read before changing how the plugin
offers the server or the agent to Copilot.

Sources (read only, nothing run): github.com/microsoft/copilot-for-eclipse at commit `6dc0170` (25 Nov 2026, "prepare
for 0.22.0"), and the Copilot language server it bundles, npm `@github/copilot-language-server` 1.541.0
(`dist/main.js`). The language server is the part Copilot shares with VS Code and other editors; it reads agent files
and runs MCP servers.

## 1. Agent files

- Read by the language server, not by Eclipse code. Eclipse asks it for the agents of all workspace folders
  (`FileBasedCustomModeService.loadCustomModes`).
- Eclipse passes **every open top-level project** as a workspace folder (`WorkspaceUtils.listWorkspaceFolders`), and
  the agents are merged into one list. One agent in one open project is offered in the whole workspace.
- An agent's ID is its file URI. The same agent in two projects appears **twice** in the mode list.
- Places the language server reads (Eclipse sets no other): `.github/agents/**/*.agent.md` and
  `.claude/agents/**/*.agent.md` in each project, and **`~/.copilot/agents/**/*.agent.md` for the user** (every
  workspace). It also reads Claude Code agents (`.claude/agents/**/*.md`, `~/.claude/agents/**/*.md`).
- The built-in "Debugger" agent (the one Copilot handed off to in plain Agent mode) is shipped inside the language
  server and switched on by a capability (`debuggerAgent`).

## 2. The `tools:` line of an agent (step a): not used, on purpose

- Without `tools:`, the agent gets all enabled tools. With it, Copilot keeps only the tools whose key is **exactly** in
  the list (`getToolsForModel`: a set of strings, no wildcards; `tools: []` means no tools).
- A tool's key is its name for Copilot's own tools, and `<server name>/<tool name>` for MCP tools.
- Eclipse names a server offered by a plugin `<plugin's Bundle-Name>: <server name>`
  (`McpExtensionPointManager.updateApprovedMcpServerString`), so ours is
  `Agentic Debugger for Eclipse IDE: eclipse-debugger`, and each of the 36 tools would have to be listed as, for example,
  `Agentic Debugger for Eclipse IDE: eclipse-debugger/begin_debug_investigation`. That is why a hand-written list with
  other names (26 Nov) left our tools out.

**Decision (Ayan, 26 Nov): no `tools:` line.** The naming rule exists only in Copilot for Eclipse's code, not in its
documentation; if it changes, every tool silently disappears from the agent and it cannot debug at all. Every new
tool would also need the list updated, and user-edited agent files would keep an old list. Our instructions already
keep the agent on our tools (every run on 26 Nov did). Look again if Copilot documents the format or supports
wildcards.

## 3. Approval of the server (step e) and signing (OPEN-45)

- Every server offered by a plugin starts as not approved, signed or not. Signing only changes the label in the
  approval dialog ("Signed" / "Unsigned"; `McpRegistrationInfo.isTrusted` is used nowhere else).
- The approval is kept only while the server JSON stays exactly the same (`detectChangesInMcpContribs` compares the
  stored and the current JSON as strings). Any change, such as a new Java path after an Eclipse update or reinstall,
  shows the server as Denied again.
- Copilot starts a stdio server with `cross-spawn` (`shell:false`; a `.cmd` file works on Windows) and stops it with
  `taskkill /pid <pid> /t /f` on Windows (the whole process tree) or SIGTERM then SIGKILL elsewhere.

**Decision (Ayan, 26 Nov): a stable launcher, on Windows first.** The JSON's command is a launcher script next to the
staged jar (`~/.eclipse-agentdebug/start-mcp-server.cmd`; under `port-<N>` for another bridge port), with no
arguments. **Linux and macOS keep the direct command** (`java -jar <jar>`) until the `.sh` launcher has run in a real
build there (CP-U7 on GitHub's Linux and macOS machines, README OPEN-54): we cannot test those systems ourselves, and
nothing untested goes to their users. The `.sh` text exists and is unit-tested (CP-U5), and CP-U7 ran it on a Linux
machine on 26 Nov. The script runs Eclipse's current Java with the jar and is rewritten at an Eclipse start only when
its text changes. The JSON names only the script, so it stays the same across Eclipse and Java updates, and the
approval stays. Once, when a user moves to this version, the JSON changes from the old form (Java path) to the
launcher, so Copilot asks one last time. (`CopilotRegistration.writeLauncher`, `json`, `jsonDirect`; tests CP-U4..U7:
CP-U7 builds a tiny echo jar, writes the launcher for the system the build runs on, really runs it, on Windows through
`cmd.exe /d /s /c` as cross-spawn does, and checks that stdin and stdout pass through with nothing else on stdout.)

## 4. The mode list after a start (OPEN-51)

See [copilot-mode-list-bug.md](copilot-mode-list-bug.md): the list misses custom agents until it is clicked or
Configure Agents is opened.

## 5. Checks in the test Eclipse, 27 Nov 2026 (Copilot 0.22 nightly, Auto model)

**One agent for the user (OPEN-23 d), 01:15-01:37.** `agentic_debugger.agent.md` copied to
`%USERPROFILE%\.copilot\agents\`: Preferences → GitHub Copilot → Custom Agents listed it with an empty Project
column, next to the copy in `spring-boot-debugging`. After our project menu Remove, exactly one entry. A new
Agentic Debugger chat ran only our tools and found `OrderService.quote` line 23; after a restart the agent was in the
mode list. So the user file works like a project file, without writing into projects.

**Tool approval.** Preferences → GitHub Copilot → Tool Auto Approve → MCP Server and Tool Approval had every tool of
`Agentic Debugger for Eclipse IDE: eclipse-debugger` ticked, so Copilot asked for none of them, including
`evaluate_expression`; "Trust MCP tool annotations" was off. Our server declares no tool annotations. Consequence:
README OPEN-58 (consent from the user, asked by the plugin).

**Terminal.** Copilot's `run_in_terminal` opens its own tab "Copilot" and works. Other PowerShell tabs showed
`. $env:COPILOT_POWERSHELL_INTEGRATION_SCRIPT` failing (variable empty); not caused by our plugin, cause not checked
(handoff, Noticed).

## 6. The "Confirm MCP Server Registration" dialog

Copilot opens it only when the user clicks: the tool button (wrench) in the chat bar, its menu item for new MCP
registrations, or Preferences → GitHub Copilot → Model Context Protocol → Edit MCP Servers from Plug-ins
(`McpExtensionPointManager.approveExtMcpRegistration`, called only from `ActionBar` and `McpPreferencePage`, commit
`6dc0170`). At a start Copilot only compares the stored and the current server JSON (`detectChangesInMcpContribs`) and,
when they differ or the server is new, marks it Denied and puts a red dot on the wrench button. So the dialog seen on
27 Nov 01:37 (showing Approved) came from a click, not from a start. Whether our JSON changes between starts shows as
that red dot: [release checks](release-checks.md) E. Our JSON is built only from the launcher path and the bridge
port (`CopilotRegistration.json`), both stable.

## 7. Tool annotations and server instructions (OPEN-58, OPEN-23 b)

Since 27 Nov our server declares MCP tool annotations (`ToolSafety`): read-only tools `readOnlyHint=true`,
`evaluate_expression` and `set_tracepoint` `destructiveHint=true`, the rest neither. With "Trust MCP tool annotations"
on, Copilot can approve the read-only ones itself and ask for the others. Independent of that, the plugin asks the
user in Eclipse before code runs in the program (`EvaluationConsent`). The server also sends MCP "instructions" at
connection saying that the tools start the program or test themselves; whether Copilot shows them to the model is not
checked (release checks B).

## 8. Which modes get our tools, and starting other agents (OPEN-60, 27 Nov 2026)

Read in Copilot for Eclipse 0.22 (`6dc0170`) and its language server 1.541.0 (`dist/main.js`):

- **Tools per request:** `getToolsForModel(mode)`: an agent with a `tools:` list gets exactly those (matched on
  `configurationKey`: `<server>/<tool>` for MCP tools, the plain name for Copilot's own); **Ask** gets six read-only
  built-in tools (never MCP tools); **Agent** and every custom agent *without* `tools:` get all tools whose status is
  "enabled" (`shouldApplyGlobalToolsSetting`). A new MCP server's tools start "enabled". So before OPEN-60 Agent mode
  had our tools since step 1, and our agent (no `tools:`) shared Agent mode's switch.
- **Starting another agent:** Agent mode has `run_subagent`; its prompt says "You should ALWAYS use the run_subagent
  tool to delegate tasks to specialized agents when the task you are working on matches the agent's description",
  listing every custom agent whose invoke policy contains "model". The default policy is `["user","model"]`; the key
  read is `x-github-copilot-invoke-policy` (GitHub documents `disable-model-invocation: true`, which 1.541 does not
  read yet). `run_subagent` refuses an agent without "model" ("not available for model invocation").
- **No MCP server per agent:** the extension point has only a provider class; agent front matter knows `name`,
  `description`, `tools`, `argument-hint`, `target`, `model` and the invoke policy. `mcp-servers` exists only for the
  cloud agent at organization level (GitHub docs: not used in VS Code and other IDE agents).
- **Settings (per workspace, Copilot's preference node `com.microsoft.copilot.eclipse.ui`):** `mcpToolsModeStatus`
  `{mode: {server group: {tool: boolean}}}` (`agent-mode`, custom agents by file URI), read once after the MCP tools
  first arrive (`McpConfigService` → `initializeMcpToolsStatus`); when empty Copilot uses `mcpToolsStatus`
  (`{server group: {tool: boolean}}`, Agent mode only), which it also applies at once when it changes.
  `autoApproveMcpServers` is a JSON list of server group names, read at every tool call (case-insensitive). Saving
  the Tools page for a custom agent makes the language server rewrite that agent file's `tools:`.
- **What the plugin does (OPEN-60):** both user-only lines and a `tools:` list in the agent; our tools `false` for
  `agent-mode` in both settings (only our group, other values written back unchanged, unknown formats refused);
  then our group in `autoApproveMcpServers`. Applied in `CopilotMcpProvider` before the offer is answered, i.e. before
  Copilot starts the server and reads the settings. Real values seen 27 Nov: `agent-mode` held only "Built-in Tools"
  (with `run_subagent` on) and no entry for our server, so our tools were on in Agent mode.
- **Remaining limit:** an agent whose author lists our tools by name in its own `tools:` gets them; nothing in
  Copilot prevents that. If Copilot renames these keys, the per-version check (release checks B and F) and Check setup
  show it.

