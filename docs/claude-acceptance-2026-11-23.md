# Claude Code acceptance — Java/JUnit and Spring Boot (23 November 2026)

[README](../README.md) · [Java E2E runner](../dev-e2e/README.md) · [Evaluation plan](evaluation-plan.md) · [Capabilities](capabilities.md)

## Scope and evidence rules

Three **single-run** Claude Code investigations were captured against a dedicated Windows Eclipse IDE/JDT instance using the opt-in Java E2E framework and the disposable `spring-boot-debugging` fixture. These results establish **three investigated scenarios**, **not** a repeatable success percentage, an A/B benefit for the optional skill, or support for arbitrary Spring Boot endpoints. The optional skill was not established as loaded in these trials; classify them as *baseline-style* rather than claiming proven skill-disabled isolation.

Evidence comes from the operator-supplied CLI outputs, manifests and Claude transcripts reviewed on 23 November 2026. The JUnit run produced a final-result JSON; the later Boot runs produced **stream-JSON event transcripts** with MCP tool request/response evidence. A Claude claim of a result is weaker than an observed MCP event. `REVIEW_REQUIRED` is the **harness capture status**, not a PASS verdict. Original reports were generated under ignored `evaluation/reports/claude-trials/`; they are **not** committed with this document. Preserve the original reports and hashes for future audit; the filenames below are identifiers, not proof that the files exist in another clone.

## Evidence ledger

| Trial | Captured evidence | Reviewed result | Boundary |
|---|---|---|---|
| `claude:junit` | `junit-20261123-101725-244.claude.json` and manifest | **Successful symptom-driven diagnosis (reported)**. Claude selected `OrderServiceTest.calculatesDiscount`, reported `OrderService.quote` line 25, observed `percent=10`, `rate=0`, and attributed wrong total to `percent / 100` integer division. NORMAL cleanup was reported. | Final-result JSON and summary; tool-by-tool MCP responses and independent post-cleanup inventory were not captured. One denied `Grep` call did not deny Eclipse debugger tools. |
| `claude:boot-quote` | `boot-quote-20261123-113509-848.claude.jsonl`, manifest, MCP config | **PASS — event-level runtime evidence**. Claude launched owned Orders fixture, used `start_fixture_http_request`, suspended the HTTP request thread at `OrderService.java:24`, observed `percent=10`, `rate=0`, resumed the request, and used `get_fixture_http_request` to obtain HTTP 200 `{"total":120.00}` (expected 108.00). `finish_debug_investigation` returned `completed=true`, `action=CLEANUP`. | A readiness-first attempt was rejected and Claude retried. Cleanup response observed; no separate post-cleanup inventory. |
| `claude:validation` | `validation-20261123-115424-818.claude.jsonl` and manifest | **PASS — event-level runtime evidence**. Claude used the approved asynchronous fixture POST with `quantity=0`, observed the application exception in `OrderService.quote` line 17 and the controller request frame, and obtained HTTP 400 `{"error":"Quantity must be positive"}`. NORMAL cleanup returned `completed=true`, `action=CLEANUP`. | `OrderController.invalidOrder` handler identification used source/HTTP evidence; **no breakpoint hit inside that handler** was claimed. No independent post-cleanup inventory. |
| `claude:boot-quote` (evening, automatic grade) | `boot-quote-20261123-164056-136.claude.jsonl`, `.inventory.json`, `.verdict.json` | **PASS — graded automatically by EVAL-05.** `wait_for_stop` reported the stop at `OrderService:24` with `percent=10`, `rate=0`; approved `discount` request returned HTTP 200 `120.00`; `finish_debug_investigation` `CLEANUP`; independent inventory: no leftover breakpoints or running owned sessions, existing sessions unchanged. 12 debugger calls, 19 turns, 57 s, $0.12, `claude-sonnet-5-5`. | One run. A skill was loaded (with-skill run). The workspace had no user breakpoints, so their preservation was not really exercised. One fixture request was rejected first and retried. |

### First repeated batches (23 Nov 2026, evening) — not a valid skill comparison

Two batches of 3 runs per scenario (`--repeat 3`, with skill and `--no-skill`), graded automatically. With skill: boot-quote 3/3, junit 3/3, validation 0/3. Without skill: boot-quote 0/3, junit 2/3, validation 1/3. The failures follow the **order of the runs**, not the skill mode: the first validation run produced correct evidence, but JDT's modal "Repeated exception occurrence" dialog (Spring rethrows the same `IllegalArgumentException`, which hits the agent's exception breakpoint again) blocked JDT's event thread from about 22:27 until it was clicked at 22:36. During that time a stop was not reported to `wait_for_stop`, `finish_debug_investigation` reported `CLEANUP` while the JVM stayed live, and the following runs got the leftover JVM from `start_java_application` (`reusedSession`), so the fixture HTTP tool correctly refused them (`VERIFY_OWNED_FIXTURE_LAUNCH`). The grader marked all affected runs UNSAFE (`inventory-own-sessions-ended`). Evidence: bridge log, Eclipse `.log` (deferred breakpoint handling logged at 22:36:02), inventories. Fix: CORE-16 and harness isolation between runs. **Use only the reruns for any skill comparison.**

### Reruns after CORE-16 (23 Nov 2026, late evening)

With skill (batch `20261123-172553-skill`): boot-quote 3/3, junit 3/3, validation 3/3 PASS. Without skill (batches `20261123-173729-noskill`, `20261123-174953-noskill`): boot-quote 6/6, junit 6/6, validation 5/6 PASS. The one non-PASS (`validation-20261123-175500-083`) is a run where the Claude CLI ended after 7 turns with an error before any stop; it left its breakpoint and JVM behind and is graded FAIL. All runs used `wait_for_stop`; every completed run had independently verified cleanup. Averages per run: with skill 12.7 debugger calls, 19.1 turns, 42 s, $0.14; without skill 10.0, 14.5, 34 s, $0.10. These prompts name the tools and steps explicitly, so they cannot show a benefit from the skill; symptom-only prompts are the next experiment.

For the two Spring Boot trials the HTTP MCP tools were **test-only**, guarded by E2E environment and explicit fixture POST approval; they do **not** constitute a general-purpose HTTP client for production debugging.

## Installed direct regression — separate from autonomous Claude

Six suites contain **23** direct Java/JDT scenarios: `phase1` (7), `phase2` (2), `boot` (2), `boot-quote` (1), `acceptance` (4), `boot-gaps` (7). A previous combined run reported **23/23 PASS**, `cleanup=true`. After installing the HTTP MCP feature, a combined `all` run succeeded for the first **16**, then `boot-gaps` passed `two-mains` but its other six cases hit socket read timeouts. Afterwards the individual `junit-invalid-method` and `two-services` cases passed; subsequently the entire `boot-gaps` group passed **7/7**, `cleanup=true` on that updated installation. Thus all 23 scenarios have passed on the updated installation, but **the full uninterrupted 23/23 run after the HTTP feature was not reconfirmed**. Do not claim a repeat all-in-one PASS for that specific updated build.

**Update (later on 23 November 2026):** the cause was found with the CORE-13 bridge log — Eclipse console output blocking on an unread pipe when DevE2E starts Eclipse — and fixed; the CORE-13 build then passed 23/23 in 3 consecutive uninterrupted runs. See [ADR-006](decisions.md#adr-006--bridge-concurrency-model-core-13). Original observation: the `Eclipse-AgentDebug-Bridge` thread was in `ServerSocket.accept` in a thread dump captured *after* the failures, so a **persistent** deadlock was not seen; transient stalls remain unexplained. Eclipse error logs included JDI `VMDisconnectedException` during UI source lookup after debugged processes disconnected; this is not proof of the timeout root cause.

## Implementation tested and boundaries

- Eclipse bridge uses authenticated localhost transport; Java debugger commands run through JDT. The runner reads `dev-e2e/e2e.properties` and isolates the dedicated bridge port (observed `21766`).
- The test launcher uses the freshly built shaded `mcp-server/target/agentic-debugger-mcp-1.0.0-SNAPSHOT.jar` for these E2E Claude sessions; it must **not** assume the user-scoped staged JAR was refreshed by Eclipse deployment. The normal user's `eclipse-debugger` registration is unchanged by E2E configuration.
- `FixtureHttpTools` provides `start_fixture_http_request`, `get_fixture_http_request`, `cancel_fixture_http_request`: a constrained asynchronous local request path, not arbitrary URL/body access. It was built/deployed successfully and exercised by the two Claude HTTP trials.
- These trials used `wait_for_debug_pause`, which polls and does not offer exact event stop correlation. The event-based `wait_for_stop` (CORE-14) was added afterwards and is not part of this evidence.
- The source was compiled and installed using Maven/Tycho on Windows/JDK 25; no proof of a signed/released artifact, STS-specific launches, Linux/macOS, remote JVMs or other MCP clients.

## Reproduction commands (from repository root)

```cmd
:: Deterministic direct installed-Eclipse regression; no Claude usage
java dev-e2e/DevE2E.java test --approve-fixture-post

:: To rebuild and install: first close the dedicated Eclipse instance
java dev-e2e/DevE2E.java all --approve-fixture-post

:: Individual, opt-in Claude trials (may consume Claude usage)
java dev-e2e/DevE2E.java test --tests claude:junit --approve-claude
java dev-e2e/DevE2E.java test --tests claude:boot-quote --approve-claude --approve-fixture-post
java dev-e2e/DevE2E.java test --tests claude:validation --approve-claude --approve-fixture-post
```

The runner checks the configured Eclipse/workspace bridge. `test` does not deploy new MCP/plugin binaries. Report statuses remain `REVIEW_REQUIRED` until evidence is inspected. Never run against a non-disposable workspace or a real application.

## Follow-up acceptance gates — not yet closed

1. *(Done as EVAL-05, `dev-e2e/ClaudeTranscriptEvaluator.java`: self-test 13/13 and one fresh trial graded PASS; the three earlier transcripts were not available to regrade.)* Build a Java transcript verdict evaluator which parses MCP request/response events, verifies exact launch/ownership, request ID and response, required suspended application frame/variables, and cleanup; distinguish PASS/PARTIAL/FAIL/BLOCKED/UNSAFE. Retain raw JSONL.
2. *(Done for new trials: `ClaudeSymptomTrial` writes `<stem>.inventory.json`; verified on the evening boot-quote trial. User-owned marker preservation still needs a workspace that actually contains user breakpoints.)* Verify post-cleanup **independent** session and breakpoint inventories, including user-owned marker preservation; explicitly test negative ownership cases for fixture HTTP tools.
3. Run at least several independent sessions per scenario with fixed model and environment; capture costs and success distribution. Then compare without vs with optional skill, verifying actual activation.
4. Reconfirm one uninterrupted 23/23 regression on the updated candidate, diagnose any repeat timeout with in-flight command timing and **during-failure** thread dump.
5. Continue separate planned cases: plain Java symptom, ambiguity/two services, Spring-context JUnit. Avoid expanding product claims from these three trials alone.

**Status:** P0 *single-trial symptom investigation and evidence capture* completed for the implemented three scenarios. **P0 reliability and automated evidence gate remain open.**
