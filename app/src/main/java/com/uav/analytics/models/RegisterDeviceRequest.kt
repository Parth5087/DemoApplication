package com.uav.analytics.models

import com.google.gson.annotations.SerializedName

data class RegisterDeviceRequest(
    @SerializedName("camera_id")
    val cameraId: String,

    @SerializedName("device_id")
    val deviceId: String,
)