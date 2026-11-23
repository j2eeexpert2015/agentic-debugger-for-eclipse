# Java E2E — Additional direct acceptance coverage

This update **extends the existing repository runner**, without replacing its previously passing 12 scenarios or changing plugin/MCP production code.

## Installation

Extract the ZIP **over the existing repository checkout**, preserving directory structure. **Do not delete the checkout first.** This overlay adds `BootGapVerifier.java` and updates `dev-e2e/DevE2E.java`; it does not replace or delete any other source. It does **not** contain or overwrite the local `dev-e2e/e2e.properties` file.

Repository prerequisites: existing `PhaseOneBridgeVerifier.java`, `PhaseTwoBridgeVerifier.java`, `JavaFixtureQuoteClient.java`, `SpringBootDirectVerifier.java`, `SpringBootQuoteVerifier.java`, and the already-present `PendingAcceptanceVerifier.java`. The latter exists in the current GitHub repository, but local worktrees need to contain it before selecting the `acceptance` group.

Java 21+ required. Run commands from the **repository root**. Tests reuse the dedicated Eclipse installation (auto-start only when safe), workspace and port in `dev-e2e/e2e.properties`. **No manual Eclipse opening or plugin redeployment for `test`.** Existing `all` and `deploy` commands remain unchanged.

## Commands

```cmd
java dev-e2e/DevE2E.java list

rem NEW tests only (no HTTP POST authorization required)
java dev-e2e/DevE2E.java test --groups acceptance,boot-gaps

rem A specific advanced test
java dev-e2e/DevE2E.java test --tests acceptance:core-step-into-out
java dev-e2e/DevE2E.java test --tests boot-gaps:two-mains
java dev-e2e/DevE2E.java test --tests boot-gaps:junit-invalid-method
java dev-e2e/DevE2E.java test --tests boot-gaps:two-services

rem Complete regression: previous 12 + 4 existing pending + 3 new = 19 direct cases
java dev-e2e/DevE2E.java test --approve-fixture-post
```

A command selecting only `acceptance` or `boot-gaps` does **not** require `--approve-fixture-post`. The full regression does, because the pre-existing HTTP scenarios issue an approved POST to the disposable Boot fixture.

## New/connected scenarios

| Group:case | Verification | Boundaries |
|---|---|---|
| `acceptance:core-step-into-out` | Dedicated JDT Step Into / Step Out assertions | Reuses existing verifier; actual installed acceptance not yet run with this updated kit |
| `acceptance:sb01-target-selection` | Target identity and invalid-target rejection | Direct debugger checks, **not** agent ambiguity-resolution evidence |
| `acceptance:sb09-borrowed-session` | Ending a borrower preserves an owned JVM | Not the full concurrency/safety matrix |
| `acceptance:sb09-expired-owner` | Expired investigation cannot restart a live owner JVM | Not a substitute for all negative scenarios |
| `boot-gaps:two-mains` | Orders and Inventory mains are independently discovered in the same fixture project | Does not verify Claude selection |
| `boot-gaps:junit-invalid-method` | Missing JUnit method rejected without changing active sessions | Does not test Spring context failure or disabled JUnit cases |
| `boot-gaps:two-services` | Two distinct Boot JVMs run concurrently; cleaning one does not stop the other | Does not test user-owned breakpoints or all multi-service interleavings |

Generated reports (ignored by `.gitignore`):

- `evaluation/reports/acceptance-java-live.json`
- `evaluation/reports/boot-gaps-java-live.json`

When selecting one case, the filename includes that case's name.

## Safety and scope

- Test targets MUST be the existing dedicated disposable Eclipse workspace; the Boot fixture marker is checked.
- Tests use NORMAL investigations and never intentionally kill Eclipse or modify unrelated sessions.
- Starting two Boot JVMs refuses pre-existing/reused targets; both owners are cleaned even after failures, with failures reported explicitly.
- No Python or PowerShell is used in the Java test execution path. Existing PowerShell skill/developer scripts remain outside this migration.
- This is an **untested live Eclipse extension** until run on your machine; compiling Java sources and checking selectors does not constitute live acceptance.
- STS-specific configurations, failed-startup/timeout fixtures, autonomous Claude trials, and complete user-marker/concurrency matrices remain pending.

## After running

```cmd
git status
git diff --check
git diff --stat
```

Review `acceptance-java-live.json` and `boot-gaps-java-live.json` for each PASS and `cleanup=true` before claiming completion. Preserve the previous 12-scenario result as a separate baseline.
