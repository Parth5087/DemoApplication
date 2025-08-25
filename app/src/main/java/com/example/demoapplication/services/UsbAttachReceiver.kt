package com.example.demoapplication.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.annotation.RequiresApi

class UsbAttachReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Ensure watcher is running to grab permission immediately
            context.startForegroundService(Intent(context, UsbCameraWatcherService::class.java))
        }
    }
}