@echo off
setlocal EnableExtensions
pushd "%~dp0..\.." || exit /b 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0test-skill.ps1" %*
set "rc=%errorlevel%"
popd
exit /b %rc%
