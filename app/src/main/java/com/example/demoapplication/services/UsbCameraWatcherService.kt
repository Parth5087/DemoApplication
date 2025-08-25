package com.example.demoapplication.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class UsbCameraWatcherService : Service() {
    private val channelId = "usb_watch_channel"

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "USB Watcher", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notif: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle("Watching for USB cameras")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build()
            } else {
                Notification.Builder(this)
                    .setContentTitle("Watching for USB cameras")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build()
            }
        startForeground(101, notif)
        UsbCameraManager.start(this)
    }

    override fun onDestroy() {
        UsbCameraManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}