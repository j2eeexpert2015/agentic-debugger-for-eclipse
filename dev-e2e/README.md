# Java end-to-end tests (development)

<!-- JAVA-E2E-19-STATUS:BEGIN -->
**Current evidence (23 November 2026):** In one combined Windows run, the repository-integrated Java E2E runner completed **19/19 direct Eclipse scenarios PASS**, all reporting successful cleanup. This includes dedicated Step Into/Out, two Boot mains, two concurrently running Boot JVMs with owned-session isolation, JUnit method rejection, HTTP quote and validation checks. Run `java dev-e2e/DevE2E.java test --approve-fixture-post` from the repository root. Full Claude/skill, Spring Tools, Linux/macOS, startup negatives and comprehensive user-marker/configuration isolation are **not yet verified**. See [current README](../README.md#4-limitations-and-pending-work) for prioritized next steps. Older dated sections below are preserved as historical snapshots; do not interpret their previous `NOT RUN` or `pending` entries as the latest direct-test status. Do not infer autonomous Claude behavior from a Java bridge test.
<!-- JAVA-E2E-19-STATUS:END -->


The core repository contains the Java E2E runner and all currently migrated test sources. **JDK 21+** and **Maven** are required; live suites require a local Eclipse installation with JDT and the plugin. Commands below are executed **from the repository root**, not from `dev-e2e/`.

## One-time setup

Configure `dev-e2e/e2e.properties`:

| Property | Purpose |
|---|---|
| `repository` | Exact path to the checkout (must equal current repository root) |
| `eclipse` | Dedicated Eclipse installation directory containing `eclipse.exe` on Windows |
| `workspace` | Dedicated disposable test workspace; never use a normal user workspace |
| `bridgePort` | Dedicated port (not default `8765`); must be free for startup |

Use a test Eclipse installation separate from any running personal IDE. The defaults in the bundled example correspond to the existing Windows verification setup; adjust as needed. Do not commit local absolute path changes.

Run `integrate` once after extracting this repository overlay. The `all` and `deploy` modes also integrate automatically; `test` and `build` do not silently rewrite the verifier. Integration migrates `PhaseTwoBridgeVerifier.java` once to the **Java-only** fixture client. It checks for source anchors and fails closed on an unknown verifier structure. Existing `PhaseOneBridgeVerifier.java` and debugger production sources are not modified.

## Commands

```sh
# Inspect available commands
java dev-e2e/DevE2E.java help

# Integrate the Java client and verify the new sources; no Eclipse actions
java dev-e2e/DevE2E.java integrate

# Retire only the obsolete PowerShell HTTP fixture client after TWO Phase 2 Java cases PASS
# This requires the Phase 2 Java report produced by the tests (validation + ownership)
java dev-e2e/DevE2E.java cleanup

# Run every migrated scenario against the installed test Eclipse, auto-starting it if absent
java dev-e2e/DevE2E.java test --approve-fixture-post

# Run entire migrated Phase 1 regression only (seven scenarios)
java dev-e2e/DevE2E.java test --groups phase1

# Run selected groups
java dev-e2e/DevE2E.java test --groups phase1,boot

# Run individual / mixed scenarios
java dev-e2e/DevE2E.java test --tests phase1:trace,phase1:exception,boot:readiness

# Reproduce real HTTP validation/ownership, only with consent
java dev-e2e/DevE2E.java test --tests phase2:validation,phase2:ownership --approve-fixture-post

# Build + deploy + auto-start Eclipse + run all (Eclipse MUST be closed first)
java dev-e2e/DevE2E.java all --approve-fixture-post

# Deploy already-built update site (Eclipse MUST be closed first)
java dev-e2e/DevE2E.java deploy --approve-fixture-post
```

## Supported selectors

| Group | Individual scenarios | Default coverage |
|---|---|---|
| `phase1` | `conditional`, `exception`, `junit`, `variables`, `trace`, `pause-restart`, `filters` | 7 direct Java JDT scenarios |
| `phase2` | `validation`, `ownership` | 2 live Boot HTTP/safety scenarios |
| `boot` | `discovery`, `readiness` | 2 Boot main/healthy startup scenarios |
| `boot-quote` | `quote` | 1 actual HTTP quote defect |

`--groups` selects whole groups. `--tests` selects named cases; multiple cases from the same group are supported. Do not combine `--groups` with `--tests`. HTTP suites require `--approve-fixture-post`; other suites do not. Every case writes a distinct JSON report under `evaluation/reports/`, containing observed runtime evidence and cleanup state.

## Deployment and safety

- `test` compiles the Java verifiers, verifies or starts the dedicated Eclipse workspace, and executes selected scenarios. It does **not** rebuild/redeploy the plugin.
- `all` runs Maven packaging and Eclipse p2 deployment first, and **will refuse** to change an already-running Eclipse installation.
- The runner never terminates Eclipse automatically and refuses port `8765`, an unrelated workspace or a reused application for destructive scenarios.
- For live HTTP tests, `JavaFixtureQuoteClient` verifies the expected loopback application identity and explicit consent, prevents proxy/redirect use, and captures a receipt.
- The script does not execute Claude Code or skill evaluations. Those PowerShell tools remain until separately migrated and verified.
- The report is evidence for **the selected fixtures/scenarios only**, not proof of Linux/macOS compatibility or of all advanced Spring Boot acceptance combinations.

## PowerShell cleanup boundary

`cleanup` deletes `e2e-runner/boot-tests/Invoke-FixtureQuote.ps1` **only after** verifying the Java-only verifier, the legacy `verify-phase2-eclipse.bat` Java client selection, and a stored two-scenario Phase 2 Java report showing PASS and cleanup for both cases. It also repairs the historical fixture-client link in the Spring Boot tool audit. It intentionally leaves other PowerShell scripts in place because the Claude/skill evaluation and older Windows developer tooling still depend on them. Do not delete those scripts until the corresponding Java equivalents pass parity and references are updated.

After tests, review `git status` before committing the migration and generated outputs. Keep transient `target/` folders and `evaluation/reports/*.json` out of version control unless intentionally archiving verified evidence.

## Current GitHub baseline and retained dependencies

This integration overlay was checked against GitHub `main` at commit `1e6e88ee8f2cd7a1abda4b4c08e4dc54352e1a57` (23 November 2026). It deliberately **does not duplicate** source already present in the repository: `PhaseOneBridgeVerifier.java`, `PhaseTwoBridgeVerifier.java`, the Maven POMs, feature/update-site definitions, Eclipse plugin, or the older skill test harness. Those are essential retained dependencies, not omissions. The Java-only switch in `PhaseTwoBridgeVerifier.java` is applied by the deterministic `integrate` command and refuses an unknown source layout.

**Checked-in source changes:** runner + documentation, Java client and Boot verifier sources, fixture identity endpoint, fixture usage documentation and Windows legacy batch compatibility wrapper. **Local-only:** `dev-e2e/e2e.properties`, generated reports and `target/` directories; verify `.gitignore` before committing.

Before running `all`, close the selected Eclipse installation; do not close other installations. `test` may reuse the dedicated Eclipse test instance only after validating its actual workspace and install directory. Keep the old skill/ownership PowerShell harnesses until their remaining test coverage is migrated and verified.

**Verification limit:** Java 21 compilation and package integrity can be checked offline. A live Windows Eclipse regression is required after integrating this overlay, and the user should review its reports before deleting or committing old files. No offline audit can certify the future machine-specific Eclipse run.
