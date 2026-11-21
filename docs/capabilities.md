# Implemented Capabilities and Verification

[Documentation index](../README.md#documentation) · [Roadmap](feature-roadmap.md) · [Evaluation](evaluation-plan.md)

## Meaning of status

- **Implemented / source review:** functionality is present in the supplied v1.4 repository archive; not a claim that every branch passed live testing.
- **E2E verified previously:** an earlier installed-plugin run exercised the relevant workflow; verify the exact packaged version before release.
- **Not verified:** available source or descriptions do not prove the complete behavior.
- **Not implemented / unknown:** do not advertise as supported until a source inspection and test confirms it.

The previous baseline was the v1.4 archive. This package also contains a **1.5.0 source candidate**; this documentation does not assert that the candidate is installed or build-verified.

## Feature inventory

| ID | Capability | What it provides | Status and evidence boundary | Validation to maintain |
|---|---|---|---|---|
| CORE-01 | Eclipse–MCP bridge | Connect external MCP server to running Eclipse debugger using local authenticated bridge | Implemented; previous PONG preflight | Connection, auth failure, multiple-instance conflict |
| CORE-02 | Java launch discovery | Identify runnable Java main applications without manually entering fully qualified names | Implemented; previous E2E discovered sample | Single-main, multi-main and ambiguous projects |
| CORE-03 | Application launch | Reuse or create standard Java launch configuration and start debugging | Implemented; previously E2E tested | New vs reused configuration, invalid class |
| CORE-04 | Line breakpoints | Add, list and remove source-line breakpoints | Implemented; previous installed-plugin preflight | Hit expected line; preserve user-owned breakpoints |
| CORE-05 | Execution control | Resume and step using Eclipse JDT debug state | Implemented; previous E2E tested step-over | Suspend/step into/over/out, ended session, timeout |
| CORE-06 | Runtime inspection | Inspect debug session, frames and variable values | Implemented; previous E2E inspected calculator state | Verify exact values on a suspended JVM |
| CORE-07 | Source navigation | Select debugger session and open suspended source in Eclipse | Implemented in source; visual GUI behavior not E2E-verified | Two simultaneous sessions; editor and line |
| CORE-08 | Investigation lifecycle | Begin/finish using `NORMAL` or `CONTROLLED` and investigation ID | Added in v1.4 source; **not live installed-E2E verified** | Cleanup/preservation; ownership, reused sessions |
| CORE-09 | Managed Claude registration | Register MCP integration while preserving unrelated entries | Implemented; source tests present; installed behavior needs validation | Add/verify/remove with existing registrations |
| CORE-10 | Installed binary integrity | Compare built/debugger classes against installed Eclipse copy | Implemented; previous installed E2E passed | Detect stale plugin despite successful build |
| CORE-11 | Windows deployment | Build and install p2 update using developer script | Script present; **end-to-end script execution not verified** | Disposable Eclipse install/uninstall/reinstall |
| CORE-12 | Optional debugging skill | Guide agent strategy and tool selection | **Not implemented** | A/B results; zero core dependency |

## Phase 1 candidate implementation (v1.5.0, unverified in installed Eclipse)

New source-level operations cover conditional breakpoints, exception breakpoints, individual JUnit test launches, nested variable expansion, non-suspending JDT tracepoint expressions, owned-session restart, and per-target step filtering. **These are candidate implementations; no live Eclipse verification or Maven build has run in this environment.**

See [Phase 1 implementation status](phase1-implementation-status.md) for exact MCP tool names, caveats and required release gates, and [Claude prompts](phase1-claude-scenarios.md) for validation scenarios.

## Nonclaims and boundaries

We do **not** currently claim **verified installed-plugin support** for the new Phase 1 operations, JVM attach, or data watchpoints. Eclipse IDE may support some of these manually, but an IDE capability is not automatically an MCP capability. See [roadmap](feature-roadmap.md).

Successful static code analysis by Claude is **not** evidence that the debugger was invoked. The trace must show actual MCP tool calls, successful suspension, and observed values.

## Core regression invariant

All CORE-01 through CORE-11 capabilities must remain functional **with no skill installed**. Skill updates must not change API contracts, session ownership, or cleanup policy; those guarantees belong to the plugin and MCP server.
