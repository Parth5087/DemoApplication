package com.uav.analytics

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uav.analytics.services.CameraBackgroundService
import com.uav.analytics.services.LiveCameraDetectService
import kotlinx.coroutines.launch

class LauncherActivity : AppCompatActivity() {
    companion object{
        private const val TAG = "LauncherActivity"
    }

    @Volatile private var launched = false
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    // ANR protection
    private val mainHandler = Handler(Looper.getMainLooper())
    private var anrWatchdog: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        Log.w(TAG, "LauncherActivity created")

        // Start ANR protection
        startAnrProtection()

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                showCameraIdDialogIfNeeded { proceedWithLaunch() }
            } else {
                Log.w(TAG, "Camera permission denied")
                finishWithError("Camera permission required")
            }
        }
        checkPermissionsAndProceed()
    }

    private fun checkPermissionsAndProceed() {
        lifecycleScope.launch {
            val hasPermission = ContextCompat.checkSelfPermission(
                this@LauncherActivity,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                showCameraIdDialogIfNeeded { proceedWithLaunch() }
            } else {
                permissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    private fun showCameraIdDialogIfNeeded(onComplete: () -> Unit) {
        val savedCameraId = prefs.getString("camera_id", null)
        if (savedCameraId != null) {
            Log.d(TAG, "Camera ID already saved: $savedCameraId")
            onComplete()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_camera_id, null)
        val etCameraId = dialogView.findViewById<EditText>(R.id.et_camera_id)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnSave.setOnClickListener {
            val cameraId = etCameraId.text.toString().trim()
            if (cameraId.isNotEmpty()) {
                prefs.edit().putString("camera_id", cameraId).apply()
                Log.d(TAG, "Camera ID saved: $cameraId")
                dialog.dismiss()
                onComplete()
            } else {
                etCameraId.error = "Please enter a valid Camera ID"
            }
        }

        dialog.show()
    }

    private fun proceedWithLaunch() {
        if (launched) return

        mainHandler.postDelayed({
            if (!launched) {
                launched = true
                stopAnrProtection()

                val cachedDestination = getCachedDestination()
                startServiceByDestination(cachedDestination)

                fetchFreshRemoteConfigInBackground {
                    Log.d(TAG, "RemoteConfig background task completed - finishing activity")
                    finish()
                }
            }
        }, 8000)
    }

    private fun getCachedDestination(): String {
        return prefs.getString("cached_destination", "live_camera_detect") ?: "live_camera_detect"
    }

    private fun startServiceByDestination(destination: String) {
        Log.d(TAG, "Starting service for destination: $destination")

        try {
            when (destination.lowercase()) {
                "auto_photo_capture" -> {
                    // Stop any running LiveCameraDetectService first
                    stopService(Intent(this, LiveCameraDetectService::class.java))
                    val serviceIntent = Intent(this, CameraBackgroundService::class.java)
                    ContextCompat.startForegroundService(this, serviceIntent)
                    Log.d(TAG, "Started CameraBackgroundService for auto_photo_capture")
                }
                "live_camera_detect" -> {
                    // Stop any running CameraBackgroundService first
                    stopService(Intent(this, CameraBackgroundService::class.java))
                    val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
                    ContextCompat.startForegroundService(this, serviceIntent)
                    Log.d(TAG, "Started LiveCameraDetectService for live_camera_detect")
                }
                else -> {
                    // Fallback to default service
                    Log.w(TAG, "Unknown destination '$destination' - falling back to LiveCameraDetectService")
                    stopService(Intent(this, CameraBackgroundService::class.java))
                    val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
                    ContextCompat.startForegroundService(this, serviceIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service for destination '$destination': ${e.message}")
            // Ultimate fallback
            try {
                stopService(Intent(this, CameraBackgroundService::class.java))
                val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
                startService(serviceIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback service start also failed: ${e2.message}")
            }
        }
    }

    private fun fetchFreshRemoteConfigInBackground(onComplete: () -> Unit) {
        Log.d(TAG, "Starting background RemoteConfig fetch...")

        RemoteConfigHelper.fetchAndActivate { success ->
            Log.d(TAG, "RemoteConfig fetch completed - success: $success")

            if (success) {
                val newDestination = RemoteConfigHelper.startDestination()
                Log.d(TAG, "RemoteConfig destination: $newDestination")

                val oldDestination = getCachedDestination()

                // Update cache for next launch
                prefs.edit().putString("cached_destination", newDestination).apply()

                // Restart service if destination changed
                if (oldDestination != newDestination) {
                    Log.i(TAG, "Destination changed: $oldDestination -> $newDestination")
                    restartServiceWithNewDestination(newDestination)
                }
            }

            // Single onComplete call
            Log.d(TAG, "Finishing activity after RemoteConfig")
            onComplete()
        }
    }

    private fun restartServiceWithNewDestination(newDestination: String) {
        // Use application context to start service since activity might be finished
        val appContext = applicationContext

        mainHandler.post {
            try {
                Log.i(TAG, "Immediately restarting service with new destination: $newDestination")

                when (newDestination.lowercase()) {
                    "auto_photo_capture" -> {
                        // Stop LiveCameraDetectService and start CameraBackgroundService
                        appContext.stopService(Intent(appContext, LiveCameraDetectService::class.java))

                        val serviceIntent = Intent(appContext, CameraBackgroundService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(serviceIntent)
                        } else {
                            appContext.startService(serviceIntent)
                        }
                        Log.d(TAG, "Service switched to CameraBackgroundService")
                    }
                    "live_camera_detect" -> {
                        // Stop CameraBackgroundService and start LiveCameraDetectService
                        appContext.stopService(Intent(appContext, CameraBackgroundService::class.java))

                        val serviceIntent = Intent(appContext, LiveCameraDetectService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(serviceIntent)
                        } else {
                            appContext.startService(serviceIntent)
                        }
                        Log.d(TAG, "Service switched to LiveCameraDetectService")
                    }
                }

                // Optional: Show a notification about the service change
                showServiceChangeNotification(newDestination)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart service with new destination: ${e.message}")
            }
        }
    }

    private fun showServiceChangeNotification(newDestination: String) {
        try {
            val serviceName = when (newDestination.lowercase()) {
                "auto_photo_capture" -> "Auto Photo Capture"
                "live_camera_detect" -> "Live Camera Detection"
                else -> "Camera Service"
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "service_change",
                    "Service Changes",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, "service_change")
                .setContentTitle("Service Updated")
                .setContentText("Switched to $serviceName")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(103, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show service change notification: ${e.message}")
        }
    }

    // ANR Protection Methods
    private fun startAnrProtection() {
        anrWatchdog = Runnable {
            if (!launched) {
                Log.w(TAG, "ANR PROTECTION: Forcing service start after timeout")
                launched = true
                val cachedDestination = getCachedDestination()
                startServiceByDestination(cachedDestination)
                finish()
            }
        }
        mainHandler.postDelayed(anrWatchdog!!, 15000L) // 15 second protection
    }

    private fun stopAnrProtection() {
        anrWatchdog?.let {
            mainHandler.removeCallbacks(it)
            anrWatchdog = null
        }
    }

    private fun finishWithError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        stopAnrProtection()
        finish()
    }

    override fun onDestroy() {
        stopAnrProtection()

        // Last resort: if we're being destroyed without launching, start service
        if (!launched) {
            Log.w(TAG, "Activity destroyed without launch - starting service as fallback")
            val cachedDestination = getCachedDestination()
            startServiceByDestination(cachedDestination)
        }

        super.onDestroy()
    }
}