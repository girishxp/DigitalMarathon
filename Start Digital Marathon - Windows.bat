@echo off
setlocal DisableDelayedExpansion
if not exist "%~dp0app-files\scripts\launch-windows.ps1" (
  echo The Digital Marathon launcher is missing.
  echo Extract the entire ZIP, then open this file from the extracted folder.
  pause
  exit /b 1
)
if not exist "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" (
  echo Windows PowerShell could not be found on this computer.
  pause
  exit /b 1
)
start "" "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -NonInteractive -STA -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0app-files\scripts\launch-windows.ps1"
if errorlevel 1 (
  echo Digital Marathon could not open its launcher.
  echo Extract the entire ZIP, then try again from the extracted folder.
  pause
  exit /b 1
)
endlocal
exit /b 0
