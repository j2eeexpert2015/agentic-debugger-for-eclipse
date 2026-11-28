# Release checks with Copilot and Agent Activity (OPEN-23, OPEN-36, OPEN-58, OPEN-12)

[Status and tracking](../STATUS.md#status-and-tracking) · [Handoff](handoff.md) · [Copilot notes](copilot-integration-notes.md)

Manual checks in the test Eclipse that the automated tests cannot do: a real agent (GitHub Copilot) and what the user
sees. Run them after the build, deploy and regression of the delivery (handoff §2). Each check says what to type, what
to expect and what to send back. Record the result in the README row named after the check.

Setup once: start the test Eclipse (`java dev-e2e/DevE2E.java start`), open Copilot Chat. Leave the Copilot tool
settings as they are (all eclipse-debugger tools ticked under Tool Auto Approve): the consent question must come from
Eclipse, not from Copilot.

## A. Consent before code runs (OPEN-58)

Mode **Agentic Debugger**, new chat:

```
Use the eclipse-debugger tools to debug OrderService in spring-boot-debugging and tell me why the total is wrong. When you are stopped at the breakpoint, use evaluate_expression to compute percent / 100.0 and show me the result.
```

1. When the agent calls `evaluate_expression`, Eclipse shows **"Agentic Debugger: allow the agent to run code?"** with
   the expression, the place (`OrderService.quote:23 (thread ...)`) and three buttons. Expected: the chat waits.
   Screenshot the dialog.
2. Click **Deny**. Expected: the chat shows a reply starting with `Refused:`; the agent does not retry the same
   evaluation and continues with variables; the investigation still finishes with the right cause (line 23).
3. New chat, same prompt, click **Allow for this debugging session**. Expected: the value (`0.1`) comes back; a
   second evaluation in the same run is not asked again.
4. Optional: new chat, same prompt, do not answer for 90 s. Expected: the dialog closes itself, the reply says
   "no answer from the user in Eclipse within 90 s".

Send: the dialog screenshot, and for 2 and 3 the end of the chat.

## B. Copilot without our agent must not use our tools (OPEN-60; until 27 Nov 14:12 the opposite, OPEN-23 b)

Mode **Agent** (Copilot's own, not Agentic Debugger), new chat:

```
The test OrderServiceTest.calculatesDiscount in spring-boot-debugging fails. Debug it and tell me why.
```

Expected: **no** eclipse-debugger tool and **no** "Run Sub Agent" to Agentic Debugger; Copilot works with its own tools
(it may use its Debugger mode or `java_debugger`). Repeat in mode **Ask**. Send: any eclipse-debugger tool or
"Run Sub Agent" seen (yes or no), the first lines of the chat.

## C. Copilot scenarios (OPEN-23, release requirement)

Mode **Agentic Debugger**, a new chat for each:

| # | Prompt | Expected cause |
|---|---|---|
| C1 | `OrderCalculatorTest in phase-one-debugging fails. Debug it with the eclipse-debugger tools and tell me why.` | `OrderCalculator.calculate` line 5, `double discountRate = discountPercent / 100` (integer division: 0.0; plain Java + JUnit) |
| C2 | `The test OrderServiceTest.calculatesDiscount in spring-boot-debugging fails. Debug it and tell me why.` | `OrderService.quote` line 23, `int rate = percent / 100` (Spring Boot project, JUnit) |
| C3 | `In spring-boot-debugging, POST /orders/quote with quantity 2, unitPrice 60 and discountPercent 10 returns 120.00 instead of 108.00. Start OrdersApplication with the eclipse-debugger tools, then send that request and find where the value first goes wrong.` | the same line, observed in the request thread; Copilot sends the request with its own terminal (an "Allow" click for the command) |

For each: right cause (yes or no), `finish_debug_investigation` called (yes or no), report path given (yes or no),
anything refused (red lines), number of tool calls if Copilot shows it.

## D. Agent Activity window (OPEN-36)

Watch the Agent Activity window during the runs above, and do these extra cases:

| # | Case | How | Expected |
|---|---|---|---|
| D1 | Normal run | any of C1 to C3 | opens by itself at the right without taking the keyboard; banner thinking (orange) / debugging (red) / finished (green); "Paused in Class.method:line" while stopped; the agent's analysis at the end; Open HTML Report works |
| D2 | Run without a stop | prompt: `Run StepFilterApplication in phase-one-debugging in the debugger with the eclipse-debugger tools, without breakpoints, and tell me what it prints.` | warning that the program ended without a stop; finished banner |
| D3 | Window closed | close Agent Activity in the middle of a run | the run goes on; clicking the agent status in Eclipse's status bar opens it again with the full history |
| D4 | Agent stops | press Stop in the Copilot chat in the middle of a run | after 2 minutes the warning "no agent activity"; the agent's own program and breakpoints are cleaned up at the latest after 10 minutes (banner: ended without the agent finishing it) |
| D5 | Reports off | close the test Eclipse, `set AGENTDEBUG_REPORT=off` in the cmd window, start it from that window with `java dev-e2e/DevE2E.java start`, run C2 | no report file is written and Open HTML Report has nothing to open (not checked how it shows that; I think Copilot passes the variable on to our server, also not checked) |
| D6 | Copilot Chat comes along (OPEN-59) | start any of C1 to C3 from the Java or Resource perspective with the Copilot Chat open | Eclipse switches to Debug and the Copilot Chat is there with the same conversation, still running; typing in it works |

Send: a screenshot per case, and anything that looked wrong.

## E. The approval dialog (noticed 27 Nov)

Copilot opens "Confirm MCP Server Registration" only on a click: the tool button (wrench) in the chat bar, menu
item for new MCP registrations, or Preferences → GitHub Copilot → Model Context Protocol → Edit MCP Servers from
Plug-ins (Copilot's code: `McpExtensionPointManager.approveExtMcpRegistration`, called from `ActionBar` and
`McpPreferencePage` only). It is not opened at start. What matters is whether Copilot sees our server as **new or
changed** at a start: then the wrench button carries a **red dot**.

At the next two starts of the test Eclipse (one after `deploy`, one plain `start`), before clicking anything: does
the wrench button in the Copilot chat bar have a red dot (yes or no)? No red dot means nothing to fix.

## F. Only the Agentic Debugger agent, nothing blocks (OPEN-60, OPEN-23 d, OPEN-61)

Before: remove the old user-level agent copy from the (d) check
(`del "%USERPROFILE%\.copilot\agents\agentic_debugger.agent.md"`) and the project copies (project menu → Agentic
Debugger → Remove), so only the new one is tested.

| # | Do | Expected |
|---|---|---|
| F1 | Restart the test Eclipse (after deploy). Preferences → Agentic Debugger → GitHub Copilot | Checkbox *Only the Agentic Debugger agent, without confirmations* ticked; the line under it says our tools are off for Agent mode and Copilot runs them without asking |
| F2 | Same page → Agentic Debugger agent → **Install** | "Installed ...\.copilot\agents\agentic_debugger.agent.md"; no duplicate note |
| F3 | Copilot's mode list → Configure Agents... once, close; choose **Agentic Debugger**, new chat, the prompt of B | Our tools run; **no** "Confirm MCP Tool", no "Run command in terminal ... Allow", no "allow the agent to run code?" dialog; `finish_debug_investigation` called |
| F4 | New chat in **Agent** mode, the prompt of B | As B: no eclipse-debugger tool, no "Run Sub Agent" |
| F5 | In the F3 chat switch the mode to **Agent** and ask "Debug it again with the eclipse-debugger tools" | No eclipse-debugger tool (Copilot says it does not have them) |
| F6 | Copilot's Tools page (Preferences → GitHub Copilot → ...), mode **Agent** | Our group unticked; mode **Agentic Debugger**: our tools and read_file, file_search, grep_search, list_dir ticked. Do not click Apply for the Agentic Debugger mode (Copilot would rewrite our file) |
| F7 | Preferences → Agentic Debugger → **Check setup** | No FAIL; agent "all workspaces" up to date; tools off for Agent mode; tools run without confirmations; MCP server's tools match the agent's list. **Copy report** and send it |
| F8 | Untick the checkbox, restart, new **Agent** chat, prompt of B | Agent mode may now use our tools, and Copilot asks before each one ("Confirm MCP Tool"); tick it again, restart, F4 again passes |
| F9 | Project menu → Add Agentic Debugger Agent on spring-boot-debugging | The message notes the agent is in 2 places; Check setup warns "installed 2 times"; Remove it again |
| F10 | Preferences → Agentic Debugger → tick *Ask before running expressions and tracepoints*, Agentic Debugger chat: "Evaluate percent / 100.0 at the stop" | The dialog asks; untick it again: no dialog |
| F11 | 30 s after a start, look at the status bar right side | Nothing when all is fine; "check setup" in amber only when the check finds a problem (try: untick the checkbox while our server stays auto-approved by hand) |
| F12 | OPEN-69: Preferences → Agentic Debugger, then its pages Claude Code and GitHub Copilot, in the cases of `docs/design/preferences-pages.md` that can be made here (Claude Code not set up, set up, an older entry if one is at hand; Copilot agent installed and removed; Copilot skill own copy or the Claude Code copy). Then the Copilot approval case: launch so that Copilot asks again (another port, or deny our server), approve it on Copilot's MCP page | Each page says who it is for; only the buttons of the table are enabled; Remove (Claude Code) asks first; untick and tick the Copilot box: "Restart Eclipse to apply this change." only after a change. Main page: the result shows without a click (the last one at once with "Checked at …", then the fresh one); problems numbered at the top with what to do, then "Everything else is fine (N checks)"; in colour. Copilot page: "Server approved in Copilot: ✖ Not yet" with **Open Copilot's MCP page**, which opens that page in the same window; after approving and coming back: "✔ Approved". Approval case: the setup check shows one amber "Restart Eclipse to finish the Copilot setup." (no red row); the Copilot page says "⚠ Not in effect yet: restart Eclipse…"; Copilot's Tool Auto Approve list no longer has our server (the plugin took it back); **record whether Copilot's Agent mode now asks before our tools without a restart** (not verified); restart: "✔ In effect …", the setup check all OK, our server auto-approved again; every setting kept |
| F13 | OPEN-69 status bar and Eclipse without Copilot: (1) with a problem (for example our server Denied in Copilot), look at the bottom bar; fix it on Copilot's page without opening our page; (2) an Eclipse without GitHub Copilot (a second install, or Copilot uninstalled), open Preferences → Agentic Debugger and its GitHub Copilot page | (1) "⚠ Agentic Debugger: 1 thing to fix" at the left of the bottom bar, not next to Copilot's icon; tooltip lists the problem and "Click to open the setup check."; a click opens the main page; after the fix it goes away within a few seconds (at most a minute), without a restart. (2) Main page link "GitHub Copilot (not installed)", setup check without a Copilot group; the GitHub Copilot page shows only the one line, no sections; nothing about Copilot in the status bar. (3) After Eclipse was stopped without closing (Task Manager), start it: still **one** item in the bottom bar |
| F14 | OPEN-70 welcome after installing, in an Eclipse that is not the `DevE2E` test Eclipse (there it is never shown): install the update-site zip (Help → Install New Software → Add → Archive) or from the update site; to see it again, close Eclipse and delete the line `setupPromptShown=true` from `<workspace>\.metadata\.plugins\org.eclipse.core.runtime\.settings\org.learningfromexperience.eclipse.agentdebug.prefs`. Cases that can be made: with Claude Code and Copilot; Copilot uninstalled; Claude Code not on the PATH (start Eclipse from a shortcut with a PATH without it) | "Agentic Debugger is installed." in bold; one line per agent, ✔ green when found, grey "– … not installed" otherwise; "You can also do this later in **Window → Preferences → Agentic Debugger**." with the path bold; Later closes it; Set up now opens Preferences on the Claude Code page (Copilot page when only Copilot); no second popup; with no agent only OK. GitHub Copilot page without Copilot: the two lines, "Help → Eclipse Marketplace" bold. Not shown again at the next start |
| F15 | OPEN-71 Claude Code after an Eclipse update (Windows). Before: Claude Code set up with an older plugin (entry `java.exe -jar …`). Install this version, restart Eclipse, open Preferences → Agentic Debugger → Claude Code | "⚠ Needs an update" with **Update**; Update → "✔ Set up" and "Start a new Claude Code session…"; `claude mcp get eclipse-debugger`: Command `cmd`, Args `/c C:\Users\<you>\.eclipse-agentdebug\start-mcp-server.cmd`, Status ✓ Connected; a new Claude Code session lists the eclipse-debugger tools in `/mcp` and debugs; ~/.claude/skills/eclipse-debugger/SKILL.md says v0.1.8. Automated parts of F12-F15: `mvn clean verify -Pui-tests` (docs/ui-tests.md; UI-05 is this check with throw-away Claude settings) |

