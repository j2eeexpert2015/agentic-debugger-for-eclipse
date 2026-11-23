# Apply and verify the Claude evidence gate (Java 21)

This package adds **three new files**, without modifying existing source, Eclipse plugin, MCP server or global Claude registration:

- `dev-e2e/ClaudeEvidenceGate.java` — opt-in wrapper + offline grader + read-only before/after bridge inventory.
- `dev-e2e/ClaudeEvidenceGateSelfTest.java` — deterministic Java-only synthetic tests.
- `docs/claude-evidence-gate.md` — full commands, evidence limits and rollback.

From repository root (`C:\All_GitHub_Desktop\agentic-debugger-for-eclipse` on your Windows host):

1. Extract the ZIP, preserving repository-relative directory paths. Inspect `git status --short`; there should be three new files. These are additive, not replacements.
2. Confirm Java 21+ and run the offline self-test:

   ```cmd
   if not exist "%TEMP%\agentdebug-evidence-test" mkdir "%TEMP%\agentdebug-evidence-test"
   javac --release 21 -d "%TEMP%\agentdebug-evidence-test" dev-e2e\ClaudeEvidenceGate.java dev-e2e\ClaudeEvidenceGateSelfTest.java
   java -cp "%TEMP%\agentdebug-evidence-test" ClaudeEvidenceGateSelfTest
   ```

3. Reconfirm the **first priority**: with the dedicated Eclipse instance running (or available for auto-start), execute:

   ```cmd
   java dev-e2e/DevE2E.java test --approve-fixture-post
   ```

   This runs 23 **direct** Java scenarios, not Claude. Save the output and six `evaluation/reports/*-java-live.json` files. A successful old baseline is not proof that the latest installed build passes uninterrupted.
4. Only **after the direct regression is green** and if you deliberately authorize a new paid trial, run:

   ```cmd
   java dev-e2e/ClaudeEvidenceGate.java run --scenario boot-quote --approve-claude --approve-fixture-post
   ```

   It handles before/after snapshots and reviews its own new transcript. If you want to evaluate the old Boot quote transcript with **no token cost** (and no independent historical snapshots), use `grade` mode from the linked documentation; expect PARTIAL due to missing independent inventories, even if event-level debugging evidence is complete.
5. Review `evaluation/reports/claude-trials/*.evidence-verdict.json`, the matching `.before.json` and `.after.json`, and `git diff --check`. Do not commit generated reports or credentials. Commit only intended source/docs after review.

**Rollback:** remove these three new source/doc files. No installed plugin or Claude MCP configuration was modified. All existing raw evidence is retained.

**Not performed in this environment:** the actual Windows/Eclipse uninterrupted 23-case regression or live MCP inventory; they require your running dedicated Eclipse instance. Offline Java 21 compile/tests and replay of the provided Boot quote and validation event transcripts were performed.
