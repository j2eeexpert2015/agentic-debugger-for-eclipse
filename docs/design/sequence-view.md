# Sequence view of Agent Activity (OPEN-65)

Agreed with Ayan 28 Nov 2026 (mockups v1 to v4, Option A): a third view, **Flow | Sequence | Steps**, in the same
Agent Activity page. The banner, request, agent details, warnings, Now line, toolbar and the agent's analysis stay
shared. Nothing new is recorded: the view is drawn from what the MCP server and Eclipse already deliver.

## Data used (all existing)

| Part of the view | Source |
|---|---|
| Columns (classes) | the class of each stop (`FlowPage.nodes`, kind STOP, `where` = `Class.method:line`), first appearance first |
| Boxes (stops) | the same STOP nodes as Flow; number, method, line |
| "new: …" in a box | variables compared with the previous stop **in the same method** (changed or not there before) |
| Line kind: call | the previous stop's method is in this stop's call stack (`Frames:`) — observed |
| Line kind: step | same class and method, another line — observed |
| Line kind: back in the caller | this stop's method was in the previous stop's call stack — **inferred** |
| Line kind: next stop | neither; how the two places connect is not recorded |
| Command on a line | the last `control_debug_session` step (kind STEP) between the two stops |
| Thinking time | sum of the gaps between one step's end and the next step's start (`startedAt`/`finishedAt`) |
| Root cause box | at the finish only: the stop at the agent's `rootCause` file and first line (the agent's statement) |
| Selected panel | stop: `FlowPage.body` (exactly the Flow card); line: from, to, kind, command, thinking, steps in between; class: its stops; step: its details |
| Timeline | every step with its time since the start; thinking pauses of 3 s or more |

## Limits (said in the view)

- Only places where the program paused become boxes; methods passed through without a pause are not shown.
- "Back in the caller" is inferred from call stacks, marked INFERRED.
- The per-stop reason and the root cause are the agent's text, marked FROM THE AGENT.
- In a window narrower than 820 px the three panels stack (diagram, selected, timeline).

## Deliveries

1. **`open-65-sequence-1.zip`**: `SequencePage` (model, diagram, selected blocks, timeline), the third tab in the live
   view, light and dark styles, tests SQ-U1..U4. The selection is kept across live updates.
2. Next: the process strip (Prepare, Start program, Wait, Stops, Result, Clean up), one selection shared by all three
   views, thinking pauses in Flow and Steps, the Sequence view in the HTML report.
3. Then: colours and glow polished to mockup v4 everywhere (light mode: dark text only).
4. **`open-66-activity-v7.zip`** (OPEN-66, mockups v5-v7): "paused" wording; a colour per class (`SequencePage.CLASS_COLOURS`,
   seven, repeating: column, boxes, Selected title, timeline rows); a colour per kind of line (first pause rose, call
   cyan, step green, back in the caller violet) with arrowheads and legend; the waiting period (`ActivityPage.waitingForPause`:
   the program runs and has not paused, before the first pause or after a resume or step) as an amber box in the
   diagram, amber timeline rows and an amber "Waiting" banner; one status card; cleaner panels (`ActivityPage.CLEAN_CSS`).
