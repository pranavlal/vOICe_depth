package com.pranav.voicedepth

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Formatter

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var urlTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var mjpegServer: MjpegServer? = null
    private var depthEngine: DepthEngine? = null
    
    private var cameraDevice: CameraDevice? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageReader: ImageReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        urlTextView = findViewById(R.id.urlTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener { startServer() }
        stopButton.setOnClickListener { stopServer() }

        urlTextView.setOnClickListener {
            val url = urlTextView.text.toString()
            if (url.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MJPEG URL", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.copy_to_clipboard, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startServer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        val ip = getLocalIpAddress()
        if (ip == null) {
            Toast.makeText(this, "No network connection", Toast.LENGTH_SHORT).show()
            return
        }

        val port = 8080
        val url = "http://$ip:$port/depth_stream.mjpeg"
        
        try {
            depthEngine = DepthEngine(this)
            mjpegServer = MjpegServer(port)
            mjpegServer?.start()
            
            startCamera()

            statusTextView.setText(R.string.server_running)
            urlTextView.text = url
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun startCamera() {
        cameraThread = HandlerThread("CameraBackground").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
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
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCaptureSession() {
        imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            // Optimization: Reuse buffers if possible (not fully implemented here for brevity, 
            // but converted Image to Bitmap more efficiently)
            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap != null && mjpegServer != null) {
                // If ARCore is supported, we would ideally extract the depth map from the AR frame.
                // However, the Camera2 and ARCore Session interaction is complex.
                // For this implementation, we ensure depthEngine is initialized and preferred.
                val depthBitmap = depthEngine?.processFrame(bitmap)
                if (depthBitmap != null) {
                    mjpegServer?.setFrame(depthBitmap)
                }
            }
        }, cameraHandler)

        cameraDevice?.createCaptureSession(listOf(imageReader!!.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(imageReader!!.surface)
                }
                session.setRepeatingRequest(request.build(), null, cameraHandler)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {}
        }, cameraHandler)
    }

    private var nv21Buffer: ByteArray? = null

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val totalSize = ySize + uSize + vSize
        if (nv21Buffer == null || nv21Buffer!!.size != totalSize) {
            nv21Buffer = ByteArray(totalSize)
        }
        val nv21 = nv21Buffer!!
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        // Performance: Use a lower quality for intermediate processing if possible, but 100 for depth input
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun stopServer() {
        mjpegServer?.stop()
        mjpegServer = null
        
        depthEngine?.stop()
        depthEngine = null

        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null

        statusTextView.setText(R.string.server_stopped)
        urlTextView.text = ""
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun getLocalIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipAddress = wifiInfo.ipAddress
        return if (ipAddress == 0) null else {
            val bytes = byteArrayOf(
                (ipAddress and 0xFF).toByte(),
                (ipAddress shr 8 and 0xFF).toByte(),
                (ipAddress shr 16 and 0xFF).toByte(),
                (ipAddress shr 24 and 0xFF).toByte()
            )
            InetAddress.getByAddress(bytes).hostAddress
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
