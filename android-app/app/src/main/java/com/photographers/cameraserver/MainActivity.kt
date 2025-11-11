package com.photographers.cameraserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var btnStartStop: Button
    private lateinit var tvStatus: TextView
    private var isServerRunning = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStartStop = findViewById(R.id.btnStartStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStartStop.setOnClickListener {
            if (allPermissionsGranted()) {
                toggleServer()
            } else {
                requestPermissions()
            }
        }

        updateUI()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                toggleServer()
            } else {
                tvStatus.text = "Permissions denied. Please grant all permissions."
            }
        }
    }

    private fun toggleServer() {
        if (isServerRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    private fun startServer() {
        val intent = Intent(this, CameraServerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        isServerRunning = true
        updateUI()
    }

    private fun stopServer() {
        val intent = Intent(this, CameraServerService::class.java)
        stopService(intent)
        isServerRunning = false
        updateUI()
    }

    private fun updateUI() {
        if (isServerRunning) {
            btnStartStop.text = "Stop Server"
            tvStatus.text = "Server is running\nBroadcasting on _mycamapp._tcp"
        } else {
            btnStartStop.text = "Start Server"
            tvStatus.text = "Server is stopped"
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if service is actually running
        // This is a simple approach; in production, you might use a bound service
        updateUI()
    }
}
