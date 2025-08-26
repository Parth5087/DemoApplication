package com.example.demoapplication.services

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.util.Log

object UsbCameraManager {
    private const val TAG = "UsbCameraManager"
    private const val ACTION_USB_PERMISSION = "com.example.usb.USB_PERMISSION"

    var onReady: ((UsbDevice) -> Unit)? = null
    var onDetached: (() -> Unit)? = null
    var onPermissionDenied: ((UsbDevice) -> Unit)? = null

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    private var permissionPi: PendingIntent? = null
    private var currentDevice: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (granted) {
                            Log.i(TAG, "Permission granted for ${device.deviceName}")
                            currentDevice = device
                            onReady?.invoke(device)
                        } else {
                            Log.w(TAG, "Permission denied for ${device.deviceName}")
                            onPermissionDenied?.invoke(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val d: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (isVideoDevice(d)) {
                        Log.i(TAG, "Video USB attached: ${d?.deviceName}")
                        handleDevice(d!!)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (d != null && d == currentDevice) {
                        Log.w(TAG, "Current device detached: ${d.deviceName}")
                        currentDevice = null
                        onDetached?.invoke()
                    }
                }
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        permissionPi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERMISSION), flags)

        appContext.registerReceiver(receiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })

        enumerateAndHandle()
    }

    fun stop() {
        try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
        currentDevice = null
    }

    private fun enumerateAndHandle() {
        val firstVideo = usbManager.deviceList.values.firstOrNull { isVideoDevice(it) }
        if (firstVideo != null) handleDevice(firstVideo)
        else Log.w(TAG, "No USB video device found.")
    }

    private fun handleDevice(device: UsbDevice) {
        currentDevice = device
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Already have permission for ${device.deviceName}")
            onReady?.invoke(device)
        } else {
            requestPermission(device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val pi = permissionPi ?: return
        Log.i(TAG, "Requesting permission for ${device.deviceName}")
        usbManager.requestPermission(device, pi)
    }

    private fun isVideoDevice(device: UsbDevice?): Boolean {
        if (device == null) return false
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}
