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
java dev-e2e/BridgeUnitTests.java
java dev-e2e/DevE2E.java test --groups bridge
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

The opt-in `bridge` group (4 scenarios) is run separately; see [Bridge stability tests](#bridge-stability-tests-core-13).

## Bridge stability tests (CORE-13)

Two levels, both separate from the 23-scenario regression (the `bridge` group is **not** included in a plain `test` run):

```cmd
:: 1. Unit tests — no Eclipse, no Maven, about 15 s (BR-U1..BR-U13)
java dev-e2e/BridgeUnitTests.java

:: 2. Fault injection against the installed bridge in the dedicated Eclipse (about 45 s)
java dev-e2e/DevE2E.java test --groups bridge
java dev-e2e/DevE2E.java test --tests bridge:busy
```

The `bridge` group uses the test-only `BRIDGE_TEST_SLEEP` command. The bridge accepts it only when the dedicated Eclipse was started **by DevE2E**, which sets `AGENTDEBUG_E2E=1`; a normal Eclipse never enables it. If the group reports *"Test hook disabled"*, close the dedicated Eclipse and rerun so DevE2E starts it. It launches nothing and changes no project, breakpoint or launch configuration.

| Scenario | Checks | Pass if |
|---|---|---|
| `slow-command` | BR-E1, E2, E4, E5 | PING / `LIST_DEBUG_SESSIONS` / `GET_E2E_INFO` / `LIST_BREAKPOINTS` answer in < 1 s while a 27 s state change runs; `BRIDGE_TIMEOUT` with an operation ID after ~25 s; `GET_OPERATION` returns the real result; diagnostics list the command; log has `SLOW`/`TIMEOUT` lines and never the token |
| `busy` | BR-E3 | A real `BEGIN_INVESTIGATION` gets `BRIDGE_BUSY` naming the running command after ~5 s, then succeeds once the lane is free; its investigation is cleaned up |
| `parallel-load` | BR-E9 | 20 parallel reads all succeed |
| `auth` | BR-E7 | Missing and wrong tokens are rejected exactly as before |

**Manual (BR-E8):** close Eclipse while a slow command runs → clean shutdown; after restart the bridge starts and `BRIDGE_START` appears in the log.

**Legacy comparison:** close the dedicated Eclipse, then run with `AGENTDEBUG_BRIDGE_MODE=legacy` set in the same command prompt (`set AGENTDEBUG_BRIDGE_MODE=legacy`), so DevE2E starts Eclipse in the previous single-threaded mode. Close Eclipse again and clear the variable afterwards.

**Bridge log:** `%USERPROFILE%\.eclipse-agentdebug\bridge-<port>.log` (rolls over at 1 MB). One line per command — name, lane, queue and run time, outcome — plus `SLOW`, `TIMEOUT`, `ABANDONED`, `BRIDGE_START/STOP` events. Never arguments or tokens. Tuning (rarely needed): `AGENTDEBUG_BRIDGE_BUSY_WAIT_MS` (default 5000), `AGENTDEBUG_BRIDGE_ABANDON_MS` (default 300000).

### Go / no-go checklist for bridge changes

Record a **baseline before deploying** a bridge change: 3 full `test --approve-fixture-post` runs on the current build, keeping the reports. Then, on the new build:

- [ ] `BridgeUnitTests` — 13/13
- [ ] `test --groups bridge` — 4/4, plus the manual shutdown check
- [ ] **3 consecutive uninterrupted 23/23 runs**, all with cleanup (one overnight loop of 10+ runs before calling CORE-13 done)
- [ ] Every scenario that passed in the baseline still passes; no scenario more than ~20 % slower
- [ ] One full run in `legacy` mode on the same build gives the same results (apart from timeouts that disappear in concurrent mode)
- [ ] Ownership checks 32/32 (`e2e-runner/ownership-tests/`)
- [ ] Old MCP server JAR with new plugin, and new MCP server with old plugin: `ping`, `eclipse_ping`, Phase 1 pass
- [ ] Manual: Preferences → Configure/Verify; one real Claude investigation; your own breakpoints and session untouched while Claude debugs
- [ ] Record commit, bundle, JDK and date in `docs/current-state.md`

Rollback: `AGENTDEBUG_BRIDGE_MODE=legacy` (no reinstall), or revert the single CORE-13 commit.

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
