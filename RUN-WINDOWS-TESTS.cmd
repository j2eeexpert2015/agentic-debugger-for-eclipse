@echo off
rem Run from ANY working directory; script locates its own repository.
call "%~dp0scripts\windows\dev.bat" %*
exit /b %errorlevel%
