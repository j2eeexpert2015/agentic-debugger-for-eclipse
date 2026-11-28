# Continuous integration and releases (GitHub Actions)

[README](../README.md#status-and-tracking) · [Working agreement](working-agreement.md) · [Handoff](handoff.md) ·
[Release checklist](release-verification-checklist.md) · [Install guide](install-from-github-releases.md)

Built on branch `ci/github-actions` (27-28 Nov, Windows first), merged into `main` on 28 Nov (README OPEN-54..56).
GitHub's standard machines are free for this public repository.

## What runs when

| Event | Workflow | What happens |
|---|---|---|
| Push to `main` or `ci/**`, pull request to `main` | `release-readiness.yml` "CI and release" | Five stages, one after another (below). Nothing is published. A newer push to the same branch replaces a running check. |
| Actions → "CI and release" → **Run workflow** on `main`, `create_draft` ticked, tag `v1.0.0-preview.1` | same | The five stages again for that commit, then stage 6: a **draft** GitHub Release with the tested zip, `SHA256SUMS.txt` and the notes. Never cancelled by a push. |
| A release is **published** on the Releases page (or Run workflow) | `update-site-pages.yml` "Update site (GitHub Pages)" | Builds the Eclipse update site from every published release and puts it on GitHub Pages. |

The five stages (`release-readiness.yml` calls the others):

1. `ci.yml`: build and unit tests, Windows, JDK 21 and 25; a test-count table on the run page.
2. `integration-windows.yml`: Eclipse 2025-12 downloaded (cached), `DevE2E deploy`, `FullSuite` (at least 43 tests) on
   a fresh Windows machine; logs and screenshots kept.
3. `zip-install-windows.yml`: install the built update-site zip into a clean Eclipse, uninstall, reinstall,
   `RegressionSuite` again.
4. `compatibility-windows.yml`: the same zip on Eclipse 2026-09, `RegressionSuite`.
5. Release candidate: the tested zip renamed with its version (`agentic-debugger-update-site-1.0.0.<qualifier>.zip`),
   its SHA-256 and notes, kept as the artifact `release-candidate-validated`.

A fresh machine for every run: no leftover breakpoints, no old window layout, nothing from earlier runs.

## Making a release

1. Code on `main`, with the pipeline green for that commit (Actions tab).
2. One version everywhere (Ayan 28 Nov 11:31): every pom `1.0.0-SNAPSHOT`, plugin MANIFEST and feature
   `1.0.0.qualifier`. Releases are **previews** for now: `v1.0.0-preview.1`, `-preview.2`, …; later `v1.0.0-rc.N`, and a
   plain `v1.0.0` only for the final release. Stage 6 refuses a tag whose version differs from the poms' or that
   already exists. Previews are marked as pre-releases.
3. GitHub → **Actions** → **CI and release** → **Run workflow**: branch `main`, tick `create_draft`, tag `v1.0.0-preview.1` (the next free number) →
   Run. Takes as long as the five stages.
4. GitHub → **Releases**: the new **draft**. Check its zip and notes, do the manual part of the
   [release checklist](release-verification-checklist.md) (part D: install the zip, a Claude Code and a Copilot run).
5. **Publish release** on the draft. That starts "Update site (GitHub Pages)"; a few minutes later the update site
   has the new version.
6. Another preview of the same version needs no change (`-preview.2`). For a new version, change all poms, the
   MANIFEST and `feature.xml` together (e.g. `1.1.0-SNAPSHOT` / `1.1.0.qualifier`), so the tag can match.

## Installing from Eclipse

- **Update site (one fixed URL):** Help → Install New Software → Add… → Location
  `https://j2eeexpert2015.github.io/agentic-debugger-for-eclipse/` → Agentic Debugger for Eclipse IDE → restart.
  Help → Check for Updates then finds later releases. The site lists every release published by this pipeline (a
  composite p2 repository, each release in `releases/<tag>/`); the early hand-made `v1.5.0-preview.1` is left out.
- **Zip:** download `agentic-debugger-update-site-*.zip` from the release's Assets; Help → Install New Software →
  Add… → Archive….

## One-time setup (repository settings)

- Settings → **Pages** → Build and deployment → Source: **GitHub Actions**. Without it the update-site workflow fails
  at "Publish on GitHub Pages".
- The release job writes with its own `contents: write` permission; nothing else to change.
- The Run workflow button appears only for workflows on the default branch (`main`).

## Reading a run

GitHub → **Actions** → the run:

- each stage's **Summary** has its table (unit tests per module; integration tests per class);
- on failure: the red step's log, and the artifacts (`test-reports-...`, `integration-windows-logs` with
  `screen-after-start.png`, `screen-at-end.png`, `eclipse-console.log`, `eclipse-workspace.log`, `failsafe-reports/`);
- on success: `release-candidate-validated` (zip, checksum, notes).

## Not covered yet

| Step | What | Tracking |
|---|---|---|
| 1.3 | Build and unit tests on Linux and macOS (new matrix rows in `ci.yml`) | OPEN-54 |
| 1.4 | Copilot launcher on Linux/macOS once CP-U7 passes there | OPEN-54 |
| 2.2 | Integration tests on Linux (Xvfb), then macOS | OPEN-55 |
| — | Signing the plugin (certificate as a GitHub secret) | OPEN-45 |
| — | Eclipse Marketplace entry | later |
