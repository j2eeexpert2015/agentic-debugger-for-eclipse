# Handoff for a new chat (27 Nov 2026, 00:40 IST)

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

**A. CI phase 1 (OPEN-54), step 1.2: Windows JDK 21, run pending.** Windows first, then the other systems (Ayan,
27 Nov). Work happens on branch `ci/github-actions`; `main` stays for development. **Step 1.1 done, checked 27 Nov
00:47:** run #1 (Windows, JDK 25) green in 2 min 51 s, unit tests 54/34/23/84 = 195, 0 failures, both p2 sites
reached, update-site zip kept. `ci-phase1-2-jdk21.zip` adds JDK 21 to the matrix (two jobs: `windows-latest / JDK 25`
and `/ JDK 21`) and moves `upload-artifact` to v5 (Node 20 deprecation warning in run #1). Ayan extracts it on the
branch, commits, pushes, sends both jobs' tables. Next: step 1.3 (Linux, macOS), then merge into `main`, then 1.4
(launcher on Linux/macOS when CP-U7 passes there). Phase 2 (OPEN-55, integration tests) also Windows first; plan in
`docs/ci.md`.

**B. OPEN-23 (d) check, not run yet.** Question: offer the agent once per user in `%USERPROFILE%\.copilot\agents\`
(Copilot's language server reads it for every workspace) instead of a copy in each project (each open project's copy
shows as a separate entry). Ayan's manual check, in order:
1. copy `...\java-e2e-workspace\spring-boot-debugging\.github\agents\agentic_debugger.agent.md` to
   `%USERPROFILE%\.copilot\agents\`;
2. in Copilot's mode list open **Configure Agents...** once (reload, OPEN-51) and count the "Agentic Debugger" entries
   (expected: one per project with the file plus one from the user folder);
3. remove the agent from both projects (project menu **Remove Agentic Debugger Agent for GitHub Copilot**), reload,
   expect exactly one entry; new chat, prompt below, tools must run;
4. restart the test Eclipse (`java dev-e2e/DevE2E.java start` after the close step): is the entry there directly or
   only after the reload?
5. afterwards remove the user file (`del "%USERPROFILE%\.copilot\agents\agentic_debugger.agent.md"`) or keep it.

The user file shows the agent in **every** Eclipse with Copilot on that machine, so if built it is an opt-in
Install/Remove on the preferences page (with the same checksum protection as the project file), the project menu
stays, and a warning when the agent exists in several places.

Check prompt for Copilot (Agentic Debugger mode, new chat):
`Use the eclipse-debugger tools to debug OrderService in phase-one-debugging and tell me why the total is wrong.`

## 5. Next, in this order

1. Finish 4A (CI steps 1.2, 1.3) and 4B (d check), one at a time.
2. OPEN-23 (d) implementation, if the check supports it.
3. OPEN-23 (b): neutral wording in tool descriptions and skill for Copilot's Agent mode ("starts the test itself,
   no running session needed"), not forbidding Copilot's own tools; check with Copilot runs and one Claude batch.
4. OPEN-57: models guess names close to ours (`projectName` on `list_java_applications`, `cleanup` on
   `finish_debug_investigation`, `references/spring-boot.md` instead of `spring-boot-debugging.md`); measure in the
   next Copilot runs, then list exact file paths and parameters in SKILL.md and the agent file.
5. Later: OPEN-45 signing (only changes the "Signed" label in Copilot; needs a certificate), OPEN-43 console,
   OPEN-51 upstream report (draft in `docs/copilot-mode-list-bug.md`), OPEN-53 `DevE2E stop`, OPEN-55/56 CI phases.

## 6. Where the recent code is

| Area | Files |
|---|---|
| Copilot server offer and launcher | `eclipse-plugin/src/.../integration/CopilotMcpProvider.java`, `CopilotRegistration.java` (`json`, `jsonDirect`, `launcherText`, `writeLauncher`, `runtimeFolder`); tests `CopilotRegistrationTest` CP-U1..U7 |
| Copilot agent file | template `eclipse-plugin/copilot/agentic_debugger.agent.md`; `integration/CopilotAgentInstaller.java` (`AGENT_FILE`), `ui/AddCopilotAgentHandler.java`, Remove handler; tests CA-U* |
| Skill for Copilot | `integration/CopilotSkill.java`, preferences page; source `skills/eclipse-debugger/` (v0.1.5) |
| Agent Activity banner | `report/src/main/java/.../report/activity/ActivityPage.java` (`phase()`, `where()`), `FlowPage.java` (dark CSS), `eclipse-plugin/.../ui/AgentStatusControl.java` |
| Quiet server | `mcp-server/.../DebugMcpServer.java`, `SessionReport.java` (`ENV_VERBOSE`, `verbose(env)`) |
| CI | `.github/workflows/ci.yml`, `docs/ci.md` (branch `ci/github-actions` until merged into `main`) |

## 7. Last delivery

`ci-phase1-2-jdk21.zip` (27 Nov 2026): `.github/workflows/ci.yml` (header comment, `java: [ '25', '21' ]`,
`upload-artifact@v5` twice), `docs/ci.md` (plan table: 1.1 done with evidence, 1.2 JDK 21, 1.3 Linux/macOS, 1.4
launcher, phase 2 Windows first), README OPEN-54 and OPEN-55, this file (4A, 5, 6, 7, 8). Not checked by the
assistant: the GitHub run (no access to the Actions tab; YAML only parsed locally), whether the build runs on JDK 21
(the assistant did not build).

## 8. Noticed, not started

- `SessionReportTest` (mcp-server unit tests) writes report files into the user's home folder
  (`%USERPROFILE%\.eclipse-agentdebug\reports\...`, seen in CI run #1); unit tests should use a temporary folder.
- `dev-e2e/e2e.properties` is tracked in git although `.gitignore` lists it and the working agreement says it is never
  committed (it was added before the ignore rule); removing it from git needs a commit that deletes it from the index.
- `examples/README.md` has two dead links to deleted documents; left unchanged (examples/ rule) until Ayan decides.
