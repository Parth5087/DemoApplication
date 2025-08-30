package com.example.demoapplication

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.demoapplication.services.CameraService
import com.example.demoapplication.services.NetworkModule

class LauncherActivity : AppCompatActivity() {
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fetch RC -> apply base URL -> open destination
        RemoteConfigHelper.fetchAndActivate {
            val dest = RemoteConfigHelper.startDestination() // "auto_photo_capture" / "live_camera_detect"
            NetworkModule.applyStartDestination(dest)
            openDestination()
        }

        // Safety fallback in case fetch is slow: still apply base URL from cached/DEFAULT values
        window.decorView.postDelayed({
            if (!launched) {
                val dest = RemoteConfigHelper.startDestination()
                NetworkModule.applyStartDestination(dest)
                openDestination()
            }
        }, 1500)
    }

    private fun openDestination() {
        if (launched) return
        launched = true

        val dest = RemoteConfigHelper.startDestination().lowercase()
        when (dest) {
            "auto_photo_capture" -> {
                startActivity(Intent(this, CameraDetectActivity::class.java))
            }
            "live_camera_detect" -> {
                startActivity(Intent(this, MainActivity::class.java))
            }
            else -> {
                startActivity(Intent(this, CameraDetectActivity::class.java))
            }
        }
        finish()
    }

}