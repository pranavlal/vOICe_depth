diff --git a/README.md b/README.md
index 9fa2a26..9a2d483 100644
--- a/README.md
+++ b/README.md
@@ -68,11 +68,11 @@ All hotkey presses provide audio feedback using your system's native text-to-spe
 * `i` : **Toggle Color Inversion** (for The vOICe). Flips the depth grayscale map (white becomes black and vice-versa). This is useful because *The vOICe* translates bright pixels into loud sounds and dark pixels into quiet ones.
 * `a` : **Toggle Auto-ranging**. Dynamically scales the depth map so that the nearest object in the frame is always the brightest and the farthest is the darkest. This maximizes the contrast for your current view. When turned OFF, the app uses fixed distance limits (which can be adjusted manually).
 * `g` : **Cycle Gamma correction** (e.g. 0.50, 0.60, 0.75, 1.0). Adjusts the mid-tone brightness. Lower gamma values make the overall image brighter, which can help bring out details in darker/farther areas.
-* `v` : **Flip Image Vertically** (Up/Down). Useful if your camera is mounted upside down.
-* `h` : **Flip Image Horizontally** (Left/Right). Mirrors the camera image.
+* `v` : **Toggle Flip Image Vertically** (Up/Down). Useful if your camera is mounted upside down. (Default: OFF)
+* `h` : **Toggle Flip Image Horizontally** (Left/Right). Mirrors the camera image. (Default: OFF)
 * `[ / ]` : **Decrease / Increase Near Depth Limit**. Only works when Auto-ranging is OFF. Adjusts the minimum distance threshold.
 * `- / =` : **Decrease / Increase Far Depth Limit**. Only works when Auto-ranging is OFF. Adjusts the maximum distance threshold.
-* `r` : **Reset to Default Parameters**. Re-enables Auto-ranging and Inversion, resets Gamma to 0.60, and restores normal image orientation.
+* `r` : **Reset to Default Parameters**. Re-enables Auto-ranging and Inversion, resets Gamma to 0.60, and restores normal image orientation (no flipping).
 * `s` : **Save Frame**. Saves the current depth frame to your disk as a PNG image file.
 * `q / ESC` : **Quit** the application.
 
