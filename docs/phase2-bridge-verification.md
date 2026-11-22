# Phase 2 bridge verification — first P2-06 implementation

[README](../README.md) · [Spring Boot scenario catalog](spring-boot-debugging-scenarios.md) · [Phase 2 audit](spring-boot-tool-audit.md)

## Purpose and scope

This **new, opt-in Java verifier** covers two previously outstanding deterministic cases using existing debugger operations, the disposable `spring-boot-debugging` fixture and the already existing strict PowerShell HTTP client. It does **not** invoke Claude and does **not** replace the full SB-01–SB-10 acceptance matrix.

- **SB-08 / `validation`:** create investigation-owned service throw-site and controller handler breakpoints, start the Boot fixture, require actual readiness, send a single approved invalid-quantity POST using the existing fixture client, inspect `quantity=0` in the suspended service frame, resume to the exception handler, and assert real HTTP 400 and error text. NORMAL cleanup is required.
- **SB-09 / `ownership`:** start an investigation-owned fixture JVM, borrow its existing session with another investigation, finish the borrower without terminating the owner's JVM, reject a finished investigation's restart attempt, and prove that the fixture client refuses a POST without explicit approval. NORMAL cleanup is required.

## Safety and prerequisites

Use a **disposable Windows Eclipse installation and workspace**, with JDT/JUnit and the imported standalone `examples/spring-boot-debugging` Maven fixture. Run the Eclipse bridge on a **nondefault** port, for example `21765`, and close other Eclipse instances that could own the same port. The verifier rejects port `8765`, checks the bridge's actual workspace, checks the fixture main is discoverable, and refuses to interact with a reused preexisting launch. Each scenario records a separate investigation ID and performs best-effort cleanup even on failure.

HTTP requests require an explicit opt-in flag. The HTTP client also verifies the exact launch, workspace, local listening PID, loopback target and approved fixture endpoint; it does not follow redirects or use a proxy. Never run these tests against a real service or workspace containing unrelated user sessions.

## Running

From a Windows Command Prompt in the repository root, with the disposable Eclipse instance **already running**:

```cmd
set "AGENTDEBUG_WORKSPACE=C:\test\boot-workspace"
set "AGENTDEBUG_PORT=21765"
set "AGENTDEBUG_APPROVE_FIXTURE_POST=YES"
scripts\windows\verify-phase2-eclipse.bat
```

For one scenario, append `validation` or `ownership`. The script compiles the Java 21 verifier and existing Java bridge client using `javac`, without adding Maven dependencies or changing the main `dev.bat` workflow. The report is `evaluation/reports/phase2-live.json`; keep it uncommitted because bridge traces may include fixture runtime state. Java build compatibility can be checked on any JDK 21+ but only Windows with installed Eclipse can verify the live behavior.

## Acceptance evidence and limitations

For each case, a PASS requires successful assertions **and** NORMAL cleanup. A failure is recorded with a reason and cleanup result, and the script exits nonzero. These new tests have **not been run against installed Windows Eclipse** as part of this patch; record them as **NOT RUN** until independently executed. Do not infer Phase 2 complete from these two checks.

Still pending separately: full multi-launch/marker isolation, concurrent HTTP requests, wrong-target consent matrix beyond fixture-client checks, STS-specific launch coverage, symptom-only Claude baseline, and the overall same-build acceptance report. The previously observed intermittent process-destruction error still warrants repetition rather than an assumed fix.
