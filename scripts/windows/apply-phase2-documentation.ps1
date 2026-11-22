# Applies an additive Phase 2 evidence reconciliation to the existing checkout.
# Does not use Git or modify Java source, Eclipse installation, or test reports.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$report = Join-Path $repo 'docs\phase2-verification-consolidation.md'
if (!(Test-Path -LiteralPath $report -PathType Leaf)) {
    throw "Missing docs/phase2-verification-consolidation.md. Extract the package at the repository root first."
}
$header = '## Local Phase 2 Verification Update (22 November 2026)'
$beginMarker = '<!-- PHASE2-VERIFICATION-CONSOLIDATION:BEGIN -->'
$endMarker = '<!-- PHASE2-VERIFICATION-CONSOLIDATION:END -->'
$backupDir = Join-Path ([IO.Path]::GetTempPath()) 'AgenticDebugger-Phase2Docs-Backup-20261122'
[void](New-Item -ItemType Directory -Force -Path $backupDir)

function Update-Document([string]$relative, [string]$body, [scriptblock]$rewrite) {
    $file = Join-Path $repo ($relative -replace '/', '\')
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file not found: $relative" }
    $old = [IO.File]::ReadAllText($file, [Text.Encoding]::UTF8)
    if ($old.Contains($beginMarker)) {
        Write-Host "UNCHANGED (already reconciled): $relative"
        return
    }
    $updated = $old
    if ($null -ne $rewrite) { $updated = & $rewrite $updated }
    $block = "`r`n$beginMarker`r`n$header`r`n`r`n$body`r`n$endMarker`r`n"
    # Insert immediately before the first ## section. Preserve all other existing content.
    $match = [regex]::Match($updated, '(?m)^## ')
    if (!$match.Success) { throw "No section heading found in $relative; not safe to update" }
    $updated = $updated.Insert($match.Index, $block + "`r`n")
    $backup = Join-Path $backupDir ($relative -replace '/', '__')
    [IO.File]::WriteAllText($backup, $old, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($file, $updated, [Text.UTF8Encoding]::new($false))
    Write-Host "UPDATED: $relative (backup: $backup)"
}

$readmeText = @'
The installed Windows validation now reports **SB-08 PASS** (service throw and controller handler; HTTP 400; owned cleanup) and the tested **SB-09 subset PASS** (borrowed session preserved, expired investigation rejected, unauthorized fixture POST rejected). The combined Phase 2 verifier passed in one Eclipse instance. Immediately afterward, the seven Phase 1 direct bridge scenarios all passed with cleanup and the Maven reactor reported `BUILD SUCCESS`. These are **direct bridge tests on local candidate `1.5.0.20261122084229515`**, not fresh Claude sessions.

Earlier installed Spring Boot launch, HTTP (direct + explicit Claude) and JUnit evidence remains in [Spring Boot tool audit](docs/spring-boot-tool-audit.md). **Full Phase 2 acceptance is still open**: the complete multi-service ownership matrix, STS comparison and skill-free symptom-only Boot trials are not established by these direct runs. See [consolidated results and scenario matrix](docs/phase2-verification-consolidation.md).
'@
# Do not overwrite current repository status or original roadmap sections: add factual update.
Update-Document 'README.md' $readmeText $null

$devText = @'
**Newest local installed evidence:** SB-08 direct invalid-quantity request debugging and SB-09 bounded ownership/consent checks both **PASS** together, followed by **7/7 Phase 1 bridge regression PASS**, all with reported cleanup. The local installed bundle was `1.5.0.20261122084229515`. These results extend, but do not replace, the separate historical P2-01–P2-05 audit evidence. P2-06 remains **in progress**, because full SB-01–SB-10 explicit/symptom/negative gates and STS coverage are not complete. [Consolidated evidence](phase2-verification-consolidation.md).

**Immediate order:** archive successful results and reproducible local harness fixes → finish essential multi-service and environment checks → obtain bounded skill-free Java/JUnit/Boot symptom baseline → author optional unified skill (Phase 3) → controlled A/B evaluation (Phase 4). Do not imply that an optional skill is required for core debugging.
'@
Update-Document 'docs/development-roadmap.md' $devText $null

$featureText = @'
**Post-audit local test update:** BOOT-07 / SB-08 (invalid-quantity exception/handler) passed direct installed Eclipse checks; BOOT-10 / SB-09 passed a **subset** covering borrowed launch preservation, expired investigation rejection and refusal of unapproved fixture POST. Phase 1's seven capabilities passed direct regression on the same installation. The broader BOOT-10 cross-service matrix and BOOT-11 / SB-10 Spring Tools compatibility remain pending. No Boot-specific natural-language symptom-only trial or skill A/B result is claimed. [Evidence ledger](phase2-verification-consolidation.md).
'@
Update-Document 'docs/feature-roadmap.md' $featureText $null

$scenarioText = @'
**New local direct evidence:** On candidate `1.5.0.20261122084229515`, SB-08 stopped at `OrderService.java:17` and `OrderController.java:27`, returned HTTP 400 with `Quantity must be positive`, and cleaned up. SB-09's tested borrowed-launch, expired-ID and unapproved-POST subset passed with cleanup. These two checks passed sequentially; the full Phase 1 direct suite then passed 7/7. **Explicit Claude and symptom-only Boot gates were not rerun**. The [consolidated four-gate scenario matrix](phase2-verification-consolidation.md#4-scenario-acceptance-matrix--do-not-treat-unrun-trials-as-failures-or-successes) remains authoritative for completion decisions; this catalog's original acceptance requirements stay unchanged.
'@
Update-Document 'docs/spring-boot-debugging-scenarios.md' $scenarioText $null

$auditText = @'
**Additional local installed run, 22 November 2026:** User-supplied console output confirms `scripts\windows\verify-phase2-eclipse.bat` **PASS** for `validation` and `ownership` in a single Eclipse session, using plugin `1.5.0.20261122084229515`, JDK 25 and bridge port 21765. The validation request was actually served (HTTP 400, service throw line 17, controller handler line 27). The ownership subset preserved a borrowed launch and rejected an expired investigation and unapproved POST; both scenarios reported `cleanup=true`. Then `scripts\windows\verify-phase1-eclipse.bat` reported all seven Java bridge scenarios **PASS**, cleanup true, and Maven `BUILD SUCCESS`. These runs extend the prior tool audit but are **not** a full SB-01–SB-10 four-gate sign-off. [Evidence, artifact distinction, and remaining gaps](phase2-verification-consolidation.md).

The first local validation failure was due to the Windows PowerShell fixture HTTP client not loading `System.Net.Http`. After fixing the client, the test passed. The verifier also required a local Java access-modifier correction for the shared bridge trace field. Both harness changes should be included in any reproducible candidate source baseline. The older failed JSON is not proof of the subsequent successful run.
'@
Update-Document 'docs/spring-boot-tool-audit.md' $auditText $null

$backlogText = @'
**P2-06 local progress (22 November 2026):** The Java Phase 2 verifier now records SB-08 **PASS** (real HTTP 400 with service and controller exception stops) and a focused SB-09 **PASS** (borrowed session preserved, expired investigation rejected and unapproved POST blocked). Both passed sequentially on installed `1.5.0.20261122084229515`; all seven Phase 1 direct scenarios also passed afterward. **P2-06 remains open** for the broader cross-session matrix, STS coverage, complete source-to-artifact evidence and explicit/symptom-only trials. [Consolidated acceptance ledger](phase2-verification-consolidation.md). The Java test harness and HTTP client fixes were local; do not assume GitHub `main` already includes them.

**Next code tasks:** preserve the corrected Phase 2 verifier and PowerShell client; collect successful JSON and command transcripts; finish bounded SB-09/10 checks, then prepare skill-free agent baselines before starting Phase 3's optional skill. Do not rebuild new Boot MCP APIs without observed gaps.
'@
Update-Document 'docs/codex-implementation-backlog.md' $backlogText $null

$evalText = @'
**New direct-only results (not skill A/B):** Local candidate `1.5.0.20261122084229515` passed SB-08 and SB-09's tested subset together, then passed all seven Phase 1 bridge scenarios. This is important deterministic regression evidence, but **does not change the existing no-skill Claude symptom baseline or establish any Boot agent-choice outcome**. Continue to label all Boot symptom-only conditions `NOT RUN` until observed in isolated fresh agent sessions. [Current evidence matrix](phase2-verification-consolidation.md).
'@
Update-Document 'docs/evaluation-plan.md' $evalText $null
Write-Host 'Documentation reconciliation complete. This command did not run Git, Maven or Eclipse.'
