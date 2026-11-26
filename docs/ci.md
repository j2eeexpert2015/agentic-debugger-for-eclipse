# Continuous integration (GitHub Actions)

[README](../README.md#status-and-tracking) · [Working agreement](working-agreement.md) · [Handoff](handoff.md)

Why: Ayan builds and tests on Windows only. GitHub's machines (Windows, Linux, macOS) are free for this public
repository on the standard runners and give evidence for the systems we cannot test by hand (README OPEN-54..56).
We grow CI one step at a time and start from what we know works: Windows.

Two workflows:

- `.github/workflows/ci.yml` ("Build and unit tests"): every push to `main` and to `ci/...` branches, pull requests,
  and by hand (Actions tab → Build and unit tests → Run workflow).
- `.github/workflows/integration-windows.yml` ("Integration tests (Windows)"): pushes to `ci/...` branches and by
  hand; build, install the plugin into Eclipse 2025-12 (`DevE2E deploy`), `RegressionSuite`.

## Plan

| Phase / step | What | Tracking | Status |
|---|---|---|---|
| 1.1 | **Windows only**, JDK 25 (same as Ayan's machine): `mvn -B -ntp clean package`, unit tests of `report`, `mcp-server`, `testing`, `eclipse-plugin`; a test-count table on the run page; test reports kept when the build fails; the update-site zip kept | OPEN-54 | **Done, checked 27 Nov 00:47** (run #1, 2 min 51 s): report 54, mcp-server 34, testing 23, eclipse-plugin 84, total 195, 0 failures; both p2 sites reached from GitHub's machine; update-site zip kept (6.3 MB) |
| 1.2 | Add **Windows, JDK 21** (the README promises JDK 21+): build and unit tests run on a Java 21 JVM. One new value in the `matrix` of `ci.yml`; `upload-artifact` v4 → v5 (Node 20 warning) | OPEN-54 | **Done, checked 27 Nov 01:04** (run #2, 1 min 47 s with the Maven cache): both jobs green (Windows JDK 25 and JDK 21) |
| 1.3 | Add Linux (`ubuntu-latest`) and macOS (`macos-latest`): only new rows in the `matrix` | OPEN-54 | After 1.2 is green |
| 1.4 | When CP-U7 (runs the real launcher script) passes on Linux and macOS: switch the Copilot launcher on there (`CopilotMcpProvider`) | OPEN-54 | After 1.3 |
| 2.1 | Integration tests with a real test Eclipse, **Windows first** (Ayan, 27 Nov: Windows first, then the other systems): download Eclipse 2025-12 (Java developers package: JDT, JUnit, m2e) and cache it, write `dev-e2e/e2e.properties` with the job's paths, `java dev-e2e/DevE2E.java deploy`, then `mvn -pl testing verify -Pintegration-tests -Dfixture.post.approved=true` (`RegressionSuite`, 21 tests); keep test reports and the Eclipse log on failure. Unknowns: whether a GitHub Windows machine shows the Eclipse window (I think yes, not checked), `DevE2E` with other paths, run time. Then Linux with Xvfb, then macOS. Claude and Copilot runs stay manual (accounts, usage). Workflow `.github/workflows/integration-windows.yml` ("Integration tests (Windows)"): pushes to `ci/...` and by hand, not on `main` until reliable; Eclipse cached before our plugin is installed; screenshots after start and at the end (evidence whether the Eclipse window shows) | OPEN-55 | Delivered 27 Nov (Ayan chose to start phase 2 now, 01:04), first run pending |
| 2.2 | Integration tests on Linux (Xvfb), then macOS | OPEN-55 | After 2.1 and 1.3 |
| 3 | Releases: on a version tag build, create a GitHub Release with the update-site zip, publish the update site on GitHub Pages; signing (OPEN-45) later with the certificate as a GitHub secret | OPEN-56 | Not started |

## Branches

- `main`: development, as before.
- `ci/github-actions`: CI work until the first runs are green, then merged into `main`. Development on `main` meanwhile
  does not matter for CI (the CI branch builds the code as it was when the branch was made).

## Reading a run

GitHub → **Actions** → the run → a job (`windows-latest / JDK 25`, `windows-latest / JDK 21`):

- the job's page (**Summary**) shows the table "Unit tests" per module; expected today: report 54, mcp-server 34,
  testing 23, eclipse-plugin 84, 0 failures;
- when the build fails: the red step's log, and the artifact `test-reports-...` (surefire XML and text per module);
- when it passes: the artifact `update-site-...` (the same update-site zip as a local build).

## First things to watch

- Tycho reaching the p2 sites from GitHub's machines (`download.eclipse.org` and the Copilot update site on Azure).
- Windows line endings: the repository has no `.gitattributes`, so GitHub's Windows machine checks out with CRLF, the
  same as Ayan's GitHub Desktop checkout. Linux and macOS (step 1.3) check out with LF; text-comparing tests may differ.
- The first run downloads all Maven and p2 dependencies (slow); later runs use the Maven cache.

## Reading an integration run

GitHub → **Actions** → "Integration tests (Windows)" → the run → the job:

- **Summary**: the table "Integration tests" (expected: 21 completed, 0 failures, 0 errors) and one row per test class;
- the artifact `integration-windows-logs`: `screen-after-start.png` and `screen-at-end.png` (does the Eclipse window
  show?), `eclipse-console.log` (Eclipse's output), `eclipse-workspace.log` (the workspace `.metadata/.log`),
  `failsafe-reports/` (one file per test class).

First run: Eclipse is downloaded (about 350 MB, then cached); the fixture projects are imported into a new workspace
and their Maven dependencies downloaded, so `DevE2E deploy` may need most of its 5-minute wait for the bridge.
