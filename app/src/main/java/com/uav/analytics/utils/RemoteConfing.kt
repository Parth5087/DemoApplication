package com.uav.analytics

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings

object RemoteConfigHelper {

    // ---- Keys (single source of truth) ----
    private const val CAPTURE_INTERVAL_SEC = "capture_interval_sec"
    private const val UPLOAD_INTERVAL_SEC_PHOTOS  = "upload_interval_sec_photos"
    private const val UPLOAD_INTERVAL_SEC_DATA  = "upload_interval_sec_data"
    private const val WEBP_QUALITY         = "jpeg_to_webp_quality"
    private const val WEBP_MAX_DIM         = "jpeg_to_webp_max_dim"
    private const val AUTO_UPLOAD_ENABLED  = "auto_upload_enabled"
    private const val START_DESTINATION    = "start_destination"
    private const val DELETE_RECORD_TIME     = "delete_record_time"
    private const val INTERVAL_TIME     = "interval_time"
    private const val APP_VERSION       = "latest_app_version"

    private val rc get() = Firebase.remoteConfig
    private var initialized = false
    private lateinit var appContext: Context

    /** Call once from Application.onCreate() */
    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app.applicationContext

        val isDebug = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val settings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (isDebug) 0 else 12 * 60 * 60
        }
        rc.setConfigSettingsAsync(settings)

        // Programmatic defaults so we never see STATIC again
        val defaults = mapOf(
            CAPTURE_INTERVAL_SEC to 1L,
            UPLOAD_INTERVAL_SEC_PHOTOS  to 60L,
            UPLOAD_INTERVAL_SEC_DATA to 3600L,
            WEBP_QUALITY         to 80L,
            WEBP_MAX_DIM         to 1200L,
            AUTO_UPLOAD_ENABLED  to true,
            START_DESTINATION    to "live_camera_detect",
            DELETE_RECORD_TIME   to 180L,
            INTERVAL_TIME        to 300L,
            APP_VERSION          to BuildConfig.VERSION_NAME
        )
        rc.setDefaultsAsync(defaults)

        // Initialize cache with defaults
        saveAllConfigToCache(defaults)
    }

    /** Fetch+activate; calls back with success=true/false */
    fun fetchAndActivate(onDone: (Boolean) -> Unit = {}) {
        rc.fetchAndActivate().addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w("RC-SRC", "fetchAndActivate failed: ${task.exception?.message}")
            }
            val st = when (rc.info.lastFetchStatus) {
                0 -> "SUCCESS"
                1 -> "FAILURE"
                2 -> "THROTTLED"
                -1 -> "NO_FETCH_YET"
                else -> rc.info.lastFetchStatus.toString()
            }
            Log.d("RC-SRC", "fetchAndActivate done=${task.isSuccessful} lastFetchStatus=$st")

            // Save to cache if successful
            if (task.isSuccessful) {
                saveAllConfigToCache(getAllConfigValues())
            }

            logAll("AFTER FETCH")
            onDone(task.isSuccessful)
        }
    }

    // ---------- Typed getters (with small fallbacks) ----------
    fun captureIntervalMs(): Long {
        val sec = rc.getLong(CAPTURE_INTERVAL_SEC)
        return (if (sec <= 0) 1 else sec) * 1000L
    }

    fun uploadIntervalPhotosMs(): Long {
        val sec = rc.getLong(UPLOAD_INTERVAL_SEC_PHOTOS)
        return (if (sec <= 0) 60 else sec) * 1000L
    }

    fun uploadIntervalDataMs(): Long {
        val sec = rc.getLong(UPLOAD_INTERVAL_SEC_DATA)
        return (if (sec <= 0) 3600 else sec) * 1000L
    }

    fun webpQuality(): Int = rc.getLong(WEBP_QUALITY).toInt()
    fun webpMaxDim(): Int  = rc.getLong(WEBP_MAX_DIM).toInt()
    fun getExpirationHours(): Int = rc.getLong(DELETE_RECORD_TIME).toInt()
    fun getIntervalTime(): Long = rc.getLong(INTERVAL_TIME)
    fun autoUploadEnabled(): Boolean = rc.getBoolean(AUTO_UPLOAD_ENABLED)
    fun startDestination(): String = rc.getString(START_DESTINATION)
    fun getAppVersion(): String = rc.getString(APP_VERSION)

    // ---------- Cache Methods ----------
    fun getAllConfigValues(): Map<String, Any> {
        return mapOf(
            CAPTURE_INTERVAL_SEC to rc.getLong(CAPTURE_INTERVAL_SEC),
            UPLOAD_INTERVAL_SEC_PHOTOS to rc.getLong(UPLOAD_INTERVAL_SEC_PHOTOS),
            UPLOAD_INTERVAL_SEC_DATA to rc.getLong(UPLOAD_INTERVAL_SEC_DATA),
            WEBP_QUALITY to rc.getLong(WEBP_QUALITY),
            WEBP_MAX_DIM to rc.getLong(WEBP_MAX_DIM),
            AUTO_UPLOAD_ENABLED to rc.getBoolean(AUTO_UPLOAD_ENABLED),
            START_DESTINATION to rc.getString(START_DESTINATION),
            DELETE_RECORD_TIME to rc.getLong(DELETE_RECORD_TIME),
            INTERVAL_TIME to rc.getLong(INTERVAL_TIME),
            APP_VERSION to rc.getString(APP_VERSION)
        )
    }

    fun saveAllConfigToCache(configValues: Map<String, Any>) {
        val prefs = getSharedPreferences()
        val editor = prefs.edit()

        configValues.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putLong(key, value.toLong())
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.putLong("cache_timestamp", System.currentTimeMillis())
        editor.apply()
        Log.d("RC-SRC", "💾 Saved all RemoteConfig values to cache")
    }

    fun getCachedConfigValue(key: String, defaultValue: Any): Any {
        val prefs = getSharedPreferences()

        // Check if cache is expired (24 hours)
        val cacheTimestamp = prefs.getLong("cache_timestamp", 0L)
        if (System.currentTimeMillis() - cacheTimestamp > 24 * 60 * 60 * 1000) {
            Log.d("RC-SRC", "🧹 Cache expired, clearing")
            clearConfigCache()
            return getFromRemoteConfig(key, defaultValue)
        }

        val cachedValue = when (defaultValue) {
            is String -> prefs.getString(key, null)
            is Long -> if (prefs.contains(key)) prefs.getLong(key, 0L) else null
            is Boolean -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null
            else -> null
        }

        // If cached value is null or not found, get from RemoteConfig
        return if (cachedValue != null) {
            cachedValue
        } else {
            Log.d("RC-SRC", "📦 Cache missing for $key, fetching from RemoteConfig")
            getFromRemoteConfig(key, defaultValue)
        }
    }

    private fun getFromRemoteConfig(key: String, defaultValue: Any): Any {
        return try {
            when (key) {
                CAPTURE_INTERVAL_SEC -> rc.getLong(CAPTURE_INTERVAL_SEC)
                UPLOAD_INTERVAL_SEC_PHOTOS -> rc.getLong(UPLOAD_INTERVAL_SEC_PHOTOS)
                UPLOAD_INTERVAL_SEC_DATA -> rc.getLong(UPLOAD_INTERVAL_SEC_DATA)
                WEBP_QUALITY -> rc.getLong(WEBP_QUALITY)
                WEBP_MAX_DIM -> rc.getLong(WEBP_MAX_DIM)
                AUTO_UPLOAD_ENABLED -> rc.getBoolean(AUTO_UPLOAD_ENABLED)
                START_DESTINATION -> rc.getString(START_DESTINATION)
                DELETE_RECORD_TIME -> rc.getLong(DELETE_RECORD_TIME)
                INTERVAL_TIME -> rc.getLong(INTERVAL_TIME)
                APP_VERSION -> rc.getString(APP_VERSION)
                else -> defaultValue
            }
        } catch (e: Exception) {
            Log.e("RC-SRC", "❌ Error getting $key from RemoteConfig: ${e.message}")
            defaultValue
        }
    }

    fun clearConfigCache() {
        val prefs = getSharedPreferences()
        prefs.edit().clear().apply()
        Log.d("RC-SRC", "🧹 Cleared RemoteConfig cache")
    }

    // ---------- Cached Value Getters ----------
    fun getCachedCaptureInterval(): Long {
        return getCachedConfigValue(CAPTURE_INTERVAL_SEC, 1L) as Long
    }

    fun getCachedUploadIntervalPhotos(): Long {
        return getCachedConfigValue(UPLOAD_INTERVAL_SEC_PHOTOS, 60L) as Long
    }

    fun getCachedUploadIntervalData(): Long {
        return getCachedConfigValue(UPLOAD_INTERVAL_SEC_DATA, 3600L) as Long
    }

    fun getCachedWebpQuality(): Int {
        return (getCachedConfigValue(WEBP_QUALITY, 80L) as Long).toInt()
    }

    fun getCachedWebpMaxDim(): Int {
        return (getCachedConfigValue(WEBP_MAX_DIM, 1200L) as Long).toInt()
    }

    fun getCachedAutoUploadEnabled(): Boolean {
        return getCachedConfigValue(AUTO_UPLOAD_ENABLED, true) as Boolean
    }

    fun getCachedStartDestination(): String {
        return getCachedConfigValue(START_DESTINATION, "live_camera_detect") as String
    }

    fun getCachedDeleteRecordTime(): Int {
        return (getCachedConfigValue(DELETE_RECORD_TIME, 180L) as Long).toInt()
    }

    fun getCachedIntervalTime(): Long {
        return getCachedConfigValue(INTERVAL_TIME, 300L) as Long
    }

    fun getCachedAppVersion(): String {
        return getCachedConfigValue(APP_VERSION, BuildConfig.VERSION_NAME) as String
    }

    // ---------- Helper Methods ----------
    private fun getSharedPreferences(): android.content.SharedPreferences {
        if (!initialized) {
            throw IllegalStateException("RemoteConfigHelper not initialized. Call init() first.")
        }
        return appContext.getSharedPreferences("remote_config_cache", Context.MODE_PRIVATE)
    }

    // ---------- Debug logger ----------
    private fun srcName(key: String): String {
        return when (rc.getValue(key).source) {
            0 -> "STATIC"   // no default set
            1 -> "DEFAULT"  // from setDefaultsAsync
            2 -> "REMOTE"   // from Firebase console
            else -> "?"
        }
    }

    private fun logAll(prefix: String = "RC") {
        Log.d("RC-SRC", "[$prefix] $CAPTURE_INTERVAL_SEC=${rc.getLong(CAPTURE_INTERVAL_SEC)} src=${srcName(CAPTURE_INTERVAL_SEC)}")
        Log.d("RC-SRC", "[$prefix] $UPLOAD_INTERVAL_SEC_PHOTOS=${rc.getLong(UPLOAD_INTERVAL_SEC_PHOTOS)} src=${srcName(UPLOAD_INTERVAL_SEC_PHOTOS)}")
        Log.d("RC-SRC", "[$prefix] $UPLOAD_INTERVAL_SEC_DATA=${rc.getLong(UPLOAD_INTERVAL_SEC_DATA)} src=${srcName(UPLOAD_INTERVAL_SEC_DATA)}")
        Log.d("RC-SRC", "[$prefix] $WEBP_QUALITY=${rc.getLong(WEBP_QUALITY)} src=${srcName(WEBP_QUALITY)}")
        Log.d("RC-SRC", "[$prefix] $WEBP_MAX_DIM=${rc.getLong(WEBP_MAX_DIM)} src=${srcName(WEBP_MAX_DIM)}")
        Log.d("RC-SRC", "[$prefix] $DELETE_RECORD_TIME=${rc.getLong(DELETE_RECORD_TIME)} src=${srcName(DELETE_RECORD_TIME)}")
        Log.d("RC-SRC", "[$prefix] $INTERVAL_TIME=${rc.getLong(INTERVAL_TIME)} src=${srcName(INTERVAL_TIME)}")
        Log.d("RC-SRC", "[$prefix] $AUTO_UPLOAD_ENABLED=${rc.getBoolean(AUTO_UPLOAD_ENABLED)} src=${srcName(AUTO_UPLOAD_ENABLED)}")
        Log.d("RC-SRC", "[$prefix] $START_DESTINATION=\"${rc.getString(START_DESTINATION)}\" src=${srcName(START_DESTINATION)}")
        Log.d("RC-SRC", "[$prefix] $APP_VERSION=\"${rc.getString(APP_VERSION)}\" src=${srcName(APP_VERSION)}")

        // Also log cached values for comparison
        Log.d("RC-SRC", "[CACHED] Upload Data: ${getCachedUploadIntervalData()}s")
        Log.d("RC-SRC", "[CACHED] Upload Data Interval: ${getCachedIntervalTime()}s")
        Log.d("RC-SRC", "[CACHED] Destination: ${getCachedStartDestination()}")
    }
}
