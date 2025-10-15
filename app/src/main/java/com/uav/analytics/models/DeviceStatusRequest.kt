package com.uav.analytics.models

import com.google.gson.annotations.SerializedName

data class DeviceStatusRequest(
    @SerializedName("camera_id")
    val cameraId: String,

    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("status")
    val status: String,
)