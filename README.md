# Agentic Debugger for Eclipse IDE

An independent Eclipse IDE plugin that makes Eclipse's Java debugger available to Claude Code through a local MCP server. The MCP server supports the Eclipse debugger operations implemented by this repository, including launching Java debugging sessions, breakpoints, threads, stack frames, variables and stepping.

> **Build status:** This repository is a source build candidate. The complete Maven/Tycho p2 build and installation on Windows Eclipse have **not yet been verified**. Do not publish it as a production release until the checks under *Release verification* pass.

## For Eclipse IDE users — one installation

**Prerequisites:** Eclipse IDE with Java debugging support, Java 21+, and Claude Code installed. Claude Code must be available to the Eclipse process (the plugin checks `PATH` and the user's `.local/bin` directory).

1. Download the built p2 update-site ZIP from a release, or use the URL of the published update site.
2. In Eclipse select **Help → Install New Software → Add → Archive** (for a ZIP) or enter the update-site URL.
3. Select **Agentic Debugger for Eclipse IDE**, complete installation, and restart Eclipse.
4. On first restart, click **Configure** in the Agentic Debugger prompt. The plugin registers the bundled MCP server and reports success or failure. The same action is available later under **Window → Preferences → Agentic Debugger**.
5. Restart Claude Code if it was already open. Type `/mcp` to view the `eclipse-debugger` MCP server, and try `eclipse_ping`.

**No Maven build, standalone Java installer, manual JSON edits, or MCP JAR download is required for end users.** If the startup prompt was dismissed, open **Window → Preferences → Agentic Debugger** at any time.

The first-time confirmation is intentional: we do not change third-party tool settings without the user's permission.

## Files created and modified

| Where | Action | Why |
|---|---|---|
| Eclipse's p2-managed plugin location | Installed by Eclipse | Contains Eclipse debugger plugin and embedded `agentic-debugger-mcp.jar` |
| `~/.eclipse-agentdebug/agentic-debugger-mcp.jar` | Created by our plugin **after** Configure; replaced when plugin upgrades | Stable absolute path used by Claude Code despite changing Eclipse bundle versions |
| `~/.eclipse-agentdebug/claude-registration.properties` | Created after successful verification | Marks the plugin's **own** registration; used to prevent removing somebody else's MCP entry |
| `~/.claude.json` | **Claude Code CLI** adds/removes the user-scoped `eclipse-debugger` entry | Allows MCP access across all Java projects; other entries remain managed by Claude Code |
| `~/.eclipse-agentdebug/bridge.token` | Created/refreshed when the Eclipse bridge starts | Ephemeral local bridge authentication |
| Eclipse workspace preferences | Records that the first-launch prompt was displayed | Prevents repeating the prompt in the same workspace |

On Windows, `~` is normally `C:\Users\<WindowsUser>`. **No `.mcp.json` is created inside a Java project.** The plugin does not directly parse or rewrite `.claude.json`. Its configuration action invokes the documented `claude mcp add --scope user --transport stdio ...` command; removal invokes `claude mcp remove eclipse-debugger --scope user` only after ownership checks.

### Sample Claude MCP registration

The `mcpServers` entry managed by Claude Code looks conceptually like this (actual paths are detected automatically):

```json
{
  "mcpServers": {
    "eclipse-debugger": {
      "type": "stdio",
      "command": "C:\\Java\\bin\\java.exe",
      "args": ["-jar", "C:\\Users\\developer\\.eclipse-agentdebug\\agentic-debugger-mcp.jar"]
    }
  }
}
```

**This is an illustration, not an instruction to replace the existing `.claude.json` file.** Claude Code often stores many unrelated settings in that file.

### Existing registration protection

- If `eclipse-debugger` already resolves in Claude Code and isn't confirmed as managed by the plugin, **Configure refuses to overwrite it**. Other MCP entries are never intentionally changed.
- **Remove managed registration** first checks the plugin's own marker and checks the server JAR path reported by Claude Code. If ownership cannot be confirmed, removal is refused.
- A failed registration is reported, not silently treated as successful.
- **Verify registration** checks the stored CLI entry. The live `/mcp` connection and Eclipse `eclipse_ping` are separate end-to-end checks.
- Registration uses Claude's CLI; the exact formatting of `~/.claude.json` remains Claude Code's responsibility. For extra protection, make a backup of `~/.claude.json` before installing any tool that modifies Claude settings.

### How to modify or remove

- To **inspect** registration: run `claude mcp get eclipse-debugger` (optional diagnostic).
- To **modify/reconfigure** it: use **Window → Preferences → Agentic Debugger**. If an unrecognized existing registration is present, review/remove it yourself first; this plugin never automatically takes ownership of it.
- To **remove only our MCP registration**: click **Remove managed registration** on that Preferences page. It does not remove unrelated MCP entries or files from Java projects.
- To **uninstall the Eclipse plugin**: use **Help → About Eclipse IDE → Installation Details → Installed Software → Uninstall**, then restart Eclipse. If you also want to remove the Claude registration, use the Preferences page **before uninstalling**.
- The staged per-user JAR, ownership marker, and bridge token may remain after uninstall. After unregistering and closing Eclipse, these can be removed by deleting the `~/.eclipse-agentdebug` directory; do this only if you no longer need other files in that directory.

## Developer build

Repository layout:

```text
agentic-debugger-for-eclipse/
├── pom.xml
├── eclipse-plugin/         # Eclipse bridge, debugger commands and Claude Code Preferences UI
├── mcp-server/             # Java STDIO MCP server, packaged into Eclipse plugin
├── eclipse-feature/        # Installable Eclipse feature
├── eclipse-update-site/    # p2 repository / installable ZIP
└── installer/              # Legacy stand-alone setup source, NOT used by the new default build
```

Using Java 21+ and Maven, from the root of this repository:

```cmd
mvn clean package
```

On a successful build, locate the generated update-site ZIP in `eclipse-update-site/target/`. Do **not** assume it exists unless the build reports success. Install from that ZIP using the instructions above. Maven/Tycho dependencies require access to a compatible Eclipse p2 repository and Maven Central.

The normal build deliberately does not package the legacy `installer` module. End users must not be instructed to run `java -jar agentic-debugger-setup.jar` in this distribution.

## Architecture

```text
Claude Code (launches stdio subprocess)
      |
      v
MCP server JAR (staged once into user-owned path by Eclipse)
      |
      | localhost TCP bridge, authenticated with bridge.token
      v
Eclipse plugin -> JDT/Debug Platform -> Java debugging session
```

The plugin's bridge binds to `127.0.0.1:8765`, not a public network interface. Claude Code starts the MCP subprocess as needed; Eclipse itself does not need to keep a separate MCP server process running.

## Release verification (required before publishing)

- [ ] `mvn clean package` succeeds from a clean checkout with Maven and JDK 21/25.
- [ ] Generated p2 repository ZIP contains p2 metadata, feature, and Eclipse plugin bundle.
- [ ] Installed Eclipse plugin bundle **contains** `lib/agentic-debugger-mcp.jar` and the executable runtime is functional.
- [ ] Eclipse installs, restarts, and shows **Window → Preferences → Agentic Debugger**.
- [ ] First-run prompt appears once. Dismissing it makes **no** changes to Claude Code settings.
- [ ] Configure succeeds with native Windows `claude.exe` located at `%USERPROFILE%\.local\bin`.
- [ ] With existing unrelated entries in `.claude.json`, Configure/Verify/Remove retain them unchanged in meaning; with a conflicting `eclipse-debugger` registration, Configure refuses and preserves it.
- [ ] Plugin upgrade refreshes bundled runtime at the stable path; `/mcp`, `eclipse_ping` and Java debugging still work.
- [ ] Validate actual debugging tools against Eclipse 2026-06 and 2026-09, Java 21 and Java 25 as applicable.
- [ ] Test Windows, Linux and macOS process launching and p2 upgrades/removal.

## Trademark

Independent open-source project. Not affiliated with or endorsed by the Eclipse Foundation. Eclipse is a trademark of Eclipse Foundation AISBL.
