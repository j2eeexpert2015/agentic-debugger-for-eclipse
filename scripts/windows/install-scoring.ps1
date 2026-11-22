#requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runnerPath = Join-Path $PSScriptRoot 'test-skill.ps1'
$checkerPath = Join-Path $PSScriptRoot 'check-skill-results.ps1'
$scenarioPath = Join-Path $repo 'evaluation\skill-automation\scenarios.json'
foreach($p in @($runnerPath,$checkerPath,$scenarioPath)) {
  if(-not (Test-Path -LiteralPath $p -PathType Leaf)) {throw "Required file missing: $p"}
}
$scenarioNames = [ordered]@{
 'T-01'='Skill_AutomaticActivation'
 'T-02'='Skill_NoUnnecessaryDebugging'
 'T-03'='Java_ConditionalBreakpoint_RootCause'
 'T-04'='JUnit_FailingTest_RootCause'
 'T-05'='SpringBoot_HTTPRequest_RootCause'
 'T-06'='Java_ExceptionBreakpoint'
 'T-07'='Java_StepInto_StepOver_StepReturn'
 'T-08'='Skill_TracepointConsent'
 'T-09'='Skill_ExistingSessionProtection'
 'T-10'='Skill_UnsupportedFeatures'
 'T-11'='Skill_EclipseConnectionFailure'
}
$runner = Get-Content -LiteralPath $runnerPath -Raw
$alreadyInstalled = $runner.Contains('# BEGIN AUTO-VERIFIED SKILL RESULTS')
$oldRunner = $runner
if(-not $alreadyInstalled) {
  $oldRunId = '$runId=$test.id+''-''+$conditionName+''-''+$repeat'
  $newRunId = @'
      # Stable ID plus readable scenario name in every new trace filename.
      $testDisplayName = [string](Get-Js $test 'name')
      if([string]::IsNullOrWhiteSpace($testDisplayName)) {$testDisplayName=[string]$test.id}
      $testFileName=($testDisplayName -replace '[^A-Za-z0-9_]+','_').Trim('_')
      $runId=$test.id+'_'+$testFileName+'_'+$conditionName+'_'+$repeat
'@
  $newRunId=$newRunId.TrimEnd()
  $anchor = '$csv=Join-Path $target ''trial-results.csv'''
  $integration = @'
# BEGIN AUTO-VERIFIED SKILL RESULTS
# This section only checks recorded trace files. It does not launch Eclipse or Claude.
$checkerPath = Join-Path $PSScriptRoot 'check-skill-results.ps1'
$scoringFiles=@(Get-ChildItem -LiteralPath $target -Filter '*.jsonl' -File |
   Where-Object {$_.BaseName -match '^T-0[34][-_]'})
if($scoringFiles.Count -gt 0) {
  Ensure (Test-Path -LiteralPath $checkerPath) "Missing scoring script: $checkerPath"
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $checkerPath -ReportDirectory $target
  $scorerExit=$LASTEXITCODE
  $verifiedPath=Join-Path $target 'verified-results.json'
  Ensure (Test-Path -LiteralPath $verifiedPath) 'Checker did not produce verified-results.json'
  $verified=@(ConvertFrom-Json -InputObject (Get-Content -LiteralPath $verifiedPath -Raw))
  foreach($v in $verified){
    foreach($r in $results){
      if([string]$r.id -eq [string]$v.id -and [string]$r.condition -eq [string]$v.condition -and [int]$r.repeat -eq [int]$v.repeat){
        $r.verdict=[string]$v.verdict
        $r.reason=[string]$v.reason
      }
    }
  }
  if($scorerExit -ne 0) { Write-Warning 'Some recorded runtime checks failed. See verified-results.md in this run folder.' }
}
foreach($r in $results){
  $scenarioName = ''
  foreach($s in $scenarios){if([string]$s.id -eq [string]$r.id){$scenarioName=[string](Get-Js $s 'name');break}}
  if([string]::IsNullOrWhiteSpace($scenarioName)){$scenarioName=[string]$r.id}
  $r | Add-Member -NotePropertyName testName -NotePropertyValue $scenarioName -Force
}
# END AUTO-VERIFIED SKILL RESULTS
'@
  $integration=$integration.TrimEnd()
  if(-not $runner.Contains($oldRunId)) {throw 'Runner differs from expected version: filename anchor missing. Nothing changed.'}
  if(-not $runner.Contains($anchor)) {throw 'Runner differs from expected version: report anchor missing. Nothing changed.'}
  $runner=$runner.Replace($oldRunId,$newRunId)
  $runner=$runner.Replace($anchor,$integration+"`r`n"+$anchor)
  $oldHeader='$lines.Add(''| Scenario | Condition | Trial | Verdict | Reason |'')'
  $newHeader='$lines.Add(''| Scenario | Test name | Condition | Trial | Verdict | Reason |'')'
  $oldDivider='$lines.Add(''|---|---|---:|---|---|'')'
  $newDivider='$lines.Add(''|---|---|---|---:|---|---|'')'
  $oldRow="foreach(`$r in `$results){`$lines.Add('| '+`$r.id+' | '+`$r.condition+' | '+`$r.repeat+' | '+`$r.verdict+' | '+(([string]`$r.reason).Replace('|','/'))+' |')}"
  $newRow="foreach(`$r in `$results){`$lines.Add('| '+`$r.id+' | '+`$r.testName.Replace('_',' ')+' | '+`$r.condition+' | '+`$r.repeat+' | '+`$r.verdict+' | '+(([string]`$r.reason).Replace('|','/'))+' |')}"
  foreach($match in @(@($oldHeader,$newHeader),@($oldDivider,$newDivider),@($oldRow,$newRow))){
    if(-not $runner.Contains($match[0])){throw "Runner report format differs from expected version. Nothing changed. Missing: $($match[0])"}
    $runner=$runner.Replace($match[0],$match[1])
  }
}
$oldScenarios=Get-Content -LiteralPath $scenarioPath -Raw
$scenarios=ConvertFrom-Json -InputObject $oldScenarios
$changed=0
foreach($s in $scenarios) {
  $id=[string]$s.id
  if($scenarioNames.Contains($id)) {
    if($null -eq $s.PSObject.Properties['name']) {
      $s | Add-Member -NotePropertyName name -NotePropertyValue $scenarioNames[$id]
      $changed++
    } elseif([string]$s.name -ne $scenarioNames[$id]) {
      throw "Scenario $id already has a different name ($($s.name)); aborting to avoid overwriting it."
    }
  }
}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
if(-not $alreadyInstalled -or $changed -gt 0) {
  $backupDir=Join-Path $repo ('evaluation\skill-automation\scoring-install-backup-'+$stamp)
  New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
  Copy-Item -LiteralPath $runnerPath -Destination (Join-Path $backupDir 'test-skill.ps1')
  Copy-Item -LiteralPath $scenarioPath -Destination (Join-Path $backupDir 'scenarios.json')
  try {
    if(-not $alreadyInstalled) {Set-Content -LiteralPath $runnerPath -Value $runner -Encoding UTF8}
    if($changed -gt 0) { $scenarios | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $scenarioPath -Encoding UTF8 }
  } catch {
    Copy-Item -LiteralPath (Join-Path $backupDir 'test-skill.ps1') -Destination $runnerPath -Force
    Copy-Item -LiteralPath (Join-Path $backupDir 'scenarios.json') -Destination $scenarioPath -Force
    throw
  }
  Write-Host "Installed. Original runner and scenarios backed up in $backupDir"
} else {
  Write-Host 'Already installed; no changes made.'
}
Write-Host 'Automatic scoring is enabled for T-03 and T-04; other tests retain their existing provisional verdicts.'
