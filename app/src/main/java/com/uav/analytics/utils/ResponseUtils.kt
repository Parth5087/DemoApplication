package com.uav.analytics.utils

import android.util.Log
import com.google.gson.Gson
import com.uav.analytics.models.ErrorResponse

object ResponseUtils {
    fun parseErrorMessage(errorBody: String?, statusCode: Int): String {
        return try {
            if (errorBody.isNullOrEmpty()) {
                return "Registration failed ($statusCode). Please try again."
            }

            val gson = Gson()
            val errorResponse = gson.fromJson(errorBody, ErrorResponse::class.java)

            // Check for specific camera_id validation error
            if (statusCode == 422 && errorResponse.errors?.camera_id != null) {
                errorResponse.errors.camera_id.joinToString(", ")
            } else {
                errorResponse.message ?: "Registration failed ($statusCode). Please try again."
            }
        } catch (e: Exception) {
            Log.e("ErrorMsg", "Error parsing error response", e)
            "Registration failed ($statusCode). Please try again."
        }
    }
}