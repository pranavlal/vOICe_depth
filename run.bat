@echo off
setlocal

:: Windows launcher for vOICe Depth

if not exist "venv\Scripts\Activate.ps1" (
    echo [ERROR] Virtual environment not found. Please run install.ps1 first.
    pause
    exit /b 1
)

echo Activating virtual environment...
call "venv\Scripts\activate.bat"

echo.
echo Select the program to run:
echo 1) Capture Window (vd.py)
echo 2) Virtual Camera (voice_depth_virtualcam.py)
echo.
set /p choice="Enter choice (1/2): "

if "%choice%"=="1" (
    echo Running vd.py...
    python vd.py %*
) else if "%choice%"=="2" (
    echo Running voice_depth_virtualcam.py...
    python voice_depth_virtualcam.py %*
) else (
    echo Invalid choice.
)

pause
