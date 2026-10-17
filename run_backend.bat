@echo off
echo Starting SnipTutor FastAPI Backend...

set "PYTHON_EXE=python"
if exist ".venv\Scripts\python.exe" (
    set "PYTHON_EXE=.venv\Scripts\python.exe"
) else if exist "C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe" (
    set "PYTHON_EXE=C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe"
)

"%PYTHON_EXE%" -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
pause
