# Handoff for a new chat (25 Nov 2026)

Read this first, then the README section **Status and tracking**, which is the single tracking list. This file only
says where the work stands and how we work; details live in the README and the documents it links.

## How we work (the owner's rules)

- **Complete files only**, never git patches. Several files: a zip in repository structure, extracted over the
  repository root (`powershell -Command "Expand-Archive -Force -Path '%USERPROFILE%\Downloads\<zip>' -DestinationPath ."`).
  One file: its exact destination and a `copy /Y` command.
- **Every change listed:** file, place in it, before and after, and why.
- **The assistant does not run builds or tests.** Compiling against stand-ins and running plain unit tests in its own
  copy is fine; the owner runs `mvn clean package`, the regression and the Claude trial and sends the output.
- **Exact commands**, plain Windows `cmd`, no git commands (the owner uses GitHub Desktop). Deletions with
  `del /q` and `rmdir /s /q`. All commands of one round in one block chained with `&&` (the owner pastes them at once).
- **Always a complete, consistent set of files**, so a delivery never depends on an earlier one that was not applied.
- `examples/` stays unchanged. The README "Status and tracking" table is the only tracking list.
- Machines: main checkout `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse` (Windows, JDK 25, Eclipse 2026-09,
  Tycho 5.0.4); a second machine at `C:\All_GitHub_Desktop_Projects\...` (sync by commit and push).

## The standard check

Close Eclipse first, then:

```
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse ^
 && mvn clean package ^
 && java dev-e2e/DevE2E.java deploy --suite regression --approve-fixture-post ^
 && java dev-e2e/DevE2E.java test --tests claude:boot-quote --approve-claude --approve-fixture-post --report
```

Expected now: BUILD SUCCESS with unit tests `report` 40, `mcp-server` 26, `testing` 18, `eclipse-plugin` 48;
regression 23/23; the trial `VERDICT PASS` and `REPORT CHECK PASS`.

## Where things stand

**Modules** (7 in the reactor): `report` (events, isolation, HTML report writer, the Agent Activity store and page;
no dependencies; embedded in the plugin as `lib/report.jar`), `mcp-server`, `testing` (grader, the direct bridge
verifiers in package `e2e`, the ownership harness in `ownership-tests/`), `eclipse-plugin`, `eclipse-feature`,
`eclipse-update-site`, the parent `pom.xml`. `dev-e2e/` holds `DevE2E` (build, deploy, test runs) and the Claude trial.

**OPEN-38 codebase reduction** ([analysis](codebase-reduction-analysis.md)):
- Steps 1 to 3 done and checked (unused files, documents, one JSON reader, no legacy bridge).
- Step 4 (report: one module, our own HTML writer, ChainTest gone), step 5 (installer removed, test commands only in a
  `DevE2E` Eclipse) and step 6 (reduced: `e2e-runner` merged into `testing`) were built successfully on 25 Nov with the
  expected test counts. The first regression after step 6 failed only because two verifiers still pointed to
  `e2e-runner/target/phase2-classes`; fixed in `PhaseTwoBridgeVerifier` and `SpringBootQuoteVerifier`.
  **Pending:** the standard check with that fix. When it passes, mark steps 4 to 6 done in the README OPEN-38 row and
  in the analysis document, and count the files.
- Not done on purpose: one runner with scenario classes (most verifiers already share `PhaseOneBridgeVerifier.Bridge`
  and `.Json`; a rewrite only the live regression can check costs more than it saves).

**OPEN-36 Agent Activity** (live view of what the agent does):
- The page shows the steps in time order (running step red and breathing, done green), the pinned banner with a
  blinking dot, the current stop with call chain and variables, Follow, Expand All, Collapse All, Open HTML Report,
  clickable source references. The same page is the HTML report (`ActivityPage.report`).
- New on 25 Nov, built but **not yet looked at by the owner**: Agent Activity opens in a window of its own
  (`ActivityWindow`, belongs to the Eclipse window, not to a perspective, so the switch to Debug cannot hide it;
  remembers its place); the docked view stays available (Preferences > Agentic Debugger > "Show Agent Activity in its
  own window" off). And the terminal views are carried into the Debug perspective (`TerminalCarrier`; same sessions,
  nothing chosen or started). Manual check for the terminal: Java perspective with a Terminal view open (any shell),
  run the trial, the Terminal must be there after the switch to Debug, below the Debug view.

**Ideas agreed but not started:**
- A report and view organised by program flow instead of tool calls, like the IntelliJ investigation page the owner
  showed: stops as the nodes (location, reason, variables, expressions, the calls as chips), a value tracker that turns
  red at the first mismatch, OBSERVED and INFERRED tags, Previous/Next/Replay in the HTML report, a root-cause box.
  Built from recorded data; the agent's reasoning through new optional tool arguments (`purpose`) and structured fields
  on `finish_debug_investigation` (tracked value, findings, root cause, inferred, fix). The owner asked to see the HTML
  report version first or the live view first: not yet answered.

**Other open items** (details in the README table): OPEN-35 manual check, OPEN-37 (report: breakpoints set versus hit),
OPEN-30 R2b/R3, OPEN-03 ownership harness by hand (`testing/ownership-tests/run.ps1` after a deploy, Eclipse closed),
OPEN-22/23, OPEN-31 to OPEN-34, and the older test items. `examples/README.md` has two dead links to deleted documents;
left unchanged by the rule above until the owner decides.
