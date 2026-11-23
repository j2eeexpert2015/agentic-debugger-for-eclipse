# Phase 2 — Verification Consolidation and Remaining Acceptance

<!-- JAVA-E2E-19-STATUS:BEGIN -->
**Latest installed-Eclipse result (23 November 2026): 23/23 direct Java E2E scenarios PASS in one combined Windows run, with successful cleanup for each scenario.** Groups: Phase 1 Java/JUnit (7), Phase 2 validation/ownership (2), Boot discovery/readiness (2), HTTP quote defect (1), core acceptance (4), Boot failure/isolation acceptance (7). Dedicated startup-failure, readiness-timeout and port-conflict tests passed; startup and port failures were verified using actual JVM PIDs and OS process exit evidence. The combined result was reported from a local working tree, not a separately verified published release. Autonomous Claude, skill activation/comparison, STS-specific launch modes, Linux/macOS and full user-owned marker/configuration preservation remain OPEN. **Current implementation, exact runner commands and prioritized next steps:** [root README](../README.md#next-priorities). Older status tables below describe their dated milestones and must not be interpreted as the latest 23-scenario direct-test status.
<!-- JAVA-E2E-19-STATUS:END -->


[README](../README.md) · [Development Roadmap](development-roadmap.md) · [Spring Boot Scenarios](spring-boot-debugging-scenarios.md) · [Tool Audit](spring-boot-tool-audit.md) · [Evaluation Plan](evaluation-plan.md)

**Consolidated:** 22 November 2026. **Scope:** actual evidence recorded in the repository as of GitHub `main` commit `59e64785c23420528c889d013e87d7b1b0d9a3f3`, plus independently reported local Windows results after adding the P2-06 verifier. **This report is a test-evidence consolidation, not a Phase 2 release sign-off.**

## 1. Source and evidence boundaries

The original GitHub Spring Boot evidence was recorded on candidate `1.5.0.20261121194127738` and the subsequent JUnit negative-case run used `1.5.0.20261122050939175` (see [tool audit](spring-boot-tool-audit.md)). These are **historical, separate installed runs**.

The newly reported **local** runs used Eclipse debugger bundle **`1.5.0.20261122084229515`**, Eclipse JDT with Maven fixture projects, JDK **25.0.5.0.3**, bridge **127.0.0.1:21765**, and test workspace `%LOCALAPPDATA%\AgenticDebugger\dev-workspace`. The user ran tests from `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse` on 22 November 2026. Eclipse's doctor check reported: `PASS: installed debugger, compiled classes, Java fixture and JUnit discovery.` The updated Java verifier is a **local working-tree change**, not a claim that it is present in GitHub `main`.

**Evidence handling:** Recent successful results below are supported by terminal output supplied in the project conversation. The previously uploaded `evaluation/reports/phase2-live.json` belongs to a **failed earlier attempt**, and **must not** be cited as the successful combined run. Copy the final successful JSON report and command output into a durable report location before release sign-off. The Phase 1 output similarly identifies a local `phase1-live.json`, whose detailed contents were not uploaded here.

## 2. Latest local installed verification

| ID / check | Observed result | Evidence and limitations |
|---|---|---|
| Environment/bridge | **PASS** | `dev.bat doctor` reported installed debugger, compiled classes, Java fixture and JUnit discovery; port `21765` was `LISTENING` |
| **SB-08 — invalid quantity / handled exception** | **PASS — direct bridge** | New `verify-phase2-eclipse.bat validation` run stopped at `OrderService.java:17` and `OrderController.java:27`; HTTP **400** with `{"error":"Quantity must be positive"}`; `cleanup=true` |
| **SB-09 — ownership and fixture-client consent subset** | **PASS — direct bridge** | Combined `verify-phase2-eclipse.bat` showed `borrowedLaunchPreserved=true`, `expiredInvestigationRejected=true`, `unapprovedPostRejected=true`, `cleanup=true` |
| Combined Phase 2 verifier | **PASS — direct bridge** | `verify-phase2-eclipse.bat` ran `validation` and `ownership` sequentially in one Eclipse instance; overall `[Phase 2] PASS` |
| **Phase 1 regression (JAVA-01–JAVA-07)** | **PASS — direct bridge** | `verify-phase1-eclipse.bat`: conditional, exception, JUnit, variables, trace, pause-restart, filters: all seven `status=PASS, cleanup=true`; Maven reactor `BUILD SUCCESS` |
| Installed Phase 2 explicit Claude / symptom trials on **this local candidate** | **NOT RUN / not supplied** | Current tests exercise the bridge directly. Earlier explicit Claude trials on earlier candidate remain separately documented |
| Full ownership matrix and multi-service interference on **this local candidate** | **PARTIAL** | SB-09 subset passed. All cross-service/concurrent/launch-identity negative cases are not established by this new two-scenario verifier |
| STS installation matrix | **NOT RUN** | Plain Eclipse JDT/JUnit path exercised; Spring Tools launch delegate remains unverified |

### Debugging of the test harness itself

The first local SB-08 attempt created breakpoints and started Spring Boot but timed out without a suspended request. Diagnostics then showed that `Invoke-FixtureQuote.ps1` failed before sending HTTP due to `Net.Http.HttpClientHandler` being unresolved under Windows PowerShell 5.1. Loading `System.Net.Http` in the test client resolved the environment issue. The subsequent SB-08 run proved both service/handler stops and the actual HTTP 400 response. **This was a test-client failure, not evidence of a debugger defect.**

The Java verifier initially also attempted to read a `private` `Bridge.traces` field; that compile-time issue was corrected locally. Both corrections must be included in any future reproducible checked-in baseline. Do not mark the old unsuccessful report as passed.

## 3. Previously documented Spring Boot evidence (GitHub main)

| Work item / scenario | Earlier documented evidence | Status interpretation |
|---|---|---|
| P2-01 / P2-02 | Existing JDT/bridge tool audit, deterministic Spring Boot fixture, smoke tests and defect oracle | Implemented, reviewed |
| P2-03 / SB-01–SB-03 | Installed discovery, multi-main selection, saved configurations, profiles, launch readiness, startup errors, timeout and cleanup | Direct scenarios tested on earlier candidate; incomplete agent-behavior matrix |
| P2-04 / SB-04–SB-05 | Real POST through controller, repository and service; `percent=10`, `rate=0`; HTTP 200 total `120.00` instead of `108.00`; direct and one explicit Claude investigation | Main HTTP vertical slice demonstrated on earlier candidate |
| P2-05 / SB-06–SB-07 | Targeted ordinary JUnit and Spring-context JUnit; missing method, assertion failures, context initialization error and disabled method; two explicit Claude sessions | Direct and explicit checks documented on earlier candidate |
| P2-06 / SB-08 | Previously planned; now local installed direct 400/exception verification passed | Direct gate complete on local candidate; explicit/symptom gates pending |
| P2-06 / SB-09 | Previously planned; now local borrowed-launch, expired-ID, unapproved POST checks passed | Subset complete; full cross-session matrix pending |
| P2-06 / SB-10 | Plain Eclipse prerequisite evidence through local doctor and main/JUnit discovery | Spring Tools comparison and complete compatibility matrix pending |

## 4. Scenario acceptance matrix — do not treat unrun trials as failures or successes

Each scenario has four separate gates: **direct installed Eclipse**, **explicit Claude**, **skill-free symptom-only Claude**, and **negative/ownership**. `Partial` means some cases passed but the entire gate was not established. Historical runs on different installed candidates are distinguished from local P2-06 runs.

| Scenario | Direct installed | Explicit Claude | Symptom-only Claude | Negative / ownership | Remaining gap |
|---|---|---|---|---|---|
| SB-01 Target selection | PASS (historical) | NOT RUN | NOT RUN | PARTIAL | Agent selection under ambiguity |
| SB-02 Saved launch configuration | PASS (historical) | NOT RUN | NOT RUN | PARTIAL | Agent preserves config and refuses ambiguous target |
| SB-03 Readiness/startup failure | PASS (historical) | NOT RUN | NOT RUN | PASS (historical bounded failure cases) | Agent readiness decisions |
| SB-04 HTTP wrong total | PASS (historical) | PASS (historical, 1 run) | NOT RUN | PASS (historical approved-client rejection checks) | Symptom-only baseline and repetition |
| SB-05 Layer tracing | PASS (historical) | PASS (historical explicit HTTP investigation) | NOT RUN | PARTIAL | Independent symptom-only trial; no fabricated layers |
| SB-06 Plain JUnit test | PASS (historical) | PASS (historical, 1 run) | NOT RUN | PASS (historical missing method) | Symptom-only baseline |
| SB-07 Spring-context JUnit | PASS (historical) | PASS (historical, 1 run) | NOT RUN | PASS (historical context error/disabled) | Symptom-only baseline |
| SB-08 Validation and handled exception | PASS (local) | NOT RUN | NOT RUN | PASS (local HTTP 400 and owned cleanup) | Agent-level exception-handling trial |
| SB-09 Session isolation and consent | PARTIAL (local) | NOT RUN | NOT RUN | PARTIAL (local subset passed) | Two active services, ambiguous target, concurrent markers |
| SB-10 Plain JDT vs Spring Tools | PARTIAL (plain JDT) | NOT RUN | NOT RUN | NOT RUN | Inspect STS installation and delegate or explicitly mark environment not available |

**Full Phase 2 acceptance remains OPEN.** The historical runs show a functional Spring Boot vertical slice. The new local P2-06 direct checks and Phase 1 regression are green. They **do not** substitute for all skill-free symptom prompts or the entire STS/ownership matrix specified by [the scenario catalog](spring-boot-debugging-scenarios.md#3-acceptance-matrix-and-release-gating).

## 5. Next focused actions (before Phase 3 skill evaluation)

1. **Preserve reproducible source:** include the new `PhaseTwoBridgeVerifier.java`, `verify-phase2-eclipse.bat`, fixed `Invoke-FixtureQuote.ps1`, and package-visibility fix for `PhaseOneBridgeVerifier.Bridge.traces` in the same source baseline; capture their exact versions. Note that changing the test harness does not require reinstalling the plugin.
2. **Archive final reports:** capture the **successful** `evaluation/reports/phase2-live.json` and `evaluation/reports/phase1-live.json` with revision, local patch identity, bundle version, scenario outcomes and cleanup; do not overwrite or conflate earlier failed files.
3. **Run bounded remaining deterministic coverage:** SB-09 multi-service preservation, ambiguous/borrowed launch and no unauthorized deletion; check whether the intermittent `FINISH_INVESTIGATION` destroy error recurs. Do not expand MCP APIs unless a real failure demonstrates a gap.
4. **Complete only the highest-value skill-free agent baseline:** at least SB-04, SB-06, SB-07 and SB-08 symptom-only prompts in fresh independent sessions, using consistent model permissions and no skill. A single result per prompt is descriptive, not a performance rate.
5. **Document SB-10 honestly:** distinguish the verified plain JDT/JUnit setup from untested Spring Tools delegates; untested combinations must remain `NOT RUN`.
6. **Release gate:** rerun cross-phase installed regression against a traceable source/artifact and record all unrun gates explicitly before claiming Phase 2 fully verified. Phase 3 skill authoring may begin once its chosen prerequisite baseline is defined, but skill A/B improvement claims require the Phase 4 controlled evaluation.

## 6. Local commands (CMD, disposable Eclipse already running)

```cmd
scripts\windows\dev.bat doctor
scripts\windows\verify-phase2-eclipse.bat
scripts\windows\verify-phase1-eclipse.bat
```

The Phase 2 verifier requires the existing `AGENTDEBUG_WORKSPACE`, nondefault `AGENTDEBUG_PORT` and explicit `AGENTDEBUG_APPROVE_FIXTURE_POST=YES` environment settings. It targets only the approved local fixture. These are **verification commands**, not a substitute for the separate explicit/symptom Claude trials.
