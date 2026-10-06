@echo off
cd /d "%~dp0"
"C:\Users\weilai\AppData\Local\Programs\Python\Python313\python.exe" -m venv venv
"venv\Scripts\pip.exe" install pywin32 psutil
echo Done. Now run run.bat
pause
