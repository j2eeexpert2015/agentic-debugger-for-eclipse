# GitHub Release verification checklist (maintainers)

**Purpose:** Publish an installable, traceable, tested update-site ZIP through GitHub Releases. **GitHub Releases first; Eclipse Marketplace later.** This is a release gate, not an additional automated test suite.

**Status:** the pipeline runs on `main` (merged 28 Nov, [docs/ci.md](ci.md)). Complete this checklist for the exact commit being released. The initial public `v1.5.0-preview.1` asset is older than the CI-generated release candidates.

## A. Approve the exact source revision

- [ ] Identify the approved product commit SHA and release tag (`vMAJOR.MINOR.PATCH`, or `-preview.N` / `-rc.N` for a pre-release). Ensure this is the actual intended product code, not simply the last passing CI branch commit.
- [ ] Confirm the release tag does not already exist, and note any merge conflicts/resolutions.
- [ ] Confirm which Eclipse versions, Java runtimes, and operating systems the release notes may legitimately claim. Do not extrapolate from build-time compatibility to untested runtime compatibility.

## B. Run existing sequential CI and retain evidence

Open **GitHub → Actions → CI and release** for the *exact* approved commit. Record the run URL and verify:

- [ ] **Stage 1:** Windows JDK 21 and 25 builds and unit tests passed with actual Surefire reports.
- [ ] **Stage 2:** Eclipse 2025-12 `FullSuite`: **43 completed, 0 failures, 0 errors, 0 skips** (baseline counts; update if the product's test suite changes).
- [ ] **Stage 3:** Install from the generated ZIP, uninstall, verify removed feature root, reinstall from that same ZIP, then **21 completed regression tests, 0 failures/errors/skips**.
- [ ] **Stage 4:** Eclipse 2026-09 compatibility: the *same* tested ZIP installs and 21 regression tests pass. **Do not mark this complete from a queued or skipped job.** If support is intentionally limited to an earlier Eclipse release, make that decision explicit and revise the declared compatibility gate before release.
- [ ] **Stage 5:** `release-candidate-validated` is produced after the preceding steps succeed; the ZIP, `SHA256SUMS.txt`, and `RELEASE_CANDIDATE.md` are present.
- [ ] **Draft release job:** no release was created on ordinary branch pushes; it must remain manual and draft-only.

The complete evidence belongs to one successful workflow execution for one exact source revision. **Do not combine passing results from different commits to certify a release.**

## C. Verify the release assets and metadata

- [ ] Download the candidate artifact from the successful workflow run.
- [ ] Confirm it contains **one** installable `agentic-debugger-update-site-VERSION.zip`, `SHA256SUMS.txt`, and release notes. Do not publish GitHub's source ZIP as the plugin distribution.
- [ ] Compare SHA-256 of the ZIP with `SHA256SUMS.txt`. On Windows CMD: `certutil -hashfile "ACTUAL_ZIP_FILENAME.zip" SHA256`.
- [ ] Confirm the installed Eclipse **feature and plugin versions agree**, and Maven's update-site base version agrees with the product version. The CI candidate version has a build qualifier; the preview tag uses a human-readable tag (`v1.5.0-preview.N`).
- [ ] Verify release notes contain: tag, exact source commit, Actions run URL, supported/tested Eclipse and Java versions, known limitations, checksum and installation steps.
- [ ] Document the signing choice truthfully. A checksum is not a digital signature. The current CI does not establish a trusted signing identity for public artifacts.

## D. Human acceptance that CI does not replace

- [ ] On a clean supported Eclipse installation, install the **actual downloaded candidate ZIP** using **Help → Install New Software → Add → Archive**; restart and verify the installed feature.
- [ ] Confirm Claude Code can discover `eclipse-debugger`, `eclipse_ping` succeeds, and one end-to-end Java debugging investigation produces expected stops, cleanup, and report.
- [ ] Where Copilot is advertised, check its MCP approval, tool discovery and actual debugger behavior. **Do not assert custom-agent-only tool isolation until separately demonstrated.**
- [ ] Review user-owned breakpoints and unrelated debug sessions after agent testing; confirm they are preserved.
- [ ] Confirm publisher/trust warnings, installation instructions, and privacy/security descriptions match the actual release behavior.
- [ ] Check upgrading from a previous public release **if upgrade support is claimed**. Current automated tests prove same-version uninstall/reinstall, not an old-to-new upgrade.

## E. Draft and publish deliberately

- [ ] On `main`, invoke **Run workflow** with `create_draft=true` and a *new* matching tag (a preview, `v1.0.0-preview.N`; `v1.0.0` only for the final release). This must rerun all validation gates against the approved source commit. It cannot be used from the Actions UI before the workflow is on the default branch.
- [ ] Confirm the draft was created, is **unpublished**, has the correct target commit and tag, and includes the tested update-site ZIP, checksum and release notes.
- [ ] Inspect the draft's assets and verify the checksum again. Review installation text and known limitations; mark it as a prerelease if appropriate.
- [ ] Only after explicit owner approval, publish the draft through GitHub Releases. Do not enable auto-publishing on ordinary pushes.
- [ ] On the published release page, confirm the ZIP is under **Assets**, downloads successfully, and matches the recorded checksum. Confirm links from README and [installation guide](install-from-github-releases.md) work.
- [ ] Publishing starts **Update site (GitHub Pages)**: when it is green, in a clean Eclipse add `https://j2eeexpert2015.github.io/agentic-debugger-for-eclipse/` (Help → Install New Software) and check that the new version is offered and installs; in an Eclipse with the previous release, Help → Check for Updates offers it.

## Release acceptance record

Fill this in for each approved release:

| Field | Value |
|---|---|
| Release tag | _Not assigned_ |
| Source commit SHA | _Not assigned_ |
| Sequential CI run URL | _Not assigned_ |
| Installable ZIP filename | _Not assigned_ |
| SHA-256 | _Not assigned_ |
| Eclipse versions actually tested | _Not confirmed for this release_ |
| Java runtime(s) actually tested | _Not confirmed for this release_ |
| Manual Claude/Copilot acceptance evidence | _Pending_ |
| Signing status | _Undecided / verify_ |
| Final owner approval | _Pending_ |
| Published GitHub Release URL | _Not published_ |
| Update site shows this version | _Pending_ |

**Stop rule:** If any required row is unverified, leave the release as a draft. This checklist documents requirements; checking the boxes does not substitute for log evidence.
