@echo off
rem Closes only the dedicated test Eclipse, gracefully (see close-test-eclipse.ps1). Use: call dev-e2e\close-test-eclipse.cmd
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0close-test-eclipse.ps1"
exit /b %ERRORLEVEL%
