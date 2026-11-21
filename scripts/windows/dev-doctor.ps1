param([int]$WaitSeconds = 0)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path

function Invoke-Bridge([string]$command) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $port)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 10000
        $writer = New-Object IO.StreamWriter($stream, (New-Object Text.UTF8Encoding($false)))
        $writer.WriteLine("AUTH $token $command")
        $writer.Flush()
        $reply = (New-Object IO.StreamReader($stream)).ReadLine() | ConvertFrom-Json
        if ($reply.status -ne 'success') { throw "Bridge $command failed: $($reply.message)" }
        return $reply
    } finally { $client.Dispose() }
}

try {
    Get-Command java, javac, mvn -ErrorAction Stop | ForEach-Object { Write-Host "$($_.Name) $($_.Source)" }
    if (Get-Command git -ErrorAction SilentlyContinue) { git -C $repo status --short --branch }
    $port = if ($env:AGENTDEBUG_PORT) { [int]$env:AGENTDEBUG_PORT } else { 8765 }
    $name = if ($port -eq 8765) { "bridge.token" } else { "bridge-$port.token" }
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        try { $token = [IO.File]::ReadAllText("$env:USERPROFILE\.eclipse-agentdebug\$name").Trim(); $info = Invoke-Bridge 'GET_E2E_INFO'; break }
        catch { if ([DateTime]::UtcNow -ge $deadline) { throw }; Start-Sleep -Seconds 2 }
    } while ($true)
    Write-Host "Loaded Eclipse debugger $($info.bundleVersion) from $($info.loadedFrom)"
    if ($info.loadedFrom -notmatch 'plugins[/\\]org\.learningfromexperience\.eclipse\.agentdebug_') { throw 'Expected the installed debugger bundle. Check which Eclipse instance owns port 8765.' }
    if ($env:AGENTDEBUG_WORKSPACE -and [IO.Path]::GetFullPath($env:AGENTDEBUG_WORKSPACE).TrimEnd("\") -ne $info.workspace.TrimEnd("\")) { throw "Wrong workspace: $($info.workspace)" }
    if ($env:AGENTDEBUG_ECLIPSE_DIR -and [IO.Path]::GetFullPath($env:AGENTDEBUG_ECLIPSE_DIR).TrimEnd("\") -ne ([uri]$info.installDirectory).LocalPath.TrimEnd("\")) { throw "Wrong installation: $($info.installDirectory)" }
    $base = "$repo\eclipse-plugin\target\classes\org\learningfromexperience\eclipse\agentdebug\debug"
    foreach ($item in @{EclipseAgentOperations=$info.operationsSha256; EclipseBreakpointService=$info.breakpointSha256}.GetEnumerator()) {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $digest = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes("$base\$($item.Key).class"))).Replace("-", "") }
        finally { $sha.Dispose() }
        if ($digest -ne $item.Value) { throw "Loaded $($item.Key) differs from the local build. Run dev.bat deploy." }
    }
    $apps = Invoke-Bridge 'LIST_JAVA_APPLICATIONS'
    $tests = Invoke-Bridge 'LIST_JUNIT_TESTS'
    $fixture = 'org.learningfromexperience.debugfixtures'
    if (!($apps.applications | Where-Object { $_.project -eq 'phase-one-debugging' -and $_.mainType -eq "$fixture.OrderBatchApplication" }) -or
        !($tests.tests | Where-Object { $_.projectName -eq 'phase-one-debugging' -and $_.typeName -eq "$fixture.OrderCalculatorTest" -and $_.methodName -eq 'appliesTenPercentDiscount' })) {
        throw 'Import examples/phase-one-debugging into this workspace as an existing Maven project, wait for its build, and retry.'
    }
    Write-Host 'PASS: installed debugger, compiled classes, Java fixture and JUnit discovery.'
} catch { Write-Host "FAIL: $($_.Exception.Message)"; exit 1 }
