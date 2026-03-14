package com.pranav.voicedepth

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var urlTextView: TextView
    private lateinit var depthImageView: ImageView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var streamService: DepthStreamService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DepthStreamService.LocalBinder
            streamService = binder.getService()
            isBound = true

            // Send initial rotation and set up callback
            streamService?.setDisplayRotation(getDisplayRotationDegrees())
            streamService?.setFrameCallback { bitmap ->
                runOnUiThread {
                    depthImageView.setImageBitmap(bitmap)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusTextView)
        urlTextView = findViewById(R.id.urlTextView)
        depthImageView = findViewById(R.id.depthImageView)
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
        
        // Initial setup for URL
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val port = 8080
        urlTextView.text = "http://$ip:$port/depth_stream.mjpeg"
    }

    private fun startServer() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
            return
        }

        val intent = Intent(this, DepthStreamService::class.java)
        intent.putExtra("port", 8080)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        statusTextView.setText(R.string.server_running)
        startButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun stopServer() {
        if (isBound) {
            streamService?.setFrameCallback(null)
            unbindService(serviceConnection)
            isBound = false
            streamService = null
        }
        
        val intent = Intent(this, DepthStreamService::class.java)
        stopService(intent)

        statusTextView.setText(R.string.server_stopped)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun getLocalIpAddress(): String? {
        return "127.0.0.1"
    }

    private fun getDisplayRotationDegrees(): Int {
        val rotation = windowManager.defaultDisplay.rotation
        return when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
