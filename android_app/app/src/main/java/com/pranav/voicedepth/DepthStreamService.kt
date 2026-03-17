package com.pranav.voicedepth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

class DepthStreamService : Service() {

    private val binder = LocalBinder()
    
    private var mjpegServer: MjpegServer? = null
    private var depthEngine: DepthEngine? = null
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageReader: ImageReader? = null
    
    private var frameCallback: ((Bitmap) -> Unit)? = null
    private var sensorOrientation = 0
    
    // Performance: Reusable objects to prevent GC thrashing
    private val jpegOutputStream = ByteArrayOutputStream()
    private val rotationMatrix = Matrix()

    inner class LocalBinder : Binder() {
        fun getService(): DepthStreamService = this@DepthStreamService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setFrameCallback(callback: ((Bitmap) -> Unit)?) {
        frameCallback = callback
    }

    override fun onCreate() {
        super.onCreate()
        depthEngine = DepthEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8080) ?: 8080
        
        startForegroundServiceNotification()
        
        if (mjpegServer == null) {
            try {
                mjpegServer = MjpegServer(port)
                mjpegServer?.start()
                startCamera()
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "DepthStreamChannel"
        val channelName = "Depth Stream Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Depth Server Running")
            .setContentText("Streaming depth map data for The vOICe")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCamera() {
        cameraThread = HandlerThread("CameraBackground").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            
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
                    stopSelf()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCaptureSession() {
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            try {
                val bitmap = imageToBitmap(image)

                if (bitmap != null) {
                    val depthBitmap = depthEngine?.processFrame(bitmap)
                    if (depthBitmap != null) {
                        val degrees = sensorOrientation
                        val rotatedBitmap = rotateBitmap(depthBitmap, degrees)
                        
                        mjpegServer?.setFrame(depthBitmap) // Send un-rotated raw sensor image to The vOICe
                        
                        // Send upright rotated image to local UI preview if bound
                        frameCallback?.invoke(rotatedBitmap)
                    }
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
                android.util.Log.e("DepthStreamService", "Camera capture session configuration failed")
            }
        }, cameraHandler)
    }

    private var nv21Buffer: ByteArray? = null

    // From Method #3 in https://medium.com/@eeshan.jamal/convert-image-from-yuv-420-888-to-nv21-format-in-android-part-i-a0aa1e7fb3d0
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4

        val nv21 = ByteArray(ySize + uvSize * 2)

        val yBuffer = image.planes[0].buffer.also { it.rewind() } // Y
        val uBuffer = image.planes[1].buffer.also { it.rewind() } // U
        val vBuffer = image.planes[2].buffer.also { it.rewind() } // V

        var rowStride = image.planes[0].rowStride
        assert(image.planes[0].pixelStride == 1)

        var pos = 0

        if (rowStride == width) { // likely
            yBuffer[nv21, 0, ySize]
            pos += ySize
        } else {
            var yBufferPos = -rowStride.toLong() // not an actual position
            while (pos < ySize) {
                yBufferPos += rowStride.toLong()
                yBuffer.position(yBufferPos.toInt())
                yBuffer[nv21, pos, width]
                pos += width
            }
        }

        rowStride = image.planes[2].rowStride
        val pixelStride = image.planes[2].pixelStride

        assert(rowStride == image.planes[1].rowStride)
        assert(pixelStride == image.planes[1].pixelStride)

        if (pixelStride == 2 && rowStride == width && uBuffer[0] == vBuffer[1]) {
            // maybe V and U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            val savePixel = vBuffer[1]
            try {
                vBuffer.put(1, (savePixel.toInt().inv()).toByte())
                if (uBuffer[0] == (savePixel.toInt().inv()).toByte()) {
                    vBuffer.put(1, savePixel)
                    vBuffer.position(0)
                    uBuffer.position(0)
                    vBuffer[nv21, ySize, 1]
                    uBuffer[nv21, ySize + 1, uBuffer.remaining()]

                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    jpegOutputStream.reset()
                    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, jpegOutputStream)
                    val imageBytes = jpegOutputStream.toByteArray()
                    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
            } catch (ex: Exception) {
                // unfortunately, we cannot check if vBuffer and uBuffer overlap
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel)
        }

        // other currentFrame, currentFrame by currentFrame
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = col * pixelStride + row * rowStride
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
    
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        rotationMatrix.reset()
        rotationMatrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegServer?.stop()
        mjpegServer = null
        
        depthEngine?.stop()
        depthEngine = null

        captureSession?.close()
        captureSession = null

        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null

        cameraThread?.quitSafely()
        cameraThread = null
    }
}
