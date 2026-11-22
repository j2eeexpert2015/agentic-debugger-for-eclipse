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

function Ensure-Compiled-PluginClasses([string]$root) {
    $base = Join-Path $root 'eclipse-plugin\target\classes\org\learningfromexperience\eclipse\agentdebug\debug'
    $needed = @('EclipseAgentOperations.class', 'EclipseBreakpointService.class')
    $missing = @($needed | Where-Object { -not (Test-Path -LiteralPath (Join-Path $base $_) -PathType Leaf) })

    if ($missing.Count -gt 0) {
        Write-Host "[DOCTOR] Compiled plugin classes missing: $($missing -join ', ')"
        Write-Host '[DOCTOR] Building local source automatically (mvn -DskipTests package). This does NOT redeploy Eclipse.'
        Push-Location $root
        try {
            & mvn.cmd '-DskipTests' 'package' | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "Automatic Maven build failed (exit $LASTEXITCODE). See Maven output above." }
        } finally { Pop-Location }
        $stillMissing = @($needed | Where-Object { -not (Test-Path -LiteralPath (Join-Path $base $_) -PathType Leaf) })
        if ($stillMissing.Count -gt 0) {
            throw "Build succeeded but compiled plugin classes remain missing: $($stillMissing -join ', ')"
        }
        Write-Host '[DOCTOR] Local plugin classes built successfully.'
    } else {
        Write-Host '[DOCTOR] Local compiled plugin classes found; no build needed.'
    }
    return $base
}

try {
    Get-Command java, javac, mvn -ErrorAction Stop | ForEach-Object { Write-Host "$($_.Name) $($_.Source)" }
    if (Get-Command git -ErrorAction SilentlyContinue) { git -C $repo status --short --branch }
    $port = if ($env:AGENTDEBUG_PORT) { [int]$env:AGENTDEBUG_PORT } else { 8765 }
    $name = if ($port -eq 8765) { 'bridge.token' } else { "bridge-$port.token" }
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        try {
            $token = [IO.File]::ReadAllText("$env:USERPROFILE\.eclipse-agentdebug\$name").Trim()
            $info = Invoke-Bridge 'GET_E2E_INFO'
            break
        } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw }
            Start-Sleep -Seconds 2
        }
    } while ($true)
    Write-Host "Loaded Eclipse debugger $($info.bundleVersion) from $($info.loadedFrom)"
    if ($info.loadedFrom -notmatch 'plugins[/\\]org\.learningfromexperience\.eclipse\.agentdebug_') {
        throw "Expected the installed debugger bundle. Check which Eclipse instance owns port $port."
    }
    if ($env:AGENTDEBUG_WORKSPACE -and [IO.Path]::GetFullPath($env:AGENTDEBUG_WORKSPACE).TrimEnd([char]'\') -ne $info.workspace.TrimEnd([char]'\')) {
        throw "Wrong workspace: expected '$env:AGENTDEBUG_WORKSPACE', actual '$($info.workspace)'"
    }
    if ($env:AGENTDEBUG_ECLIPSE_DIR -and [IO.Path]::GetFullPath($env:AGENTDEBUG_ECLIPSE_DIR).TrimEnd([char]'\') -ne ([uri]$info.installDirectory).LocalPath.TrimEnd([char]'\')) {
        throw "Wrong installation: expected '$env:AGENTDEBUG_ECLIPSE_DIR', actual '$($info.installDirectory)'"
    }

    $base = Ensure-Compiled-PluginClasses $repo
    foreach ($item in @{EclipseAgentOperations=$info.operationsSha256; EclipseBreakpointService=$info.breakpointSha256}.GetEnumerator()) {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $classFile = Join-Path $base "$($item.Key).class"
            $digest = [BitConverter]::ToString($sha.ComputeHash([IO.File]::ReadAllBytes($classFile))).Replace('-', '')
        } finally { $sha.Dispose() }
        if ($digest -ne $item.Value) {
            throw "Loaded $($item.Key) differs from the local build. Check source revision against installed plugin; do not run runtime tests until synchronized."
        }
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
