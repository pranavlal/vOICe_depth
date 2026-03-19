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
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var urlTextView: TextView
    private lateinit var depthImageView: ImageView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var remoteSwitch: SwitchCompat

    private var streamService: DepthStreamService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DepthStreamService.LocalBinder
            streamService = binder.getService()
            isBound = true

            streamService?.setFrameCallback { bitmap ->
                runOnUiThread { depthImageView.setImageBitmap(bitmap) }
            }

            streamService?.setErrorCallback { errorMessage ->
                runOnUiThread { showError(errorMessage) }
            }

            streamService?.setPortCallback { actualPort ->
                runOnUiThread { updateUrlDisplay(actualPort) }
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
        remoteSwitch = findViewById(R.id.remoteSwitch)

        startButton.setOnClickListener { startServer() }
        stopButton.setOnClickListener { stopServer() }

        urlTextView.setOnClickListener {
            val url = urlTextView.text.toString()
            if (url.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("MJPEG URL", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        remoteSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (startButton.isEnabled == false) {
                // If server is running, inform user they need to restart to apply network changes
                Toast.makeText(this, "Restart server to apply network changes", Toast.LENGTH_LONG).show()
            }
            updateUrlDisplay(8080)
        }
        
        depthImageView.contentDescription = "Depth preview idle"
        updateUrlDisplay(8080)
    }

    private fun updateUrlDisplay(port: Int) {
        val isRemote = remoteSwitch.isChecked
        val ip = if (isRemote) (getLocalIpAddress() ?: "127.0.0.1") else "127.0.0.1"
        val url = "http://$ip:$port/depth_stream.mjpeg"
        urlTextView.text = url
        
        if (!startButton.isEnabled) {
            statusTextView.announceForAccessibility("Server address updated to $url")
        }
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
        intent.putExtra("isRemote", remoteSwitch.isChecked)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        statusTextView.text = "Server running..."
        depthImageView.contentDescription = "Depth preview active"
        startButton.isEnabled = false
        stopButton.isEnabled = true
        remoteSwitch.isEnabled = false // Disable toggle while running to avoid confusion
    }

    private fun stopServer() {
        unbindFromService()
        val intent = Intent(this, DepthStreamService::class.java)
        stopService(intent)

        statusTextView.text = "Server stopped"
        depthImageView.contentDescription = "Depth preview idle"
        depthImageView.setImageDrawable(null)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        remoteSwitch.isEnabled = true
        
        updateUrlDisplay(8080)
    }

    private fun showError(message: String) {
        try {
            statusTextView.text = message
            depthImageView.contentDescription = "Depth preview error: $message"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            startButton.isEnabled = true
            stopButton.isEnabled = false
            remoteSwitch.isEnabled = true
            statusTextView.announceForAccessibility(message)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun unbindFromService() {
        if (isBound) {
            streamService?.setFrameCallback(null)
            streamService?.setErrorCallback(null)
            streamService?.setPortCallback(null)
            unbindService(serviceConnection)
            isBound = false
            streamService = null
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error getting IP address", e)
        }
        return null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startServer()
        } else {
            showError("Camera and Notification permissions are required.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindFromService()
    }
}
