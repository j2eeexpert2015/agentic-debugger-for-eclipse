# Codebase reduction analysis — 25 November 2026

What the product does, which files do it, and what can go, be merged or be simplified. Nothing is changed by this
document; every step below is a separate, verified delivery. Counts exclude `target/`, `.git/` and `examples/`
(examples stay unchanged by rule).

## 1. Where the size is

| Area | Files | Lines | Comment |
|---|---:|---:|---|
| Product code (plugin, MCP server, report modules, installer) | 56 | 10,600 | the product; reasonably sized for what it does |
| Unit tests | 21 | 3,100 | keep; follow the code they test |
| Test tooling (`e2e-runner`, `dev-e2e`, `testing`, `scripts`, `evaluation`) | 44 | 7,900 | almost as big as the product; several generations side by side |
| One-off and leftover files (`maintenance/`, `APPLY*.md`, log dumps, disabled CI, `RUN-WINDOWS-TESTS.cmd`) | 14 | 1,500 | no longer used |
| Documentation (`docs/`, root `*.md`, module READMEs, skill) | 35 | 3,700 | 21 files in `docs/`; many describe finished phases |
| Maven modules (`pom.xml`) | 13 | | 3 of them for one report |
| **Total** | **189** | | |

**Result (25 Nov, after step 6):** 125 files (same counting), 7 Maven modules instead of 13, about 21,500 lines of Java, Markdown, XML and scripts; all six steps checked by the build, the regression 23/23 and a Claude trial with the report.

The product itself is not the problem. The weight is in **test tooling built in several generations** (PowerShell,
then batch, then Java runners, then `DevE2E`), **one-off migration scripts that stayed**, **documentation per phase**,
and **the same small helpers written again in each module**.

## 2. What the product does, and which files do it

| Functionality | Files today | Verdict |
|---|---|---|
| Debugger operations in Eclipse (breakpoints, launch, step, stack, variables, exceptions, JUnit, Spring Boot) | `debug/*` (8 files, 2,300 lines; `EclipseAgentOperations` 1,150) | keep |
| Bridge (authenticated socket, READ/MUTATE lanes) | `bridge/*` (4 files) | keep; remove the old single-threaded "legacy" mode (D3) |
| Claude Code registration (Preferences page, jar install) | `integration/*` (3 files) **and** the whole `installer` module (5 classes) | two implementations of the same thing (C3) |
| MCP server (tools, investigation tracking) | `DebugMcpServer`, `InvestigationTracker` (and `MiniJson`, replaced by the shared `Json` in step 3) | keep |
| Trial-only HTTP tools for the Orders fixture | `FixtureHttpTools` (in the shipped MCP server) | keep, but switch off unless a test asks (D2) |
| Session report (HTML file) | `report-model` (11 classes), `report-format`, `report-chaintest` (3 modules) | over-built for one writer (C4, C5) |
| Agent Activity view | `activity/*`, `ui/*` (new) | keep |
| Perspective and editor following | `AgentDebugUiCoordinator`, `PerspectivePolicy` | keep |
| Development fixture and smoke project inside the plugin | `DevelopmentFixture`, `SmokeTestProject` | test support in the product (D2) |
| Test runs | `dev-e2e/DevE2E` + 10 `e2e-runner` verifiers + grader in `testing` + `ClaudeSymptomTrial` | keep `DevE2E`; consolidate the rest (C2, C6) |
| Skill | `skills/eclipse-debugger` (6 files) | keep |

## 3. Findings

### A. Delete: nothing in the build, the tests or `DevE2E` uses these (35 files, about 3,000 lines)

Not deleted after a closer look: `e2e-runner/ownership-tests/` is the 32-check ownership harness of OPEN-03 (seeds real user breakpoints and sessions inside Eclipse), started by its own `run.ps1`; it moves into the one e2e runner in step 6.

| What | Why it can go |
|---|---|
| `maintenance/` (6 Java files) | one-off scripts that installed earlier workflows, roadmaps and cleanups; already applied |
| `APPLY.md`, `APPLY-AGENT-HANDOFF.md`, `APPLY-DEVELOPMENT-WORKFLOW.md` | instructions for those one-off scripts |
| `eclipse-bridge-threads.txt` (40 KB), `eclipse-errors.txt` (13 KB) | thread and error dumps from a past investigation, kept in the repository root |
| `dev-e2e/UpdateRoadmapDocs.java`, `dev-e2e/SyncFailureFixtures.java` | one-off; the second copies two test files the `examples` project already has |
| `e2e-runner/boot-tests/fixtures/` (2 files) | copies of `examples/spring-boot-debugging` tests |
| `e2e-runner/.../ClaudeE2ERunner.java` (428 lines) and `e2e-runner/claude-prompt.txt` | the old Claude runner; `DevE2E` uses `ClaudeSymptomTrial` |
| `scripts/windows/` (13 batch and PowerShell files) and `RUN-WINDOWS-TESTS.cmd` | the generation before `DevE2E` (deploy, doctor, skill tests, phase 1 and 2 checks); `test-scoring.ps1` is used by nothing at all |
| `evaluation/skill-automation/scenarios.json` | used only by `scripts/windows/test-skill.ps1` |
| `.github/workflows/*.disabled` and `scripts/VerifyUpdateSite.java` | CI switched off; the Java file is used only by it |
| `DevE2E` modes `integrate` and `cleanup` (about 95 lines) | one-off migrations that still run on every `all` and `deploy` and can change files |

Git keeps all of it; nothing is lost.

### B. Documentation: 21 files in `docs/` down to 5 (done in step 2)

The README is the single tracking list by rule, yet `development-roadmap.md`, `codex-implementation-backlog.md` and
`current-state.md` tracked work too, and the `phase1-*`, `phase2-*`, `phase3-*` files described finished phases.

| Kept | Now also holds |
|---|---|
| `README.md` | install troubleshooting (from `eclipse-installation-and-validation.md`); a documentation table |
| `docs/decisions.md` | unchanged (ADRs) |
| `docs/capabilities.md` | the Spring Boot scenarios SB-01 to SB-10 and their acceptance matrix (from `spring-boot-debugging-scenarios.md`) |
| `docs/feature-roadmap.md`, now titled "Feature catalog" (file name kept, many links point to it) | the agent behaviour scenarios EV-01 to EV-16 (from `evaluation-plan.md`) and the external research RS-01 to RS-07 (from `research-sources.md`) |
| `docs/development-workflow.md` | the architecture and source map (from `current-state.md`), updated |
| `docs/design/` (3 files), `docs/codebase-reduction-analysis.md` | unchanged |
| `dev-e2e/README.md` | stays the test guide (instead of a new `docs/testing.md`: it sits next to the tool) |
| `AGENTS.md` | kept but shortened: coding agents (Codex, Claude Code) read this file by convention |

Deleted (18 files; history in git): the `phase1-*` (3), `phase2-*` (1) and `phase3-*` (2) files,
`claude-acceptance-2026-11-23.md`, `repository-hygiene.md`, `codex-implementation-backlog.md`, `current-state.md`,
`development-roadmap.md`, `evaluation-plan.md`, `spring-boot-debugging-scenarios.md`, `spring-boot-tool-audit.md`,
`research-sources.md`, `windows-test-quickstart.md`, `eclipse-installation-and-validation.md` and the root
`README-INSTALL-AND-TEST.md`. `examples/README.md` still links two of them; it stays unchanged by rule.

### C. Merge duplicates

1. **Six JSON readers.** `MiniJson` (MCP server), `ActivityJson` (plugin), and readers inside `PhaseOneBridgeVerifier`,
   `InvestigationLeaseVerifier`, `ClaudeTranscriptEvaluator` and `FixtureHttpTools`. One small `Json` class in the shared
   module (`report-format`, which the plugin already embeds) is enough. About 400 lines less.
2. **Five bridge clients** (socket, token, `AUTH <token> <command>`): `DebugMcpServer`, `PhaseOneBridgeVerifier`,
   `BridgeFaultVerifier`, `JavaFixtureQuoteClient`, `DevE2E`. One `BridgeClient` for the test tools and one in the MCP
   server.
3. **Claude Code registration twice.** The plugin's Preferences page (`ClaudeIntegration`) and the `installer` module do
   the same `claude mcp get/add/remove`. Either drop the `installer` module (9 files, 620 lines, one `pom.xml`) and
   document the one manual `claude mcp add` command, or let both use one class. **Decision needed.**
4. **Report: three modules for one writer.** `report-model` (events, queue, isolation), `report-format` (escaping,
   Markdown, colours) and `report-chaintest` (the writer). Merge `report-model` and `report-format` into one `report`
   module, and remove the plug-in mechanism built for several writers (`ReportWriterProvider`, `ChainTestWriterProvider`,
   `ReportRecorders`, the `META-INF/services` file): there is one writer. One `pom.xml` and about 5 classes less.
5. **Report: replace ChainTest with our own page (largest single gain).** Most of `ChainTestReportWriter` (500 lines)
   works around ChainTest: hiding its branding, re-ordering its cards, inlining its CSS, script and font, post-processing
   its HTML. The Agent Activity page already renders the same investigation, in the style you chose, from the same
   events. One renderer for both, written to a file at the finish, would:
   remove the `report-chaintest` module and the ChainTest dependency (and what it pulls into the MCP server jar),
   make the report and the view look the same, and give the report Expand All and Collapse All, the colours and the
   time order for free. About 800 lines out, 150 in. **Decision needed** (the report would look like the view).
6. **Ten test programs with the same helpers.** The `e2e-runner` verifiers each have their own `main`, argument parsing,
   `require`, `str`, `number` and report writing. One runner with the scenarios as classes and one helper class; the
   transcript grader moves from `testing` into the same module (one `pom.xml` less, and the `dev-e2e` launcher that
   compiles it on the fly goes). Medium effort, done last.

### D. Simplify the product

1. **`EclipseAgentOperations` (1,150 lines)** does launching, breakpoints, stepping, inspection, JUnit and cleanup in one
   class. Split by topic only if it keeps growing; it is not dead weight.
2. **Test support shipped in the product.** `FixtureHttpTools` (MCP tools for the Orders fixture), `DevelopmentFixture`,
   `SmokeTestProject`, and the bridge commands `GET_E2E_INFO` and `VERIFY_OWNED_FIXTURE_LAUNCH` are for the tests. Keep
   them (the trials need them), but switch them on only when a test asks (an environment variable set by `DevE2E`), so a
   normal user's agent never sees fixture tools.
3. **The "legacy" bridge mode** (`AGENTDEBUG_BRIDGE_MODE=legacy`) keeps the old single-threaded bridge next to the
   current one. The concurrent bridge has passed every regression since; remove the second path.

## 4. Target shape

| | Today | After A to D |
|---|---:|---:|
| Files (without `examples/`) | 189 | about 115 |
| Maven modules | 13 | 9 or 8 (report merged, `report-chaintest` and `testing` gone, `installer` optional) |
| Documentation files in `docs/` | 21 | about 6 |
| Test tooling lines | 7,900 | about 5,000 |
| Product lines | 10,600 | about 9,800 (ChainTest workarounds, duplicate JSON, legacy bridge) |

## 5. Order of work

Each step is one delivery with the usual checks: `mvn clean package`, regression 23/23, one Claude trial with
`--report`.

| Step | What | Risk |
|---|---|---|
| 1 | Delete the unused files of A, and the `integrate`/`cleanup` modes of `DevE2E` (done 25 Nov: build, regression 23/23 and trial PASS) | none: nothing uses them |
| 2 | Documentation down to 5 files in `docs/` plus the designs (B; done 25 Nov) | none for the code |
| 3 | One JSON reader in the product (C1) and no legacy bridge (D3) (done 25 Nov: build, regression 23/23 and three trials PASS); the test tools' JSON readers and bridge clients (C2) move to step 6, because `DevE2E` compiles those tools without the project's jars | low |
| 4 | Report: merge modules, remove the plug-in mechanism, our own HTML writer (C4, C5; done 25 Nov: one module `report`, the report is the Agent Activity page as a file, ChainTest and `report-chaintest` gone) | medium; checked by the report trial |
| 5 | Test support switched off unless a test asks (D2); installer decision (C3) (done 25 Nov: `installer` removed; `GET_E2E_INFO` and `VERIFY_OWNED_FIXTURE_LAUNCH` only with `AGENTDEBUG_E2E=1`; unused `PREPARE_SMOKE_TEST` and `SmokeTestProject` removed) | low |
| 6 | One e2e runner, grader moved (C6) (done 25 Nov, reduced: `e2e-runner` merged into `testing`, which already held the grader; ownership harness moved there and its launcher repaired; the scenario-class runner and one helper class dropped: the verifiers already share the bridge client and JSON reader of `PhaseOneBridgeVerifier`, the rest are one-line helpers, and a rewrite only the live regression can check costs more than it saves) | medium; checked by the full regression |

## 6. Decisions needed

1. Replace ChainTest with our own page, so the report looks like the Agent Activity view? (C5) **Decided 25 Nov: yes** (step 4).
2. Keep the `installer` command-line setup, or rely on the Preferences page and one documented command? (C3) **Decided 25 Nov: drop it** (step 5).
3. Delete finished-phase documents outright (history stays in git), or keep them in one `docs/history.md`? (B) **Decided: deleted** (step 2).
