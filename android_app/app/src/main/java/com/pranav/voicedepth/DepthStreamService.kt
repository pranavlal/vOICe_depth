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
import androidx.core.app.ServiceCompat

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
    private var errorCallback: ((String) -> Unit)? = null
    private var portCallback: ((Int) -> Unit)? = null
    private var sensorOrientation = 0
    
    // Performance: Reusable objects to prevent GC thrashing
    private val jpegOutputStream = ByteArrayOutputStream()
    private val rotationMatrix = Matrix()
    private var cachedRotatedBitmap: Bitmap? = null
    private var rotationCanvas: android.graphics.Canvas? = null
    private var cachedRotatedBitmap2: Bitmap? = null
    private var rotationCanvas2: android.graphics.Canvas? = null

    inner class LocalBinder : Binder() {
        fun getService(): DepthStreamService = this@DepthStreamService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setFrameCallback(callback: ((Bitmap) -> Unit)?) {
        frameCallback = callback
    }

    fun setErrorCallback(callback: ((String) -> Unit)?) {
        errorCallback = callback
    }

    fun setPortCallback(callback: ((Int) -> Unit)?) {
        portCallback = callback
    }

    override fun onCreate() {
        super.onCreate()
        depthEngine = DepthEngine(this)
        if (!depthEngine!!.isInitialized) {
            val errorMsg = depthEngine!!.initError ?: "Unknown error"
            android.util.Log.e("DepthStreamService", "DepthEngine failed to initialize: $errorMsg")
            errorCallback?.invoke("Model failed to load: $errorMsg")
        }
    }

    fun stopStuff() {
       android.util.Log.i("DepthStreamService", "Stopping service and cleaning up resources")
       depthEngine?.stop()
       mjpegServer?.notifyShutdown()
       ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
       stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8080) ?: 8080
        val isRemote = intent?.getBooleanExtra("isRemote", false) ?: false
        
        startForegroundServiceNotification()
        
        if (mjpegServer == null) {
            try {
                val hostname = if (isRemote) null else "127.0.0.1"
                mjpegServer = MjpegServer(hostname, port)
                mjpegServer?.start()
                portCallback?.invoke(port)
                startCamera()
            } catch (e: java.net.BindException) {
                android.util.Log.e("DepthStreamService", "Port $port already in use", e)
                errorCallback?.invoke("Port $port is already in use.")
                stopSelf()
            } catch (e: Exception) {
                android.util.Log.e("DepthStreamService", "Failed to start server", e)
                errorCallback?.invoke("Server failed to start: ${e.message}")
                stopSelf()
            }
        } else {
            // Already running, just notify port again
            portCallback?.invoke(port)
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
            val cameraList = manager.cameraIdList
            if (cameraList.isEmpty()) {
                android.util.Log.e("DepthStreamService", "No cameras available")
                errorCallback?.invoke("No cameras available on this device.")
                return
            }
            val cameraId = cameraList[0]
            
            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
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
                    android.util.Log.e("DepthStreamService", errorMsg)
                    errorCallback?.invoke("Camera error: $errorMsg")
                    stopSelf()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            android.util.Log.e("DepthStreamService", "Failed to open camera", e)
            errorCallback?.invoke("Failed to open camera: ${e.message}")
        }
    }

    private fun createCaptureSession() {
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            try {
                val bitmap = imageToBitmap(image)
                val degrees = sensorOrientation

                // if (bitmap != null) { // Testing only! Return unprocessed camera view
                //     frameCallback?.invoke(bitmap)
                //     return@setOnImageAvailableListener
                // }

                if (bitmap != null) {
                    val rotatedBitmap = rotateBitmap(bitmap, degrees, cacheIndex = 1)
                    val depthBitmap = depthEngine?.processFrame(rotatedBitmap)
                    if (depthBitmap != null) {
                        mjpegServer?.setFrame(rotateBitmap(depthBitmap, 360 - degrees, cacheIndex = 2)) // Rotate back
                        frameCallback?.invoke(depthBitmap)
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
    
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int, cacheIndex: Int = 1): Bitmap {
        val normalizedDegrees = (degrees % 360 + 360) % 360
        if (normalizedDegrees == 0) return bitmap
        
        val newWidth = if (normalizedDegrees % 180 == 0) bitmap.width else bitmap.height
        val newHeight = if (normalizedDegrees % 180 == 0) bitmap.height else bitmap.width
        
        var cached = if (cacheIndex == 1) cachedRotatedBitmap else cachedRotatedBitmap2
        var canvas = if (cacheIndex == 1) rotationCanvas else rotationCanvas2

        if (cached == null || cached.width != newWidth || cached.height != newHeight) {
            android.util.Log.i("DepthStreamService", "Allocating new rotated bitmap (cache $cacheIndex): ${newWidth}x${newHeight}")
            cached = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            canvas = android.graphics.Canvas(cached!!)
            
            if (cacheIndex == 1) {
                cachedRotatedBitmap = cached
                rotationCanvas = canvas
            } else {
                cachedRotatedBitmap2 = cached
                rotationCanvas2 = canvas
            }
        }
        
        rotationMatrix.reset()
        rotationMatrix.postRotate(normalizedDegrees.toFloat())
        
        // Correct translation after rotation around 0,0
        if (normalizedDegrees == 90) rotationMatrix.postTranslate(newWidth.toFloat(), 0f)
        else if (normalizedDegrees == 180) rotationMatrix.postTranslate(newWidth.toFloat(), newHeight.toFloat())
        else if (normalizedDegrees == 270) rotationMatrix.postTranslate(0f, newHeight.toFloat())
        
        canvas?.drawColor(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.CLEAR)
        canvas?.drawBitmap(bitmap, rotationMatrix, null)
        
        return cached!!
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegServer?.notifyShutdown()
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
