@echo off
setlocal EnableExtensions
call "%~dp0dev-settings.cmd" || exit /b 1
pushd "%~dp0..\.." || exit /b 1
set "mode=%~1"
if "%mode%"=="" set "mode=all"
if /i not "%mode%"=="all" if /i not "%mode%"=="validation" if /i not "%mode%"=="ownership" goto usage
if not "%~2"=="" goto usage
if not defined AGENTDEBUG_WORKSPACE goto no_workspace
if not exist "%AGENTDEBUG_WORKSPACE%\" goto no_workspace
if not defined AGENTDEBUG_PORT goto no_port
if "%AGENTDEBUG_PORT%"=="8765" goto no_port
if /i not "%AGENTDEBUG_APPROVE_FIXTURE_POST%"=="YES" goto no_consent
set "classes=e2e-runner\target\phase2-classes"
if not exist "%classes%" mkdir "%classes%"
if errorlevel 1 goto failed
echo [Phase 2] Compile pure Java verifier and HTTP client...
javac --release 21 -encoding UTF-8 -d "%classes%" ^
 "e2e-runner\src\main\java\org\learningfromexperience\agentdebug\e2e\PhaseOneBridgeVerifier.java" ^
 "e2e-runner\src\main\java\org\learningfromexperience\agentdebug\e2e\PhaseTwoBridgeVerifier.java" ^
 "e2e-runner\src\main\java\org\learningfromexperience\agentdebug\e2e\JavaFixtureQuoteClient.java"
if errorlevel 1 goto failed
echo [Phase 2] Run %mode% with Java HTTP client...
java -cp "%classes%" org.learningfromexperience.agentdebug.e2e.PhaseTwoBridgeVerifier ^
 --root "." --workspace "%AGENTDEBUG_WORKSPACE%" --port "%AGENTDEBUG_PORT%" ^
 --scenario "%mode%" --approve-fixture-post --http-client java
if errorlevel 1 goto failed
echo [Phase 2] PASS. Inspect evaluation\reports\phase2-live.json
set "rc=0"
goto done
:no_workspace
echo [ERROR] Set AGENTDEBUG_WORKSPACE to the existing disposable Eclipse workspace.
goto failed
:no_port
echo [ERROR] Set AGENTDEBUG_PORT to a non-default disposable bridge port.
goto failed
:no_consent
echo [ERROR] Set AGENTDEBUG_APPROVE_FIXTURE_POST=YES to authorize the fixture-only HTTP POST.
goto failed
:usage
echo Usage: verify-phase2-eclipse.bat [all^|validation^|ownership]
goto failed
:failed
set "rc=1"
:done
popd
exit /b %rc%
