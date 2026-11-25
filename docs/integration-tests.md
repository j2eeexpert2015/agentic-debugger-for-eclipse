# Integration tests

The integration tests are **JUnit 5 classes** that drive the installed plugin in a real, disposable **test Eclipse**
through its bridge. No AI agent is involved and no Claude usage is spent, except in `ClaudeAgentEndToEndTests`. They
live in `testing/src/test/java/org/learningfromexperience/agentdebug/e2e/`; the normal build (`mvn clean package`)
compiles them but never runs them, because they need the running test Eclipse.

## Running them

**1. Start the test Eclipse** (setup of `dev-e2e/e2e.properties` once per machine: [Java runner](../dev-e2e/README.md)):

```cmd
:: Build, install the plugin into the test Eclipse and start it (close the test Eclipse first)
java dev-e2e/DevE2E.java all
:: Install the last build and start it, or only start it with the plugin already installed
java dev-e2e/DevE2E.java deploy
java dev-e2e/DevE2E.java start
```

**2. Run the tests** with Maven (in a `cmd` chain write `call mvn`):

```cmd
:: Regression suite (default, 21 tests); -Dfixture.post.approved=true allows the HTTP requests to the test application
mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true

:: Another suite, one class, or one test (Class#method)
mvn -pl testing verify -Pintegration-tests -Dit.test=FullSuite -Dfixture.post.approved=true
mvn -pl testing verify -Pintegration-tests -Dit.test=ExtendedSuite
mvn -pl testing verify -Pintegration-tests -Dit.test=BreakpointTests
mvn -pl testing verify -Pintegration-tests -Dit.test=BreakpointTests#conditionalStopsOnlyOnMatch

:: Claude end to end (uses Claude usage): one scenario, with the session report; or the basic Haiku check
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#springBootWrongQuote -Dclaude.approved=true -Dfixture.post.approved=true -Dclaude.report=true
mvn -pl testing verify -Pintegration-tests -Dit.test=ClaudeAgentEndToEndTests#junitWrongDiscount -Dclaude.approved=true -Dclaude.model=haiku
```

**Or in the IDE:** right-click `RegressionSuite` (or any test class or method), Run As, JUnit Test. Approvals go into
the run configuration's VM arguments (`-Dfixture.post.approved=true`); without them those tests show as skipped.

| Option (`-D...`) | Meaning |
|---|---|
| `fixture.post.approved=true` | Allows HTTP requests to the local test Spring Boot application. Without it the `SpringBootHttpTests` and the HTTP Claude tests are **skipped**, with this reason. |
| `claude.approved=true` | Allows running real Claude, which uses Claude usage. Without it `ClaudeAgentEndToEndTests` is skipped. |
| `claude.model=haiku` | The Claude model for the trial (default: the Claude CLI's default). |
| `claude.report=true` | The trial also writes the debugging session report (and keeps its events for `DevE2E report-replay`). |
| `claude.skill=false` | Claude may not use the skill (comparison run). |
| `it.test=NAME` | What to run: a suite, a class, or `Class#method`. Default: `RegressionSuite`. |

**Results:** one line per test in the output (`breakpoints/conditional-stops-only-on-match: PASS (2310 ms)`), the
standard JUnit result files in `testing/target/failsafe-reports/`, and per class a JSON report with each test's
evidence, duration and every bridge call in `evaluation/reports/<group>.json`. A Claude test passes when the automatic
verdict is PASS; transcripts and verdicts go to `evaluation/reports/claude-trials/`, and the pass-rate and cost summary
of the run is printed at the end. To repeat a trial, run the command again;
`java dev-e2e/ClaudeTranscriptEvaluator.java --summary` summarizes all runs.

## How the tests are built

1. **A class is a product feature**, named after it (`BreakpointTests`, display name "Breakpoints").
2. **A test method is the expected behaviour**: `conditionalStopsOnlyOnMatch`, display name "Conditional breakpoint
   stops only when the condition matches". In reports and result lines it is written `conditional-stops-only-on-match`.
3. **Each test that changes anything runs in its own NORMAL investigation** and fails unless that investigation's
   cleanup was complete: own breakpoints gone, own JVM ended (base class `EclipseIntegrationTest`).
4. **Stops are awaited with `WAIT_FOR_STOP`**, the way the agent does it, never by polling threads. (Exception: the
   user's own program in `UserResourceSafetyTests`, which stopped before any wait, is checked with `GET_DEBUG_STATE`.)
5. **Suites** list their classes (`@Suite`, `@SelectClasses`): `RegressionSuite`, `ExtendedSuite`, `FullSuite`. The
   tests share one Eclipse and run one after the other; every class has a 6-minute limit per test (`@Timeout`), the
   Claude tests 10 minutes.

## Regression suite: `RegressionSuite` (21 tests)

Class, then each test method as it appears in reports.

| Class (report name) | Test | Checks |
|---|---|---|
| `ProgramStartTests` (**program-start**): finding and starting Java programs and JUnit tests | `finds-main-classes` | Both Spring Boot mains of the fixture project (Orders, Inventory) are listed exactly once; listing changes no session |
| | `rejects-unknown-program` | An unknown project and main class are refused; no session is started |
| | `runs-one-junit-method` | A JUnit test method is found, started alone, and stops at a breakpoint in the code it tests |
| | `rejects-unknown-junit-method` | A JUnit method that does not exist is refused; no session is started |
| `BreakpointTests` (**breakpoints**) | `conditional-stops-only-on-match` | `orderId == 1057` stops only for that order (locals checked); a second condition on the line is refused; listed with its condition |
| | `exception-stops-where-thrown` | Settings that never stop are refused; an existing one is not changed; listed as the agent's; Eclipse's blocking "Repeated exception occurrence" dialog stays off (CORE-16); the stop is `EXCEPTION` with its type in `processOrder`; the exception's message can be read |
| | `tracepoint-prints-without-stopping` | `TRACE-ORDER 1057` appears in the program output and the program runs to its end |
| `ExecutionControlTests` (**execution-control**): stepping, step filters, pause, restart | `step-into-and-return` | Step Into enters `sum`, Step Return comes back to `main`, same thread |
| | `step-over-to-next-line` | Step Over is reported as `STEP_END` on the next line; after Resume the program's end is reported promptly |
| | `step-filters-skip-jdk-code` | With filters `java.*, javax.*, sun.*`, Step Into stays in the program's code; an invalid filter is refused |
| | `pause-and-restart` | A running program reports no stop; Pause is reported as `PAUSED` on the main thread; a restart is refused for another investigation and works for the owner |
| `StopsAndVariablesTests` (**stops-and-variables**): what the agent sees when the program stops or ends | `stop-reports-location-and-variables` | One `WAIT_FOR_STOP` call returns breakpoint, location, thread, frames and local variables; the same stop is never reported twice |
| | `program-end-reported` | A program that ends without stopping is reported as `TERMINATED` well before the timeout |
| | `nested-objects-expand` | `order.customer.address` expands level by level to its value; page size 0 is refused |
| | `program-output-read` | Program output comes back with its total sizes; short output whole |
| `SpringBootHttpTests` (**spring-boot-http**): debugging a real HTTP request | `wrong-quote-value-found` | POST /orders/quote stops in `OrderService.quote` with `percent=10`, `rate=0` (the first wrong value); the response is HTTP 200 with the wrong total 120.00; the test HTTP client refuses to send without approval |
| | `validation-error-returns-400` | Quantity 0 stops where `OrderService` throws and in the controller's error handler; the response is HTTP 400 "Quantity must be positive" from this launch's URL |
| `SpringBootStartupTests` (**spring-boot-startup**): an application that fails to start | `startup-failure-detected` | The failure is seen, and the JVM is confirmed gone by its operating-system process ID |
| | `port-conflict-detected` | The same for a port that is already in use |
| `InvestigationIsolationTests` (**investigation-isolation**): one investigation never stops or changes another one's program | `borrowed-app-survives-other-finish` | B uses A's running program and finishes: A's program keeps running, and B can no longer restart it |
| | `two-apps-stay-independent` | Orders and Inventory run together for two investigations; finishing Orders leaves Inventory running |

## Extended suite: `ExtendedSuite` (22 tests)

Kept out of the default because they need a short lease, the bridge's test hook or the test's user breakpoints
and launches; run them for changes to cleanup, ownership or the bridge.

| Class (report name) | Test | Checks |
|---|---|---|
| `AbandonedSessionCleanupTests` (**abandoned-session-cleanup**): investigations an agent left open are cleaned up (5 s lease instead of 10 min) | `idle-session-cleaned-up` | Stopped, then no calls: breakpoint removed and JVM ended within 30 s; a late finish says "expired" |
| | `long-wait-keeps-session` | A 12 s `WAIT_FOR_STOP` on a 5 s lease does not expire the investigation |
| | `controlled-mode-keeps-session` | A CONTROLLED investigation expires, but its session and breakpoint are kept |
| | `agent-disconnect-cleans-up` | Through a real MCP server: a breakpoint and launch without investigationId join the open investigation; the program is confirmed stopped at the breakpoint (as when an agent crashes mid-investigation); closing the agent's connection removes the breakpoint and ends the stopped JVM |
| | `ambiguous-call-rejected` | With two investigations open, a breakpoint without investigationId is refused |
| `AgentEclipseConnectionTests` (**agent-eclipse-connection**): the link the agent's MCP server uses to send commands to Eclipse (the bridge) | `slow-command-does-not-block` | Reads answer in under 1 s while a 27 s state change runs; it times out at 25 s with an operation ID whose late result can be fetched; logged without the token |
| | `busy-command-reported` | A second state change gets `BRIDGE_BUSY` naming the running command, then succeeds |
| | `parallel-requests-succeed` | 20 parallel reads all succeed |
| | `wrong-token-rejected` | A missing or wrong token is refused |
| `UserResourceSafetyTests` (**user-resource-safety**, OPEN-02/03): the user's own breakpoints and launches are never taken over, changed or removed. Before the tests the test Eclipse gets the user's things, created the way a person creates them (test commands `TEST_USER_*`): a line breakpoint in `LongRunningApplication`, an exception breakpoint for `ArithmeticException` (caught only), a launch configuration with program and VM arguments and an environment variable, running and stopped at that breakpoint; all removed afterwards. Runs in this order; each check is recorded under the name it had in the former PowerShell harness (`testing/ownership-tests`, deleted 25 Nov) | `user-breakpoints-cannot-be-taken-over` | The agent sees the user's breakpoints as the user's: cannot add a condition, an exception breakpoint over theirs, or remove either; both keep their settings |
| | `borrowed-user-program-is-never-stopped` | Without any investigation the user's program can be neither stopped nor resumed (OPEN-40); the agent may use it inside an investigation but cannot stop or restart it; a second launch for that investigation is refused without leaving a configuration; the program is still stopped afterwards |
| | `cleanup-removes-only-the-agents-own-things` | Finishing removes the agent's breakpoint and ends its program; a breakpoint the user set where the agent's was survives; the user's program keeps running; another investigation could not resume it; a finished ID is refused |
| | `invalid-requests-are-refused` | Invalid mode, unknown JUnit method, invalid variable path, page over 50, malformed or too many step filters: refused; the agent's own program is still resumed without the investigation ID (OPEN-40) |
| | `same-name-programs-are-never-guessed` | Two runs of the user's configuration: borrowing by that name and reading its state are refused, never guessed |
| | `stale-investigation-cannot-control-new-run` | After the user ended and restarted their program, an investigation that used the old run can neither pause nor finish against the new one |
| | `controlled-investigation-keeps-its-program` | A CONTROLLED investigation keeps its stopped program and breakpoint at the finish (removed by the test afterwards) |
| | `user-settings-are-unchanged` | After all of it: the user's breakpoints and launch configuration (main class, program and VM arguments, environment) unchanged, their program still running |
| `BuildCheckTests` (**build-check**, OPEN-34): a project that cannot run as written is refused before the start. Breaks and repairs the test workspace's copy of `phase-one-debugging` (`examples/` untouched) | `compile-error-refuses-start` | With a compile error added (`BrokenOnPurpose.java`), starting a program is refused, naming `BrokenOnPurpose.java:5`; no configuration is created; after the file is removed the same start works |
| | `missing-class-refuses-start` | With a compiled class deleted behind Eclipse's back, the start is refused with "is not built"; after a build it works |
| `JUnitResultsTests` (**junit-results**, OPEN-25): the agent reads a JUnit run's outcome as Eclipse's JUnit view shows it. Uses the fixture's `OrderServiceTest` (`quotesFullPrice` passes, `calculatesDiscount` fails: expected 108.00, was 120.00) | `run-mode-reports-the-failure` | Started with mode RUN and a breakpoint set: the run is not stopped, finishes, and `GET_JUNIT_RESULTS` gives one FAILED test with expected 108, actual 120 and the location `OrderServiceTest.calculatesDiscount:` + the assertion's line; then the debug run in the same investigation is allowed and stops at the breakpoint |
| | `whole-class-counts-each-test` | The whole class in RUN mode: total 2, passed 1, failed 1; the failed test listed first |
| | `debug-mode-result-after-resume` | In debug mode, stopped at the breakpoint: not finished, note "still in progress"; after removing the breakpoint and resuming, `WAIT_FOR_STOP` ends with TERMINATED and carries `testResult` (FAILED, first failure at `OrderServiceTest.calculatesDiscount:`...); `GET_JUNIT_RESULTS` says FAILED too; then a second debug run in the same investigation is allowed (the first has ended) and stops at the breakpoint |

## Claude end to end: `ClaudeAgentEndToEndTests` (uses Claude usage)

**Real Claude Code debugs a planted bug** with this tool and the skill, from the user's prompt to its final answer,
and is graded automatically (right value found, cleanup, report path). Needs `-Dclaude.approved=true`; never part of
a suite.

| Test | The planted bug | Trial file names |
|---|---|---|
| `springBootWrongQuote` | Quote endpoint returns 120.00 instead of 108.00 | `boot-quote-*` |
| `junitWrongDiscount` | A discount JUnit test of the Spring Boot fixture fails; debug that one test method | `junit-*` |
| `springBootValidation400` | An invalid quantity gets HTTP 400; find the application code where it starts | `validation-*` |

The transcript and verdict files keep the trials' short names (right column), so earlier results stay comparable.

## What changed on 25 Nov 2026, and why

The groups were named after when they were written (`phase1`, `phase2`, `acceptance`, `boot-gaps`), not after what
they test. Regrouped by feature; duplicates removed; scenarios with the same setup merged; regression 23 → 21
scenarios, full 38 → 30, Spring Boot starts per regression 11 → 7. The two checks that were only in the opt-in stop
group now run in every regression: `WAIT_FOR_STOP` (the tool Claude uses most) and the blocking-dialog check (CORE-16).

| Before | Now |
|---|---|
| `boot/discovery` | removed: inside `program-start/finds-main-classes` |
| `boot/readiness` | removed: every Spring Boot scenario waits for readiness |
| `phase2/ownership` | removed: same checks as `investigation-isolation/borrowed-app-survives-other-finish`, on Spring Boot (slower); its unapproved-request check moved into `spring-boot-http/wrong-quote-value-found` |
| `boot-gaps/owned-isolation` | removed: mirror image of `two-services` (same two applications, finished in the other order) |
| `boot-gaps/readiness-timeout` | removed: it tested the test application's output, not the product (no readiness command exists); flaky on 25 Nov 14:28 |
| `phase1/exception` + `stops/exception` | `breakpoints/exception-stops-where-thrown` |
| `phase1/pause-restart` + `stops/pause` | `execution-control/pause-and-restart` |
| `acceptance/sb09-borrowed-session` + `sb09-expired-owner` | `investigation-isolation/borrowed-app-survives-other-finish` |
| `boot-gaps/two-mains` | `program-start/finds-main-classes` |
| `acceptance/sb01-target-selection` | `program-start/rejects-unknown-program` |
| `phase1/junit` | `program-start/runs-one-junit-method` |
| `boot-gaps/junit-invalid-method` | `program-start/rejects-unknown-junit-method` |
| `phase1/conditional`, `phase1/trace` | `breakpoints/conditional-stops-only-on-match`, `breakpoints/tracepoint-prints-without-stopping` |
| `acceptance/core-step-into-out`, `stops/step`, `phase1/filters` | `execution-control/step-into-and-return`, `step-over-to-next-line`, `step-filters-skip-jdk-code` |
| `stops/breakpoint`, `stops/terminated`, `phase1/variables`, `stops/output` | `stops-and-variables/stop-reports-location-and-variables`, `program-end-reported`, `nested-objects-expand`, `program-output-read` |
| `boot-quote/quote`, `phase2/validation` | `spring-boot-http/wrong-quote-value-found`, `validation-error-returns-400` |
| `boot-gaps/startup-failure`, `boot-gaps/port-conflict` | `spring-boot-startup/startup-failure-detected`, `port-conflict-detected` |
| `boot-gaps/two-services` | `investigation-isolation/two-apps-stay-independent` |
| `lease/*` (5) | `abandoned-session-cleanup/*` (same five, renamed) |
| `bridge/*` (4) | `agent-eclipse-connection/*` (same four, renamed) |
| `claude/boot-quote`, `claude/junit`, `claude/validation` | `ClaudeAgentEndToEndTests`: `springBootWrongQuote`, `junitWrongDiscount`, `springBootValidation400` |

Kept separate on purpose: scenarios that would share a setup but test different things (`step-over-to-next-line`
ends after a step, `program-end-reported` has no stop at all; the two HTTP scenarios each match a Claude scenario;
`finds-main-classes` and `rejects-unknown-program` take under a second and a separate result says which part broke).

Later the same day the groups became JUnit 5 classes (`DevE2E test` and its options `--group`, `--suite`, `--tests`,
`--approve-*`, `--model`, `--repeat`, `--no-skill`, `--report` were replaced by the Maven command and the `-D` options
above), and every stop wait switched from polling GET_THREADS/GET_STACK to `WAIT_FOR_STOP`.
