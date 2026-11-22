#requires -Version 5.1
[CmdletBinding()]
param(
  [string]$ReportDirectory = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if(-not $ReportDirectory){$ReportDirectory=Join-Path $repo 'evaluation\reports\skill-automation'}
$checker=Join-Path $PSScriptRoot 'check-skill-results.ps1'
if(-not (Test-Path -LiteralPath $checker)){throw "Missing checker: $checker"}
$traces=@(Get-ChildItem -LiteralPath $ReportDirectory -Filter '*.jsonl' -Recurse -File)
$source=@{}
foreach($id in @('T-03','T-04')){
  $items=@($traces | Where-Object {$_.BaseName -match ('^'+$id+'[-_]') -and $_.BaseName -match 'WITH_SKILL[-_]1$'} | Sort-Object LastWriteTime -Descending)
  if($items.Count -eq 0){throw "No existing $id WITH_SKILL recording found under $ReportDirectory"}
  $source[$id]=$items[0].FullName
}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('skill-scoring-selftest-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
function Run-Check([string]$case,[string]$sourcePath,[scriptblock]$transform,[string]$expected) {
  $dir=Join-Path $temp $case
  New-Item -ItemType Directory -Path $dir | Out-Null
  $path=Join-Path $dir ([IO.Path]::GetFileName($sourcePath))
  $lines=New-Object 'System.Collections.Generic.List[string]'
  foreach($line in (Get-Content -LiteralPath $sourcePath)){
    $o=ConvertFrom-Json -InputObject $line
    if($transform){ & $transform $o }
    $lines.Add(($o | ConvertTo-Json -Depth 70 -Compress))
  }
  $lines | Set-Content -LiteralPath $path -Encoding UTF8
  & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $checker -ReportDirectory $dir | Out-Null
  $exit=$LASTEXITCODE
  $resultPath=Join-Path $dir 'verified-results.json'
  if(-not (Test-Path $resultPath)){throw "$case produced no result"}
  $parsed=@(ConvertFrom-Json -InputObject (Get-Content -LiteralPath $resultPath -Raw))
  if($parsed.Count -ne 1){throw "$case expected 1 result, got $($parsed.Count)"}
  if($parsed[0].verdict -ne $expected){throw "$case expected $expected; got $($parsed[0].verdict): $($parsed[0].reason)"}
  if(($expected -eq 'PASS' -and $exit -ne 0) -or ($expected -eq 'FAIL' -and $exit -eq 0)){throw "$case unexpected checker exit code: $exit"}
  Write-Host "PASS selftest: $case => $expected"
}
try {
  Run-Check 'RecordedJavaSuccess' $source['T-03'] $null 'PASS'
  Run-Check 'RecordedJUnitSuccess' $source['T-04'] $null 'PASS'
  # Remove the successful frame-variable tool results, not just the final explanation.
  $missingJvm = {
    param($o)
    if($o.type -eq 'user'){
      foreach($b in @($o.message.content)){
        if($b.type -eq 'tool_result' -and (ConvertTo-Json -InputObject $b.content -Depth 25 -Compress) -match 'discountRate'){
          $b.content=@([pscustomobject]@{type='text';text='{"status":"error","message":"Simulated missing JVM values"}'})
        }
      }
    }
  }
  Run-Check 'RejectMissingJVMValues' $source['T-04'] $missingJvm 'FAIL'
  $wrongMethod = {
    param($o)
    if($o.type -eq 'assistant'){
      foreach($b in @($o.message.content)){
        if($b.type -eq 'tool_use' -and $b.name -eq 'mcp__eclipse-debugger__start_junit_test'){
          $b.input.methodName='aDifferentTest'
        }
      }
    }
  }
  Run-Check 'RejectWrongJUnitMethod' $source['T-04'] $wrongMethod 'FAIL'
  # Cleanup mutation uses call id mapping rather than assuming a particular JSON escape format.
  $cleanupId=''
  foreach($line in (Get-Content -LiteralPath $source['T-03'])) {
    $o=ConvertFrom-Json -InputObject $line
    if($o.type -eq 'assistant') {
      foreach($b in @($o.message.content)) {if($b.type -eq 'tool_use' -and $b.name -eq 'mcp__eclipse-debugger__finish_debug_investigation'){$cleanupId=[string]$b.id}}
    }
  }
  if(-not $cleanupId){throw 'Could not find cleanup tool call in Java recording'}
  $badCleanup={
    param($o)
    if($o.type -eq 'user'){
      foreach($b in @($o.message.content)){
        if($b.type -eq 'tool_result' -and [string]$b.tool_use_id -eq $cleanupId){
          $b.content=@([pscustomobject]@{type='text';text='{"status":"error","completed":false,"ownedSession":false}'})
        }
      }
    }
  }.GetNewClosure()
  Run-Check 'RejectFailedCleanup' $source['T-03'] $badCleanup 'FAIL'
  Write-Host 'PASS: two real recordings accepted, three intentionally wrong recordings rejected.'
} finally {
  Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
