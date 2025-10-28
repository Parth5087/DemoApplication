package com.uav.analytics.domain.analytics

import android.content.Context
import android.util.Log
import com.uav.analytics.services.NetworkModule
import com.google.gson.Gson

class AggregatesSender(private val context: Context) {

    private val repo = AnalyticsRepository(context)
    private val gson = Gson()

    /**
     * Send hourly payload containing per-minute batches + totals.
     *
     * cameras: list of camera ids (e.g. listOf("cam1"))
     * fromMillis/toMillis: hour window (exclusive/inclusive as you prefer)
     */
    suspend fun sendStoredPersonsAggregates(
        cameras: List<String>,
        fromMillis: Long,
        toMillis: Long,
        deviceId: String,
        intervalTime: Long,
    ): Boolean {
        return try {
            val body = repo.buildHourlyPayloadAsWrapper(
                cameras = cameras,
                fromMillis = fromMillis,
                toMillis = toMillis,
                deviceId = deviceId,
                intervalTime = intervalTime,
            )

            val json = gson.toJson(body)
            Log.d("AggregatesSender", "REQUEST BODY: $json")
            Log.d("AggregatesSender", "Using base URL: ${getCurrentBaseUrl()}")
            Log.d("AggregatesSender", "Full API endpoint: ${getCurrentBaseUrl()}store-analytics-data")

            val resp = NetworkModule.api.postAggregates(body)
            Log.d("AggregatesSender", "POST /store-analytics-data -> ${resp.code()} ${resp.message()}")

            if (resp.isSuccessful) {
                // ---------- Choose one of the cleanup strategies below ----------

                // Variant A: delete only records that were part of this window (recommended)
                val removed = repo.removeFaceRecordsInWindow(fromMillis, toMillis)
                Log.d("AggregatesSender", "Removed $removed face records from DB that were included in payload")

                // If you also want to remove person records created during this window:
                // val removedPersons = repo.removePersonRecordsInWindow(fromMillis, toMillis)
                // Log.d("AggregatesSender", "Removed $removedPersons person records from DB in window")

                // -----------------------------------------------------------------
                // Variant B (uncomment if you want aggressive full clear):
                // repo.clearAllStoredData(removePersonsToo = true)
                // Log.d("AggregatesSender", "Cleared all stored face and person records after successful send")
                // -----------------------------------------------------------------

                true
            } else {
                // Read error body for debugging (if available)
                val errorBody = resp.errorBody()?.string()
                Log.e(
                    "AggregatesSender",
                    "Failed to send aggregates. Server responded with ${resp.code()} ${resp.message()}.\nError Body: $errorBody"
                )
                false
            }
        } catch (e: Exception) {
            Log.e("AggregatesSender", "Failed to send stored-person aggregates", e)
            false
        }
    }

    /**
     * Send batch data from ImageVectorUseCase
     */
    suspend fun sendBatchData(intervals: List<IntervalCounts>, deviceId: String, cameraID: String, isRetry: Boolean = false): Boolean {
        return try {
            // Use repository to build the payload
            val batchData = repo.buildBatchDataPayload(intervals, deviceId, cameraID)

            val json = gson.toJson(batchData)
            Log.d("AggregatesSender", "BATCH DATA REQUEST: $json")

            val resp = NetworkModule.api.postAggregatesData(batchData)
            Log.d("AggregatesSender", "POST /store-analytics-data -> ${resp.code()} ${resp.message()}")

            if (resp.isSuccessful) {
                Log.d("AggregatesSender", "✅ ${if (isRetry) "Retry " else ""}Batch data sent successfully for ${intervals.size} intervals")
                true
            } else {
                val errorBody = resp.errorBody()?.string()
                Log.e("AggregatesSender", "❌ ${if (isRetry) "Retry " else ""}Failed to send batch data: ${resp.code()} - $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e("AggregatesSender", "Failed to send batch data", e)
            false
        }
    }

    private fun getCurrentBaseUrl(): String {
        return try {
            // Access the base URL through reflection or add a getter in NetworkModule
            val field = NetworkModule::class.java.getDeclaredField("baseUrl")
            field.isAccessible = true
            field.get(null) as String
        } catch (e: Exception) {
            "Unknown"
        }
    }
}