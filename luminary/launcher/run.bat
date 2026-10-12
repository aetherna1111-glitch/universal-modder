@echo off
rem Fallback starter. Prefer Luminary.exe (see build_starter.bat): it opens no console window at all.
rem This file briefly shows a cmd window, then starts the launcher with pythonw (no console).
cd /d "%~dp0"
if exist "Luminary.exe" (
  start "" "Luminary.exe" %*
) else (
  start "" "python\pythonw.exe" launcher.py %*
)
exit /b
