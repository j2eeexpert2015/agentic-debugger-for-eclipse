# Handoff for a new chat (27 Nov 2026, 15:30 IST)

Read [working-agreement.md](working-agreement.md) first (how we work), then this file (where the work stands), then
the README section **Status and tracking** (the single list of all items). The repository is the truth; earlier
chats are not. Delivery-by-delivery notes up to 27 Nov 00:30 are in the git history of this file.

## 1. Environment

| What | Where / value |
|---|---|
| Repository (Ayan's checkout) | `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse`, GitHub `j2eeexpert2015/agentic-debugger-for-eclipse` (public) |
| Second machine (sync by push) | `C:\All_GitHub_Desktop_Projects\...` |
| Build | Maven + Tycho 5.0.4, JDK 25 on Ayan's machine, `maven.compiler.release` 21 (README promises JDK 21+) |
| Test Eclipse | `C:\All_Softwares\eclipse` (Eclipse 2025-12 target; bundled JustJ Java 25.0.4) |
| Test workspace | `%LOCALAPPDATA%\AgenticDebugger\java-e2e-workspace` (projects `phase-one-debugging`, `spring-boot-debugging`) |
| Bridge port of the test Eclipse | 21766 (`dev-e2e/e2e.properties`, untracked, never committed) |
| Copilot | GitHub Copilot for Eclipse 0.22 nightly, language server 1.541.0; models via "Auto" (runs used claude-sonnet-5-5) |
| Plugin version | 1.5.0-SNAPSHOT (feature 1.5.0.202611261846 deployed 27 Nov 00:19) |

Modules: `report`, `mcp-server` (shaded jar), `testing` (grader, Claude trial runner, integration tests),
`eclipse-plugin`, `eclipse-feature`, `eclipse-update-site`. Source map: development-workflow.md.

## 2. The standard check

```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && ^
<close-test-Eclipse step from working-agreement.md §4> && ^
call mvn clean package && ^
java dev-e2e/DevE2E.java deploy && ^
call mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true
```

Expected today: BUILD SUCCESS with unit tests **report 54, mcp-server 38, testing 23, eclipse-plugin 114** (114 since
`open-60-copilot-agent-only.zip`; 91 before); deploy
ends with "The test Eclipse is ready"; `RegressionSuite` 27 tests, 0 failures (from `open-58-23b-12.zip`; before it
mcp-server 34, eclipse-plugin 84, regression 21). A Claude trial (costs usage) only when
the delivery says "Trial needed: yes":
`call mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootWrongQuote -Dclaude.approved=true -Dfixture.post.approved=true -Dclaude.report=true`.

## 3. Done recently (all checked by Ayan, details in the README rows)

- **OPEN-44** quiet MCP server (slf4j-nop; extra stderr only with `AGENTDEBUG_VERBOSE=1`).
- **OPEN-47** integration tests fail early when breakpoints are left in the test projects.
- **OPEN-48/49** Agent Activity banner colours by phase (thinking orange, debugging red, finished green, ended
  without finish bright yellow; dot and box) and a "Paused in / Last stop Class.method:line" chip.
- **OPEN-52** end-of-run notification popup removed.
- **OPEN-23 GitHub Copilot**, steps 1 to 3 and step 4 (a, e):
  - the plugin offers its MCP server to Copilot (approval dialog), the project menu adds/removes the
    "Agentic Debugger" custom agent (`.github/agents/agentic_debugger.agent.md`), the skill for Copilot on request;
  - (a) no `tools:` line in the agent, decided (exact per-tool names, breaks silently);
  - (e) **Windows launcher** `%USERPROFILE%\.eclipse-agentdebug[\port-N]\start-mcp-server.cmd`: Copilot's JSON names
    only the script, so the approval survives plugin, Eclipse and Java updates. Checked 27 Nov 00:23: one Denied on
    moving to this version, approve, runs work; after a redeploy nothing asked. Linux/macOS still use `java -jar`.
  - Copilot's internals (agent folders, tool keys, approval, process start/stop): `docs/copilot-integration-notes.md`.
  - The Tools page for our agent shows all boxes empty: only the display for an agent without `tools:`; the agent
    gets all tools. No user step.

## 4. In flight (waiting for Ayan)

**A. CI (OPEN-54, OPEN-55) lives on branch `ci/github-actions`** until merged; its own handoff text, `docs/ci.md` and
the CI rows are on that branch (`main` stays for development). State 27 Nov 01:06: unit tests green on Windows JDK 25
and JDK 21; integration tests on a GitHub Windows machine (`integration-windows.yml`, step 2.1) delivered, first run
pending. When merging into `main`: README and this file were changed on both branches (different sections), check them.

**B. Release items (27 Nov).** Checked by Ayan: build, unit tests and regression **27/27** (02:48, with fix1 and
fix2): OPEN-12 done; release checks **A passed** (02:50-02:58): OPEN-58 done. Delivered, waiting: `open-59-chat.zip`
(Copilot's Chat comes along to the Debug perspective, OPEN-59). Still to run: [release-checks.md](release-checks.md)
B, C, D (with D6 for OPEN-59), E, then one Claude trial (tool descriptions and skill changed).

**C. OPEN-23 (d) check: done 27 Nov 01:15-01:37** (details in the README OPEN-23 row). One agent file in
`%USERPROFILE%\.copilot\agents\` is enough: listed once (empty Project column), our tools run, right cause found,
still in the mode list after a restart. The user file is still on Ayan's machine (keep it until (d) is built).

**D. OPEN-60/61 manual checks (27 Nov, 17:00-17:30).** Steps 1-9 passed (Agentic Debugger runs our tools with no
confirmations; Agent and Ask modes use none, also after a restart; Check setup all OK). Step 10A found a bug:
unchecking the box left our server in Copilot's `autoApproveMcpServers` because the plugin had not added that entry
itself (no `copilotAutoApproveAddedByPlugin`), so Agent mode would run our tools unasked (Check setup WARN). Fix in
`open-60-uncheck-fix.zip`: unchecking removes our entry whoever added it; the start-up apply still removes only its own.
Then (17:49 to 18:55): the uncheck fix works (no WARN; Agent Mode tools checked), box on again after a restart all
OK, duplicate warning (page note, Add message, Check setup WARN) works, consent question on asks before
`evaluate_expression` and off runs it at once, Copilot's settings file holds only the intended changes. **Failed:** the
status-bar item draws nothing (neither "check setup" nor "Agent: …" during runs; the quiet check itself logs
"Setup check: WARN"; no error in the console or `.metadata/.log`).

**E. `open-61-fixes.zip` (27 Nov evening):** status bar: width hint for the label and a console trace
("Status bar: ..." with the label's place) to find why nothing shows; `evaluate_expression` and `set_tracepoint`
refuse a missing `allowSideEffects` with "Not run: allowSideEffects must be true ... Call again with
allowSideEffects=true" (was "Explicit consent required", which read like a user refusal; the model hit it in both
runs); Setup check: Claude row without the doubled "Claude Code:", skill row with the installed version and a WARN when
older than the plugin's (SC-U7); short Remove message. Checked here: SetupCheck, CopilotSettings, CopilotAgentTools
tests 20/20 with stand-ins; not compiled here: the UI classes. Next: build, restart, read the "Status bar:" lines.
Result 20:28 (build 115 plugin tests): the trace showed the cause: the status-bar item is sized once from the empty
label and is only **7 px high** (`box {0, 0, 278, 7}`), so the text was drawn but cut to a strip; the tooltip showed the
new skill WARN (0.1.6 installed, 0.1.7 in the plugin). Check setup now shows the skill version and one "Claude Code:".

**F. `open-61-statusbar-height.zip`:** the label's height is set to one line of its font (`heightHint` from the
font metrics), so Eclipse sizes the item tall enough. The trace stays until this is checked.
Result 20:45: still 7 px (`box {0, 0, 278, 7}`): the parent is a status-bar toolbar, and on Windows a toolbar
without buttons is 7 px high whatever its control wants.

**G. `open-61-status-trim.zip`:** the agent status is no longer a `<control>` in a status-bar toolbar (plugin.xml
entry removed) but an E4 tool control of its own in the window's bottom trim (`ui/StatusTrim`, added at start-up
and removed at shutdown so the saved workbench never refers to the plugin); `AgentStatusControl` is created by
Eclipse through `@PostConstruct create(Composite)` (MANIFEST imports `jakarta.annotation`); the window is found by
its shell. Not compiled here (E4 and SWT).
Result 21:00: works (15 px, readable, click opens the preferences); after Configure Claude Code skill 0.1.7 and all
OK (21:49); a run shows green "Agent: finished · 1 min 05 s" (21:57).

**H. `open-59-61-final.zip` (27 Nov 22:05):** Copilot's Chat got a tab group of its own in Debug. Result 22:16:
it split the right side into two narrow columns; Ayan wants it as a normal tab. Also seen: Copilot's own `read_file`
tool crashed once (TypeError in copilot-language-server main.js), the model went on; not ours.

**I. `open-59-chat-tab.zip` (27 Nov 22:20):** own tab group removed (`giveChatItsArea`); the Chat stays a tab next to
Variables, Breakpoints and Expressions, selected after the switch; `AgentDebugUiCoordinator.showDebugViews` no
longer leaves Variables in front of the Chat at the first stop when the Chat tab was showing (Ayan 22:16: Chat
stays in front during the run). Needs a one-time Reset Perspective in Debug (the 22:05 split is saved there).
Result 22:29: Chat is a normal tab and selected after the switch; at the first stop Variables still came forward
(Eclipse's own view management, not our code: in Debug-perspective mode `showDebugViews` is not called).

**J. `open-59-chat-end.zip` (27 Nov 22:40, replaces the unsent-to-test `open-59-chat-front.zip`):** Ayan 22:37 chose
the middle way: no fight with Eclipse at the stops (Variables comes forward as usual; Agent Activity shows the stops);
when the agent's program ends (TERMINATE of the followed launch), the Chat tab is shown once, 0.6 s later, without the
keyboard (`showChat`), so the agent's answer is in view. The `showDebugViews` change from I is undone.
Result 22:50: the Chat tab was not brought back (Variables stayed in front, empty). **K. `open-59-chat-finish.zip`:**
triggered by the investigation's finish instead (`ActivityViewOpener.keepInView`, our own event, then 0.6 s later
`showChat`); the TERMINATE listener is removed.
Result 23:21: still Variables in front after the finish (the Chat came only when clicked ~40 s later). Likely
cause: `getActiveWorkbenchWindow()` is null while the Agent Activity window (its own shell) has the focus.
**L. `open-59-chat-window.zip`:** `showChat` uses the first Eclipse window when none is active and writes what it did
to the console ("Copilot Chat at the finish: ...").
Result 23:47: log "brought to the front", yet Variables was in front afterwards. **Parked (Ayan 23:50).**

**M. `open-59-revert.zip` (27 Nov 23:55):** everything from H to L removed: `AgentDebugUiCoordinator` and
`ActivityViewOpener` back to the committed version, `ActivityViewPlacement` back too (only `modelWindow` and
`modelService` stay package-visible for `StatusTrim`), `TerminalCarrier` unchanged. The Chat carry from
`open-59-chat.zip` stays.

**Open (parked):** Chat in front at the finish; Ayan 23:50: the switch to Debug seems slower than before. In the
23:47 video about 5 s pass between "Running begin_debug_investigation" and the switch; no earlier measurement to
compare. Suspect: carrying the Chat view (an embedded browser) into Debug; test: close the Chat view in Java, run,
and compare.

**N. `open-62-stop-banner.zip` (28 Nov 00:25, includes M):** OPEN-62: Agent Activity "Stop and clean up" (Ayan 00:18:
no cancel once the agent ended its turn) and the banner text min-width (one word per line in a narrow window, seen
00:16). Also (Ayan 00:25, status bar "Agent: thinking · 12 min"): the 10-minute expiry now also ends the
investigation in Agent Activity ("Ended by Eclipse"); before, only the MCP server's finish event did, and Copilot
never sends it. Report tests 57 expected (AP-U14, AS-U10, AS-U11).
Result 00:38: Stop and clean up works.

**O. `open-63-activity-clarity.zip` (28 Nov 01:00):** Follow pauses only on the user's own scrolling; a blinking
"Now:" line at the end of the live flow; plain wording (no-stop timeout, movement only when the line changed,
"called from" with the stack on a click, Preparation one line per kind, quieter badges). Report tests 58 expected,
MCP 38 (SR-U4 title). Later (Ayan 00:53): his mockup `runtime-investigation-v3.html`, a sequence view of the stops,
first for the HTML report; design note to write in `docs/design/` (column width: the live window is ~480 px).

**P. Mockups (28 Nov 01:01-01:15):** `agent-activity-sequence-mockup.html` (v1) and `-v2.html` (Option A, Ayan's
choice: Flow | Sequence | Steps tabs in one view, shared banner, details, warnings, Now line and analysis; thinking
pauses from the gaps between calls; glow). Not in the repo yet; the design note comes before building it.

**Q. `open-64-id-typos.zip` (28 Nov 01:20):** OPEN-64, mistyped investigation IDs corrected by the MCP server
(same first 8 characters, this session's only matching open investigation). MCP tests 39 expected (TR-U7).

**R. `open-62-63-64-all.zip`:** all of tonight's code in one zip (Ayan's request 01:26).

**S. `open-65-sequence-1.zip` (28 Nov 02:00):** OPEN-65 delivery 1, the Sequence tab (design note
`docs/design/sequence-view.md`). Report tests 62 expected (SQ-U1..U4 new). Also in H: status-bar console trace removed; skill warnings say "click Configure Claude Code" (a restart does
not refresh the skill in the test Eclipse: `AGENTDEBUG_FIXTURE_SOURCE` skips the start-up refresh).

**T. Mockups v5-v7 and `open-66-activity-v7.zip` (28 Nov 02:20-02:45):** Ayan 02:16 on the real Sequence tab: request
in its own box, even fonts, "Paused" instead of "Stopped" (the wording is our MCP server's, not the model's), bright
line colours per kind, a colour per class carried into the timeline and Steps rows; 02:24: pause rows as full boxes in
the class colour, show the waiting period; 02:27: cleaner and sharper (v7); 02:31 "go with this". OPEN-66 row in the
README. Report activity tests 40/40 here (expected report module 64 = 62 + SQ-U5 + AP-U15; other modules unchanged).
Not built here: mcp-server (SessionReportTest changed), the plugin, Eclipse. Not yet in the plugin from the mockups:
class colours on the Flow cards and Steps rows, the process strip and shared selection (OPEN-65 delivery 2).
`open-66-activity-v7-fix1.zip` (02:50): Ayan's build 02:43 failed HW-U6 ("REPORT CHECK FAIL  missing: request"):
the report check looked for the old label "Request: ". `ReportReplay.check` and `ClaudeSymptomTrial` now accept any
label that starts with "Request" (new and older reports).
`open-66-activity-v7-fix2.zip` (03:05): one font scale in the live window (Ayan 02:56: variables table and section
headings bigger than the timeline): section headings 11px like the panel headers, table text 12.5px Segoe UI, values
12px code font (`CLEAN_CSS`, `.sqb>h3`); the Sequence diagram is never drawn larger than its own width (one class made
its text about 19px); the waiting text no longer names Spring Boot ("It may still be starting.", Flow and Sequence).
Previews built from the code: `agent-activity-fonts-preview(-dark).html`.

**U. Ideas parked as future items (28 Nov 03:00-03:51), README OPEN-67 and OPEN-68:** measure tool calls and token
counts per investigation (Claude Code telemetry); "calls in between" behind a setting with A/B runs. Not started.
Also discussed, outside this product: a standalone Eclipse "Flow Recorder" plugin (Java agent added at launch, one
sequence diagram per HTTP request, no AI); concept pages `petclinic-flow-find-owners.html`,
`petclinic-flow-add-visit.html`. Closest existing tool: AppMap (IntelliJ, VS Code; no Eclipse plugin found).

**V. Preferences by agent (28 Nov 04:04-04:21), README OPEN-69:** mockups v1-v3, then the full case tables with the
enabled buttons per case in `docs/design/preferences-pages.md`. Not started; to be built carefully, a pure state class
with one unit test per table row first.

**W. `open-69-preferences.zip` (28 Nov 05:10), OPEN-69 built:** `integration/PreferenceStates` (every case and its
enabled buttons, PS-U1..U8), `ClaudeIntegration.registration()`/`installSkill()`/`claudeSkillFolder()`,
`SetupCheck.summary()` (grouped, problems first) and new fix texts pointing to the pages; `ui/PrefUi` (sections),
`ui/ClaudeCodePreferences` (new page, id `...preferences.claude`), `ui/ClaudeIntegrationPreferences` (main page, all
agents), `ui/CopilotPreferences` (rebuilt), `ui/ClaudeUsage` (last Claude Code investigation, stored), plugin.xml,
`ActivityPage.agent` public. Checked here: integration tests 65/65 and report activity tests with the JUnit stand-in;
the UI classes compiled against SWT/JFace stand-ins only. To check in Eclipse: release check F12.
`open-69-preferences-2.zip` (28 Nov 09:50, Ayan 09:37-09:40): setup check in colour (`StyledText`, `SetupCheck.spans`,
PS-U9) and re-run by itself when the page is shown again after a problem, with a line saying so. From now on builds
use `-DskipTests` (Ayan 09:30: all tests before a merge); not yet run in his build: OPEN-66, OPEN-69.

**X. `open-69-preferences-3.zip` (28 Nov 10:30, Ayan 10:00 "proceed"):** setup check runs when the main page opens
(last result at once with its time, `SetupCheckRunner.lastDeepRows/lastDeepAt`), button "Check again";
`SetupCheck.summary` numbered problems first, then "Everything else is fine (N checks)" (PS-U7, PS-U9 rewritten).
Copilot after an approval in the session: `Snapshot.isolatedAtStart` (`CopilotIntegration.isolatedThisSession()`) gives
one amber "Restart Eclipse to finish the Copilot setup." instead of the red row (SC-U8); `CopilotIntegration.watch`
listens to Copilot's `mcpToolsModeStatus`/`mcpToolsStatus` and takes back our own auto-approval (re-added at the next
start). Copilot page: approval line first with "Open Copilot's MCP page" (`PreferenceStates.approval`, PS-U10), box
"Only the Agentic Debugger agent, without asking each time" with the new tooltip, live status
(`PreferenceStates.toolsStatus`, PS-U11). Checked here: integration tests 69/69 with the JUnit stand-in; the pages
compiled against SWT/JFace stand-ins; `SetupCheckRunner` and the watcher not compiled here (Eclipse classes). Open:
whether Copilot honours the withdrawn auto-approval without a restart (F12). Tests not yet run in his build: OPEN-66,
OPEN-69.





## 5. Next, in this order

1. **Check `open-60-copilot-agent-only.zip`** (OPEN-60 P0, OPEN-23 d, OPEN-61; section 7): build, deploy, regression,
   then [release checks](release-checks.md) B and F; fix what they show.
2. Results of 4B: release checks A, C, D, E (B is now the OPEN-60 check).
3. Remaining release must-haves (agreed 27 Nov): report location (reports not written into the user's project by
   default; switch and folder in Preferences, part of OPEN-30 R2b); OPEN-04 launch keeps profile, arguments,
   environment and working directory, and `@SpringBootTest` method debugging; remove `dev-e2e/e2e.properties` from
   git. Ayan chose for now: OPEN-58, OPEN-23 (b) + Copilot scenarios, OPEN-36 check, OPEN-12, approval dialog.
4. **OPEN-46** (moved up, proposed 27 Nov, Ayan to confirm): a short manual protocol for Copilot runs; release checks
   C is a first version of it.
5. OPEN-57: exact names and parameters (`lineNumber`, `investigationId`, `allowSideEffects`, reference file paths)
   in SKILL.md and the agent file; measured before and after.
6. CI on its branch: 2.1 first run, then 1.3 (Linux, macOS), merge into `main`, 1.4, 2.2 (`docs/ci.md` there).
   Open release question: first release Windows only, or wait for Linux/macOS evidence.
7. Later: OPEN-45 signing (only changes the "Signed" label in Copilot; needs a certificate), OPEN-43 console,
   OPEN-51 upstream report (draft in `docs/copilot-mode-list-bug.md`), OPEN-53 `DevE2E stop`, OPEN-56 releases.
   OPEN-22 demo apps: parked (Ayan, 27 Nov 02:02).

## 6. Where the recent code is

| Area | Files |
|---|---|
| Copilot server offer and launcher | `eclipse-plugin/src/.../integration/CopilotMcpProvider.java`, `CopilotRegistration.java` (`json`, `jsonDirect`, `launcherText`, `writeLauncher`, `runtimeFolder`); tests `CopilotRegistrationTest` CP-U1..U7 |
| Copilot agent file | template `eclipse-plugin/copilot/agentic_debugger.agent.md`; `integration/CopilotAgentInstaller.java` (`AGENT_FILE`), `ui/AddCopilotAgentHandler.java`, Remove handler; tests CA-U* |
| Skill for Copilot | `integration/CopilotSkill.java`, preferences page; source `skills/eclipse-debugger/` (v0.1.6) |
| Copilot: only our agent, no confirmations (OPEN-60) | template `eclipse-plugin/copilot/agentic_debugger.agent.md` (user-only lines, `tools:` list); `integration/CopilotAgentTools.java` (reads the list), `CopilotSettings.java` + `MiniJson.java` (Copilot's settings, pure), `CopilotIntegration.java` (applies the preference; called by `CopilotMcpProvider`); `ui/CopilotPreferences.java` (page "GitHub Copilot"); tests CT-U1..U4, CS2-U1..U9 |
| Agent for all workspaces (OPEN-23 d) | `CopilotAgentInstaller.installForUser`, `removeForUser`, `state`; `CopilotPreferences`; tests CA-U9, CA-U10 |
| Setup check (OPEN-61) | `integration/SetupCheck.java` (pure, SC-U1..U6), `ui/SetupCheckRunner.java` (snapshot, quiet check 30 s after start from `AgentDebugStartup`), "Check setup" on `ClaudeIntegrationPreferences`, status note in `AgentStatusControl` |
| Consent before code runs (OPEN-58) | `eclipse-plugin/.../debug/EvaluationConsent.java` (policy, tests EC-U1..U6), `ui/EvaluationConsentDialog.java`, used in `EclipseAgentOperations` (`evaluateExpression`, `setConditionalBreakpoint`, `TEST_SET_CONSENT`); `mcp-server/.../ToolSafety.java` (annotations, TS-U1..U4); `DebugMcpServer.INSTRUCTIONS` |
| Execution-control refusals (OPEN-12) | `EclipseAgentOperations.control`, `thread`, `launch`/`endedLaunch`; tests in `ExecutionControlTests` |
| Agent Activity banner | `report/src/main/java/.../report/activity/ActivityPage.java` (`phase()`, `where()`), `FlowPage.java` (dark CSS), `eclipse-plugin/.../ui/AgentStatusControl.java` |
| Quiet server | `mcp-server/.../DebugMcpServer.java`, `SessionReport.java` (`ENV_VERBOSE`, `verbose(env)`) |
| CI | `.github/workflows/ci.yml`, `integration-windows.yml`, `docs/ci.md` (branch `ci/github-actions` until merged) |

## 7. Last delivery

`open-60-shorter-texts.zip` (27 Nov 15:05): after Ayan's check (build 54/38/23/114, regression 27/27 at 14:42; F1
page OK, "too much text"): short texts, one idea per line, on the GitHub Copilot page, the consent and setup-check
parts of the main page, the Add message, the status lines of `CopilotIntegration`/`CopilotSettings` and the skill
messages of `CopilotSkill` (CS-U3 expects the shorter remove message). No behaviour changed.

Before it:

`open-60-copilot-agent-only.zip` (27 Nov 15:30, on `feature/all-final`), one round as Ayan asked (14:12 "go"):
- **OPEN-60 (P0):** only the Agentic Debugger agent uses our tools in Copilot, and nothing blocks it there. Agent
  template: `disable-model-invocation: true`, `x-github-copilot-invoke-policy: [user]`, a `tools:` list (our 36 tools +
  read_file, file_search, grep_search, list_dir). Preference (on by default) applied to Copilot's workspace settings
  when Copilot asks for our server: our tools off for `agent-mode` (`mcpToolsModeStatus`, `mcpToolsStatus`), then our
  server auto-approved. Consent question for expressions/tracepoints off by default (preference to turn it on).
  Skill v0.1.7, tool descriptions and server instructions say "asks only if the user turned that on".
- **OPEN-23 (d):** Install / Remove for all workspaces on the new page Preferences → Agentic Debugger → GitHub Copilot
  (skill buttons moved there); duplicate note there and in the Add message.
- **OPEN-61:** Check setup + Copy report on the main page; quiet check 30 s after start; "check setup" in the status
  bar; Error Log entry.
- Docs: README (status update, Copilot section steps 3, 5, 6, FAQ, OPEN-60, OPEN-61, OPEN-23 a/b/d, OPEN-58 default),
  release checks B reversed and F new, Copilot notes §8, this file.
- Checked here: the changed pure classes and their tests with JUnit and Eclipse stand-ins, 49/49 (CT, CS2, SC, CA,
  EC, CS, CP). Not checked: the Maven build, the Eclipse UI code (two preference pages, status bar, Add handler,
  start-up check), every Copilot run.

Before it:

`open-59-chat.zip` (27 Nov 03:05, on `main`): Copilot's Chat view carried into the Debug perspective (OPEN-59), README
(OPEN-12 and OPEN-58 done, OPEN-57 cases, OPEN-59 new), release checks D6, this file. PP-U1..U8 ran with a JUnit
stand-in; the carrier itself and plugin.xml in Eclipse not checked.

Before it, `open-58-23b-12-fix2.zip` (27 Nov 02:45): `thread()` says "This program has ended" when the launch's
process has ended or its target has no threads.

Before it, `open-58-23b-12-fix1.zip` (27 Nov 02:40): after Ayan's run 02:33 (build and unit tests passed, regression 25/27),
`EclipseAgentOperations.control` and `target` messages, `ExecutionControlTests.refusesStepsOnRunningThreads` waits
until the program runs. Details in the README OPEN-12 row.

Before it, `open-58-23b-12.zip` (27 Nov 2026, on `main`): see the README rows OPEN-58, OPEN-23, OPEN-12, OPEN-36 and
`docs/release-checks.md` (new). Not checked by the assistant: the build (Maven Central and the Eclipse p2 sites are
not reachable from its machine; `EvaluationConsent` and its test ran with a JUnit stand-in, the testing module
compiled against stand-ins, the plugin and MCP server code did not compile there), the dialog, every integration test,
any Copilot run.

## 8. Noticed, not started

- Copilot approval asked again when the test workspace is opened from the desktop shortcut: that start has no
  `AGENTDEBUG_PORT`, so the launcher path differs (port 8765 folder vs `port-21766`) and Copilot sees a new command.
  Ayan uses `java dev-e2e/DevE2E.java start` only (28 Nov 00:04). Real users start one way, so not a product issue.
- Copilot run 28 Nov 00:10 (model MAI Code 2 Flash via Auto): it mistyped its investigationId twice (refused, as
  designed); `wait_for_stop` ended with "MCP error -32001: Request timed out" (Copilot's tool timeout is shorter
  than our wait), and the agent ended its turn while the program waited for the HTTP request. Fix idea: wait in
  shorter slices that answer "still waiting, call again" before Copilot's limit.

- Red PowerShell error at start-up (`$env:COPILOT_POWERSHELL_INTEGRATION_SCRIPT`): Eclipse restores the Terminal tab
  of a Copilot shell from the last session; with the Terminal view closed before a restart it does not appear (Ayan
  27 Nov 19:02). Not ours; Copilot issue.
- Copilot's Chat mode list can come up blank after a restart (seen 27 Nov 19:01); known, OPEN-51.

- Copilot Agent mode reads our skill files (`SKILL.md`, references) although it cannot use our tools (27 Nov step 8).

- Copilot's stored approval in the test workspace (27 Nov 14:06, `mcpExtensionPointContrib`) names
  `C:\Users\vmadmin\.eclipse-agentdebug\start-mcp-server.cmd` without `port-21766` and without `env`, although the
  test Eclipse runs on 21766 (at 00:09 the dialog showed both). Check setup (OPEN-61) shows it as "Copilot approved a
  different command"; find out which start wrote it (DevE2E start without `AGENTDEBUG_PORT`?).

- `SessionReportTest` (mcp-server unit tests) writes report files into the user's home folder
  (`%USERPROFILE%\.eclipse-agentdebug\reports\...`, seen in CI run #1); unit tests should use a temporary folder.
- `dev-e2e/e2e.properties` is tracked in git although `.gitignore` lists it and the working agreement says it is never
  committed (added before the ignore rule); removing it from git needs a commit that deletes it from the index.
- After the project menu **Remove Agentic Debugger Agent**, `spring-boot-debugging` still has a `.github` folder
  (27 Nov); Remove should delete the folders it created when empty (planned with (d), section 5).
- The "Confirm MCP Server Registration" dialog (27 Nov 01:37): explained, Copilot opens it only on a click
  (copilot-integration-notes §6); release checks E watches whether Copilot sees our server as changed at a start.
- Red PowerShell tabs in the Terminal view at start: `. $env:COPILOT_POWERSHELL_INTEGRATION_SCRIPT` fails because the
  variable is empty. Copilot's own terminal (tab "Copilot") works. I think these are Copilot terminals from earlier
  chats restored by Eclipse without Copilot's variable; not checked (close them all, restart, see if they return). Our
  plugin only moves the Terminal view (`TerminalCarrier`), it never starts a shell.
- Agent Activity banner while the consent dialog waits says "Running: Evaluated expression: ..." (past tense);
  clearer: "Waiting for your answer in Eclipse" (27 Nov 02:50).
- The report shows the source lines the agent names in `rootCause` even when its sentence is about another line
  (27 Nov 02:58: `OrderService.java:22` for the code of line 23); the report could check that the quoted code is on
  that line (OPEN-57/OPEN-37).
- `examples/README.md` has two dead links to deleted documents; left unchanged (examples/ rule) until Ayan decides.
