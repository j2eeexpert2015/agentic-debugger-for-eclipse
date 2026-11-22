#requires -Version 5.1
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$ReportDirectory,
  [string]$Scenario = '*'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Check([bool]$ok,[string]$message) { if(-not $ok){throw $message} }
function Field($o,[string]$k) {
  if($null -eq $o){return $null}
  $p=$o.PSObject.Properties[$k]
  if($null -eq $p){return $null}
  return $p.Value
}
function TextParts($value) {
  if($null -eq $value){return ''}
  if($value -is [string]){return $value}
  $chunks = [System.Collections.Generic.List[string]]::new()
  foreach($p in @($value)) {
    if($p -is [string]) { $chunks.Add($p) }
    elseif((Field $p 'text') -is [string]) { $chunks.Add([string](Field $p 'text')) }
  }
  return ($chunks.ToArray() -join "`n")
}
function Convert-ToolResult($raw) {
  $text=TextParts $raw
  try { return (ConvertFrom-Json -InputObject $text -ErrorAction Stop) } catch { return $null }
}
function Contains-Value($events,[string]$key,[string]$value) {
  foreach($e in $events){
    if($e.name -ne 'get_frame_variables' -or $null -eq $e.output){continue}
    if([string](Field $e.output 'status') -ne 'success'){continue}
    foreach($v in @(Field $e.output 'variables')){
      if([string](Field $v 'name') -eq $key -and [string](Field $v 'value') -eq $value){return $true}
    }
  }
  return $false
}
function Has-Tool($events,[string]$name) { return @($events|Where-Object {$_.name -eq $name -and $null -ne $_.output -and [string](Field $_.output 'status') -eq 'success'}).Count -gt 0 }
function Add-Check($checks,[string]$name,[bool]$passed,[string]$note=''){
 $checks.Add([pscustomobject]@{check=$name;passed=$passed;detail=$note})
}
# Additional checks are deliberately conservative: never claim a PASS from Claude's prose alone.
function Score-Additional([string]$id,[string]$condition,[int]$trial,[string]$base,$events,[string]$answer,[int]$parseErrors) {
  $checks=[System.Collections.Generic.List[object]]::new()
  $skill=@($events|Where-Object {$_.name -eq 'Skill' -and [string](Field $_.input 'skill') -eq 'eclipse-debugger' -and -not $_.error})
  if($condition -eq 'WITH_SKILL') {Add-Check $checks 'Skill tool invoked' ($skill.Count -gt 0)}
  else {Add-Check $checks 'Baseline has no Skill tool call' ($skill.Count -eq 0)}
  $mutations=@($events|Where-Object {$_.name -match '^(start_|set_|remove_|control_|restart_|finish_|begin_|evaluate_expression|configure_step)'})
  $success=@($events|Where-Object {[string](Field $_.output 'status') -eq 'success' -and -not $_.error})
  $begins=@($success|Where-Object {$_.name -eq 'begin_debug_investigation'})
  $finishes=@($success|Where-Object {$_.name -eq 'finish_debug_investigation' -and [bool](Field $_.output 'completed') -and [bool](Field $_.output 'ownedSession')})
  $ownedCleanup=$false
  foreach($f in $finishes){foreach($b in $begins){
    if([string](Field $f.input 'investigationId') -and
       [string](Field $f.input 'investigationId') -eq [string](Field $b.output 'investigationId')){$ownedCleanup=$true}
  }}
  $pause=Has-Tool $events 'wait_for_debug_pause'
  $frames=Has-Tool $events 'get_stack_frames'
  $verdict='FAIL'
  $override=''
  if($id -eq 'Skill_UnsupportedFeatures'){
    # This is a read-only capability test. There is no debugger runtime to inspect.
    # Tool reads such as Grep/Read are allowed, but any mutation is a failure.
    Add-Check $checks 'No Eclipse or debugger mutations' ($mutations.Count -eq 0)
    Add-Check $checks 'Explicitly declines remote attach' (($answer -match '(?i)(remote|attach|JDWP)') -and ($answer -match '(?i)(not established|unavailable|unsupported|no attach tool|deferred|neither capability)'))
    Add-Check $checks 'Explicitly declines watchpoint' (($answer -match '(?i)watchpoint') -and ($answer -match '(?i)(not established|unavailable|unsupported|no watchpoint|deferred|neither capability)'))
  } elseif($id -eq 'Java_ExceptionBreakpoint') {
    Add-Check $checks 'Eclipse bridge connected' (@($events|Where-Object {$_.name -eq 'eclipse_ping' -and $_.raw -match 'PONG'}).Count -gt 0)
    Add-Check $checks 'Correct fixture launched' (@($success|Where-Object {$_.name -eq 'start_java_application' -and [string](Field $_.input 'projectName') -eq 'phase-one-debugging'}).Count -gt 0)
    Add-Check $checks 'Exception breakpoint created' (@($success|Where-Object {$_.name -eq 'set_exception_breakpoint' -and (($_.input | ConvertTo-Json -Compress -Depth 8) -match 'IllegalStateException')}).Count -gt 0)
    Add-Check $checks 'Paused and inspected stack' ($pause -and $frames)
    Add-Check $checks 'Diagnosis mentions target exception' ($answer -match 'IllegalStateException')
    Add-Check $checks 'Investigation cleanup succeeded' $ownedCleanup
    # Stack presence cannot alone establish the ORIGINAL throwing line, so this stays PARTIAL.
    $override='PARTIAL'
  } elseif($id -eq 'Java_StepInto_StepOver_StepReturn') {
    Add-Check $checks 'Correct StepFilter fixture launched' (@($success|Where-Object {$_.name -eq 'start_java_application' -and [string](Field $_.input 'mainType') -match 'StepFilterApplication$'}).Count -gt 0)
    Add-Check $checks 'Debugger paused and stack inspected' ($pause -and $frames)
    $actions=@($success|Where-Object {$_.name -eq 'control_debug_session'}|ForEach-Object {[string](Field $_.input 'action')})
    Add-Check $checks 'STEP_INTO observed' (@($actions|Where-Object {$_ -match '^(STEP_INTO|STEP_IN)$'}).Count -gt 0)
    Add-Check $checks 'STEP_OVER observed' (@($actions|Where-Object {$_ -eq 'STEP_OVER'}).Count -gt 0)
    Add-Check $checks 'STEP_RETURN observed' (@($actions|Where-Object {$_ -match '^(STEP_RETURN|STEP_OUT)$'}).Count -gt 0)
    Add-Check $checks 'Investigation cleanup succeeded' $ownedCleanup
  } elseif($id -eq 'Skill_ExistingSessionProtection') {
    Add-Check $checks 'Own investigation started and finished' $ownedCleanup
    Add-Check $checks 'Runtime pause and variables inspected' ($pause -and (Has-Tool $events 'get_frame_variables'))
    Add-Check $checks 'Diagnosis explains calculation' ($answer -match '(?i)integer division')
    # Existing developer-owned resources must be established independently.
    # The prompt merely says a session MAY exist; absence cannot prove protection.
    $override='PARTIAL'
  }
  Add-Check $checks 'Final Claude response recorded' (-not [string]::IsNullOrWhiteSpace($answer))
  $failed=@($checks.ToArray()|Where-Object {-not $_.passed})
  if($failed.Count -eq 0 -and $parseErrors -eq 0){$verdict='PASS'}
  if($override -and $verdict -eq 'PASS'){$verdict=$override}
  # Absence of skill invocation in a read-only WITH_SKILL test cannot establish
  # skill activation; report this as unconfirmed rather than a debugger failure.
  if($id -eq 'Skill_UnsupportedFeatures' -and $condition -eq 'WITH_SKILL' -and $skill.Count -eq 0 -and $parseErrors -eq 0 -and
     @($failed | Where-Object {$_.check -ne 'Skill tool invoked'}).Count -eq 0) {$verdict='NOT_CONFIRMED'}
  $reason=if($failed.Count){($failed|ForEach-Object {$_.check}) -join '; '}
          elseif($parseErrors){"Unparseable JSONL lines: $parseErrors"}
          elseif($override){'Recorded behavior is plausible, but decisive runtime ownership/throwing-line evidence is not independently confirmed'}
          else{'All recorded assertions passed'}
  return [pscustomobject]@{id=$id;name=$id;condition=$condition;repeat=$trial;source=$base;verdict=$verdict;reason=$reason;checks=$checks.ToArray();toolCalls=$events.Count;parseErrors=$parseErrors}
}

function Score-Trace([string]$path) {
  $aliases=[ordered]@{
    'T-03'='Java_ConditionalBreakpoint_RootCause'
    'T-04'='JUnit_FailingTest_RootCause'
    'T-06'='Java_ExceptionBreakpoint'
    'T-07'='Java_StepInto_StepOver_StepReturn'
    'T-09'='Skill_ExistingSessionProtection'
    'T-10'='Skill_UnsupportedFeatures'
  }
  $base=[IO.Path]::GetFileNameWithoutExtension($path)
  $id=''
  foreach($key in $aliases.Keys) {
    foreach($candidate in @($key,[string]$aliases[$key])) {
      if($base.StartsWith($candidate+'_',[StringComparison]::OrdinalIgnoreCase) -or
         $base.StartsWith($candidate+'-',[StringComparison]::OrdinalIgnoreCase)) {
        $id=[string]$aliases[$key]
        break
      }
    }
    if($id){break}
  }
  Assert-Check ($id -ne '') "Unsupported test filename: $base"
  $conditionMatch=[regex]::Match($base,'(?<condition>WITH_SKILL|WITHOUT_SKILL)[-_](?<trial>\d+)$')
  Assert-Check $conditionMatch.Success "Invalid test filename: $base"
  $condition=$conditionMatch.Groups['condition'].Value
  $trial=[int]$conditionMatch.Groups['trial'].Value
  $checks=[System.Collections.Generic.List[object]]::new()
  $calls=@{}
  $events=[System.Collections.Generic.List[object]]::new()
  $answer=''
  $parseErrors=0
  foreach($line in (Get-Content -LiteralPath $path)){
    if([string]::IsNullOrWhiteSpace($line)){continue}
    try {$o=ConvertFrom-Json -InputObject $line -ErrorAction Stop} catch {$parseErrors++;continue}
    $typ=[string](Field $o 'type')
    if($typ -eq 'assistant'){
      foreach($b in @(Field (Field $o 'message') 'content')){
        if([string](Field $b 'type') -eq 'tool_use'){
          $toolName=[string](Field $b 'name')
          $call=[pscustomobject]@{name=($toolName -replace '^mcp__eclipse-debugger__','');input=(Field $b 'input');output=$null;raw=$null;error=$false}
          $calls[[string](Field $b 'id')]=$call
          $events.Add($call)
        }
      }
    } elseif($typ -eq 'user') {
      foreach($b in @(Field (Field $o 'message') 'content')){
        if([string](Field $b 'type') -ne 'tool_result'){continue}
        $key=[string](Field $b 'tool_use_id')
        if($calls.ContainsKey($key)){
          $call=$calls[$key]
          $call.raw=TextParts (Field $b 'content')
          $call.output=Convert-ToolResult (Field $b 'content')
          $call.error=([bool](Field $b 'is_error'))
        }
      }
    } elseif($typ -eq 'result') {$answer=[string](Field $o 'result')}
  }
  if($id -notin @('Java_ConditionalBreakpoint_RootCause','JUnit_FailingTest_RootCause')) {
    return (Score-Additional $id $condition $trial $base $events.ToArray() $answer $parseErrors)
  }
  $skill=@($events | Where-Object {$_.name -eq 'Skill' -and [string](Field $_.input 'skill') -eq 'eclipse-debugger' -and -not $_.error})
  if($condition -eq 'WITH_SKILL') {
    Add-Check $checks 'Unified skill invoked' ($skill.Count -gt 0)
  } else {
    Add-Check $checks 'No skill invoked in baseline' ($skill.Count -eq 0)
  }
  Add-Check $checks 'Eclipse bridge connected' (@($events|Where-Object {$_.name -eq 'eclipse_ping' -and $_.raw -match 'PONG'}).Count -gt 0)
  $startName=if($id -eq 'Java_ConditionalBreakpoint_RootCause'){'start_java_application'}else{'start_junit_test'}
  $start=@($events|Where-Object {$_.name -eq $startName -and [string](Field $_.output 'status') -eq 'success'})
  if($id -eq 'Java_ConditionalBreakpoint_RootCause'){
    Add-Check $checks 'Correct Java main launched' (@($start|Where-Object {[string](Field $_.input 'projectName') -eq 'phase-one-debugging' -and [string](Field $_.input 'mainType') -match '\.OrderBatchApplication$'}).Count -gt 0)
    Add-Check $checks 'Conditional breakpoint for order 1057' (@($events|Where-Object {$_.name -eq 'set_conditional_breakpoint' -and [string](Field $_.output 'status') -eq 'success' -and [string](Field $_.input 'condition') -match '^\s*orderId\s*==\s*1057\s*$'}).Count -gt 0)
  }else{
    Add-Check $checks 'Correct individual JUnit method launched' (@($start|Where-Object {[string](Field $_.input 'projectName') -eq 'phase-one-debugging' -and [string](Field $_.input 'typeName') -match '\.OrderCalculatorTest$' -and [string](Field $_.input 'methodName') -eq 'appliesTenPercentDiscount'}).Count -gt 0)
    Add-Check $checks 'Stopped in Java calculation method' (@($events|Where-Object {$_.name -eq 'get_stack_frames' -and [string](Field $_.output 'status') -eq 'success' -and @((Field $_.output 'frames')|Where-Object {[string](Field $_ 'className') -match '\.OrderCalculator$' -and [string](Field $_ 'methodName') -eq 'calculate'}).Count -gt 0}).Count -gt 0)
  }
  Add-Check $checks 'JVM suspended at breakpoint' (@($events|Where-Object {$_.name -eq 'wait_for_debug_pause' -and [string](Field $_.output 'status') -eq 'success' -and @((Field $_.output 'threads')|Where-Object {[bool](Field $_ 'suspended')}).Count -gt 0}).Count -gt 0)
  foreach($kv in @(@('orderId','1057'),@('discountPercent','10'),@('discountRate','0.0'),@('amount','120.0'))){
    Add-Check $checks ("Actual JVM value: {0} = {1}" -f $kv[0],$kv[1]) (Contains-Value $events $kv[0] $kv[1])
  }
  Add-Check $checks 'Explains integer division as root cause' (($answer -match '(?i)integer division') -and ($answer -match 'discountPercent\s*/\s*100')) 'Explanation is cross-checked with real variable values above'
  $begins=@($events|Where-Object {$_.name -eq 'begin_debug_investigation' -and [string](Field $_.output 'status') -eq 'success'})
  $finish=@($events|Where-Object {$_.name -eq 'finish_debug_investigation' -and [string](Field $_.output 'status') -eq 'success' -and [bool](Field $_.output 'completed') -and [bool](Field $_.output 'ownedSession')})
  $cleanup=$false
  foreach($f in $finish){foreach($b in $begins){if([string](Field $f.input 'investigationId') -eq [string](Field $b.output 'investigationId')) {$cleanup=$true}}}
  Add-Check $checks 'Owned investigation cleanup succeeded' $cleanup
  $mutatingError=@($events|Where-Object {$_.name -match '^(start_|set_|remove_|control_|finish_|begin_)' -and $_.error})
  # A claimed successful investigation is not enough: its actual debug session
  # and breakpoints must have been inspected after cleanup.
  $finishPos=-1
  for($i=0;$i -lt $events.Count;$i++){
    if($events[$i].name -eq 'finish_debug_investigation' -and [string](Field $events[$i].output 'status') -eq 'success') {$finishPos=$i}
  }
  $noSessions=$false; $noBreakpoints=$false; $checkedSessions=$false; $checkedBreakpoints=$false
  if($finishPos -ge 0) {
    for($i=$finishPos+1;$i -lt $events.Count;$i++){
      $event=$events[$i]
      if($event.name -eq 'list_debug_sessions' -and [string](Field $event.output 'status') -eq 'success'){
        $checkedSessions=$true
        $sessions=@(Field $event.output 'sessions')
        if($sessions.Count -eq 0 -or ($sessions.Count -eq 1 -and $null -eq $sessions[0])){$noSessions=$true}
      }
      if($event.name -eq 'list_breakpoints' -and [string](Field $event.output 'status') -eq 'success'){
        $checkedBreakpoints=$true
        $breakpoints=@(Field $event.output 'breakpoints')
        if($breakpoints.Count -eq 0 -or ($breakpoints.Count -eq 1 -and $null -eq $breakpoints[0])){$noBreakpoints=$true}
      }
    }
  }
  if($checkedSessions){Add-Check $checks 'No remaining debug sessions after cleanup' $noSessions}
  if($checkedBreakpoints){Add-Check $checks 'No remaining breakpoints after cleanup' $noBreakpoints}
  Add-Check $checks 'Final Claude response recorded' (-not [string]::IsNullOrWhiteSpace($answer))
  $failed=@($checks.ToArray()|Where-Object {-not $_.passed})
  $verdict=if($failed.Count -eq 0 -and $parseErrors -eq 0){'PASS'}else{'FAIL'}
  return [pscustomobject]@{id=$id;name=$id;condition=$condition;repeat=$trial;source=$base;verdict=$verdict;reason=if($failed.Count){($failed|ForEach-Object {$_.check}) -join '; '}elseif($parseErrors){"Unparseable JSONL lines: $parseErrors"}else{'All recorded assertions passed'};checks=$checks.ToArray();toolCalls=$events.Count;rejectedToolCalls=$mutatingError.Count;parseErrors=$parseErrors}
}

Assert-Check (Test-Path -LiteralPath $ReportDirectory -PathType Container) "Report folder not found: $ReportDirectory"
# Reports are stored in timestamped subdirectories below the skill-automation root.
# Support historical IDs and new descriptive filenames.
$files=@(Get-ChildItem -LiteralPath $ReportDirectory -Filter '*.jsonl' -File -Recurse |
  Where-Object { $_.BaseName -match '^(T-0[34679]|T-10|Java_ConditionalBreakpoint_RootCause|JUnit_FailingTest_RootCause|Java_ExceptionBreakpoint|Java_StepInto_StepOver_StepReturn|Skill_ExistingSessionProtection|Skill_UnsupportedFeatures)[-_]' -and $_.BaseName -like $Scenario } |
  Sort-Object FullName)
Assert-Check ($files.Count -gt 0) 'No supported debugging recordings found in this folder or its subfolders'
$results=[System.Collections.Generic.List[object]]::new()
foreach($f in $files){$results.Add((Score-Trace $f.FullName))}
$out=Join-Path $ReportDirectory 'verified-results.json'
$results.ToArray() | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $out -Encoding UTF8
$md=[System.Collections.Generic.List[string]]::new()
$md.Add('# Eclipse Skill — Verified Debugging Results')
$md.Add('')
$md.Add('Checks use actual MCP tool responses, the expected test values, and the final explanation. Java/JUnit have fixed-value checks; exception, stepping, ownership and unsupported-feature tests have conservative evidence checks. Ownership remains PARTIAL without confirmed pre-existing sessions.')
$md.Add('')
$md.Add('| Test | Descriptive name | Condition | Trial | Result | Details |')
$md.Add('|---|---|---|---:|---|---|')
foreach($r in $results){$md.Add('| '+$r.id+' | '+$r.name.Replace('_',' ')+' | '+$r.condition+' | '+$r.repeat+' | '+$r.verdict+' | '+$r.reason.Replace('|','/')+' |')}
$md.Add('')
foreach($r in $results){$md.Add('## '+$r.id+' — '+$r.name.Replace('_',' ')); foreach($c in $r.checks){$md.Add('- '+$(if($c.passed){'PASS'}else{'FAIL'})+': '+$c.check)}; $md.Add('')}
$md | Set-Content -LiteralPath (Join-Path $ReportDirectory 'verified-results.md') -Encoding UTF8
foreach($r in $results){Write-Host ("{0} {1}: {2}" -f $r.id,$r.name,$r.verdict)}
Write-Host "Saved: $out"
if(@($results.ToArray()|Where-Object {$_.verdict -eq 'FAIL'}).Count -gt 0){exit 1}
exit 0
