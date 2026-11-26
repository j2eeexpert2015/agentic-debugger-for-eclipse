# Handoff for a new chat (27 Nov 2026, 02:00 IST)

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

Expected today: BUILD SUCCESS with unit tests **report 54, mcp-server 34, testing 23, eclipse-plugin 84**; deploy
ends with "The test Eclipse is ready"; `RegressionSuite` 21 tests, 0 failures. A Claude trial (costs usage) only when
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

**B. OPEN-23 (d) check: done 27 Nov 01:15-01:37** (details in the README OPEN-23 row). One agent file in
`%USERPROFILE%\.copilot\agents\` is enough: listed once (empty Project column), our tools run, right cause found,
still in the mode list after a restart. The user file is still on Ayan's machine (keep it until (d) is built).

## 5. Next, in this order

1. **OPEN-23 (d) implementation:** opt-in Install/Remove of the agent in `~/.copilot/agents/` on the preferences page
   (same checksum protection as the project file), project menu stays, a warning when the agent exists in several
   places; Remove also deletes the `.github/agents` and `.github` folders it created when they are left empty.
2. **OPEN-58** (Ayan, 27 Nov: right after (d)): user consent in Eclipse for side-effecting evaluation, plus MCP tool
   annotations. Demo of the dialog first.
3. **OPEN-46** (moved up, proposed 27 Nov, Ayan to confirm): a short manual protocol for Copilot runs (fixed prompts, fields per run, results table in
   `docs/copilot-integration-notes.md`), used by 4 and 5.
4. OPEN-23 (b): neutral wording in tool descriptions and skill for Copilot's Agent mode ("starts the test itself,
   no running session needed"), not forbidding Copilot's own tools; check with the OPEN-46 protocol and one Claude batch.
5. OPEN-57: exact names and parameters (`lineNumber`, `investigationId`, `allowSideEffects`, reference file paths)
   in SKILL.md and the agent file; measured with the OPEN-46 protocol before and after.
6. CI on its branch: 2.1 first run, then 1.3 (Linux, macOS), merge into `main`, 1.4, 2.2 (`docs/ci.md` there).
7. Later: OPEN-45 signing (only changes the "Signed" label in Copilot; needs a certificate), OPEN-43 console,
   OPEN-51 upstream report (draft in `docs/copilot-mode-list-bug.md`), OPEN-53 `DevE2E stop`, OPEN-56 releases.

## 6. Where the recent code is

| Area | Files |
|---|---|
| Copilot server offer and launcher | `eclipse-plugin/src/.../integration/CopilotMcpProvider.java`, `CopilotRegistration.java` (`json`, `jsonDirect`, `launcherText`, `writeLauncher`, `runtimeFolder`); tests `CopilotRegistrationTest` CP-U1..U7 |
| Copilot agent file | template `eclipse-plugin/copilot/agentic_debugger.agent.md`; `integration/CopilotAgentInstaller.java` (`AGENT_FILE`), `ui/AddCopilotAgentHandler.java`, Remove handler; tests CA-U* |
| Skill for Copilot | `integration/CopilotSkill.java`, preferences page; source `skills/eclipse-debugger/` (v0.1.5) |
| Agent Activity banner | `report/src/main/java/.../report/activity/ActivityPage.java` (`phase()`, `where()`), `FlowPage.java` (dark CSS), `eclipse-plugin/.../ui/AgentStatusControl.java` |
| Quiet server | `mcp-server/.../DebugMcpServer.java`, `SessionReport.java` (`ENV_VERBOSE`, `verbose(env)`) |
| CI | `.github/workflows/ci.yml`, `integration-windows.yml`, `docs/ci.md` (branch `ci/github-actions` until merged) |

## 7. Last delivery

`open-23-d-check.zip` (27 Nov 2026, docs only, on `main`): README (OPEN-23 (d) check result, OPEN-57 more cases, new
OPEN-58), this file (header, 4, 5, 7, 8), `docs/working-agreement.md` §4 (no continued line starting with `(`),
`docs/copilot-integration-notes.md` (new §5). Nothing to build.

## 8. Noticed, not started

- `SessionReportTest` (mcp-server unit tests) writes report files into the user's home folder
  (`%USERPROFILE%\.eclipse-agentdebug\reports\...`, seen in CI run #1); unit tests should use a temporary folder.
- `dev-e2e/e2e.properties` is tracked in git although `.gitignore` lists it and the working agreement says it is never
  committed (added before the ignore rule); removing it from git needs a commit that deletes it from the index.
- After the project menu **Remove Agentic Debugger Agent**, `spring-boot-debugging` still has a `.github` folder
  (27 Nov); Remove should delete the folders it created when empty (planned with (d), section 5).
- The "Confirm MCP Server Registration" dialog appeared at a test-Eclipse start although our server was already
  Approved (27 Nov 01:37); unclear whether at every start or only after `deploy`. Note at the next restarts.
- Red PowerShell tabs in the Terminal view at start: `. $env:COPILOT_POWERSHELL_INTEGRATION_SCRIPT` fails because the
  variable is empty. Copilot's own terminal (tab "Copilot") works. I think these are Copilot terminals from earlier
  chats restored by Eclipse without Copilot's variable; not checked (close them all, restart, see if they return). Our
  plugin only moves the Terminal view (`TerminalCarrier`), it never starts a shell.
- `examples/README.md` has two dead links to deleted documents; left unchanged (examples/ rule) until Ayan decides.
