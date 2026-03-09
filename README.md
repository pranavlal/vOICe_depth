# vOICe Depth

**vOICe Depth** is an accessibility tool designed to bridge depth-perception hardware (like Intel RealSense/OAK-D cameras) or standard Webcams (via MiDaS neural networks) with [The vOICe](https://www.seeingwithsound.com/). It computes real-time depth maps and feeds them either to an on-screen window or directly to a Virtual Camera intended to be read by The vOICe. 

This project aims to be completely screen-reader friendly and uses native TTS to announce hotkeys and camera changes.

## Requirements
* Python 3.9+ 
* A webcam **OR** an OAK-D Lite camera
* Windows or Linux
* For Virtual Camera: **OBS Studio** (Click "Start Virtual Camera") or UnityCapture

## Installation

### Windows
1. Open PowerShell in this folder.
2. Run `.\install.ps1` -- this will create a local Python virtual environment (`venv`) and download PyTorch, OpenCV, and other requirements.
3. If it asks about execution policies, you might need to run `Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser` first.

### Linux
1. Open terminal in this folder.
2. Run `chmod +x install.sh` and then `./install.sh`. 

## How to Run
After installation, double click `run.bat` on Windows or execute `./run.sh` on Linux. The script will automatically activate the Python environment and launch the graphical interface.

There are two main scripts:
1. `vd.py`: Runs the standard application and displays the depth window on-screen.
2. `voice_depth_virtualcam.py`: Same as above, but outputs to a Virtual Web Camera instead.

## Available Hotkeys (Audio Feedback Included)
* `c` : Switch Camera Input
* `i` : Toggle Color Inversion (for The vOICe)
* `a` : Toggle Auto-ranging (dynamically scales depth)
* `g` : Cycle Gamma correction (e.g. 0.60, 0.75, 1.0)
* `v` : Flip Image Vertically (Up/Down)
* `h` : Flip Image Horizontally (Left/Right)
* `[ / ]` : Decrease / Increase Near Depth Limit (when auto-range is OFF)
* `- / =` : Decrease / Increase Far Depth Limit (when auto-range is OFF)
* `r` : Reset to Default Parameters
* `s` : Save the current depth frame to disk
* `q / ESC` : Quit the application

## Virtual Camera Setup (Windows)
If using `voice_depth_virtualcam.py`, your depth map is sent to a virtual webcam driver. The easiest and most compatible way to link this to The vOICe is to install [OBS Studio](https://obsproject.com/). 
1. Open OBS Studio.
2. Click **Start Virtual Camera** in the bottom right corner.
3. Keep OBS open, and run `voice_depth_virtualcam.py`. It will detect the OBS camera and pipe the depth map into it.
4. Open The vOICe and set its camera source to **OBS Virtual Camera**.
