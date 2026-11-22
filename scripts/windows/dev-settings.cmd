@echo off
rem SINGLE SOURCE OF CONFIGURATION for this Windows development/test environment.
rem Edit defaults here once, or override them with environment variables.
rem The repository root is discovered from the script location, not current CMD directory.
for %%I in ("%~dp0..\..") do set "AGENTDEBUG_REPO_DIR=%%~fI"
if not defined AGENTDEBUG_ECLIPSE_DIR set "AGENTDEBUG_ECLIPSE_DIR=C:\All_Softwares\eclipse"
if not defined AGENTDEBUG_WORKSPACE set "AGENTDEBUG_WORKSPACE=%LOCALAPPDATA%\AgenticDebugger\dev-workspace"
if not defined AGENTDEBUG_PORT set "AGENTDEBUG_PORT=21765"
if not defined AGENTDEBUG_CLAUDE_EXE set "AGENTDEBUG_CLAUDE_EXE=%USERPROFILE%\.local\bin\claude.exe"
if not defined AGENTDEBUG_FIXTURE_SOURCE set "AGENTDEBUG_FIXTURE_SOURCE=%AGENTDEBUG_REPO_DIR%\examples\phase-one-debugging;%AGENTDEBUG_REPO_DIR%\examples\spring-boot-debugging"
echo.
echo [AGENTDEBUG CONFIG] Repository : %AGENTDEBUG_REPO_DIR%
echo [AGENTDEBUG CONFIG] Eclipse    : %AGENTDEBUG_ECLIPSE_DIR%
echo [AGENTDEBUG CONFIG] Workspace  : %AGENTDEBUG_WORKSPACE%
echo [AGENTDEBUG CONFIG] Bridge port: %AGENTDEBUG_PORT%
echo [AGENTDEBUG CONFIG] Claude CLI : %AGENTDEBUG_CLAUDE_EXE%
echo [AGENTDEBUG CONFIG] Settings   : scripts\windows\dev-settings.cmd
exit /b 0
