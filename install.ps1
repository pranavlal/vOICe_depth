<#
.SYNOPSIS
Installs dependencies for vOICe Depth (Mono Depth version)

.DESCRIPTION
This script sets up a Python virtual environment and installs the required
packages (PyTorch, OpenCV, pyvirtualcam) to run the MiDaS-based depth webcams.
#>

$ErrorActionPreference = "Stop"

Write-Host "Setting up vOICe Depth environment..." -ForegroundColor Cyan

# Check if Python is installed
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Host "Python is not installed or not in PATH. Please install Python 3.9+." -ForegroundColor Red
    exit 1
}

# Create virtual environment if it doesn't exist
if (-not (Test-Path "venv")) {
    Write-Host "Creating virtual environment 'venv'..." -ForegroundColor Cyan
    python -m venv venv
}

# Activate virtual environment
$activateScript = ".\venv\Scripts\Activate.ps1"
if (-not (Test-Path $activateScript)) {
    Write-Host "Could not find activation script at $activateScript" -ForegroundColor Red
    exit 1
}

Write-Host "Activating virtual environment..." -ForegroundColor Cyan
. $activateScript

Write-Host "Upgrading pip..." -ForegroundColor Cyan
python -m pip install --upgrade pip

Write-Host "Installing dependencies from requirements.txt..." -ForegroundColor Cyan
# We specify the index-url for PyTorch to ensure we get a compatible version (CUDA 12.4 is a common default, or CPU if no GPU)
# For a generic installation that works on most Windows machines (CPU or CUDA):
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
pip install -r requirements.txt

Write-Host "Installation Complete!" -ForegroundColor Green
Write-Host "To run the programs, make sure to activate the environment first:" -ForegroundColor Yellow
Write-Host ".\venv\Scripts\Activate.ps1"
Write-Host "python vd.py"
