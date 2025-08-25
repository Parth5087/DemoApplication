package com.example.demoapplication.services

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

object UsbCameraManager {
    private const val TAG = "UsbCameraManager"
    private const val ACTION_USB_PERMISSION = "com.example.usb.USB_PERMISSION"

    private lateinit var appContext: Context
    private lateinit var usbManager: UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var permissionPi: PendingIntent? = null
    private var currentDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null

    private var retryCount = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        if (granted) {
                            Log.i(TAG, "Permission granted for ${device.deviceName}")
                            tryOpen(device)
                        } else {
                            Log.w(TAG, "Permission denied for ${device.deviceName}")
                            scheduleRetry()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (isVideoDevice(device)) {
                        Log.i(TAG, "Video USB attached: ${device?.deviceName}")
                        handleDevice(device!!)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == currentDevice) {
                        Log.w(TAG, "Current device detached: ${device.deviceName}")
                        close()
                    }
                }
            }
        }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        permissionPi = PendingIntent.getBroadcast(
            appContext, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        appContext.registerReceiver(receiver, filter)

        // Kick off initial enumeration
        enumerateAndHandle()
    }

    fun stop() {
        try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
        close()
    }

    private fun enumerateAndHandle() {
        val devices = usbManager.deviceList.values.toList()
        val firstVideo = devices.firstOrNull { isVideoDevice(it) }
        if (firstVideo != null) {
            handleDevice(firstVideo)
        } else {
            Log.w(TAG, "No USB video device found; will retry.")
            scheduleRetry()
        }
    }

    private fun handleDevice(device: UsbDevice) {
        // If we already hold it, ensure open
        currentDevice = device
        if (usbManager.hasPermission(device)) {
            tryOpen(device)
        } else {
            requestPermission(device)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val pi = permissionPi ?: return
        Log.i(TAG, "Requesting permission for ${device.deviceName}")
        usbManager.requestPermission(device, pi)
    }

    private fun tryOpen(device: UsbDevice) {
        // Safety: close old
        connection?.close()
        connection = null

        // Attempt to open a new connection
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "openDevice() returned null; scheduling retry.")
            scheduleRetry()
            return
        }

        // Claim the first video interface (class 0x0E)
        val iface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO }

        if (iface == null) {
            Log.e(TAG, "No video interface found; releasing connection and retrying.")
            conn.close()
            scheduleRetry()
            return
        }

        val claimed = conn.claimInterface(iface, true)
        if (!claimed) {
            Log.e(TAG, "Failed to claim interface; will retry.")
            conn.close()
            scheduleRetry()
            return
        }

        // Success: keep references
        connection = conn
        currentDevice = device
        retryCount = 0
        Log.i(TAG, "USB video device ready: ${device.deviceName}")

        // TODO: hand off to your UVC code to start streaming
        // openDevice(conn, iface)  // Implement in your UVC stack
    }

    private fun scheduleRetry() {
        val delayMs = (1000L * (1 shl retryCount).coerceAtMost(32)) // 1s,2s,4s,8s,16s,32s
        retryCount = (retryCount + 1).coerceAtMost(5)
        mainHandler.postDelayed({ enumerateAndHandle() }, delayMs)
    }

    private fun close() {
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        currentDevice = null
    }

    private fun isVideoDevice(device: UsbDevice?): Boolean {
        if (device == null) return false
        // Fast path: any interface with class VIDEO
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }
}