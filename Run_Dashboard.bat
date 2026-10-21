@echo off
title SnipTutor Analytics Dashboard
echo =========================================================
echo   Starting SnipTutor Usage & Token Analytics Dashboard
echo =========================================================

set "PYTHON_EXE=python"
if exist ".venv\Scripts\python.exe" (
    set "PYTHON_EXE=.venv\Scripts\python.exe"
) else if exist "C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe" (
    set "PYTHON_EXE=C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe"
)

"%PYTHON_EXE%" -m streamlit run dashboard.py --server.port 8501 --theme.base "dark"
pause
