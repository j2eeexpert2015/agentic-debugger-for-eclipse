# Local commands (Windows)

[Working agreement](working-agreement.md) · [Handoff](handoff.md) · [Integration tests](integration-tests.md) · [UI tests](ui-tests.md) · [Release checks](release-checks.md)

The commands the owner runs on his Windows machine, one per scenario. Each one is **a single line** to paste into
`cmd` (multi-line pastes get cut or leave the last line waiting for Enter). The steps are joined with `&&`, so the line
stops at the first step that fails (a failed build never deploys an old one). Maven is always `call mvn …`: without
`call`, cmd ends the line after Maven.

An assistant giving a command picks the scenario here, puts **one line of note before it** (what it does), and
replaces `<zip>` with the delivery's zip name. Keep this page up to date when a command changes.

Machine values (from `dev-e2e/e2e.properties`, never committed): repository `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse`,
test Eclipse `C:\All_Softwares\eclipse`, test workspace `%LOCALAPPDATA%\AgenticDebugger\java-e2e-workspace`, bridge port 21766.

## 1. A delivery

**1a. New zip with code: close the test Eclipse, unpack, build (no tests), install the new plugin into the test Eclipse and start it.** The usual round.
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call dev-e2e\close-test-eclipse.cmd && tar -xf "%USERPROFILE%\Downloads\<zip>" -C . && call mvn clean package -DskipTests && java dev-e2e/DevE2E.java deploy
```

**1b. New zip with docs only: unpack it.** Nothing to build.
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && tar -xf "%USERPROFILE%\Downloads\<zip>" -C .
```

**1c. New zip, build only (no Eclipse needed, e.g. the build or the unit tests changed).**
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && tar -xf "%USERPROFILE%\Downloads\<zip>" -C . && call mvn clean package
```

## 2. Test Eclipse

**2a. Close it gracefully** (only the test Eclipse; stops with a message when a dialog is open in it). Never `Stop-Process`: a killed Eclipse does not save its workspace, so removed breakpoints come back.
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call dev-e2e\close-test-eclipse.cmd
```

**2b. Rebuild what is in the repository now and install it** (no new zip).
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call dev-e2e\close-test-eclipse.cmd && call mvn clean package -DskipTests && java dev-e2e/DevE2E.java deploy
```

**2c. Install the last build again without building.**
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call dev-e2e\close-test-eclipse.cmd && java dev-e2e/DevE2E.java deploy
```

**2d. Start it** (or check the one that runs). Always start the test Eclipse this way, not from the desktop shortcut: only DevE2E sets the test port and the test hooks.
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && java dev-e2e/DevE2E.java start
```

**2e. Check `dev-e2e/e2e.properties`.**
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && java dev-e2e/DevE2E.java config
```

## 3. Tests: `mvn verify -Drun=<name>`

One pattern for every test (28 Nov). The name says what is tested, the kind of test, and the Eclipse it needs:
`-without-eclipse` (no Eclipse), `-with-temporary-eclipse` (Maven makes a fresh Eclipse and throws it away; yours is
not touched), `-with-test-eclipse` (your test Eclipse: closed gracefully, given the new build, restarted). Pick the
row of what you changed; GitHub runs everything on every push anyway. Maven prints `[INFO]` lines for the steps it
runs (close, install, start) and the test results at the end.

| You changed | Name | What it runs |
|---|---|---|
| Reports, Agent Activity | `report-unit-tests-without-eclipse` | the report module (HTML report, Agent Activity page) |
| MCP server code | `mcp-server-unit-tests-without-eclipse` | the MCP server module |
| Plugin code without UI | `eclipse-plugin-unit-tests-without-eclipse` | the plugin's logic (registration, setup check, rules), without starting Eclipse |
| The grader | `claude-grader-unit-tests-without-eclipse` | the grader that scores Claude trials (module `testing`) |
| Several of the above | `all-unit-tests-without-eclipse` | all four above (the same as plain `mvn verify`) |
| After a Claude Code setup or update | `claude-code-registration-check-without-eclipse` | your real Claude Code `eclipse-debugger` entry: there, ✓ Connected, our launcher on Windows; changes nothing |
| Preferences pages, welcome, setup check | `eclipse-plugin-ui-tests-with-temporary-eclipse` | SWTBot clicks through the plugin's screens (UI-01..UI-05) in a fresh Eclipse that is thrown away; keep mouse and keyboard off |
| Breakpoints, stepping, launching, variables, Spring Boot, clean-up, the bridge | `eclipse-plugin-debugger-tests-with-test-eclipse` | closes the test Eclipse, installs the new build, starts it, runs the `RegressionSuite`; add `-Dit.test=FullSuite`, a class (`BreakpointTests`) or `Class#method` |
| Feature, update site, packaging | `eclipse-plugin-install-tests-with-test-eclipse` | closes the test Eclipse, installs the built zip, uninstalls, reinstalls, starts it, runs the `RegressionSuite` |
| Skill, tool descriptions, server instructions | `claude-agent-trial-with-test-eclipse` | closes, installs, starts, then real Claude Code debugs the planted quote bug (costs usage); `-Dit.test=ClaudeAgentEndToEndTests#junitWrongDiscount` or `#springBootValidation400` for the others |
| Docs only | — | nothing |
| Build files, CI | — | nothing locally; push and look at GitHub |

Every line is the same, only the name changes, for example:
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call mvn verify -Drun=eclipse-plugin-ui-tests-with-temporary-eclipse
```
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call mvn verify -Drun=eclipse-plugin-debugger-tests-with-test-eclipse -Dit.test=BreakpointTests
```
One class of unit tests: add `-Dtest=ClaudeRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false`.
Before the `-with-test-eclipse` names: Run → Remove All Breakpoints in the test Eclipse (until the tests clean the
test projects themselves). Expected (28 Nov): unit tests report 64, mcp-server 39, testing 23, eclipse-plugin 127; UI
tests 5; `RegressionSuite` 27.

By hand only: the Copilot agent run and the manual checks ([release-checks.md](release-checks.md)).

## 4. Claude Code

**4a. What Claude Code has registered** (Windows should show Command `cmd`, Args `/c …\.eclipse-agentdebug\start-mcp-server.cmd`, Status ✓ Connected). The check `claude-code-registration-check-without-eclipse` (section 3) tests the same.
```
claude mcp get eclipse-debugger
```

**4b. Remove our entry** (to test the setup from scratch; the Claude Code preferences page sets it up again).
```
claude mcp remove eclipse-debugger -s user
```

**4c. Which skill version is installed** (prints the line "skill v0.1.8 …").
```
findstr /c:"skill v" "%USERPROFILE%\.claude\skills\eclipse-debugger\SKILL.md"
```

## 5. Another Eclipse (not the test Eclipse)

**5a. Install the built zip by hand:** Help → Install New Software → Add… → **Archive…** and pick
`C:\All_GitHub_Desktop\agentic-debugger-for-eclipse\eclipse-update-site\target\agentic-debugger-update-site-1.0.0-SNAPSHOT.zip`.
Typing a plain path into Location gives "Scheme not supported"; typed, it must be
`jar:file:/C:/All_GitHub_Desktop/agentic-debugger-for-eclipse/eclipse-update-site/target/agentic-debugger-update-site-1.0.0-SNAPSHOT.zip!/`.
No uninstall needed first: each build has a newer version qualifier, so Eclipse installs it as an update.

**5b. Install from the published update site:** Help → Install New Software → Add… → Location
`https://j2eeexpert2015.github.io/agentic-debugger-for-eclipse/`.

**5c. See the welcome dialog again (release check F14):** close that Eclipse, delete the line `setupPromptShown=true` from
`<workspace>\.metadata\.plugins\org.eclipse.core.runtime\.settings\org.learningfromexperience.eclipse.agentdebug.prefs`, start it.
