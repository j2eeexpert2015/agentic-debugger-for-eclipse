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
function Score-Trace([string]$path) {
  $names=@{
    'Java_ConditionalBreakpoint_RootCause'='Java_ConditionalBreakpoint_RootCause';
    'JUnit_FailingTest_RootCause'='JUnit_FailingTest_RootCause'
  }
  $base=[IO.Path]::GetFileNameWithoutExtension($path)
  # Historical files retain their old IDs; all returned results use canonical names.
  $id=if($base -match '^(?:T-03|Java_ConditionalBreakpoint_RootCause)[-_]') {'Java_ConditionalBreakpoint_RootCause'}
      elseif($base -match '^(?:T-04|JUnit_FailingTest_RootCause)[-_]') {'JUnit_FailingTest_RootCause'}
      else {''}
  $conditionMatch=[regex]::Match($base,'(?<condition>WITH_SKILL|WITHOUT_SKILL)[-_](?<trial>\d+)$')
  Assert-Check $conditionMatch.Success "Invalid test filename: $base"
  $condition=$conditionMatch.Groups['condition'].Value
  $trial=[int]$conditionMatch.Groups['trial'].Value
  $checks=[System.Collections.Generic.List[object]]::new()
  if(-not $names.ContainsKey($id)){
    return [pscustomobject]@{id=$id;name='Unsupported_Scenario';condition=$condition;repeat=$trial;source=$base;verdict='NOT_SCORED';reason='The checker currently supports Java conditional-breakpoint and JUnit failing-test debugging';checks=@()}
  }
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
  return [pscustomobject]@{id=$id;name=$names[$id];condition=$condition;repeat=$trial;source=$base;verdict=$verdict;reason=if($failed.Count){($failed|ForEach-Object {$_.check}) -join '; '}elseif($parseErrors){"Unparseable JSONL lines: $parseErrors"}else{'All recorded assertions passed'};checks=$checks.ToArray();toolCalls=$events.Count;rejectedToolCalls=$mutatingError.Count;parseErrors=$parseErrors}
}

Assert-Check (Test-Path -LiteralPath $ReportDirectory -PathType Container) "Report folder not found: $ReportDirectory"
# Reports are stored in timestamped subdirectories below the skill-automation root.
# Support historical IDs and new descriptive filenames.
$files=@(Get-ChildItem -LiteralPath $ReportDirectory -Filter '*.jsonl' -File -Recurse |
  Where-Object { $_.BaseName -match '^(?:T-0[34]|Java_ConditionalBreakpoint_RootCause|JUnit_FailingTest_RootCause)[-_]' -and $_.BaseName -like $Scenario } |
  Sort-Object FullName)
Assert-Check ($files.Count -gt 0) 'No supported Java or JUnit debugging recordings found in this folder or its subfolders'
$results=[System.Collections.Generic.List[object]]::new()
foreach($f in $files){$results.Add((Score-Trace $f.FullName))}
$out=Join-Path $ReportDirectory 'verified-results.json'
$results.ToArray() | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $out -Encoding UTF8
$md=[System.Collections.Generic.List[string]]::new()
$md.Add('# Eclipse Skill â€” Verified Debugging Results')
$md.Add('')
$md.Add('Checks use actual MCP tool responses, the expected test values, and the final explanation. Only the Java conditional-breakpoint and JUnit failing-test scenarios are scored in this version.')
$md.Add('')
$md.Add('| Test | Descriptive name | Condition | Trial | Result | Details |')
$md.Add('|---|---|---|---:|---|---|')
foreach($r in $results){$md.Add('| '+$r.id+' | '+$r.name.Replace('_',' ')+' | '+$r.condition+' | '+$r.repeat+' | '+$r.verdict+' | '+$r.reason.Replace('|','/')+' |')}
$md.Add('')
foreach($r in $results){$md.Add('## '+$r.id+' â€” '+$r.name.Replace('_',' ')); foreach($c in $r.checks){$md.Add('- '+$(if($c.passed){'PASS'}else{'FAIL'})+': '+$c.check)}; $md.Add('')}
$md | Set-Content -LiteralPath (Join-Path $ReportDirectory 'verified-results.md') -Encoding UTF8
foreach($r in $results){Write-Host ("{0} {1}: {2}" -f $r.id,$r.name,$r.verdict)}
Write-Host "Saved: $out"
if(@($results.ToArray()|Where-Object {$_.verdict -eq 'FAIL'}).Count -gt 0){exit 1}
exit 0

