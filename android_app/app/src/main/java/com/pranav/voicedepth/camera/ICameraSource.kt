package com.pranav.voicedepth.camera

import android.graphics.Bitmap

/**
 * Common interface for camera sources (Internal and External/USB)
 */
interface ICameraSource {
    /**
     * Start the camera source
     */
    fun start()

    /**
     * Stop the camera source and release resources
     */
    fun stop()

    /**
     * Set callback for receiving processed frames as Bitmaps
     */
    fun setFrameCallback(callback: (Bitmap) -> Unit)

    /**
     * Set callback for reporting errors
     */
    fun setErrorCallback(callback: (String) -> Unit)

    /**
     * Return the sensor orientation in degrees
     */
    fun getSensorOrientation(): Int
}
