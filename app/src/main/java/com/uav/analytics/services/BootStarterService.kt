package com.uav.analytics.services

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.uav.analytics.LauncherActivity
import com.uav.analytics.R
import java.util.concurrent.atomic.AtomicBoolean

class BootStarterService : Service() {

    private val CHANNEL_ID = "app-start-service"
    private val NOTIF_ID = 1001
    private val unlockReceiverRegistered = AtomicBoolean(false)
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // ACTION_USER_UNLOCKED received — now safe to launch Activity
            try {
                val launch = Intent(this@BootStarterService, LauncherActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(launch)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to launch activity after user unlock", ex)
            } finally {
                // unregister receiver
                if (unlockReceiverRegistered.getAndSet(false)) {
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // start foreground with a notification so the service isn't killed
        startForeground(NOTIF_ID, buildNotification())

        // If device already unlocked, we can try to start the app (but still safer to rely on notification)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val isLocked = keyguard?.isDeviceLocked ?: false
        if (!isLocked) {
            // Option A: start Activity now (may still be restricted on some devices)
            try {
                val launch = Intent(this, LauncherActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(launch)
            } catch (ex: Exception) {
                Log.w(TAG, "Could not auto-start Activity at boot (blocked). Showing notification instead.")
            }
            // Stop service if its only job was to open the activity:
            // stopSelf()
        } else {
            // Register ACTION_USER_UNLOCKED — we'll start activity when device is unlocked
            if (!unlockReceiverRegistered.getAndSet(true)) {
                registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    RECEIVER_NOT_EXPORTED
                )
            }
        }

        // Optionally: start your LiveCameraDetectService here (only if CAMERA permission already granted)
        /*try {
            val camIntent = Intent(this, LiveCameraDetectService::class.java)
            ContextCompat.startForegroundService(this, camIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start LiveCameraDetectService at boot: ${e.message}")
        }*/
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep service running
        return START_STICKY
    }

    override fun onDestroy() {
        if (unlockReceiverRegistered.getAndSet(false)) {
            try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "${getString(R.string.app_name)}", NotificationManager.IMPORTANCE_LOW)
        nm?.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        // Notification tap opens the app
        val launchIntent = Intent(this, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("App started on boot — tap to open")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BootStarterService"
    }
}