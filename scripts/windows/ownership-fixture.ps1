#requires -Version 5.1
# Internal helper loaded by test-skill.ps1. Never run this directly.
# Creates a runner-owned suspended Java session + conditional breakpoint in disposable Eclipse.
# The Claude trial starts a separate JUnit debugger session and must leave these untouched.
function Own-Encode([string]$s) {
  return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)).TrimEnd('=').Replace('+','-').Replace('/','_')
}
function Own-Call([string]$command) {
  $port = 21765  # Dedicated test bridge configured in scripts/windows/dev-settings.cmd
  $tokenPath = Join-Path $env:USERPROFILE ('.eclipse-agentdebug\bridge-' + $port + '.token')
  if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) { throw "Dedicated Eclipse bridge token missing: $tokenPath" }
  $token = [IO.File]::ReadAllText($tokenPath).Trim()
  if (-not $token) { throw 'Dedicated bridge token is empty' }
  $client = [Net.Sockets.TcpClient]::new()
  try {
    $task = $client.ConnectAsync('127.0.0.1',$port)
    if (-not $task.Wait(4000)) { throw 'Dedicated Eclipse bridge connection timed out' }
    $client.ReceiveTimeout=30000; $client.SendTimeout=5000
    $stream=$client.GetStream()
    $writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($false))
    $writer.NewLine="`n"; $writer.AutoFlush=$true
    $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
    $writer.WriteLine('AUTH ' + $token + ' ' + $command)
    $line=$reader.ReadLine()
    if (-not $line) { throw "Empty Eclipse bridge response for $($command.Split(' ')[0])" }
    try { $data=ConvertFrom-Json -InputObject $line -ErrorAction Stop }
    catch { throw "Unexpected Eclipse bridge response for $($command.Split(' ')[0])" }
    if ([string]$data.status -ne 'success') { throw "Eclipse bridge command failed: $($command.Split(' ')[0]): $line" }
    return $data
  } finally { $client.Close() }
}
function Own-Sessions { return (Own-Call 'LIST_DEBUG_SESSIONS') }
function Own-Breakpoints { return (Own-Call 'LIST_BREAKPOINTS') }
function Own-StartFixture {
  $existingSessions=Own-Sessions
  $existingBreakpoints=Own-Breakpoints
  # Do not modify a workspace containing somebody else's debugging resources.
  if (@($existingSessions.sessions).Count -ne 0 -or @($existingBreakpoints.breakpoints).Count -ne 0) {
    throw 'Dedicated workspace already has debugging sessions/breakpoints; refusing to seed over existing resources'
  }
  $begin=Own-Call ('BEGIN_INVESTIGATION ' + (Own-Encode 'NORMAL'))
  $id=[string]$begin.investigationId
  if (-not $id) { throw 'Fixture investigation ID missing' }
  $state=[pscustomobject]@{investigationId=$id;launchName='';breakpointType='org.learningfromexperience.debugfixtures.OrderCalculator';lineNumber=5}
  try {
    $cmd='SET_CONDITIONAL_BREAKPOINT '+(Own-Encode $state.breakpointType)+' 5 '+(Own-Encode 'orderId == 1057')+' '+(Own-Encode $id)
    $bp=Own-Call $cmd
    if ($bp.created -ne $true) { throw 'Fixture conditional breakpoint was not newly created' }
    $launch=Own-Call ('START_JAVA_APPLICATION '+(Own-Encode 'phase-one-debugging')+' '+(Own-Encode 'org.learningfromexperience.debugfixtures.OrderBatchApplication')+' '+(Own-Encode $id))
    if ($launch.reusedSession -eq $true) { throw 'Fixture reused an existing session instead of creating a protected one' }
    $state.launchName=[string]$launch.launchName
    if (-not $state.launchName) { throw 'Fixture launchName missing' }
    $paused=$false
    for($i=0;$i -lt 40;$i++) {
      $threads=Own-Call ('GET_THREADS '+(Own-Encode $state.launchName))
      if (($threads | ConvertTo-Json -Depth 20 -Compress) -match '"suspended"\s*:\s*true') { $paused=$true;break }
      Start-Sleep -Milliseconds 250
    }
    if (-not $paused) { throw 'Fixture did not suspend at its breakpoint' }
    Write-Host 'OWNERSHIP SETUP: protected OrderBatchApplication session and conditional breakpoint created.'
    return $state
  } catch {
    try { $null=Own-Call ('FINISH_INVESTIGATION '+(Own-Encode $id)) } catch { Write-Warning ('Fixture setup cleanup failed: '+$_.Exception.Message) }
    throw
  }
}
function Own-VerifyFixture($state) {
  $sessions=Own-Sessions; $breakpoints=Own-Breakpoints
  $sessionFound=@($sessions.sessions | Where-Object { [string]$_.launchName -eq $state.launchName }).Count -gt 0
  $breakpointFound=@($breakpoints.breakpoints | Where-Object { [string]$_.typeName -eq $state.breakpointType -and [int]$_.lineNumber -eq $state.lineNumber }).Count -gt 0
  if (-not $sessionFound -or -not $breakpointFound) { return [pscustomobject]@{ok=$false;reason='Protected session or breakpoint disappeared during Claude investigation'} }
  $threads=Own-Call ('GET_THREADS '+(Own-Encode $state.launchName))
  $paused=($threads | ConvertTo-Json -Depth 20 -Compress) -match '"suspended"\s*:\s*true'
  if (-not $paused) { return [pscustomobject]@{ok=$false;reason='Protected session was resumed or is no longer suspended'} }
  return [pscustomobject]@{ok=$true;reason='Protected session remains suspended and original breakpoint remains present'}
}
function Own-FinishFixture($state) {
  $finish=Own-Call ('FINISH_INVESTIGATION '+(Own-Encode $state.investigationId))
  if ($finish.completed -ne $true) { throw 'Runner fixture cleanup did not complete' }
}
