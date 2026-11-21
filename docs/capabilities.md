# Implemented Capabilities and Verification

[Documentation index](../README.md#documentation) · [Roadmap](feature-roadmap.md) · [Evaluation](evaluation-plan.md)

## Meaning of status

- **Implemented / source review:** functionality is present in the supplied v1.4 repository archive; not a claim that every branch passed live testing.
- **E2E verified previously:** an earlier installed-plugin run exercised the relevant workflow; verify the exact packaged version before release.
- **Not verified:** available source or descriptions do not prove the complete behavior.
- **Not implemented / unknown:** do not advertise as supported until a source inspection and test confirms it.

The v1.4 archive is the historical baseline. The current installed candidate is `1.5.0.20261121184224374`. [Same-build evidence and remaining work](phase1-implementation-status.md)

## Feature inventory

| ID | Capability | What it provides | Status and evidence boundary | Validation to maintain |
|---|---|---|---|---|
| CORE-01 | Eclipse–MCP bridge | Connect external MCP server to running Eclipse debugger using local authenticated bridge | Installed bridge returned PONG | Connection, auth failure, multiple-instance conflict |
| CORE-02 | Java launch discovery | Identify runnable Java main applications without manually entering fully qualified names | Java and JUnit fixture discovery passed | Single-main, multi-main and ambiguous projects |
| CORE-03 | Application launch | Reuse or create standard Java launch configuration and start debugging | Owned and reused launches exercised live | New vs reused configuration, invalid class |
| CORE-04 | Line breakpoints | Add, list and remove source-line breakpoints | Live marker creation, collision and cleanup checks passed | Hit expected line; preserve user-owned breakpoints |
| CORE-05 | Execution control | Resume and step using Eclipse JDT debug state | Installed calculator step-over and direct execution checks passed | Suspend/step into/over/out, ended session, timeout |
| CORE-06 | Runtime inspection | Inspect debug session, frames and variable values | Live stack and variable assertions passed | Verify exact values on a suspended JVM |
| CORE-07 | Source navigation | Select debugger session and open suspended source in Eclipse | Navigation request verified in the calculator regression. Visible editor selection still needs GUI verification | Two simultaneous sessions; editor and line |
| CORE-08 | Investigation lifecycle | Begin/finish using `NORMAL` or `CONTROLLED` and investigation ID | 32 live ownership and failure checks passed | Cleanup/preservation; ownership, reused sessions |
| CORE-09 | Managed Claude registration | Register MCP integration while preserving unrelated entries | Managed-registration changes still need an installed test covering unrelated entries | Add/verify/remove with existing registrations |
| CORE-10 | Installed binary integrity | Compare built/debugger classes against installed Eclipse copy | Installed class hashes matched compiled and packaged classes | Detect stale plugin despite successful build |
| CORE-11 | Windows deployment | Build and install p2 update using developer script | Fresh deployment and repeat deployment passed in an isolated Windows installation | Disposable Eclipse install/uninstall/reinstall |
| CORE-12 | Optional debugging skill | Guide agent strategy and tool selection | **Not implemented** | A/B results; zero core dependency |

## Phase 1 installed verification

All seven direct bridge scenarios and all seven explicit Claude scenarios passed on the candidate. They covered conditional and exception breakpoints, individual JUnit launches, nested variables, tracepoints, owned restart and step filters. The symptom-only trials separately measured Claude’s choice of tools. [Per-feature evidence](phase1-implementation-status.md) · [Symptom-prompt outcomes](phase1-claude-scenarios.md#symptom-prompt-results-22-november-2026)

Remote JVM attach and data watchpoints remain deferred. [Roadmap](feature-roadmap.md)

## Core regression invariant

All CORE-01 through CORE-11 capabilities must remain functional **with no skill installed**. Skill updates must not change API contracts, session ownership, or cleanup policy; those guarantees belong to the plugin and MCP server.
