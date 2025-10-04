package com.uav.analytics.domain.analytics


data class TimestampRange(
    val from: String, // "yyyy-MM-dd HH:mm:ss"
    val to: String
)

data class CameraData(
    val totalHeadCount: Int,
    val genderCount: Map<String, Int>,
    val expression: Map<String, Int>,
    val ageGroup: Map<String, Int>,
    val uniqueFaceCount: Int
)

data class CameraEnvelope(
    val cameraId: String,
    val timestamp: TimestampRange,
    val data: CameraData
)

data class HourlyCameraEnvelope(
    val cameraId: String,
    val timestamp: TimestampRange, // same as before: hour range
    val batches: Map<String, CameraEnvelope>, // key is minute window id like "batch_0" or timestamp
    val totals: CameraData,
    val batchCount: Int
)

data class HourlyPayload(
    val startedAt: String,
    val endAt: String,
    val cameras: Map<String, Map<String, Any>> // cameraId -> dynamic camera map (contains totals + batch_* entries)
)