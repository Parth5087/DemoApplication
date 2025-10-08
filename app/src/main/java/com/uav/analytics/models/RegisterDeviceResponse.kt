package com.uav.analytics.models

import com.google.gson.annotations.SerializedName

data class RegisterDeviceResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String? = null,
)