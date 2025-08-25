package com.example.demoapplication.domain.analytics


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
