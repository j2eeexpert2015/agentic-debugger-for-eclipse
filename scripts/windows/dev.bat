@echo off
setlocal EnableExtensions
pushd "%~dp0..\.." || exit /b 1
if not "%~2"=="" goto usage
set "mode=%~1"
if "%mode%"=="" goto full
if /i "%mode%"=="doctor" goto doctor
if /i "%mode%"=="deploy" goto deploy
if /i "%mode%"=="test" goto test
for %%S in (conditional exception junit variables trace pause-restart filters) do if /i "%mode%"=="%%S" goto feature
:usage
echo Usage: dev.bat [doctor^|deploy^|test^|conditional^|exception^|junit^|variables^|trace^|pause-restart^|filters]
set "rc=2"
goto done
:doctor
powershell.exe -NoProfile -File "%~dp0dev-doctor.ps1"
goto result
:deploy
if not defined AGENTDEBUG_ECLIPSE_DIR set "AGENTDEBUG_ECLIPSE_DIR=C:\All_Softwares\eclipse"
if not defined AGENTDEBUG_WORKSPACE set "AGENTDEBUG_WORKSPACE=%LOCALAPPDATA%\AgenticDebugger\dev-workspace"
powershell.exe -NoProfile -File "%~dp0dev-deploy.ps1"
if errorlevel 1 goto result
powershell.exe -NoProfile -File "%~dp0dev-doctor.ps1" -WaitSeconds 300
goto result
:full
call "%~dp0dev.bat" deploy
if errorlevel 1 goto result
:test
powershell.exe -NoProfile -File "%~dp0dev-doctor.ps1"
if errorlevel 1 goto result
call mvn verify -Pinstalled-e2e
if errorlevel 1 goto result
set "mode=all"
goto verify
:feature
powershell.exe -NoProfile -File "%~dp0dev-doctor.ps1"
if errorlevel 1 goto result
:verify
call "%~dp0verify-phase1-eclipse.bat" "%mode%"
:result
set "rc=%errorlevel%"
:done
popd
exit /b %rc%
