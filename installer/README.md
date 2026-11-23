# Setup utility (`agentic-debugger-setup.jar`)

A small command-line tool that installs the Agentic Debugger MCP server on this computer and registers it with Claude Code, **without going through Eclipse Preferences**. It is an alternative to **Window → Preferences → Agentic Debugger → Configure** in the plugin, for cases such as:

- setting up Claude Code from a terminal or a script, before or without opening the Preferences page;
- checking or removing a registration from the command line;
- having the MCP server jar in a fixed, known location, so you can add it **manually** to Claude Code or to another MCP client (see [Manual registration](#manual-registration-without-this-utility)).

The MCP server only works while Eclipse is running with the Agentic Debugger plugin installed: it talks to the bridge inside Eclipse. This utility does not install or change the Eclipse plugin.

## Build

`mvn clean package` from the repository root builds it after `mcp-server` and embeds that module's jar:

```
installer/target/agentic-debugger-setup.jar
```

## Commands

Requires JDK 21+ and the Claude Code CLI (`claude --version` must work in the same terminal, or Claude Code installed in `~/.local/bin`).

```cmd
:: Install the MCP server and register "eclipse-debugger" for Claude Code (user scope)
java -jar installer\target\agentic-debugger-setup.jar install

:: Same, replacing an existing "eclipse-debugger" registration without asking
java -jar installer\target\agentic-debugger-setup.jar install --replace

:: Show whether the jar is installed, managed by this utility, and registered
java -jar installer\target\agentic-debugger-setup.jar check

:: Remove the registration and the files this utility installed
java -jar installer\target\agentic-debugger-setup.jar uninstall
```

| Command | What it does |
|---|---|
| `install` | Copies the embedded MCP server to `mcp-server.jar` in the folder below, then runs `claude mcp add --transport stdio --scope user eclipse-debugger -- <java> -jar <mcp-server.jar>` with the Java that runs the utility. Asks before replacing an existing `eclipse-debugger` registration; without a console it stops and leaves it unchanged. |
| `install --replace` | The same, replacing an existing registration without asking. |
| `check` | Reports the three states; exits with an error if any is missing. Run `/mcp` in Claude Code to confirm the live connection. |
| `uninstall` | Only if this utility installed it (`installation.properties` with `managedBy=agentic-debugger-setup`): removes the user-scoped registration, `mcp-server.jar` and `installation.properties`. Never deletes the folder or other files. |

**Install folder** (`InstallPaths`):

| System | Folder |
|---|---|
| Windows | `%LOCALAPPDATA%\agentic-debugger-for-eclipse` (default `C:\Users\<you>\AppData\Local\agentic-debugger-for-eclipse`) |
| Linux | `$XDG_DATA_HOME/agentic-debugger-for-eclipse` if set and absolute, otherwise `~/.local/share/agentic-debugger-for-eclipse` |
| macOS | `~/Library/Application Support/agentic-debugger-for-eclipse` |

The utility uses only the official `claude mcp` commands. It never edits `~/.claude.json` and never touches a project `.mcp.json`.

## Using it together with the plugin

Both the utility and the plugin's Preferences page register the **same server name**, `eclipse-debugger`, in user scope. Use one of them, not both:

| | Setup utility | Plugin Preferences |
|---|---|---|
| Server jar | `<install folder>\mcp-server.jar` | `~\.eclipse-agentdebug\agentic-debugger-mcp.jar` |
| After a plugin update | Run `install --replace` again with the new utility, so the MCP server matches the plugin version | Refreshed automatically on every Eclipse start |

If the Preferences page finds a registration made by this utility, it recognizes it and offers to move it to the plugin's own jar (tested by `CR-U1` in the plugin).

**Caution (OPEN-29):** `uninstall` decides from its own `installation.properties` file only. If you have moved to the Preferences page, `uninstall` would still remove the `eclipse-debugger` registration, even though it now belongs to the plugin. Until this is fixed, after switching to the Preferences page do not run `uninstall`; delete `mcp-server.jar` and `installation.properties` from the install folder by hand.

## Manual registration without this utility

Any copy of the MCP server jar works: the one this utility installed, the one the plugin stages in `~\.eclipse-agentdebug\`, or `mcp-server\target\agentic-debugger-mcp-1.0.0-SNAPSHOT.jar` from a build.

**Claude Code:**

```cmd
claude mcp add --transport stdio --scope user eclipse-debugger -- "C:\path\to\java.exe" -jar "C:\path\to\mcp-server.jar"
```

**Any other MCP client** that starts stdio servers: command `java`, arguments `-jar <path to the jar>`. For example:

```json
{
  "mcpServers": {
    "eclipse-debugger": {
      "command": "C:\\path\\to\\java.exe",
      "args": ["-jar", "C:\\path\\to\\mcp-server.jar"]
    }
  }
}
```

The server connects to the Eclipse bridge on port **8765** by default. Only when Eclipse uses another bridge port (for example the dedicated test Eclipse), set the environment variable `AGENTDEBUG_PORT` for the server, for example `claude mcp add --env AGENTDEBUG_PORT=<port> ...` or an `"env"` entry in the JSON.

## Tests

JUnit 5, run by `mvn clean package` (no Claude Code needed, nothing installed):

| Test | Checks |
|---|---|
| `InstallPathsTest` IP-U1..U4 | Install folder on Windows, Linux (default and `XDG_DATA_HOME`) and macOS |
| `ClaudeCodeDetectionTest` CD-U1..U3 | Finds `claude` in `~/.local/bin`, reads the Windows `Path` variable case-insensitively, clear error when the CLI is missing |

Install, check and uninstall against a real Claude Code are not automated; check them by hand with the commands above.
