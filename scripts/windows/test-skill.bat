@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem All companion scripts are resolved relative to this BAT, never the current directory.
set "SCRIPT_DIR=%~dp0"
set "SETTINGS=%SCRIPT_DIR%dev-settings.cmd"
set "ENSURE=%SCRIPT_DIR%dev-ensure-eclipse.ps1"
set "RUNNER=%SCRIPT_DIR%test-skill.ps1"

if not exist "%SETTINGS%" (
  echo [SKILL RUNNER] ERROR: Missing configuration "%SETTINGS%"
  exit /b 1
)
if not exist "%RUNNER%" (
  echo [SKILL RUNNER] ERROR: Missing PowerShell runner "%RUNNER%"
  exit /b 1
)
call "%SETTINGS%"
if errorlevel 1 (
  echo [SKILL RUNNER] ERROR: Configuration failed.
  exit /b 1
)
if not defined AGENTDEBUG_REPO_DIR (
  echo [SKILL RUNNER] ERROR: AGENTDEBUG_REPO_DIR was not defined by settings.
  exit /b 1
)
if not exist "%AGENTDEBUG_REPO_DIR%\pom.xml" (
  echo [SKILL RUNNER] ERROR: Invalid repository "%AGENTDEBUG_REPO_DIR%"
  exit /b 1
)

rem Detect runtime invocation while retaining the caller's original %%* arguments.
set "NEEDS_ECLIPSE=NO"
set "AUTHORIZED=NO"
:scan
if "%~1"=="" goto scanned
if /I "%~1"=="-AllowDisposableEclipse" set "AUTHORIZED=YES"
if /I "%~1"=="-Mode" (
  if /I "%~2"=="selected" set "NEEDS_ECLIPSE=YES"
  if /I "%~2"=="full" set "NEEDS_ECLIPSE=YES"
  if /I "%~2"=="compare" set "NEEDS_ECLIPSE=YES"
)
shift
goto scan
:scanned
if /I "%NEEDS_ECLIPSE%"=="YES" if /I "%AUTHORIZED%"=="YES" (
  if not exist "%ENSURE%" (
    echo [SKILL RUNNER] ERROR: Missing Eclipse readiness script "%ENSURE%"
    exit /b 1
  )
  echo [SKILL RUNNER] Ensuring dedicated Eclipse workspace and bridge are ready...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ENSURE%" -WaitSeconds 180
  if errorlevel 1 (
    echo [SKILL RUNNER] BLOCKED: Eclipse readiness check failed; Claude tests not launched.
    exit /b 1
  )
)

pushd "%AGENTDEBUG_REPO_DIR%"
if errorlevel 1 exit /b 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%RUNNER%" %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
