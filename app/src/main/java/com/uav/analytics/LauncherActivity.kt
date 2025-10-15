package com.uav.analytics

import android.annotation.SuppressLint
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
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.uav.analytics.models.ErrorResponse
import com.uav.analytics.models.RegisterDeviceRequest
import com.uav.analytics.services.CameraBackgroundService
import com.uav.analytics.services.LiveCameraDetectService
import com.uav.analytics.services.NetworkModule
import com.uav.analytics.utils.ResponseUtils
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

    // Track completion states
    private var permissionGranted = false
    private var cameraIdSaved = false

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
                Log.d(TAG, "Camera permission granted")
                permissionGranted = true
                // After permission granted, show camera ID dialog
                showCameraIdDialogIfNeeded()
            } else {
                Log.w(TAG, "Camera permission denied")
                finishWithError("Camera permission required")
            }
        }

        // Start the process
        checkPermissionsAndProceed()
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidId(context: Context): String? {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            Log.d(TAG, "Android ID: $androidId")
            androidId
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving Android ID", e)
            null
        }
    }

    private fun checkPermissionsAndProceed() {
        lifecycleScope.launch {
            val hasPermission = ContextCompat.checkSelfPermission(
                this@LauncherActivity,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                Log.d(TAG, "Camera permission already granted")
                permissionGranted = true
                showCameraIdDialogIfNeeded()
            } else {
                permissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    private fun showCameraIdDialogIfNeeded() {
        Log.d(TAG, "showCameraIdDialogIfNeeded called - permissionGranted: $permissionGranted")

        val savedCameraId = prefs.getString("camera_id", null)
        if (savedCameraId != null) {
            Log.d(TAG, "Camera ID already saved: $savedCameraId")
            cameraIdSaved = true
            // Both conditions are met, proceed to launch
            proceedWithLaunch()
            return
        }

        // If we don't have permission yet, wait for it
        if (!permissionGranted) {
            Log.d(TAG, "Waiting for permission before showing camera ID dialog")
            return
        }
        val androidId = getAndroidId(this) ?: "Unavailable"

        val dialogView = layoutInflater.inflate(R.layout.dialog_camera_id, null)
        val etCameraId = dialogView.findViewById<EditText>(R.id.et_camera_id)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar) // Add this to your dialog layout

        val dialog = AlertDialog.Builder(this)
            .setTitle("Device ID: $androidId")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnSave.setOnClickListener {
            val cameraId = etCameraId.text.toString().trim()

            if (cameraId.isEmpty()) {
                etCameraId.error = "Please enter a valid Camera ID"
                return@setOnClickListener
            }

            // Show loading state
            btnSave.isEnabled = false
            btnSave.text = getString(R.string.registering)
            progressBar.visibility = View.VISIBLE

            Log.d(TAG, "Attempting device registration: camera=$cameraId, device=$androidId")

            // Execute registration in coroutine
            lifecycleScope.launch {
                try {
                    val request = RegisterDeviceRequest(
                        cameraId = cameraId,
                        deviceId = androidId
                    )

                    val response = NetworkModule.api.registerDevice(request)
                    Log.d(TAG, "Device registration response: ${response.code()} - ${response.message()}")

                    if (response.isSuccessful) {
                        // Registration successful
                        val registerResponse = response.body()
                        Log.d(TAG, "Device registration successful: $registerResponse")

                        prefs.edit().putString("camera_id", cameraId).apply()
                        Log.d(TAG, "Camera ID saved: $cameraId")
                        cameraIdSaved = true

                        dialog.dismiss()
                        proceedWithLaunch()
                    } else {
                        // Registration failed - parse error response
                        val errorBody = response.errorBody()?.string()
                        Log.e(TAG, "Device registration failed: ${response.code()} - $errorBody")

                        val errorMessage = ResponseUtils.parseErrorMessage(errorBody, response.code())

                        runOnUiThread {
                            btnSave.isEnabled = true
                            btnSave.text = getString(R.string.save)
                            progressBar.visibility = View.GONE
                            etCameraId.error = errorMessage
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Device registration error", e)
                    runOnUiThread {
                        btnSave.isEnabled = true
                        btnSave.text = getString(R.string.save)
                        progressBar.visibility = View.GONE
                        etCameraId.error = "Network error: ${e.message}"
                    }
                }
            }
        }

        dialog.show()
    }

    private fun proceedWithLaunch() {
        Log.d(TAG, "proceedWithLaunch called - launched: $launched, permission: $permissionGranted, cameraID: $cameraIdSaved")

        if (launched) {
            Log.d(TAG, "Already launched, skipping")
            return
        }

        // Double check both conditions
        if (!permissionGranted || !cameraIdSaved) {
            Log.w(TAG, "Cannot proceed - missing conditions. Permission: $permissionGranted, CameraID: $cameraIdSaved")
            return
        }

        launched = true
        stopAnrProtection()

        Log.d(TAG, "All conditions met, proceeding with launch")

        mainHandler.postDelayed({
            val cachedDestination = getCachedDestination()
            startServiceByDestination(cachedDestination)

            fetchFreshRemoteConfigInBackground {
                Log.d(TAG, "RemoteConfig background task completed - finishing activity")
                finish()
            }
        }, 2000)
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
//                    stopService(Intent(this, CameraBackgroundService::class.java))
//                    val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
//                    ContextCompat.startForegroundService(this, serviceIntent)
                    startActivity(Intent(this,MainActivity::class.java))
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
                Log.w(TAG, "ANR PROTECTION: Checking if we can proceed despite timeout")
                // Even in ANR protection, only proceed if we have both conditions
                if (permissionGranted && cameraIdSaved) {
                    Log.w(TAG, "ANR PROTECTION: Forcing service start after timeout")
                    launched = true
                    val cachedDestination = getCachedDestination()
                    startServiceByDestination(cachedDestination)
                    finish()
                } else {
                    Log.w(TAG, "ANR PROTECTION: Cannot proceed - missing permission or camera ID")
                    Log.w(TAG, "Permission: $permissionGranted, CameraID: $cameraIdSaved")
                    // Extend the timeout since we're waiting for user input
                    mainHandler.postDelayed(anrWatchdog!!, 10000L) // Another 10 seconds
                }
            }
        }
        mainHandler.postDelayed(anrWatchdog!!, 30000L) // 30 second protection for user input
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

        // Last resort: if we're being destroyed without launching, only start service if we have both conditions
        if (!launched && permissionGranted && cameraIdSaved) {
            Log.w(TAG, "Activity destroyed with both conditions met - starting service as fallback")
            val cachedDestination = getCachedDestination()
            startServiceByDestination(cachedDestination)
        } else if (!launched) {
            Log.w(TAG, "Activity destroyed without both conditions - permission: $permissionGranted, cameraID: $cameraIdSaved")
        }

        super.onDestroy()
    }
}