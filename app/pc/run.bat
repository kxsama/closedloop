@echo off
cd /d "%~dp0"
if not exist "venv\Scripts\python.exe" (
  echo [closedloop] venv not found. Run setup.bat first.
  pause
  exit /b 1
)
title closedloop-pc
"venv\Scripts\python.exe" main.py
pause
