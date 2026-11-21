# Phase 1 — Implementation Status and Release Checklist

[README](../README.md#documentation) · [Eclipse setup and validation](eclipse-installation-and-validation.md) · [Prompt tests](phase1-claude-scenarios.md) · [Capabilities](capabilities.md)

**Status on 22 November 2026.** All four planned verification work items are complete. Candidate `1.5.0.20261121184224374` ran in an isolated Windows Eclipse installation. Publication is pending.

| Work item | Outcome |
|---|---|
| Fresh workspace and redeployment | `dev.bat` imported the Maven fixture and ran the tests from an empty workspace. A second deployment reused that workspace and passed the tests again. |
| Ownership and failure handling | All 32 live checks passed on the candidate, including user-marker preservation, reused sessions, concurrent launches and stale investigation IDs. |
| Tool selection from symptoms | Claude chose the intended mechanism in six of seven trials. Five answers were complete. Trace output covered IDs 1000–1283, and the pause trial sampled one JVM twice instead of repeating the run. Each trial included relevant fixture source. |
| Same-build acceptance | The installed calculator regression, all seven direct scenarios and all seven explicit Claude scenarios passed on the candidate. Every direct and explicit feature scenario reported successful cleanup. |

## Source and artifact audit

On 22 November 2026, the plug-in retained in the disposable Eclipse installation matched the SHA-256 recorded by the explicit Claude verification run. The original build ran from a modified checkout based on `7b559f2234029eb72d49b5427de02e68fd91db59`. A subsequent committed revision reproduces its complete unpacked plug-in contents.

| Item | Verified value |
|---|---|
| Source revision rebuilt | `ff28455bcdd8be96901d0629533da6fb61d18843` |
| Installed bundle | `org.learningfromexperience.eclipse.agentdebug_1.5.0.20261121184224374.jar` |
| Tested plug-in JAR SHA-256 | `0D2C8F070148B122806C0EB17DC2B5B324310D9838381733D95F4FBCCA437211` |
| Comparison rebuild JAR SHA-256 | `B56E836D8903F5A4EA7E3183DDCBBF573BCDDE208BE9F8D7A1867F04A8CA340E` |
| File comparison | All 3,315 file paths and SHA-256 values matched, including 3,286 entries inside the bundled MCP JAR. The two installed feature files also matched. |

The comparison used an empty directory populated by `git -c core.autocrlf=false archive` of the source revision, with CRLF restored for `mcp-server/pom.xml` as in the Windows checkout. Maven 3.9.16 ran `mvn -o -DforceContextQualifier=20261121184224374 clean package`. The audit compared uncompressed file bytes recursively through the nested MCP JAR. The outer archive hashes differ. Runtime acceptance results belong to the original installed JAR. The local update-site ZIP inspected during the audit contained plug-in qualifier `202611211846`, a different build. The original tested ZIP has no verified hash in the available evidence.

## What remains

- **Publication.** The [source and artifact audit](#source-and-artifact-audit) links the tested plug-in to committed source. The original tested update-site ZIP remains unidentified. Before publication, identify and validate the exact distribution ZIP or verify a newly versioned release candidate from committed source. CI and release workflows are disabled.
- **Coverage follow-ups.** Full trace retrieval for fast-exiting launches, repeat-run selection from a symptom alone, visible editor focus with simultaneous sessions, parameterized JUnit coverage, and installed managed-registration changes remain open. The prompt results describe one trial per scenario.
- **Next development phase.** Phase 2 covers essential Spring Boot workflows and Spring-context JUnit debugging. The optional debugging skill and its A/B evaluation follow. Field watchpoints, remote JVM attach, variable mutation, hot code replacement and advanced Boot work such as JPA diagnostics remain deferred. [Roadmap](feature-roadmap.md)

## Verified Phase 1 features

| ID | Capability added in source | MCP tool(s) | Verification state | Caveats |
|---|---|---|---|---|
| P1-COND | JDT line-breakpoint condition and evaluation policy | `set_conditional_breakpoint`; existing `list_breakpoints` includes condition | Direct and explicit Claude scenarios passed | Conditional expression errors occur at runtime; duplicate markers are preserved |
| P1-EXC | Eclipse JDT caught/uncaught exception breakpoints; owned cleanup | `set_exception_breakpoint`, `list_exception_breakpoints`, `remove_exception_breakpoint` | Direct and explicit Claude scenarios passed | Exception type is supplied explicitly; duplicate markers rejected |
| P1-JUNIT | JUnit source discovery and JUnit-launch configuration | `list_junit_tests`, `start_junit_test` | Direct and explicit Claude scenarios passed | JUnit must be installed; JUnit 4/5 runner selection uses test source; parameterized methods need separate coverage |
| P1-VARS | Read-only nested fields and array-element expansion, limit 1–50 | `expand_frame_variable` | Direct and explicit Claude scenarios passed | Variable names depend on JVM debug information; no evaluation; output values clipped |
| P1-TRACE | JDT conditional evaluation with `System.out.println(...); return false;` | `set_tracepoint` | Direct and explicit Claude scenarios passed | Expression evaluation may have side effects and requires user consent; verify Eclipse console output |
| P1-PAUSE | Reuse existing PAUSE control; new investigation-owned restart | existing `control_debug_session`, `restart_debug_session` | Direct and explicit Claude scenarios passed | Reused/user-owned launches cannot be restarted by new tool |
| P1-FILTER | Per-Java-target filter configuration | `configure_step_filters` | Direct and explicit Claude scenarios passed | Respects target; 30 patterns max; does not change global Java Debug preferences |

## Earlier source and fixture checks

- Java 21+ example main classes compiled with `javac` and four standalone examples executed with expected sample output.
- Modified dependency-free `DebugInvestigationPolicyTest` compiled and passed with `javac` and `java`.
- Java 21 Phase 1 bridge verifier compiled with `javac` (syntax/standard-library check only).
- Java-only p2 ZIP validation utility compiled with `javac`.
- The initial source checks preceded the installed Windows tests recorded below.

## Reproducing the verification on a new candidate

1. `mvn clean package` at repository root; must compile every new Java operation against actual Eclipse JDT APIs and package an update-site ZIP.
2. Install candidate into disposable normal Eclipse; confirm bundle/feature 1.5.0, restart; ensure only that Eclipse holds bridge port.
3. `mvn verify -Pinstalled-e2e` against same installed instance; require baseline PASS and exact installed binary check.
4. Use `dev.bat deploy` to import the fixture, or import `examples/phase-one-debugging` as an existing Maven project manually. The JUnit fixture is intentionally failing; this is expected. Allow workspace build to complete.
5. `mvn verify -Pphase1-bridge`; confirm seven PASS results and successful cleanup (tests may need adaptation to actual Eclipse console behavior, but report failures accurately).
6. Run explicit Claude prompts for every feature and preserve full MCP traces; independently verify runtime states.
7. Run natural-language prompts on the same fixture and record feature-selection rate, correct diagnosis, tool calls, time, and failures. **These probabilistic outcomes should not block a correct deterministic plugin feature release**, but must be reported.
8. Run negative permission/ownership tests and the core suite without any skill.

## Known gaps that should not be concealed

- The existing bridge returns `get_process_output` only while a launch is active. Fast-exiting programs require an inspection window or a separate output-capture improvement.
- The new JUnit discovery recognizes familiar test annotations by name; it is not yet a complete JUnit Platform test-plan discovery implementation.
- The Windows run verified console trace output and nested fields for the supplied fixtures.
- A skill and A/B evaluation runner have **not** been introduced in this phase, by design.

## Verification update — November 21, 2026

A user-run `mvn verify -Pphase1-bridge` exposed (1) incorrect Base64 encoding of numeric verifier arguments, (2) a missing `phase-one-debugging` Eclipse workspace project, (3) a JUnit assertion escaping scenario-level reporting, and (4) an unescaped Windows batch `echo` parenthesis. The Java verifier and Windows script are corrected here. That run required a manual import. The subsequent developer workflow automated it. The subsequent installed run is recorded below.

## Installed Windows results on 21 November 2026

Maven/Tycho packaged the update site. The installed bundle was 1.5.0.202611212359. The bridge reported an operations class SHA-256 of 42452f11a3100eb181371edbb0e863ad5609ac4e15947647e0adeddadd9193db, matching the compiled class.

The Java bridge verifier ran all seven fixture scenarios in one invocation. All seven passed, including tracepoint console output, and each investigation reported successful cleanup.

The installed Claude calculator regression passed with Maven profile `installed-e2e`. Its trace verified a step-over, source navigation, a suspended calculator stack, and the observed value `sum=10`.

Seven fresh Claude sessions ran the explicit feature prompts with line-numbered fixture source. Conditional breakpoints, individual JUnit debugging, nested variables, tracepoint output, pause/restart, and step filtering met the requested observations. The exception prompt partially passed. Claude captured the throwing stack and quantity but obtained the exception message from source after the variable path was rejected. All seven NORMAL investigations reported successful cleanup. At this earlier checkpoint, symptom-prompt and ownership runs were still outstanding. The 22 November results above supersede that status.
### Exception message retest

Installed build 1.5.0.20261121235901 passed all seven direct bridge scenarios in one run, including a new assertion on the thrown exception's detailMessage. A fresh Claude exception scenario received no fixture source or expected message. It observed processOrder at line 15, quantity 0, and the live detailMessage "Quantity must be positive". NORMAL cleanup completed. This completes the previously partial explicit exception scenario. The other six explicit Claude results are from the preceding build.

### Fresh Windows workspace and repeat deployment, 22 November 2026

The default Windows developer command packaged and installed the feature in a separate Eclipse installation, started an empty workspace on loopback port 18765, imported the Maven fixture, and verified Java and JUnit discovery. The installed Claude calculator regression and all seven direct scenarios passed. A standalone deployment to the same installation and workspace also passed. The subsequent test run passed the calculator regression and all seven scenarios with successful cleanup. The trace scenario now waits for the order batch to exit before cleanup. The test IDE closed normally after the repeat run.

### Installed ownership and failure checks, 22 November 2026

The isolated ownership harness reproduced three failures on bundle 1.5.0.20261121183022598. A rejected JUnit launch left a saved configuration. A CONTROLLED investigation could issue a control request to, and finish against, a different JVM using its previous launch configuration name.

Bundle 1.5.0.20261121184224374 passed all 32 live checks. Investigations bind to the actual Eclipse launch object, and the launch target is validated before saving a new configuration. The suite exercised genuine JDT user line and exception markers, a user replacement at a formerly agent-owned location, a borrowed suspended launch, simultaneous distinct and same-name launches, stale/completed IDs, invalid inputs, NORMAL cleanup and CONTROLLED retention. The MCP server also rejected tracepoint and expression evaluation calls with consent explicitly set to false.

The test bundle is separate from the product. To reproduce, use the following command in PowerShell with the disposable installed IDE closed and a workspace already prepared by the developer deployment command.

```powershell
.\e2e-runner\ownership-tests\run.ps1 -EclipseDirectory C:\test\eclipse -Workspace C:\test\workspace -Port 18765
```

The harness writes ownership-results.json in the selected workspace. Its startup extension requires an explicit workspace match and a non-default bridge port.
The standard installed Claude calculator regression and all seven direct Phase 1 scenarios also passed on the ownership-fix build. All seven reported successful cleanup.

### Same-build explicit Claude verification

On 22 November 2026, seven fresh Claude Sonnet 5.5 sessions exercised the explicit feature prompts against installed Windows bundle `1.5.0.20261121184224374`. All seven met the requested observations and returned completed NORMAL cleanup with the original investigation ID.

| Feature | Verified debugger evidence |
|---|---|
| Conditional breakpoint | First inspected stop at order 1057 had discountPercent 10. Resume was accepted. |
| Exception breakpoint | Caught and uncaught flags were set. The throwing frame was processOrder and the live detailMessage was “Quantity must be positive”. A consent-denied evaluation attempt was followed by read-only field inspection. |
| JUnit | Discovered and launched appliesTenPercentDiscount alone. Runtime operands showed a 10 percent input and a computed rate of 0.0. |
| Nested variables | Read Kolkata and two items with price/quantity pairs 60/2 and 10/1, using bounded reads. |
| Tracepoint | Authorized output included TRACE-ORDER 1057. The output window was truncated; full batch coverage was not verified. |
| Pause and restart | Paused the main thread, inspected its stack, restarted the owned launch, and observed the replacement running. |
| Step filters | Enabled the requested filters and stepped from main line 9 to main line 10. |

The same installed build previously passed the installed Claude calculator regression, all seven direct bridge scenarios, and 32 ownership and failure checks. In the separate symptom-prompt assessment five scenarios were complete and two partial. Those results represent one run per prompt with fixture source supplied.

The checkout contained local changes. The base commit alone does not identify the tested binary. Plugin JAR SHA-256: `0D2C8F070148B122806C0EB17DC2B5B324310D9838381733D95F4FBCCA437211`.
