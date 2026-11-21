param(
    [Parameter(Mandatory)][string]$EclipseDirectory,
    [Parameter(Mandatory)][string]$Workspace,
    [Parameter(Mandatory)][ValidateRange(1024,65535)][int]$Port
)
$ErrorActionPreference = 'Stop'
$ide = (Resolve-Path -LiteralPath $EclipseDirectory).Path
$workspacePath = (Resolve-Path -LiteralPath $Workspace).Path
if ($Port -eq 8765 -or !(Test-Path "$workspacePath/phase-one-debugging/.agentdebug-fixture")) {
    throw 'Use an isolated port and the disposable workspace prepared by dev.bat deploy.'
}
if (Get-Process eclipse -ErrorAction SilentlyContinue | Where-Object Path -eq "$ide\eclipse.exe") {
    throw 'Close the disposable Eclipse installation before starting the ownership tests.'
}
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$build = (New-Item -ItemType Directory -Force "$repo/e2e-runner/target/ownership-bundle").FullName
& "$PSScriptRoot/build.ps1" $ide $build
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Copy-Item -LiteralPath "$build/ownership-tests.jar" -Destination "$ide/dropins/ownership-tests.jar"
$env:AGENTDEBUG_ECLIPSE_DIR = $ide
$env:AGENTDEBUG_WORKSPACE = $workspacePath
$env:AGENTDEBUG_OWNERSHIP_WORKSPACE = $workspacePath
$env:AGENTDEBUG_PORT = "$Port"
$started = [DateTime]::UtcNow
& "$repo/scripts/windows/dev.bat" deploy
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$report = Join-Path $workspacePath 'ownership-results.json'
$deadline = [DateTime]::UtcNow.AddMinutes(3)
do {
    if ((Test-Path $report) -and (Get-Item $report).LastWriteTimeUtc -ge $started) {
        $result = Get-Content -Raw $report | ConvertFrom-Json
        $result | Format-List
        if ($result.failure -or $result.cleanupFailure -or
            @($result.PSObject.Properties | Where-Object Value -eq 'FAIL').Count -or
            $result.'CONTROLLED retains its marker' -ne 'PASS') { exit 1 }
        Write-Host "PASS: $report"
        exit 0
    }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $deadline)
throw "Timed out waiting for ownership checks. See $workspacePath/.metadata/.log"
