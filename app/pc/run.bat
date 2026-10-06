@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "venv\Scripts\python.exe" (
  echo [closedloop] 还没装依赖。先双击一次 setup.bat。
  pause
  exit /b 1
)
title 闭环 · PC 版
"venv\Scripts\python.exe" main.py
