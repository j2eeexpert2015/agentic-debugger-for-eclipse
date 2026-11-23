# Agentic Debugger for Eclipse IDE

Debug **Java, JUnit 5, and Spring Boot** applications in Eclipse with **Claude Code** through the Model Context Protocol (MCP). The plugin uses Eclipse Java Debug Tools (JDT) for actual execution control; Claude selects and invokes MCP tools. Safety checks for session ownership, breakpoints and cleanup are implemented in Java, not left solely to prompt instructions.

**Evidence snapshot — 24 November 2026 (Windows, JDK 25.0.5, Eclipse 2026-09):** direct regression **23/23**, stop/output group **6/6**, abandoned-investigation group **5/5**, bridge fault injection **4/4** (23 Nov), unit tests **49/49** (JUnit 5, run by the Maven build), all with verified cleanup. Repeated Claude trials (3 per scenario, graded automatically from actual debugger results): **9/9 PASS with the skill, 17/18 without** (the one FAIL is a run where the Claude CLI ended early). Small samples on test fixtures: an indication, not a reliability statistic. Details: [current state](docs/current-state.md), [acceptance evidence](docs/claude-acceptance-2026-11-23.md).

## Current status and next step

The debugger features are sufficient for demonstrations of Java and Spring Boot applications. **Current plan (24 Nov 2026), in this order:**

1. **Test structure:** unit tests as JUnit 5 in the Maven build, integration tests reorganized by feature (OPEN-17, OPEN-18).
2. **Debugging session report** (OPEN-30): every step the agent takes in an investigation (files, breakpoint lines, stops, variable values, HTTP results, cleanup, the agent's conclusion, optional screenshots) recorded and written as an HTML report. Decoupled behind a report listener that can be switched off; first report writer ChainTest, after a prototype.
3. **Demo sample applications** in a new `sample-applications/` folder: **one plain Java application and one Spring Boot application**, built from the scenarios that are easy to show visually, for videos and live demos (OPEN-22). The existing `examples/` projects stay **unchanged**: they are the test fixtures that all direct tests and Claude trials are written against.
4. **GitHub Copilot integration** in Eclipse through the same MCP server (OPEN-23).

All other work follows these steps. Everything open is tracked in **one table: [Status and tracking](#status-and-tracking)**.

## Install and use

### Which Eclipse IDE?

Use **Eclipse IDE for Enterprise Java and Web Developers** (recommended for Spring Boot) or **Eclipse IDE for Java Developers**, with **Java/JDT debugging, JUnit and Maven/m2e** available. This is an **Eclipse IDE workbench plugin**, **not a standalone Eclipse RCP/RAP runtime application**. The Eclipse IDE for RCP and RAP Developers package has not been independently tested; it would need the Java tooling installed. **Spring Tools is optional**, and its specialized launch modes remain unverified.

Requirements: **JDK 21+** and authenticated Claude Code for Claude-driven use. Building from source requires Maven and Git. The 23-scenario direct regression ran on Windows, Eclipse 4.41 and JDK 25.0.5; no Python is required by the Java E2E framework.

### Install the plugin

1. Obtain an Eclipse **update-site ZIP** from [GitHub Releases](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases) if available, or run `mvn clean package` and use the generated `eclipse-update-site/target/agentic-debugger-update-site-*.zip`. A GitHub source ZIP is **not** an installable Eclipse update site.
2. In Eclipse use **Help → Install New Software → Add → Archive**, select the update-site ZIP, install **Agentic Debugger for Eclipse IDE**, and restart.
3. Use **Window → Preferences → Agentic Debugger** to configure Claude Code. With Eclipse running and the project imported, check Claude's `/mcp` for `eclipse-debugger` and call `eclipse_ping` (expected `PONG`).

See [installation and validation](docs/eclipse-installation-and-validation.md) for troubleshooting. To install and register the MCP server from a terminal instead, or to add it manually to Claude Code or another MCP client, use the [setup utility](installer/README.md).

### Ask Claude to investigate

Example symptom-only request: *“My orders API returns 120.00, but I expect 108.00. Find the cause. Do not modify source or unrelated running applications.”*

`NORMAL` investigations remove their **own** debugging resources when properly finished; `CONTROLLED` investigations may retain state. Neither authorizes changing user-owned breakpoints or unrelated launches. The [optional skill](skills/eclipse-debugger/SKILL.md) is not required for core MCP debugging.

## Implementation and test status

**Implemented:** Java main discovery and launch; line, conditional and exception breakpoints; tracepoints; Step Into/Over/Out; pause/resume/restart; step filters; thread/stack/variable inspection; selected JUnit tests; Boot launch and HTTP request investigation; authenticated MCP bridge; investigation ownership and cleanup. Added and tested on the installed build 23–24 Nov 2026: **bridge stability** (CORE-13: parallel reads, per-command deadlines, `BRIDGE_TIMEOUT`/`BRIDGE_BUSY`, timing log `~/.eclipse-agentdebug/bridge-<port>.log`); **event-based `wait_for_stop`** (CORE-14: stop reason, thread, location, frames and locals in one call; never reports a stop twice); **process output head+tail** (CORE-15); **no blocking "Repeated exception occurrence" dialog, verified JVM termination on cleanup** (CORE-16); **cleanup of abandoned investigations** (CORE-17: on MCP disconnect, after 10 min idle, and of leftover breakpoints after an Eclipse restart; breakpoints/launches without an ID join the open investigation). The older `wait_for_debug_pause` still polls and is kept for compatibility.

**Latest Windows direct-test results (24 November 2026, CORE-17 build):**

| Suite | PASS | Scope |
|---|---:|---|
| `phase1` | 7/7 | Java/JUnit breakpoints, variables, stepping-related controls, trace and filters |
| `phase2` | 2/2 | Boot validation HTTP 400, safety and ownership |
| `boot` | 2/2 | Boot discovery and readiness |
| `boot-quote` | 1/1 | Real HTTP quote investigation and wrong discount value |
| `acceptance` | 4/4 | Target rejection, ownership checks and Step Into/Out |
| `boot-gaps` | 7/7 | Two Boot mains/services, failed startup, bounded timeout, port conflict, isolated cleanup, invalid JUnit method |
| **Regression total** | **23/23** | All `cleanup=true`; on 23 Nov also 3 consecutive uninterrupted runs (CORE-13 build) |
| `stops` (opt-in) | 6/6 | `wait_for_stop`: breakpoint, step, exception, pause, program end; process output |
| `lease` (opt-in) | 5/5 | Abandoned investigations: idle expiry, long waits kept alive, CONTROLLED kept, MCP disconnect, ambiguous ownership rejected |
| `bridge` (opt-in, 23 Nov) | 4/4 | Reads stay fast during a slow command; `BRIDGE_TIMEOUT`, `BRIDGE_BUSY`, 20 parallel reads, authentication |
| Unit tests (JUnit 5, in `mvn clean package`) | 49/49 | Plugin 41, mcp-server 8 (24 Nov); setup utility 7 and Claude grader 18 added, waiting for build check |

The failing-startup and port-conflict tests confirmed fixture JVM exit using OS PIDs. This is **local working-tree evidence**; it does not independently certify a published release or other systems. Report files are generated under ignored `evaluation/reports/`. See [Java runner](dev-e2e/README.md).

**Agent behavior (23 November 2026):** Claude runs are graded automatically (PASS/PARTIAL/FAIL/BLOCKED/UNSAFE) from the debugger's actual results plus a before/after Eclipse inventory. Clean batches, 3 runs per scenario (Boot wrong quote, Boot HTTP 400, JUnit): **with skill 9/9 PASS, without skill 17/18 PASS**; every run used `wait_for_stop`; cleanup independently verified. On these explicit prompts the skill added no accuracy and cost about 30% more calls and money; whether it helps on vague, symptom-only prompts is open (OPEN-05). See [evidence ledger](docs/claude-acceptance-2026-11-23.md).

**Limits:** Run to Line, variable mutation, field watchpoints, JDWP attach, thread-dump export and Hot Code Replace are not exposed as verified MCP features. STS-specific modes, Linux/macOS, more complex Spring-context JUnit cases and a full user-marker/configuration matrix need separate tests. Every open item is listed in [Status and tracking](#status-and-tracking).

## Developer quickstart

Architecture: **Claude Code → MCP stdio server → authenticated localhost bridge → Eclipse JDT → Java/JUnit/Spring Boot JVM**. Core locations: [`eclipse-plugin/`](eclipse-plugin/), [`mcp-server/`](mcp-server/), [`installer/`](installer/) (setup utility), [`testing/`](testing/) (Claude transcript grader), [`dev-e2e/`](dev-e2e/), [`e2e-runner/`](e2e-runner/), [`examples/`](examples/) (test fixtures; kept unchanged because the direct tests and Claude trials depend on them), `sample-applications/` (planned demo applications, OPEN-22), [`skills/eclipse-debugger/`](skills/eclipse-debugger/).

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

:: Unit tests of one module only (mcp-server, installer, testing)
mvn clean package -pl mcp-server
```

Use `package`, not `mvn test`: the plugin build copies the mcp-server jar, which exists only after `package`.

| Module | Unit tests |
|---|---|
| `eclipse-plugin` | 41: BR-U1..U13, OUT-U1..U5, LE-U1..U7, PO-U1..U6, CR-U1..U6, RJ-U1..U4 |
| `mcp-server` | 8: TR-U1..U5, TO-U1..U3 |
| `installer` (setup utility) | 7: IP-U1..U4, CD-U1..U3 |
| `testing` (Claude transcript grader) | 18: EV-U1..U18 |

Build outputs: `eclipse-update-site/target/agentic-debugger-update-site-*.zip` (install into Eclipse), `installer/target/agentic-debugger-setup.jar` ([setup utility](installer/README.md)).

### Integration tests (dedicated test Eclipse)

One-time setup: create the untracked `dev-e2e/e2e.properties` from [the example](dev-e2e/e2e.properties.example), using a **dedicated Eclipse installation**, a **disposable workspace** and a **non-default bridge port**.

```cmd
:: Build, install the new plugin into the test Eclipse, and run the 23-scenario regression
:: (also runs the unit tests through the build; close the test Eclipse first)
java dev-e2e/DevE2E.java all --approve-fixture-post

:: Install the last build without rebuilding, then run the regression
java dev-e2e/DevE2E.java deploy --approve-fixture-post

:: Integration tests only, against the plugin already installed (starts the test Eclipse if needed)
java dev-e2e/DevE2E.java test --approve-fixture-post

:: Selected groups, or single scenarios
java dev-e2e/DevE2E.java test --groups phase1,boot-gaps --approve-fixture-post
java dev-e2e/DevE2E.java test --tests boot-gaps:port-conflict,acceptance:core-step-into-out

:: All groups and scenario names
java dev-e2e/DevE2E.java list
```

`--approve-fixture-post` allows HTTP requests to the local test Spring Boot application only; groups without HTTP requests do not need it.

| Group | Scenarios | In the 23-scenario regression |
|---|---:|---|
| `phase1` | 7 | Yes |
| `phase2` | 2 | Yes |
| `boot` | 2 | Yes |
| `boot-quote` | 1 | Yes |
| `acceptance` | 4 | Yes |
| `boot-gaps` | 7 | Yes |
| `stops` | 6 | No, run with `test --groups stops` |
| `lease` | 5 | No, run with `test --groups lease` |
| `bridge` | 4 | No, run with `test --groups bridge` |

The group names will change to feature names (`--feature`, `--suite regression`) with OPEN-18. Automatic fixture synchronization is not implemented yet (OPEN-01). Older PowerShell scripts still support specialized paths and must not be deleted without parity checks. Details: [Java runner](dev-e2e/README.md).

### Claude trials (use Claude usage)

Require the Claude CLI with the `eclipse-debugger` MCP registration; never run by the build or by a plain `test`.

```cmd
:: One trial, or 3 runs of each scenario; add --no-skill for the comparison without the skill
java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude
java dev-e2e/DevE2E.java test --groups claude --repeat 3 --approve-claude --approve-fixture-post

:: Grade saved transcripts again, and the pass-rate summary (no Eclipse, no Claude usage)
java dev-e2e/ClaudeTranscriptEvaluator.java
java dev-e2e/ClaudeTranscriptEvaluator.java --summary
```

Each run is graded automatically (`*.verdict.json` under `evaluation/reports/claude-trials/`, summary table at the end of a batch).

## Status and tracking

**This is the single tracking list.** Every open item is in the first table, every finished item in the second. Details and evidence live in the linked docs, but an item that is not in this section is not tracked. When an item is finished, move it to **Done** with its date and evidence; when new work is found (a bug, a gap, a follow-up), add it to **Open** with an ID.

Priority values: **Plan 1/2/3/4** (the current plan, in order) · **P1** (next after the plan) · **P2**, **P3** (later) · **Eng** (engineering work without a fixed slot).

Status values: **Not started** · **Partly done** (some of it works and is tested; the rest is listed in "Done when") · **Parked** (decided to do later) · **Deferred** (waits for demand or a feasibility review).

### Open

| ID | Priority | Item | Status | Done when | Feature IDs / details |
|---|---|---|---|---|---|
| OPEN-17 | **Plan 1** | **Unit tests as JUnit 5** run by `mvn package`, plus a `testing` module; integration tests (live Eclipse) and Claude trials stay out of the build and run only through the test runner | Partly done: step A done (unit tests in the build); step B in progress (`testing` module with the grader tests EV-U1..U18; setup utility `installer` added to the build with its tests IP-U1..U4, CD-U1..U3; waiting for build check) | `mvn clean package` fails when a unit test fails | — |
| OPEN-18 | **Plan 1** | **Test reorganization by feature:** one integration group per feature, `--feature` / `--suite` selection, old runners removed. Later in this item: integration tests rewritten as JUnit 5 test classes (still started only by `DevE2E`, never by the Maven build) | Not started | Same results before and after the move | — |
| OPEN-30 | **Plan 2** | **Debugging session report:** every step of an investigation recorded and written as an HTML report: investigation and mode, launches (project, main class or test method), breakpoints set or removed (source file, line, condition, the code on that line), stops (reason, thread, top frames, local variables with secrets masked), steps and resumes, HTTP requests and responses, exceptions, cleanup result or "ended without finish", the agent's conclusion (new optional `summary` argument of `finish_debug_investigation`), optional screenshots of the Eclipse window at stops (taken by the plugin). **Architecture rules ([ADR-009](docs/decisions.md)):** (1) *dependency direction*: modules `report-model` (events, `DebugReportListener`, no third-party dependencies) and `report-chaintest` (depends on the model); `mcp-server` uses the ChainTest writer only at runtime scope through `ServiceLoader`; (2) *runtime isolation*: a step is only queued (bounded, non-blocking), rendering runs on a background thread in the MCP server process, never in Eclipse; screenshots asynchronous with a time limit; (3) *failure isolation*: writer errors never reach the command result or the investigation lifecycle, go to stderr, and disable the writer after repeated failures; reporting `off` uses a no-op listener and is the default until R1a passes. Steps: **R1a** ChainTest prototype (ChainTest core engine driven by our own listener, no TestNG/JUnit; must show nested steps and a screenshot, nothing written to stdout, offline report, acceptable jar size with the Azure/AWS SDKs excluded); **R1** recorder and report at finish and on abandonment; **R2** agent conclusion, on/off and folder setting, `get_investigation_report` tool, skill update; **R3** optional screenshots | Not started | Unit tests for recording and rendering in the build, including the isolation tests in ADR-009 (slow writer, failing writer, missing writer, full queue); integration group `report` checks the report content of a real investigation; regression 23/23 with reporting `off` and with `chaintest` | New; fallback writer: own HTML through the same interface if R1a fails |
| OPEN-22 | **Plan 3** | **Demo sample applications** in a new top-level `sample-applications/` folder: **one plain Java application** (`java-debugging-demo`) and **one Spring Boot application** (`spring-boot-debugging-demo`). Scenarios are chosen because they are easy to show visually on screen, starting from the scenarios the test fixtures already prove (for example: a value wrong for only one record in a large loop, found with a conditional breakpoint; an exception swallowed inside a stream or lambda; a wrong discount in a REST call traced from controller to service; a request rejected with HTTP 400; a failing JUnit test). Each scenario has a symptom-only prompt, the expected root cause and a short demo script. Only scenarios that work with the current features are included. **`examples/` is not changed**: it remains the test-fixture set | Not started | Both applications import cleanly into Eclipse; every scenario is found by the agent with the expected root cause (graded automatically where possible); each application has a README with the demo steps | New; reuses the trial runner and grader (EVAL-05/06); `examples/` stays the test fixtures |
| OPEN-23 | **Plan 4** | **GitHub Copilot integration in Eclipse:** register the existing MCP server with GitHub Copilot (agent mode) in Eclipse, verify tool use and safety rules, document setup, and run the demo scenarios with Copilot | Not started | Setup documented; the OPEN-22 scenarios run with Copilot; differences from Claude Code recorded | Same MCP server; no Copilot-specific debugger code expected (to be verified) |
| OPEN-01 | P1 | **Automatic fixture synchronization:** copy changed test projects into the disposable test workspace, marker-verified, never touching other projects | Not started | New or changed fixture classes reach the test workspace without manual copying; blocks OPEN-11 and new Claude scenarios | [Runner](dev-e2e/README.md) |
| OPEN-02 | P1 | **User-resource safety matrix:** tests with real user breakpoints, exception breakpoints and custom launch configurations present; nothing of the user's is changed or removed | Partly done | Direct group passes with user markers/configurations present (so far the workspace had none, so preservation was not really exercised) | CORE-03, CORE-04, BOOT-10 |
| OPEN-03 | P1 | **Ownership checks:** port the 32 PowerShell ownership/failure checks to a Java direct-test group and rerun them on the current build | Partly done | 32/32 in Java on the current build; PowerShell copy removed | CORE-08; `e2e-runner/ownership-tests/run.ps1` |
| OPEN-04 | P1 | **Spring Boot scenario acceptance gaps:** agent chooses between two Boot apps without guessing; launch keeps profile, arguments, environment and working directory; `@SpringBootTest` method debugging | Partly done | Direct checks for each; currently discovery, readiness, HTTP quote, HTTP 400 and one Spring-context JUnit method pass | BOOT-01, BOOT-02, BOOT-06 ([scenarios](docs/spring-boot-debugging-scenarios.md)) |
| OPEN-05 | P1 | **Skill evaluation on symptom-only prompts:** vague prompts ("the total is wrong") with and without the skill; plus skill install/remove invariants and the Boot JUnit scenarios | Partly done | Repeated batches both ways on symptom-only prompts; base tests pass with no skill and after skill removal | SKILL-01..03, EVAL-03, EVAL-04; explicit-prompt comparison done (see Done) |
| OPEN-06 | P1 | **Run to Line** | Not started | Scoped operation, correct thread, cleanup; direct test | JAVA-11 |
| OPEN-07 | P1 | **Change Variable Value** with explicit consent | Not started | Owned session only, consent enforced, safe failure; direct and negative tests | JAVA-10B |
| OPEN-09 | P1 | **Claude MCP registration preservation:** installing/configuring never changes other entries in the user's Claude MCP configuration | Not started | Installed test with unrelated registrations present | CORE-09 |
| OPEN-10 | P1 | **Bridge stability follow-ups:** one comparison run in legacy mode (`AGENTDEBUG_BRIDGE_MODE=legacy`); overnight loop of 10+ regression runs | Partly done | Legacy run 23/23; overnight loop without failure | CORE-13 ([checklist](dev-e2e/README.md#bridge-stability-tests-core-13)) |
| OPEN-11 | P1 | **Stop-waiting follow-ups:** several threads stopping at the same breakpoint; Spring Boot request threads; output of a launch that already ended | Not started | Direct tests (needs a multi-thread fixture, so OPEN-01 first) | CORE-14, CORE-15 |
| OPEN-12 | P1 | **Execution-control negatives:** operations on ended, stale or invalid threads fail safely; stepping in Spring Boot and JUnit contexts | Partly done | Direct negative tests; Step Into/Out already pass for plain Java | CORE-05-06 |
| OPEN-08 | P2 | **Other MCP clients** (Codex and other compatible clients; GitHub Copilot is OPEN-23) | Not started | Each client verified before support is claimed | — |
| OPEN-24 | P2 | **Exception breakpoint package filters:** stop only for exceptions thrown in the application's own packages, not in Spring or the JDK (Spring throws and catches an `IllegalArgumentException` on every startup) | Not started | Filtered exception breakpoint ignores framework throws in a direct test | JAVA-02 follow-up |
| OPEN-25 | P2 | **JUnit test results:** pass/fail per test, assertion message, expected vs actual, failure location returned to the agent | Not started | Direct test with a failing assertion returns the structured result | JAVA-03 follow-up |
| OPEN-26 | P2 | **Launch settings:** program and VM arguments, environment variables, Spring profile, in a temporary launch configuration owned by the investigation (the user's configurations never change) | Not started | Direct test: app sees the arguments, environment and active profile; user configuration unchanged | BOOT-02 |
| OPEN-27 | P2 | **Safe local HTTP request tool for real applications:** localhost only, the user approves the application and URL, asynchronous like the test tool (today the request tool is test-only) | Not started | Safety design reviewed; direct and negative tests (non-localhost, unapproved app rejected) | BOOT-04 follow-up |
| OPEN-28 | P2 | **Spring step-filter preset:** skip proxies, CGLIB, reflection and AOP frames when stepping | Not started | Stepping through a Spring call stays in application classes in a direct test | JAVA-07 follow-up |
| OPEN-29 | P2 | **Setup utility `uninstall` can remove the plugin's registration:** it checks only its own `installation.properties`, not which jar the `eclipse-debugger` registration points to, so after the Preferences page has taken the registration over, `uninstall` removes it | Not started | `uninstall` removes the registration only when it points to the utility's own `mcp-server.jar`; unit test | [Setup utility](installer/README.md) |
| OPEN-13 | P2 | **Coverage gaps in implemented features:** bridge port collision and several IDEs (CORE-01); agent choice among ambiguous mains (CORE-02); editor focus with concurrent sessions (CORE-07); release ZIP matches source revision (CORE-10); deploy failure recovery and version changes (CORE-11); failing condition expressions (JAVA-01); tracepoint output after exit (JAVA-05) | Not started | One direct or documented manual test each | [Feature tracker](docs/feature-roadmap.md) |
| OPEN-14 | P2 | **Thread dumps, local JVM attach, method breakpoints, watchpoints** | Not started | Feasibility and safety test per API | JAVA-08, JAVA-09 |
| OPEN-15 | P2 | **Spring Tools launch modes and complex JUnit** (parameterized tests, JUnit Platform selection) | Partly done | Installed variants verified; plain Eclipse JDT/JUnit already verified | BOOT-11, JAVA-03 |
| OPEN-16 | P2 | **Linux/macOS and remaining PowerShell harnesses** | Partly done | Java runner passes on Linux/macOS; PowerShell paths replaced with parity checks | — |
| OPEN-19 | Eng | **Docs consolidation:** one roadmap file, `testing.md`, history moved to `docs/archive/` | Parked | Status maintained only here and in one roadmap | — |
| OPEN-20 | Eng | **Parallel test lanes** (several test Eclipses) | Parked | Opt-in `--parallel N` with identical results | — |
| OPEN-21 | P3 | **Remote/Docker attach, Hot Code Replace, JPA/transaction diagnostics, concurrent/async Boot requests** | Deferred | Demand, feasibility and safety review first | BOOT-05, JAVA-10A, BOOT-08, BOOT-09 |

### Done

| Item | Date | Evidence |
|---|---|---|
| **Bridge stability (CORE-13):** parallel reads, ordered state changes, per-command deadlines, `BRIDGE_TIMEOUT`/`BRIDGE_BUSY`, operation tracking, timing log; root cause of earlier timeouts fixed (console output blocking on an unread pipe). Follow-ups: OPEN-10 | 23 Nov 2026 | 3 consecutive uninterrupted 23/23 runs, `bridge` 4/4, unit 13/13 ([ADR-006](docs/decisions.md)) |
| **Event-based stop waiting (CORE-14)** and **process output head+tail (CORE-15).** Follow-ups: OPEN-11 | 23 Nov 2026 | `stops` 6/6, unit 18/18, regression 23/23 |
| **Repeated-exception dialog never blocks agents; cleanup verifies the JVM ended (CORE-16)** | 23 Nov 2026 | `stops` 6/6 with `SKIP_RECURRENCES`, validation trials 8/8 after the fix (1/6 before) ([ADR-007](docs/decisions.md)) |
| **Unit tests as JUnit 5 in the Maven build (OPEN-17 step A):** 49 unit tests run by `mvn clean package` (plugin 41 through Tycho and Surefire, mcp-server 8); a failing test fails the build; integration tests and Claude trials stay out of the build; `dev-e2e/BridgeUnitTests.java` removed | 24 Nov 2026 | `mvn clean package`: plugin 41/41, mcp-server 8/8; regression 23/23 afterwards |
| **Cleanup of abandoned investigations (CORE-17):** MCP disconnect, 10-minute idle lease, leftover breakpoints after an Eclipse restart, IDs attached automatically | 24 Nov 2026 | `lease` 5/5, unit 30/30, regression 23/23 ([ADR-008](docs/decisions.md)) |
| **Automatic Claude verdicts (EVAL-05)** from actual debugger results plus before/after Eclipse inventory | 23 Nov 2026 | Self-test 19/19; first trial graded PASS with independent cleanup |
| **Repeated trials and skill comparison on explicit prompts (EVAL-06)** | 23 Nov 2026 | With skill 9/9, without 17/18; no accuracy gain from the skill on explicit prompts ([evidence](docs/claude-acceptance-2026-11-23.md)) |
| **Single-trial Claude acceptance:** JUnit diagnosis, Boot wrong quote, Boot HTTP 400 | 23 Nov 2026 | [Acceptance evidence](docs/claude-acceptance-2026-11-23.md) |
| **Step Into / Step Out dedicated tests (CORE-05-03/04)** | 23 Nov 2026 | `acceptance:core-step-into-out` |
| **Core debugger and Phase 1 Java features:** bridge, main discovery, launch, breakpoints, stepping, variables, editor navigation, NORMAL/CONTROLLED ownership, conditional and exception breakpoints, single JUnit methods, nested variables, tracepoints, pause/restart, step filters | Nov 2026 | Regression 23/23; [capabilities](docs/capabilities.md) (CORE-01..08, JAVA-01..07) |

Release hygiene runs across all items: keep the **23/23 direct regression** and record source commit, bundle identity, environment, reports and deviations. Order may change based on real Claude trial failures; do not implement features just to match a competitor's checklist.

## Development workflow

Coding agents should begin with [AGENTS.md](AGENTS.md) and the [current-state handoff](docs/current-state.md) before changing code.

Changes follow an **evidence-first, reviewable process**: reproduce and isolate the issue; prepare a minimal, reversible change (with dry-run/backups for source migrations); compile and test the affected scenario; run broader regression; independently review Claude MCP evidence when applicable; then update verified status, documentation and repository hygiene. **Do not treat `REVIEW_REQUIRED` as a PASS or run paid Claude trials without approval.**

See the [complete development workflow](docs/development-workflow.md) and [coding-agent instructions](AGENTS.md). All agents and contributors should read these before modifying the project.

## Documentation

[Current-state handoff](docs/current-state.md) — latest verified evidence and next safe task.


[Development workflow](docs/development-workflow.md) · [Claude acceptance evidence](docs/claude-acceptance-2026-11-23.md) · [Repository hygiene](docs/repository-hygiene.md) · [Development roadmap](docs/development-roadmap.md) · [Feature tracker](docs/feature-roadmap.md) · [Agent evaluation plan](docs/evaluation-plan.md) · [Capabilities](docs/capabilities.md) · [Spring Boot scenarios](docs/spring-boot-debugging-scenarios.md) · [Java E2E runner](dev-e2e/README.md) · [Architecture decisions](docs/decisions.md) · [Coding-agent instructions](AGENTS.md)

Older phase reports in [`docs/`](docs/) preserve dated test evidence. The current README and actual test records define current status, not older planning snapshots.
