@echo off
rem Developer start: runs the launcher with a console and mirrors the log there (--console).
chcp 65001 >nul
cd /d "%~dp0"
"python\python.exe" launcher.py --console --debug %*
pause
