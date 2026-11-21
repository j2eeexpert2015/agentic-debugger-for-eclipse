param(
    [Parameter(Mandatory)][string]$EclipseDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$classes = (New-Item -ItemType Directory -Force (Join-Path $OutputDirectory 'classes')).FullName
$plugins = Join-Path $EclipseDirectory 'plugins'
$jdt = Get-Item "$plugins/org.eclipse.jdt.debug_*/jdimodel.jar"
$jars = Get-ChildItem $plugins -Filter '*.jar' | Where-Object {
    $_.Name -match '^org\.eclipse\.(core\.|equinox\.(common|registry|preferences)_|jdt\.(core|launching)_|debug\.core_|ui[._]|osgi_|jface_)'
}
$classpath = (@($jdt.FullName) + @($jars.FullName)) -join ';'
$verifier = Join-Path $PSScriptRoot '../src/main/java/org/learningfromexperience/agentdebug/e2e/PhaseOneBridgeVerifier.java'
& javac -proc:none --release 21 -cp $classpath -d $classes $verifier "$PSScriptRoot/OwnershipChecks.java"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& jar cfm "$OutputDirectory/ownership-tests.jar" "$PSScriptRoot/MANIFEST.MF" -C $classes org/learningfromexperience/agentdebug/e2e -C $PSScriptRoot plugin.xml
exit $LASTEXITCODE
