# Agentic Debugger for Eclipse IDE

Debug **Java, JUnit 5 and Spring Boot** applications in Eclipse using **Claude Code** and the **Model Context Protocol (MCP)**. The plugin uses Eclipse Java Debug Tools (JDT) for actual debugging; Claude chooses and invokes the tools. Breakpoints, stepping, runtime values and investigation safety are handled by Java code, not by prompt instructions alone.

**Status — 23 November 2026:** **23/23 direct Java end-to-end scenarios PASS in one Windows Eclipse run**, with `cleanup=true` for all 23. This establishes the specified installed debugger workflows, **not** autonomous Claude/skill reliability, all Spring Tools launch types, or cross-platform support. See [What is tested](#implementation-and-verification) and [Next priorities](#next-priorities).

## Install and use

### Supported Eclipse installation

Use **Eclipse IDE for Enterprise Java and Web Developers** (recommended for Spring Boot), or **Eclipse IDE for Java Developers**. Both are suitable when they have **JDT, Java launch/debug, JUnit and Maven (m2e)** support available. The plugin targets the **Eclipse IDE workbench**, not the **Eclipse RCP/RAP runtime alone**. The **Eclipse IDE for RCP and RAP Developers** package is not a tested configuration; it would need the Java/JDT tooling installed. **Spring Tools (STS) is optional**, and STS-specific launch delegates still require separate compatibility testing.

Requirements: **JDK 21+** and Claude Code CLI (installed/authenticated for Claude-driven use). Maven and Git are needed when building from source; **Maven/m2e** is also needed for the supplied test fixtures. The latest verified direct regression used Windows, JDK 25.0.5, Eclipse 4.41 and a dedicated workspace.

### Install the Eclipse plugin

1. Obtain an **Eclipse update-site ZIP** from [GitHub Releases](https://github.com/j2eeexpert2015/agentic-debugger-for-eclipse/releases), if a release asset exists. The repository's automatic source ZIP **cannot** be installed as an Eclipse update site.
2. Alternatively build the update site from this repository:

   ```cmd
   mvn clean package
   ```

   Use the generated `eclipse-update-site/target/agentic-debugger-update-site-*.zip` (actual name depends on Maven version).
3. In Eclipse: **Help → Install New Software → Add → Archive**, select that update-site ZIP, install **Agentic Debugger for Eclipse IDE**, then restart Eclipse.
4. Configure **Window → Preferences → Agentic Debugger → Configure Claude Code**. Keep Eclipse running, start Claude Code for a project imported into Eclipse, check `/mcp` for `eclipse-debugger`, and call `eclipse_ping` (expected `PONG`).

For complete installation troubleshooting, see [Eclipse installation and validation](docs/eclipse-installation-and-validation.md).

### Start an investigation

Ask Claude, for example: *“My order API returns 120.00 instead of 108.00. Debug the running application in Eclipse, show the incorrect runtime value, do not edit the code, and clean up your own debugging resources.”*

`NORMAL` investigations clean up their **own** breakpoints and launches when `finish_debug_investigation` executes. `CONTROLLED` investigations deliberately retain state. Neither mode authorizes changing unrelated user sessions or breakpoints. For Spring profiles, VM arguments or environment variables, configure a suitable launch in Eclipse first. The [optional debugging skill](skills/eclipse-debugger/SKILL.md) may guide Claude but is **not required** for the MCP debugger.

## Implementation and verification

**Implemented in the plugin** (scope: Java debugger via Eclipse JDT): Java main discovery and launch; ordinary, conditional and exception breakpoints; tracepoints; pause/resume/restart; Step Into/Over/Out; step filters; stack frames and variables; selected JUnit methods; Spring Boot launch and HTTP-request debugging; authenticated local bridge; investigation ownership and cleanup; Claude Code MCP registration.

**Direct installed-Eclipse tests — all passed** on 23 November 2026:

| Test group | PASS | Verified scope |
|---|---:|---|
| `phase1` | 7/7 | Java/JUnit breakpoints, variables, trace, pause/restart, filters |
| `phase2` | 2/2 | HTTP 400 validation, consent and ownership |
| `boot` | 2/2 | Boot main discovery and successful readiness |
| `boot-quote` | 1/1 | Real HTTP request; incorrect discount value observed in service |
| `acceptance` | 4/4 | Target rejection, borrowed/expired ownership, Step Into/Out |
| `boot-gaps` | 7/7 | Two mains, invalid JUnit method, two services, startup failure, timeout, port conflict, reverse cleanup |
| **Total** | **23/23** | **Every scenario reported `cleanup=true`** |

The failure-path tests observed real Spring Boot errors and confirmed fixture-JVM exit by OS PID. The combined run was executed on a **local working tree**; the supplied terminal evidence does **not** independently prove that the same source was committed, released, or tested on another machine. Reports are generated under the locally ignored `evaluation/reports/` directory. See [Java E2E runner](dev-e2e/README.md) for precise commands and scopes.

**Claude/skill evidence is a separate gate.** Historical explicit Claude/skill trials exist, but a full repeated **symptom-only** Claude assessment, automatic skill-activation reliability, and no-skill/with-skill comparison are **not established** by the 23 direct tests. The opt-in `claude` trial group currently **captures transcripts for review**, not automatic agent PASS verdicts.

### Known limits

- **Not implemented or deferred:** Run to Line, runtime variable mutation, remote JDWP attach, watchpoints, Hot Code Replace, JPA/transaction-specific diagnostics.
- **Not fully verified:** STS-specific launch modes; Linux/macOS runner; complex/parameterized and Spring-context JUnit negatives; user-owned marker/configuration isolation in every two-service permutation.
- A Boot fixture failure/exit check does not prove that Claude selected the right operation or gave a correct explanation. A direct passing scenario applies to its **named scope**, not arbitrary Java/Spring applications.

## For developers

**Architecture:** Claude Code → MCP stdio server → authenticated loopback bridge → Eclipse JDT operations → Java/JUnit/Spring Boot JVM. The optional skill only affects agent guidance.

| Area | Code location |
|---|---|
| Eclipse plugin, JDT operations, bridge, ownership | [`eclipse-plugin/src/`](eclipse-plugin/src/) |
| MCP tools and parameter contracts | [`mcp-server/src/`](mcp-server/src/) |
| Build, feature and Eclipse update site | [`pom.xml`](pom.xml), [`eclipse-feature/`](eclipse-feature/), [`eclipse-update-site/`](eclipse-update-site/) |
| Java E2E runner and verifiers | [`dev-e2e/`](dev-e2e/), [`e2e-runner/src/main/java/`](e2e-runner/src/main/java/) |
| Controlled fixtures | [`examples/`](examples/) |
| Optional Claude debugging skill | [`skills/eclipse-debugger/`](skills/eclipse-debugger/) |

### Run the installed Java regression

Configure `dev-e2e/e2e.properties` from [`e2e.properties.example`](dev-e2e/e2e.properties.example): repository root, **dedicated Eclipse installation**, **disposable test workspace**, non-default bridge port. Do not commit the local file. From the repository root:

```cmd
:: Full direct 23-scenario regression. Explicitly authorizes localhost fixture POSTs.
java dev-e2e/DevE2E.java test --approve-fixture-post

:: Selected groups or single cases
java dev-e2e/DevE2E.java test --groups acceptance,boot-gaps
java dev-e2e/DevE2E.java test --tests boot-gaps:port-conflict

:: Build, deploy and test (close only the dedicated Eclipse instance first)
java dev-e2e/DevE2E.java all --approve-fixture-post
```

`test` reuses/starts the dedicated Eclipse test instance, **but does not redeploy the plugin**. `all` builds/deploys, and must not be used on a valuable Eclipse installation. Fixtures are copied into the dedicated workspace; when repository fixture source changes, use [`SyncFailureFixtures.java`](dev-e2e/SyncFailureFixtures.java) for the two failure fixtures or refresh the disposable workspace safely. Automatic general fixture synchronization is **pending**. No Python is used by the Java E2E framework. Older PowerShell scripts remain for **specialized skill/ownership/deployment workflows**, and should not be deleted without parity testing.

**Opt-in Claude transcript trials** (not included in 23):

```cmd
java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude
java dev-e2e/DevE2E.java test --groups claude --approve-claude --approve-fixture-post
```

Requires configured Claude CLI/MCP and may consume usage. Review transcripts, actual tool invocations, observed runtime evidence, diagnosis and cleanup. CLI completion is **not** an agent debugging PASS.

## Next priorities

**P0 — Engineering reliability and baseline sign-off**

1. Record the **tested Git commit/working-tree diff**, installed plugin bundle, Java/Eclipse versions and 23 JSON reports. Rerun the complete regression on a clean build before claiming a release.
2. Fix fixture update handling in `DevE2E.java` with marker-verified, safe synchronization of the *dedicated* workspace (the current workflow required manual recovery after fixture changes).
3. Extend ownership protection beyond fixture JVMs to **user-owned breakpoints, saved launch configurations, ambiguous targets, and concurrent sessions**; preserve negative-case evidence.

**P1 — Agent-level acceptance and compatibility**

4. Run/review the **three symptom-only Claude trials**: wrong HTTP quote, failing JUnit method, handled HTTP 400. Record correct target/tool selection, actual breakpoint evidence, diagnosis, consent and cleanup; repeat before reporting rates.
5. Test explicit/automatic activation of the optional skill and **no-skill versus with-skill** outcomes; keep the debugger independent of skill success.
6. Verify selected `@SpringBootTest` failure/context-negative cases and STS-specific launch behavior on an actual Spring Tools installation.

**P2 — Platform and tooling consolidation**

7. Verify Linux/macOS and migrate PowerShell-only skill/ownership/deployment coverage to Java **only after equivalent tests pass**. Preserve existing safety checks.

**Deferred:** advanced JDWP/watchpoint/Hot Code Replace/variable editing capabilities until product demand and a working Eclipse API spike justify them.

## Documentation

Current entry points: [Java E2E guide](dev-e2e/README.md) · [Capabilities and acceptance details](docs/capabilities.md) · [Development roadmap / stable feature IDs](docs/development-roadmap.md) · [Feature tracker](docs/feature-roadmap.md) · [Spring Boot acceptance scenarios](docs/spring-boot-debugging-scenarios.md) · [Evaluation plan](docs/evaluation-plan.md) · [Architecture decisions](docs/decisions.md).

Older phase-specific results and tool audits remain under [`docs/`](docs/) **as dated evidence**, not the latest project status. When documents disagree on current coverage, use this README plus the actual dated test reports, not an older planning snapshot.
