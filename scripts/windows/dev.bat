@echo off
setlocal EnableExtensions DisableDelayedExpansion
call "%~dp0dev-settings.cmd" || exit /b 1
pushd "%AGENTDEBUG_REPO_DIR%" || exit /b 1
set "mode=%~1"
if "%mode%"=="" goto usage
if /i "%mode%"=="config" goto ok
if /i "%mode%"=="doctor" goto ensure
if /i "%mode%"=="start" goto ensure
if /i "%mode%"=="deploy" goto deploy
if /i "%mode%"=="build" goto build
if /i "%mode%"=="test" goto test
if /i "%mode%"=="phase2" goto phase2
for %%S in (conditional exception junit variables trace pause-restart filters) do if /i "%mode%"=="%%S" goto feature
:usage
echo Usage: dev.bat config^|doctor^|start^|build^|deploy^|test^|phase2^|conditional^|exception^|junit^|variables^|trace^|pause-restart^|filters
echo [SAFE] No command specified. Nothing was changed.
set "rc=2"
goto done
:ok
set "rc=0"
goto done
:ensure
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-ensure-eclipse.ps1" -WaitSeconds 180
goto result
:deploy
rem Deploy requires stopping the configured Eclipse installation first; never terminate it automatically.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-deploy.ps1"
if errorlevel 1 goto result
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-ensure-eclipse.ps1" -WaitSeconds 300
goto result
:build
call mvn clean package -DskipTests
goto result
:test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-ensure-eclipse.ps1" -WaitSeconds 180
if errorlevel 1 goto result
call mvn verify -Pinstalled-e2e
if errorlevel 1 goto result
set "mode=all"
goto verify
:feature
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-ensure-eclipse.ps1" -WaitSeconds 180
if errorlevel 1 goto result
:verify
call "%~dp0verify-phase1-eclipse.bat" "%mode%"
goto result
:phase2
rem Phase 2 explicit HTTP approval is NEVER granted automatically.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0dev-ensure-eclipse.ps1" -WaitSeconds 180
if errorlevel 1 goto result
call "%~dp0verify-phase2-eclipse.bat" "%~2"
goto result
:result
set "rc=%errorlevel%"
:done
popd
exit /b %rc%
