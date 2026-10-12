@echo off
chcp 65001 >nul
title Luminary
cd /d "%~dp0"
start "" python\pythonw.exe launcher.py
exit