# Agentic Debugger for Eclipse IDE

Debug **Java, JUnit 5, and Spring Boot** applications in Eclipse with **Claude Code** through the Model Context Protocol (MCP). The plugin uses Eclipse Java Debug Tools (JDT) for actual execution control; Claude selects and invokes MCP tools. Safety checks for session ownership, breakpoints and cleanup are implemented in Java, not left solely to prompt instructions.

**Evidence snapshot — 23 November 2026:** A historical single-run **23/23 direct Java E2E PASS** baseline exists. Following the fixture HTTP MCP change, the first 16 direct cases passed in a combined run, the Boot-gap group later passed **7/7** separately, but a fresh uninterrupted 23/23 pass on that exact updated build remains pending. Three single-session Claude investigations were reviewed (JUnit diagnosis; Boot quote and HTTP 400 event-level runtime PASS). See [acceptance evidence](docs/claude-acceptance-2026-11-23.md); do not infer repeated reliability or skill A/B success.

## Current milestone and next development priority

**P0 single-trial symptom-driven acceptance is demonstrated** for the three implemented scenarios: one JUnit investigation (reported diagnostic evidence) and two Spring Boot HTTP investigations (event-level MCP evidence). See [Claude acceptance results, evidence boundaries and exact commands](docs/claude-acceptance-2026-11-23.md). The optional skill's activation, repeated no-skill reliability and with-skill A/B benefit have **not** been established.

**Next P0 gate:** automate evidence-backed verdicts and independent post-cleanup checks; rerun the updated 23-case suite together. Then prioritize safe fixture synchronization, event-correlated pause waiting and user-owned resource isolation. Do not add debugger features merely for checklist parity.
## Install and use

### Which Eclipse IDE?

Use **Eclipse IDE for Enterprise Java and Web Developers** (recommended for Spring Boot) or **Eclipse IDE for Java Developers**, with **Java/JDT debugging, JUnit and Maven/m2e** available. This is an **Eclipse IDE workbench plugin**, **not a standalone Eclipse RCP/RAP runtime application**. The Eclipse IDE for RCP and RAP Developers package has not been independently tested; it would need the Java tooling installed. **Spring Tools is optional**, and its specialized launch modes remain unverified.

Requirements: **JDK 21+** and authenticated Claude Code for Claude-driven use. Building from source requires Maven and Git. The 23-scenario direct regression ran on Windows, Eclipse 4.41 and JDK 25.0.5; no Python is required by the Java E2E framework.

### Install the plugin

1. Obtain an Eclipse **update-site ZIP** from [GitHub Releases](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases) if available, or run `mvn clean package` and use the generated `eclipse-update-site/target/agentic-debugger-update-site-*.zip`. A GitHub source ZIP is **not** an installable Eclipse update site.
2. In Eclipse use **Help → Install New Software → Add → Archive**, select the update-site ZIP, install **Agentic Debugger for Eclipse IDE**, and restart.
3. Use **Window → Preferences → Agentic Debugger** to configure Claude Code. With Eclipse running and the project imported, check Claude's `/mcp` for `eclipse-debugger` and call `eclipse_ping` (expected `PONG`).

See [installation and validation](docs/eclipse-installation-and-validation.md) for troubleshooting.

### Ask Claude to investigate

Example symptom-only request: *“My orders API returns 120.00, but I expect 108.00. Find the cause. Do not modify source or unrelated running applications.”*

`NORMAL` investigations remove their **own** debugging resources when properly finished; `CONTROLLED` investigations may retain state. Neither authorizes changing user-owned breakpoints or unrelated launches. The [optional skill](skills/eclipse-debugger/SKILL.md) is not required for core MCP debugging.

## Implementation and test status

**Implemented:** Java main discovery and launch; line, conditional and exception breakpoints; tracepoints; Step Into/Over/Out; pause/resume/restart; step filters; thread/stack/variable inspection; selected JUnit tests; Boot launch and HTTP request investigation; authenticated MCP bridge; investigation ownership and cleanup. `wait_for_debug_pause` exists but **polls**, rather than correlating the precise debugger stop event.

**Historical uninterrupted Windows direct-test baseline (23 November 2026):**

| Suite | PASS | Scope |
|---|---:|---|
| `phase1` | 7/7 | Java/JUnit breakpoints, variables, stepping-related controls, trace and filters |
| `phase2` | 2/2 | Boot validation HTTP 400, safety and ownership |
| `boot` | 2/2 | Boot discovery and readiness |
| `boot-quote` | 1/1 | Real HTTP quote investigation and wrong discount value |
| `acceptance` | 4/4 | Target rejection, ownership checks and Step Into/Out |
| `boot-gaps` | 7/7 | Two Boot mains/services, failed startup, bounded timeout, port conflict, isolated cleanup, invalid JUnit method |
| **Total** | **23/23** | **All reported `cleanup=true`** |

The failing-startup and port-conflict tests confirmed fixture JVM exit using OS PIDs. This is **local working-tree evidence**; it does not independently certify a published release or other systems. Report files are generated under ignored `evaluation/reports/`. See [Java runner](dev-e2e/README.md).

**Agent behavior (23 November 2026):** The JUnit single-method diagnosis was supported by Claude's final-result record; the Spring Boot wrong-quote and invalid-quantity runs have reviewed MCP event-level proof of HTTP reproduction, runtime debugging and cleanup responses. **Three single trials are not a reliability study.** Skill activation, repeated no-skill trials, controlled no-skill/with-skill comparison, and independent post-cleanup inventories remain open. The harness intentionally labels captures `REVIEW_REQUIRED`; see [evidence ledger](docs/claude-acceptance-2026-11-23.md).

**Limits:** Run to Line, variable mutation, field watchpoints, JDWP attach, thread-dump export and Hot Code Replace are not exposed as verified MCP features. STS-specific modes, Linux/macOS runner execution, more complex Spring-context JUnit cases and comprehensive user-marker/configuration isolation require separate tests.

## Developer quickstart

Architecture: **Claude Code → MCP stdio server → authenticated localhost bridge → Eclipse JDT → Java/JUnit/Spring Boot JVM**. Core locations: [`eclipse-plugin/`](eclipse-plugin/), [`mcp-server/`](mcp-server/), [`dev-e2e/`](dev-e2e/), [`e2e-runner/`](e2e-runner/), [`examples/`](examples/), [`skills/eclipse-debugger/`](skills/eclipse-debugger/).

Configure the untracked `dev-e2e/e2e.properties` from [the example](dev-e2e/e2e.properties.example), using a **dedicated Eclipse installation**, **disposable workspace**, and **nondefault bridge port**. From the repository root:

```cmd
:: Installed direct 23-scenario regression, with approved local fixture POSTs
java dev-e2e/DevE2E.java test --approve-fixture-post

:: One test, or a selected group
java dev-e2e/DevE2E.java test --tests boot-gaps:startup-failure
java dev-e2e/DevE2E.java test --groups acceptance,boot-gaps

:: Build, deploy and test (dedicated Eclipse instance must be closed)
java dev-e2e/DevE2E.java all --approve-fixture-post

:: Bridge stability (CORE-13): unit tests (no Eclipse needed), then fault injection on the dedicated Eclipse
java dev-e2e/BridgeUnitTests.java
java dev-e2e/DevE2E.java test --groups bridge
```

**First agent baseline trial (requires Claude CLI/MCP, may consume usage):**

```cmd
java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude
```

For the current three-trial group:

```cmd
java dev-e2e/DevE2E.java test --groups claude --approve-claude --approve-fixture-post
```

Review generated transcripts under `evaluation/reports/claude-trials/`; they are not automatic PASS reports. `test` reuses or starts the dedicated Eclipse instance but does not redeploy the plugin. General automatic fixture synchronization remains pending. Older PowerShell scripts still support specialized evaluation/deployment paths and must not be deleted without parity checks.

## Prioritized development order

| Order | Priority | Work and acceptance outcome |
|---|---|---|
| **1** | **P0** | **Bridge stability (CORE-13):** parallel read-only commands, ordered state changes, per-command deadlines with explicit `BRIDGE_TIMEOUT`/`BRIDGE_BUSY`, tracked operations (`get_bridge_operation`), timing and slow-command log; `AGENTDEBUG_BRIDGE_MODE=legacy` fallback. **Implemented in source; installed acceptance pending:** 3 consecutive uninterrupted 23/23 runs, legacy-mode parity and ownership checks ([checklist](dev-e2e/README.md#bridge-stability-tests-core-13)) |
| 2 | P0 | **Automated Claude acceptance verdicts and independent cleanup evidence:** preserve event-level MCP observations; revalidate full combined regression; repeat controlled no-skill trials (initial JUnit/Boot smoke completed) |
| 3 | P0 | **Automatic fixture synchronization:** marker-verified disposable workspace; no manual source copying or destructive project changes |
| 4 | P0 | **Event-correlated pause waiting:** exact launch/thread/stop cause; no stale or unrelated stop accepted |
| 5 | P0 | **User-resource safety:** preserve user-owned breakpoints, launch configurations and unrelated JVMs under concurrent debugging |
| 6 | P1 | **Run to Line:** scoped operation, correct thread and cleanup |
| 7 | P1 | **Change Variable Value:** explicit consent, owned session and safe failure behavior |
| 8 | P1 | **Claude regression and skill evaluation:** compare without versus with skill after reliable no-skill baseline |
| 9 | P1 | **Multiple MCP clients:** verify Codex and compatible clients before promising support |
| 10 | P2 | **Thread dumps, local JVM attach, method breakpoints/watchpoints:** individual API feasibility and safety tests |
| 11 | P2 | **Spring Tools and extended JUnit compatibility:** verify real installed variants |
| 12 | P2 | **Cross-platform and remaining PowerShell parity:** preserve historical test coverage |
| 13 | P3 | **Remote/Docker attach and Hot Code Replace:** defer pending demand, feasibility and safety review |

Release hygiene runs across these priorities: retain the **23/23 direct regression** and record source commit, bundle identity, environment, reports and deviations. The ordering may change based on actual Claude trial failures; do not implement features just to match a competitor's checklist.

## Development workflow

Coding agents should begin with [AGENTS.md](AGENTS.md) and the [current-state handoff](docs/current-state.md) before changing code.

Changes follow an **evidence-first, reviewable process**: reproduce and isolate the issue; prepare a minimal, reversible change (with dry-run/backups for source migrations); compile and test the affected scenario; run broader regression; independently review Claude MCP evidence when applicable; then update verified status, documentation and repository hygiene. **Do not treat `REVIEW_REQUIRED` as a PASS or run paid Claude trials without approval.**

See the [complete development workflow](docs/development-workflow.md) and [coding-agent instructions](AGENTS.md). All agents and contributors should read these before modifying the project.

## Documentation

[Current-state handoff](docs/current-state.md) — latest verified evidence and next safe task.


[Development workflow](docs/development-workflow.md) · [Claude acceptance evidence](docs/claude-acceptance-2026-11-23.md) · [Repository hygiene](docs/repository-hygiene.md) · [Development roadmap](docs/development-roadmap.md) · [Feature tracker](docs/feature-roadmap.md) · [Agent evaluation plan](docs/evaluation-plan.md) · [Capabilities](docs/capabilities.md) · [Spring Boot scenarios](docs/spring-boot-debugging-scenarios.md) · [Java E2E runner](dev-e2e/README.md) · [Architecture decisions](docs/decisions.md) · [Coding-agent instructions](AGENTS.md)

Older phase reports in [`docs/`](docs/) preserve dated test evidence. The current README and actual test records define current status, not older planning snapshots.
