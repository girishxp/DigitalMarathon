@echo off
setlocal
set "ROOT=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\build_windows.ps1" -Mode clean
if errorlevel 1 (
  echo.
  echo Rebuild failed. See digital-marathon-startup.log in this folder.
  pause
)
endlocal
