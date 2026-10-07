@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0app-files\scripts\publish-github.ps1" %*
set "RESULT=%ERRORLEVEL%"
if "%~1"=="" (pause) else if not "%RESULT%"=="0" pause
exit /b %RESULT%
