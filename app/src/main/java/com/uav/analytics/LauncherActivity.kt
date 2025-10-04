package com.uav.analytics

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.uav.analytics.services.CameraBackgroundService
import com.uav.analytics.services.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LauncherActivity : AppCompatActivity() {
    private val TAG = "LauncherActivity"
    @Volatile private var launched = false

    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        Log.w(TAG, "Start Activity")

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                showCameraIdDialogIfNeeded { proceedWithLaunch() }
            } else {
                Log.w(TAG, "Camera permission denied — cannot proceed")
                // Optionally show a Toast or dialog explaining permission is required
                finish()
            }
        }

        lifecycleScope.launch {
            val hasPermission = ContextCompat.checkSelfPermission(this@LauncherActivity, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
                etCameraId.requestFocus()
            }
        }

        dialog.show()
    }

    private fun proceedWithLaunch() {
        if (launched) return

        // Kick off non-blocking startup flow
        lifecycleScope.launch {
            // Run fetch+activate with timeout on IO dispatcher, but don't block UI.
            val success = withContext(Dispatchers.IO) {
                try {
                    // Wait up to 10s for remote config; treat timeout as failure
                    withTimeout(10_000L) {
                        fetchAndActivateAsync()
                    }
                } catch (te: TimeoutCancellationException) {
                    Log.w(TAG, "RemoteConfig fetch timed out after 10s")
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "RemoteConfig fetch threw: ${e.message}", e)
                    false
                }
            }

            // Now switch to Main to open destination / start services and finish activity
            withContext(Dispatchers.Main) {
                if (!launched) {
                    if (success) {
                        try {
                            val dest = RemoteConfigHelper.startDestination()
                            NetworkModule.applyStartDestination(dest)
                            Log.w(TAG, "Start Activity - calling openDestination() with remote config: $dest")
                            openDestination(dest)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open destination from remote config: ${e.message}", e)
                            openDefaultService()
                        }
                    } else {
                        Log.w(TAG, "RemoteConfig fetch failed — opening default LiveCameraDetectService")
                        openDefaultService()
                    }
                } else {
                    Log.d(TAG, "Already launched; skipping remote config result handling")
                }
            }
        }

        // Defensive fallback: if for some reason coroutine was cancelled or blocked, still ensure default after 10s.
        // This fallback simply schedules a check on main thread; it doesn't block.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!launched) {
                Log.w(TAG, "Fallback timeout triggered — opening default LiveCameraDetectService")
                openDefaultService()
            }
        }, 10_000L)
    }

    /**
     * Bridge helper: convert callback-based fetchAndActivate to suspending boolean result.
     * Modify if RemoteConfigHelper already offers a suspending API; use that directly.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun fetchAndActivateAsync(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            RemoteConfigHelper.fetchAndActivate { success ->
                // Ensure resume only once and on coroutine's context
                if (cont.isActive) {
                    cont.resume(success) {}
                }
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false) {}
        }

        // If coroutine is cancelled, there's no built-in cancellation in many callback APIs.
        // Optionally, you can add cancellation cleanup here if RemoteConfigHelper supports it.
        cont.invokeOnCancellation {
            Log.w(TAG, "fetchAndActivateAsync coroutine cancelled")
        }
    }

    private fun openDestination(dest: String) {
        if (launched) return
        launched = true

        when (val destination = dest.lowercase()) {
            "auto_photo_capture" -> {
                val serviceIntent = Intent(this, CameraBackgroundService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                Log.d(TAG, "Started CameraService for auto_photo_capture")
            }
            "live_camera_detect" -> {
//                val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
//                ContextCompat.startForegroundService(this, serviceIntent)
                startActivity(Intent(this, MainActivity::class.java))
                Log.d("OpenDest", "Started LiveCameraService for live_camera_detect")
            }
            else -> {
                Log.d(TAG, "Unknown destination '$destination' — starting default LiveCameraService")
                openDefaultService()
            }
        }
        finish()
    }

    private fun openDefaultService() {
        if (launched) return
        launched = true

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        // If destroyed before launching any service, start default service.
        if (!launched) {
            Log.w(TAG, "Activity destroyed before launch — opening default service")
            // Start default service on main thread — don't call blocking code here.
            openDefaultService()
        }
        super.onDestroy()
    }
}