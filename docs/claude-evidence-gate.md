# Claude Evidence and Independent Cleanup Gate

[README](../README.md) · [Agent instructions](../AGENTS.md) · [Java E2E runner](../dev-e2e/README.md) · [Acceptance ledger](claude-acceptance-2026-11-23.md)

**Implementation:** `dev-e2e/ClaudeEvidenceGate.java` (Java 21 source launcher, no Maven/Python dependency) and `dev-e2e/ClaudeEvidenceGateSelfTest.java` (offline synthetic regression). The tool is independent of the existing `DevE2E.java` and `ClaudeSymptomTrial.java`; it does **not** patch them, the Eclipse plugin, MCP server or global Claude configuration.

## Two separate milestones

### 1 — Reconfirm the uninterrupted direct regression

On Windows, from the existing repository root with its **already deployed updated plugin** and *dedicated disposable Eclipse workspace*, run:

```cmd
java dev-e2e/DevE2E.java test --approve-fixture-post
```

This runs **all 23 direct tests across six groups in one invocation**, requires no Claude subscription usage, and creates its Java JSON reports under ignored `evaluation/reports/`. It may launch the dedicated test Eclipse instance; it **does not rebuild or redeploy** the plugin. `--approve-fixture-post` is explicit consent for the approved disposable localhost fixture tests only.

**Evidence required to claim PASS:** all six groups successfully complete in that one invocation, no timeout, each scenario `status=PASS`, cleanup succeeds, and the build/installed bundle identity and source revision are recorded. Do not report 23/23 for the updated build based only on separately passing groups. A failure requires the first failing scenario's report and bridge timing/stack evidence before changing implementation.

If plugin/MCP source actually changes, close only the **dedicated test** Eclipse instance yourself before using `java dev-e2e/DevE2E.java all --approve-fixture-post`. Never deploy into a personal Eclipse installation that's in use.

### 2 — Automated runtime evidence and independent cleanup

The new **live wrapper** performs the complete sequence:

1. Verify the dedicated fixture marker and read the local `dev-e2e/e2e.properties` port (must not be default `8765`). Read the authenticated bridge token **without displaying it**.
2. Read-only snapshot `GET_E2E_INFO`, `LIST_DEBUG_SESSIONS`, `LIST_BREAKPOINTS`, `LIST_EXCEPTION_BREAKPOINTS`, and `LIST_RUN_CONFIGURATIONS` **before** invoking Claude.
3. Run the existing Java `DevE2E` Claude trial with normal explicit permission flags, preserving the original `.claude.jsonl` and `.manifest.json`. This step consumes Claude usage.
4. Take the **same independent authenticated bridge snapshot after the Claude CLI exits**; it is not an assertion supplied by Claude.
5. Correlate `tool_use` with `tool_result` by tool ID in the captured stream JSON; verify scenario-specific launch, `NORMAL` investigation ID, scoped HTTP input, request ID, worker suspension, matching stack and locals, resume, actual HTTP response, and `CLEANUP` response.
6. Compare before/after inventory **items**, not merely their counts; a changed user session or marker makes the verdict fail. Write a new `.evidence-verdict.json` and preserve `.before.json` and `.after.json`. No automatic cleanup or deletions are performed on failure.

Example from repository root (requires explicit consent; **do not run merely to inspect old evidence**):

```cmd
java dev-e2e/ClaudeEvidenceGate.java run --scenario boot-quote --approve-claude --approve-fixture-post
java dev-e2e/ClaudeEvidenceGate.java run --scenario validation --approve-claude --approve-fixture-post
java dev-e2e/ClaudeEvidenceGate.java run --scenario junit --approve-claude
```

Running `ClaudeEvidenceGate.java run` causes the existing `DevE2E` runner to compile/test and launch the requested Claude trial **once**. Do not also run the same Claude trial separately unless repeated acceptance is intentional. The original `DevE2E` CLI deliberately reports `REVIEW_REQUIRED`; the wrapper reports a separate independent evidence verdict.

**Offline evaluation only — no Eclipse, no Claude usage:**

```cmd
java dev-e2e/ClaudeEvidenceGate.java grade --scenario boot-quote --transcript evaluation/reports/claude-trials/EXISTING.claude.jsonl
```

For archived runs without before/after snapshots, an otherwise fully evidenced HTTP debug investigation receives **PARTIAL**, because cleanup was acknowledged by Eclipse but independent inventory was not captured. That is a **stricter automation gate**, not a retraction of the earlier event-level investigation evidence. To compare a saved pair of authentic snapshots, add `--before path --after path`. Do **not** fabricate snapshots for historical runs.

## Verdicts and limitations

| Verdict | Meaning |
|---|---|
| `PASS` | Scenario runtime oracle, owned cleanup response, and read-only before/after inventory checks all match |
| `PARTIAL` | Recorded runtime can be supported but a required independent inventory or proof is missing |
| `FAIL` | Required runtime/cleanup evidence is absent or contradicted, or independent inventory changed |

The tool returns nonzero exit for any outcome other than `PASS`. Each recorded check and failure reason is written into the `.evidence-verdict.json`. The wrapper does not accept an `exitCode=0` or Claude's narrative as runtime proof. It treats missing evidence conservatively; a genuine run can be marked PARTIAL if a required tool result or inventory is missing.

**What inventory equality can and cannot prove:** It demonstrates that the listed sessions, Java line breakpoints, exception breakpoints, and debug launch configurations match at the two snapshot times. It does **not** prove the absence of temporary changes between snapshots, that all Eclipse debug resources are captured by those four listing APIs, or prove ownership of a marker whose fields are not exposed by the bridge. Do not claim a comprehensive user-resource isolation guarantee from this gate alone. Changes by another process between snapshots also cause conservative failure. Test only in an otherwise idle disposable workspace.

The Java runner does not remove the intentionally broken business fixtures or arbitrary projects. Test-only fixture HTTP tools are not unrestricted production HTTP clients. Never print/store bridge authentication tokens in source control. `evaluation/reports/` must stay ignored.

## Offline self-test

```cmd
if not exist "%TEMP%\agentdebug-evidence-test" mkdir "%TEMP%\agentdebug-evidence-test"
javac --release 21 -d "%TEMP%\agentdebug-evidence-test" dev-e2e\ClaudeEvidenceGate.java dev-e2e\ClaudeEvidenceGateSelfTest.java
java -cp "%TEMP%\agentdebug-evidence-test" ClaudeEvidenceGateSelfTest
```

The test builds **fabricated** streams, not user transcripts, and proves these classifications: fully observed and unchanged inventory = PASS; changed inventory = FAIL; no inventory = PARTIAL; missing incorrect runtime value = FAIL. Generated class output is placed under your system temporary directory, outside the repository. This self-test requires no Eclipse/Claude or network.

**Evidence note:** In the provided 23 November Boot quote and validation transcripts, Claude invoked the optional `Skill` tool at the beginning, so those trials **do not establish a controlled no-skill comparison**. The JUnit capture has not been event-replayed as a verified transcript in this package. A future skill A/B requires controlled skill availability and repeatable runs.
