# Preferences pages by agent (OPEN-69)

Ayan, 28 Nov 2026 04:04-04:21: the two preference pages have too much text and do not say which part is for
Claude Code and which for GitHub Copilot. Approved direction: mockup v3
(`preferences-pages-by-agent-mockup-v3.html`). Requirement (04:21): cover every case; a control is enabled only
when its action can work and is safe.

This is a layout and wording change. Preference keys, defaults and the actions behind the buttons stay the same.

## Pages

| Page (tree) | Badge | Contents |
|---|---|---|
| Agentic Debugger | ALL AGENTS | While an agent debugs (2 options), Code the agent runs (1 option), Setup check |
| Agentic Debugger > Claude Code (new) | CLAUDE CODE ONLY | Setup, Skill |
| Agentic Debugger > GitHub Copilot (existing id `...preferences.copilot`) | GITHUB COPILOT ONLY | Agentic Debugger agent, Who can use our tools, Skill |

The existing page ids stay (`...agentdebug.preferences`, `...agentdebug.preferences.copilot`); the Claude Code page gets
a new id. Every place that names a page path ("Preferences > Agentic Debugger ...", about 21 in code and docs) and every
place that opens a page by id (`AgentStatusControl`) is updated. Explanations move into tooltips (ⓘ).

## Claude Code page

**Setup line**: from `claude mcp get eclipse-debugger` (`ClaudeIntegration.status()`), run in the background when the
page opens and on Check again; never on the UI thread. **Second line**: from Eclipse (the MCP server's connection to
the bridge carries the client name and version; Agent Activity knows running investigations; the last finished
investigation's time is stored), shown only when Setup is OK.

| Case | Setup line | Note / second line | Set up | Update | Remove | Check again |
|---|---|---|---|---|---|---|
| Check running (page opened, or after a click) | Checking… | — | off | off | off | off |
| Action running (Set up, Update, Remove) | Setting up… / Updating… / Removing… | — | off | off | off | off |
| Claude Code not found (no `claude` on the PATH) | ✖ Claude Code not found | Install it, open a new terminal, click Check again | off | off | off | **on** |
| Check failed (timeout 30 s, error) | ⚠ Couldn't check | What failed; nothing was changed | off | off | off | **on** (main) |
| Not set up | ✖ Not set up | — | **on** (main) | off | off | on |
| Set up, this version, just set up | ✔ Set up | Start a new Claude Code session; open sessions don't see the change | off | off | on | on |
| Set up, never used | ✔ Set up | Not used yet. Start Claude Code and ask it to debug | off | off | on | on |
| Set up, a session open, idle | ✔ Set up | ● A Claude Code session is open with our tools (version) | off | off | on | on |
| Set up, debugging now | ✔ Set up | ● Debugging now: (title), started (time) · Open Agent Activity | off | off | on | on |
| Set up, used before | ✔ Set up | Last debugging session: (when), (title), (outcome) | off | off | on | on |
| Older entry of this plugin | ⚠ Set up by an older version | Claude Code would start the old server | off | **on** (main) | on | on |
| Someone else's `eclipse-debugger` entry | ⚠ Another 'eclipse-debugger' entry exists | Left unchanged; commands to see and remove it | **off** | **off** | **off** | on |
| Removed while a session is open | ✖ Not set up | The open session keeps our tools until it ends | on (main) | off | off | on |

Never enabled: anything that would change an entry that is not ours; two actions at once.

**Skill** (folder `~/.claude/skills/eclipse-debugger`, shared with Copilot; works without the `claude` command):

| Case | Line | Install | Update | Remove |
|---|---|---|---|---|
| Up to date | ✔ eclipse-debugger 0.1.7, up to date | off | off | on |
| Older | ⚠ 0.1.6 installed, 0.1.7 available | off | **on** (main) | on |
| Missing | ✖ Not installed | **on** (main) | off | off |
| Folder there but not ours (no version we know) | ⚠ A different skill with this name | off | off | off (left unchanged) |
| Action running | Installing… / Updating… / Removing… | off | off | off |

Remove always asks first: "The skill is shared: removing it here also removes it for GitHub Copilot."

## GitHub Copilot page

| Case | Agent | Who can use our tools | Skill |
|---|---|---|---|
| Copilot not installed | Banner "GitHub Copilot isn't installed in this Eclipse. Not needed for Claude Code."; all controls **off** | off | off (managed on the Claude Code page) |
| Copilot installed, agent not installed | ✖ Not installed · **Install** (main) on, Remove off | on | as on the Claude Code page; Remove asks, naming Claude Code |
| Agent installed, current | ✔ Installed for all workspaces · Install off, Remove on | on | same |
| Agent installed, older | ⚠ Older version · **Update** (main) on, Remove on | on | same |
| Checkbox changed since Eclipse started | — | note "Restart Eclipse to apply this change." (only then) | — |
| Server not approved in Copilot | Shown in the setup check with a link to Copilot's MCP page; we cannot approve it for the user | — | — |
| Action running | all buttons off | off | off |

## Setup check (main page)

Results grouped under Eclipse, Claude Code and GitHub Copilot; problems first, each with one line saying what to do
and a link to the page that fixes it. All fine: one line "✔ All N checks OK". Copilot not installed is not a problem
(Claude Code users do not need it). Check setup is off while a check runs. Copy report copies the full text report.

## Tests to add

- A pure class that maps each case above to (status text, note, enabled buttons, main button), with one unit test per
  row of the three tables, so "enabled when it must not be" is caught without Eclipse.
- Release check: click through the three pages with Claude Code missing, not set up, set up; Copilot missing and
  installed; Eclipse restarted in between (settings kept).
