# UI tests (SWTBot)

[Contributing](../CONTRIBUTING.md) · [Status and tracking](../STATUS.md) · [Release checks](release-checks.md)

OPEN-72 (28 Nov 2026). The click-through of the release checks F12-F14, automated: a real Eclipse with the plugin
starts in a fresh workspace and a test clicks through it like a user (SWTBot, Eclipse's UI test tool), reading the
texts that are shown.

## Running them

One flag picks the suite:

| Command | Runs |
|---|---|
| `mvn clean verify` | Everything as before: build and all unit tests (the integration tests stay in `testing`, `-Pintegration-tests`) |
| `mvn clean verify -Pui-tests` | Builds everything, skips those tests, and runs only the UI tests |

On Windows (cmd), from the repository root:

```cmd
call mvn clean verify -Pui-tests
```

- Maven downloads SWTBot from `download.eclipse.org` the first time.
- An Eclipse window opens and is clicked by the test: **don't use mouse or keyboard** until it closes (a few
  minutes).
- The test Eclipse uses its own port (21799) and its own home folder (`eclipse-plugin-ui-tests/target/home`), so your
  normal Eclipse, the DevE2E test Eclipse and `~/.eclipse-agentdebug` are not touched. It does run `claude mcp get
  eclipse-debugger` for the Claude Code page, with **its own Claude Code settings folder**
  (`eclipse-plugin-ui-tests/target/claude-config`, through `CLAUDE_CONFIG_DIR`), so your `~/.claude.json` is never
  read or changed. UI-05 stops without changing anything if it ever sees an existing `eclipse-debugger` entry, and
  checks that Claude Code wrote into the test folder.
- Results: `eclipse-plugin-ui-tests/target/surefire-reports/`. The tests are JUnit 5 and run in name order, ui01 to
  ui05. The manifest allows JUnit 5 only (`[5,6)`): with JUnit 6 also allowed, Tycho found no tests.

## What they check

| Test | What |
|---|---|
| UI-01 | The welcome at the first start: "Agentic Debugger is installed.", a line per agent (Claude Code found or not installed; GitHub Copilot not installed), the path "Window → Preferences → Agentic Debugger.", Set up now and Later; Later closes it and no second popup follows. With no agent: the no-agent text and OK. |
| UI-02 | Main page: "For every agent.", Check again, Copy report; the setup check runs by itself and shows "Checked at HH:mm". |
| UI-03 | Claude Code page: "For Claude Code only." and a Setup state (Set up, Not set up, Needs an update, …). |
| UI-05 | OPEN-71 (incident 28 Nov): Claude Code gets an old-style entry whose Java doesn't exist; the Claude Code page shows "⚠ Needs an update"; **Update** → "✔ Set up"; `claude mcp get eclipse-debugger` then names the launcher (Windows) and says Connected; the entry is removed at the end. Skipped when Claude Code isn't on the PATH. |
| UI-04 | GitHub Copilot page without Copilot: only "GitHub Copilot isn't installed in this Eclipse." and "Install it from Help → Eclipse Marketplace, then restart Eclipse." |

GitHub Copilot is not installed in the test Eclipse, so the Copilot pages are tested in their not-installed case only.
Whether Claude Code is found depends on the machine (the PATH); UI-01 accepts both.

## Still manual

Copilot's own dialogs and sign-in; colours, fonts and layout; a real Claude Code or Copilot investigation (the Claude
end-to-end test in `testing`); the status-bar note; the Set up and Remove buttons.

## Next

More tests per feature (Agent Activity view, status bar, Set up / Update against a throw-away Claude configuration);
the GitHub pipeline on Windows and, with a virtual screen, Linux (OPEN-54/55).
