package com.uav.analytics.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.uav.analytics.models.DeviceInfoData
import com.uav.analytics.utils.DeviceUtils

class RequestReceiver : BroadcastReceiver() {
    private val TAG = "RequestReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.uad.launcher.REQUEST_DEVICE_ID",
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Device ID request received from launcher")
                sendDeviceIdToLauncher(context)
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun sendDeviceIdToLauncher(context: Context) {
        val deviceInfo = DeviceUtils
        try {
            val deviceId = deviceInfo.getAndroidId(context)
            val broadcastIntent = Intent("com.uav.analytics.DEVICE_ID_ACTION").apply {
                putExtra("device_id", deviceId)
                putExtra("timestamp", System.currentTimeMillis())
                setPackage("com.uad.launcher") // Target launcher app
            }

            context.sendBroadcast(broadcastIntent)
            Log.d(TAG, "Device ID sent to launcher: $deviceId ...")

        } catch (e: Exception) {
            Log.e(TAG, "Error sending device ID: ${e.message}")
        }
    }
}