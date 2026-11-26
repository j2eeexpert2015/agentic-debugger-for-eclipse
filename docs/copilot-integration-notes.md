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
