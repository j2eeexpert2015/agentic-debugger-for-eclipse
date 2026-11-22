@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Unified entry point. Resolve scripts relative to this file, not current directory.
set "SCRIPTS=%~dp0scripts\windows"
if not exist "%SCRIPTS%\dev.bat" (
  echo [ERROR] Missing "%SCRIPTS%\dev.bat"
  exit /b 1
)
if not exist "%SCRIPTS%\test-skill.bat" (
  echo [ERROR] Missing "%SCRIPTS%\test-skill.bat"
  exit /b 1
)

if /I "%~1"=="skill" goto skill
call "%SCRIPTS%\dev.bat" %*
exit /b %errorlevel%

:skill
if "%~2"=="" goto usage
if /I "%~2"=="selected" goto selected
if /I "%~2"=="smoke" goto smoke
if /I "%~2"=="selftest" goto selftest
if /I "%~2"=="preflight" goto preflight
if /I "%~2"=="full" goto full
if /I "%~2"=="compare" goto compare
if /I "%~2"=="analyze" goto analyze
goto usage

:selected
if "%~3"=="" goto usage
if not "%~4"=="" goto usage
rem Explicitly requested disposable Eclipse use; no fixture HTTP POST approval.
call "%SCRIPTS%\test-skill.bat" -Mode selected -Scenario "%~3" -AllowDisposableEclipse
exit /b %errorlevel%

:smoke
if not "%~3"=="" goto usage
call "%SCRIPTS%\test-skill.bat" -Mode smoke
exit /b %errorlevel%
:selftest
if not "%~3"=="" goto usage
call "%SCRIPTS%\test-skill.bat" -Mode selftest
exit /b %errorlevel%
:preflight
if not "%~3"=="" goto usage
call "%SCRIPTS%\test-skill.bat" -Mode preflight
exit /b %errorlevel%
:full
if not "%~3"=="" goto usage
call "%SCRIPTS%\test-skill.bat" -Mode full -AllowDisposableEclipse
exit /b %errorlevel%
:compare
if not "%~3"=="" goto usage
call "%SCRIPTS%\test-skill.bat" -Mode compare -AllowDisposableEclipse
exit /b %errorlevel%
:analyze
if not "%~3"=="" goto usage
call "%SCRIPTS%\test-skill.bat" -Mode analyze
exit /b %errorlevel%
:usage
echo Usage: RUN-WINDOWS-TESTS.cmd config^|doctor^|start^|build^|deploy^|test^|phase2
 echo        RUN-WINDOWS-TESTS.cmd skill selftest^|preflight^|smoke^|selected Java_ConditionalBreakpoint_RootCause^|full^|compare
 echo [SAFE] Invalid arguments. Nothing was changed.
exit /b 2
