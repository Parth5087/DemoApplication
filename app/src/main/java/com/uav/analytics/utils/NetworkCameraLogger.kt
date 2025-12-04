package com.uav.analytics.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.uav.analytics.services.NetworkModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

class NetworkCameraLogger(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("connection_logs", Context.MODE_PRIVATE)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val connectionEvents = CopyOnWriteArrayList<ConnectionEvent>()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var periodicUploadJob: Job? = null
    private val uploadScope = CoroutineScope(Dispatchers.IO)

    // Network state tracking
    private var lastKnownNetworkState: Boolean? = null
    private var isInitialized = false

    // Add these new fields for retry mechanism
    private var isWaitingForNetwork = false
    private var pendingLogsRetryJob: Job? = null
    private var networkStateListener: NetworkStateListener? = null

    // Date formatter for current local time
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata") // Force IST timezone
    }

    data class ConnectionEvent(
        val type: String, // "NETWORK_CONNECT", "NETWORK_DISCONNECT", "CAMERA_CONNECT", "CAMERA_DISCONNECT", "API_CALL_FAILED", "DEVICE_STORAGE_INFO"
        val timestamp: String, // Current local date and time
        val systemTime: Long = System.currentTimeMillis(), // System time as reference
        val details: String = "",
        val isOnline: Boolean = false
    )

    data class ConnectionLogPayload(
        val deviceId: String,
        val logs: List<ConnectionEvent>
    )

    /** Initialize network monitoring with initial state check */
    fun initializeNetworkMonitoring() {
        try {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.e("NetworkCameraLogger", "❌ ConnectivityManager is not available")
                return
            }

            // Check initial network state
            val initialNetworkState = getCurrentNetworkStatus()
            lastKnownNetworkState = initialNetworkState

            debugTimeInfo()

            // Log initial state with CORRECT timestamps
            if (!initialNetworkState) {
                logNetworkEvent("NETWORK_DISCONNECT", "Initial state - Network disconnected", false)
            } else {
                logNetworkEvent("NETWORK_CONNECT", "Initial state - Network connected", true)
            }

            isInitialized = true

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (lastKnownNetworkState != true) {
                        logNetworkEvent("NETWORK_CONNECT", "Network connected", true)
                        lastKnownNetworkState = true
                    }
                }

                override fun onLost(network: Network) {
                    if (lastKnownNetworkState != false) {
                        logNetworkEvent("NETWORK_DISCONNECT", "Network disconnected", false)
                        lastKnownNetworkState = false
                    }
                }

                override fun onUnavailable() {
                    if (lastKnownNetworkState != false) {
                        logNetworkEvent("NETWORK_DISCONNECT", "Network unavailable", false)
                        lastKnownNetworkState = false
                    }
                }
            }

            // Register network callback
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)

            // Clear any old invalid logs on initialization
            clearOldLogs()

        } catch (e: Exception) {
            Log.e("NetworkCameraLogger", "❌ Failed to initialize network monitoring: ${e.message}")
        }
    }


    /** Log network connection/disconnection events */
    private fun logNetworkEvent(type: String, details: String, isOnline: Boolean) {
        val event = ConnectionEvent(
            type = type,
            timestamp = getCurrentTimestamp(),
            systemTime = System.currentTimeMillis(),
            details = details,
            isOnline = isOnline
        )

        connectionEvents.add(event)
        saveEventToStorage(event)
//        Log.d("NetworkCameraLogger", "📶 $type at ${event.timestamp} - $details")
    }

    /** Log API call success */
    fun logApiCallSuccess(apiEndpoint: String, details: String = "") {
        val event = ConnectionEvent(
            type = "API_CALL_SUCCESS",
            timestamp = getCurrentTimestamp(),
            systemTime = System.currentTimeMillis(),
            details = "API: $apiEndpoint - Success. $details",
            isOnline = true
        )

        connectionEvents.add(event)
        saveEventToStorage(event)
//        Log.d("NetworkCameraLogger", "✅ API $apiEndpoint success at ${event.timestamp} - $details")
    }

    /** Log API call failure */
    fun logApiCallFailure(apiEndpoint: String, errorCode: Int? = null, errorMessage: String = "", details: String = "") {
        val errorInfo = if (errorCode != null) "Error $errorCode: " else ""
        val event = ConnectionEvent(
            type = "API_CALL_FAILED",
            timestamp = getCurrentTimestamp(),
            systemTime = System.currentTimeMillis(),
            details = "API: $apiEndpoint - $errorInfo$errorMessage. $details",
            isOnline = false
        )

        connectionEvents.add(event)
        saveEventToStorage(event)
//        Log.e("NetworkCameraLogger", "❌ API $apiEndpoint failed at ${event.timestamp} - $errorInfo$errorMessage - $details")
    }

    /** Log camera connection events */
    fun logCameraConnect(cameraId: String, details: String = "") {
        val event = ConnectionEvent(
            type = "CAMERA_CONNECT",
            timestamp = getCurrentTimestamp(),
            systemTime = System.currentTimeMillis(),
            details = "Camera $cameraId active. $details",
            isOnline = true
        )

        connectionEvents.add(event)
        saveEventToStorage(event)
//        Log.d("NetworkCameraLogger", "📷 Camera $cameraId active at ${event.timestamp} - $details")
    }

    /** Log camera disconnection events */
    fun logCameraDisconnect(cameraId: String, details: String = "") {
        val event = ConnectionEvent(
            type = "CAMERA_DISCONNECT",
            timestamp = getCurrentTimestamp(),
            systemTime = System.currentTimeMillis(),
            details = "Camera $cameraId inactive. $details",
            isOnline = false
        )

        connectionEvents.add(event)
        saveEventToStorage(event)
//        Log.d("NetworkCameraLogger", "📷 Camera $cameraId inactive at ${event.timestamp} - $details")
    }

    /** Log device storage information with RAM and ROM details */
    private fun logDeviceStorageInfo() {
        val ramRomInfo = getCurrentRamRomInfo()
        val event = ConnectionEvent(
            type = "DEVICE_STORAGE_INFO",
            timestamp = getCurrentTimestamp(),
            systemTime = System.currentTimeMillis(),
            details = ramRomInfo,
            isOnline = true
        )

        connectionEvents.add(event)
        saveEventToStorage(event)
        Log.d("NetworkCameraLogger", "💾 Device Storage Info at ${event.timestamp}")
    }

    /** Get current RAM ROM information as formatted string */
    private fun getCurrentRamRomInfo(): String {
        return try {
            DeviceUtils.getFormattedRamRomInfo(context)
        } catch (e: Exception) {
            Log.e("NetworkCameraLogger", "❌ Error getting RAM ROM info: ${e.message}")
            "RAM ROM Info Unavailable"
        }
    }


    /** Get current local timestamp */
    private fun getCurrentTimestamp(): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("Asia/Kolkata")

        return formatter.format(calendar.time)
    }

    private fun debugTimeInfo() {
        val currentTime = System.currentTimeMillis()

        Log.d("TimeDebug", "🔍 === TIME DEBUG INFO ===")
        Log.d("TimeDebug", "📱 System Time: $currentTime")
        Log.d("TimeDebug", "🌍 Default Timezone: ${TimeZone.getDefault().id}")
        Log.d("TimeDebug", "⏰ Current Timestamp: ${getCurrentTimestamp()}")
        Log.d("TimeDebug", "📅 Calendar Time: ${Calendar.getInstance().time}")
        Log.d("TimeDebug", "🔍 === END TIME DEBUG ===")
    }

    /** Save event to persistent storage */
    private fun saveEventToStorage(event: ConnectionEvent) {
        coroutineScope.launch {
            try {
                val existingJson = prefs.getString("connection_events", "[]")
                val existingEvents = gson.fromJson(existingJson, Array<ConnectionEvent>::class.java).toMutableList()

                existingEvents.add(event)

                // Keep only last 1000 events to prevent storage overflow
                if (existingEvents.size > 1000) {
                    existingEvents.subList(0, existingEvents.size - 1000).clear()
                }

                val updatedJson = gson.toJson(existingEvents)
                prefs.edit().putString("connection_events", updatedJson).apply()

                Log.d("NetworkCameraLogger", "💾 Saved connection event: ${event.type} at ${event.timestamp}")

            } catch (e: Exception) {
                Log.e("NetworkCameraLogger", "❌ Error saving connection event: ${e.message}")
            }
        }
    }

    /** Load events from persistent storage */
    private fun loadEventsFromStorage(): List<ConnectionEvent> {
        return try {
            val json = prefs.getString("connection_events", "[]")
            gson.fromJson(json, Array<ConnectionEvent>::class.java).toList()
        } catch (e: Exception) {
            Log.e("NetworkCameraLogger", "❌ Error loading connection events: ${e.message}")
            emptyList()
        }
    }

    /** Send all connection logs to API */
    fun sendConnectionLogsToAPI() {
        coroutineScope.launch {
            try {
                // Add current device storage info before sending
                logDeviceStorageInfo()

                val storedEvents = loadEventsFromStorage()
                val currentEvents = connectionEvents.toList()

                val allEvents = (storedEvents + currentEvents).distinctBy { "${it.type}_${it.timestamp}_${it.systemTime}" }

                if (allEvents.isEmpty()) {
                    Log.d("NetworkCameraLogger", "📭 No connection events to send")
                    return@launch
                }

                // Sort events by system time (most reliable)
                val sortedEvents = allEvents.sortedBy { it.systemTime }

                val payload = ConnectionLogPayload(
                    deviceId = DeviceUtils.getAndroidId(context),
                    logs = sortedEvents
                )

                val success = sendLogsToAPI(payload)


                if (success) {
                    // Clear sent events from storage
                    prefs.edit().remove("connection_events").apply()
                    connectionEvents.clear()
                    clearAllLogs()
                    Log.d("NetworkCameraLogger", "✅ Connection logs sent successfully (${sortedEvents.size} events)")
                    // Reset retry flag if successful
                    isWaitingForNetwork = false
                    pendingLogsRetryJob?.cancel()
                } else {
                    Log.e("NetworkCameraLogger", "❌ Failed to send connection logs")
                    // Schedule retry when network becomes available
                    scheduleRetryWhenNetworkAvailable()
                }

            } catch (e: Exception) {
                Log.e("NetworkCameraLogger", "❌ Error sending connection logs: ${e.message}")
                // Schedule retry when network becomes available
                scheduleRetryWhenNetworkAvailable()
            }
        }
    }

    /** Send logs to API with enhanced logging */
    private suspend fun sendLogsToAPI(payload: ConnectionLogPayload): Boolean {
        val startTime = System.currentTimeMillis()

        return try {
            // Check network before making API call
            if (!isNetworkConnected()) {
                Log.w("NetworkCameraLogger", "🌐 No network connection - cannot send logs")
                return false
            }

            val json = gson.toJson(payload)

            // Enhanced API request logging
            Log.d("API_DEBUG", "┌───────────── API REQUEST ─────────────")
            Log.d("API_DEBUG", "│ 📍 Endpoint: POST /store-logs")
            Log.d("API_DEBUG", "│ 🆔 Device ID: ${payload.deviceId}")
            Log.d("API_DEBUG", "│ 📊 Events Count: ${payload.logs.size}")
            Log.d("API_DEBUG", "│ 📦 Payload Preview: ${json.take(200)}...")
            Log.d("API_DEBUG", "└──────────────────────────────────────")

            // Find and log device storage info
            val storageEvent = payload.logs.find { it.type == "DEVICE_STORAGE_INFO" }
            storageEvent?.let {
                Log.d("API_DEBUG", "│ 💾 Device Storage Info: ${it.details}...")
            }


            val response = NetworkModule.api.storeLogs(payload)
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            // Enhanced API response logging
            Log.d("API_DEBUG", "┌───────────── API RESPONSE ────────────")
            Log.d("API_DEBUG", "│ ⏱️  Duration: ${duration}ms")
            Log.d("API_DEBUG", "│ 🔢 Status Code: ${response.code()}")
            Log.d("API_DEBUG", "│ ✅ Successful: ${response.isSuccessful}")

            if (response.isSuccessful) {
                Log.d("API_DEBUG", "│ 🎉 Request completed successfully")
            } else {
                Log.d("API_DEBUG", "│ ❌ Request failed")
                val errorBody = response.errorBody()?.string()
                Log.d("API_DEBUG", "│ 📋 Error Response: $errorBody")
            }
            Log.d("API_DEBUG", "└──────────────────────────────────────")

            response.isSuccessful

        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime

            Log.e("API_DEBUG", "┌───────────── API ERROR ───────────────")
            Log.e("API_DEBUG", "│ ⏱️  Duration: ${duration}ms")
            Log.e("API_DEBUG", "│ 💥 Exception: ${e.javaClass.simpleName}")
            Log.e("API_DEBUG", "│ 📝 Message: ${e.message}")
            Log.e("API_DEBUG", "└──────────────────────────────────────")
            false
        }
    }

    /** Schedule retry when network becomes available */
    private fun scheduleRetryWhenNetworkAvailable() {
        if (isWaitingForNetwork) {
            return // Already waiting for network
        }

        Log.d("NetworkCameraLogger", "🔄 Scheduling retry when network becomes available")
        isWaitingForNetwork = true

        // Cancel any existing retry job
        pendingLogsRetryJob?.cancel()

        // ONLY setup network listener - NO retry loop
        setupNetworkRetryListener()

        Log.d("NetworkCameraLogger", "📡 Waiting for network connectivity to retry failed API call")
    }

    /** Set up network state listener for automatic retry */
    private fun setupNetworkRetryListener() {
        if (networkStateListener != null) {
            return // Listener already set up
        }

        networkStateListener = object : NetworkStateListener {
            override fun onNetworkAvailable() {
                if (isWaitingForNetwork) {
                    Log.d("NetworkCameraLogger", "🌐 Network available - retrying failed API call...")

                    coroutineScope.launch {
                        try {
                            // Small delay to ensure network is stable
                            delay(2000)

                            if (isWaitingForNetwork && isNetworkConnected()) {
                                Log.d("NetworkCameraLogger", "🔄 Retrying previously failed API call")
                                sendConnectionLogsToAPI()
                            }
                        } catch (e: Exception) {
                            Log.e("NetworkCameraLogger", "❌ Error in network retry: ${e.message}")
                        }
                    }
                }
            }

            override fun onNetworkLost() {
                // Nothing to do when network is lost
            }
        }

        // Register the network state listener
        registerNetworkStateListener()
        Log.d("NetworkCameraLogger", "📡 Network retry listener activated")
    }

    /** Register network state listener for retry mechanism */
    private fun registerNetworkStateListener() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.e("NetworkCameraLogger", "❌ ConnectivityManager not available for retry listener")
                return
            }

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val retryNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    networkStateListener?.onNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    networkStateListener?.onNetworkLost()
                }
            }

            connectivityManager.registerNetworkCallback(networkRequest, retryNetworkCallback)
            Log.d("NetworkCameraLogger", "📡 Registered network retry listener")

        } catch (e: Exception) {
            Log.e("NetworkCameraLogger", "❌ Error registering network retry listener: ${e.message}")
        }
    }


    /** Get current network status */
    private fun getCurrentNetworkStatus(): Boolean {
        return connectivityManager?.let { cm ->
            try {
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } catch (e: Exception) {
                Log.e("NetworkCameraLogger", "❌ Error checking network status: ${e.message}")
                false
            }
        } ?: false
    }

    /** Clear all connection logs */
    fun clearAllLogs() {
        connectionEvents.clear()
        prefs.edit().remove("connection_events").apply()
        lastKnownNetworkState = null
        Log.d("NetworkCameraLogger", "🧹 All connection logs cleared")
    }

    /** Clear logs older than 30 days or with invalid timestamps */
    private fun clearOldLogs() {
        coroutineScope.launch {
            try {
                val storedEvents = loadEventsFromStorage()
                val currentTime = System.currentTimeMillis()
                val thirtyDaysAgo = currentTime - (30L * 24 * 60 * 60 * 1000) // 30 days ago

                val validEvents = storedEvents.filter { event ->
                    // Keep events from last 30 days and valid timestamps
                    event.systemTime > thirtyDaysAgo && event.systemTime <= currentTime + (24 * 60 * 60 * 1000) // Allow 1 day future
                }

                if (validEvents.size < storedEvents.size) {
                    val clearedCount = storedEvents.size - validEvents.size
                    val updatedJson = gson.toJson(validEvents)
                    prefs.edit().putString("connection_events", updatedJson).apply()
                    Log.d("NetworkCameraLogger", "🧹 Cleared $clearedCount old/invalid logs")
                }

            } catch (e: Exception) {
                Log.e("NetworkCameraLogger", "❌ Error clearing old logs: ${e.message}")
            }
        }
    }

    /** Clean up resources */
    fun cleanup() {
        stopPeriodicUpload()
        // Clean up retry mechanism
        isWaitingForNetwork = false
        pendingLogsRetryJob?.cancel()
        pendingLogsRetryJob = null
        networkStateListener = null

        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
                Log.d("NetworkCameraLogger", "📶 Network monitoring stopped")
            } catch (e: Exception) {
                Log.e("NetworkCameraLogger", "❌ Error unregistering network callback: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        isInitialized = false
        Log.d("NetworkCameraLogger", "🧹 NetworkCameraLogger cleaned up")
    }

    /** Start periodic upload every hour at round hours (1:00, 2:00, 3:00, etc.) */
    fun startPeriodicUpload() {
        // Only start if not already  active
        if (periodicUploadJob?.isActive == true) return

        Log.d("NetworkCameraLogger", "🔄 Periodic upload started")

        periodicUploadJob = uploadScope.launch {
            while (isActive) {
                try {
                    val delayMillis = calculateDelayToNextHour()
                    // For testing: 2-minute delay (120,000 milliseconds)
//                    val delayTesting = 120000L
                    delay(delayMillis)
                    // Send logs to API
                    sendConnectionLogsToAPI()

                } catch (e: Exception) {
                    Log.e("NetworkCameraLogger", "❌ Error in periodic upload: ${e.message}")
                    delay(60000) // Wait 1 minute before retrying if there's an error
                }
            }
        }
    }

    private fun calculateDelayToNextHour(): Long {
        val calendar = Calendar.getInstance()
        val currentTime = calendar.timeInMillis

        // Set to next hour (e.g., if current time is 1:30, next execution will be at 2:00)
        calendar.add(Calendar.HOUR_OF_DAY, 1)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val nextHourTime = calendar.timeInMillis
        return nextHourTime - currentTime
    }

    /** Stop periodic upload */
    fun stopPeriodicUpload() {
        periodicUploadJob?.cancel()
        periodicUploadJob = null
        Log.d("NetworkCameraLogger", "🛑 Periodic upload stopped")
    }

    /** Check if network is currently connected */
    fun isNetworkConnected(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.e("NetworkCameraLogger", "❌ ConnectivityManager is not available")
                return false
            }

            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    )
        } catch (e: Exception) {
            Log.e("NetworkCameraLogger", "❌ Error checking network connection: ${e.message}")
            false
        }
    }

    /** Interface for network state changes */
    private interface NetworkStateListener {
        fun onNetworkAvailable()
        fun onNetworkLost()
    }
}