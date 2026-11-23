# Agent instructions — Agentic Debugger for Eclipse IDE

Applies to this repository and to **any** coding assistant operating on it. Read [the development workflow](docs/development-workflow.md) before source changes; use [the architecture decisions](docs/decisions.md) and [Java E2E guide](dev-e2e/README.md) for detailed contracts. The repository's current files and tests override earlier chat summaries or old milestone reports.

## New-agent orientation: reading order

Before changing code, check the current branch/revision and read the relevant documents. Current evidence outranks historical milestone reports.

| Read | Purpose |
|---|---|
| [README](README.md) | Product, architecture and quickstart |
| [Current state](docs/current-state.md) | Latest evidence, blocker and next safe action |
| [Capabilities](docs/capabilities.md) | What is implemented versus verified |
| [Feature roadmap](docs/feature-roadmap.md) | Open capability gaps |
| [Development workflow](docs/development-workflow.md) | Investigation, changes, testing and handoff |
| [Architecture decisions](docs/decisions.md) | Safety and ownership contracts |
| [Java E2E](dev-e2e/README.md) | Test commands, environment and approvals |
| [Claude evidence](docs/claude-acceptance-2026-11-23.md) | Reviewed single-trial observations and limits |
| [Evaluation plan](docs/evaluation-plan.md) | Agent scoring and experiments |
| [Development roadmap](docs/development-roadmap.md) | Prioritized next actions |

Read task-relevant files, not every historical report. Raw evaluation/reports/ is ignored by Git; never invent missing evidence or run paid Claude trials without approval.

## Before changing anything

- Inspect `git status --short` and the current branch/commit. Respect uncommitted user edits. Understand the reproducer, acceptance oracle, minimal change, and rollback plan.
- Separate **MCP server**, **Eclipse plugin/bridge**, **Java E2E framework**, **deliberately broken fixture**, and **documentation** failures. Do not patch by speculation.
- Keep the default developer experience Java-centric. No ad hoc Python or PowerShell dependency for the canonical Java E2E path.

## Implementation and safety

- Preserve personal Eclipse projects, user-owned breakpoints/JVMs/configurations, and unrelated Claude MCP registrations. NEVER silently modify `~/.claude.json`, delete a workspace, kill a user JVM, or enable permission bypass.
- Dedicated tests use untracked `dev-e2e/e2e.properties`, a disposable Eclipse workspace, a nondefault bridge port, and authentication. Do not ask the user to manually `set AGENTDEBUG_PORT` in CMD.
- Fixture POSTs require explicit `--approve-fixture-post`; Claude trials require explicit `--approve-claude`. The HTTP MCP reproduction tools are E2E-only and must verify fixture/launch ownership. Keep fixture bugs intentional for the debugger oracle.
- Prefer small complete source files or a narrowly anchored, dry-run-first, idempotent Java patcher with backups under ignored `evaluation/reports/`. Never replace whole docs when a small insertion suffices. No automatic deletions.

## Validation order

1. Compile/check changes and run the **smallest** relevant direct scenario.
2. Run its direct test group. For changed runtime behavior, finish with the **23-scenario direct suite** when possible.
3. If plugin/MCP runtime changed, close the dedicated Eclipse instance before `java dev-e2e/DevE2E.java all --approve-fixture-post`. `test` does not deploy changes.
4. Run Claude trials only when approved and useful. Examine `.claude.jsonl` events and cleanup results; `REVIEW_REQUIRED` or CLI exit code zero is **not** automatic PASS.
5. Record independently what passed, failed, was blocked, or was not run. Do not hide flaky full-suite failures behind separately passing tests.

From repository root:

```cmd
java dev-e2e/DevE2E.java list
java dev-e2e/DevE2E.java test --tests boot-gaps:junit-invalid-method
java dev-e2e/DevE2E.java test --approve-fixture-post
```

## Handoff and documentation

Update README status and links, capabilities, roadmaps, evaluation/scenario docs, and architecture decisions only as the evidence warrants. Keep historical reports and still-needed specialized scripts. Run `git diff --check` and inspect the diff. Provide exact files/commands, test outcomes, remaining gaps, deployment/rollback needs and next priority. See [full workflow](docs/development-workflow.md).
