# Apply agent-onboarding finalization

Extract this ZIP into your `agentic-debugger-for-eclipse` repository, retaining paths.

From repository root in Windows CMD:

```cmd
java maintenance/InstallAgentHandoff.java
java maintenance/InstallAgentHandoff.java --apply
java maintenance/InstallAgentHandoff.java --apply
git diff --check
git diff -- AGENTS.md README.md docs/development-workflow.md
git status --short
```

The first command is **dry run**; the second applies; the third must report `UNCHANGED`. Review and commit the three updated Markdown files and `docs/current-state.md`. `maintenance/InstallAgentHandoff.java` is a one-time migration utility: it may be left out of the Git commit after applying, or retained if needed for reproducibility. Backups are saved under ignored `evaluation/reports/agent-handoff-backups/`. No debugger source, Eclipse settings, Claude registration or test fixture is changed; no rebuild or paid Claude run is required.
