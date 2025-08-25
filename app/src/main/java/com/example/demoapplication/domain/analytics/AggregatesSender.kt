package com.example.demoapplication.domain.analytics

import android.content.Context
import android.util.Log
import com.example.demoapplication.services.NetworkModule
import com.google.gson.Gson

class AggregatesSender(private val context: Context) {

    private val repo = AnalyticsRepository(context)
    private val gson = Gson()

    suspend fun sendStoredPersonsAggregates(
        cameras: List<String>,
        fromMillis: Long,
        toMillis: Long,
        fallbackToAnyLatest: Boolean = true
    ): Boolean {
        return try {
            val body = repo.buildPayloadForStoredPersons(
                cameras = cameras,
                fromMillis = fromMillis,
                toMillis = toMillis,
                fallbackToAnyLatest = fallbackToAnyLatest
            )
            // 👉 print the body JSON here
            val json = gson.toJson(body)
            Log.d("AggregatesSender", "REQUEST BODY: $json")

            val resp = NetworkModule.api.postAggregates(body)
            Log.d("AggregatesSender", "POST /aggregate (stored) -> ${resp.code()} ${resp.message()}")
            resp.isSuccessful
        } catch (e: Exception) {
            Log.e("AggregatesSender", "Failed to send stored-person aggregates", e)
            false
        }
    }
}