# Agentic Debugger for Eclipse IDE

Your AI agent debugs your code with Eclipse's **real debugger**, instead of guessing from the source.

- **Debugs:** Java applications, JUnit 5 tests, Spring Boot applications (including real HTTP requests)
- **Agents:** Claude Code (in a terminal) · GitHub Copilot Chat (inside Eclipse)
- **The agent:** starts the program or test, sets breakpoints, waits for stops, reads variables, steps through the code
- **You see:** every step live in Eclipse, and an HTML report of each investigation
- **Safe:** the agent touches only the breakpoints and launches it created, never yours, and cleans up after itself
- **No extra AI model:** it works with the agent you already use, through MCP

Wrong total, failing test, an endpoint returning 400? Describe the symptom; the agent finds the line and the values
that cause it.

## Install

1. In Eclipse: **Help → Install New Software… → Add…**
2. Location: `https://j2eeexpert2015.github.io/agentic-debugger-for-eclipse/` → **Add**.
3. Tick **Agentic Debugger for Eclipse IDE** → **Next** → accept → **Finish**. The plugin is not signed yet: accept the
   warning if you trust this project. Restart Eclipse.

**Needs:** Eclipse 2025-12 or later (*IDE for Enterprise Java and Web Developers*, or *IDE for Java Developers*),
JDK 21+, and Claude Code or GitHub Copilot for Eclipse 0.22+. Tested on Windows.

Later versions: **Help → Check for Updates**. Offline install from the zip on the
[Releases page](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases), with checksum:
[installation guide](docs/install-from-github-releases.md). Settings: **Window → Preferences → Agentic Debugger**
(one page per agent, and a setup check that lists anything to fix).

![Agent Activity: what the agent is doing, live](docs/images/agent-activity.png)

![Debugging session report](docs/images/report.png)

## Use with Claude Code

1. **Window → Preferences → Agentic Debugger → Claude Code → Set up.** This registers the plugin's MCP server with
   Claude Code. In the **Skill** section of the same page, **Install** adds the optional `eclipse-debugger` skill.
2. Keep Eclipse running with your project imported. In a terminal, start `claude`; `/mcp` lists `eclipse-debugger`.
3. Describe the symptom and ask Claude to debug it, for example:

   > My orders API returns 120.00, but I expect 108.00. Debug it in Eclipse and find the cause. Do not change the
   > source code or other running applications.

<details>
<summary>Registering by hand, or with another MCP client</summary>

The plugin keeps the MCP server in `%USERPROFILE%\.eclipse-agentdebug\agentic-debugger-mcp.jar`, refreshed at every
Eclipse start. For Claude Code:

```cmd
claude mcp add --transport stdio --scope user eclipse-debugger -- "C:\path\to\java.exe" -jar "%USERPROFILE%\.eclipse-agentdebug\agentic-debugger-mcp.jar"
```

Any other MCP client that starts stdio servers: command `java` (21 or newer), arguments `-jar <path to the jar>`. The
server connects to Eclipse on port **8765**; when Eclipse uses another port, set `AGENTDEBUG_PORT` for the server.
</details>

## Use with GitHub Copilot

1. Install GitHub Copilot for Eclipse (0.22 or later) and sign in.
2. **Approve the server once:** Window → Preferences → Agentic Debugger → **GitHub Copilot** shows
   "Server approved in Copilot: ✖ Not yet" → **Open Copilot's MCP page** → select *Agentic Debugger for Eclipse IDE* →
   **Approve**. Restart Eclipse afterwards.
3. On the same page, **Agentic Debugger agent → Install** (for all workspaces).
4. In Copilot Chat, choose **Agentic Debugger** in the mode list (next to Ask, Agent, Plan) and describe the symptom.

By default only the Agentic Debugger agent can use the debugger tools, and it runs them without asking each time;
Copilot's own Ask and Agent modes keep working as before. To let every Copilot mode use them (Copilot then asks
before each one), untick *Only the Agentic Debugger agent, without asking each time* on that page.

## What you get

- **Debugger control:** start Java programs, JUnit tests and Spring Boot applications in debug mode; line,
  conditional and exception breakpoints; tracepoints; step into, over and out; pause, resume, restart; step filters.
- **What the agent sees:** threads, stack, variables (nested objects too), program output, JUnit results, HTTP requests
  to a Spring Boot application.
- **Agent Activity:** a live view (or its own window) with what the agent is doing, where the program is paused and
  the values there, then the agent's conclusion.
- **Session report:** every investigation as one HTML file, in the project's `debug-reports` folder (the agent tells
  you the path). Add `debug-reports/` to `.gitignore` if you do not want to commit reports. Switch reports off with
  `AGENTDEBUG_REPORT=off`, or send them to one folder with `AGENTDEBUG_REPORT_DIR`.
- **Safety:** your own breakpoints and launches are never changed; code the agent runs in the program (expressions,
  tracepoints) can be set to need your approval (Preferences → Agentic Debugger → *Ask before running expressions and
  tracepoints*).

## Updating

Install the new version (Help → Check for Updates) and restart Eclipse. The MCP server and the skill are refreshed
from the plugin when Eclipse starts; start a **new** Claude Code session afterwards (a running session keeps the old
server). A skill file you edited yourself is kept, and the new version is put next to it as `SKILL.md.new`.

## Troubleshooting

Start with **Window → Preferences → Agentic Debugger**: the setup check runs when the page opens and lists anything to
fix. A problem also shows as "⚠ Agentic Debugger: … to fix" in Eclipse's status bar. **Copy report** gives the full
details for an issue.

| Problem | What to do |
|---|---|
| Plugin missing after installing | Help → About Eclipse IDE → Installation Details → Installed Software; restart Eclipse |
| Claude cannot reach Eclipse | Keep Eclipse running with the project imported; `/mcp` must list `eclipse-debugger`; close other Eclipse instances that use the same port |
| Old behaviour after an update | Start Eclipse normally once, then start a new Claude Code session |
| Copilot shows the server as *Denied* | Approve it again (step 2 above); Copilot asks again when the server's command changes, for example after an Eclipse update on Linux/macOS |
| "Restart Eclipse to finish the Copilot setup" | Approving the server switches our tools on for Copilot's Agent mode; the restart switches them off again |
| *Agentic Debugger* missing from Copilot's mode list | A Copilot issue: open **Configure Agents…** in that list once and close it ([details](docs/copilot-mode-list-bug.md)) |
| "Unknown investigationId … Refused" in the chat | The agent mistyped its investigation ID; nothing was changed and it normally retries |
| A breakpoint never stops | The line has code, the right application is launched, a condition is valid there |
| The agent names a cause without debugging | Ask it explicitly to debug in Eclipse; the session report shows what it actually did |

## Uninstall

Help → About Eclipse IDE → Installation Details → Installed Software → Uninstall. Remove the Claude Code registration
first on Window → Preferences → Agentic Debugger → Claude Code → **Remove** (it never touches other entries).

## More

- [Status and tracking](STATUS.md): current plan, test status, every open and finished item
- [Contributing](CONTRIBUTING.md): build, tests, development workflow, all documentation
- [Changelog](CHANGELOG.md) · [Releases](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases) ·
  [Installation guide](docs/install-from-github-releases.md) · [Capabilities](docs/capabilities.md)
