package com.photographers.cameraserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraServerService : LifecycleService() {
    
    companion object {
        private const val TAG = "CameraServerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "camera_server_channel"
        private const val SERVICE_NAME = "My Camera"
        private const val SERVICE_TYPE = "_mycamapp._tcp"
        private const val PORT = 8080
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    
    private val activeStreams = mutableSetOf<OutputStream>()
    private var isStreaming = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        initializeCamera()
        startSocketServer()
        startNsdBroadcast()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Camera server is running"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Server Active")
            .setContentText("Broadcasting on $SERVICE_TYPE")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "Camera provider initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startSocketServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Socket server started on port $PORT")
                
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let {
                            Log.d(TAG, "Client connected: ${it.inetAddress}")
                            launch { handleClient(it) }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error accepting client", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start socket server", e)
            }
        }
    }

    private fun startNsdBroadcast() {
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)
        
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "NSD unregistration failed: $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "NSD service registered: ${serviceInfo?.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "NSD service unregistered")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = socket.getInputStream().bufferedReader()
                val output = socket.getOutputStream()

                while (isActive && !socket.isClosed) {
                    val command = input.readLine() ?: break
                    Log.d(TAG, "Received command: $command")

                    when (command.trim()) {
                        "CMD_START_PREVIEW" -> {
                            activeStreams.add(output)
                            if (!isStreaming) {
                                startPreviewStream()
                            }
                        }
                        "CMD_STOP_PREVIEW" -> {
                            activeStreams.remove(output)
                            if (activeStreams.isEmpty()) {
                                stopPreviewStream()
                            }
                        }
                        "CMD_TAKE_PHOTO" -> {
                            takeHighResPhoto(output)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing socket", e)
                }
            }
        }
    }

    private fun startPreviewStream() {
        if (isStreaming) return
        
        isStreaming = true
        
        val preview = Preview.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            try {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                
                val jpegBytes = compressToJpeg(bytes, imageProxy.width, imageProxy.height)
                
                synchronized(activeStreams) {
                    val iterator = activeStreams.iterator()
                    while (iterator.hasNext()) {
                        val output = iterator.next()
                        try {
                            writeFrame(output, 'P', jpegBytes)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send preview frame", e)
                            iterator.remove()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing preview frame", e)
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.d(TAG, "Preview stream started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview stream", e)
            isStreaming = false
        }
    }

    private fun stopPreviewStream() {
        isStreaming = false
        cameraProvider?.unbindAll()
        Log.d(TAG, "Preview stream stopped")
    }

    private fun takeHighResPhoto(output: OutputStream) {
        if (imageCapture == null) {
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind image capture", e)
                return
            }
        }

        imageCapture?.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        
                        val jpegBytes = compressToJpeg(bytes, image.width, image.height, 95)
                        
                        writeFrame(output, 'H', jpegBytes)
                        Log.d(TAG, "High-res photo sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send high-res photo", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                }
            }
        )
    }

    private fun compressToJpeg(bytes: ByteArray, width: Int, height: Int, quality: Int = 20): ByteArray {
        // This is a simplified version. In production, you'd convert YUV to RGB and then compress
        // For now, we'll just return the raw bytes as a placeholder
        return bytes
    }

    private fun writeFrame(output: OutputStream, type: Char, data: ByteArray) {
        synchronized(output) {
            // Protocol: [type:1byte][size:4bytes][data:size bytes]
            output.write(type.code)
            output.write(ByteBuffer.allocate(4).putInt(data.size).array())
            output.write(data)
            output.flush()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying")
        
        isStreaming = false
        activeStreams.clear()
        
        serviceScope.cancel()
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }

        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering NSD service", e)
        }

        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
