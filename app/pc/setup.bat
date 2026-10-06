@echo off
chcp 65001 >nul
cd /d "%~dp0"
title closedloop-pc-setup

echo [closedloop] 检查 Python 环境...

set "PY="
py -3 --version >nul 2>&1
if not errorlevel 1 set "PY=py -3"
if defined PY goto :havepy
python --version >nul 2>&1
if not errorlevel 1 set "PY=python"
:havepy

if not defined PY goto :nopy

echo [closedloop] 使用 %PY% 创建虚拟环境 venv ...
%PY% -m venv venv
if errorlevel 1 goto :failvenv

echo [closedloop] 安装依赖（首次约 1-2 分钟）...
"venv\Scripts\python.exe" -m pip install --upgrade pip -q
"venv\Scripts\pip.exe" install -r requirements.txt
if errorlevel 1 goto :retrymirror
goto :done

:retrymirror
echo.
echo [closedloop] 直连安装失败，改用清华镜像重试...
"venv\Scripts\pip.exe" install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
if errorlevel 1 goto :failpip
goto :done

:done
echo.
echo [closedloop] 完成。现在双击 run.bat 启动。
pause
exit /b 0

:nopy
echo.
echo [closedloop] 没找到 Python。
echo 请先安装 Python 3.10 或更高版本，安装时务必勾选 "Add Python to PATH"。
echo 下载地址：https://www.python.org/downloads/
echo.
pause
exit /b 1

:failvenv
echo.
echo [closedloop] 创建虚拟环境失败。请确认 Python 安装完整（含 venv 模块）。
pause
exit /b 1

:failpip
echo.
echo [closedloop] 依赖安装失败。请检查网络后重试，或手动执行：
echo     venv\Scripts\pip.exe install -r requirements.txt
pause
exit /b 1
