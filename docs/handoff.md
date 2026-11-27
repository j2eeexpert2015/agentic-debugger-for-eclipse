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
Next: step 10A again, 10B (box on), then 11-14.

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

- Setup check: the Claude row reads "Claude Code: Claude Code: Configured" (prefix twice); the skill row does not show
  the installed version, so 0.1.6 in `~/.claude/skills` vs 0.1.7 in the build went unnoticed (27 Nov).
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
