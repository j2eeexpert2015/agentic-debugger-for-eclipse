# Installing the development workflow documentation

1. Extract the ZIP in the root of `agentic-debugger-for-eclipse`. It adds `AGENTS.md`, `docs/development-workflow.md`, and `maintenance/InstallDevelopmentWorkflow.java`. It does **not** overwrite `README.md` directly.
2. In a CMD terminal at the repository root, preview:

   ```cmd
   java maintenance/InstallDevelopmentWorkflow.java
   ```
3. Apply:

   ```cmd
   java maintenance/InstallDevelopmentWorkflow.java --apply
   ```
4. Check idempotence by repeating `--apply` (expected `UNCHANGED`), and review:

   ```cmd
   git diff --check
   git diff -- README.md
   git status --short
   ```

The updater backs up the original README to ignored `evaluation/reports/development-workflow-backups/`. It preserves existing README paragraphs and status text and adds only a short section plus two links. **No Eclipse restart, Maven rebuild, Claude use, configuration change, or HTTP approval is required.** Do not run an unnecessary 23-scenario or paid Claude trial for docs-only changes.

If a future README has changed structurally, the updater refuses rather than guessing where to insert text. Review the current document and adjust the integration deliberately; do not force it.

After reviewing and committing the new docs, this one-time updater can be removed in a separate, explicit hygiene change if desired. Do not remove still-needed maintenance/test tools in the same step.
