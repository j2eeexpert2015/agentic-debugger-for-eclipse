# Current State and Agent Handoff

[README](../README.md) · [AGENTS](../AGENTS.md) · [Development workflow](development-workflow.md) · [Capabilities](capabilities.md) · [Acceptance evidence](claude-acceptance-2026-11-23.md) · [Roadmap](development-roadmap.md)

**Evidence snapshot: 23 November 2026.** This is a dated engineering handoff, not live CI or release certification. A new agent must check current Git revision, installed bundle and test reports before making newer claims.

## Architecture and source map

Claude Code → MCP stdio server → authenticated localhost Eclipse bridge → Eclipse JDT → debugged Java/JUnit/Spring Boot JVM.

| Directory | Responsibility |
|---|---|
| `eclipse-plugin/` | JDT execution control, bridge and investigation ownership |
| `mcp-server/` | MCP tools and isolated test-only fixture HTTP reproduction |
| `dev-e2e/` | Java 21 E2E runner, deployment and Claude capture |
| `e2e-runner/`, `examples/` | Direct verifiers and intentionally buggy fixtures |
| `skills/eclipse-debugger/` | Optional agent guidance, separate from code-enforced safety |
| `docs/`, `maintenance/` | Documentation, evidence and one-time migration utilities |

See [capabilities](capabilities.md) for verified feature scope, [decisions](decisions.md) for architecture/safety constraints and [workflow](development-workflow.md) for change procedure.

## Verified state and boundaries

| Area | Verified evidence | Unverified boundary |
|---|---|---|
| Direct Java E2E | Historical uninterrupted **23/23 PASS** with reported cleanup | Fresh uninterrupted 23/23 on post-HTTP MCP build |
| Latest direct sequence | First 16 PASS together before six Boot-gap timeouts; later Boot-gap **7/7 PASS** as a group, with two isolated cases passing | Cause of transient timeout and a complete updated-build run |
| Claude JUnit | Single-method discount diagnosis supported by Claude final-result capture | Independent full event verification; repeated reliability |
| Claude Boot quote | Single event-verified trial: owned HTTP request breakpoint, `percent=10`, `rate=0`, HTTP 200, total `120.00`, cleanup response | Independent post-cleanup inventory and repeats |
| Claude HTTP 400 | Single event-verified trial: invalid quantity, exception in `OrderService.quote`, HTTP 400/error body, cleanup response | Actual stop in handler (source/response identified handler); repeated trials |
| Skills and portability | Skill code and plans exist; Windows ownership checks cover subsets | Controlled skill A/B; complete user-owned marker matrix; STS and Linux/macOS |

Raw `.claude.jsonl`, manifests and direct JSON reports are under ignored `evaluation/reports/` on the testing machine, **not normally present in a clone**. `REVIEW_REQUIRED` records capture, **not an automatic PASS**. See [detailed acceptance ledger](claude-acceptance-2026-11-23.md).

## Next priority and safe continuation

1. **First — CORE-13 bridge stability** (implemented in source, not yet installed-verified). Likely cause of the six `boot-gaps` timeouts: the bridge served one connection at a time while synchronous launches of failing fixtures ran, so queued commands exceeded the 30 s client timeout and then ran late. Sequence: (a) record a **baseline on the current build** — 3 full runs, keep reports; (b) deploy, run `java dev-e2e/BridgeUnitTests.java` and `DevE2E test --groups bridge`; (c) **3 consecutive uninterrupted 23/23 runs**, plus one legacy-mode run (`AGENTDEBUG_BRIDGE_MODE=legacy`) for comparison and the 32 ownership checks; (d) read `~/.eclipse-agentdebug/bridge-<port>.log` to confirm which command was slow; on any later timeout, use that log and `GET_BRIDGE_DIAGNOSTICS` before patching anything. Full checklist: [dev-e2e/README.md](../dev-e2e/README.md#bridge-stability-tests-core-13).
2. Build an independent Java Claude-transcript evaluator for PASS/PARTIAL/FAIL/UNSAFE based on actual MCP events and post-cleanup session/marker inventories.
3. Repeat no-skill trials with controlled fixture revision, model and permissions before estimating reliability or comparing optional skill A/B. Claude trials require explicit authorization and can consume usage.
4. Continue safe fixture sync, event-correlated pause waiting and user-resource isolation as prioritized in the [development roadmap](development-roadmap.md).

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
