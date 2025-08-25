package com.example.demoapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class CameraForegroundService : LifecycleService() {

    private lateinit var previewView: PreviewView

    override fun onCreate() {
        super.onCreate()

        // Start notification
        startForeground(1, createNotification())

        // Add previewView programmatically (since service has no layout)
        previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1) // Hidden preview
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(previewView, params)

        startCamera()
    }

    private fun createNotification(): Notification {
        val channelId = "camera_service_channel"
        val channelName = "Camera Service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId, channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            startCamera()
            notificationManager.createNotificationChannel(chan)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Camera Running")
            .setContentText("Foreground service is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        ContextCompat.getMainExecutor(this),
                        FaceAnalyzerBackground { faceCount ->
                            Log.d("FaceCountInBackground", "Detected $faceCount faces in background")
                            // TODO: You can show a notification or update a local database here
                        }
                    )
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(previewView)
    }
}

class FaceAnalyzerBackground(
    private val onFaceCount: (Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build()
    )

    private val seenTrackingIds = mutableMapOf<Int, Long>()
    private var totalFaceCount = 0
    private val TIME_THRESHOLD_MS = 5_000L

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val currentTime = System.currentTimeMillis()

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                for (face in faces) {
                    val trackingId = face.trackingId ?: continue
                    val lastSeen = seenTrackingIds[trackingId]
                    if (lastSeen == null || currentTime - lastSeen > TIME_THRESHOLD_MS) {
                        seenTrackingIds[trackingId] = currentTime
                        totalFaceCount++
                        onFaceCount(totalFaceCount)
                    }
                }

                // Clean up old tracking IDs
                seenTrackingIds.entries.removeAll {
                    currentTime - it.value > TIME_THRESHOLD_MS
                }
            }
            .addOnFailureListener {
                Log.e("FaceAnalyzerBG", "Detection failed", it)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}