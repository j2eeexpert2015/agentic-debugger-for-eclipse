# Java E2E milestone: failure paths, isolation, and Claude transcript capture

**Overlay, not full repo.** Extract into the existing `agentic-debugger-for-eclipse` checkout, overwriting matching files. Never delete the checkout, JDT plugin, Maven fixtures, legacy script harnesses, or your local `dev-e2e/e2e.properties`.

## Verified before this change

19/19 Java E2E scenarios, `cleanup=true`, in one Windows Eclipse run (reported 23 Nov 2026). This is a user-supplied result, not a GitHub release or the result of running these *new* tests.

## Added direct Java scenarios (4)

`boot-gaps:startup-failure`: Boot configuration's singleton bean throws a uniquely tagged exception; test requires observed marker, terminated JVM, no web readiness, and owner cleanup.

`boot-gaps:readiness-timeout`: a Boot non-web application is alive but has no HTTP listener. After bounded 6 seconds, it must *not* be reported ready; cleanup must stop the owned JVM. This is a harness-level readiness negative, not a new MCP readiness API.

`boot-gaps:port-conflict`: the fixture binds its own ephemeral **loopback** port, then starts Boot targeting that exact occupied port via command-line property. Verifier requires a unique failure marker, termination, no success startup, and cleanup. Does not commandeer any user or fixed port.

`boot-gaps:owned-isolation`: start Orders and Inventory in independent NORMAL investigations, clean Inventory first, verify Orders remains alive, then clean Orders. Complements existing `two-services` reverse-direction cleanup. Neither test exercises user-owned breakpoint preservation.

All new application mains are isolated in `org.learningfromexperience.bootfixture.failures`. The Orders API/business-defect source remains untouched. Existing Maven import must refresh/compile these new Java files in Eclipse; if class discovery fails, refresh the fixture Maven project in the dedicated test workspace (or run `all` after closing dedicated Eclipse).

## Commands (Windows CMD from repository root)

```cmd
java dev-e2e/DevE2E.java list
java dev-e2e/DevE2E.java test --tests boot-gaps:startup-failure,boot-gaps:readiness-timeout,boot-gaps:port-conflict,boot-gaps:owned-isolation
java dev-e2e/DevE2E.java test --approve-fixture-post
```

Default `test --approve-fixture-post` includes all previous 19 plus 4 new direct scenarios (expected 23 when new tests pass). It **does not** launch Claude. Reports are written to ignored `evaluation/reports/`.

## Claude symptom-only trials (separate, opt-in; no automatic PASS)

The same `DevE2E.java` runner supports an additional `claude` group. It starts the currently configured, installed Eclipse test instance and executes the local Claude Code CLI (`claude -p`) with **default tool permissions**; there is deliberately no bypass permissions flag. Trials may consume subscription/API usage. They require the Eclipse MCP registration to be valid; CLI headless sessions may deny MCP calls under default permissions, in which case inspect the captured transcript and treat it as an unsuccessful or inconclusive trial. Do **not** grant blanket permissions simply to make the experiment pass.

```cmd
java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude
java dev-e2e/DevE2E.java test --groups claude --approve-claude --approve-fixture-post
```

Three experiments: `claude:boot-quote`, `claude:junit`, `claude:validation`.

Each trial captures a timestamped prompt, Claude JSON transcript and manifest under `evaluation/reports/claude-trials/`. Result `REVIEW_REQUIRED` means only that Claude CLI exited normally. It is **not a PASS**. The human reviewer must verify actual MCP calls, fixture identity, correct selected target, real runtime frame/locals, response/assertion, root-cause argument, and safe cleanup. Capture an agent's failures as failures; do not infer from polished narrative. The CLI's `-p` invocation might require additional MCP permission configuration: record it as a blocked/inconclusive test rather than force an unsafe bypass.

There is no automatic claim of skill effect. For a controlled no-skill vs skill comparison, use isolated sessions, explicit activation evidence, identical prompts and fixture revisions, and repeated trials; run only after the above symptom-only baseline is verified. This harness is a capture stage, not a statistically valid skill A/B evaluator.

## Outstanding acceptance (not covered by these additions)

- Actual user-owned breakpoint preservation during simultaneous Boot investigations, duplicate launch-config selection, and interrupted sessions.
- Spring-context JUnit failure/disabled/parameterized behavior across installed configurations.
- Plain Eclipse versus Spring Tools launch modes and multi-OS runs.
- Explicit-vs-symptom-vs-skill repeated Claude trial grading; permission and activation checks.
- Migrating developer and skill-testing PowerShell scripts to Java with feature parity.

## Provenance and boundaries

Only the three new fixture mains, Java test verifier, Java runner, optional Claude capture source, and this documentation are in the ZIP. No Python source, Python dependency, or `.gitignore` replacement. Existing ignores for `evaluation/reports/` and `dev-e2e/e2e.properties` remain authoritative. Java 21 compiles the runner and verifier using local stub checks. New live Eclipse cases and Claude trials are NOT RUN here. Do not claim 23/23 or Claude success until independently observed.
