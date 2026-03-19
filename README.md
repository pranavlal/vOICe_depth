# vOICe Depth

**vOICe Depth** is a  companion  program to [The vOICe](https://www.seeingwithsound.com/) created by Dr., Peter BL. Meijer  which is a program that converts images to  sound allowing blind people to percieve images and live scenes. Traditionally, [The vOICe](https://www.seeingwithsound.com/) has had monocular vision though stereo vision support has been present in the softwarefrom  a long  time. Much has changed today since depth cameras are more readily availaable and  today, it is possible to even use ordinary webcams like depthcameras via ai models that convert frames to stereo images.  In other words, vOICe Depth is designed to bridge depth-perception hardware (like Intel RealSense/OAK-D cameras) or standard Webcams (via MiDaS neural networks) with [The vOICe](https://www.seeingwithsound.com/). It computes real-time depth maps and feeds them either to an on-screen window or directly to a Virtual Camera intended to be read by The vOICe. 

This project aims to be completely screen-reader friendly and uses native TTS to announce hotkeys and camera changes.
###Note:
The virtual camera  support is indeed present but does not yet work with the vOICe at least natively.

## Requirements
* Python 3.9+ 
* A webcam **OR** an OAK-D Lite camera
* Windows or Linux
* For Virtual Camera: **OBS Studio** (Click "Start Virtual Camera") or UnityCapture
###Note:
I have created the program to also support Linux though the vOICe does  not natively support Linux unless you use the web version which runs in browsers that support web RTC.

## Download

You can download the latest standalone version of vOICe Depth directly from the [GitHub Releases](https://github.com/pranavlal/vOICe_depth/releases) page:
1. Navigate to the **Releases** section on the right-hand side of the GitHub repository (or use the link above).
2. Download the `vOICe_depth_v1.2.zip` (or highest version available) from the Assets under the latest release.
3. Extract the ZIP file to a folder on your computer.

## Installation and execution
Inside the extracted folder, there are automated setup and run scripts. 

### Windows
**First Time Setup:**
1. Open the extracted folder in File Explorer.
2. **Right-click** on the empty space, and select "Open in Terminal". Alternatively, open PowerShell from the menu that  comes up when you  press  the windows key +   x and `cd` into this folder.
3. Run `.\install.ps1`. This creates an isolated Python virtual environment (`venv`) and installs PyTorch, OpenCV, and pyvirtualcam.
   - *Note: If you receive an execution policy error, run `Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser` first.*

**Every Time You Run:**
1. Double-click `run.bat` (or run `.\run.bat` via terminal). The script will automatically activate the Python environment and launch the graphical interface. 
2. Alternatively, you can activate the environment manually and run the scripts:
   ```powershell
   .\venv\Scripts\Activate.ps1
   python vd.py
   # OR
   python voice_depth_virtualcam.py
   ```

### Linux
**First Time Setup:**
1. Open a terminal and `cd` into the extracted folder or  ssh into your linux machine and get into the folder.
2. Make the install script executable: `chmod +x install.sh`
3. Run the installer: `./install.sh`. This automatically installs system requirements (like `python3-venv` and `v4l2loopback-dkms` for virtual cameras), sets up a Python virtual environment, and installs Python libraries.

**Every Time You Run:**
1. Run `./run.sh` to automatically activate the environment and start the application. 
2. Alternatively, you can run manually inside the folder:
   ```bash
   source venv/bin/activate
   python vd.py
   # OR
   python voice_depth_virtualcam.py
   ```

There are two main scripts:
1. `vd.py`: Runs the standard application and displays the depth window on-screen.
2. `voice_depth_virtualcam.py`: Same as above, but outputs to a Virtual Web Camera instead.

## Available Hotkeys
All hotkey presses provide audio feedback using your system's native text-to-speech capabilities.

* `c` : **Switch Camera Input**. Cycles through available camera devices if you have multiple webcams connected.
* `i` : **Toggle Color Inversion** (for The vOICe). Flips the depth grayscale map (white becomes black and vice-versa). This is useful because *The vOICe* translates bright pixels into loud sounds and dark pixels into quiet ones.
* `a` : **Toggle Auto-ranging**. Dynamically scales the depth map so that the nearest object in the frame is always the brightest and the farthest is the darkest. This maximizes the contrast for your current view. When turned OFF, the app uses fixed distance limits (which can be adjusted manually).
* `g` : **Cycle Gamma correction** (e.g. 0.50, 0.60, 0.75, 1.0). Adjusts the mid-tone brightness. Lower gamma values make the overall image brighter, which can help bring out details in darker/farther areas.
* `v` : **Toggle Flip Image Vertically** (Up/Down). Useful if your camera is mounted upside down. (Default: OFF)
* `h` : **Toggle Flip Image Horizontally** (Left/Right). Mirrors the camera image. (Default: OFF)
* `[ / ]` : **Decrease / Increase Near Depth Limit**. Only works when Auto-ranging is OFF. Adjusts the minimum distance threshold.
* `- / =` : **Decrease / Increase Far Depth Limit**. Only works when Auto-ranging is OFF. Adjusts the maximum distance threshold.
* `r` : **Reset to Default Parameters**. Re-enables Auto-ranging and Inversion, resets Gamma to 0.60, and restores normal image orientation (no flipping).
* `s` : **Save Frame**. Saves the current depth frame to your disk as a PNG image file.
* `q / ESC` : **Quit** the application.

## Virtual Camera Setup (Windows)
If using `voice_depth_virtualcam.py`, your depth map is sent to a virtual webcam driver. The easiest and most compatible way to link this to The vOICe is to install [OBS Studio](https://obsproject.com/). 
1. Open OBS Studio.
2. Click **Start Virtual Camera** in the bottom right corner.
3. Keep OBS open, and run `voice_depth_virtualcam.py`. It will detect the OBS camera and pipe the depth map into it.
4. Open The vOICe and set its camera source to **OBS Virtual Camera**.
#A note on development
In the interest of transparancy, I have vibe coded this program. I have  however run the program on my ownmachines  because I am a user of [The vOICe](https://www.seeingwithsound.com/). The program  is  also fully open  source and you are free to inspect the source code and to buildupon it.
I welcome constructive engagement and  will do my best to fix bugs.

## Android Application
A native Android application is available in the `android_app` directory. This app functions as a depth map media server, capturing real-time depth and streaming it as an MJPEG server for The vOICe.

### New in Release 1.2
- **Optimized Normalization**: Implemented `NormalizeOp(115.0f, 58.0f)` for the MiDaS model, significantly improving depth map contrast and accuracy.
- **Privacy by Default**: The MJPEG server now defaults to **localhost-only (`127.0.0.1`)** binding. This means the depth stream is only accessible to "The vOICe for Android" running on the same device.
- **Remote Streaming Toggle**: Added an "Enable Remote Streaming" switch for debugging and testing. When enabled, the app broadcasts on your WiFi network and displays your device's local IP address.
- **Enhanced Accuracy**: Uses the MiDaS (Small) TFLite model with GPU acceleration support for smooth, real-time depth estimation.

### Integration with The vOICe for Android
The MJPEG stream from this app is designed to be used as a video source for **The vOICe for Android**. 
1. Download and install **The vOICe for Android** from [seeingwithsound.com/android.htm](https://www.seeingwithsound.com/android.htm).
2. Start the vOICe Depth app and tap **Start Server**.
3. By default, the URL will be `http://127.0.0.1:8080/depth_stream.mjpeg`.
4. If you need to test from another device, enable **Remote Streaming** to see your network IP.
5. In **The vOICe for Android**, configure the camera source to use the provided network stream URL.