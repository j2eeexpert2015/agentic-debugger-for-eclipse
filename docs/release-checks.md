# Release checks with Copilot and Agent Activity (OPEN-23, OPEN-36, OPEN-58, OPEN-12)

[README](../README.md#status-and-tracking) · [Handoff](handoff.md) · [Copilot notes](copilot-integration-notes.md)

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

## B. Copilot without our agent (OPEN-23 b)

Mode **Agent** (Copilot's own, not Agentic Debugger), new chat:

```
The test OrderServiceTest.calculatesDiscount in spring-boot-debugging fails. Debug it and tell me why.
```

Expected: Copilot uses the eclipse-debugger tools and starts the test itself (`start_junit_test`). Before 27 Nov it
handed the work to its built-in "Debugger" agent, whose `java_debugger` asked the user to start a debug session.
Send: whether it handed off (yes or no), which tools it used, the end of the chat.

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
