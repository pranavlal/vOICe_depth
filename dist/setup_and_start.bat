@echo off
title vOICe Depth Installer and Launcher

echo ========================================================
echo   vOICe Depth - 1-Click Setup and Start
echo ========================================================
echo.
echo Step 1: Installing Dependencies (this may take a while the first time)...
powershell.exe -ExecutionPolicy Bypass -File "%~dp0install.ps1"
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Installation encountered an issue. See details above.
    pause
    exit /b %errorlevel%
)

echo.
echo Step 2: Starting the Application...
call "%~dp0run.bat"

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] The application exited with an error. 
    pause
)
