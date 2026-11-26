# Continuous integration (GitHub Actions)

[README](../README.md#status-and-tracking) · [Working agreement](working-agreement.md) · [Handoff](handoff.md)

Why: Ayan builds and tests on Windows only. GitHub's machines (Windows, Linux, macOS) are free for this public
repository on the standard runners and give evidence for the systems we cannot test by hand (README OPEN-54..56).
We grow CI one step at a time and start from what we know works: Windows.

The workflow is `.github/workflows/ci.yml` ("Build and unit tests"). It runs on every push to `main` and to
`ci/...` branches, on pull requests, and by hand (Actions tab → Build and unit tests → Run workflow).

## Plan

| Phase / step | What | Tracking | Status |
|---|---|---|---|
| 1.1 | **Windows only**, JDK 25 (same as Ayan's machine): `mvn -B -ntp clean package`, unit tests of `report`, `mcp-server`, `testing`, `eclipse-plugin`; a test-count table on the run page; test reports kept when the build fails; the update-site zip kept | OPEN-54 | Delivered 27 Nov, first run pending |
| 1.2 | Add Linux (`ubuntu-latest`) and macOS (`macos-latest`) on JDK 25, and Windows on JDK 21 (the README promises JDK 21+): only new rows in the `matrix` of `ci.yml` | OPEN-54 | After 1.1 is green |
| 1.3 | When CP-U7 (runs the real launcher script) passes on Linux and macOS: switch the Copilot launcher on there (`CopilotMcpProvider`) | OPEN-54 | After 1.2 |
| 2 | Integration tests with a real test Eclipse: download Eclipse 2025-12, write `dev-e2e/e2e.properties` in the job, deploy, start, run `RegressionSuite`. Windows first (no virtual display needed, same as Ayan's setup; not yet checked that a GitHub Windows machine runs the Eclipse UI), then Linux with Xvfb, then macOS | OPEN-55 | Not started |
| 3 | Releases: on a version tag build, create a GitHub Release with the update-site zip, publish the update site on GitHub Pages; signing (OPEN-45) later with the certificate as a GitHub secret | OPEN-56 | Not started |

## Branches

- `main`: development, as before.
- `ci/github-actions`: CI work until the first runs are green, then merged into `main`. Development on `main` meanwhile
  does not matter for CI (the CI branch builds the code as it was when the branch was made).

## Reading a run

GitHub → **Actions** → the run → the job (`windows-latest / JDK 25`):

- the job's page (**Summary**) shows the table "Unit tests" per module; expected today: report 54, mcp-server 34,
  testing 23, eclipse-plugin 84, 0 failures;
- when the build fails: the red step's log, and the artifact `test-reports-...` (surefire XML and text per module);
- when it passes: the artifact `update-site-...` (the same update-site zip as a local build).

## First things to watch

- Tycho reaching the p2 sites from GitHub's machines (`download.eclipse.org` and the Copilot update site on Azure).
- Windows line endings: the repository has no `.gitattributes`, so GitHub's Windows machine checks out with CRLF, the
  same as Ayan's GitHub Desktop checkout. Linux and macOS (step 1.2) check out with LF; text-comparing tests may differ.
- The first run downloads all Maven and p2 dependencies (slow); later runs use the Maven cache.
