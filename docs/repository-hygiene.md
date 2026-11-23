# Repository hygiene review — 23 November 2026

[README](../README.md) · [Claude acceptance evidence](claude-acceptance-2026-11-23.md) · [Java E2E guide](../dev-e2e/README.md)

This review checked GitHub `main` source/documentation structure on 23 November 2026 alongside the operator-provided Windows build, regression and Claude trial results. **It is not an automated dependency/security audit and does not prove the local clone is identical to the default branch.**

## Actionable cleanup

| Item | Finding | Action |
|---|---|---|
| `eclipse-bridge-threads.txt`, `eclipse-errors.txt` at repository root | Diagnostic data from one transient timeout, not source/configuration. Files are **tracked**. Their contents can include local paths and stack traces. | Archive privately if wanted, then remove from version control; keep a short summary in the acceptance evidence page. Do not include future live diagnostics in commits. |
| `APPLY.md`, `maintenance/ApplyClaudeFirstRoadmap.java` | One-time roadmap updater with outdated claim that symptom-only Boot trials have not yet run. Historical migration utility, not needed by the current runner. | Remove from the active root / maintenance tree **after verifying the local roadmap changes are committed**. Git history preserves them. |
| `maintenance/InstallFixtureHttp.java` | One-time source integration utility; integration was applied and compiled successfully. | Keep until feature source changes are committed and inspected, then consider removing it from the active maintenance directory. Do not rerun it against an already integrated version without its idempotence checks. |
| `dev-e2e/UpdateRoadmapDocs.java`, `maintenance/RepositoryCleanup.java` | Earlier migration utilities. | **Do not delete automatically.** Review any remaining references/needed cleanup before removing. |
| `README-INSTALL-AND-TEST.md` | Skill candidate guide includes an outdated claim suggesting no HTTP reproduction tool exists. | Correct wording: test-only constrained fixture tools now exist; still **no general-purpose HTTP client**. Retain skill activation guidance as a separate, unverified candidate. |
| `docs/phase*`, `docs/spring-boot-tool-audit.md` | Dated historical tests and decisions, including old `NOT RUN` cells. | Retain for audit; link current ledger rather than rewriting old runs as if they had passed. |
| `scripts/windows/`, `e2e-runner/ownership-tests/`, fixtures | Specialized legacy scenarios not yet proven redundant with unified Java tests. | Retain pending parity mapping; removing them now risks losing coverage. |

## Safe removal procedure (optional, after review)

```cmd
:: Verify your working tree and inspect the candidates first
git status --short
git diff --check
git ls-files -- eclipse-bridge-threads.txt eclipse-errors.txt APPLY.md maintenance/ApplyClaudeFirstRoadmap.java

:: Only after preserving any diagnostic copies you need and verifying prior migrations
git rm -- eclipse-bridge-threads.txt eclipse-errors.txt APPLY.md maintenance/ApplyClaudeFirstRoadmap.java

git diff --cached --stat
```

Do **not** use recursive cleanup, `git clean -fdx`, deletion of `evaluation/reports/`, or workspace deletion. The proposed deletions are opt-in and not performed by the documentation updater. If these files contain local changes you want to retain, archive them before `git rm`.

## Known technical debt and next code work

1. **Claude evidence evaluator:** JSONL event parser, precise assertions, independent post-cleanup verification. Current `REVIEW_REQUIRED` is intentional until manual review.
2. **Fixture synchronization:** deterministic, marker-checked import/sync into disposable test workspace; currently partially manual.
3. **Debugger stop correlation:** replace 250 ms poll-based pause matching with actual launch/thread/event identity; do not treat timeouts as proof of wrong stop.
4. **Bridge timing:** per-command elapsed-time logging and bounded response diagnostics. A full-suite timeout was not reproduced by the isolated Boot-gap group; no code regression has been proven.
5. **MCP runtime lifecycle:** normal Claude registration may point at a staged user JAR while E2E uses freshly built repository shaded JAR. Document and verify refresh behavior before promising identical tool sets to everyday users.
6. **Permissions / safety:** test-only HTTP tools must remain disabled outside an explicitly approved disposable E2E environment; continue rejecting borrowed sessions and non-fixture targets.
7. **Release confidence:** CI workflows are currently stored as `.disabled`; no cross-platform release qualification or active CI is inferred.

No functional-source deletion or debugger refactor is justified solely by the three successful Claude investigations. Keep modifications evidence-driven.
