# Design documents

| Document | Content | Status |
|---|---|---|
| [flow-report.md](flow-report.md) | **Program-flow report** (OPEN-30 R4, 25 Nov 2026): phase 1 (flow from recorded data, Previous/Next/Replay) and the detailed design of phase 2 (the agent's reasons, findings, tracked value and root cause, cross-checked by the tool) | Phase 1 implemented, waiting for check; phase 2 designed |
| [activity-view-requirements.md](activity-view-requirements.md) | **Agreed requirements** (24 Nov 2026) for OPEN-35 (keep the current perspective), OPEN-36 (Activity view) and OPEN-37 (report: what actually happened), in that order. Applies where it differs from the external design | Not implemented; plan steps 3 to 5 |
| [AGENTIC_DEBUGGER_LIVE_VIEW_AND_REPORT_DESIGN.md](AGENTIC_DEBUGGER_LIVE_VIEW_AND_REPORT_DESIGN.md) | Perspective behaviour, live **Agentic Debugger Activity** view in Eclipse (sections 1 to 11) and the visual contract of the HTML investigation report (section 12). Written against commit `775ac2d` (24 Nov 2026) | Design only, not implemented. Tracked as OPEN-35, OPEN-36, OPEN-37 in the [status list](../../STATUS.md#status-and-tracking) |

The same design also exists as two separate files (`AGENTIC_DEBUGGER_ECLIPSE_LIVE_VIEW_IMPLEMENTATION_HANDOFF.md` = sections 1 to 11, `AGENTIC_DEBUGGER_HTML_REPORT_DESIGN.md` = section 12); only the combined file is kept here, unchanged, so the text exists once.

## Review notes (24 Nov 2026)

The design is kept unchanged; these notes record where it differs from the code of 24 Nov and how the plan uses it. They take precedence over the design where they disagree.

**Confirmed against the code:** the plugin itself switches to the Debug perspective (`AgentDebugUiCoordinator` calls `showPerspective(ID_DEBUG_PERSPECTIVE)` when the preference `autoDebugPerspective` is on, default `true`); `plugin.xml` declares no view; the bridge is request/response only; `DebugStopTracker` discards unreported stops on resume and is not a history; `ReportingTools` wraps tools only when reporting is active; stacks are limited to 60 frames.

**Out of date in the design:**

1. Reports are **on by default** since R2c (24 Nov) and go to `<Eclipse project>\debug-reports` (risk 8, F-08, section 2 and section 12 assume "off unless `AGENTDEBUG_REPORT` is set").
2. Several report points of section 12 are already done (OPEN-30): exact prompt versus "As passed on by the agent" (RPT-01), agent conclusion in its own card (RPT-09, still to be labelled "not independently verified"), one self-contained offline file and no ChainTest dashboard or metadata (RPT-10), HTML escaping and Markdown rendering of the analysis, plain-language step titles, technical details collapsed, refused tool calls marked as the agent's and not the application's, dropped-event note.
3. F-01 names Eclipse 2024-03/2024-06 as test targets; the plugin currently requires 2026-09 (OPEN-31), so the matrix is 2026-03, 2026-06 and 2026-09.
4. Scenarios T1 to T3 (1,000 customers, swallowed import exception, `PricingService`/`ShippingService`) need the demo applications of OPEN-22; `examples/` does not contain them.
5. RPT-10 says source coordinates are text only; OPEN-33 (report opened in Eclipse with links to the code) is compatible: links work only in our Eclipse viewer, an ordinary browser shows a hint, nothing uses `file://`.

**Architecture (section 4), as agreed:** the Activity view is fed by the **same events as the report**: a second listener next to the ChainTest writer forwards them through one new, authenticated bridge command that only queues the event (no new port or token), plus a new "step started" event; a JDT listener in the plugin adds running/paused/ended. This keeps the request and the agent's analysis (known only to the MCP server) and one event model for report and view. (A first review note proposed recording at the bridge instead; replaced by this decision.) The full LiveDebugEvent schema, cross-process deduplication and the transport spike of the external design are not needed.

**Split into tracked items:**

| Item | Design sections | Size |
|---|---|---|
| OPEN-35 Keep the current perspective, show Debug/Variables/Breakpoints in it, Claude terminal stays | 3.1, F-01, stage 1 | Small, plan step 3 |
| OPEN-36 Activity view, fed by the report's events | 3.3, F-02 to F-05, F-10, stages 2 and 3, plus our own additions (pinned, phases, pulsing current step, finish banner with analysis, notification, warnings) | Large, plan step 4 |
| OPEN-37 Report: breakpoints set versus hit, "no stop captured", source line, changed values, conclusion label | parts of 12.3 only; last-stop card, counts, filters and integrity footer not taken | Medium, plan step 5 |
| Deferred | value history, observed journey polish, multi-JVM (F-06, F-09, R-UI4, stage 5) | — |

Source navigation is shared with OPEN-33.
