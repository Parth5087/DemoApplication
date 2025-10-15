package com.uav.analytics.domain.analytics


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
    val runningTotalNewArrivals: Int
)
