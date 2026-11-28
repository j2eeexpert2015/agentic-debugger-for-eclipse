# Closes ONLY the dedicated test Eclipse, gracefully (as if its window's X was clicked), and waits up to 60 s.
# The Eclipse folder comes from dev-e2e/e2e.properties (key "eclipse"), else C:\All_Softwares\eclipse.
# Never Stop-Process: a killed Eclipse does not save its workspace (removed breakpoints come back) and does not run
# the plugin's shutdown clean-up (28 Nov). Exit code 1 when it did not close (usually a dialog is waiting in it).
$eclipse = 'C:\All_Softwares\eclipse'
$props = Join-Path $PSScriptRoot 'e2e.properties'
if (Test-Path $props) {
    $line = Get-Content $props | Where-Object { $_ -match '^\s*eclipse\s*=' } | Select-Object -First 1
    if ($line) { $eclipse = ($line -split '=', 2)[1].Trim() }
}
$exe = [IO.Path]::GetFullPath((Join-Path $eclipse 'eclipse.exe'))
$p = @(Get-Process eclipse -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and ([IO.Path]::GetFullPath($_.Path) -ieq $exe) })
if ($p.Count -eq 0) { Write-Host "Test Eclipse is not running ($exe)."; exit 0 }
$p | ForEach-Object { [void]$_.CloseMainWindow() }
$p | Wait-Process -Timeout 60 -ErrorAction SilentlyContinue
if ($p | Where-Object { Get-Process -Id $_.Id -ErrorAction SilentlyContinue }) {
    Write-Host 'Test Eclipse did not close: a dialog is waiting in it. Answer it, then run again.'
    exit 1
}
Write-Host 'Test Eclipse closed.'
exit 0
