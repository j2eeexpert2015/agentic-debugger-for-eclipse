$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path
try {
    $ide = (Resolve-Path -LiteralPath $env:AGENTDEBUG_ECLIPSE_DIR).Path
    $workspace = [IO.Path]::GetFullPath($env:AGENTDEBUG_WORKSPACE)
    $director = Join-Path $ide 'eclipsec.exe'
    if (!(Test-Path -LiteralPath $director -PathType Leaf)) { throw "Missing Eclipse p2 director in $ide" }
    if (Get-Process eclipse -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq "$ide\eclipse.exe" }) {
        throw "Close the selected Eclipse installation before deployment: $ide"
    }
    $port = if ($env:AGENTDEBUG_PORT) { [int]$env:AGENTDEBUG_PORT } else { 8765 }
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
    try { $probe.Start() } finally { $probe.Stop() }
    $qualifier = [DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')
    Write-Host "Packaging for $ide and workspace $workspace (bridge $port)"
    Push-Location $repo
    try { & mvn.cmd "-DforceContextQualifier=$qualifier" clean package; if ($LASTEXITCODE) { throw 'Maven package failed' } }
    finally { Pop-Location }
    $site = Join-Path $repo 'eclipse-update-site\target\repository'
    $feature = @(Get-ChildItem "$site\features" -Filter 'org.learningfromexperience.eclipse.agentdebug.feature_*.jar')
    if ($feature.Count -ne 1) { throw 'Expected one packaged debugger feature' }
    $version = $feature[0].BaseName.Split('_')[-1]
    $iu = 'org.learningfromexperience.eclipse.agentdebug.feature.feature.group'
    $argsP2 = @('-nosplash','-application','org.eclipse.equinox.p2.director','-destination',$ide)
    $roots = & $director @argsP2 -listInstalledRoots
    if ($LASTEXITCODE) { throw 'Could not query installed features' }
    $change = @()
    if ($roots -match ('^' + [regex]::Escape($iu) + '/')) { $change += @('-uninstallIU',$iu) }
    $change += @('-repository',([uri]$site).AbsoluteUri,'-installIU',"$iu/$version")
    & $director @argsP2 @change
    if ($LASTEXITCODE) { throw 'Eclipse p2 deployment failed' }
    $p1 = Join-Path $repo 'examples\phase-one-debugging'
    $p2 = Join-Path $repo 'examples\spring-boot-debugging'
    $env:AGENTDEBUG_FIXTURE_SOURCE = "$p1;$p2"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "$ide\eclipse.exe"
    $psi.Arguments = "-clean -data `"$workspace`""
    $psi.UseShellExecute = $true
    [void][System.Diagnostics.Process]::Start($psi)
    Write-Host "Started Eclipse with debugger $version. Waiting for fixture import and bridge readiness."
} catch { Write-Host "FAIL: $($_.Exception.Message)"; exit 1 }
