# Changelog

Releases are on the [Releases page](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases) and on
the update site `https://j2eeexpert2015.github.io/agentic-debugger-for-eclipse/`. Until the first final release, every
release is a **preview** (`v1.0.0-preview.N`). Detailed history: [Status and tracking](STATUS.md) (Done table).

## v1.0.0-preview.1 (in preparation)

First release made by the release pipeline ([docs/ci.md](docs/ci.md)).

- Debugger control for an AI agent through MCP: Java programs, JUnit 5 tests and Spring Boot applications; line,
  conditional and exception breakpoints, tracepoints, stepping, pause, resume, restart, step filters; threads, stack,
  variables, program output, JUnit results, HTTP requests.
- Claude Code: set up from Preferences → Agentic Debugger → Claude Code; optional `eclipse-debugger` skill.
- GitHub Copilot for Eclipse: the server offered to Copilot; the **Agentic Debugger** agent; by default only that agent
  uses the debugger tools, without asking each time.
- Agent Activity view or window, and an HTML report for every investigation.
- Safety: the agent changes only the breakpoints and launches it created; cleanup of abandoned investigations;
  optional approval before code runs in the program.
- Preference pages per agent, with a setup check and a status-bar note when something needs fixing; a short welcome
  after installing that says which agent was found and opens its page.
- Tested on Windows with Eclipse 2025-12 and 2026-09 (43 integration tests, install, uninstall and reinstall of the
  update-site zip). The plugin is not signed.

The earlier hand-made `v1.5.0-preview.1` (24 Nov 2026) is outdated; versions start again at 1.0.0.
