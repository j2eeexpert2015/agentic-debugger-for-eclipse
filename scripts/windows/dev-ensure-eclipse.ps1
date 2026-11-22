#requires -Version 5.1
[CmdletBinding()]
param([int]$WaitSeconds = 180, [switch]$StartOnly)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
function Fail([string]$why) { Write-Host "[ECLIPSE READY] FAIL: $why"; exit 1 }
function PathKey([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return '' }
    return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($value)).TrimEnd([char]'\').ToLowerInvariant()
}
function PortListening([int]$port) {
    $c = New-Object Net.Sockets.TcpClient
    try {
        $ar=$c.BeginConnect('127.0.0.1',$port,$null,$null)
        if (-not $ar.AsyncWaitHandle.WaitOne(800)) { return $false }
        try { $c.EndConnect($ar); return $true } catch { return $false }
    } catch { return $false } finally { $c.Close() }
}
try {
    if (-not $env:AGENTDEBUG_ECLIPSE_DIR) { throw 'Missing AGENTDEBUG_ECLIPSE_DIR. Use RUN-WINDOWS-TESTS.cmd.' }
    if (-not $env:AGENTDEBUG_WORKSPACE) { throw 'Missing AGENTDEBUG_WORKSPACE. Use RUN-WINDOWS-TESTS.cmd.' }
    if (-not $env:AGENTDEBUG_PORT) { throw 'Missing AGENTDEBUG_PORT. Use RUN-WINDOWS-TESTS.cmd.' }
    $eclipse = Join-Path $env:AGENTDEBUG_ECLIPSE_DIR 'eclipse.exe'
    if (-not (Test-Path -LiteralPath $eclipse -PathType Leaf)) { throw "Eclipse not found: $eclipse" }
    $workspace = PathKey $env:AGENTDEBUG_WORKSPACE
    $exeKey = PathKey $eclipse
    $port = [int]$env:AGENTDEBUG_PORT
    if ($port -lt 1024 -or $port -gt 65535) { throw "Invalid developer bridge port: $port" }
    if ($workspace -eq '' -or $workspace -eq (PathKey $env:USERPROFILE)) { throw 'Refusing unsafe workspace path.' }
    Write-Host "[ECLIPSE READY] Expected workspace: $workspace"
    Write-Host "[ECLIPSE READY] Expected bridge:    127.0.0.1:$port"

    # Detect the Eclipse process for this exact installation; never stop or hijack other IDEs.
    $processes = @(Get-CimInstance Win32_Process -Filter "Name = 'eclipse.exe'" -ErrorAction Stop | Where-Object { $_.ExecutablePath -and (PathKey $_.ExecutablePath) -eq $exeKey })
    $portOpen = PortListening $port
    if ($portOpen) {
        Write-Host '[ECLIPSE READY] Bridge TCP listener detected. Verifying full identity via doctor.'
    } elseif ($processes.Count -gt 0) {
        # An Eclipse process already exists but bridge is absent. A missing -data does not prove the workspace.
        $matched = @($processes | Where-Object { $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Contains($workspace) })
        if ($matched.Count -eq 0) {
            throw "Eclipse from the configured installation is running, but not listening on port $port and its command line does not identify the test workspace. Close or investigate that instance manually; no second instance will be started."
        }
        Write-Host '[ECLIPSE READY] Matching Eclipse workspace process found; waiting for bridge (no new process).'
    } else {
        $dir=Split-Path -Parent $workspace
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Write-Host '[ECLIPSE READY] No matching Eclipse instance or bridge. Starting dedicated workspace.'
        $psi=New-Object Diagnostics.ProcessStartInfo
        $psi.FileName=$eclipse
        $psi.Arguments='-clean -data "'+$env:AGENTDEBUG_WORKSPACE+'"'
        $psi.UseShellExecute=$true
        [void][Diagnostics.Process]::Start($psi)
    }
    if ($StartOnly) { Write-Host '[ECLIPSE READY] Startup requested. No debugger operations performed.'; exit 0 }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'dev-doctor.ps1') -WaitSeconds $WaitSeconds
    if ($LASTEXITCODE -ne 0) { throw "Doctor verification failed. Check workspace, port, loaded plugin and fixture import. Exit code $LASTEXITCODE." }
    Write-Host '[ECLIPSE READY] PASS: correct Eclipse installation, workspace, bridge, compiled bundle and fixtures.'
    exit 0
} catch { Fail $_.Exception.Message }
