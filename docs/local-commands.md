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

## 3. Tests

**3a. Full check before a merge: build with all unit tests, install, remove breakpoints (the line waits for a key), regression suite.**
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call dev-e2e\close-test-eclipse.cmd && call mvn clean package && java dev-e2e/DevE2E.java deploy && echo In the test Eclipse: Run - Remove All Breakpoints - Yes. Then press a key here. && pause && call mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true
```
Expected (28 Nov): unit tests report 64, mcp-server 39, testing 23, eclipse-plugin 127; "The test Eclipse is ready"; `RegressionSuite` 27 tests, 0 failures.

**3b. Integration tests only** (the test Eclipse runs with the current build; remove all breakpoints in it first). Replace `RegressionSuite` with `FullSuite`, `ExtendedSuite`, a class (`BreakpointTests`) or `Class#method`.
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call mvn -pl testing verify -Pintegration-tests -Dit.test=RegressionSuite -Dfixture.post.approved=true
```

**3c. UI tests (SWTBot).** Maven starts its own Eclipse; keep mouse and keyboard off while it runs. Runs only these 5 tests (UI-01..UI-05), with throw-away Claude settings.
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call mvn clean verify -Pui-tests
```
Expected (28 Nov 20:06): `Tests run: 5, Failures: 0`. Results in `eclipse-plugin-ui-tests\target\surefire-reports\`.

**3d. Claude trial** (costs usage; only when a delivery says "Trial needed: yes").
```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse && call mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootWrongQuote -Dclaude.approved=true -Dfixture.post.approved=true -Dclaude.report=true
```

## 4. Claude Code

**4a. What Claude Code has registered** (Windows should show Command `cmd`, Args `/c …\.eclipse-agentdebug\start-mcp-server.cmd`, Status ✓ Connected).
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
