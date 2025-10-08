package com.uav.analytics.models

import com.google.gson.annotations.SerializedName

data class ErrorResponse(
    @SerializedName("success") val success: Boolean? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("errors") val errors: ErrorDetails? = null
)

data class ErrorDetails(
    @SerializedName("camera_id") val camera_id: List<String>? = null
)