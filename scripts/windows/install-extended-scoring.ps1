#requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runner=Join-Path $PSScriptRoot 'test-skill.ps1'
$checker=Join-Path $PSScriptRoot 'check-skill-results.ps1'
$packaged=Join-Path $PSScriptRoot 'extended-scoring-files\check-skill-results.ps1'
foreach($f in @($runner,$checker,$packaged)) {if(-not (Test-Path -LiteralPath $f -PathType Leaf)){throw "Required file missing: $f"}}
$original=[IO.File]::ReadAllText($runner)
$tag='# EXTENDED-EVIDENCE-CHECKING-INSTALLED'
if($original.Contains($tag)) {Write-Host 'Already installed; no changes made.';exit 0}
$old="`$_.BaseName -match '^(Java_ConditionalBreakpoint_RootCause|JUnit_FailingTest_RootCause)[-_]'"
$new="`$_.BaseName -match '^(Java_ConditionalBreakpoint_RootCause|JUnit_FailingTest_RootCause|Java_ExceptionBreakpoint|Java_StepInto_StepOver_StepReturn|Skill_ExistingSessionProtection|Skill_UnsupportedFeatures)[-_]'"
if(-not $original.Contains($old)){throw 'Unexpected current runner version: scoring filter missing. Nothing changed.'}
if($original.Split(@($old),[StringSplitOptions]::None).Count -ne 2){throw 'Scoring filter appears more than once. Nothing changed.'}
if(-not $original.Contains('# FINAL-VERDICTS-V2-INSTALLED')){throw 'Final verdict fix missing. Nothing changed.'}
$updated=$tag+"`r`n"+$original.Replace($old,$new)
$newChecker=[IO.File]::ReadAllText($packaged)
foreach($candidate in @($updated,$newChecker)){
 $tokens=$null;$parseErrors=$null
 $null=[System.Management.Automation.Language.Parser]::ParseInput($candidate,[ref]$tokens,[ref]$parseErrors)
 if(@($parseErrors).Count -gt 0){throw ('Script syntax failed before installation: '+$parseErrors[0].Message)}
}
if(-not $newChecker.Contains('function Score-Additional')){throw 'Packaged checker is missing additional scenarios'}
$backup=Join-Path $repo ('evaluation\skill-automation\extended-scoring-backup-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $backup|Out-Null
Copy-Item -LiteralPath $runner -Destination (Join-Path $backup 'test-skill.ps1')
Copy-Item -LiteralPath $checker -Destination (Join-Path $backup 'check-skill-results.ps1')
try {
 [IO.File]::WriteAllText($runner,$updated,[Text.UTF8Encoding]::new($true))
 [IO.File]::WriteAllText($checker,$newChecker,[Text.UTF8Encoding]::new($true))
 if(-not ([IO.File]::ReadAllText($runner)).Contains($tag)){throw 'Post-install verification failed'}
} catch {
 Copy-Item -LiteralPath (Join-Path $backup 'test-skill.ps1') -Destination $runner -Force
 Copy-Item -LiteralPath (Join-Path $backup 'check-skill-results.ps1') -Destination $checker -Force
 throw
}
Write-Host 'PASS: Extended evidence checks installed; Eclipse startup and debugger code unchanged.'
Write-Host 'Scored: Java conditional breakpoint, JUnit method, step operations and unsupported capabilities.'
Write-Host 'Conservative PARTIAL: exception throwing origin and existing-session ownership pending independent proof.'
Write-Host 'Blocked Spring Boot and disconnected-bridge scenarios remain blocked without explicit permissions/isolation.'
Write-Host "Backup: $backup"
