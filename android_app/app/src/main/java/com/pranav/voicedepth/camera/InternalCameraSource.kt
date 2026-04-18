package com.pranav.voicedepth.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.ActivityCompat
import java.io.ByteArrayOutputStream

/**
 * Camera source implementation for built-in Android cameras using Camera2 API
 */
class InternalCameraSource(private val context: Context) : ICameraSource {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageReader: ImageReader? = null
    
    private var frameCallback: ((Bitmap) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var sensorOrientation = 0
    
    // Performance: Reusable objects
    private val jpegOutputStream = ByteArrayOutputStream()

    override fun start() {
        cameraThread = HandlerThread("CameraBackground").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraList = manager.cameraIdList
            if (cameraList.isEmpty()) {
                errorCallback?.invoke("No cameras available on this device.")
                return
            }
            val cameraId = cameraList[0]
            
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                errorCallback?.invoke("Camera permission is required.")
                return
            }
            
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    val errorMsg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera is in use by another app"
                        ERROR_MAX_CAMERAS_IN_USE -> "Too many cameras open"
                        ERROR_CAMERA_DISABLED -> "Camera is disabled"
                        ERROR_CAMERA_DEVICE -> "Camera device error"
                        ERROR_CAMERA_SERVICE -> "Camera service error"
                        else -> "Camera error code: $error"
                    }
                    errorCallback?.invoke("Internal Camera error: $errorMsg")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            errorCallback?.invoke("Failed to open internal camera: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    frameCallback?.invoke(bitmap)
                }
            } finally {
                image.close()
            }
        }, cameraHandler)

        cameraDevice?.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader!!.surface)
                }
                session.setRepeatingRequest(request.build(), null, cameraHandler)
            }
            override fun onConfigureFailed(p0: CameraCaptureSession) {
                errorCallback?.invoke("Camera session configuration failed.")
            }
        }, cameraHandler)
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = image.planes[0].buffer.also { it.rewind() } // Y
        val uBuffer = image.planes[1].buffer.also { it.rewind() } // U
        val vBuffer = image.planes[2].buffer.also { it.rewind() } // V

        val rowStride = image.planes[0].rowStride
        var pos = 0

        if (rowStride == width) {
            yBuffer[nv21, 0, ySize]
            pos += ySize
        } else {
            var yBufferPos = 0
            while (pos < ySize) {
                yBuffer.position(yBufferPos)
                yBuffer[nv21, pos, width]
                yBufferPos += rowStride
                pos += width
            }
        }

        val uvRowStride = image.planes[2].rowStride
        val uvPixelStride = image.planes[2].pixelStride

        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = col * uvPixelStride + row * uvRowStride
                nv21[pos++] = vBuffer[vuPos]
                nv21[pos++] = uBuffer[vuPos]
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        jpegOutputStream.reset()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, jpegOutputStream)
        val imageBytes = jpegOutputStream.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    override fun stop() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraThread?.quitSafely()
        cameraThread = null
    }

    override fun setFrameCallback(callback: (Bitmap) -> Unit) {
        frameCallback = callback
    }

    override fun setErrorCallback(callback: (String) -> Unit) {
        errorCallback = callback
    }

    override fun getSensorOrientation(): Int = sensorOrientation
}
