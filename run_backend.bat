@echo off
echo Starting SnipTutor FastAPI Backend...
"C:\Users\acer\AppData\Local\Python\pythoncore-3.14-64\python.exe" -m uvicorn main:app --reload --host 127.0.0.1 --port 8000
pause
