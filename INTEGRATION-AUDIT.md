# Integration audit — Java E2E runner

**Repository checked:** `j2eeexpert2015/agentic-debugger-for-eclipse` (`main`, commit `1e6e88ee8f2cd7a1abda4b4c08e4dc54352e1a57`, 23 November 2026).

This is a **complete repository-relative integration overlay**, not a replacement ZIP for the entire existing Git repository. The installer uses the repository's existing Phase 1 and Phase 2 verifier sources, Maven configuration, plugin implementation, Eclipse update site, skill evaluation scripts and fixture sources. They are intentionally kept intact.

## Checked against GitHub

| Path / component | GitHub status | Overlay action |
|---|---|---|
| `e2e-runner/.../PhaseOneBridgeVerifier.java` | Existing, seven passing cases | **Keep unchanged**, reused for Phase 1 and Java bridge support |
| `e2e-runner/.../PhaseTwoBridgeVerifier.java` | Existing Java/PowerShell client selector | **Migrate idempotently** via `integrate` to Java-only HTTP path; refuse unexpected source layout |
| `e2e-runner/.../JavaFixtureQuoteClient.java` | Already tracked | Include exact Java client in overlay |
| `e2e-runner/.../SpringBootDirectVerifier.java` | Already tracked | Include direct Boot tests in overlay |
| `e2e-runner/.../SpringBootQuoteVerifier.java` | Already tracked | Include quote/first-wrong-value test in overlay |
| `examples/.../FixtureIdentityController.java` | Already tracked | Include fixture-only PID identity endpoint in overlay |
| Root `pom.xml`, `e2e-runner/pom.xml` | Existing | Keep, don't overwrite |
| `scripts/windows/dev.bat` and other Windows harnesses | Existing, still used for legacy direct/skill test coverage | Keep, don't delete |
| `scripts/windows/verify-phase2-eclipse.bat` | Existing, uses old/default client unless updated | Update compatibility wrapper to select Java client and compile its dependency |
| `e2e-runner/boot-tests/Invoke-FixtureQuote.ps1` | Legacy HTTP client | Delete only through explicit `cleanup` after both Java Phase 2 cases pass |
| `docs/spring-boot-tool-audit.md` | Historical audit links to old client | Update obsolete client link at cleanup; retain historical results |
| `docs/phase2-verification-consolidation.md` | Historical PowerShell client incident and original source checklist | Preserve historical account, do not erase evidence |
| `dev-e2e/` | Not yet tracked | Add repeatable Java runner, local properties and documentation |

## Essential sequence

1. Extract the ZIP into the **root of an existing checkout** of this repository, preserving relative directories.
2. Review `dev-e2e/e2e.properties` (defaults match the Windows test machine previously used).
3. `java dev-e2e/DevE2E.java integrate`
4. With dedicated Eclipse running, `java dev-e2e/DevE2E.java test --approve-fixture-post` (or let Java start Eclipse).
5. Inspect `evaluation/reports/` and verify all twelve cases report PASS and cleanup.
6. `java dev-e2e/DevE2E.java cleanup` (optional and separately authorized by executing it).
7. Rerun the whole regression after cleanup and inspect `git status`; commit only the intended source changes.

For a **clean build + reinstall**, close the configured Eclipse installation first and run `java dev-e2e/DevE2E.java all --approve-fixture-post`.

All, group and individual scenarios are supported with `--groups` and `--tests`, documented in `dev-e2e/README.md`.

## Safety / coverage boundary

- Eclipse is not forcibly terminated.
- Bridge reuse now checks **actual workspace, Eclipse install directory, and installed plugin location**, not merely the open port.
- The Java HTTP client still requires explicit fixture-only POST approval.
- No production plugin code is altered by this overlay, except the already-existing fixture-only identity class (in `examples/`).
- Historical PowerShell skill and specialized testing files remain because no proven Java parity exists yet.
- The existing user's Windows test showed 12/12 PASS before repository integration. **This audited overlay has not been run in a live Eclipse environment here.**
