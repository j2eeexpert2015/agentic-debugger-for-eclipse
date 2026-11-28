# Preferences pages by agent (OPEN-69)

Ayan, 28 Nov 2026 04:04-04:21: the two preference pages have too much text and do not say which part is for
Claude Code and which for GitHub Copilot. Approved direction: mockup v3
(`preferences-pages-by-agent-mockup-v3.html`). Requirement (04:21): cover every case; a control is enabled only
when its action can work and is safe.

This is a layout and wording change. Preference keys, defaults and the actions behind the buttons stay the same.

**Built 28 Nov (`open-69-preferences.zip`).** Three corrections against the first version of this note, found while
reading the code behind each button:
1. The skill is not always shared. Copilot reads the Claude Code copy (`~/.claude/skills`) only when it exists;
   otherwise it gets its own copy in `~/.copilot/skills` (`CopilotSkill`). Removing on the Copilot page removes only
   that copy, and there is no Remove for the Claude Code copy (none existed; the plugin refreshes it at each start once
   Claude Code is set up). So no "remove for both" question is needed.
2. Remove is not offered for an entry of an older version: `ClaudeIntegration.remove()` removes only an entry that
   points to the current server. Update is the button there.
3. Eclipse cannot see an idle Claude Code session (the client name arrives only when an investigation starts), so the
   page shows "Debugging now", "Last debugging session" or "No debugging session recorded yet", never "session open".

Every case below is `PreferenceStates` (tests PS-U1..U8); the pages only draw what it returns.

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
| Claude Code not found (no `claude` on Eclipse's PATH) | ✖ Claude Code not found | Install it, then restart Eclipse (Eclipse reads the PATH when it starts) | off | off | off | **on** |
| Check failed (timeout 30 s, error) | ⚠ Couldn't check | What failed; nothing was changed | off | off | off | **on** (main) |
| Not set up | ✖ Not set up | — | **on** (main) | off | off | on |
| Set up, this version, just set up | ✔ Set up | Start a new Claude Code session; open sessions don't see the change | off | off | on | on |
| Set up, nothing recorded | ✔ Set up | No debugging session recorded yet. Start Claude Code and ask it to debug | off | off | on | on |
| Set up, debugging now | ✔ Set up | ● Debugging now: (title), started (time) · Open Agent Activity | off | off | on | on |
| Set up, used before | ✔ Set up | Last debugging session: (when), (title), (outcome) | off | off | on | on |
| Our entry no longer works (OPEN-71): the old form, its Java gone after an Eclipse update, or Claude Code's own check says "Failed to connect" | ⚠ Needs an update | Claude Code can't start the debugger with its current entry. Update fixes it; then start a new Claude Code session. | off | **on** (main) | **off** | on |
| Someone else's `eclipse-debugger` entry | ⚠ Another 'eclipse-debugger' entry exists | Left unchanged; commands to see and remove it | **off** | **off** | **off** | on |
| Removed while a session is open | ✖ Not set up | The open session keeps our tools until it ends | on (main) | off | off | on |

Never enabled: anything that would change an entry that is not ours; two actions at once.

**Skill** (`~/.claude/skills/eclipse-debugger`; refreshed by the plugin at each Eclipse start once Claude Code is set up):

| Case | Line | Install | Update |
|---|---|---|---|
| Missing | ✖ Not installed | **on** (main) | off |
| Up to date | ✔ eclipse-debugger 0.1.7, up to date · "Updated by itself when Eclipse starts." | off | off |
| Older | ⚠ 0.1.6 installed, 0.1.7 available | off | **on** (main) |
| Changed by you (a `.new` file next to it: the plugin kept your copy) | ⚠ Changed by you; kept · compare the .new files | off | off |
| Action running | Working… | off | off |

## GitHub Copilot page

| Case | Agent | Who can use our tools | Skill |
|---|---|---|---|
| Copilot not installed | Two lines only: **GitHub Copilot isn't installed in this Eclipse.** / "Install it from **Help → Eclipse Marketplace**, then restart Eclipse." (OPEN-70, 28 Nov 13:25: no "For GitHub Copilot only", no "Not needed for Claude Code") | — | — |
| Copilot installed, agent not installed | ✖ Not installed · **Install** (main) | on | see below |
| Agent installed, current | ✔ Installed for all workspaces · Remove | on | see below |
| Agent installed, older | ⚠ Older version installed · **Update** (main), Remove | on | see below |
| Agent changed by you | ⚠ Installed, changed by you · Update (keeps your file, writes .new), Remove (asks) | on | see below |
| Checkbox changed since Eclipse started | — | "Restart Eclipse to apply this change." (only then; it replaces the live status) | — |
| Server not approved in Copilot | — | first line "Server approved in Copilot: ✖ Not yet" · **Open Copilot's MCP page** (main); we cannot approve it for the user | — |
| Server approved | — | "Server approved in Copilot: ✔ Approved", no button | — |
| Action running | all buttons off | off | off |

"Who can use our tools" (OPEN-69, 28 Nov 10:00): box "Only the Agentic Debugger agent, without asking each time"
(tooltip: ticked = only our agent, no asking; unticked = every mode, Copilot asks before each tool). Under it a live
status read from Copilot's settings each time the page is drawn (`PreferenceStates.toolsStatus`, PS-U11):

| Box | Tools off for Agent mode | Auto-approved | Status |
|---|---|---|---|
| on | yes | yes | ✔ In effect: only the Agentic Debugger agent uses our tools, without asking each time |
| on | no | any | ⚠ Not in effect yet: restart Eclipse. Copilot's Agent mode can still use our tools. |
| on | yes | no | ⚠ Not in effect yet: restart Eclipse. Copilot asks before each of our tools. |
| off | any | any | Every Copilot mode can use our tools; Copilot asks before each one. |

The box does not untick itself after an approval: it is the user's choice, and the next start applies it again.
"Open Copilot's MCP page" finds Copilot's page by its label "Model Context Protocol" in the workbench's preference
pages and opens it in the same window; if it is not found, a message says where it is. The page draws again when it
is shown again (back from Copilot's page).

**Approval during a session (Ayan's video, 28 Nov):** approving our server makes Copilot switch our tools on for its
Agent mode again, while our auto-approval stays. The plugin watches Copilot's settings (`mcpToolsModeStatus`,
`mcpToolsStatus`) and takes back the auto-approval it added itself; the next start switches the tools off and adds it
back. The setup check then shows one amber row "Restart Eclipse to finish the Copilot setup." instead of the red
failure; the red one stays for the case where the start did not switch the tools off. Whether Copilot honours the
withdrawn auto-approval without a restart is not verified (release check F12).

Copilot's skill: uses the Claude Code copy (✔, no buttons: managed on the Claude Code page); own copy up to date
(Remove); own copy older (**Update** main, Remove); none (**Install** main). Remove deletes only the files the plugin
wrote and you did not change.

## Setup check (main page)

Runs by itself when the page opens: the last result of this session is shown at once with "Checked at 09:49", then
a fresh check runs in the background ("Checked at 09:49. Checking again…"). The button is "Check again"; it is off
while a check runs. Copy report copies the full text report. Copilot not installed is not a problem (Claude Code users
do not need it).

Layout (`SetupCheck.summary`, PS-U7), problems first and numbered, failures before warnings:

```
⚠ 2 things to fix
 1. ✖ Claude Code: ...
      → what to do
 2. ⚠ GitHub Copilot: ...
      → what to do

After you fix something, come back to this page or click Check again.

Everything else is fine (9 checks)
  Eclipse:  ✔ 3 OK
  Claude Code:  ✔ 2 OK
  GitHub Copilot:  ✔ 4 OK
  ℹ GitHub Copilot: a note
      → what to do
```

All fine: "✔ All N checks OK" and the same group lines. In colour (`SetupCheck.spans`, PS-U9): headline and "✔ N OK"
green or amber, numbered warnings amber, failures red, notes blue, group names bold, "→ what to do" and the hint
plain. When a problem was found, the check runs again when the page is shown again in the same Preferences window
(`setVisible`), so the result is never stale after a fix on another page.

## Status bar (all pages)

When the setup check found something and no investigation is shown: "⚠ Agentic Debugger: 1 thing to fix" (or "N
things to fix") at the **left** of the bottom bar, just before Eclipse's message area (Ayan 28 Nov 10:25: not next to
Copilot's items on the right). The tooltip lists the problems, numbered, and "Click to open the setup check."; a
click opens the main page. It follows a fix made anywhere: a change to Copilot's settings or ours runs the quiet
check again two seconds later, and while a problem is shown the quiet check runs every minute (it only reads files
and settings). The main page link reads "GitHub Copilot (not installed)" when Copilot is missing.

## Welcome after installing (OPEN-70)

Shown once, at the first Eclipse start with the plugin (preference `setupPromptShown`; not in the test Eclipse of
`DevE2E`). It sets nothing up itself, so it never shows an error; `PreferenceStates.welcome`, test PS-U12. Mockup:
`post-install-welcome-mockup.html` (28 Nov).

| Found | Lines | Buttons | Set up now opens |
|---|---|---|---|
| Claude Code and Copilot | **Agentic Debugger is installed.** Connect it to your AI agent: ✔ Claude Code found, ✔ GitHub Copilot found. You can also do this later in **Window → Preferences → Agentic Debugger**. | Later, **Set up now** | Claude Code page |
| Only Claude Code | … ✔ Claude Code found, – GitHub Copilot not installed (grey) … | Later, **Set up now** | Claude Code page |
| Only Copilot | … – Claude Code not installed (grey), ✔ GitHub Copilot found … | Later, **Set up now** | GitHub Copilot page |
| Neither | **Agentic Debugger is installed.** It works with Claude Code or GitHub Copilot for Eclipse. Install one, then restart Eclipse. | **OK** | — |

Claude Code "found" = the `claude` command is on the PATH or in `~/.local/bin` (a file lookup, nothing started).
Removed: the setup run from the dialog and its popups ("Configured at user scope…", "Already configured (user
scope)…", "Configuration failed…", "Update Agentic Debugger Registration"); the Claude Code page handles all of them.

## Tests to add

- A pure class that maps each case above to (status text, note, enabled buttons, main button), with one unit test per
  row of the three tables, so "enabled when it must not be" is caught without Eclipse.
- Release check: click through the three pages with Claude Code missing, not set up, set up; Copilot missing and
  installed; Eclipse restarted in between (settings kept).
