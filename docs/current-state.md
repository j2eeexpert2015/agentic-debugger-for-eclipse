# Current State and Agent Handoff

[README](../README.md) · [AGENTS](../AGENTS.md) · [Development workflow](development-workflow.md) · [Capabilities](capabilities.md) · [Acceptance evidence](claude-acceptance-2026-11-23.md) · [Roadmap](development-roadmap.md)

**Evidence snapshot: 23 November 2026.** This is a dated engineering handoff, not live CI or release certification. A new agent must check current Git revision, installed bundle and test reports before making newer claims.

## Architecture and source map

Claude Code → MCP stdio server → authenticated localhost Eclipse bridge → Eclipse JDT → debugged Java/JUnit/Spring Boot JVM.

| Directory | Responsibility |
|---|---|
| `eclipse-plugin/` | JDT execution control, bridge and investigation ownership |
| `mcp-server/` | MCP tools and isolated test-only fixture HTTP reproduction |
| `dev-e2e/` | Java 21 E2E runner, deployment, Claude capture and bridge unit-test runner |
| `e2e-runner/`, `examples/` | Direct verifiers and intentionally buggy fixtures |
| `skills/eclipse-debugger/` | Optional agent guidance, separate from code-enforced safety |
| `docs/`, `maintenance/` | Documentation, evidence and one-time migration utilities |

See [capabilities](capabilities.md) for verified feature scope, [decisions](decisions.md) for architecture/safety constraints and [workflow](development-workflow.md) for change procedure.

## Verified state and boundaries

| Area | Verified evidence | Unverified boundary |
|---|---|---|
| Direct Java E2E | **23/23 PASS in 3 consecutive uninterrupted runs** on the CORE-13 build (23 Nov 2026), all `cleanup=true` | Overnight loop; Linux/macOS |
| Event-based stops / output (CORE-14, CORE-15) | Unit checks **18/18**, `stops` group **6/6** and unchanged regression **23/23** on the installed Windows build (23 Nov 2026), all `cleanup=true` | Several threads stopping at once; Spring Boot request threads; Claude trial using `wait_for_stop`; repeated runs |
| Bridge stability (CORE-13) | Bridge fault injection **4/4**, unit checks **13/13**; earlier timeout cause found (console pipe) and fixed | Legacy-mode comparison run; 32 ownership checks |
| Claude JUnit | Single-method discount diagnosis supported by Claude final-result capture | Independent full event verification; repeated reliability |
| Claude Boot quote | Single event-verified trial: owned HTTP request breakpoint, `percent=10`, `rate=0`, HTTP 200, total `120.00`, cleanup response | Independent post-cleanup inventory and repeats |
| Claude HTTP 400 | Single event-verified trial: invalid quantity, exception in `OrderService.quote`, HTTP 400/error body, cleanup response | Actual stop in handler (source/response identified handler); repeated trials |
| Skills and portability | Skill code and plans exist; Windows ownership checks cover subsets | Controlled skill A/B; complete user-owned marker matrix; STS and Linux/macOS |

Raw `.claude.jsonl`, manifests and direct JSON reports are under ignored `evaluation/reports/` on the testing machine, **not normally present in a clone**. `REVIEW_REQUIRED` records capture, **not an automatic PASS**. See [detailed acceptance ledger](claude-acceptance-2026-11-23.md).

## Next priority and safe continuation

**Current plan (24 Nov 2026, report added as step 2):** 1) unit tests as JUnit 5 and test reorganization (OPEN-17 done; OPEN-18 step A done, step B later); 2) debugging session report, decoupled behind a report listener, ChainTest after a prototype (OPEN-30); 3) demo sample applications in `sample-applications/`, one plain Java and one Spring Boot application, with `examples/` left unchanged as test fixtures (OPEN-22); 4) GitHub Copilot integration (OPEN-23). The README [Status and tracking](../README.md#status-and-tracking) table is the single list of open work; the numbered history below is kept as record.

1. **Done — CORE-13 bridge stability (23 Nov 2026).** 3 consecutive uninterrupted 23/23 runs, bridge group 4/4, unit tests 13/13 on the installed Windows build. Root cause of the earlier `boot-gaps` timeouts: Eclipse console output blocking on an unread pipe (DevE2E now redirects it to `e2e-runner/target/eclipse-console.log`; diagnostic `println` removed from `listDebugSessions`). Still open for CORE-13: one legacy-mode comparison run (close the test Eclipse first so DevE2E starts it with `AGENTDEBUG_BRIDGE_MODE=legacy`), the 32 ownership checks, and an overnight loop. On any future timeout, read `~/.eclipse-agentdebug/bridge-<port>.log` and `GET_BRIDGE_DIAGNOSTICS` before patching. [Checklist](../dev-e2e/README.md#bridge-stability-tests-core-13).
2. **Done — CORE-14 `wait_for_stop` and CORE-15 output head+tail (23 Nov 2026)** (README Done table). `stops` group 6/6, regression 23/23 on the installed build. Fixed during testing: a pause inside a native method (`Thread.sleep`) failed while reading locals; locals are now read separately and missing locals are explained in `localsNote`. Observed: with the Eclipse Console attached, program output is only in the `console` field. Next: priority 2 (transcript evaluator), with Claude trials now able to use `wait_for_stop`.
3. **Done — automatic Claude verdicts and repeated trials (EVAL-05, EVAL-06), 23 Nov 2026** (README Done table). First batches were spoiled by JDT's modal "Repeated exception occurrence" dialog (fixed: **CORE-16**, `stops` 6/6 and regression 23/23 on the installed build). Clean batches, 3 runs per scenario on `claude-sonnet-5-5`:

   | Arm | Runs | PASS | Debugger calls/run | Turns/run | Time/run | Cost/run |
   |---|---|---|---|---|---|---|
   | Skill allowed | 9 | 9 (100%) | 12.7 | 19.1 | 42 s | $0.14 |
   | Skill disabled | 18 | 17 (94%) | 10.0 | 14.5 | 34 s | $0.10 |

   The one FAIL: the Claude CLI ended after 7 turns (no debugger fault), leaving its breakpoint and JVM behind (**CORE-17, done 24 Nov 2026**: idle lease in Eclipse, cleanup when the MCP connection closes, removal of leftover agent breakpoints after an Eclipse restart, and breakpoints/launches without an ID attached to the open investigation; unit 30/30, `lease` 5/5, regression 23/23, `stops` 6/6). Every run used `wait_for_stop`; every completed run had cleanup independently verified. **Reading:** on these explicit prompts the skill adds no accuracy and costs more calls, turns and money; this says nothing yet about vague symptom-only prompts, where a skill could matter. Small samples on easy fixtures; treat as indication, not statistics.
4. Repeat no-skill trials with controlled fixture revision, model and permissions before estimating reliability or comparing optional skill A/B. Claude trials require explicit authorization and can consume usage.
5. Continue safe fixture sync and user-resource isolation as prioritized in the [development roadmap](development-roadmap.md).

**Next safe command (already deployed plugin, isolated disposable Eclipse):**

```cmd
java dev-e2e/DevE2E.java test --approve-fixture-post
```

This is a direct Java test, **not a Claude trial**; `--approve-fixture-post` authorizes only the disposable fixture. `test` does **not** redeploy plugin changes. If MCP/plugin source changes require deployment, **close the dedicated Eclipse test instance first** and run:

```cmd
java dev-e2e/DevE2E.java all --approve-fixture-post
```

The local E2E configuration lives in untracked `dev-e2e/e2e.properties` and must point to a dedicated disposable workspace, matching Eclipse install and authenticated nondefault bridge port. Recent tests used port `21766`, not a universal default. Never modify unrelated projects, user-owned sessions, global Claude MCP registration or credentials. Claude trials require separate `--approve-claude`.

## Updating this handoff

After a **material, verified milestone**, update the dated evidence snapshot, source revision and bundle, exact command/results, limits, remaining blocker and next safe action. Link detailed evidence rather than pasting raw logs. Keep historical phase documents as dated history. Do not include personal paths, secrets or unverified claims.
