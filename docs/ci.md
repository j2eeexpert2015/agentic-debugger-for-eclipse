# Continuous integration and releases (GitHub Actions)

[Status and tracking](../STATUS.md#status-and-tracking) · [Working agreement](working-agreement.md) · [Handoff](handoff.md) ·
[Release checklist](release-verification-checklist.md) · [Install guide](install-from-github-releases.md)

Built on branch `ci/github-actions` (27-28 Nov, Windows first), merged into `main` on 28 Nov (README OPEN-54..56).
GitHub's standard machines are free for this public repository.

## What runs when

| Event | Workflow | What happens |
|---|---|---|
| Push to a work branch (not `main`; not when only `.md` or `docs/` changed) | `tests-all-platforms.yml` "Tests on Windows, Linux and macOS" | The short check: Windows, `all-unit-tests-without-eclipse` (JDK 21 and 25) and `eclipse-plugin-ui-tests-with-temporary-eclipse`, ~15 min. Early feedback; not required. |
| Push to a `ci/…` branch | same | All three systems, all four kinds: for work on the pipelines themselves (the Run workflow button appears only once the workflow is on `main`). |
| Actions → "Tests on Windows, Linux and macOS" → **Run workflow** (any branch; choose all, linux, macos or windows) | same | Unit, UI, debugger (`FullSuite`) and install tests on the chosen systems, all at once, each job alone. For Linux/macOS from time to time. Not required. |
| Every Sunday 02:00 IST | same, on `main` | All three systems, all four kinds, so Linux/macOS breakage shows within a week. |
| Pull request into `main`, push to `main` | `release-readiness.yml` "CI and release" | Windows, the release gate: unit tests, then UI tests and the debugger stages (below). Must be green to merge. Runs for docs-only changes too (a required check that is skipped would block the merge). |
| Actions → "CI and release" → **Run workflow** on `main`, `create_draft` ticked, tag `v1.0.0-preview.1` | same | The five stages again for that commit, then stage 6: a **draft** GitHub Release with the tested zip, `SHA256SUMS.txt` and the notes. Never cancelled by a push. |
| A release is **published** on the Releases page (or Run workflow) | `update-site-pages.yml` "Update site (GitHub Pages)" | Builds the Eclipse update site from every published release and puts it on GitHub Pages. |

The five stages (`release-readiness.yml` calls the others):

1. `ci.yml`: build and unit tests, Windows, JDK 21 and 25; a test-count table on the run page. Then, side by side:
   stage 2b, the UI tests (`-Drun=eclipse-plugin-ui-tests-with-temporary-eclipse`, since 28 Nov), and stages 2-4.
2. `integration-windows.yml`: debugger tests: Eclipse 2025-12 downloaded (cached), `DevE2E deploy`, `FullSuite` (at
   least 43 tests) on a fresh Windows machine; logs and screenshots kept.
3. `zip-install-windows.yml`: install the built update-site zip into a clean Eclipse, uninstall, reinstall,
   `RegressionSuite` again.
4. `compatibility-windows.yml`: the same zip on Eclipse 2026-09, `RegressionSuite`.
5. Release candidate: the tested zip renamed with its version (`agentic-debugger-update-site-1.0.0.<qualifier>.zip`),
   its SHA-256 and notes, kept as the artifact `release-candidate-validated`.

A fresh machine for every run: no leftover breakpoints, no old window layout, nothing from earlier runs.

## Branches and pull requests (agreed 28 Nov 20:32)

- No development branch. Work happens on short branches (`feature/…`, `fix/…`, `ci/…`) made from `main`; each push is
  tested on all three systems. When it is ready: GitHub Desktop → **Create Pull Request** → merge in the browser when
  "CI and release" is green, with **Squash and merge** (one commit on `main` per pull request).
- **`main` ruleset**, set once by Ayan: Settings → Rules → Rulesets → New branch ruleset → name `main`, Enforcement
  Active, Target branches: Include default branch; tick **Restrict deletions**, **Require a pull request before
  merging** (Allowed merge methods: Squash), **Require status checks to pass** (add the "CI and release" stages after
  their first run on a pull request), **Block force pushes** → Create. Linux/macOS jobs are added to the required
  checks once they are green.

## Undo a bad change

- **Merged, not released:** the merged pull request on GitHub → **Revert** → a new pull request that undoes it; it is
  checked like any other, then merged. Or GitHub Desktop → History → right-click the commit → Revert changes in
  commit → push on a branch → pull request.
- **Released:** a release is separate from `main`. Stop new installs: set the release back to draft (or delete it),
  then Actions → "Update site (GitHub Pages)" → Run workflow (the site lists only published releases). For those who
  installed it: publish a fixed preview (`v1.0.0-preview.N+1`) from the reverted `main`; its newer version stamp makes
  Eclipse offer it as an update. A downgrade cannot be pushed to users.
- Safety net: a release needs the Run workflow button and stays a draft until Ayan publishes it.

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
| 1.3, 2.2 | Linux and macOS: all four automated kinds run (`tests-all-platforms.yml`, 28 Nov); green and required next | OPEN-54, OPEN-55 |
| 1.4 | Copilot launcher on Linux/macOS once CP-U7 passes there | OPEN-54 |
| — | Claude agent trials on GitHub (API key as a secret, Run workflow only; docs/test-plan.md §4) | not decided |
| — | Signing the plugin (certificate as a GitHub secret) | OPEN-45 |
| — | Eclipse Marketplace entry | later |
