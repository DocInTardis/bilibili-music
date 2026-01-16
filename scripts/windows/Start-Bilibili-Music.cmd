@echo off
setlocal

REM One-click launcher for Windows.
REM Tip: create a desktop shortcut to this file.

set SCRIPT_DIR=%~dp0
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%start-bilibili-music.ps1"
if errorlevel 1 (
  echo.
  echo Failed to start. See logs above.
  pause
)

