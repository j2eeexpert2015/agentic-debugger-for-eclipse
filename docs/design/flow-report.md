# Program-flow report (OPEN-30 R4)

Agreed with the owner on 25 Nov 2026 after his look at an IntelliJ debugging-investigation page: the report should
show **where the program went**, not only the list of tool calls, and it should say clearly which parts were
**recorded by the tools** and which are **the agent's own statements**.

Two phases:

| Phase | What | Source of the data | State |
|---|---|---|---|
| 1 | Program flow in the HTML report: nodes, details per node, Previous / Next / Replay, the old step list as "All steps" | Only what the tools recorded (the steps the MCP server already writes) | Delivered 25 Nov, waiting for check |
| 2 | The agent's reasoning at the nodes, a value tracker, a root-cause box with the source lines | New optional tool arguments and structured fields at the finish, cross-checked by the tool | Designed below, not started |
| 1 (live) | The same flow in the live Agent Activity window: one column, a click opens a node, the newest node open, the running call pulsing in its node; Flow and Steps | Phase 1 data | Delivered 25 Nov, waiting for check |

## Phase 1 (implemented)

Code: `report/src/main/java/.../report/activity/FlowPage.java`, used by `ActivityPage.report(...)`. Tests FP-U1 to FP-U5.

**Nodes**, built from the recorded steps in order:

| Node | Starts at | Takes in |
|---|---|---|
| Preparation | the first step | breakpoints, the launch and any other call before the first HTTP exchange or stop |
| HTTP request | "Sent HTTP request: ..." | |
| Stop | a stop step ("Stopped at breakpoint", "Stopped after step", "Stopped on exception", "Paused", "Thread suspended") | every call while the program stands there: variables, call stack, expansions, evaluations, the resume or step |
| No stop | "Program ended without stopping", "Waited for a stop: none (...)"; or added when breakpoints were set and nothing stopped ("No stop was reached") | |
| HTTP response | "Received HTTP response: N" | |
| Never hit | added for every breakpoint (not tracepoint) that was set but never stopped the program, before the cleanup (OPEN-37) | |
| Cleaned up | the finish or the automatic cleanup | |
| From the agent | added when the agent gave an analysis | |

A refused call stays at the node it was made at, is shown amber and counted ("REFUSED CALL").

**Per node** (right-hand side): step N of M, tags (OBSERVED, BREAKPOINT HIT, NEVER HIT, REFUSED CALL, FROM THE
AGENT); for a stop: location, call chain (three frames, the rest folded), thread, the frame's variables (values changed
since the previous stop highlighted with the previous value), "Moved: line 24 → 25" when the stop is in the same method
as the previous one, the fields of each expanded object folded (all expansions of one path together, without repeats),
the expressions with their results (or why they were refused); for every node the calls as chips ("expand_frame_variable
unitPrice ×2") and every call with its arguments and results folded below.

**Page:** the header as before (title, request, info line, banner with the time taken); the legend; the views
"Program flow" (default) and "All steps" (the earlier page, unchanged, with Expand All and Collapse All); the flow
on the left, the detail on the right; at the bottom Previous, Next, Replay (plays the flow from the start, a click
stops it), "Step N of M", a progress bar and one dot per node (a red ring for a breakpoint hit); keys ← → R. Done nodes
are full, later ones faded, the current one outlined, the arrows lit up to it.

**Two looks** (owner's request 25 Nov 11:12): **dark** (default), styled after the IntelliJ investigation page (navy
background, light text, coloured accents, the current node glowing and slightly larger, arrows with heads, gradient
progress bar), and **light**, high-contrast, matching the Agent Activity window. The "Light theme" / "Dark theme"
button switches; the browser remembers the choice when it allows it. Both cover the flow and "All steps".
(`FlowPage.CSS` light, `FlowPage.DARK_CSS` dark, on `body.report.dark`.)

**Without the script** (mail preview, some viewers) everything is still there: the flow, every node's details one below
the other (a node is a link to its details), then all steps. Nothing is loaded from outside the file.

## Phase 1 in the live window (implemented)

`FlowPage.live(inv)`, a new area `flow` of the page that `ActivityPage.update` replaces in place, styles `FlowPage.LIVE_CSS`.
The window is narrow, so one column: each node is a box; a click opens it and shows the same sections as the report's
detail (variables, expressions, calls, the agent's analysis). The newest node is open, earlier ones close as new ones
arrive, unless the user opened or closed one by hand (kept across updates, like the steps). The call the agent is making
now pulses red in its node ("Evaluated expression: percent / 100"). Breakpoints never hit and "No stop was reached" appear
only once the investigation is over (while it runs a breakpoint may still be hit). The toolbar has Flow (default) and
Steps (the earlier step list with the current stop and the analysis; Expand All and Collapse All show only there);
Follow works in both. Both looks here too: a Dark / Light button in the toolbar switches in place; Eclipse keeps the choice (preference `activityTheme`, light by default) for the window and the docked view.

## Phase 2 (designed, not started)

Goal: the parts of the IntelliJ page that need the agent (why it stopped somewhere, what it learned, the value it
followed, the root cause), **always tagged FROM THE AGENT**, and cross-checked by the tool where the recording allows.

### 1. New optional tool arguments

| Tool | Argument | Meaning | Shown |
|---|---|---|---|
| `set_breakpoint`, `set_conditional_breakpoint` | `purpose` (string, ≤ 200 chars) | why a stop here helps ("to see the discount values before the total") | the stop node of that breakpoint: "Why the agent stopped here" |
| `evaluate_expression` | `purpose` | what the expression should show | next to the expression |
| `control_debug_session` (STEP_*) | `purpose` | why the step | the next stop: "Why the agent stepped" |

Optional and short: an agent that leaves them out loses nothing. The MCP server records them in the step's technical
lines (`purpose: ...`); the report reads them from there (no change to the event model).

### 2. Structured fields on `finish_debug_investigation`

All optional, next to the existing `summary` (Markdown, still shown as "From the agent"):

```json
{
  "title": "Discount lost by integer division",
  "trackedValue": { "name": "discount rate", "expected": "0.10", "actual": "0" },
  "findings": [ { "at": "OrderService.quote:24", "text": "inputs and percent=10 correct; rate is 0" } ],
  "rootCause": { "file": "OrderService.java", "lines": "23", "explanation": "percent / 100 is integer division" },
  "fix": "BigDecimal.valueOf(percent).movePointLeft(2)",
  "notObserved": [ "line 23 itself was not stepped" ],
  "assumedPath": "OrderController.quote -> OrderService.quote"
}
```

Where they appear: `title` as the report's subtitle; `findings[].text` as "What the agent learned here" on the stop node
whose location matches `at` (unmatched findings go to the agent node); `trackedValue` as the tracker (section 4);
`rootCause` and `fix` as the root-cause box on the agent node; `notObserved` as "Not directly observed"; `assumedPath`
as a dashed node between the request and the first stop.

`FlowPage.Node.purpose()` and `finding()` already exist (always null in phase 1) and are rendered when set.

### 3. Cross-checks by the tool (what makes the tags honest)

- A finding's `at` must name a recorded stop; otherwise it is shown on the agent node with "no stop recorded here".
- A value the agent quotes as `actual` is compared with the recorded variables of the stops; a match gets a green
  "seen at OrderService.quote:24" note, no match an amber "not in the recorded values".
- `rootCause.file` and `lines`: the MCP server reads those lines from the project (the plugin already returns
  `projectLocation`) and the report shows them as code (OBSERVED: the file's text) with the explanation (FROM THE AGENT).
  A file outside the project or not found: the explanation only, with a note.
- Nothing the agent says ever turns a node or a value green.

### 4. Value tracker

A row under the header: the tracked value at each place it was seen (request body, each stop's variable, response),
filled in as Next or Replay reaches that node; the first place where the recorded value differs from `expected` turns
red ("FIRST WRONG") and a one-line mismatch note appears. Only recorded values are placed in the row; `expected` is the
agent's (tagged).

### 5. Skill and checks

- The skill asks for `purpose` on breakpoints and the structured finish fields (short, optional, never instead of the
  Markdown summary).
- New grader check (testing): in Claude trials, count how many of the fields the agent filled; a report-only metric at
  first, not a pass condition, so we learn whether agents use them before relying on them.
- Unit tests: matching of findings, the value comparison, source lines read and escaped, missing or wrong fields.

### Order of work for phase 2

1. Finish fields in the MCP server (`finish_debug_investigation` schema, recorded with the finish event) and the report
   (agent node: title, root-cause box with source lines, not-observed list, findings on stops).
2. `purpose` arguments and their display.
3. Value tracker and mismatch note.
4. Skill text and the grader metric; one Claude trial with `--report` to see what the agent fills.
