#requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runnerPath = Join-Path $PSScriptRoot 'test-skill.ps1'
if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) { throw "Runner missing: $runnerPath" }
$original = [IO.File]::ReadAllText($runnerPath)
$already = '# FINAL-VERDICTS-V2-INSTALLED'
if ($original.Contains($already)) { Write-Host 'Final verdict fix already installed; no changes made.'; exit 0 }
$markerBegin = '# BEGIN AUTO-VERIFIED SKILL RESULTS'
$markerEnd = '# END AUTO-VERIFIED SKILL RESULTS'
if (-not $original.Contains($markerBegin) -or -not $original.Contains($markerEnd)) { throw 'Existing scoring integration was not found. No files changed.' }
$oldAssign = '$verified=@(ConvertFrom-Json -InputObject (Get-Content -LiteralPath $verifiedPath -Raw))'
$newAssign = '$verified=ConvertFrom-Json -InputObject (Get-Content -LiteralPath $verifiedPath -Raw)'
if (-not $original.Contains($oldAssign)) { throw 'Expected JSON parsing line was not found. No files changed.' }
$oldMatch = 'if([string]$r.id -eq [string]$v.id -and [string]$r.condition -eq [string]$v.condition -and [int]$r.repeat -eq [int]$v.repeat){'
$newMatch = 'if([string]$r.id -eq [string]$v.id -and [string]$r.condition -eq [string]$v.condition -and [int]$r.repeat -eq [int]$v.repeat -and [string]$v.source -eq (([string]$r.id)+''_''+([string]$r.condition)+''_''+([string]$r.repeat))){'
if (-not $original.Contains($oldMatch)) { throw 'Expected score matching line was not found. No files changed.' }
$oldPrint = 'Write-Host "$runId : $($analysis.verdict) ($($analysis.reason))"'
if (-not $original.Contains($oldPrint)) { throw 'Expected provisional result printing line was not found. No files changed.' }
# The report must be written AFTER scoring. This anchor follows the scoring integration.
$reportAnchor = '$csv=Join-Path $target ''trial-results.csv'''
if (-not $original.Contains($reportAnchor)) { throw 'Final CSV report anchor missing. No files changed.' }
# Avoid silently updating multiple script copies or partially matching an unknown version.
foreach ($item in @(@($oldAssign,$newAssign),@($oldMatch,$newMatch))) {
 if ($original.Split(@($item[0]),[StringSplitOptions]::None).Count -ne 2) { throw "Expected exactly one anchor: $($item[0])" }
}
$updated = $original.Replace($oldAssign,$newAssign).Replace($oldMatch,$newMatch)
$updated = $updated.Replace($oldPrint,'# Provisional verdict intentionally not printed; final verdict is printed after evidence checking.')
$finalDisplay = @'
# FINAL-VERDICTS-V2-INSTALLED
# Print exactly one verdict per selected trial, after evidence-based scoring.
foreach($finalRow in $results) {
  $finalId=([string]$finalRow.id)+'_'+([string]$finalRow.condition)+'_'+([string]$finalRow.repeat)
  Write-Host ($finalId+' : '+[string]$finalRow.verdict+' ('+[string]$finalRow.reason+')')
}
'@
$updated = $updated.Replace($reportAnchor,$finalDisplay+"`r`n"+$reportAnchor)
$tokenList=$null; $parseErrors=$null
$null=[System.Management.Automation.Language.Parser]::ParseInput($updated,[ref]$tokenList,[ref]$parseErrors)
if (@($parseErrors).Count -gt 0) { throw ('Updated PowerShell failed to parse: '+$parseErrors[0].Message) }
$backup=Join-Path $repo ('evaluation\skill-automation\final-verdict-backup-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item -LiteralPath $runnerPath -Destination (Join-Path $backup 'test-skill.ps1') -Force
try {
  [IO.File]::WriteAllText($runnerPath,$updated,[Text.UTF8Encoding]::new($true))
  if (-not ([IO.File]::ReadAllText($runnerPath)).Contains($already)) { throw 'Verification after writing failed' }
} catch {
  Copy-Item -LiteralPath (Join-Path $backup 'test-skill.ps1') -Destination $runnerPath -Force
  throw
}
Write-Host 'PASS: Final verdict integration corrected. No Eclipse, Claude, or source files changed.'
Write-Host "Backup: $backup"
Write-Host 'Future suite runs will print one final result per test and export those same results to CSV.'
