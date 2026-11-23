# Java E2E integration package — safe extraction

**This ZIP is a complete *integration overlay*, not a complete Git checkout.** Do not delete any existing repository files or folders before extracting it. Doing so would remove the plugin, Maven modules, documentation, fixtures, and historical tests not in this ZIP.

Source baseline: `j2eeexpert2015/agentic-debugger-for-eclipse`, GitHub main commit `1e6e88ee8f2cd7a1abda4b4c08e4dc54352e1a57` (23 November 2026). Review changes if your checkout has moved beyond this commit.

1. Keep the existing repo at `C:\All_GitHub_Desktop\agentic-debugger-for-eclipse` intact. Make a Git commit or backup of any uncommitted changes before extracting.
2. Extract this ZIP **into that existing repo root**, preserving the relative paths and overwriting the matching files. **Do not delete the old checkout.** The ZIP is not intended for an empty directory.
3. In `dev-e2e/e2e.properties`, verify the repository and Eclipse locations, workspace, and port. This local properties file is ignored by the included `.gitignore`; the shareable template is `dev-e2e/e2e.properties.example`.
4. Run from the repository root in CMD:

```cmd
cd /d C:\All_GitHub_Desktop\agentic-debugger-for-eclipse
java dev-e2e/DevE2E.java integrate
java dev-e2e/DevE2E.java test --approve-fixture-post
```

The `test` command compiles the Java verifiers and automatically starts a dedicated Eclipse instance if it is not already running; it does not deploy/rebuild the product. If the installed Eclipse plugin is not current, close that test Eclipse instance and run `java dev-e2e/DevE2E.java all --approve-fixture-post` instead. The runner will refuse to deploy to an open Eclipse installation and never forcibly closes it.

To run selected suites:

```cmd
java dev-e2e/DevE2E.java test --groups phase1,boot
java dev-e2e/DevE2E.java test --tests phase1:trace,phase2:ownership --approve-fixture-post
```

Review `evaluation/reports/*.json`. Only after BOTH Java Phase 2 validation and ownership tests pass and their cleanup is confirmed, optionally remove the obsolete PowerShell HTTP fixture client:

```cmd
java dev-e2e/DevE2E.java cleanup
java dev-e2e/DevE2E.java test --approve-fixture-post
```

**Cleanup intentionally retains all other PowerShell scripts.** They still support distinct Windows/skill and historical test workflows. No Python files, Python commands, or Python runtime are required by this kit. This artifact has passed static/package checks; live Eclipse tests on this specific overlay still have to run on your machine.

Afterward run `git status`, review additions/updates and any deletion caused by the optional cleanup, and commit. Do not commit your local `dev-e2e/e2e.properties`; the `.gitignore` excludes it.
