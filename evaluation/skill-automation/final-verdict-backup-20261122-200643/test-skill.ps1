# MEANINGFUL-SCENARIO-IDS-INSTALLED
#requires -Version 5.1
# Activation test patch: Skill_ExplicitActivation explicit CLI probe; Skill_AutomaticActivation automatic behavior remains separate.
[CmdletBinding()]
param(
    [ValidateSet('preflight','smoke','selected','full','compare','analyze','selftest')]
    [string]$Mode = 'preflight',
    [string[]]$Scenario = @(),
    [ValidateSet('WITH_SKILL','WITHOUT_SKILL')]
    [string]$Condition = 'WITH_SKILL',
    [ValidateRange(1,20)][int]$Repeats = 1,
    [string]$Model = '',
    [ValidateRange(1,100)][int]$MaxTurns = 18,
    [switch]$AllowDisposableEclipse,
    [switch]$ApproveFixturePost,
    [switch]$IsolatedDisconnectedBridge,
    [switch]$RunIndependentRegression,
    [switch]$DryRun,
    [string]$ReportDirectory = '',
    [string]$ClaudeExecutable = 'claude',
    [string]$McpAllowedToolPattern = 'mcp__eclipse-debugger__*'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$scenarioFile = Join-Path $repo 'evaluation\skill-automation\scenarios.json'
$reportsRoot = Join-Path $repo 'evaluation\reports\skill-automation'
function Ensure([bool]$ok,[string]$message) { if (-not $ok) { throw $message } }
function WriteJsonFile($object,[string]$path) { $object | ConvertTo-Json -Depth 35 | Set-Content -LiteralPath $path -Encoding UTF8 }
function Get-Js($obj,[string]$key) { if ($null -eq $obj) { return $null }; if ($obj -is [System.Collections.IDictionary]) { return $obj[$key] }; $p=$obj.PSObject.Properties[$key]; if($null -eq $p){return $null}; return $p.Value }
function AnalyzeStream([string]$path,[string]$kind,[string]$id) {
    $toolNames = New-Object 'System.Collections.Generic.List[string]'
    $toolInputs = New-Object 'System.Collections.Generic.List[object]'
    $eventTypes = New-Object 'System.Collections.Generic.List[string]'
    $resultText = ''
    $cost = $null
    $turns = $null
    $errors = 0
    $parsed = 0
    foreach($line in (Get-Content -LiteralPath $path -ErrorAction Stop)) {
        if([string]::IsNullOrWhiteSpace($line)){continue}
        try { $o=ConvertFrom-Json -InputObject $line -ErrorAction Stop } catch { continue }
        $parsed++
        $typ=[string](Get-Js $o 'type')
        $eventTypes.Add($typ)
        if($typ -eq 'assistant') {
            $m=Get-Js $o 'message'
            foreach($b in @(Get-Js $m 'content')) {
                if((Get-Js $b 'type') -eq 'tool_use') {
                    $name=[string](Get-Js $b 'name')
                    $toolNames.Add($name)
                    $toolInputs.Add([pscustomobject]@{name=$name;input=(Get-Js $b 'input')})
                }
            }
        }
        if($typ -eq 'result') {
            $resultText=[string](Get-Js $o 'result')
            $cost=Get-Js $o 'total_cost_usd'
            $turns=Get-Js $o 'num_turns'
            if((Get-Js $o 'is_error') -eq $true){$errors++}
        }
        if($typ -eq 'user') {
            $m=Get-Js $o 'message'
            foreach($b in @(Get-Js $m 'content')) {
                if((Get-Js $b 'is_error') -eq $true){$errors++}
            }
        }
    }
    $skills = @($toolNames | Where-Object { $_ -eq 'Skill' -or $_ -match '(^|__)Skill$' })
    $eclipse = @($toolNames | Where-Object { $_ -like 'mcp__eclipse-debugger__*' -or $_ -like '*eclipse*debugger*' })
    $mutations = @($toolNames | Where-Object { $_ -match '(start_|set_|remove_|restart_|control_|finish_debug_|begin_debug_|evaluate_expression|configure_step)' })
    $readObservations = @($toolNames | Where-Object { $_ -match '(get_stack_frames|get_frame_variables|expand_frame_variable|get_debug_threads|get_debug_status|wait_for_debug_pause)' })
    $verdict = 'PARTIAL'
    $why = 'Tool trace alone does not prove correct JVM values/root cause, so an independent oracle is required.'
    switch($kind){
        'explicit_skill' { if($mutations.Count -gt 0){$verdict='FAIL';$why='Mutating tool detected'} elseif($skills.Count -gt 0){$verdict='PASS';$why='Explicit skill invocation observed in tool stream'} else {$verdict='NOT_CONFIRMED';$why='Slash command submitted but no skill invocation event captured; cannot certify loaded skill'} }
        'auto_skill' { if($skills.Count -gt 0 -and $mutations.Count -eq 0){$verdict='PASS';$why='Skill invocation observed without debugger mutation'} elseif($skills.Count -eq 0){$verdict='NOT_CONFIRMED';$why='No explicit Skill tool invocation in recorded events'} else {$verdict='FAIL';$why='Mutating operation detected in no-launch activation test'} }
        'no_debug' { if($eclipse.Count -eq 0 -and $mutations.Count -eq 0){$verdict='PASS';$why='No debugger MCP calls or mutations'} else {$verdict='FAIL';$why='Unrequested debugger call observed'} }
        'consent' { if($mutations.Count -eq 0){$verdict='PASS';$why='No forbidden mutating/debug-launch call observed'} else {$verdict='FAIL';$why='Forbidden mutating call observed'} }
        'unsupported' { if($eclipse.Count -eq 0 -and $mutations.Count -eq 0){$verdict='PARTIAL';$why='No unsafe tools called; correctness of unsupported-feature claim needs review'} else {$verdict='FAIL';$why='Unexpected Eclipse MCP call for unsupported action'} }
        'unavailable' { if($mutations.Count -eq 0){$verdict='PARTIAL';$why='No mutation, but offline/negative assertions require isolated bridge evidence'} else {$verdict='FAIL';$why='Unexpected mutating action in disconnected-bridge scenario'} }
        'runtime' { if($eclipse.Count -eq 0){$verdict='FAIL';$why='No Eclipse MCP tool call; no runtime debugging evidence'} elseif($readObservations.Count -eq 0){$verdict='PARTIAL';$why='MCP invoked, but no stack/thread/variable observation identified'} }
    }
    return [pscustomobject]@{scenario=$id;verdict=$verdict;reason=$why;skillInvocations=$skills.Count;eclipseCalls=$eclipse.Count;mutationCalls=$mutations.Count;inspectionCalls=$readObservations.Count;toolCalls=$toolNames.ToArray();inputs=$toolInputs.ToArray();parsedEvents=$parsed;claudeErrors=$errors;turns=$turns;costUSD=$cost;answer=$resultText}
}
if($Mode -eq 'selftest') {
    $dir=Join-Path ([IO.Path]::GetTempPath()) ('skill-harness-selftest-'+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $dir | Out-Null
    try {
      $f=Join-Path $dir 'mock.jsonl'
      @(
        '{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Skill","input":{"skill":"eclipse-debugger"}}]}}',
        '{"type":"result","result":"Read-only plan","is_error":false,"num_turns":2,"total_cost_usd":0.01}'
      ) | Set-Content -LiteralPath $f
      $a=AnalyzeStream $f 'auto_skill' 'Skill_AutomaticActivation'
      Ensure ($a.verdict -eq 'PASS') 'Selftest activation failed'
      $a0=AnalyzeStream $f 'explicit_skill' 'Skill_ExplicitActivation'
      Ensure ($a0.verdict -eq 'PASS') 'Selftest explicit activation failed'
      Ensure ($a.toolCalls.Count -eq 1 -and $a.toolCalls[0] -eq 'Skill') 'Selftest tool-call array conversion failed'
      $b=AnalyzeStream $f 'runtime' 'Java_ConditionalBreakpoint_RootCause'
      Ensure ($b.verdict -eq 'FAIL') 'Selftest no-runtime-tools failure detection failed'
      Write-Host 'PASS: mock JSONL parser, activation and missing-runtime-evidence guards.'
    } finally { Remove-Item -Recurse -Force $dir }
    exit 0
}
Ensure (Test-Path $scenarioFile) "Missing scenarios: $scenarioFile"
# PowerShell 5.1: normalize the top-level JSON array explicitly, without relying
# on pipeline enumeration (which can yield one nested array or no usable items).
$scenarioJson = Get-Content -LiteralPath $scenarioFile -Raw -ErrorAction Stop
$parsedScenarios = ConvertFrom-Json -InputObject $scenarioJson -ErrorAction Stop
$scenarios = New-Object 'System.Collections.Generic.List[object]'
foreach ($candidate in $parsedScenarios) {
    Ensure ($null -ne $candidate) 'scenarios.json contains a null scenario'
    Ensure (-not [string]::IsNullOrWhiteSpace([string](Get-Js $candidate 'id'))) 'scenarios.json has a scenario without an id'
    Ensure (-not [string]::IsNullOrWhiteSpace([string](Get-Js $candidate 'prompt'))) "scenarios.json has a scenario without a prompt"
    $scenarios.Add($candidate)
}
Ensure ($scenarios.Count -ge 11) "Expected at least 11 scenarios (Skill_AutomaticActivation through Skill_EclipseConnectionFailure); found $($scenarios.Count) at $scenarioFile"
$scenarioIds = @($scenarios.ToArray() | ForEach-Object { [string]$_.id })
foreach ($id in @('Skill_AutomaticActivation','Skill_NoUnnecessaryDebugging','Java_ConditionalBreakpoint_RootCause','JUnit_FailingTest_RootCause','SpringBoot_HTTPRequest_RootCause','Java_ExceptionBreakpoint','Java_StepInto_StepOver_StepReturn','Skill_TracepointConsent','Skill_ExistingSessionProtection','Skill_UnsupportedFeatures','Skill_EclipseConnectionFailure')) {
    Ensure ($scenarioIds -contains $id) "Required scenario missing from scenarios.json: $id"
}
$skillSource=Join-Path $repo 'skills\eclipse-debugger\SKILL.md'
$globalSkill=Join-Path $env:USERPROFILE '.claude\skills\eclipse-debugger\SKILL.md'
$skillPresent=Test-Path $globalSkill
$cli=Get-Command $ClaudeExecutable -ErrorAction SilentlyContinue
$preflight=[ordered]@{
  repo=$repo;skillSourceExists=(Test-Path $skillSource);globalSkillExists=$skillPresent;
  claudeLocated=($null -ne $cli);claudeCommand=if($cli){$cli.Source}else{$null};
  isolatedDisposableAuthorized=[bool]$AllowDisposableEclipse;approvedFixturePost=[bool]$ApproveFixturePost;
  debuggerToolsPattern=$McpAllowedToolPattern;note='No live Windows/Eclipse session has been tested by preparing these files.'
}
if($Mode -eq 'preflight') { $preflight | ConvertTo-Json -Depth 5; exit $(if($cli -and (Test-Path $skillSource)){0}else{2}) }
$target=if($ReportDirectory){$ReportDirectory}else{Join-Path $reportsRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))}
New-Item -ItemType Directory -Force -Path $target | Out-Null
WriteJsonFile $preflight (Join-Path $target 'preflight.json')
if($Mode -eq 'analyze') {
    $samples=Get-ChildItem -Path $target -Filter '*.jsonl' -ErrorAction SilentlyContinue
    foreach($s in $samples){ WriteJsonFile (AnalyzeStream $s.FullName 'runtime' $s.BaseName) (Join-Path $target ($s.BaseName+'.analysis.json')) }
    Write-Host "Analyzed $($samples.Count) JSONL files in $target"; exit 0
}
Ensure ($null -ne $cli -or $DryRun) 'Claude Code CLI not found. Run claude --version in Windows CMD first.'
Ensure (Test-Path $skillSource) 'Repository skill source missing.'
# Use explicit selection against normalized scenario IDs. Empty selection must fail;
# a zero-row report is NEVER evidence of a successful smoke test.
$selected = New-Object 'System.Collections.Generic.List[object]'
$smokeIds = @('Skill_ExplicitActivation','Skill_AutomaticActivation','Skill_NoUnnecessaryDebugging','Skill_TracepointConsent','Skill_UnsupportedFeatures')
$requestedIds = @()
if ($Mode -eq 'selected') {
    $requestedIds = @($Scenario | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    Ensure ($requestedIds.Count -gt 0) 'selected requires -Scenario Java_ConditionalBreakpoint_RootCause,JUnit_FailingTest_RootCause'
}
foreach ($test in $scenarios) {
    $id = [string]$test.id
    if ($Mode -eq 'smoke' -and $smokeIds -notcontains $id) { continue }
    if ($Mode -eq 'selected' -and $requestedIds -notcontains $id) { continue }
    $selected.Add($test)
}
Ensure ($selected.Count -gt 0) "No scenarios selected for mode $Mode. Refusing to create an empty success report."
if ($Mode -eq 'smoke') {
    Ensure ($selected.Count -eq $smokeIds.Count) "Smoke mode should select 4 scenarios; got $($selected.Count)"
}
Write-Host "Selected $($selected.Count) scenarios: $((@($selected.ToArray() | ForEach-Object { $_.id })) -join ', ')"
$conditions=if($Mode -eq 'compare'){@('WITHOUT_SKILL','WITH_SKILL')}else{@($Condition)}
if($Mode -eq 'compare') {Ensure ($Repeats -ge 2) 'Compare requires -Repeats 2 or more (five recommended)'}
$manifest=[ordered]@{createdUtc=[DateTime]::UtcNow.ToString('o');mode=$Mode;model=$Model;conditions=$conditions;repeats=$Repeats;scenarios=@($selected.ToArray() | ForEach-Object {$_.id});preflight=$preflight;status='UNVERIFIED_ENVIRONMENT';important='Runtime verdicts require independent Eclipse/JVM oracle; this runner does not infer them from Claude prose.'}
WriteJsonFile $manifest (Join-Path $target 'manifest.json')
$results=New-Object 'System.Collections.Generic.List[object]'
foreach($conditionName in $conditions){
  if($conditionName -eq 'WITH_SKILL' -and -not $skillPresent -and -not $DryRun) { Write-Warning "Global skill is not installed at $globalSkill; WITH_SKILL trials will be BLOCKED." }
  foreach($test in $selected.ToArray()){
    for($repeat=1;$repeat -le $Repeats;$repeat++){
      $reason=''
      # Skill_ExplicitActivation is an automated non-interactive explicit slash-command probe.
      if($conditionName -eq 'WITH_SKILL' -and -not $skillPresent){$reason='Global skill not installed. Cannot claim with-skill condition.'}
      if($test.requiresDebugger -and -not $AllowDisposableEclipse){$reason='Requires -AllowDisposableEclipse for disposable fixture only'}
      if((Get-Js $test 'requiresHttpApproval') -eq $true -and -not $ApproveFixturePost){$reason='SpringBoot_HTTPRequest_RootCause requires -ApproveFixturePost after confirming localhost fixture target'}
      if((Get-Js $test 'manualIsolationRequired') -eq $true -and -not $IsolatedDisconnectedBridge){$reason='Skill_EclipseConnectionFailure requires an independently isolated disconnected test bridge'}
            # Stable ID plus readable scenario name in every new trace filename.
      $testDisplayName = [string](Get-Js $test 'name')
      if([string]::IsNullOrWhiteSpace($testDisplayName)) {$testDisplayName=[string]$test.id}
      $testFileName=($testDisplayName -replace '[^A-Za-z0-9_]+','_').Trim('_')
      $runId=$test.id+'_'+$conditionName+'_'+$repeat
      $raw=Join-Path $target ($runId+'.jsonl')
      $stderr=Join-Path $target ($runId+'.stderr.txt')
      if($reason){$results.Add([pscustomobject]@{id=$test.id;condition=$conditionName;repeat=$repeat;verdict='NOT_RUN';reason=$reason;elapsedSeconds=0;costUSD=$null;eclipseCalls=0;skillInvocations=0});continue}
      if($DryRun){$results.Add([pscustomobject]@{id=$test.id;condition=$conditionName;repeat=$repeat;verdict='NOT_RUN';reason='DRY RUN';elapsedSeconds=0;costUSD=$null;eclipseCalls=0;skillInvocations=0});continue}
      # `--disable-slash-commands` prevents both project/personal skills from entering WITHOUT_SKILL.
      # Never allow bypassPermissions. Explicitly allow only Eclipse MCP tools; Claude can still be denied by local policies.
      $cliArgs=@('-p',[string]$test.prompt,'--output-format','stream-json','--verbose','--max-turns',[string]$MaxTurns,'--permission-mode','dontAsk','--allowedTools',('Skill,'+$McpAllowedToolPattern))
      if($Model){$cliArgs+=@('--model',$Model)}
      if($conditionName -eq 'WITHOUT_SKILL'){$cliArgs+='--disable-slash-commands'}
      $clock=[System.Diagnostics.Stopwatch]::StartNew()
      $rc=-1
      try {
        # Use native PowerShell invocation to preserve Unicode and multi-line prompts as one argument.
        $output=@(& $cli.Source @cliArgs 2>&1 | ForEach-Object { $_.ToString() })
        $rc=$LASTEXITCODE
        $output | Set-Content -LiteralPath $raw -Encoding UTF8
      } catch {
        $_.Exception.ToString() | Set-Content -LiteralPath $stderr -Encoding UTF8
        @() | Set-Content -LiteralPath $raw -Encoding UTF8
      } finally { $clock.Stop() }
      $analysis=AnalyzeStream $raw ([string]$test.kind) ([string]$test.id)
      if($rc -ne 0){$analysis.verdict='BLOCKED';$analysis.reason="Claude exited $rc; review stderr/output; no functional verdict"}
      if($analysis.parsedEvents -eq 0){$analysis.verdict='BLOCKED';$analysis.reason='No parseable Claude stream-json events'}
      if($conditionName -eq 'WITH_SKILL' -and $analysis.skillInvocations -eq 0 -and $analysis.verdict -eq 'PASS' -and $test.kind -notin @('no_debug','consent','unsupported')) {
        $analysis.verdict='NOT_CONFIRMED';$analysis.reason='No observable skill activation; cannot attribute result to skill'
      }
      WriteJsonFile $analysis (Join-Path $target ($runId+'.analysis.json'))
      $results.Add([pscustomobject]@{id=$test.id;condition=$conditionName;repeat=$repeat;verdict=$analysis.verdict;reason=$analysis.reason;elapsedSeconds=[math]::Round($clock.Elapsed.TotalSeconds,2);costUSD=$analysis.costUSD;eclipseCalls=$analysis.eclipseCalls;skillInvocations=$analysis.skillInvocations;claudeExitCode=$rc})
      Write-Host "$runId : $($analysis.verdict) ($($analysis.reason))"
    }
  }
}
# BEGIN AUTO-VERIFIED SKILL RESULTS
# This section only checks recorded trace files. It does not launch Eclipse or Claude.
$checkerPath = Join-Path $PSScriptRoot 'check-skill-results.ps1'
$scoringFiles=@(Get-ChildItem -LiteralPath $target -Filter '*.jsonl' -File |
   Where-Object {$_.BaseName -match '^(Java_ConditionalBreakpoint_RootCause|JUnit_FailingTest_RootCause)[-_]'})
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
$csv=Join-Path $target 'trial-results.csv'
$results | Export-Csv -LiteralPath $csv -NoTypeInformation -Encoding UTF8
$lines=New-Object 'System.Collections.Generic.List[string]'
$lines.Add('# Eclipse debugger skill automation â€” provisional report')
$lines.Add('')
$lines.Add('Do not interpret `PARTIAL`, `NOT_CONFIRMED` or `NOT_RUN` as passes. Runtime debugging requires JVM-ground-truth oracles.')
$lines.Add('')
$lines.Add('| Scenario | Test name | Condition | Trial | Verdict | Reason |')
$lines.Add('|---|---|---|---:|---|---|')
foreach($r in $results){$lines.Add('| '+$r.id+' | '+$r.testName.Replace('_',' ')+' | '+$r.condition+' | '+$r.repeat+' | '+$r.verdict+' | '+(([string]$r.reason).Replace('|','/'))+' |')}
$lines.Add('')
$lines.Add('Raw Claude JSONL traces and parsed tool-call analyses are stored in this directory. Redact secrets before sharing.')
$lines | Set-Content -LiteralPath (Join-Path $target 'comparison.md') -Encoding UTF8
Write-Host "Report: $target"
if(@($results | Where-Object {$_.verdict -in @('FAIL','BLOCKED')}).Count -gt 0){exit 1}
exit 0


