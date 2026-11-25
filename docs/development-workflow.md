# Evidence-Driven Development Workflow

[README](../README.md#development-workflow) · [Agent instructions](../AGENTS.md) · [Architecture decisions](decisions.md) · [Java E2E runner](../dev-e2e/README.md) · [Feature catalog](feature-roadmap.md)

This is the **current engineering procedure** for changing *Agentic Debugger for Eclipse IDE*. Follow it whether work is performed by a human, ChatGPT, Claude Code, Codex, or another coding agent. It governs a **change**, not a specific editor, agent, or temporary chat session. The repository and verified evidence—not prior chat summaries—are authoritative.

At the start of a new session, read the README: its first section is the current plan, and **Status and tracking** is the single list of open and finished work with its latest evidence.

## Architecture and source map

Claude Code → MCP stdio server → authenticated localhost Eclipse bridge → Eclipse JDT → debugged Java/JUnit/Spring Boot JVM.

| Directory | Responsibility |
|---|---|
| `eclipse-plugin/` | JDT execution control, bridge, investigation ownership, Agent Activity view, perspective handling, Claude Code registration (Preferences) |
| `mcp-server/` | MCP tools, investigation tracking, session report and live events (`SessionReport`, `ActivityForwarder`), test-only fixture HTTP tools |
| `report/` | Report events and isolation rules (ADR-009), shared escaping/Markdown/colours/JSON, the Agent Activity store and page (embedded in the plugin as `lib/report.jar`), the HTML report writer (the same page as a file) |
| `dev-e2e/` | `DevE2E` (prepares the test Eclipse: config, build, deploy, start, report-replay), grader launcher |
| `testing/` | Claude transcript grader and its unit tests, the Claude trial runner (`src/main`); the integration tests and Claude end-to-end tests as JUnit 5 classes (`src/test/java/.../e2e`, profile `integration-tests`, [list](integration-tests.md)); the user-resource safety tests that replaced the 32-check ownership harness are `UserResourceSafetyTests` |
| `examples/` | Intentionally buggy fixtures the tests are written against; **never changed** |
| `skills/eclipse-debugger/` | Optional agent guidance, separate from code-enforced safety |
| `docs/` | Decisions (ADRs), capabilities and SB scenarios, feature catalog, this workflow, designs |

## 1. Non-negotiable principles

1. **Evidence before modification.** Confirm the actual symptom, intended behavior, affected code, and a falsifiable diagnosis. Do not patch a guessed cause. Identify whether the defect is in the Eclipse plugin, MCP server, test fixture, Java E2E harness, deployment, permissions, or documentation.
2. **Minimum justified change.** Do not add features, refactor unrelated modules, or delete history to make a test pass. Avoid changing real business code to conceal a debugger defect. Deliberately incorrect test fixtures must remain incorrect unless the task explicitly changes the oracle.
3. **Safety is enforced in code.** The optional skill and Claude prompts are guidance, not authorization or a substitute for identity, ownership, and consent checks. Preserve user-owned breakpoints, JVMs, projects, launch configurations, and Claude's global MCP registration.
4. **Reproducible, reviewable delivery.** Prefer complete replacement files for small changes, or a small **idempotent Java** integration/migration tool for narrowly scoped edits. No unexplained hand editing, broad regex replacements, or opaque destructive scripts.
5. **Prove what actually happened.** A successful compile, installed plugin, completed Claude CLI call, correct static explanation, and debugger-observed runtime value are **different evidence levels**. Do not label `REVIEW_REQUIRED` as `PASS` without reviewing event evidence.
6. **Keep continuity in the repository.** Update current docs, status, decisions, and tests after verification; never depend on the next agent knowing this conversation.

## 2. Start-of-task checklist

1. Read `README.md`, `AGENTS.md`, this page, `docs/decisions.md`, `dev-e2e/README.md`, and the relevant capability/scenario documentation. Check `git status --short`, current branch/commit, and local diffs **before editing**. GitHub `main` may differ from the user's uncommitted working tree.
2. State the requested behavior, affected module, safety boundary, acceptance oracle, and the **smallest relevant test**. Identify whether the change requires an MCP JAR build, Eclipse plugin redeployment, or only a Java harness/documentation update.
3. Reproduce or inspect the problem. Retain command output, raw MCP tool results, relevant frames/variables, exact error, and fixture/process identity. If blocked, say what evidence is missing instead of inventing it.
4. Inspect existing APIs, fixtures, and tests for reuse. For high-impact changes, document a small reversible plan and an explicit rollback path.

## 3. Change delivery and integration contract

**Prefer a ZIP preserving repository-relative paths** when work is prepared outside the user's checkout. Include only changed/new files, not generated binaries, machine-specific `e2e.properties`, secrets, large logs, or entire unchanged directories. Explain what each file does and what, if anything, is replaced.

For changes inside existing long files, a narrow one-off Java utility may be used (not kept in the repository afterwards) that:

- Defaults to **dry run**; applies only with `--apply`.
- Checks the expected file, its exact anchor or structural preconditions, and whether the change is already installed. Refuses ambiguous or unexpected source states rather than overwriting them.
- Makes a backup in an **ignored** `evaluation/reports/...` directory before changing an existing file, keeps original encoding/line endings where feasible, and performs an atomic move when supported.
- Is **idempotent**: the second apply does not duplicate text or alter unrelated code. `git diff --check` and a targeted `git diff` must be reviewed.
- Never silently deletes files, overwrites local modifications unrelated to the change, rewrites credentials, or modifies `~/.claude.json`/other user MCP registrations.

Do **not** create a maintenance Java patcher for every simple edit. A complete, reviewable source replacement is preferable when safely scoped. One-off patchers are transitional; once the change is committed and no longer needed, assess removal explicitly rather than accumulating migration tools. Retain any still-required test harness.

## 4. Build, deploy, and test matrix

Configuration lives in **untracked** `dev-e2e/e2e.properties` (template: `dev-e2e/e2e.properties.example`). The Java runner reads repository, Eclipse installation, disposable workspace and `bridgePort`. Do not ask users to set `AGENTDEBUG_PORT` manually in CMD or to re-register MCP globally. Use a dedicated nondefault bridge (historical Windows E2E runs used `21766`).

| Change | Minimum check | Deployment required? |
|---|---|---|
| README, docs, agent instructions | Review links, claims, `git diff --check` | **No** |
| Java E2E or Claude-trial launcher only | Java compile and affected `DevE2E test` scenario | **No**, if plugin/MCP artifact unchanged |
| MCP server tool/schema/runtime | `mvn clean package`; inspect shaded MCP JAR, tool registration; affected direct and Claude scenarios | **Yes**, for integrated plugin distribution; E2E Claude launcher must load matching built JAR |
| Eclipse plugin/bridge/JDT operations | `mvn clean package`, Eclipse install, targeted installed E2E test | **Yes** |
| Fixture/oracle | Verify deliberate test defect and fixture sync; relevant direct/negative tests | Depends on imported workspace state |
| Ownership, auth, consent, HTTP reproduction | Positive **and negative** tests (wrong workspace, borrowed JVM, expired owner, unauthorized POST); ensure cleanup | As affected modules require |

**Canonical commands, run from repository root** (every group and scenario: [integration tests](integration-tests.md)):

```cmd
java dev-e2e/DevE2E.java list
java dev-e2e/DevE2E.java start
mvn -pl testing verify -Pintegration-tests -Dit.test=ProgramStartTests#rejectsUnknownJunitMethod
mvn -pl testing verify -Pintegration-tests -Dit.test=ProgramStartTests
mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true
```

To **build, deploy, and test**, first **close the dedicated Eclipse instance**; the tool intentionally refuses to overwrite a running installation or kill Eclipse:

```cmd
java dev-e2e/DevE2E.java all
```

`start` reuses or starts the verified dedicated Eclipse workspace but **does not rebuild or deploy** the plugin. If an integration test requires HTTP POST, the explicit `-Dfixture.post.approved=true` is mandatory (without it those tests are skipped); it is not blanket permission to call arbitrary services. Do not use destructive workspace resets or terminate other JVMs to clear an error. Follow `dev-e2e/README.md` for safe fixture refresh; automatic fixture synchronization is not universally available.

## 5. Test progression and failure analysis

Use a short feedback loop: **compile/static checks → smallest affected direct scenario → relevant suite → full direct regression → opt-in Claude acceptance only when needed**. The regression suite consists of seven groups named by feature (`program-start`, `breakpoints`, `execution-control`, `stops-and-variables`, `spring-boot-http`, `spring-boot-startup`, `investigation-isolation`) totaling **21 scenarios** since 25 November 2026 (23 in six differently named groups before; [integration tests](integration-tests.md)). A successful result from separate groups is not proof of one uninterrupted full-suite run.

If a test fails:

1. Record the **first failure** and the exact command, stderr, process identities, report and timestamps; later errors may be cascading.
2. Check bridge/workspace identity, launch ownership, running JVM state, readiness, fixture drift, and permission denials before blaming debugger logic.
3. If a bridge socket times out, capture Eclipse logs and a **thread dump while the stall is occurring** if possible. A later idle bridge dump cannot rule out a transient stall. Avoid speculative concurrency rewrites.
4. Run the failed scenario alone, then its group, then the full regression to distinguish an isolated defect from ordering or intermittent contention.
5. Add a deterministic regression for the proven defect where feasible; document remaining flakiness without declaring it fixed.

## 6. Claude Code agent acceptance: separate evidence gate

Opt-in trials may spend Claude usage and must **never** be triggered by an ordinary documentation or deterministic test change. Example:

```cmd
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#junitWrongDiscount -Dclaude.approved=true
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootWrongQuote -Dclaude.approved=true -Dfixture.post.approved=true
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootValidation400 -Dclaude.approved=true -Dfixture.post.approved=true
```

**Trial needed? (OPEN-39, token use).** A Claude trial costs Claude usage (about $0.19 for one boot-quote run on
Sonnet, 25 Nov 2026), so every delivery says *Trial needed: yes* or *no* with the reason:

- **Yes** when anything the agent reads or does changes: the skill (`skills/eclipse-debugger/`), tool names,
  descriptions or arguments (`DebugMcpServer`), tool replies (the plugin's JSON, `SessionReport`, `ReportingTools`),
  value text, the trial prompt or the grader's rules. One `ClaudeAgentEndToEndTests#springBootWrongQuote` run is enough for routine changes;
  repeated runs or the other scenarios only for changes to the skill's method or a new scenario.
- **No** for the report's look or layout (`FlowPage`, `ActivityPage`, CSS), the Agent Activity window, docs, the
  build, and direct-test or unit-test changes. Check the report with the last trial's recorded events instead:
  `java dev-e2e/DevE2E.java report-replay` writes `<report>.replayed.html` from the newest `*.events.jsonl` (kept by
  every trial with `-Dclaude.report=true`) and runs the same REPORT CHECK, without Eclipse and without Claude.

The trial uses a test-scoped MCP configuration, dedicated bridge, restricted tools and an explicit approved fixture POST mechanism—not arbitrary Bash or global Claude registration edits. Read `testing/src/main/java/.../testing/trial/ClaudeSymptomTrial.java` for the exact *current* implementation; this page is procedural, not a guarantee the runner has every desired grader feature.

Review the actual `.claude.jsonl` tool events plus manifest and prompt (when present), verifying: selected launch/JUnit method; begun `NORMAL` investigation; relevant breakpoints and stop on **correct target/thread**; inspected application frames and values; exact response and request ID if applicable; source-vs-runtime distinction; resume; successful `finish_debug_investigation` response; and ideally post-cleanup session/breakpoint inventory. A tool response of `completed=true` does **not** independently prove empty resource inventories. Capture permission denials, timeouts and costs if supplied. **Do not equate `exitCode=0` or `REVIEW_REQUIRED` with pass.** Classify `PASS`, `PARTIAL`, `FAIL`, `UNSAFE`, `BLOCKED`, or `NOT RUN` with the evidence limitation.

The 23 November 2026 one-off review found a successful JUnit discount diagnosis based mainly on Claude's final-result report, and event-supported Spring Boot HTTP quote and HTTP 400 validation investigations. This is **not** a statistical reliability result, a skill A/B result, or an all-features acceptance certification. The dated acceptance record is in the git history (`docs/claude-acceptance-2026-11-23.md`); current trials are graded automatically (VERDICT lines, `*.verdict.json`).

## 7. Documentation and status updates after verification

- **`README.md`:** current capabilities, simple usage, development-workflow link, accurately scoped status, next priorities. Keep it concise.
- **`docs/capabilities.md`:** implemented vs installed-tested vs supported-by-JDT vs unverified; no speculative capability claims.
- **`docs/feature-roadmap.md` (feature catalog):** feature IDs, status and evidence, EV scenarios, research; stable IDs. Order and priority live only in the README tracking table.
- **SB scenarios in `docs/capabilities.md`:** acceptance criteria and scenario coverage; do not rewrite historical entries as if old tests ran on new binaries.
- **`docs/decisions.md`:** architecture, safety, and tooling decisions with the reason and tradeoff when warranted.
- **`dev-e2e/README.md`:** exact runnable commands, configuration, test groups, interpretation and permissions.
- **`AGENTS.md` and this page:** revise only when the **working procedure** changes; avoid duplicating all command details in three places.

Finished-phase reports are not kept in `docs/` (removed 25 Nov 2026, OPEN-38); they stay in the git history. Evidence under `evaluation/reports/` is ignored/local unless intentionally curated; cite the **commit, bundle, JDK, workspace, bridge port, command and report** for release claims. Do not commit credentials, machine-specific paths or huge raw troubleshooting dumps.

## 8. Cleanup and final handoff

Before proposing a deletion, inspect references/imports and Git history; categorize each file as **active code**, **test infrastructure**, **one-time migration**, **historical evidence**, or **generated diagnostic**. `git status --short`, `git diff --check`, and `git diff --stat` are mandatory review points. Preserve unrelated user edits. A rollback path must exist for every destructive or large refactor.

At handoff, state:

- Scope and exact changed files, including newly created documents.
- Commands actually run, precise pass/fail counts, and what was **not run**.
- Eclipse/plugin/MCP runtime version and whether deployment is required.
- Evidence artifact locations, first remaining blocker, and next *single* engineering priority.
- Safe application, verification and rollback instructions; no background promises or unverifiable success claims.

### Quick new-agent entry point

Start with `AGENTS.md`, this document, and `dev-e2e/README.md`; inspect `git status` before any edit. Work from the smallest reproducible test. Keep global Claude registration, personal projects, and non-test JVMs untouched. Do not spend Claude tokens unless specifically approved. Leave the repository in a more understandable state than you found it.
