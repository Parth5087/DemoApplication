package com.example.demoapplication

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.demoapplication.services.CameraBackgroundService
import com.example.demoapplication.services.LiveCameraDetectService
import com.example.demoapplication.services.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LauncherActivity : AppCompatActivity() {
    private val TAG = "LauncherActivity"
    @Volatile private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        Log.w(TAG, "Start Activity")

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

        val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d(TAG, "Started default LiveCameraDetectService")
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