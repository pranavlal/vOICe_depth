#!/usr/bin/env bash

# Linux launcher for vOICe Depth

if [ ! -f "venv/bin/activate" ]; then
    echo -e "\033[1;31m[ERROR] Virtual environment not found. Please run ./install.sh first.\033[0m"
    exit 1
fi

echo -e "\033[1;36mActivating virtual environment...\033[0m"
source venv/bin/activate

echo ""
echo "Select the program to run:"
echo "1) Capture Window (vd.py)"
echo "2) Virtual Camera (voice_depth_virtualcam.py)"
echo ""
read -p "Enter choice (1/2): " choice

if [ "$choice" == "1" ]; then
    echo -e "\033[1;32mRunning vd.py...\033[0m"
    python vd.py "$@"
elif [ "$choice" == "2" ]; then
    echo -e "\033[1;32mRunning voice_depth_virtualcam.py...\033[0m"
    python voice_depth_virtualcam.py "$@"
else
    echo -e "\033[1;31mInvalid choice.\033[0m"
fi
