# Handoff for a new chat (25 Nov 2026, 17:45)

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

## Last delivery (25 Nov, 18:50): grader counts breakpoints the MCP server attached, waiting for check

The one Haiku check ran at 18:43 (`ClaudeAgentEndToEndTests#junitWrongDiscount -Dclaude.model=haiku`): VERDICT
PARTIAL, 20 turns, 47 s, $0.014, skill and `wait_for_stop` used, right values read (`percent` 10, `rate` 0), cause
named, finish CLEANUP, inventory clean. The only failed check was `changes-inside-investigation`: Haiku called
`set_breakpoint` without an `investigationId`. The MCP server already attaches such a change to the only open
investigation (`DebugMcpServer.ownedId`, reply starts with `attachedToInvestigation`), and the inventory shows it was
cleaned up, so the grader was stricter than the product. `ClaudeTranscriptEvaluator` now counts a change as inside
the investigation when its reply carries `attachedToInvestigation`; a change with neither still fails (EV-U20, EV-U21;
grader tests 21). The check's detail says who tied each change to the investigation: "the model sent investigationId
with [...]; the model did not send it with [set_breakpoint], so the MCP server attached them to the open investigation
[...]" (or, failing: "...and the MCP server did not attach them..., so finish does not clean them up"). Check: re-grade
the 18:43 transcript (no Claude usage); expected PASS with that detail. Trial needed: no.

(Before the run, `mcp-server\target` had no jar; `mvn -pl mcp-server -am clean package` rebuilt it. Cause unknown.)

## Delivery before (25 Nov, 18:10): an agent that left without finishing, shown and tested properly, checked 18:28

Result: build with all unit tests (report 51/51), deploy of 1.5.0.202611251238, `agentDisconnectCleansUp` PASS in
6.3 s (it now waits for the stop). Agent Activity screenshot: banner "Ended without the agent finishing it: agent closed
the MCP connection, so the MCP server finished it"; "Stops not checked" and "StepFilterApplication:9" tagged NOT CHECKED
only; "Preparation" and "Cleaned up automatically" OBSERVED.


Checked 17:42 (`report-not-checked.zip`): the window showed "Stops not checked" and NOT CHECKED, the replayed report
differed from the original only by the new style lines, REPORT CHECK PASS. The owner then pointed at what was still
wrong on the screenshot; three fixes (`agent-left.zip`):
- The two "not checked" nodes carried NOT CHECKED and OBSERVED at once: now NOT CHECKED only (`FlowPage.tags`,
  `STOPS_NOT_CHECKED`; FP-U9 checks the tags of every node).
- The banner said only "Ended without the agent finishing it": now also why, from the MCP server's record, e.g.
  "...: agent closed the MCP connection, so the MCP server finished it" (`ActivityPage.statusLine`, AP-U2 extended).
- The test `agentDisconnectCleansUp` continued as soon as the program was launched (2 s), so it never proved the
  realistic case. It now confirms through the bridge that the program is stopped at `StepFilterApplication:9` before
  the agent's connection closes, then that the breakpoint is gone and the stopped JVM ended.
Trial needed: no.

## Delivery before (25 Nov, 17:45): report says "not checked" instead of "never hit", checked 17:42

The owner saw, during the run below, the Agent Activity window of the test "Closing the agent connection cleans up":
"No stop was reached" and "StepFilterApplication:9 NEVER HIT". That test sets a breakpoint, starts the program and
closes its connection without ever asking whether the program stopped, so the report could not know; the program was
probably stopped there (exit value 1: ended by the cleanup). `FlowPage.missed` now checks whether the agent asked
(any wait for a stop or any stack/variables read): if not, the node is "Stops not checked" and each breakpoint
"Not checked" (new kind `UNCHECKED`, tag NOT CHECKED) instead of "No stop was reached" / "Never hit" (FP-U3 changed,
FP-U9 new; report tests 51). Also `testing/pom.xml`: the profile no longer lists the `-D` options again (Maven passes
them by itself; the list caused "System property ... overwritten" warnings). Trial needed: no (`report-replay`).

## Delivery before (25 Nov): integration tests as JUnit 5 (OPEN-18), checked 17:26 to 17:32

Result: build with all unit tests, `deploy` ready, `FullSuite` 30/30 in 3 min (every class green on the first run),
`ClaudeAgentEndToEndTests#springBootWrongQuote` VERDICT PASS (10 debugger calls, 17 turns, $0.21) with REPORT CHECK
PASS. OPEN-18 moved to Done.

The owner found the test group names meaningless (`phase1`, `phase2`, `acceptance`, `boot-gaps` describe when a test
was written) and the DevE2E options hard to read. In one delivery (`junit-integration-tests.zip`, it replaces the
unchecked `tests-by-feature.zip`):
- **Regrouped by feature** after a full inventory ([integration tests](integration-tests.md), section "What changed"):
  duplicates removed (`boot/discovery`, `boot/readiness`, `phase2/ownership`, `boot-gaps/owned-isolation`), the
  owner dropped `boot-gaps/readiness-timeout` (tested the fixture, not the product; flaky), overlaps merged. Regression
  23 → 21, full 38 → 30.
- **JUnit 5 classes** in `testing/src/test/java/.../e2e`: one per feature on the base class `EclipseIntegrationTest`
  (its own investigation per test, cleanup asserted in `@AfterEach`, one result line per test, a JSON report per
  class), test methods named after the behaviour with readable `@DisplayName`s, suites `RegressionSuite`,
  `ExtendedSuite`, `FullSuite` (`@SelectClasses`), approvals as JUnit conditions (skipped, not failed, without them),
  `@Timeout` 6 min. Run with `mvn -pl testing verify -Pintegration-tests [-Dit.test=...]` (failsafe profile in
  `testing/pom.xml`) or from the IDE. Settings come from `dev-e2e/e2e.properties` (`TestEnvironment`).
- **Claude trials** are `ClaudeAgentEndToEndTests` (3 tests, pass = verdict PASS; `-Dclaude.approved=true`,
  `-Dclaude.model`, `-Dclaude.report`, `-Dclaude.skill`); the runner moved to `testing/.../testing/trial/ClaudeSymptomTrial.java`
  (`run(...)` returns the verdict). The skill sync moved from DevE2E into the test class.
- **Step B:** every wait for a stop uses `WAIT_FOR_STOP` (`awaitStop`), the way the agent does it.
- **DevE2E** only prepares the test Eclipse: `config`, `build`, `all`, `deploy`, `start`, `report-replay`
  (866 → 494 lines); `test` and `list` say what replaced them.
- Checked by the assistant: everything compiles against the real JUnit 5.14.1 API (built from its sources) with all
  warnings as errors; the JUnit run itself was tried against a fake bridge: result lines, report, skips with reasons,
  the regression suite finding all 21 tests. Not checked: Maven's failsafe profile (no Maven access here) and the real Eclipse.
- Trial needed: no product change, but the Claude runner moved; the standard check runs one Claude test to confirm it.

## Earlier delivery (25 Nov, 15:15): OPEN-39 token use, items 1 and 2, checked 15:45 to 15:48

The owner asked (14:24) whether the tool and the tests spend tokens they need not. Analysis of the trial transcript
`boot-quote-20261125-065851-157.claude.jsonl` (Sonnet, $0.19, 25 turns, 20 API calls, cache read 676k, output 5.5k):
about 30k tokens of the first call are Claude Code's own context (the owner's skills, plugins and tools), our 18 tools
load on demand through ToolSearch (about 5k). Waste we cause: the agent expanded `BigDecimal unitPrice` twice (statics
listed first, the value hidden in `intCompact`), and after the finish it called `list_debug_sessions`,
`list_breakpoints` and `get_investigation_report` to check what the finish had done. Delivered in `token-use-1.zip`:

- **Testing:** every delivery says *Trial needed: yes/no* (rule in `docs/development-workflow.md` §6). A `--report` trial
  keeps the report's events (`<report>.events.jsonl`, `AGENTDEBUG_REPORT_EVENTS=1` set only by the trial);
  `java dev-e2e/DevE2E.java report-replay` rebuilds `<report>.replayed.html` from the newest one and runs the REPORT
  CHECK, so report and window changes need no trial (`ReportReplay`, HW-U6).
- **Product A, values:** `ValueText` shows BigDecimal, number wrappers, LocalDate/Time/DateTime, Instant and UUID as
  their value (read from the fields, no code run in the program; anything uncertain keeps Eclipse's text);
  `expandVariable` lists own fields before static ones; the "no method return value" line is dropped (VT-U1..U3).
- **Product B, finish reply:** NORMAL finish adds `breakpointsRemoved`, `ownBreakpointsLeft` and, when verified, a
  `cleanup` sentence (PO-U7); with reports on, the MCP server adds `reportFile` and `reportMessage` to the same JSON
  (`SessionReport.withReportFile`, `ReportingTools`, SR-U15). Skill and tool descriptions say no re-check is needed.
- Trial needed: **yes** (tool replies and the skill changed). Expected: no second expansion of `unitPrice`, no
  `list_*`/`get_investigation_report` calls after the finish, fewer turns than 25.

Result 25 Nov 15:45 and 15:48 (two trials, both `VERDICT PASS`, `REPORT CHECK PASS`, flowFields 7/7, purpose 2/2).
Transcript `boot-quote-20261125-101805-410.claude.jsonl` against the 06:58 one:

| | 06:58 (before) | 15:48 (after) |
|---|---|---|
| Turns | 25 | 18 |
| Cost | $0.19 | $0.14 |
| Cache read / cache write / output tokens | 676k / 17k / 5.5k | 497k / 11k / 4.4k |
| Expansions of `unitPrice` | 2 | 0 (`wait_for_stop` showed `"value":"60"`) |
| Calls after the finish | 3 (`list_debug_sessions`, `list_breakpoints`, `get_investigation_report`) | 0 |

The finish reply carried `breakpointsRemoved 1`, `ownBreakpointsLeft 0`, `cleanup` and `reportFile`; the final answer
gave the report path from it. What is left: about 30k tokens of every call are Claude Code's own context (the owner's
37 skills, plugins and tools), read again on each of about 13 calls; that is the trial's Claude configuration, not
our tool. The two calls at the start (`list_debug_sessions`, `list_breakpoints`) stay: the skill's safety check
before changing state, small replies. ToolSearch loads 16 of our tools at once, 4 of them unused (about 4.5k tokens
once); not worth a change.

Later in OPEN-39: Haiku against Sonnet (3 runs each), a smaller Claude configuration for trials, shorter tool
descriptions, `wait_for_debug_pause` removed, possible duplicate regression scenarios compared before any removal.

## Earlier delivery (25 Nov): phase 2 steps 2 to 4, checked 12:20 to 12:29

`flow-phase2-steps2-4.zip` (on top of the repository with `flow-phase2-step1.zip` and `handoff-1158.zip` applied):
`report/.../activity/FlowPage.java`, `FlowPageTest.java`; `mcp-server/.../DebugMcpServer.java` (purpose argument);
`testing/.../grading/ClaudeTranscriptEvaluator.java`, `ClaudeTranscriptEvaluatorTest.java`; `skills/eclipse-debugger/SKILL.md`;
`README.md`, this file, `docs/design/flow-report.md`.

```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse ^
 && powershell -Command "Expand-Archive -Force -Path '%USERPROFILE%\Downloads\flow-phase2-steps2-4.zip' -DestinationPath ." ^
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
11:26 to 11:32: BUILD SUCCESS (`report` 47, `mcp-server` 26, `testing` 18, `eclipse-plugin` 48 at that time), regression all PASS,
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
 && java dev-e2e/DevE2E.java deploy ^
 && call mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true ^
 && call mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootWrongQuote -Dclaude.approved=true -Dfixture.post.approved=true -Dclaude.report=true
```

Expected now: BUILD SUCCESS with unit tests `report` 51, `mcp-server` 31, `testing` 19, `eclipse-plugin` 56;
`deploy` ends with "The test Eclipse is ready"; `RegressionSuite` 21 tests, 0 failures (30 with `-Dit.test=FullSuite`) (one line per test such as
`breakpoints/conditional-stops-only-on-match: PASS`); the Claude test passes (VERDICT PASS) with `REPORT CHECK PASS`.
Drop the last line when the delivery says "Trial needed: no". All tests and options: [integration tests](integration-tests.md).

## Where things stand

**Modules** (7 in the reactor): `report` (events, isolation, HTML report writer, the Agent Activity store and page;
no dependencies; embedded in the plugin as `lib/report.jar`), `mcp-server`, `testing` (grader, Claude trial runner, the
integration tests as JUnit classes in `src/test/java/.../e2e`, the ownership harness in `ownership-tests/`), `eclipse-plugin`, `eclipse-feature`,
`eclipse-update-site`, the parent `pom.xml`. `dev-e2e/` holds `DevE2E` (prepares the test Eclipse) and the grader launcher.

**OPEN-38 codebase reduction** ([analysis](codebase-reduction-analysis.md)):
- Steps 1 to 3 done and checked (unused files, documents, one JSON reader, no legacy bridge).
- Steps 4 to 6 done and checked on 25 Nov: report as one module with our own HTML writer (ChainTest gone), installer
  removed, test commands only in a `DevE2E` Eclipse, `e2e-runner` merged into `testing`. Build with the test counts
  below, regression 23/23, Claude trial PASS with REPORT CHECK PASS (three runs). Result: 189 files to 125, 13 Maven
  modules to 7. OPEN-38 is done.
- One test class per feature group was left out of OPEN-38 on purpose; the owner asked for it on 25 Nov afternoon
  (unclear group names), done as OPEN-18 step A2 (see "Last delivery").

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
- OPEN-32 (skill) delivered 25 Nov afternoon, checked by the owner ("fixed and working", `open32-skill.zip` + `open32-fix.zip`): the plugin packs the skill
  (`eclipse-plugin/lib/skill/`, copied by the build from `skills/eclipse-debugger`) and keeps
  `%USERPROFILE%\.claude\skills\eclipse-debugger` current on a normal start (`SkillInstaller`, SI-U1..U4); `DevE2E test`
  copies the repository's skill before Claude trials (`syncSkill`). No manual `copy` of SKILL.md any more.
  Its deploy (14:28) failed once in `boot-gap/readiness-timeout`: the never-ready fixture's start marker had to appear
  within the same 6 s window that checks it never gets ready, and Spring Boot started late. Not related to OPEN-32. The
  verifier now waits up to 45 s for the start, then watches 6 s (`BootGapVerifier.readinessTimeout`).
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
