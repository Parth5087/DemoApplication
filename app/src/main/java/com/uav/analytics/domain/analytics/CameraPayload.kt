package com.uav.analytics.domain.analytics

import com.uav.analytics.domain.ImageVectorUseCase


data class HourlyPayload(
    val startedAt: String,
    val endAt: String,
    val deviceId: String,
    val cameras: Map<String, Map<String, Any>> // cameraId -> dynamic camera map (contains totals + batch_* entries)
)

// Assuming other imports like measureTimedValue, FileUtil, etc. are present

data class FaceCounts(val detectedCount: Int, val storedCount: Long)

data class IntervalCounts(
    val timePeriod: String,
    val oldPeople: Int,
    val newPeople: Int,
    val totalPeopleSeen: Int,
    val uniqueNewArrivals: Int,
    val runningTotalNewArrivals: Int,
    val genderCounts: ImageVectorUseCase.GenderCounts,
    val ageGroupCounts: ImageVectorUseCase.AgeGroupCounts,
    val expressionCounts: ImageVectorUseCase.ExpressionCounts,
    val notes: String,
    val batchId: String,
    val batchStartTime: String,
    val batchEndTime: String
)

// API Data Classes
data class BatchData(
    val cameras: Map<String, CameraData>,
    val deviceId: String = "",
    val endAt: String,
    val startedAt: String
)

data class CameraData(
    val batchesProcessed: Int,
    val runningTotalPeople: Int,
    val totalPeopleSeenTotal: Int,
    val genderCountsTotal: Map<String, Int>,
    val ageCategoryCounts: Map<String, Int>,
    val emotionCountsTotal: Map<String, Int>,
    val batches: Map<String, BatchDetail>
)

data class BatchDetail(
    val oldPeople: Int,
    val newPeople: Int,
    val totalPeopleSeen: Int,
    val uniqueNewArrivals: Int,
    val runningTotalPeople: Int,
    val notes: String,
    val genderCounts: Map<String, Int>,
    val emotionCounts: Map<String, Int>,
    val ageCategoryCounts: Map<String, Int>,
    val batchStartTime: String,
    val batchEndTime: String
)

data class FailedPeriod(
    val periodStartTime: Long,
    val periodEndTime: Long,
    val intervals: List<IntervalCounts>,
    val attemptCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
