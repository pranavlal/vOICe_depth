package com.pranav.voicedepth.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import java.nio.ByteBuffer

/**
 * Camera source implementation for external UVC cameras using UVCAndroid library
 */
class ExternalCameraSource(private val context: Context) : ICameraSource {

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    
    private var frameCallback: ((Bitmap) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    
    private val sensorOrientation = 0 // External cameras usually don't need rotation adjustment like internal ones

    private val uvcFrameCallback = IFrameCallback { frame ->
        // Convert UVC frame to Bitmap
        try {
            frame.rewind()
            val bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565)
            frame.rewind()
            bitmap.copyPixelsFromBuffer(frame)
            frameCallback?.invoke(bitmap)
        } catch (e: Exception) {
            android.util.Log.e("ExternalCameraSource", "Error processing UVC frame", e)
        }
    }

    private val deviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            // Device attached, request permission
            usbMonitor?.requestPermission(device)
        }

        override fun onDetach(device: UsbDevice?) {
            stop()
            errorCallback?.invoke("USB Camera detached")
        }

        override fun onDeviceOpen(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            uvcCamera = UVCCamera(UVCParam())
            try {
                uvcCamera?.open(ctrlBlock)
                uvcCamera?.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                uvcCamera?.setFrameCallback(uvcFrameCallback, UVCCamera.PIXEL_FORMAT_RGB565)
                uvcCamera?.startPreview()
            } catch (e: Exception) {
                errorCallback?.invoke("Failed to open UVC camera: ${e.message}")
                uvcCamera?.destroy()
                uvcCamera = null
            }
        }

        override fun onDeviceClose(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            stop()
        }

        override fun onCancel(device: UsbDevice?) {
            errorCallback?.invoke("USB permission denied")
        }
    }

    override fun start() {
        usbMonitor = USBMonitor(context, deviceConnectListener)
        usbMonitor?.register()
        
        // Check if a device is already attached
        val devices = usbMonitor?.deviceList
        if (!devices.isNullOrEmpty()) {
            usbMonitor?.requestPermission(devices[0])
        } else {
            errorCallback?.invoke("No USB camera detected. Please plug in a UVC camera via OTG.")
        }
    }

    override fun stop() {
        uvcCamera?.stopPreview()
        uvcCamera?.destroy()
        uvcCamera = null
        
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
    }

    override fun setFrameCallback(callback: (Bitmap) -> Unit) {
        frameCallback = callback
    }

    override fun setErrorCallback(callback: (String) -> Unit) {
        errorCallback = callback
    }

    override fun getSensorOrientation(): Int = sensorOrientation
}
