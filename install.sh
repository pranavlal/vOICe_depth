#!/usr/bin/env bash

# Linux installation script for vOICe Depth

set -e

echo -e "\033[1;36mSetting up vOICe Depth environment for Linux...\033[0m"

# Check if Python is installed
if ! command -v python3 &> /dev/null; then
    echo -e "\033[1;31mPython 3 is not installed or not in PATH.\033[0m"
    exit 1
fi

if ! dpkg -s python3-venv >/dev/null 2>&1; then
    echo -e "\033[1;33mpython3-venv is missing. Attempting to install it (may require sudo)...\033[0m"
    sudo apt-get update && sudo apt-get install -y python3-venv
fi

# Need v4l2loopback-dkms for pyvirtualcam on Linux usually
if ! dpkg -s v4l2loopback-dkms >/dev/null 2>&1; then
    echo -e "\033[1;33mv4l2loopback-dkms is recommended for virtual camera support on Linux. Installing...\033[0m"
    sudo apt-get update && sudo apt-get install -y v4l2loopback-dkms || true
    sudo modprobe v4l2loopback devices=1 video_nr=20 card_label="vOICe Virtual Cam" exclusive_caps=1 || true
fi

if [ ! -d "venv" ]; then
    echo -e "\033[1;36mCreating virtual environment 'venv'...\033[0m"
    python3 -m venv venv
fi

echo -e "\033[1;36mActivating virtual environment...\033[0m"
source venv/bin/activate

echo -e "\033[1;36mUpgrading pip...\033[0m"
pip install --upgrade pip

echo -e "\033[1;36mInstalling dependencies from requirements.txt...\033[0m"
# Install PyTorch for Linux (CPU or CUDA). This is the default index which smartly handles wheels.
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
pip install -r requirements.txt

echo -e "\033[1;32mInstallation Complete!\033[0m"
echo -e "\033[1;33mTo run the programs, use ./run.sh or manual activation:\033[0m"
echo "source venv/bin/activate"
echo "python vd.py"
