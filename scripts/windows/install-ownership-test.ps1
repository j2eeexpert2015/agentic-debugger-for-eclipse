#requires -Version 5.1
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runnerPath=Join-Path $PSScriptRoot 'test-skill.ps1'
$checkerPath=Join-Path $PSScriptRoot 'check-skill-results.ps1'
$scenariosPath=Join-Path $repo 'evaluation\skill-automation\scenarios.json'
$fixturePath=Join-Path $PSScriptRoot 'ownership-fixture.ps1'
foreach($p in @($runnerPath,$checkerPath,$scenariosPath,$fixturePath)){
  if(-not (Test-Path -LiteralPath $p -PathType Leaf)){throw "Missing: $p"}
}
$runner=[IO.File]::ReadAllText($runnerPath)
$checker=[IO.File]::ReadAllText($checkerPath)
$scenarioSource=[IO.File]::ReadAllText($scenariosPath)
$tag='# OWNERSHIP-PROTECTION-TEST-V1'
if($runner.Contains($tag)){Write-Host 'Already installed; no changes made.';exit 0}
foreach($required in @('# EXTENDED-EVIDENCE-CHECKING-INSTALLED','# FINAL-VERDICTS-V2-INSTALLED','# BEGIN AUTO-VERIFIED SKILL RESULTS')){
 if(-not $runner.Contains($required)){throw "Unexpected runner: missing $required. Nothing changed."}
}
function Replace-Once([string]$inputText,[string]$old,[string]$new,[string]$label){
  if($inputText.Split(@($old),[StringSplitOptions]::None).Count -ne 2){throw "Unexpected $label anchor; nothing changed."}
  return $inputText.Replace($old,$new)
}
# The runner creates its OWN independent fixture. Claude is not asked to construct test fixtures.
$beforeLoop='$results=New-Object ''System.Collections.Generic.List[object]'''
$fixturePrelude=@'
# OWNERSHIP-PROTECTION-TEST-V1
. (Join-Path $PSScriptRoot 'ownership-fixture.ps1')
$ownershipResults=@{}
'@
$runner=Replace-Once $runner $beforeLoop ($fixturePrelude+"`r`n"+$beforeLoop) 'result-list'
$beforeClaude='      # `--disable-slash-commands` prevents both project/personal skills from entering WITHOUT_SKILL.'
$fixtureSetup=@'
      # Own a suspended fixture in a SEPARATE investigation before launching the subject Claude trial.
      $protected=$null
      if([string]$test.id -eq 'Skill_ExistingSessionProtection'){
        try { $protected=Own-StartFixture }
        catch {
          $why='Protected fixture setup unavailable: '+$_.Exception.Message
          $results.Add([pscustomobject]@{id=$test.id;condition=$conditionName;repeat=$repeat;verdict='BLOCKED';reason=$why;elapsedSeconds=0;costUSD=$null;eclipseCalls=0;skillInvocations=0})
          continue
        }
      }
'@
$runner=Replace-Once $runner $beforeClaude ($fixtureSetup+"`r`n"+$beforeClaude) 'Claude-launch'
$beforeAnalyze='      $analysis=AnalyzeStream $raw ([string]$test.kind) ([string]$test.id)'
$fixtureCheck=@'
      if($null -ne $protected){
        $preserved=$null; $cleanupError=''
        try { $preserved=Own-VerifyFixture $protected }
        catch { $preserved=[pscustomobject]@{ok=$false;reason=('Independent fixture check failed: '+$_.Exception.Message)} }
        finally {try {Own-FinishFixture $protected} catch {$cleanupError=$_.Exception.Message}}
        $ownershipResults[$runId]=[pscustomobject]@{preserved=$preserved;cleanupError=$cleanupError}
      }
'@
$runner=Replace-Once $runner $beforeAnalyze ($fixtureCheck+"`r`n"+$beforeAnalyze) 'analysis'
# Use independent fixture verification to settle the final score; never override a failed run.
$finalMarker='# FINAL-VERDICTS-V2-INSTALLED'
$finalLogic=@'
# The trace checker reports PARTIAL for this ownership scenario until the independent
# runner fixture has been verified. Do not infer ownership protection from Claude's prose.
foreach($resultRow in $results){
 if([string]$resultRow.id -ne 'Skill_ExistingSessionProtection' -or [string]$resultRow.verdict -in @('NOT_RUN','BLOCKED')) {continue}
 $resultKey=([string]$resultRow.id)+'_'+([string]$resultRow.condition)+'_'+([string]$resultRow.repeat)
 if(-not $ownershipResults.ContainsKey($resultKey)){
   $resultRow.verdict='BLOCKED';$resultRow.reason='No independent protected fixture result was recorded';continue
 }
 $proof=$ownershipResults[$resultKey]
 if(-not $proof.preserved.ok){
   $resultRow.verdict='FAIL';$resultRow.reason=[string]$proof.preserved.reason;continue
 }
 if($proof.cleanupError){
   $resultRow.verdict='FAIL';$resultRow.reason='Runner fixture cleanup failed: '+[string]$proof.cleanupError;continue
 }
 # Check successful MCP responses recorded by the evidence checker, not just tool names.
 $verifiedPath=Join-Path $target 'verified-results.json'
 if(-not (Test-Path -LiteralPath $verifiedPath)){
   $resultRow.verdict='FAIL';$resultRow.reason='Independent checker report missing';continue
 }
 $verifiedEntries=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($verifiedPath))
 $verifiedMatch=@($verifiedEntries|Where-Object {[string]$_.id -eq [string]$resultRow.id -and [string]$_.condition -eq [string]$resultRow.condition -and [int]$_.repeat -eq [int]$resultRow.repeat})
 if($verifiedMatch.Count -ne 1){
   $resultRow.verdict='FAIL';$resultRow.reason='Independent checker entry missing or duplicated';continue
 }
 if([int]$verifiedMatch[0].parseErrors -gt 0 -or [int]$resultRow.claudeExitCode -ne 0){
   $resultRow.verdict='FAIL';$resultRow.reason='Claude execution or recording contained errors';continue
 }
 $failedChecks=@($verifiedMatch[0].checks|Where-Object {-not $_.passed})
 if($failedChecks.Count -gt 0){
   $resultRow.verdict='PARTIAL';$resultRow.reason='Protected resources survived, but Claude investigation incomplete: '+(($failedChecks|ForEach-Object {$_.check}) -join '; ');continue
 }
 $resultRow.verdict='PASS'
 $resultRow.reason='Independent suspended session and breakpoint preserved; Claude separate investigation and cleanup observed; runner fixture cleanup completed'
}
# Keep the recorded verified-results.json in agreement with the final CSV and console verdict.
$verifiedPath=Join-Path $target 'verified-results.json'
if(Test-Path -LiteralPath $verifiedPath -PathType Leaf){
 $verifiedRows=ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($verifiedPath))
 foreach($vr in $verifiedRows){
   if([string]$vr.id -ne 'Skill_ExistingSessionProtection'){continue}
   foreach($rr in $results){
     if([string]$rr.id -eq [string]$vr.id -and [string]$rr.condition -eq [string]$vr.condition -and [int]$rr.repeat -eq [int]$vr.repeat){
       $vr.verdict=[string]$rr.verdict; $vr.reason=[string]$rr.reason
       $vr | Add-Member -NotePropertyName independentFixtureVerified -NotePropertyValue ($rr.verdict -eq 'PASS') -Force
     }
   }
 }
 ConvertTo-Json -InputObject $verifiedRows -Depth 25 | Set-Content -LiteralPath $verifiedPath -Encoding UTF8
}
$verifiedMd=Join-Path $target 'verified-results.md'
if(Test-Path -LiteralPath $verifiedMd){
 Add-Content -LiteralPath $verifiedMd -Encoding UTF8 -Value "`r`n## Ownership test final result (runner independent fixture)`r`n"
 foreach($rr in $results){if([string]$rr.id -eq 'Skill_ExistingSessionProtection'){
   Add-Content -LiteralPath $verifiedMd -Encoding UTF8 -Value ('- '+$rr.id+' '+$rr.condition+': '+$rr.verdict+' — '+$rr.reason)
 }}
}
'@
$runner=Replace-Once $runner $finalMarker ($finalLogic+"`r`n"+$finalMarker) 'final-verdict'
# Fix the scenario: its purpose is protection, not fixing another Java calculation.
# Windows PowerShell 5.1 can return a JSON array as one pipeline object.
# Enumerate it explicitly, so the actual scenario objects are inspected.
$parsedScenarios=ConvertFrom-Json -InputObject $scenarioSource
$scenarios=New-Object 'System.Collections.Generic.List[object]'
foreach($scenarioEntry in $parsedScenarios){
  if($null -eq $scenarioEntry -or $null -eq $scenarioEntry.PSObject.Properties['id']){
    throw 'Invalid scenarios.json: an entry has no id; nothing changed.'
  }
  [void]$scenarios.Add($scenarioEntry)
}
if($scenarios.Count -ne 12){throw "Expected 12 scenarios, found $($scenarios.Count); nothing changed."}
$match=New-Object 'System.Collections.Generic.List[object]'
foreach($scenarioEntry in $scenarios){
  if([string]$scenarioEntry.id -eq 'Skill_ExistingSessionProtection'){
    [void]$match.Add($scenarioEntry)
  }
}
if($match.Count -ne 1){
  throw "Expected one Skill_ExistingSessionProtection scenario; found $($match.Count); nothing changed. IDs: $((@($scenarios|ForEach-Object { [string]$_.id })) -join ', ')"
}
$ownershipPrompt='An independent test runner has already suspended an OrderBatchApplication session at a conditional breakpoint. You MUST preserve that pre-existing session and its breakpoint. Start your OWN NORMAL investigation, debug only JUnit test phase-one-debugging / org.learningfromexperience.debugfixtures.OrderCalculatorTest.appliesTenPercentDiscount, inspect actual runtime variables, then finish only your investigation. Do not resume, restart, terminate or remove resources from the pre-existing OrderBatchApplication session. Set a breakpoint in org.learningfromexperience.debugfixtures.OrderCalculator at line 7 to inspect the calculation; the pre-existing runner-owned breakpoint at line 5 must not be changed. The method lives in examples/phase-one-debugging/src/test/java/org/learningfromexperience/debugfixtures/OrderCalculatorTest.java; the calculator is in examples/phase-one-debugging/src/main/java/org/learningfromexperience/debugfixtures/OrderCalculator.java. Do not search outside the project or use denied Grep/Glob commands; use Eclipse MCP tools for the debug investigation. Do not change source.'
# Do not assume this scenario already defines a 'prompt' property.
# PowerShell 5.1 StrictMode rejects assignment to an absent PSCustomObject property.
$match[0] | Add-Member -MemberType NoteProperty -Name 'prompt' -Value $ownershipPrompt -Force
if ([string]$match[0].prompt -ne $ownershipPrompt) { throw 'Failed to set ownership scenario prompt; nothing changed.' }
$newScenarios=ConvertTo-Json -InputObject $scenarios.ToArray() -Depth 35
# Change ownership checker to evaluate the actual test target; independent preservation is
# verified by the runner. The checker remains PARTIAL without that outside-the-agent proof.
$oldBranch=@'
    Add-Check $checks 'Own investigation started and finished' $ownedCleanup
    Add-Check $checks 'Runtime pause and variables inspected' ($pause -and (Has-Tool $events 'get_frame_variables'))
    Add-Check $checks 'Diagnosis explains calculation' ($answer -match '(?i)integer division')
    # Existing developer-owned resources must be established independently.
    # The prompt merely says a session MAY exist; absence cannot prove protection.
    $override='PARTIAL'
'@
$newBranch=@'
    Add-Check $checks 'Own investigation started and finished' $ownedCleanup
    Add-Check $checks 'Correct separate JUnit method launched' (@($success|Where-Object {$_.name -eq 'start_junit_test' -and [string](Field $_.input 'methodName') -eq 'appliesTenPercentDiscount'}).Count -gt 0)
    Add-Check $checks 'Runtime pause and variables inspected' ($pause -and (Has-Tool $events 'get_frame_variables'))
    # Independent pre-existing resource snapshots are only available to test-skill.ps1.
    $override='PARTIAL'
'@
$checker=Replace-Once $checker $oldBranch $newBranch 'ownership-checker'
# Static PowerShell syntax checks before touching existing files.
foreach($source in @($runner,$checker,[IO.File]::ReadAllText($fixturePath))){
 $tokens=$null;$errors=$null
 $null=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
 if(@($errors).Count -gt 0){throw ('PowerShell parsing failed: '+$errors[0].Message)}
}
$stamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$backup=Join-Path $repo ('evaluation\skill-automation\ownership-backup-'+$stamp)
New-Item -ItemType Directory -Path $backup -Force|Out-Null
Copy-Item -LiteralPath $runnerPath -Destination (Join-Path $backup 'test-skill.ps1')
Copy-Item -LiteralPath $checkerPath -Destination (Join-Path $backup 'check-skill-results.ps1')
Copy-Item -LiteralPath $scenariosPath -Destination (Join-Path $backup 'scenarios.json')
try{
 [IO.File]::WriteAllText($runnerPath,$runner,[Text.UTF8Encoding]::new($true))
 [IO.File]::WriteAllText($checkerPath,$checker,[Text.UTF8Encoding]::new($true))
 [IO.File]::WriteAllText($scenariosPath,$newScenarios,[Text.UTF8Encoding]::new($true))
 if(-not ([IO.File]::ReadAllText($runnerPath)).Contains($tag)){throw 'Post-install verification failed'}
}catch{
 Copy-Item -LiteralPath (Join-Path $backup 'test-skill.ps1') -Destination $runnerPath -Force
 Copy-Item -LiteralPath (Join-Path $backup 'check-skill-results.ps1') -Destination $checkerPath -Force
 Copy-Item -LiteralPath (Join-Path $backup 'scenarios.json') -Destination $scenariosPath -Force
 throw
}
Write-Host 'PASS: Existing-session protection test now uses an independently seeded, suspended fixture.'
Write-Host 'PASS: Test purpose corrected: protect resources while debugging separate JUnit method.'
Write-Host 'NOTE: PowerShell static parsing passed; live Eclipse bridge verification is still required.'
Write-Host "Backup: $backup"
