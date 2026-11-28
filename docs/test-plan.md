# Test plan: what is tested, where, and when

[Local commands](local-commands.md) · [CI](ci.md) · [Integration tests](integration-tests.md) · [UI tests](ui-tests.md) · [Release checks](release-checks.md) · [Status](../STATUS.md#status-and-tracking)

Proposal of 28 Nov 2026 (Ayan 20:36): one page that says which tests exist, which are a must at which moment, and
which run on GitHub versus only on Ayan's machine. Branch flow (agreed 20:32): short work branches, a pull request
into `main`, no development branch.

## 1. The six kinds of test

How to run each, on any machine: `mvn verify -Drun=<name>` ([local-commands.md](local-commands.md) §3). The names say
what is tested, the kind of test and which Eclipse it needs (`-without-eclipse`, `-with-temporary-eclipse`,
`-with-test-eclipse`); the GitHub jobs carry the same names.

| Kind | What it proves | How many (28 Nov) | Needs | Where it runs |
|---|---|---|---|---|
| **Unit tests** (JUnit 5, Maven) | Logic without Eclipse: bridge protocol, rules, registration forms, reports, grader | ~250 (report 64, mcp-server 39, testing 23, eclipse-plugin 127) | JDK 21+, Maven | GitHub: Windows, Linux, macOS (JDK 21 and 25); local |
| **UI tests** (SWTBot, `-Drun=eclipse-plugin-ui-tests-with-temporary-eclipse`) | What a user sees and clicks: welcome dialog, Preferences pages, setup check, Claude Code Update | 5 (UI-01..UI-05) | An Eclipse that Maven starts; a screen (Linux: virtual screen Xvfb) | GitHub: Windows, Linux, macOS; local |
| **Debugger tests** (`testing` module, Maven profile `integration-tests`) | The installed plugin drives the real Eclipse debugger: start programs and tests, breakpoints, stepping, variables, Spring Boot and HTTP, clean-up, the bridge | `RegressionSuite` 27, `ExtendedSuite` 22, `FullSuite` both | The test Eclipse with the plugin installed (DevE2E) and the fixture projects | GitHub: Windows, Linux, macOS; local |
| **Install tests** | The built update-site zip installs, uninstalls and reinstalls in a clean Eclipse, then the `RegressionSuite` passes on it; on Windows also on the next Eclipse release (2026-09) | 3 steps + `RegressionSuite` | A clean Eclipse download | GitHub: Windows, Linux, macOS |
| **Agent trials** | A real agent finds a planted bug with our tools and the skill | Claude: 3 trials (`ClaudeAgentEndToEndTests`), graded automatically; Copilot: by hand | Claude Code signed in (costs usage); Copilot signed in | Local (Claude on GitHub possible with an API key, see §4) |
| **Manual checks** | What no automation covers yet: Copilot dialogs and modes, Agent Activity look, first run | `release-checks.md` A–F (F12–F15 …) | Ayan's eyes | Local |

## 2. Where and when (Ayan 28 Nov 21:14: Windows is the gate; Linux and macOS from time to time)

| Moment | What runs | Where | Required |
|---|---|---|---|
| **Push to a work branch** (not docs-only) | Short check on Windows: all unit tests, UI tests | GitHub | No |
| **Pull request into `main`**, and `main` | "CI and release", Windows: unit tests, UI tests, debugger tests (`FullSuite`), install tests, Eclipse 2026-09, release candidate | GitHub | **Yes** |
| **Push to a `ci/…` branch** | All four kinds on all three systems (pipeline work; the button exists only once the workflow is on `main`) | GitHub | No |
| **Run workflow** button, any branch, chosen systems | Unit, UI, debugger and install tests on Windows, Linux and/or macOS | GitHub | No |
| **Every Sunday** | The same on `main`, all three systems | GitHub | No |
| **Locally, must** | Whatever needs an account or eyes: the Claude agent trial when what the agent reads changed (skill, tool descriptions, server instructions); Copilot run and manual checks of a changed Copilot area or screen | Local | Before the pull request |
| **Locally, recommended** | The `-Drun` name of the area changed (unit tests of that module; debugger tests of that class, since branch pushes do not run them) | Local | No |
| **Before publishing a release** | Manual checks of everything changed since the last release; one Claude trial; one Copilot run | Local | Yes (Ayan) |

Linux and macOS are not required for a merge. Until the debugger tests pass there, a release says it is **tested on
Windows**. No separate Linux/macOS branches: the button tests exactly the code chosen, nothing to keep in sync.

Not a must (on purpose only): `ExtendedSuite` alone, single classes (`integration-tests.md` "Which check for which
change"), all three Claude trials together.

## 3. Order of work

1. Branch `ci/open-73-test-names-all-platforms` (28 Nov, `ci-cross-platform-3.zip`): the `-Drun` names, the workflow
   above, `dev-e2e/TestSteps.java`, macOS support in DevE2E and the UI tests, UI tests in "CI and release". Then the
   first Run workflow on all systems, and fixes system by system.
2. The `main` ruleset (pull request required, "CI and release" green, squash merge), set by Ayan (`docs/ci.md`).
3. Linux and macOS jobs required once green.
4. Skip the long jobs for docs-only changes.
5. More UI tests, so that the manual checks shrink.

## 4. Agent trials on GitHub?

- **Claude:** possible. Claude Code runs without a person when it has an API key: store `ANTHROPIC_API_KEY` as a
  repository secret, run the trials in a workflow that only starts by hand (Run workflow), on Windows first. Each run
  is billed to the API account (separate from a Claude subscription). Not built yet; Ayan decides.
- **Copilot:** no. Copilot is a chat inside Eclipse with a GitHub sign-in; there is no supported way to drive it
  without a person. It stays a manual check.

## 5. Names that mislead (OPEN-73, to decide)

`dev-e2e/DevE2E.java` is **not** a test suite and runs no test. It is the tool that **prepares the test Eclipse** on
a machine: checks the settings, builds, installs the plugin into that Eclipse and starts it on the test workspace and
port (modes `config`, `build`, `all`, `deploy`, `start`). `e2e.properties` holds this machine's paths for it (repository,
test Eclipse folder, workspace, port). The names should say that. Candidates (Ayan picks):

| Today | Option A | Option B |
|---|---|---|
| folder `dev-e2e/` | `test-eclipse-setup/` | `local-test-setup/` |
| `DevE2E.java` | `SetUpTestEclipse.java` (`java test-eclipse-setup/SetUpTestEclipse.java deploy`) | `TestEclipseSetup.java` |
| `e2e.properties` / `.example` | `this-machine.properties` | `test-eclipse-paths.properties` |
| module `testing` | `debugger-tests` (it holds the debugger tests and the agent-trial runner) | unchanged |

Unchanged in every option: `AGENTDEBUG_E2E=1` (read by the plugin), the Maven profile names. The rename is one
delivery that updates every workflow and doc, after the CI work is merged.

## 6. Known gaps

- The docs count `RegressionSuite` 27 and `ExtendedSuite` 22; the Windows workflows check "at least 21" and "at least
  43". Both pass; the first all-platforms run shows the real numbers, then the minimums are raised.
