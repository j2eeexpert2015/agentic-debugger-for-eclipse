# External Research and Feature Mapping

[Documentation index](../README.md#documentation) · [Roadmap](feature-roadmap.md) · [Evaluation](evaluation-plan.md)

**Purpose:** explain what each external project actually offers, why its ideas matter, whether equivalent capabilities exist here, and what remains unknown. This is research, **not** a statement of feature parity. URLs checked November 21, 2026; upstream functionality may change. No third-party skill text has been copied.

## RS-01 — Microsoft DebugMCP `debug-live` skill

- **Source:** [skill](https://github.com/microsoft/DebugMCP/blob/main/skills/debug-live/SKILL.md) · [repository](https://github.com/microsoft/DebugMCP)
- **What it is:** a separate agent skill providing procedural guidance for interactive VS Code debugging, distinct from the MCP debugger tools themselves.
- **Value:** guide agents to collect runtime evidence, choose breakpoints, trace values, handle short-lived programs and minimize unproductive stepping.
- **Our status:** **No equivalent installed skill yet**. Basic MCP breakpoints and inspection exist, but systematic guidance and measurable automatic selection do not.
- **Can we implement?** Independent original skill is plausible. Actual activation, effectiveness and license obligations for any reused text still require verification.
- **Track:** SKILL-01/02/03; EV-01/02/07/09/10. **Important:** do not copy blanket “remove all breakpoints” guidance; only investigation-owned resources may be cleaned up.

## RS-02 — IntelliJ IDEA `ij-debugger`

- **Source:** [JetBrains agentic debugging documentation](https://www.jetbrains.com/help/idea/agentic-debugging.html) · [JetBrains agent skills overview](https://blog.jetbrains.com/idea/2026/08/agent-skills-in-intellij-idea/)
- **What it is:** IDE-integrated MCP debugger access plus a bundled skill teaching agents how to operate it; JetBrains documentation describes agent-initiated debugger workflows and debugger tools.
- **Value:** the agent can move beyond source-only reasoning to actual state inspection using its IDE.
- **Our status:** core Java launch/breakpoint/step/inspect capability exists; **equivalent skill does not**. No verified feature parity for conditional/exception breakpoints, logpoints, JUnit or other advanced functions.
- **Can we implement?** General workflow is plausible; any individual capability requires an Eclipse API and MCP exposure assessment. Exact bundled skill contents and copying rights are not yet verified.
- **Track:** SKILL-01/02; JAVA-01/02/05.

## RS-03 — Microsoft DebugMCP debugger implementation

- **Source:** [DebugMCP repository](https://github.com/microsoft/DebugMCP) · [server design](https://github.com/microsoft/DebugMCP/blob/main/docs/architecture/debugMCPServer.md)
- **What it is:** an MCP bridge exposing debugger operations to coding agents in VS Code. It is **not the same thing** as the `debug-live` skill.
- **Value:** concrete API/UX ideas for launch, breakpoint management, inspection, stepping, and client integration.
- **Our status:** basic equivalent operations exist; advanced per-feature parity remains unverified. Different platform and tool contracts.
- **Can we implement?** Requires assessment per tool, not direct copying of its VS Code implementation.
- **Track:** JAVA-01/02/05/06 and CORE regression suite.

## RS-04 — Microsoft Java Debug Agent / VS Code Java Debugger

- **Source:** [Java Debug Agent guide](https://github.com/microsoft/vscode-java-debug/blob/main/bundled/agents/README.md) · [Java debugger repository](https://github.com/microsoft/vscode-java-debug)
- **What it is:** Java-specific AI-assisted debugging guidance and functionality integrated with VS Code/Copilot; separate from the general DebugMCP skill.
- **Value:** Java launch/test workflows, Java-specific debug operation patterns, and practical fixtures.
- **Our status:** standalone Java `main()` debugging implemented. Agent-driven individual JUnit debugging, no-config advanced Java launch and attach are **not established** in our snapshot.
- **Can we implement?** Requires per-feature feasibility spikes, especially JUnit launch handling.
- **Track:** JAVA-03; EV-03 and REG-15.

## RS-05 — Debug2Fix, Microsoft Research

- **Source:** [paper](https://arxiv.org/abs/2602.18571) · [research page](https://www.microsoft.com/en-us/research/publication/debug2fix-debug2fix-supercharging-coding-agents-with-interactive-debugging-capabilities/)
- **What it is:** research integrating interactive debugging into a coding agent through a specialized debugging subagent.
- **Value:** evaluation design and separation of interactive-debugging benefit from other agent architecture changes.
- **Our status:** **Not implemented** as an architecture; no evidence that adding a subagent is necessary for this Eclipse product.
- **Can we implement?** Technically conceivable, but **not selected**; compare simple skill guidance first.
- **Track:** SKILL-04; EV-02/05.

## RS-06 — Controlled evaluations of debugger utility

- **Source:** [Pdb or Not to Be (arXiv 2611.08666)](https://arxiv.org/abs/2611.08666)
- **What it is:** a study comparing debugging interfaces for coding agents, including the possibility that more debugging tools do not improve outcomes in a given setup.
- **Value:** avoid equating “more debugger calls” with “better defect resolution”; compare accuracy, evidence, time and resource cost.
- **Our status:** comparison framework still needs implementation.
- **Track:** SKILL-04; EV-09 (avoid unnecessary debugging).

## RS-07 — Real-world tool and skill loading failures

- **Source:** [DebugMCP skill discovery issue #105](https://github.com/microsoft/DebugMCP/issues/105) · [DebugMCP issues](https://github.com/microsoft/DebugMCP/issues)
- **What it is:** maintainer/user reports, not proof of frequency or general behavior.
- **Value:** tests should distinguish installed skill from skill actually loaded, and assess wrong-session selection and stalled waits.
- **Our status:** no automatic skill discovery tests; current E2E has bridge preflight and separate launch state validation.
- **Track:** EV-01/06/07 and skill activation telemetry.

## Future sources

Add a new RS-ID only for sources with identifiable provenance and concrete relevance. Record: title, URL, date checked, source type (docs/code/issue/paper/forum), claim actually supported, idea, feature ID, feasibility, evidence gap, evaluation case and disposition. Never equate forum anecdotes with verified implementation.
