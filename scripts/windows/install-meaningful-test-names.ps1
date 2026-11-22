#requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runnerPath=Join-Path $PSScriptRoot 'test-skill.ps1'
$scenarioPath=Join-Path $repo 'evaluation\skill-automation\scenarios.json'
$checkerPath=Join-Path $PSScriptRoot 'check-skill-results.ps1'
$testPath=Join-Path $PSScriptRoot 'test-scoring.ps1'
$entryPath=Join-Path $repo 'RUN-WINDOWS-TESTS.cmd'
$source=Join-Path $PSScriptRoot 'meaningful-test-names-files'
$files=@($runnerPath,$scenarioPath,$checkerPath,$testPath,$entryPath)
foreach($path in $files){if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing project file: $path"}}
foreach($name in @('check-skill-results.ps1','test-scoring.ps1','RUN-WINDOWS-TESTS.cmd')){
 if(-not (Test-Path -LiteralPath (Join-Path $source $name) -PathType Leaf)){throw "Missing migration package file: $name"}
}
$names=[ordered]@{
 'T-00'='Skill_ExplicitActivation'
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
$runner=Get-Content -LiteralPath $runnerPath -Raw
if($runner.Contains('MEANINGFUL-SCENARIO-IDS-INSTALLED')){
 Write-Host 'Already installed. No files changed.'; exit 0
}
if(-not $runner.Contains('# BEGIN AUTO-VERIFIED SKILL RESULTS')){throw 'Expected scoring integration not found; no files changed.'}
$oldRunId='$runId=$test.id+''_''+$testFileName+''_''+$conditionName+''_''+$repeat'
if(-not $runner.Contains($oldRunId)){throw 'Unexpected runner filename format. No files changed.'}
$oldScoringFilter="`$_.BaseName -match '^T-0[34][-_]'"
if(-not $runner.Contains($oldScoringFilter)){throw 'Expected scoring filter not found. No files changed.'}
$oldReport=Get-Content -LiteralPath $scenarioPath -Raw | ConvertFrom-Json
$ids=@($oldReport|ForEach-Object {[string]$_.id})
if(@($ids|Select-Object -Unique).Count -ne $ids.Count){throw 'Duplicate scenario IDs; no files changed.'}
foreach($id in @('T-01','T-02','T-03','T-04','T-05','T-06','T-07','T-08','T-09','T-10','T-11')){
 if($ids -notcontains $id){throw "Expected $id in scenarios.json; no files changed."}
}
foreach($s in $oldReport){
 $oldId=[string]$s.id
 if(-not $names.Contains($oldId)){throw "Unrecognized scenario $oldId; no files changed."}
 $s.id=$names[$oldId]
 if($null -eq $s.PSObject.Properties['name']) { $s | Add-Member -NotePropertyName name -NotePropertyValue $s.id }
 else {$s.name=$s.id}
}
# Scenario references in runner use the same names as JSON configuration.
foreach($oldId in $names.Keys) {$runner=$runner.Replace($oldId,$names[$oldId])}
$runIdAfterReplacement='$runId=$test.id+''_''+$testFileName+''_''+$conditionName+''_''+$repeat'
if(-not $runner.Contains($runIdAfterReplacement)){throw 'Expected run ID pattern missing after transformation.'}
$runner=$runner.Replace($runIdAfterReplacement,'$runId=$test.id+''_''+$conditionName+''_''+$repeat')
# The scorer still understands old recordings, but new runs carry only descriptive IDs.
$runner=$runner.Replace($oldScoringFilter,"`$_.BaseName -match '^(Java_ConditionalBreakpoint_RootCause|JUnit_FailingTest_RootCause)[-_]'")
$runner="# MEANINGFUL-SCENARIO-IDS-INSTALLED`r`n"+$runner
if($runner -notmatch [regex]::Escape('Java_ConditionalBreakpoint_RootCause') -or $runner -match "'T-03'"){
 throw 'Migration validation failed before changing any project file.'
}
$parseErrors=$null; $tokens=$null
$null=[System.Management.Automation.Language.Parser]::ParseInput($runner,[ref]$tokens,[ref]$parseErrors)
if(@($parseErrors).Count -gt 0){throw ('PowerShell parser rejected migrated runner: '+$parseErrors[0].Message)}
$checker=Get-Content -LiteralPath (Join-Path $source 'check-skill-results.ps1') -Raw
$test=Get-Content -LiteralPath (Join-Path $source 'test-scoring.ps1') -Raw
foreach($script in @($checker,$test)){
 $parseErrors=$null;$tokens=$null
 $null=[System.Management.Automation.Language.Parser]::ParseInput($script,[ref]$tokens,[ref]$parseErrors)
 if(@($parseErrors).Count -gt 0){throw ('PowerShell parser rejected packaged script: '+$parseErrors[0].Message)}
}
$entry=Get-Content -LiteralPath (Join-Path $source 'RUN-WINDOWS-TESTS.cmd') -Raw
if(-not $entry.Contains('Java_ConditionalBreakpoint_RootCause')){throw 'Packaged command help failed verification.'}
$newScenarios=$oldReport | ConvertTo-Json -Depth 35
$changes=@(
 @{path=$runnerPath;content=$runner},
 @{path=$scenarioPath;content=$newScenarios},
 @{path=$checkerPath;content=$checker},
 @{path=$testPath;content=$test},
 @{path=$entryPath;content=$entry}
)
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$backup=Join-Path $repo ('evaluation\skill-automation\naming-backup-'+$stamp)
New-Item -ItemType Directory -Force -Path $backup | Out-Null
foreach($change in $changes){Copy-Item -LiteralPath $change.path -Destination (Join-Path $backup ([IO.Path]::GetFileName($change.path))) -Force}
try {
 foreach($change in $changes){
  if([IO.Path]::GetExtension($change.path) -eq '.cmd') {
    # Windows CMD must not receive the UTF-8 BOM emitted by Windows PowerShell 5.1.
    [IO.File]::WriteAllText($change.path,[string]$change.content,[Text.Encoding]::ASCII)
  } else {
    Set-Content -LiteralPath $change.path -Value $change.content -Encoding UTF8
  }
}
 # Confirm canonical scenario IDs survived writing and all expected scenarios exist.
 $saved=@(ConvertFrom-Json -InputObject (Get-Content -LiteralPath $scenarioPath -Raw))
 foreach($expected in $names.Values){if(@($saved|Where-Object {$_.id -eq $expected}).Count -ne 1){
  if($expected -ne 'Skill_ExplicitActivation'){throw "Failed post-install verification: $expected"}
 }}
} catch {
 foreach($change in $changes){Copy-Item -LiteralPath (Join-Path $backup ([IO.Path]::GetFileName($change.path))) -Destination $change.path -Force}
 throw
}
Write-Host 'PASS: scenario identifiers, runner selection, filenames, reports and scoring checker use descriptive names.'
Write-Host "Backup: $backup"
Write-Host 'Example: RUN-WINDOWS-TESTS.cmd skill selected Java_ConditionalBreakpoint_RootCause'
Write-Host 'Full suite: RUN-WINDOWS-TESTS.cmd skill full'
