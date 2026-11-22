# Posts the single approved fixture request. Run alongside the debugger so it can resume the HTTP thread.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Workspace,
    [Parameter(Mandatory)][ValidateRange(1024,65535)][int]$BridgePort,
    [Parameter(Mandatory)][string]$Receipt,
    [Parameter(Mandatory)][string]$LaunchName,
    [switch]$ApproveFixturePost,
    [switch]$InvalidQuantity,
    [string]$RequestedUrl
)
$ErrorActionPreference = 'Stop'
if (!$ApproveFixturePost) { throw 'Fixture POST requires explicit approval.' }
if ($BridgePort -eq 8765) { throw 'Use the disposable Eclipse bridge.' }
$expectedWorkspace = (Resolve-Path -LiteralPath $Workspace).Path
$receiptPath = [IO.Path]::GetFullPath($Receipt)
if (Test-Path -LiteralPath $receiptPath) { throw 'Choose a fresh receipt path for each run.' }
$launch = $LaunchName
$body = '{"quantity":2,"unitPrice":60,"discountPercent":10}'
if ($InvalidQuantity) { $body = '{"quantity":0,"unitPrice":60,"discountPercent":10}' }
$token = [IO.File]::ReadAllText("$env:USERPROFILE/.eclipse-agentdebug/bridge-$BridgePort.token").Trim()

function Read-Bridge([string]$Command) {
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $BridgePort)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 5000
        $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
        $writer.WriteLine("AUTH $token $Command")
        $writer.Flush()
        $reader = [IO.StreamReader]::new($stream)
        return ($reader.ReadLine() | ConvertFrom-Json)
    } finally {
        $client.Dispose()
    }
}

$info = Read-Bridge 'GET_E2E_INFO'
if ($info.status -ne 'success' -or [IO.Path]::GetFullPath($info.workspace) -ne $expectedWorkspace) {
    throw 'Bridge workspace differs from the approved fixture workspace.'
}
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($launch)).TrimEnd('=').Replace('+','-').Replace('/','_')
$deadline = [DateTime]::UtcNow.AddSeconds(120)
do {
    $output = Read-Bridge "GET_PROCESS_OUTPUT $encoded"
    if ($output.status -eq 'success') {
        if (@($output.processes).Count -ne 1) { throw 'Expected one process for the fixture launch.' }
        $process = $output.processes[0]
        $lines = $process.stdout + "`n" + $process.console
        $startup = [regex]::Match($lines, 'Starting OrdersApplication[^\r\n]*?with PID (\d+)')
        $ready = [regex]::Match($lines, 'Tomcat started on port (\d+)')
        if ($startup.Success -and $ready.Success) { break }
    } elseif ($output.message -notmatch 'Active debug launch not found') {
        throw "Unable to identify the fixture launch: $($output.message)"
    }
    Start-Sleep -Milliseconds 250
} while ([DateTime]::UtcNow -lt $deadline)
if (!$startup.Success -or !$ready.Success) { throw 'Timed out waiting for the selected OrdersApplication to listen.' }
$serverPort = [int]$ready.Groups[1].Value
$serverPid = [int]$startup.Groups[1].Value
$url = "http://127.0.0.1:$serverPort/orders/quote"
if ($PSBoundParameters.ContainsKey('RequestedUrl') -and $RequestedUrl -cne $url) {
    throw 'Requested URL differs from the approved fixture endpoint.'
}
$listeners = @(Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction Stop)
if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -notin '127.0.0.1','::ffff:127.0.0.1' -or $listeners[0].OwningProcess -ne $serverPid) {
    throw 'Listener identity differs from the selected loopback fixture process.'
}
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$http = [Net.Http.HttpClient]::new($handler)
try {
    $http.Timeout = [TimeSpan]::FromSeconds(180)
    $payload = [Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
    $response = $http.PostAsync($url,$payload).GetAwaiter().GetResult()
    $record = [ordered]@{launch=$launch;processId=$serverPid;method='POST';url=$url;request=$body
        status=[int]$response.StatusCode;body=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()}
    $temporary = "$receiptPath.$([guid]::NewGuid()).tmp"
    [IO.File]::WriteAllText($temporary,($record | ConvertTo-Json))
    [IO.File]::Move($temporary,$receiptPath)
} finally {
    $http.Dispose()
}
