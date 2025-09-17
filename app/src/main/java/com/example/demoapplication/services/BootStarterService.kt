package com.example.demoapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.demoapplication.LauncherActivity
import com.example.demoapplication.R
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootStarterService: Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @OptIn(DelicateCoroutinesApi::class)
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()

        startForeground(1, createNotification())

        GlobalScope.launch {
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(this@BootStarterService, LauncherActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                } catch (ex: Exception) {
                    Log.e(TAG, "onCreate: ", ex)
                }
            }
        }

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "${getString(R.string.app_name)} Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(
            NotificationManager::class.java
        )
        manager.createNotificationChannel(serviceChannel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${getString(R.string.app_name)} Service")
            .setSilent(true)
            .setContentText("Please restart this device if this service is not running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }


    companion object {
        private const val TAG = "AppStartService"
        private const val CHANNEL_ID = "app-start-service"
    }

}