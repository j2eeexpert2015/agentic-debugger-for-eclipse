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

## Last delivery (26 Nov, 20:00): docs only, step 3 results, new items OPEN-48..50

- Step 3 checks so far: build 79/52/34; InvestigationIsolationTests + UserResourceSafetyTests 10/10; Claude batch
  open23-haiku-1 9/9 PASS, 9.4 calls (haiku-6 8/9 + 1 partial, 11.4; purpose 16/18; test result read 2/9, to compare
  with haiku-6). Add on the generated agent: "already up to date" + skill line (Copilot reads ~/.claude). Remove asked
  because Ayan had edited the file ("Investigates Java, edit JUnit"): correct. A hand copy `agentic_debugger.agent.md.mine`
  is in the test project's `.github/agents` (Ayan's; Remove leaves it, so the folder stays).
- Copilot run 1 (19:46, Agentic Debugger mode, sym-wrong-total prompt): report OrderServiceTest.calculatesDiscount
  19-46-35: 9 calls (list_junit_tests, set_breakpoint 23, start_junit_test, wait_for_stop, STEP_OVER, wait_for_stop with
  rate = 0, RESUME, wait_for_stop TERMINATED with the test result, finish), cause correct, 1 min 22 s (2:41 before;
  the report's time starts at the first tool call, the agent's thinking before it is not counted). Model "unknown"
  (the agent did not report it). Skill 0.1.5 reported.
- New items: OPEN-48 three banner colours in Agent Activity (thinking / debugging / finished), OPEN-49 current class and
  method in the header, OPEN-50 the menu enablement prototype (step3b), parked by Ayan.
- Still to check: Copilot run 2 with the skill only in `~/.copilot/skills`; preferences page install/remove.

## Delivery before (26 Nov, 19:00): OPEN-23 step 3, remove the agent, skill on request, stop stepping, clearer refusal

Same branch `feature/open-23-copilot` (Ayan continues on it). Agreed 26 Nov: nothing that changes how Copilot debugs
is installed without a user action (approval, Add, preferences button).
- `integration/CopilotAgentInstaller.remove(project, force)`: REMOVED / NOT_PRESENT / EDITED_NEEDS_CONFIRMATION;
  removes `.new`; `.github/agents` and `.github` only when empty. `writtenByPluginUnedited` now also counts text
  after the checksum comment as an edit (CA-U7 failed without it). CA-U6..U8.
- `ui/RemoveCopilotAgentHandler` + plugin.xml: "Remove Agentic Debugger Agent for GitHub Copilot" in the same menu;
  asks when the file was changed; the message says the skill stays and where to remove it.
- `integration/CopilotSkill`: install (to `~/.copilot/skills/eclipse-debugger` unless `~/.claude/skills/
  eclipse-debugger/SKILL.md` exists, via SkillInstaller, state `~/.eclipse-agentdebug/copilot-skill-installed.
  properties`), remove (only unchanged files the plugin wrote; empty folders), status. CS-U1..U4.
  `ClaudeIntegration.bundledSkillFiles()` extracted from `stageSkill()` for it. `AddCopilotAgentHandler` installs it
  and adds the line to its message. Preferences page: "GitHub Copilot skill" status, Install, Remove.
- Skill v0.1.5: "Before you conclude" starts with "As soon as you have observed the wrong value and know where it
  first goes wrong, stop stepping and call finish_debug_investigation." Agent file step 7: step only until the first
  incorrect value, then step 9. eclipse-mcp-tools.md: 0.1.5.
- `DebugInvestigationPolicy.unknownMessage(id)`: "Unknown investigationId: <id>. Refused; nothing was changed. [This
  is not a valid investigation ID (check for a copying mistake).] Use exactly the investigationId that
  begin_debug_investigation returned." Prefix unchanged (InvestigationTracker matches it). LE-U5 extended.
- README: plugin 79; OPEN-23 row (step 3, remaining a, b, d, e); Copilot section: Remove, skill, Tool Auto Approve,
  no effect without approval, FAQ.
Not checked here: the handlers and the preferences page (no Eclipse libraries). Check: build (plugin 79), deploy,
FullSuite not needed (`InvestigationIsolationTests` + `UserResourceSafetyTests` for the refusal message), menu Add and
Remove, preferences page, Claude batch `open23-haiku-1` (skill v0.1.5) against `open25-haiku-6` (8/9, 11.4 calls), two
Copilot runs (time, calls, report from Agent Activity, Breakpoints view empty; one with `~/.claude/skills/
eclipse-debugger` renamed). Trial needed: yes.

## Delivery before (26 Nov, 18:30): docs only, OPEN-23 step 2 checked, new items OPEN-45..47

Step 2 (with the "launch not found" fix) checked 26 Nov 17:41-18:14:
- Build: report 52, MCP 34, grader 23, plugin 72. FullSuite 42/43: `spring-boot-http/wrong-quote-value-found`
  stopped at OrderService:23 instead of 24 because of seven leftover breakpoints at line 23 (two conditional) from the
  day's manual sessions; after Remove All, `SpringBootHttpTests` 2/2.
- Menu "Agentic Debugger > Add Agentic Debugger Agent for GitHub Copilot" is near the bottom of the project menu
  (JEE Eclipse, Project Explorer). It wrote `agentic_debugger.agent.md` (Ayan's own renamed to `.mine`).
- First run with it: "eclipse-debugger MCP tools are unavailable". Cause: the fresh Eclipse install has another
  JustJ JRE (25.0.4.v20260826 instead of 25.0.5.v20261030), so the offered command changed and Copilot showed the
  server as Denied again. After Approve: only our tools, percent = 10, rate = 0, the right cause, report path, no
  "Debug launch not found" at the end, Breakpoints view empty. Agent details "GitHub Copilot 0.22.0 (nightly)";
  model stated as claude-sonnet-5-5 (Copilot's chat: MAI Code 2 Flash). One control_debug_session refused for the
  mistyped ID `a1669a79-6d9c-416a-g5ff-...` (not hex); the next call used the right ID.
This delivery:
- README: OPEN-23 row (step 2 checked, remaining steps a-e), section "Use with GitHub Copilot" (menu position,
  approve again after a Java change), new OPEN-45 (signed plugin), OPEN-46 (measuring Copilot runs), OPEN-47
  (leftover breakpoints in the test workspace).
- docs/integration-tests.md: "Which check for which change" table and the leftover-breakpoint note.
Next: merge `feature/open-23-copilot` after this (FullSuite done above). Trial needed: no.

## Delivery before (26 Nov, 17:50): OPEN-23 step 2, checked 18:14, the "Agentic Debugger" agent for GitHub Copilot

Step 1 checked 26 Nov 09:35-17:31 (build: plugin 67, MCP 34; manual checks in the test Eclipse, Copilot Free):
- Copilot lists eclipse-debugger as **Denied** (plugin unsigned); approved by hand in Preferences > GitHub Copilot >
  Model Context Protocol (MCP) > Edit MCP Servers from Plug-ins. Copilot then started the server (stdio,
  `port-21766` jar, `env AGENTDEBUG_PORT=21766`) and listed 36 tools ("CopilotMCP Refreshed 36 tools").
- Copilot 0.21.0 (update site): "No model configuration found" for every chat (issue #443, Free + Auto; log:
  "Failed to parse /models response", "No model configuration found for families: mai-code-…"). Fixed in 0.22.0;
  Ayan installed the nightly `0.22.0.202611251609_nightly`. MCP client name: `Eclipse/copilot-eclipse
  4.41.0.v20260828-1142/0.22.0.202611251609_nightly`.
- Agent mode, plain sym-wrong-total prompt (MAI Code 2 Flash, Auto): reads SKILL.md and the references, hands off to
  Copilot's built-in **Debugger** mode (a chat mode from Copilot's language server) which uses Copilot's own
  `java_debugger` (no launch action: "No active Java debug session found"), then uses our tools: begin,
  start_junit_test, wait_for_stop, control_debug_session, finish; percent = 10, rate = 0, OrderService.java:23, report.
  Model chip showed "claude-sonnet-5-5 ◆" (stated by the agent, not verified).
- Custom agent written by Ayan (`.github/agents/agentic_debugger.agent.md`, mode "Agentic Debugger", no `tools:`
  line): ping, SKILL.md, begin, references, list_junit_tests, set_breakpoint, start_junit_test, wait_for_stop, steps,
  finish; correct cause. No hand-off, no `java_debugger`. With a hand-made `tools:` list our tools were missing.
- Nightly: Copilot's own `read_file` sometimes fails (TypeError in its language server).
- Copilot's custom agents live per project in `.github/agents/*.agent.md` (Copilot's Custom Agents preference page).
Step 2 (this delivery):
- `eclipse-plugin/copilot/agentic_debugger.agent.md`: Ayan's agent with three corrections (eclipse_ping "succeeds"
  instead of "PONG"; Spring Boot = start_java_application or list_run_configurations + start_debug_session; one line
  that the user chose this agent). build.properties includes `copilot/`.
- `integration/CopilotAgentInstaller`: writes it into a project; a checksum comment at the end; unedited older
  versions replaced, edited files kept with `.new` next to them, CRLF ignored. CA-U1..U5 (plugin 72).
- `ui/AddCopilotAgentHandler` + plugin.xml: project menu "Agentic Debugger > Add Agentic Debugger Agent for GitHub
  Copilot", message with the next steps.
- report `ActivityPage.agent`: "Eclipse/copilot-eclipse …/0.22.0…_nightly" shown as "GitHub Copilot 0.22.0
  (nightly)" (AP-U11 extended).
- README: OPEN-23 row, section "Use with GitHub Copilot", new OPEN-43 (console), OPEN-44 (quieter server output).
- Fix from the 17:35 Copilot run: after the agent resumed a JUnit debug run to its end, Eclipse removed the launch,
  and the next `wait_for_stop` answered `IllegalArgumentException: Debug launch not found: Agentic JUnit - … (118)`.
  `DebugStopTracker.endedByName(name)` (TERMINATE seen for a launch of that configuration name);
  `EclipseAgentOperations.waitForStop` then answers `stopped:false, reason:TERMINATED` with a note and `testResult`,
  as for an ended launch that is still listed. Not covered by a unit test (needs Eclipse launches).
Not checked here: the handler and plugin.xml (no Eclipse libraries in my copy). Check: build (plugin 72, report 52),
deploy, menu on a project, Copilot run with the written agent. Trial needed: no (manual check).

## Delivery before (26 Nov, 09:45): OPEN-23 step 1, the plugin offers its MCP server to GitHub Copilot, checked 17:31

Branch `feature/open-23-copilot` from `main` (OPEN-25 merged). Copilot for Eclipse is open source
(github.com/microsoft/copilot-for-eclipse, MIT; read at 0.22.0): extension point
`com.microsoft.copilot.eclipse.ui.mcpRegistration`, interface `IMcpRegistrationProvider` (package
`com.microsoft.copilot.eclipse.ui.extensions`, exported), answer `{"servers":{name:{...}}}`; Copilot shows its own
approval dialog once per plugin and again when the JSON changes. Copilot reads skills from `.github/skills`,
`.claude/skills`, `.agents/skills` in the project and `~/.copilot/skills`, `~/.claude/skills`, `~/.agents/skills`.
- `integration/CopilotRegistration` (pure): `json(java, jar, port)` (stdio, `java -jar <jar>`, `env AGENTDEBUG_PORT`
  only for a port other than 8765) and `runtimeFolder(home, port)` (`~/.eclipse-agentdebug`, or `port-<n>` below it,
  so the test Eclipse on 21766 never replaces the jar the normal Eclipse uses). CP-U1..U3 (plugin 67).
- `integration/CopilotMcpProvider` implements Copilot's interface: stages the jar, answers the JSON, null on failure
  (console line). `ClaudeIntegration.stageRuntimeJar()`, `javaExecutable()` made public for it.
- plugin.xml: the `mcpRegistration` extension. MANIFEST: `Import-Package:
  com.microsoft.copilot.eclipse.ui.extensions;resolution:=optional` (without Copilot the extension is ignored, the
  class never loaded). Root pom: Copilot's p2 update site, only to compile; not in our feature or update site.
- Not decided yet: also installing the skill into `~/.copilot/skills` (today only Claude users get it, in
  `~/.claude/skills`, which Copilot reads too).
Not checked here: the build (Copilot's update site is not reachable from my copy); Copilot itself.
Check: build (plugin 67); then the manual Copilot check (handoff of the next step). Trial needed: no (manual check).

## OPEN-25 done (26 Nov, 04:25)

Batch `open25-haiku-6` (04:16-04:21, v0.1.4, $0.11): 8/9 PASS + 1 PARTIAL, 0 FAIL, 11.4 debugger calls, 20.2 turns;
sym-test-fails 3/3, sym-vague 3/3, sym-wrong-total 2/3 + 1 PARTIAL (one run still debugged phase-one-debugging's
`OrderCalculatorTest`, which has a similar bug, although the prompt names Spring Boot). This is the baseline for later
skill or tool changes. README: OPEN-25 moved to Done. Next: merge the OPEN-25 branch; OPEN-23 (Copilot) from `main`.

## Delivery before (26 Nov, 04:20): OPEN-25 closing change, checked 04:21

Step 4 checked 26 Nov 04:04-04:12: plugin 64, MCP 34, `JUnitResultsTests` 3/3 (second debug run in one investigation
stops). Batch `open25-haiku-5` (v0.1.4, new trial setup, $0.11): 7/9 PASS + 2 PARTIAL, 0 FAIL, 10.6 debugger calls,
19.4 turns; sym-test-fails 3/3 (was 1/3), sym-vague 3/3 (was 0/3), sym-wrong-total 1/3 + 2 PARTIAL: both PARTIAL runs
debugged `OrderCalculatorTest.appliesTenPercentDiscount` in phase-one-debugging (a similar integer-division bug),
because "the Orders project" fits both test projects.
- `ClaudeSymptomTrial`: sym-wrong-total now says "In the Spring Boot Orders project open in my Eclipse, …".
- README OPEN-25 row: step 4 result and the closing check.
Not measured: v0.1.4 against v0.1.1 on the new setup; the gain came from the product fix and the trial setup.
Check: batch `open25-haiku-6` (no plugin change: no deploy needed, the test Eclipse only has to run). Then OPEN-25 is
done; merge its branch, start OPEN-23 from `main`. Trial needed: yes.

## Delivery before (26 Nov, 04:10): OPEN-25 step 4 (with fix), checked 04:12, an ended debug run no longer blocks the next; trial runs in the workspace

Step 3 checked 26 Nov 03:38 (re-sent as `open-25-step3b.zip`; the first zip had not been extracted, so batch
`open25-haiku-3` at 03:04 still ran skill v0.1.3: 4/9 + 1 PARTIAL). Batch `open25-haiku-4` (03:38-03:44, v0.1.4, 9
Haiku runs, $0.13): 4/9 (sym-test-fails 1/3, sym-wrong-total 3/3, sym-vague 0/3). `testResult` arrives at TERMINATED.
Transcripts of both batches showed three causes outside the skill text:
1. After an own debug run ended without stopping (breakpoint on a line that does not run), the next debug run in the
   same investigation was refused: "Investigation already targets another debug session". Agents finished and began
   again, up to three investigations, and ran out of the 25 turns (one found rate = 0 in its third investigation).
2. The trial ran Claude in this repository: reads of the Eclipse workspace and of the skill's references were denied
   (agents guessed breakpoint lines 17 and 18 of the test), and README, handoff and roadmap, which describe the planted
   bug, were found by a search for "discount" (sym-vague, 42 files).
3. "my Orders project": two test projects have discount code (spring-boot-debugging, phase-one-debugging); Haiku asked
   which one in 6 of 6 sym-vague runs and wrote no report.
Changes:
- `DebugInvestigationPolicy.Investigation.releaseEndedLaunch(boolean ended)`: the investigation's own debug launch that
  has ended is moved to the owned runs (still checked at cleanup) and no longer binds the investigation. Borrowed
  sessions are never released this way. `EclipseAgentOperations.startJUnitTest` calls it before a debug launch. PO-U10
  (plugin 64). `JUnitResultsTests.debugModeResultAfterResume` starts a second debug run in the same investigation after
  the first ended and requires the stop.
- Fix after the first check (26 Nov 03:58, `JUnitResultsTests` 2/3: the second debug run was still refused): a JUnit
  debug launch whose JVM ended is left "disconnected", not terminated, so `ILaunch.isTerminated()` stayed false.
  `EclipseAgentOperations.launchEnded` also counts the TERMINATE event seen by `DebugStopTracker` (new `ended(launch)`)
  or all processes ended.
- `ClaudeSymptomTrial`: Claude starts in the Eclipse workspace folder (from `dev-e2e/e2e.properties`), not the
  repository; `--add-dir %USERPROFILE%\.claude\skills\eclipse-debugger` (not with `-Dclaude.skill=false`).
  sym-vague: "my Spring Boot Orders project". Earlier batches ran in the repository: not a like-for-like baseline.
Check: build, deploy, `JUnitResultsTests` 3/3, batch `open25-haiku-5`. Trial needed: yes.

## Delivery before (26 Nov, 03:10): OPEN-25 step 3, the result comes with the end of the run; skill v0.1.4, checked 03:38

Batch `open25-haiku-2` (26 Nov 02:43-02:50, v0.1.3, 9 Haiku runs, $0.14): 6 PASS, 1 PARTIAL, 2 FAIL. sym-test-fails
1/3 (23.7 debugger calls, up to three investigations and reports per run, one cut off at 25 turns); sym-vague 3/3;
sym-wrong-total 2/3. Compared 26 Nov with Microsoft's `debug-live` skill, JetBrains' `ij-debugger` and Anthropic's skill
guidance (docs/feature-roadmap.md RS-01, RS-02, RS-08): neither competitor runs a test first. So:
- Plugin: `wait_for_stop` TERMINATED of a JUnit run adds `testResult` (result, total, passed, failed, firstFailure
  with expected, actual, location; waits up to 2 s for Eclipse's JUnit model). `EclipseAgentOperations.testResultOnEnd`.
- MCP server: `wait_for_stop` description names `testResult`; `start_junit_test` says mode=run only when the failing
  test or the failure is not known. Report: the end of a run shows "Test result: calculatesDiscount FAILED: expected
  108.00, was 120.00 (OrderServiceTest.calculatesDiscount:19)" (SR-U17 extended).
- Skill v0.1.4: description in third person, "null value", "crash", "unexpected output", "instead of guessing from the
  source or adding print statements or logging"; new section "Before you conclude" (seen in the debugger, first wrong
  place, chain to the symptom, finished); quick rules: named failing test = breakpoint and debug run directly, result
  from `wait_for_stop`; new row "Failing test or failure not known yet" = mode=run and `get_junit_results`.
  `junit-debugging.md` step 2 only for that case; step 4 names the result at TERMINATED.
- `JUnitResultsTests.debugModeResultAfterResume`: WAIT_FOR_STOP after resuming returns TERMINATED with testResult.
- docs/feature-roadmap.md: RS-01, RS-02, RS-04 status updated; new RS-08 (Anthropic guidance).
Check: build, deploy, `JUnitResultsTests`, batch `open25-haiku-3`. Trial needed: yes.

## Delivery before (26 Nov, 02:50): OPEN-25 fix, a run without the debugger no longer takes the investigation's session, checked 02:50

Batch `open25-haiku-1` (26 Nov 02:25-02:33, skill v0.1.2, 9 Haiku runs, $0.14): 5/9 PASS (OPEN-05 batches 2+3: 8/9),
14.9 debugger calls (about 11), 23.2 turns (about 20); "Test result read" 7/9; one run ended at the 25-turn limit.
Call order of the 4 failed runs: in two, after `start_junit_test` (run) and `get_junit_results`, the debug
`start_junit_test` in the same investigation was refused ("Investigation is already bound to a debug session"), so the
agent finished and began again and ran out of turns or stopped early; one diagnosed from the result without a debug
run; one (wrong total) never used JUnit. The first cause is a design error of step 1: `START_JUNIT_TEST` RUN called
`verifyNewLaunch`/`recordLaunch` and took the investigation's one debug session. Fix:
- `DebugInvestigationPolicy.Investigation.recordRun`/`runs`: a run is owned (ended at cleanup if still running) but does
  not bind the investigation. `EclipseAgentOperations.startJUnitTest`: RUN uses `ensureActive` and `recordRun`;
  `cleanUp` terminates own runs still running. PO-U9 (plugin 63).
- `JUnitResultsTests.runModeReportsTheFailure`: after the result, the debug run in the same investigation starts and stops
  at the breakpoint (would have caught it).
- Skill v0.1.3: quick rule "... in debug mode, in the same investigation; the result shows where to look, the debugger
  shows why"; `junit-debugging.md` step 2: no finish/begin between run and debug run, the result is not runtime
  evidence; step 4: no `get_junit_results` while paused.
Check: build, deploy, `JUnitResultsTests`, then batch `open25-haiku-2`. Trial needed: yes.

## Delivery before (26 Nov, 02:30): OPEN-25 step 2, the skill uses the test result, checked 02:33 (5/9, see above)

Step 1 checked 26 Nov 02:19: build (plugin 62, MCP 34), `JUnitResultsTests` 3/3 (run mode finishes past a
breakpoint and gives expected 108, actual 120 and the assertion line; whole class 2/1/1; debug mode "in progress" while
paused), `ProgramStartTests` 4/4. The test run name is the launch configuration name, as assumed.
Step 2:
- Skill v0.1.2: SKILL.md quick rule "One failing JUnit test": `start_junit_test` with `mode=run`, `get_junit_results`,
  breakpoint where the wrong value is produced, then debug mode. `references/junit-debugging.md` core workflow: new
  step 2 "Run it first" (skip when the user gave the failure), step 3 breakpoint where `actual` is produced, step 4
  `wait_for_stop`, step 6 uses expected/actual from `get_junit_results`. `skill` value and tool reference: 0.1.2.
- Grader summary: column "Test result read" (a `get_junit_results` call); EV-U13 checks it.
Check: Haiku batch `open25-haiku-1` (sym-test-fails, sym-wrong-total, sym-vague, 3 each). Compare with OPEN-05 batches 2
and 3 (same prompts, skill v0.1.1): 8 of 9 PASS, about 11 debugger calls, 20 turns, $0.013 per run. Trial needed: yes.

## Delivery before (26 Nov, 02:40): JUnit test results back to the agent, step 1 (OPEN-25), checked 02:19

Branch `feature/open-25-junit-results` (from `main`). Why: the agent learned nothing from a test run except a launch
name; it guessed where to stop, or stopped on the AssertionError (Haiku FAIL 26 Nov 00:11).
- Plugin: `JUnitResults` (Eclipse's public JUnit API: `JUnitCore.addTestRunListener`, `TestRunListener`; keyed by
  the test run name = launch configuration name; last 30 runs; registered before the agent's first JUnit start).
  `JUnitFailure` (plain Java): message, expected/actual (Eclipse's values, else read from a JUnit or AssertJ message),
  location = first frame outside the test framework and the JDK, short trace. `EclipseAgentOperations`:
  `START_JUNIT_TEST` 6th argument mode RUN/DEBUG (run: `ILaunchManager.RUN_MODE`, no editor follow; reply `mode`,
  `resultsAvailable`); new `GET_JUNIT_RESULTS name waitMillis` (0-60000; waits for the end of the run, 2 s grace after
  the JVM ends; failed tests first, at most 50). `BridgeCommands`: READ_ONLY and WAITING. MANIFEST: optional
  `org.eclipse.jdt.junit.core`.
- MCP server: `start_junit_test` `mode` (debug, run) with the advice "run first, then debug"; new tool
  `get_junit_results(launchName, waitSeconds)` (19 tools); 75 s socket timeout like WAIT_FOR_STOP.
- Report/Agent Activity: "Running/Ran JUnit test (no debugger)", "Test result: calculatesDiscount FAILED: expected
  108.00, was 120.00", one line per test with location and expected/actual.
- Tests: JF-U1..U4 (plugin 62), SR-U17 and TO-U3 (MCP 34), `JUnitResultsTests` 3 (extended 22, full 43).
Not checked here: the plugin classes against the real JDT JUnit API (compiled only against my stand-ins); that the
test run name equals the launch configuration name (JUnitResultsTests shows it).
Check: build, deploy, `JUnitResultsTests`. Trial needed: no (step 2 adds the skill step and a Haiku batch).

## Delivery before (26 Nov, 02:10): OPEN-41 done (docs only), checked 02:06

Screenshots 26 Nov 02:01-02:04: the Agent Activity window shows the block folded to one line ("Agent details Claude
Code · claude-haiku-5-5 · eclipse-debug... more"); the 00:25 HTML report shows it open, in light and in dark (that
report predates the rename, so it still says "Debugged by"). The "Open HTML Report" button was absent in the
OPEN-05 runs because they ran without -Dclaude.report=true (no report file), as designed. README: OPEN-41 moved to
Done; "Current status" notes the next steps (OPEN-25, then OPEN-23; OPEN-22 parked). Build: not needed. Trial: no.

## Delivery before (26 Nov, 02:05): OPEN-05 skill part closed; skill v0.1.1; README priorities, checked 01:57 (build: report 52, MCP 33, testing 23, plugin 58)

Batch `open05-haiku-3` (26 Nov 01:47-01:52, with the skill, 6 Haiku runs, $0.07): sym-test-fails 3/3 PASS (skill loaded
3/3); sym-slash 2/3 PASS but skill loaded 0/3. Checked with findstr: the skill text ("skill v0.1.0") is in all 6
sym-test-fails transcripts and in none of the 3 sym-slash ones, so "/eclipse-debugger ..." does not load the skill in the
non-interactive trial ("claude -p"); the two passes came from the tool descriptions (the prompt names the tool). With the
new description, the skill loaded in 9 of 9 symptom runs and 8 of 9 passed (batch 1: 0 of 9 without the skill).
Changes:
- Skill v0.1.0 -> v0.1.1 (the description changed): SKILL.md heading and step 3 (`skill`: `eclipse-debugger 0.1.1`),
  `references/eclipse-mcp-tools.md`. Test data that uses "0.1.0" as an example value is unchanged.
- `ClaudeSymptomTrial.SYMPTOM_SCENARIOS` without sym-slash (default batch: 3 prompts);
  `ALL_SYMPTOM_SCENARIOS` with it, for `-Dclaude.scenarios`.
- README: OPEN-05 results; OPEN-33 parked (Ayan: no code links needed in the HTML report); OPEN-06 and OPEN-07 P1 -> P3.
Next: close OPEN-41 (screenshots), merge, then OPEN-25 (JUnit results to the agent), then OPEN-23 (Copilot: Copilot
for Eclipse supports Agent Skills since June 2026, so the same SKILL.md can be reused).
Check: build only. Trial needed: no.

## Delivery before (26 Nov, 01:45): OPEN-05 step 3, the /eclipse-debugger prompt and the final description; OPEN-42 parked, checked 01:52

Result of step 2 (26 Nov 01:15-01:20): grader 23 tests; re-grade of all 23 transcripts: the 3 UNSAFE runs of the first
batch are now FAIL (read-only commands only). Batch `open05-haiku-2` (with the skill, 6 Haiku runs, $0.08):
sym-wrong-total 3/3 PASS, sym-vague 2/3 PASS; skill loaded 6/6 (first batch 0/6), debugger used 6/6, cleanup 6/6.
The new description works. The run used the first copy of open-05-step2.zip (the re-sent zip with the same name did
not replace it in Downloads: no sym-slash runs, SKILL.md without "Use when the user asks to debug"). So this step
re-sends those files as `open-05-step3.zip`: the description with "Use when the user asks to debug" first, the
`sym-slash` prompt and its expectations. Lesson: a re-sent zip always gets a new name. Cost (26 Nov 01:33): each run's exact cost is already in its verdict
(`metrics.costUsd`, from Claude Code's `total_cost_usd`); the 24 OPEN-05 runs cost $0.1794 (batch 1 $0.1013,
batch 2 $0.0781); a debugging PASS costs $0.011-0.016, a code-reading guess $0.0025-0.0075. Showing the cost (trials,
and via a Claude Code status-line script in the report and window) is new item OPEN-42, parked by Ayan. This zip
(`open-05-step4.zip`) = the step-3 files plus the OPEN-42 row.
Check: batch `open05-haiku-3` with the skill: sym-slash and sym-test-fails, 3 runs each. Trial needed: yes.

## Delivery before (26 Nov, 01:15): OPEN-05 after the first batch: skill description and read-only shell commands, checked 01:20

First batch `open05-haiku-1` (26 Nov 00:52-00:59, 18 Haiku runs, $0.10, about 7 minutes):
| Prompt | with the skill | without |
|---|---|---|
| sym-test-fails | 2/3 PASS, debugger 3/3, skill loaded 3/3 | 0/3 PASS (1 FAIL, 2 UNSAFE), debugger 1/3 |
| sym-wrong-total | 0/3, debugger 0/3, skill loaded 0/3 | 0/3, debugger 0/3 |
| sym-vague | 0/3, debugger 1/3, skill loaded 0/3 | 0/3 (1 UNSAFE), debugger 0/3 |
Haiku read the source in all 18 runs and its diagnosis was right in 13 (6 with, 7 without the skill): `percent / 100`
is visible in the code, so this bug cannot show what runtime evidence adds. The 3 UNSAFE runs (all without the skill)
executed only read-only shell commands (git status/log, find, cat). Changes:
- `skills/eclipse-debugger/SKILL.md` frontmatter `description` (what Claude reads to decide whether to load the skill):
  before "Diagnose runtime defects ... Use when the user asks to debug a running Java application or failing test,
  inspect unexpected values ..."; now "Investigate Java, Spring Boot and JUnit 5 problems at runtime ... Use whenever
  the user reports a bug or symptom in a Java project open in Eclipse, even without asking to debug - a wrong or
  unexpected value, total or result; a failing or erroring test; an exception or wrong HTTP status; something that
  "seems broken" ... Observe the real values with breakpoints instead of guessing from the source." Body unchanged.
- Grader: `readOnlyShell()`; a read-only Bash command is listed in `no-file-or-shell-tools` ("read-only shell
  commands (allowed)") and no longer UNSAFE; it counts for "Read the code" (metric `readOnlyShellCalls`). A re-grade
  keeps the run's batch and skill mode from its manifest. EV-U22, EV-U23 (grader 23 tests).
- The description also keeps "Use when the user asks to debug" first (Ayan: the word "debug" must still trigger it).
- New prompt `sym-slash`: the sym-wrong-total symptom, but the user calls the skill by name ("/eclipse-debugger ..."),
  Claude Code's way to load a skill for certain (`argument-hint` is already set for it); expectations as sym-wrong-total.
- `symptomPrompts`: `-Dclaude.scenarios=sym-wrong-total,sym-vague,sym-slash` runs only these prompts.
Check: re-grade (no Claude), then batch `open05-haiku-2` with the skill on those three prompts (9 runs). Trial needed: yes.

## Delivery before (26 Nov, 00:55): skill evaluation on symptom-only prompts, batch tooling (OPEN-05), checked 00:59

Branch `feature/open-05-skill-evaluation` (from `main` after OPEN-41 is merged). Question: does the skill help when the
user only describes the symptom? The explicit trial prompts name the tool, the test and the rules, so they cannot show it.
- Three prompts in `ClaudeSymptomTrial` (no tool, no rules): `sym-test-fails` ("OrderServiceTest.calculatesDiscount
  fails ... Find out why."), `sym-wrong-total` (120.00 instead of 108.00, no test named), `sym-vague` ("seems broken").
  All on the junit planted bug; no HTTP (`NO_HTTP`), so no POST approval and no HTTP tools.
- `evaluation/claude-grading/expectations.json`: the three scenarios, like `junit` but without a required call (any
  route to the stop at OrderService:22-25 with percent=10, rate=0 counts) and "OrderService" instead of the test name.
- `ClaudeAgentEndToEndTests#symptomPrompts`: every prompt `-Dclaude.repeat` times (default 3), round by round; a
  measurement: fails only when a run left no transcript; 90 min timeout. `-Dclaude.batch=NAME` puts the runs with and
  without the skill (`-Dclaude.skill=false`) into one summary.
- Summary table: two new columns, "Debugger used" (any Eclipse tool call) and "Read the code" (Read, Grep or Glob)
  (EV-U13 extended; grader 21 tests).
Check: one batch with the skill and one without (9 Haiku runs each, about 10 minutes and $0.10-0.20 each), then the
summary table. Trial needed: yes (18 runs).

## Delivery before (26 Nov, 00:35): "Debugged by" renamed to "Agent details" (OPEN-41, fourth step), waiting for check

Result of the third step (26 Nov, 00:25): build OK, Haiku trial PASS (tools=13, flow 6/7, purpose 3/3; so the 00:11
FAIL was Haiku's variance: 2 of 3 runs pass). Ayan's screenshot: the block folds to one line in Eclipse's window as
planned. He found "Debugged by" misleading; the block, its folded line and the Markdown line now say "Agent details".
Only the text changed (`ActivityPage`, AP-U11, README). Check: build, deploy, a look at the window (folded, opened,
dark) and the report. Trial needed: no.

## Delivery before (26 Nov, 00:20): the "Debugged by" block folded in the window (OPEN-41, third step), checked 00:25

Result of the second step (26 Nov, 00:11): build OK, disconnect test PASS; Haiku now passed `model` (the block showed
claude-haiku-5-5), so the firmer SKILL.md wording works. Ayan: the block takes too much height in the narrow window.
Now the block is a `<details class="dby">`: in the window folded to one line ("Debugged by Claude Code ·
claude-haiku-5-5 · eclipse-debugger 0.1.0", cut with an ellipsis when narrow, "more" opens the full block, "less" folds
it); in the HTML report open (`report()` adds `open`). AP-U11 checks both. The same Haiku trial ended FAIL
(stopped-at-expected-frame, observed-percent, observed-rate, diagnosis; tools=23, two investigations): Haiku set an
exception breakpoint on AssertionError and the program ended without stopping. Not yet explained; a rerun and the
verdict.json decide whether it is Haiku's variance or something we changed (only the SKILL.md step 3 wording changed
for the agent).

## Delivery before (26 Nov, 00:05): the "Debugged by" block (OPEN-41, second step), checked 00:11

Same branch `feature/open-41-agent-and-skill-in-report`. Result of the first step (25 Nov, 23:50): build OK (report 52,
MCP 33, plugin 58, grader 21), disconnect test PASS, Haiku trial PASS; the header showed agent and skill but no model:
Haiku did not pass `model` because the skill said "when you know them". Ayan asked for a clearer, coloured block. Now:
- `ActivityPage.debuggedBy`: under the request a purple "Debugged by" block, the same in the Agent Activity window and
  the HTML report (both use `head`). Two-tone chips: Agent (name from `agent()`: claude-code -> Claude Code, Cursor,
  GitHub Copilot, ...; unknown IDs as sent), Model (blue), Skill (teal, name and version apart), Effort (grey); missing
  values "not stated" / "none reported". What the agent said about itself is marked with an amber diamond and one key
  "Reported by the agent itself; Eclipse cannot verify it" (only when something was stated).
- No mode names on the page: "After it finishes: the agent's own breakpoints are removed and the debug session it
  started is stopped. Your breakpoints and sessions are left alone." (CONTROLLED: "... are kept, so you can inspect
  them."). "Started: 25 Nov, 23:49:41". "Investigation ID: db14c2d9" (full ID as tooltip) with what it is for.
- Dark look: `body.dark` rules for the block (the page's Dark/Light button; the report opens dark).
- Markdown export: the same facts as plain lines (`debuggedByText`); `skillText` and the old info line removed.
- SKILL.md step 3, `references/eclipse-mcp-tools.md` and the `begin_debug_investigation` schema: always pass `model`
  as the instructions state it; `effort` only if stated.
- Tests: AP-U11 rewritten, AP-U3 updated (report still 52).
Check: build; disconnect test (block with "none reported", no key); one Haiku trial: block shows Claude Code, the
model, the skill; screenshots of the window in light and dark and of the report. Trial needed: yes.

## Delivery before (25 Nov, 23:45): the agent, its model, effort and skill in the report and window (OPEN-41), checked 23:50

Branch `feature/open-41-agent-and-skill-in-report` (from `main`). Our MCP server cannot see which skills Claude uses
(that happens inside Claude Code); the trial grader only knows from transcripts. Now:
- `begin_debug_investigation` takes optional `skill`, `model` and `effort`; SKILL.md (step 3) and
  `references/eclipse-mcp-tools.md` tell the agent to pass `eclipse-debugger 0.1.0`, and its model ID and effort level
  when it knows them (MCP itself carries only the client name and version). Recorded in
  `ReportEvent.InvestigationStarted` (new components; the old constructors stay), carried by `ActivityEvents` and
  `ActivityStore` (`Investigation.skill/model/effort`).
- The client line now has the version from the MCP handshake (`ReportingTools.client`: "claude-code 2.1.3").
- `ActivityPage` header and Markdown: "claude-code 2.1.3 · model claude-haiku-5-5 · effort high · skill
  eclipse-debugger 0.1.0 (stated by the agent) · NORMAL mode · ..."; an unstated model or effort is left out; without a
  skill: "no skill reported". Same in the HTML report (it uses the page).
- Tests: AP-U11 (report 52), SR-U16 (MCP server 33); FlowPageTest constructions updated for the new component.
Check: build; one Claude trial (Haiku, about $0.01) shows model and skill in the report and the window (a stated
model other than Haiku is a finding); the
agent-disconnect test shows "no skill reported". Trial needed: yes (one run; it is the only way the skill line appears).

## Delivery before (25 Nov, 23:30): check that the project is built before a start (OPEN-34), checked 23:19

Result on Eclipse 2026-09 (build 1.5.0.202611251742): plugin 58 tests, `BuildCheckTests` 2/2, regression 21/21.
OPEN-34 moved to Done. OPEN-32 checked 23:24 by a normal start (eclipse-mcp-tools.md replaced, 6,306 to 6,471 bytes;
unchanged files left alone) and moved to Done. How it works: the skill is refreshed only on a normal Eclipse start with the Claude marker file
(`~/.eclipse-agentdebug/claude-registration.properties`), never in the test Eclipse (AGENTDEBUG_FIXTURE_SOURCE set);
its check is a normal start of `C:\All_Softwares\eclipse` and a look at `~/.claude/skills/eclipse-debugger/references/`
(a `.new` file there is also correct: the Claude trial harness copies the skill itself, so the plugin keeps that copy).


Branch `feature/open-34-build-check` (from `main`).
- New plugin class `BuildCheck.verifyLaunchable(type)`: compile or build-path error markers in the type's project ->
  refused, naming up to three ("BrokenOnPurpose.java:5 Type mismatch ..."); compiled class missing from the source
  folder's output (Maven: target/classes, target/test-classes) -> one incremental build, then refused with "is not
  built" and what to ask the user. Warnings never block. Called before a NEW start in `startJavaApplication` (before a
  configuration is created; a running session is reused as before), `startJUnitTest` and `startDebug`. Avoids the
  `ClassNotFoundException` of 24 Nov and Eclipse's blocking "Errors exist in project" question.
- Test commands (test Eclipse only, fixture projects only, `.java` under `src/` only): `TEST_WRITE_SOURCE`,
  `TEST_DELETE_SOURCE`, `TEST_DELETE_CLASS_FILE`, `TEST_BUILD_PROJECT` (class `TestWorkspaceFiles`), classified in
  `BridgeCommands`.
- New integration class `BuildCheckTests` (extended suite 19, full 40): compile error refused and fixed; missing class
  refused and built.
- Skill reference: a refused start is reported to the user, not retried.
- README: OPEN-38 and OPEN-29 moved to Done (housekeeping).
Not checked by me: the plugin classes compile only against stubs here.
Check: build, deploy, `-Dit.test=BuildCheckTests`, regression 21/21 (the check runs before every start). Trial: no.

## Delivery before (25 Nov, 22:45): session control needs an investigation (OPEN-40), checked 22:52

Result on Eclipse 2026-09 (build 1.5.0.202611251716, compiled against 2025-12): MCP server 32 and plugin 58 tests,
`UserResourceSafetyTests` 8/8, regression 21/21, extended suite 17/17 (with the changed CONTROLLED cleanup); the
Agent Activity window after the disconnect test says "Program: ended". OPEN-40 moved to Done.


Branch `fix/open-40-session-control-safety` (from `main` after OPEN-31 was merged). Found while porting the ownership
checks: `control_debug_session` without `investigationId` could STOP or RESUME any session, also the user's program.
- MCP server: `control_debug_session` uses `ownedId` like the other changing tools: without an ID it joins the only
  open investigation (reply `attachedToInvestigation`); with several open it is refused. `optionalId` removed.
  Tool description: "(refused outside an investigation)". TR-U6 (MCP server tests 32).
- Plugin: `DebugInvestigationPolicy.ownerOf(launch)` = the open investigation that started it; `control()` without
  an investigation refuses a session without such an owner, saying what to do. PO-U8 (plugin tests 58).
- `UserResourceSafetyTests`: the user's program can be neither stopped nor resumed without an investigation; the
  agent's own program is still resumed without the ID. `AbandonedSessionCleanupTests.controlledModeKeepsSession` ends
  its kept session with `TEST_TERMINATE_LAUNCH` (its STOP without investigation is now refused, by design).
- Skill reference (`eclipse-mcp-tools.md`): control without an investigation is refused.
Not checked by me: the plugin change compiles only in the real build; the policy and tracker tests run here.
Check: build, deploy, `UserResourceSafetyTests` 8/8, regression 21/21, `ExtendedSuite`. Trial needed: no (the
agent flow is unchanged: the skill always begins an investigation); optional one Haiku trial.

## Delivery before (25 Nov, 22:20): OPEN-31 done, Eclipse 2025-12 to 2026-09, checked 22:22

Final check 22:19-22:22: plain `mvn clean package` built against 2025-12 by default (report 51, MCP server 31, grader 21,
plugin 57); build 1.5.0.202611251648 with the 2025-12 minimums installed into Eclipse 2026-09; regression 21/21.


Results: build against 2025-12 without a compile error; Eclipse 2025-12: install, regression 21/21 (22:08),
`UserResourceSafetyTests` 8/8 (22:03), agent-disconnect test and Agent Activity window ("Program: ended", 21:54); the
same build 1.5.0.202611251630 on Eclipse 2026-09: regression 21/21 (22:15). Now:
- `pom.xml`: `eclipse.release` default `2025-12` (the oldest supported release), with the rule for new releases.
- `MANIFEST.MF`: minimums are the 2025-12 versions, read from the owner's 2025-12 install:
  `org.eclipse.ui` 3.207.400, `org.eclipse.debug.core` 3.23.200, `org.eclipse.jdt.debug` 3.25.0.
- README: OPEN-31 moved to Done.
Machine setup: two test Eclipses, `C:\All_Softwares\eclipse` (2026-09, workspace `java-e2e-workspace`) and
`C:\All_Softwares\eclipse-2025-12` (workspace `java-e2e-workspace-2025-12`); `dev-e2e/e2e.properties` points to one
of them. Daily work on 2026-09; the regression on 2025-12 before a release. When Eclipse 2026-12 is released, the
window moves: 2026-03 becomes the oldest (build target, minimums, test install).
Check: plain `mvn clean package` (now against 2025-12), deploy into 2026-09, regression 21/21: the minimums do not
block the install. Trial needed: no.

## Delivery before (25 Nov, 21:40): the user's own breakpoints and launches proven safe (OPEN-02, OPEN-03, OPEN-09), checked 22:03

Result 22:03 on Eclipse 2025-12 (build 1.5.0.202611251630, `clean` build: report 51, MCP server 31, grader 21, plugin 57):
`UserResourceSafetyTests` 8/8 with the loop-counter check, user resources removed. OPEN-02, 03, 09, 35 moved to Done;
`testing/ownership-tests` deleted. The Agent Activity window stays on "waiting" during these tests: they talk to the
bridge directly, not through the MCP server, so no activity events exist (only the agent-disconnect test and Claude
trials go through the MCP server). OPEN-31 so far on 2025-12: compiles, installs, these 8 tests, the agent-disconnect
test and the window ("Program: ended", 21:54); the regression suite on 2025-12 is still to be reported.


Prepared while OPEN-31 (Eclipse 2025-12) was being checked; on top of `eclipse-2025-12.zip`.
- New plugin class `TestUserResources` with test commands `TEST_USER_SEED`, `TEST_USER_STATE`,
  `TEST_USER_REPLACE_BREAKPOINT`, `TEST_USER_LAUNCH_AGAIN`, `TEST_TERMINATE_LAUNCH`, `TEST_USER_CLEANUP`: they create
  the user's own things the way a person does (JDT breakpoints without the agent's attribute, a saved launch
  configuration with program and VM arguments and an environment variable), only in the test Eclipse
  (`AGENTDEBUG_E2E=1`) and only in the fixture project; what they create carries an extra attribute so a cleanup
  also finds leftovers after an Eclipse restart. Classified in `BridgeCommands` (STATE read-only, the rest
  state-changing).
- New integration class `UserResourceSafetyTests` (extended and full suite; 8 tests, run in order): the 32 checks of
  `testing/ownership-tests`, each recorded under its old name, plus OPEN-02's "the user's configuration keeps every
  attribute". `testing/ownership-tests` stays until this class passes, then it is deleted.
- OPEN-09: plugin unit test CR-U7 reads `ClaudeIntegration`: only `claude mcp get|add|remove eclipse-debugger`, never
  Claude's files (plugin tests 57).
- OPEN-35 marked done (confirmed by the owner 21:00).
- OPEN-31 finding (owner's check 21:30 on Eclipse 2025-12, agent-disconnect test): the Agent Activity window opened
  by itself with the right banner and tags, but its program line stayed "paused at StepFilterApplication.main:9"
  although the cleanup had ended the JVM (2026-09 at 18:28 said "ended"). Eclipse reported the end after the finish
  event, and `ActivityStore.program` ignored everything once the investigation was finished. Now "ended" is still
  applied to the newest investigation after its finish (running or paused are not); AS-U9 changed accordingly.
First run 21:36 (Eclipse 2025-12, build 1.5.0.202611251603, plugin tests 57): 7 of 8, 31 of 32 old checks PASS,
user resources removed. Failed: "reused session stays suspended", one `GET_DEBUG_STATE` read right after the
borrower's finish said not suspended. Nothing in the code path resumes a borrowed session (finish of a borrower
touches no launch), and later tests found the user's program alive and stopped again; most likely the read caught
Eclipse evaluating a variable for its views (the thread runs for that moment). The check now compares the program's
loop counter before and after (any resume would raise it) instead of one read.
Runs 21:52 and 21:54 used the OLD compiled test ("testCompile: Nothing to compile"): the extracted source carried
the zip's file time (written in UTC, read as local time), older than the class compiled at 21:34, so Maven skipped
it; the 21:54 PASS was the old single read being lucky. Lesson: a command that only extracts sources runs
`mvn ... clean ...`; zips are now written with India time.
Check: `mvn -pl testing clean verify -Dit.test=UserResourceSafetyTests`; expected 8/8 and `userLoopCountBefore` equal
to `userLoopCountAfter` in `evaluation/reports/user-resource-safety.json`. Then the agent-disconnect test once more
on 2025-12: the window's program line says "ended".
Not run by me: the new plugin class compiles against stubs of the JDT and debug APIs only; the integration class
compiles. Trial needed: no.

## Delivery before (25 Nov, 21:00): older Eclipse releases, first step (OPEN-31), waiting for check

Branch `feature/open-31-eclipse-compatibility`. Goal: support the last four Eclipse releases (2025-12 to 2026-09).
Java stays 21: Eclipse itself requires Java 21 since 2024-06, so a lower Java level would not widen the range; the
code is Java 17 level except one pattern `switch` in `AsyncReportRecorder`.
- `pom.xml`: the build target is the property `eclipse.release` (default `2026-09`), so
  `mvn clean package -Declipse.release=2025-12` compiles the plugin against 2025-12 and reports any newer API it uses.
- `MANIFEST.MF`: the minimum versions `org.eclipse.ui 3.209.100`, `org.eclipse.debug.core 3.24.0` and
  `org.eclipse.jdt.debug 3.26.100` (all 2026-09) removed; they made 2025-12 and older refuse the install. Once the
  oldest supported release is known, set the minimums to its versions.
- Test Eclipse 2025-12 in `C:\All_Softwares\eclipse-2025-12`, its own workspace; `dev-e2e/e2e.properties` (not in
  git) points to it while checking: `eclipse=C:/All_Softwares/eclipse-2025-12`,
  `workspace=${LOCALAPPDATA}/AgenticDebugger/java-e2e-workspace-2025-12`. DevE2E creates the workspace and the plugin
  imports the test projects on the first start.
Check: build against 2025-12, deploy, regression suite 21/21, a look at the Agent Activity view. Then the same build
on 2026-09. Trial needed: no.

## Delivery before (25 Nov, 18:50): grader counts breakpoints the MCP server attached, checked 18:57

Result: grader tests 21/21; re-grade of the 18:43 Haiku transcript: PASS (tools=13, waits=3, turns=20, flowFields=6/7,
purpose=3/3). So the one Haiku check is a PASS at $0.014 and 20 turns.


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
integration tests as JUnit classes in `src/test/java/.../e2e`), `eclipse-plugin`, `eclipse-feature`,
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

**Other open items** (details in the README table): OPEN-37 (mostly covered by R4 phase 1; source line under stops open),
OPEN-30 R2b/R3, OPEN-22 (parked)/23, OPEN-33, OPEN-41 (delivered), OPEN-05 (after OPEN-41), and the older test items. `examples/README.md` has two dead links to deleted documents;
left unchanged by the rule above until the owner decides.
