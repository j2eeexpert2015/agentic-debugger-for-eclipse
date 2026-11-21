@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Agentic Debugger for Eclipse IDE - Phase 1 Java bridge acceptance tests.
rem Author: Ayan Dutta
rem Requires the new plugin installed in a RUNNING Eclipse instance, port 8765 free
rem from other Eclipse instances, and phase-one-debugging imported as a Maven project.
rem Usage: verify-phase1-eclipse.bat [all|conditional|exception|junit|variables|trace|pause-restart|filters]
set "PROJECT_DIR=%~dp0..\.."
for %%I in ("%PROJECT_DIR%") do set "PROJECT_DIR=%%~fI"
set "SCENARIO=%~1"
if "%SCENARIO%"=="" set "SCENARIO=all"
if /I "%SCENARIO%"=="all" goto :valid
if /I "%SCENARIO%"=="conditional" goto :valid
if /I "%SCENARIO%"=="exception" goto :valid
if /I "%SCENARIO%"=="junit" goto :valid
if /I "%SCENARIO%"=="variables" goto :valid
if /I "%SCENARIO%"=="trace" goto :valid
if /I "%SCENARIO%"=="pause-restart" goto :valid
if /I "%SCENARIO%"=="filters" goto :valid
echo [ERROR] Unknown scenario: %SCENARIO%
echo Usage: %~nx0 [all^|conditional^|exception^|junit^|variables^|trace^|pause-restart^|filters]
exit /b 2
:valid
if not exist "%PROJECT_DIR%\pom.xml" (
  echo [ERROR] Maven project not found: "%PROJECT_DIR%"
  exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Maven is not available on PATH.
  exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Java is not available on PATH.
  exit /b 1
)
echo.
echo =============================================
echo Agentic Debugger - Phase 1 Eclipse Tests
echo =============================================
echo Repository: %PROJECT_DIR%
echo Scenario  : %SCENARIO%
echo.
echo [PREREQUISITES] Keep installed Eclipse open and its bridge listening on 127.0.0.1:8765.
echo [PREREQUISITES] Import examples\phase-one-debugging using Maven ^> Existing Maven Projects.
echo [IMPORTANT] This runs Java bridge tests; it does not invoke Claude Code.
echo.
pushd "%PROJECT_DIR%"
if errorlevel 1 exit /b 1
call mvn verify -Pphase1-bridge "-Dagentdebug.phase1.scenario=%SCENARIO%"
set "TEST_RESULT=%ERRORLEVEL%"
popd
if not "%TEST_RESULT%"=="0" (
  echo.
  echo [FAILED] Phase 1 bridge verification failed. See the Maven error and report if generated:
  echo          "%PROJECT_DIR%\evaluation\reports\phase1-live.json"
  exit /b %TEST_RESULT%
)
echo.
echo [SUCCESS] Phase 1 bridge verification completed.
echo [REPORT] "%PROJECT_DIR%\evaluation\reports\phase1-live.json"
echo [NEXT] For Claude prompt tests, follow docs\phase1-claude-scenarios.md.
exit /b 0
