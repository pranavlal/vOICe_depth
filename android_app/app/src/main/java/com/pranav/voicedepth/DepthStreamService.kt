package com.pranav.voicedepth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pranav.voicedepth.camera.ExternalCameraSource
import com.pranav.voicedepth.camera.ICameraSource
import com.pranav.voicedepth.camera.InternalCameraSource
import java.io.ByteArrayOutputStream

class DepthStreamService : Service() {

    private val binder = LocalBinder()
    
    private var mjpegServer: MjpegServer? = null
    private var depthEngine: DepthEngine? = null
    
    private var cameraSource: ICameraSource? = null
    
    private var frameCallback: ((Bitmap) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var portCallback: ((Int) -> Unit)? = null
    
    // Performance: Reusable objects to prevent GC thrashing
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
       stopCamera()
       ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
       stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 8080) ?: 8080
        val isRemote = intent?.getBooleanExtra("isRemote", false) ?: false
        val useExternalCamera = intent?.getBooleanExtra("useExternalCamera", false) ?: false
        
        startForegroundServiceNotification()
        
        if (mjpegServer == null) {
            try {
                val hostname = if (isRemote) null else "127.0.0.1"
                mjpegServer = MjpegServer(hostname, port)
                mjpegServer?.start()
                portCallback?.invoke(port)
                
                startCamera(useExternalCamera)
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

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
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

    private fun startCamera(useExternal: Boolean) {
        stopCamera() // Ensure clean start
        
        cameraSource = if (useExternal) {
            android.util.Log.i("DepthStreamService", "Starting External/USB Camera")
            ExternalCameraSource(this)
        } else {
            android.util.Log.i("DepthStreamService", "Starting Internal Camera")
            InternalCameraSource(this)
        }

        cameraSource?.apply {
            setErrorCallback { errorMsg ->
                android.util.Log.e("DepthStreamService", "Camera Error: $errorMsg")
                errorCallback?.invoke(errorMsg)
            }
            
            setFrameCallback { bitmap ->
                val sensorOrientation = getSensorOrientation()
                processAndStream(bitmap, sensorOrientation)
            }
            
            start()
        }
    }

    private fun processAndStream(bitmap: Bitmap, degrees: Int) {
        val rotatedBitmap = rotateBitmap(bitmap, degrees, cacheIndex = 1)
        val depthBitmap = depthEngine?.processFrame(rotatedBitmap)
        if (depthBitmap != null) {
            mjpegServer?.setFrame(rotateBitmap(depthBitmap, 360 - degrees, cacheIndex = 2)) // Rotate back
            frameCallback?.invoke(depthBitmap)
        }
    }

    private fun stopCamera() {
        cameraSource?.stop()
        cameraSource = null
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

        stopCamera()
    }
}
