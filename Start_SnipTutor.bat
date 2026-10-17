@echo off
title SnipTutor Desktop Launcher
echo ===================================================
echo   Launching SnipTutor Windows Desktop Companion...
echo ===================================================

set "PYTHON_EXE=python"
if exist ".venv\Scripts\python.exe" (
    set "PYTHON_EXE=.venv\Scripts\python.exe"
) else if exist "C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe" (
    set "PYTHON_EXE=C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe"
)

:: Start FastAPI Backend silently in background if not using a remote cloud URL
if not defined SNIPTUTOR_BACKEND_URL (
    echo [1/2] Starting local AI backend server in background...
    start "SnipTutorBackend" /MIN "%PYTHON_EXE%" -m uvicorn main:app --host 127.0.0.1 --port 8000
    timeout /t 2 /nobreak >nul
)

echo [2/2] Starting SnipTutor Desktop App...
call run_frontend.bat
