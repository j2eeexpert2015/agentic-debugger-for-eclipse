# Contributing and development

[README](README.md) · [Status and tracking](STATUS.md) · [Changelog](CHANGELOG.md) · [CI and releases](docs/ci.md)

How to build, test and change Agentic Debugger for Eclipse IDE (moved here from the README on 28 Nov 2026).

## Architecture and source map

Architecture: **Claude Code → MCP stdio server → authenticated localhost bridge → Eclipse JDT → Java/JUnit/Spring Boot JVM**. Core locations: [`eclipse-plugin/`](eclipse-plugin/), [`mcp-server/`](mcp-server/), [`testing/`](testing/) (Claude transcript grader), [`report/`](report/) (session report and the Agent Activity page, OPEN-30 and OPEN-36), [`dev-e2e/`](dev-e2e/), [`testing/`](testing/) (grader, Claude trial runner, integration tests as JUnit classes), [`examples/`](examples/) (test fixtures; kept unchanged because the direct tests and Claude trials depend on them), `sample-applications/` (planned demo applications, OPEN-22), [`skills/eclipse-debugger/`](skills/eclipse-debugger/).

## Build and test commands

All commands run from the repository root. There are two kinds of tests, and they never mix:

| Kind | What it checks | Needs | Runs with |
|---|---|---|---|
| **Unit tests** (JUnit 5) | Logic that needs no Eclipse: bridge protocol, ownership and lease rules, output trimming, MCP timeouts, setup utility paths, Claude grader | JDK 21+, Maven | `mvn clean package`, automatically |
| **Integration tests** | The installed plugin in a real Eclipse: launches, breakpoints, stops, variables, Spring Boot, HTTP, cleanup | The dedicated test Eclipse (below) | `java dev-e2e/DevE2E.java ...` only, never in the Maven build |

### Build and unit tests (no Eclipse needed)

```cmd
:: Build everything and run all unit tests; a failing unit test fails the build
mvn clean package

:: Build without running unit tests
mvn clean package -DskipTests

:: Unit tests of one module only, plus the modules it needs (-am), for example mcp-server, testing, report
mvn clean package -pl mcp-server -am
```

Use `package`, not `mvn test`: the plugin build copies the mcp-server jar, which exists only after `package`.

| Module | Unit tests |
|---|---|
| `eclipse-plugin` | 84: BR-U1..U13, OUT-U1..U5, LE-U1..U7, PO-U1..U10 (PO-U8: who owns a launch, OPEN-40; PO-U9: a test run without the debugger does not take the debug session, OPEN-25; PO-U10: an own debug run that ended no longer blocks the next one, OPEN-25), CR-U1..U7 (CR-U7: Claude commands touch only the plugin's own MCP entry, OPEN-09), RJ-U1..U4, PP-U1..U7, SI-U1..U4 (skill installed by the plugin), CP-U1..U7 and CP-U2b (what GitHub Copilot is told about the MCP server; CP-U4..U7 the launcher script that keeps Copilot's approval across Java updates, CP-U7 runs it for real on the build's own system, OPEN-23), CA-U1..U8 (the Copilot custom agent added to and removed from a project; user edits kept, OPEN-23), CS-U1..U4 (the skill for GitHub Copilot on request, OPEN-23), VT-U1..U3 (readable JDK values), JF-U1..U4 (a failed test: location, expected and actual, message, OPEN-25) |
| `mcp-server` | 34: TR-U1..U6 (TR-U6: session control joins the open investigation, OPEN-40), TO-U1..U3, SR-U1..U16 (session report recording; SR-U15 report file in the finish reply; SR-U16 the skill the agent names, OPEN-41; SR-U17 JUnit run and result titles, OPEN-25), AF-U1..U4 (Agent Activity events), FA-U1..U4 (the agent's structured findings and the root cause's source lines) |
| `testing` (Claude transcript grader; the integration tests in it are compiled only, they run with the `integration-tests` profile) | 23: EV-U1..U23 (EV-U22, EV-U23: read-only shell commands listed, not UNSAFE, OPEN-05) |
| `report` (session report and Agent Activity: events, isolation, file names, several consumers, escaping, Markdown, colours, the one JSON reader, the Agent Activity store and page, the HTML report writer and its program flow; used by the MCP server and embedded in the plugin) | 54: RM-U1..U13, RF-U1..U5, AS-U1..U6, AS-U8, AS-U9 (Agent Activity store), AP-U1..U13 (Agent Activity page; AP-U11 the "Agent details" block, OPEN-41; AP-U12 a colour per phase, OPEN-48; AP-U13 where the program is, OPEN-49), HW-U1..U6 (HTML report; HW-U6 events kept and replayed), FP-U1..U9 (program flow of the report and the live view, the agent's findings, purposes and the value tracker in it; FP-U9 breakpoints "not checked" when the agent never asked) |

Build output: `eclipse-update-site/target/agentic-debugger-update-site-*.zip` (install into Eclipse).

### Integration tests (dedicated test Eclipse)

The integration tests are JUnit 5 classes (one per product feature) that drive the installed plugin in a dedicated test Eclipse. One-time setup on each machine: create the untracked `dev-e2e/e2e.properties` from [the example](dev-e2e/e2e.properties.example), with a **dedicated Eclipse installation**, a **disposable workspace** and a **non-default bridge port**; `java dev-e2e/DevE2E.java config` checks it and explains each problem.

```cmd
:: 1. Prepare the test Eclipse (close it first for all/deploy): build + install + start, install + start, or start only
java dev-e2e/DevE2E.java all
java dev-e2e/DevE2E.java deploy
java dev-e2e/DevE2E.java start

:: 2. Run the tests: the regression suite (default, 27 tests); -Dfixture.post.approved=true allows the HTTP tests
mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true

:: Another suite (FullSuite 43, ExtendedSuite 22), one class, or one test
mvn -pl testing verify -Pintegration-tests -Dit.test=FullSuite -Dfixture.post.approved=true
mvn -pl testing verify -Pintegration-tests -Dit.test=BreakpointTests
mvn -pl testing verify -Pintegration-tests -Dit.test=BreakpointTests#conditionalStopsOnlyOnMatch
```

Or right-click `RegressionSuite` or any test class in the IDE, Run As, JUnit Test.

| Test class (report name) | Tests | What it tests | Suite |
|---|---:|---|---|
| `ProgramStartTests` (program-start) | 4 | Finding and starting Java programs and JUnit tests; unknown ones refused | regression |
| `BreakpointTests` (breakpoints) | 3 | Conditional, exception (no blocking dialog) and trace breakpoints | regression |
| `ExecutionControlTests` (execution-control) | 4 | Step into/return/over, step filters, pause, restart | regression |
| `StopsAndVariablesTests` (stops-and-variables) | 4 | What the agent sees when the program stops or ends, nested objects, program output | regression |
| `SpringBootHttpTests` (spring-boot-http) | 2 | A real HTTP request: wrong quote value found, validation error 400 | regression |
| `SpringBootStartupTests` (spring-boot-startup) | 2 | An application that fails to start, or whose port is taken: failure seen, JVM gone | regression |
| `InvestigationIsolationTests` (investigation-isolation) | 2 | One investigation never stops or changes another one's program | regression |
| `AbandonedSessionCleanupTests` (abandoned-session-cleanup) | 5 | Investigations an agent left open are cleaned up | extended |
| `AgentEclipseConnectionTests` (agent-eclipse-connection) | 4 | The agent's connection to Eclipse (bridge): slow command, busy, parallel requests, wrong token | extended |

Every test and what it checks, all options, and the old names (before 25 Nov 2026: `phase1`, `phase2`, `boot`, `boot-quote`, `acceptance`, `boot-gaps`, `stops`, `lease`, `bridge`, and the `DevE2E test` command): [Integration tests](docs/integration-tests.md). Automatic fixture synchronization is not implemented yet (OPEN-01). Details: [Java runner](dev-e2e/README.md).

### Claude end-to-end tests (use Claude usage)

`ClaudeAgentEndToEndTests`: real Claude Code debugs a planted bug with this tool and the skill, graded automatically; the test passes when the verdict is PASS. They need the Claude CLI and run only with `-Dclaude.approved=true`; never part of a suite.

```cmd
:: One trial with the debugging session report (the report path is printed after the verdict)
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootWrongQuote -Dclaude.approved=true -Dfixture.post.approved=true -Dclaude.report=true

:: All three (springBootWrongQuote, junitWrongDiscount, springBootValidation400); add -Dclaude.skill=false for the comparison without the skill
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests -Dclaude.approved=true -Dfixture.post.approved=true

:: The basic check on another model (OPEN-39: does the tool work for Haiku users)
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#junitWrongDiscount -Dclaude.approved=true -Dclaude.model=haiku

:: Rebuild the newest trial report from its recorded events (kept as *.events.jsonl by every trial with -Dclaude.report=true) and run
:: the REPORT CHECK: for report and window changes, no Eclipse, no Claude usage (OPEN-39); or name the events file
java dev-e2e/DevE2E.java report-replay

:: Grade saved transcripts again, and the pass-rate summary (no Eclipse, no Claude usage)
java dev-e2e/ClaudeTranscriptEvaluator.java
java dev-e2e/ClaudeTranscriptEvaluator.java --summary
```

Each run is graded automatically (`*.verdict.json` under `evaluation/reports/claude-trials/`, summary table at the end of a batch). Every delivery says whether a trial is needed; the rule is in the [development workflow](docs/development-workflow.md#6-claude-code-agent-acceptance-separate-evidence-gate) (OPEN-39).

## Development workflow

Coding agents start with [AGENTS.md](AGENTS.md); everyone follows the [development workflow](docs/development-workflow.md) (with the architecture and source map).

Changes follow an **evidence-first, reviewable process**: reproduce and isolate the issue; prepare a minimal, reversible change; compile and test the affected scenario; run broader regression; independently review Claude MCP evidence when applicable; then update the tracking table and documentation. **Do not treat `REVIEW_REQUIRED` as a PASS or run paid Claude trials without approval.**

## Documentation

| Document | What it holds |
|---|---|
| [README](README.md) | Install and use |
| [Status and tracking](STATUS.md) | Current plan, implementation and test status, **the single list of open and finished work** |
| [Changelog](CHANGELOG.md) | What each release contains |
| [CI and releases](docs/ci.md) | GitHub Actions pipeline, making a release, the update site |
| [Release checklist](docs/release-verification-checklist.md) · [Release checks](docs/release-checks.md) | Before publishing a release; manual checks per area |
| [Development workflow](docs/development-workflow.md) | Architecture and source map; how changes are made, tested and handed over |
| [Architecture decisions](docs/decisions.md) | ADRs: safety, ownership, isolation of reporting |
| [Capabilities](docs/capabilities.md) | What is implemented and verified; Spring Boot scenarios SB-01 to SB-10 |
| [Feature catalog](docs/feature-roadmap.md) | Feature IDs and evidence; agent behaviour scenarios EV-01 to EV-16; external research |
| [Integration tests](docs/integration-tests.md) | Every test group and scenario and what it checks; naming standard; old names |
| [Test guide](dev-e2e/README.md) | `DevE2E` commands, suites, Claude trials, setup checks |
| [Designs](docs/design/README.md) | Agent Activity view and report designs and requirements |
| [Copilot integration notes](docs/copilot-integration-notes.md) | How GitHub Copilot for Eclipse finds and runs our server and agent |
| [Working agreement](docs/working-agreement.md) · [Handoff](docs/handoff.md) | How the owner and an assistant work; where the work stands |
| [Coding-agent instructions](AGENTS.md) · [Skill](skills/eclipse-debugger/SKILL.md) | |

Dated phase reports, the old roadmaps, backlog and handoff were removed on 25 Nov 2026 (OPEN-38 step 2); they remain in
the git history.
