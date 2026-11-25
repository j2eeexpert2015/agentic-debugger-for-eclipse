# Handoff for a new chat (25 Nov 2026, 11:30)

Read this first, then the README section **Status and tracking**, which is the single tracking list. This file only
says where the work stands and how we work; details live in the README and the documents it links.

## How we work (the owner's rules)

- **Complete files only**, never git patches. Several files: a zip in repository structure, extracted over the
  repository root (`powershell -Command "Expand-Archive -Force -Path '%USERPROFILE%\Downloads\<zip>' -DestinationPath ."`).
  One file: its exact destination and a `copy /Y` command.
- **Every change listed:** file, place in it, before and after, and why.
- **The assistant does not run builds or tests.** Compiling against stand-ins and running plain unit tests in its own
  copy is fine; the owner runs `mvn clean package`, the regression and the Claude trial and sends the output.
- **Exact commands**, plain Windows `cmd`, no git commands (the owner uses GitHub Desktop). Deletions with
  `del /q` and `rmdir /s /q`. All commands of one round in one block chained with `&&` (the owner pastes them at once).
- **Always a complete, consistent set of files**, so a delivery never depends on an earlier one that was not applied.
- `examples/` stays unchanged. The README "Status and tracking" table is the only tracking list.
- Machines: main checkout `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse` (Windows, JDK 25, Eclipse 2026-09,
  Tycho 5.0.4); a second machine at `C:\All_GitHub_Desktop_Projects\...` (sync by commit and push).

## Starting a new chat

Attach this file (or point to the repository) and say which step comes next. The assistant should read this file and
the README table, ask for the output of the last check if it was not given, and continue from "Next" below. It should
not rebuild earlier work from memory: the files in the repository are the truth.

## Last delivery (25 Nov): phase 2 steps 2 to 4, checked 12:20 to 12:29

`flow-phase2-steps2-4.zip` (on top of the repository with `flow-phase2-step1.zip` and `handoff-1158.zip` applied):
`report/.../activity/FlowPage.java`, `FlowPageTest.java`; `mcp-server/.../DebugMcpServer.java` (purpose argument);
`testing/.../grading/ClaudeTranscriptEvaluator.java`, `ClaudeTranscriptEvaluatorTest.java`; `skills/eclipse-debugger/SKILL.md`;
`README.md`, this file, `docs/design/flow-report.md`.

```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse ^
 && powershell -Command "Expand-Archive -Force -Path '%USERPROFILE%\Downloads\flow-phase2-steps2-4.zip' -DestinationPath ." ^
 && copy /Y skills\eclipse-debugger\SKILL.md "%USERPROFILE%\.claude\skills\eclipse-debugger\SKILL.md" ^
 && call mvn clean package ^
 && java dev-e2e/DevE2E.java deploy --suite regression --approve-fixture-post ^
 && java dev-e2e/DevE2E.java test --tests claude:boot-quote --approve-claude --approve-fixture-post --report
```

Result 25 Nov 12:19 to 12:29: BUILD SUCCESS, regression all PASS, two Claude trials both `VERDICT PASS`,
`REPORT CHECK PASS` and `flowFields=7/7 purpose=2/2` (15 and 17 debugger calls; reports
`OrdersApplication_2026-11-25_12-27-32.html` and `..._12-29-06.html`). Claude gave every flow field and a purpose
on every call that takes one, two runs out of two. Still to confirm by eye: the tracker row and "Why the agent stopped
here" in those reports; in the Agent Activity window, Flow filling node by node and Dark staying dark for the second
trial.
Fix after the owner's screenshot 12:33 (`tracker-number.zip`): Claude wrote the expected value as "0.10 (10%)"; the
tracker now compares the first number in such a text, so a correct 0.1 would show green (FP-U8 extended).
Then (screenshot 12:36: scrolled down, the tracker was out of sight and the mismatch line half under the pinned bar):
the tracker row and the mismatch line now sit in the pinned bar at the top, so they stay in view while the flow
scrolls; the detail pane keeps clear of the bar at any height (`tracker-pinned.zip`, contains the number fix too).
The build after it (12:37) failed once in the plugin test BR-U11 (stuck command releases its lane): a timing margin of
100 ms in the test itself (watchdog 400 ms, the next command's deadline 500 ms) was too small for a busy machine; the
plugin code was not changed. The test now gives that command 1.2 s (`build-fix-1240.zip`, contains everything since
`handoff-1229.zip`).

### Phase 2 step 1, checked 11:58

The earlier delivery (`flow-live-themes.zip`, flow report and live flow with both looks) was built and tested 25 Nov
11:26 to 11:32: BUILD SUCCESS (`report` 47, `mcp-server` 26, `testing` 18, `eclipse-plugin` 48), regression all PASS,
trial `VERDICT PASS` and `REPORT CHECK PASS` (report `OrdersApplication_2026-11-25_11-32-17.html`). Its look (window Flow,
Dark staying dark, the report) is still to be confirmed by eye.

`flow-phase2-step1.zip` (on top of the repository as pushed after `flow-live-themes.zip`): report module
`ReportEvent.java`, `ActivityEvents.java`, `activity/ActivityStore.java`, `activity/FlowPage.java`, `FlowPageTest.java`;
mcp-server `DebugMcpServer.java`, `SessionReport.java`, `FinishAnalysis.java` (new), `FinishAnalysisTest.java` (new);
`skills/eclipse-debugger/SKILL.md`; `README.md`, this file, `docs/design/flow-report.md`.

(Delivered as `flow-phase2-step1.zip`.)

Result 25 Nov 11:48 to 11:58: BUILD SUCCESS, regression all PASS, trial `VERDICT PASS` (14 debugger calls, 20 turns,
44 s) and `REPORT CHECK PASS`. Report `OrdersApplication_2026-11-25_11-58-15.html`: Claude filled every new field
without being told more than the skill and tool description say: title, trackedValue (rate, 0.10, 0; the tool's check
says OBSERVED, rate = 0 at OrderService.quote:24), a finding at the stop, rootCause OrderService.java line 23 (the tool
read the real file and highlighted `int rate = percent / 100;`), fix, two notObserved items, assumedPath. (The first
`deploy` in that round stopped because Eclipse was still open; closing it and running again worked.)

## The standard check

Close Eclipse first, then:

```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse ^
 && call mvn clean package ^
 && java dev-e2e/DevE2E.java deploy --suite regression --approve-fixture-post ^
 && java dev-e2e/DevE2E.java test --tests claude:boot-quote --approve-claude --approve-fixture-post --report
```

Expected now: BUILD SUCCESS with unit tests `report` 49, `mcp-server` 30, `testing` 19, `eclipse-plugin` 48;
regression 23/23; the trial `VERDICT PASS` and `REPORT CHECK PASS`.

## Where things stand

**Modules** (7 in the reactor): `report` (events, isolation, HTML report writer, the Agent Activity store and page;
no dependencies; embedded in the plugin as `lib/report.jar`), `mcp-server`, `testing` (grader, the direct bridge
verifiers in package `e2e`, the ownership harness in `ownership-tests/`), `eclipse-plugin`, `eclipse-feature`,
`eclipse-update-site`, the parent `pom.xml`. `dev-e2e/` holds `DevE2E` (build, deploy, test runs) and the Claude trial.

**OPEN-38 codebase reduction** ([analysis](codebase-reduction-analysis.md)):
- Steps 1 to 3 done and checked (unused files, documents, one JSON reader, no legacy bridge).
- Steps 4 to 6 done and checked on 25 Nov: report as one module with our own HTML writer (ChainTest gone), installer
  removed, test commands only in a `DevE2E` Eclipse, `e2e-runner` merged into `testing`. Build with the test counts
  below, regression 23/23, Claude trial PASS with REPORT CHECK PASS (three runs). Result: 189 files to 125, 13 Maven
  modules to 7. OPEN-38 is done.
- Not done on purpose: one runner with scenario classes (most verifiers already share `PhaseOneBridgeVerifier.Bridge`
  and `.Json`; a rewrite only the live regression can check costs more than it saves).

**OPEN-36 Agent Activity** (live view of what the agent does):
- The page shows the steps in time order (running step red and breathing, done green), the pinned banner with a
  blinking dot, the current stop with call chain and variables, Follow, Expand All, Collapse All, Open HTML Report,
  clickable source references. The same page is the HTML report (`ActivityPage.report`).
- New on 25 Nov: Agent Activity opens in a window of its own (checked by the owner: stays on the right after the switch to Debug)
  (`ActivityWindow`, belongs to the Eclipse window, not to a perspective, so the switch to Debug cannot hide it;
  remembers its place); the docked view stays available (Preferences > Agentic Debugger > "Show Agent Activity in its
  own window" off). And the terminal views are carried into the Debug perspective (`TerminalCarrier`; same sessions,
  nothing chosen or started). Manual check for the terminal: Java perspective with a Terminal view open (any shell),
  run the trial, the Terminal must be there after the switch to Debug, below the Debug view.
- Fixed 25 Nov after the owner's look at a trial report: the stop panel no longer takes an expanded variable's fields
  as the frame's variables, and the report shows the last stop open (AP-U10). Checked 10:23 with a trial.
- `DevE2E` writes its logs (`eclipse-console.log`, the p2 log) to `dev-e2e/target`, outside every Maven module, so
  `mvn clean` works while the test Eclipse is still open. Start Maven with `call mvn ...` in `&&` chains.

**OPEN-30 R4 program-flow report** ([design](design/flow-report.md)), the owner's request after the IntelliJ
investigation page:
- Phase 1 delivered 25 Nov, built and tested 11:32 (look to be confirmed): the HTML report opens on "Program flow" (`FlowPage`, tests FP-U1..U5):
  nodes for preparation, HTTP request, each stop (with the calls made there), HTTP response, never-hit breakpoints,
  no stop, cleanup and the agent's analysis; details of the selected node on the right; Previous, Next, Replay; tags
  OBSERVED and FROM THE AGENT; the earlier step list stays as "All steps" (the REPORT CHECK markers are there).
  Built only from recorded data. Dark look by default (after the owner's IntelliJ example), light look on a button.
- The live Agent Activity window shows the same flow ("Flow", default; "Steps" is the earlier list): one column, a
  click opens a node, the newest open, the running call pulsing in its node (FP-U6). Dark / Light button, choice kept in the Eclipse preferences (`activityTheme`).
- Phase 2 designed, not started: `purpose` arguments, structured fields on `finish_debug_investigation` (title,
  tracked value, findings per stop, root cause with the source lines read by the tool, fix, not observed, assumed
  path), cross-checks against the recorded values, value tracker, skill text, a grader metric.
- Phase 2 step 1 done 25 Nov (checked 11:58, Claude filled every field): optional structured fields on
  `finish_debug_investigation` (`FinishAnalysis` in the MCP server reads the root cause's source lines from the
  project), shown in the flow: findings at their stops, assumed path node, the agent's node with the value check
  (OBSERVED / DIFFERS / NOT RECORDED), root-cause box with highlighted source lines, fix, not observed (FP-U7,
  FA-U1..U4). Skill updated: copy `skills\eclipse-debugger\SKILL.md` to `%USERPROFILE%\.claude\skills\eclipse-debugger\`.
- Phase 2 steps 2 to 4 done 25 Nov (two trials 12:27 and 12:29: flowFields=7/7, purpose=2/2): `purpose` on breakpoints, evaluations and
  steps shown at the stops; value tracker row with FIRST WRONG and the mismatch line; grader metric (`flowFields`,
  `purposeCalls`) on the VERDICT line and in the trial summary (FP-U8, EV-U19).
- Next: the same tracker in the live window is not planned (the agent's fields arrive only at the finish); open
  candidates: OPEN-32 (skill installed by the plugin, now worth it), R2b/R3, OPEN-35, OPEN-03.

**Where the code is** (for the flow work): `report/src/main/java/org/learningfromexperience/agentdebug/report/activity/`:
`FlowPage` (grouping into nodes, `render` for the report, `live` for the window, `CSS`, `LIVE_CSS`, `DARK_CSS`,
`SCRIPT`), `ActivityPage` (`shell(dark)` and `update` for the window, `report` for the HTML file, step list, current
stop), `ActivityStore` (events in memory). Step titles and details come from `mcp-server/.../SessionReport.java`
(`title`, the variables table, the "Result:" technical line). The plugin shows the page in `ui/ActivityPanel.java`
(page actions `open`, `openReport`, `theme`). The assistant checks its work with the report unit tests run outside
Maven against JUnit stand-ins, and renders reports from the 10:23 trial data in Chrome to look at them.

**Other open items** (details in the README table): OPEN-35 manual check, OPEN-37 (mostly covered by R4 phase 1; source line under stops open),
OPEN-30 R2b/R3, OPEN-03 ownership harness by hand (`testing/ownership-tests/run.ps1` after a deploy, Eclipse closed),
OPEN-22/23, OPEN-31 to OPEN-34, and the older test items. `examples/README.md` has two dead links to deleted documents;
left unchanged by the rule above until the owner decides.
