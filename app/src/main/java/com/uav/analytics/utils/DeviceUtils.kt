package com.uav.analytics.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.uav.analytics.models.DeviceInfoData
import java.util.*
import kotlin.math.ln
import kotlin.math.pow

object DeviceUtils {
    private const val TAG = "DeviceUtils"

    // Memory Information
    private fun getMemoryInfo(context: Context): Triple<Long, Long, Long> {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            val totalRam = memInfo.totalMem
            val availableRam = memInfo.availMem
            val usedRam = totalRam - availableRam
            val threshold = memInfo.threshold

            Triple(totalRam, usedRam, threshold)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting memory info: ${e.message}")
            Triple(0L, 0L, 0L)
        }
    }

    // Storage Information
    private fun getStorageInfo(): Pair<Long, Long> {
        return try {
            val dataDir = Environment.getDataDirectory()
            val sf = StatFs(dataDir.path)
            val blockSize = sf.blockSizeLong
            val totalBlocks = sf.blockCountLong
            val availBlocks = sf.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availBytes = availBlocks * blockSize
            val usedBytes = totalBytes - availBytes

            Pair(totalBytes, usedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage info: ${e.message}")
            Pair(0L, 0L)
        }
    }

    // Identifiers
    @SuppressLint("HardwareIds")
    fun getAndroidId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Android ID: ${e.message}")
            "Unknown"
        }
    }

    /**
     * Get formatted RAM and ROM information for API calls
     */
    fun getFormattedRamRomInfo(context: Context): String {
        return try {
            val (totalRam, usedRam, threshold) = getMemoryInfo(context)
            val (totalRom, usedRom) = getStorageInfo()

            val availableRam = totalRam - usedRam
            val availableRom = totalRom - usedRom
            val versionName = getVersionName(context)

            """
            === DEVICE RAM & ROM INFORMATION ===
            App Version: $versionName
            Total RAM: ${formatBytes(totalRam)}
            Used RAM: ${formatBytes(usedRam)}
            Available RAM: ${formatBytes(availableRam)}
            """.trimIndent()

//            Threshold RAM: ${formatBytes(threshold)}
//            Total ROM: ${formatBytes(totalRom)}
//            Used ROM: ${formatBytes(usedRom)}
//            Available ROM: ${formatBytes(availableRom)}

        } catch (e: Exception) {
            Log.e(TAG, "Error getting formatted RAM/ROM info: ${e.message}")
            "Error collecting RAM/ROM information"
        }
    }

    /**
     * Get app version name
     */
    private fun getVersionName(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting version name: ${e.message}")
            "Unknown"
        }
    }

    // Utility functions
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        return String.format(
            Locale.getDefault(),
            "%.2f %s",
            bytes / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }
}