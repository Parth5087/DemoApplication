package com.example.demoapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.demoapplication.services.BootStarterService


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                val serviceIntent = Intent(context, BootStarterService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d("BootReceiver", "Launching signage app after boot")
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not start activity on boot", e)
                // Fallback: start a foreground service or schedule WorkManager job here
            }
        }
    }
    /*override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "onReceive: boot received ${intent?.action}")
        val serviceIntent = Intent(context, BootStarterService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }*/

    companion object {
        private const val TAG = "BootReceiver"
    }
}