# Eclipse debugger skill automation — companion patch

This is an additive Windows PowerShell 5.1-compatible harness for `j2eeexpert2015/agentic-debugger-for-eclipse`. It does **not** rebuild the existing Eclipse debugger or skill, and it does **not** claim the local Eclipse/Claude tests have run. Existing repository contents are preserved.

## Installation

Copy the two files under `scripts/windows` and the JSON file under `evaluation/skill-automation` into the corresponding directories at the root of your GitHub repository. Do not replace any files with the same names without reviewing diffs. Nothing in this package modifies the Eclipse plugin or its skill.

## Recommended usage (Windows CMD, at repository root)

```
scripts\windows\test-skill.bat -Mode selftest
scripts\windows\test-skill.bat -Mode preflight
scripts\windows\test-skill.bat -Mode smoke -DryRun
scripts\windows\test-skill.bat -Mode smoke
```

`smoke` runs non-mutating automatic activation, non-debug request, consent refusal and unsupported-feature tests. A `WITH_SKILL` run requires the existing repository skill installed under `%USERPROFILE%\.claude\skills\eclipse-debugger`. `WITHOUT_SKILL` disables slash commands/skills for that subprocess using Claude's `--disable-slash-commands`. It does not remove or alter the installed skill. Verify Claude CLI support for that flag in `claude --help`.

Real debugging requires an **isolated, disposable Eclipse workspace and approved test fixture**. After confirming this manually:

```
scripts\windows\test-skill.bat -Mode selected -Scenario T-03,T-04 -AllowDisposableEclipse
```

The T-05 fixture POST must be explicitly approved with `-ApproveFixturePost` and only used after verifying the exact localhost fixture target. **This flag records consent to the specified fixture request; it does not enforce HTTP target restrictions on Claude.** For a truly unattended run, put process/network sandbox constraints outside the model.

Initial comparison (after reviewing prompts and isolating fixtures):

```
scripts\windows\test-skill.bat -Mode compare -Scenario T-03,T-04 -AllowDisposableEclipse -Repeats 5 -Model sonnet
```

The compare command does **not** reset the Eclipse fixture between trials. Therefore identical-condition A/B reports are exploratory until environment reset/isolation is added and independently verified. Do not claim valid controlled results from a repeated run without reset. Do not run T-09 against user-owned sessions. Do not run T-11 without an isolated disconnected bridge.

## Output and truthful evidence levels

Each run creates `evaluation/reports/skill-automation/<timestamp-id>/` containing `manifest.json`, `preflight.json`, `trial-results.csv`, `comparison.md`, each Claude `*.jsonl` stream and `*.analysis.json`. These can contain sensitive source or variable content; review/redact before sharing or committing. Keep report directories ignored by Git.

* Non-invasive checks can receive PASS/FAIL based on tool-call evidence.
* Automatic skill activation requires an actual `Skill` tool event; generic answers are `NOT_CONFIRMED`.
* Live Java/JUnit/Boot cases are never automatically promoted to PASS based on a plausible answer. They are PARTIAL until independently checked against actual debugger state/fixture oracle.
* The existing `verify-phase1-eclipse.bat` and `verify-phase2-eclipse.bat` should remain your deterministic debugger tests. Do not conflate their direct bridge pass with Claude skill behavior.

## Limitations that must be solved before unattended full acceptance

1. Validate the actual installed Claude CLI flags/permissions and MCP tool prefix. Local custom policy can deny MCP calls in headless mode.
2. Confirm `Skill` tool-use events are exposed by your Claude Code version; if direct slash invocation in `-p` is text-only, run T-00 interactively once and save evidence.
3. Add an isolated Eclipse fixture reset/setup protocol between trials, plus a JVM-state/HTTP oracle. Do not terminate existing developer sessions to make tests pass.
4. Run a disconnected bridge test in a separately managed environment rather than stopping your primary Eclipse instance.
5. Protect the host with process and network restrictions for unattended side-effect tests. The PowerShell flags alone are not a security sandbox.

This is **stage one**: an actual test-invocation/log-collection harness and honest preliminary grader. The full deterministic acceptance/isolated A/B runner remains to be implemented and live-validated.
