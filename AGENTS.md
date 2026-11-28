# Agent instructions — Agentic Debugger for Eclipse IDE

Applies to this repository and to **any** coding assistant operating on it. Read [the development workflow](docs/development-workflow.md) before source changes; use [the architecture decisions](docs/decisions.md) and [Java E2E guide](dev-e2e/README.md) for detailed contracts. The repository's current files and tests override earlier chat summaries or old milestone reports.

## New-agent orientation: reading order

Before changing code, check the current branch/revision and read the relevant documents. Current evidence outranks
anything in the git history.

| Read | Purpose |
|---|---|
| [README](README.md) | Product: install and use |
| [STATUS.md](STATUS.md) | Current plan, and **Status and tracking**: the single list of open and finished work with its latest evidence |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build, tests, development workflow, documentation index |
| [Development workflow](docs/development-workflow.md) | Architecture and source map; investigation, changes, testing and handoff |
| [Architecture decisions](docs/decisions.md) | Safety and ownership contracts (ADRs) |
| [Capabilities](docs/capabilities.md) | What is implemented versus verified; Spring Boot scenarios SB-01 to SB-10 |
| [Feature catalog](docs/feature-roadmap.md) | Feature IDs and evidence; agent behaviour scenarios EV-01 to EV-16; research |
| [Java E2E](dev-e2e/README.md) | Test commands, environment, approvals, setup checks |
| [Designs](docs/design/README.md) | Agent Activity view and report designs |

Read task-relevant files only. Raw `evaluation/reports/` is ignored by Git; never invent missing evidence or run paid
Claude trials without approval. Dated phase reports were removed from `docs/` on 25 Nov 2026 (OPEN-38); use the git
history if one is needed.

## Before changing anything

- Inspect `git status --short` and the current branch/commit. Respect uncommitted user edits. Understand the reproducer, acceptance oracle, minimal change, and rollback plan.
- Separate **MCP server**, **Eclipse plugin/bridge**, **Java E2E framework**, **deliberately broken fixture**, and **documentation** failures. Do not patch by speculation.
- Keep the default developer experience Java-centric. No ad hoc Python or PowerShell dependency for the canonical Java E2E path.

## Implementation and safety

- Preserve personal Eclipse projects, user-owned breakpoints/JVMs/configurations, and unrelated Claude MCP registrations. NEVER silently modify `~/.claude.json`, delete a workspace, kill a user JVM, or enable permission bypass.
- Dedicated tests use untracked `dev-e2e/e2e.properties`, a disposable Eclipse workspace, a nondefault bridge port, and authentication. Do not ask the user to manually `set AGENTDEBUG_PORT` in CMD.
- Fixture POSTs require explicit `-Dfixture.post.approved=true`; Claude end-to-end tests require explicit `-Dclaude.approved=true`. The HTTP MCP reproduction tools are E2E-only and must verify fixture/launch ownership. Keep fixture bugs intentional for the debugger oracle.
- Prefer small complete source files. Never replace whole docs when a small insertion suffices. No automatic deletions.

## Validation order

1. Compile/check changes and run the **smallest** relevant direct scenario.
2. Run its test class. For changed runtime behavior, finish with the **regression suite** (`RegressionSuite`, 21 tests) when possible.
3. If plugin/MCP runtime changed, close the dedicated Eclipse instance before `java dev-e2e/DevE2E.java all` (or `deploy` after `mvn clean package`); `start` does not deploy changes.
4. Run Claude trials only when approved and useful. Examine `.claude.jsonl` events and cleanup results; `REVIEW_REQUIRED` or CLI exit code zero is **not** automatic PASS.
5. Record independently what passed, failed, was blocked, or was not run. Do not hide flaky full-suite failures behind separately passing tests.

From repository root:

```cmd
java dev-e2e/DevE2E.java start
mvn -pl testing verify -Pintegration-tests -Dit.test=ProgramStartTests#rejectsUnknownJunitMethod
mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true
```

## Handoff and documentation

Update the tracking table (STATUS.md), capabilities, the feature catalog and architecture decisions only as the evidence warrants. Run `git diff --check` and inspect the diff. Provide exact files/commands, test outcomes, remaining gaps, deployment/rollback needs and next priority. See [full workflow](docs/development-workflow.md).
