# Java E2E runner — developer guide

[Project status and priorities](../README.md) · [Test fixtures](../examples/README.md)

**Installed Windows baseline (23 November 2026): 23/23 direct Eclipse scenarios PASS** in one combined run, `cleanup=true` for each. This is direct debugger evidence, not Claude skill acceptance or a released-binary certification.

## Setup and safety

- Java 21+ and Maven; Eclipse IDE with Java/JDT/JUnit (m2e for supplied Maven fixtures). The direct tests **do not require Claude or Python**.
- Copy `e2e.properties.example` to **untracked** `dev-e2e/e2e.properties`. Specify `repository`, `eclipse`, `workspace`, `bridgePort` once. The workspace **must be disposable and dedicated**; the bridge port must not be default `8765`.
- The runner checks the running Eclipse identity before reuse. `test` does not install/rebuild the Eclipse plugin. `all`/`deploy` require the dedicated Eclipse instance to be **closed first**. Never point deployment at an IDE containing important work.
- The failure fixtures are snapshots in the workspace. If the repository changes, close the dedicated Eclipse instance and run `java dev-e2e/SyncFailureFixtures.java` for the two failure fixtures, or rebuild a new disposable workspace. General automatic fixture synchronization remains to be implemented.

## Commands (repository root)

```cmd
java dev-e2e/DevE2E.java list
java dev-e2e/DevE2E.java test --approve-fixture-post
java dev-e2e/DevE2E.java test --groups phase1,boot-gaps
java dev-e2e/DevE2E.java test --tests boot-gaps:startup-failure,boot-gaps:port-conflict
java dev-e2e/DevE2E.java all --approve-fixture-post
```

`--groups` and `--tests` are alternative selectors. `--approve-fixture-post` explicitly authorizes the local test fixture HTTP requests. Tests record JSON under ignored `evaluation/reports/`.

| Group | Direct tests |
|---|---|
| `phase1` | 7: conditional, exception, junit, variables, trace, pause-restart, filters |
| `phase2` | 2: validation, ownership |
| `boot` | 2: discovery, readiness |
| `boot-quote` | 1: quote |
| `acceptance` | 4: sb01-target-selection, sb09-expired-owner, sb09-borrowed-session, core-step-into-out |
| `boot-gaps` | 7: two-mains, junit-invalid-method, two-services, startup-failure, readiness-timeout, port-conflict, owned-isolation |
| **Total** | **23** |

## Claude trial captures — separate from direct regression

```cmd
java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude
java dev-e2e/DevE2E.java test --groups claude --approve-claude --approve-fixture-post
```

`claude` runs `boot-quote`, `junit` and `validation` as **opt-in real CLI trials** and captures evidence under `evaluation/reports/claude-trials/`. A completed transcript is `REVIEW_REQUIRED` until tool choices, runtime observations, explanations, consent and cleanup are independently checked. Trials may consume Claude usage.

## Reviewed Claude acceptance — 23 November 2026

**Three individual runs were reviewed:** `junit` (successful diagnosis in Claude final-result JSON), `boot-quote` (event-level PASS for request/thread/`rate=0`/HTTP 200/cleanup response), and `validation` (event-level PASS for invalid quantity, application exception, HTTP 400 and cleanup response). These are **single trials, not a reliability percentage**. The optional skill was not independently confirmed disabled or enabled; a controlled A/B evaluation is still pending. The CLI still emits `REVIEW_REQUIRED` for completed captures, not an automatic verdict.

For HTTP Claude trials, the runner uses the fresh repository MCP shaded JAR, an isolated test MCP configuration, test-only `start_fixture_http_request`/`get_fixture_http_request`/`cancel_fixture_http_request` tools and explicit `--approve-fixture-post`. The installed user-scoped MCP JAR can be a different revision; E2E does not update global registration. HTTP is asynchronous because request threads may suspend at breakpoints. Arbitrary URLs/production systems are not supported by these fixture tools. Keep original JSONL/manifest outside Git (ignored report directory).

The updated installation passed all 23 direct scenarios **across separate runs**, but the most recent uninterrupted combined suite encountered six transient Boot-gap timeouts before later 7/7 isolated group success. Reconfirm a single full regression before release. [Detailed acceptance ledger and caveats](../docs/claude-acceptance-2026-11-23.md).


## Retained test infrastructure

Keep the Java verifiers in `e2e-runner/src/main/java/`, the Maven fixture modules, the optional skill under `skills/eclipse-debugger/` and existing Windows specialized scripts. The former PowerShell HTTP fixture client was removed after Java parity; **other PowerShell scripts still provide unported coverage**. `integrate` and `cleanup` commands are migration utilities, not everyday test steps. `UpdateRoadmapDocs.java` is an earlier one-time status updater; the top-level README now records current progress.

For a release gate, record the exact source revision, Eclipse bundle identity, JDK, command and all six direct-suite JSON reports, rerun against a clean build and distinguish direct JDT evidence from Claude agent behavior.
