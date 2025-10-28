package com.uav.analytics.domain.analytics

import android.content.Context
import com.uav.analytics.data.FaceImageRecord
import com.uav.analytics.data.PersonRecord
import com.uav.analytics.data.FaceImageRecord_
import com.uav.analytics.data.PersonRecord_
import com.uav.analytics.data.ObjectBoxStore
import com.uav.analytics.domain.ImageVectorUseCase
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

class AnalyticsRepository(private val context: Context) {

    private val faceBox = ObjectBoxStore.store.boxFor<FaceImageRecord>()
    private val personBox = ObjectBoxStore.store.boxFor<PersonRecord>()

    // Format used in earlier code: yyyy-MM-dd HH:mm:ss (Indian timezone)
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    // Use SimpleDateFormat for other formats, also set to India timezone
    private val isoInstantFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    private val isoNoZone = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    private val keyFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    private val fullTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    private val fullTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    fun buildHourlyPayloadAsWrapper(
        cameras: List<String>,
        fromMillis: Long,
        toMillis: Long,
        deviceId: String,
        intervalTime: Long = 180L, // Changed to 3 minutes (180 seconds) default
    ): HourlyPayload {

        val intervalMillis = (if (intervalTime <= 0) 180L else intervalTime) * 1000L

        val camerasMap = mutableMapOf<String, Map<String, Any>>()

        cameras.forEach { cameraId ->
            // Query all records for this camera in the hour
            val allRecsInHour: List<FaceImageRecord> = faceBox.query {
                between(FaceImageRecord_.createdAt, fromMillis, toMillis)
            }.find()

            val latestRecTimestamp = allRecsInHour.maxOfOrNull { it.createdAt } ?: toMillis

            // Partition records by 3-minute buckets
            val numBuckets = ((toMillis - fromMillis + intervalMillis - 1) / intervalMillis).toInt().coerceAtLeast(1)
            val buckets: Array<MutableList<FaceImageRecord>> = Array(numBuckets) { mutableListOf() }

            allRecsInHour.forEach { rec ->
                val idx = ((rec.createdAt - fromMillis) / intervalMillis).toInt().coerceIn(0, numBuckets - 1)
                buckets[idx].add(rec)
            }

            // Prepare camera-level totals (hour)
            var batchesProcessed = 0
            val imagesProcessed = 0
            val headClearIds = 0
            val headClearTotal = 0
            val genderCountsTotal = mutableMapOf("Male" to 0, "Female" to 0, "Unknown" to 0)
            val ageCategoryCounts = mutableMapOf(
                "Child (0-14)" to 0,
                "Young (15-25)" to 0,
                "Adult (26-55)" to 0,
                "Elderly (56+)" to 0
            )
            val emotionCountsTotal = mutableMapOf(
                "neutral" to 0, "happy" to 0, "surprised" to 0, "sad" to 0,
                "angry" to 0, "disgust" to 0, "fear" to 0, "contempt" to 0
            )
            val looking3plusTotal = 0
            val uniquePersonIdsAcrossHour = mutableSetOf<Long>()

            val cameraEntries = mutableMapOf<String, Any>()

            for (idx in 0 until numBuckets) {
                val bucketRecs = buckets[idx]
                val windowStart = fromMillis + idx * intervalMillis
                val windowEnd = min(windowStart + intervalMillis, toMillis)

                // Calculate unique persons in this 3-minute batch
                val uniquePersonsInBatch = bucketRecs.map { it.personID }.toSet().size

                // Compute counts for this 3-minute batch
                val headCount = uniquePersonsInBatch // Use unique persons as head count
                val faceCount = bucketRecs.size
                val looking3plus = 0

                // Gender counts
                val gMale = bucketRecs.count { it.gender?.trim() == "Male" }
                val gFemale = bucketRecs.count { it.gender?.trim() == "Female" }
                val gUnknown = bucketRecs.count { it.gender == null || it.gender!!.trim().isEmpty() || it.gender!!.trim() !in setOf("Male", "Female") }

                // Emotions
                val eNeutral = bucketRecs.count { it.expression?.lowercase()?.trim() == "neutral" }
                val eHappy = bucketRecs.count { it.expression?.lowercase()?.trim() == "happy" }
                val eSurprised = bucketRecs.count { listOf("surprised","surprise").contains(it.expression?.lowercase()?.trim()) }
                val eSad = bucketRecs.count { it.expression?.lowercase()?.trim() == "sad" }
                val eAngry = bucketRecs.count { listOf("anger","angry").contains(it.expression?.lowercase()?.trim()) }
                val eDisgust = bucketRecs.count { it.expression?.lowercase()?.trim() == "disgust" }
                val eFear = bucketRecs.count { it.expression?.lowercase()?.trim() == "fear" }
                val eContempt = bucketRecs.count { it.expression?.lowercase()?.trim() == "contempt" }

                // Age categories
                val aChild = bucketRecs.count { it.ageGroup?.trim() == "Child (0-14)" }
                val aYoung = bucketRecs.count { it.ageGroup?.trim() == "Young (15-25)" }
                val aAdult = bucketRecs.count { it.ageGroup?.trim() == "Adult (26-55)" }
                val aElderly = bucketRecs.count { it.ageGroup?.trim() == "Elderly (56+)" }

                val images = bucketRecs.size

                // Build 3-minute batch entry
                val batchKeyShort = keyFmt.format(Date(windowStart))
                val batchKeyTimestamp = ts.format(Date(windowStart))
                val batchKey = "batch_${batchKeyShort}_${batchKeyTimestamp}"

                val batchMap = mapOf(
                    "head_count" to headCount,
                    "face_count" to faceCount,
                    "looking_3plus" to looking3plus,
                    "unique_persons_in_batch" to uniquePersonsInBatch, // Added unique persons per batch
                    "gender_counts" to mapOf("Male" to gMale, "Female" to gFemale, "Unknown" to gUnknown),
                    "emotion_counts" to mapOf(
                        "neutral" to eNeutral,
                        "happy" to eHappy,
                        "surprised" to eSurprised,
                        "sad" to eSad,
                        "angry" to eAngry,
                        "disgust" to eDisgust,
                        "fear" to eFear,
                        "contempt" to eContempt
                    ),
                    "age_category_counts" to mapOf(
                        "Child (0-14)" to aChild,
                        "Young (15-25)" to aYoung,
                        "Adult (26-55)" to aAdult,
                        "Elderly (56+)" to aElderly
                    ),
                    "images" to images,
                    "time" to isoNoZone.format(Date(windowStart)),
                    // ADDED: Start and end time for the batch window
                    "batch_start_time" to isoNoZone.format(Date(windowStart)),
                    "batch_end_time" to isoNoZone.format(Date(windowEnd)),
                )

                cameraEntries[batchKey] = batchMap

                // Aggregate hourly totals
                batchesProcessed += 1
//                imagesProcessed += images
//                headClearTotal += headCount

                genderCountsTotal["Male"] = genderCountsTotal["Male"]!! + gMale
                genderCountsTotal["Female"] = genderCountsTotal["Female"]!! + gFemale
                genderCountsTotal["Unknown"] = genderCountsTotal["Unknown"]!! + gUnknown

                emotionCountsTotal["neutral"] = emotionCountsTotal["neutral"]!! + eNeutral
                emotionCountsTotal["happy"] = emotionCountsTotal["happy"]!! + eHappy
                emotionCountsTotal["surprised"] = emotionCountsTotal["surprised"]!! + eSurprised
                emotionCountsTotal["sad"] = emotionCountsTotal["sad"]!! + eSad
                emotionCountsTotal["angry"] = emotionCountsTotal["angry"]!! + eAngry
                emotionCountsTotal["disgust"] = emotionCountsTotal["disgust"]!! + eDisgust
                emotionCountsTotal["fear"] = emotionCountsTotal["fear"]!! + eFear
                emotionCountsTotal["contempt"] = emotionCountsTotal["contempt"]!! + eContempt

                ageCategoryCounts["Child (0-14)"] = ageCategoryCounts["Child (0-14)"]!! + aChild
                ageCategoryCounts["Young (15-25)"] = ageCategoryCounts["Young (15-25)"]!! + aYoung
                ageCategoryCounts["Adult (26-55)"] = ageCategoryCounts["Adult (26-55)"]!! + aAdult
                ageCategoryCounts["Elderly (56+)"] = ageCategoryCounts["Elderly (56+)"]!! + aElderly

                // Collect unique personIDs across entire hour
                bucketRecs.map { it.personID }.forEach { pid -> uniquePersonIdsAcrossHour.add(pid) }
            }

            val uniquePersonsOverall = uniquePersonIdsAcrossHour.size

            // Build camera totals
            val cameraTotalsMap = mutableMapOf<String, Any>(
                "batches_processed" to batchesProcessed,
                "images_processed" to imagesProcessed,
                "head_clear_ids" to headClearIds,
                "head_clear_total" to headClearTotal,
                "unique_persons_overall" to uniquePersonsOverall,
                "gender_counts_total" to genderCountsTotal.toMap(),
                "age_category_counts" to ageCategoryCounts.toMap(),
                "emotion_counts_total" to emotionCountsTotal.toMap(),
                "looking_3plus_total" to looking3plusTotal,
                "last_updated" to isoInstantFmt.format(Date(latestRecTimestamp))
            )

            val cameraFinalMap = linkedMapOf<String, Any>()
            cameraTotalsMap.forEach { (k, v) -> cameraFinalMap[k] = v }
            cameraEntries.forEach { (k, v) -> cameraFinalMap[k] = v }

            camerasMap[cameraId] = cameraFinalMap
        }

        val startedAt = ts.format(Date(fromMillis))
        val endAt = ts.format(Date(toMillis))

        return HourlyPayload(
            startedAt = startedAt,
            endAt = endAt,
            deviceId = deviceId,
            cameras = camerasMap.toMap()
        )
    }

    fun buildBatchDataPayload(
        intervals: List<IntervalCounts>,
        deviceId: String,
        cameraName: String = ""
    ): BatchData {
        val allIntervals = intervals
        var startedAt: String
        var endAt: String

        try {
            val minStartTime = allIntervals.minOf { fullTimeParser.parse(it.batchStartTime).time }
            val maxEndTime = allIntervals.maxOf { fullTimeParser.parse(it.batchEndTime).time }
            startedAt = fullTimeFormatter.format(Date(minStartTime))
            endAt = fullTimeFormatter.format(Date(maxEndTime))
        } catch (e: Exception) {
            // Fallback to current time if parsing fails
            val currentTime = System.currentTimeMillis()
            startedAt = fullTimeFormatter.format(Date(currentTime - 5 * 60 * 1000)) // 5 minutes ago
            endAt = fullTimeFormatter.format(Date(currentTime))
        }

        val batches = allIntervals.associate { interval ->
            val key = "batch_${interval.batchId}_${interval.batchStartTime}"
            key to BatchDetail(
                oldPeople = interval.oldPeople,
                newPeople = interval.newPeople,
                totalPeopleSeen = interval.totalPeopleSeen,
                uniqueNewArrivals = interval.uniqueNewArrivals,
                runningTotalPeople = interval.runningTotalNewArrivals,
                notes = interval.notes,
                genderCounts = mapOf(
                    "Male" to interval.genderCounts.maleCount,
                    "Female" to interval.genderCounts.femaleCount,
                    "Unknown" to 0
                ),
                emotionCounts = toEmotionMap(interval.expressionCounts),
                ageCategoryCounts = mapOf(
                    "Child (0-14)" to interval.ageGroupCounts.childCount,
                    "Young (15-25)" to interval.ageGroupCounts.youngAdultCount,
                    "Adult (26-55)" to interval.ageGroupCounts.adultCount,
                    "Elderly (56+)" to interval.ageGroupCounts.elderlyCount
                ),
                batchStartTime = interval.batchStartTime,
                batchEndTime = interval.batchEndTime
            )
        }

        val batchesProcessed = allIntervals.size
        val totalPeopleSeenTotal = allIntervals.sumOf { it.totalPeopleSeen }
        val runningTotalPeople = allIntervals.lastOrNull()?.runningTotalNewArrivals ?: 0

        val totalGenderCounts = aggregateGenderCounts(allIntervals.map { it.genderCounts })
        val totalAgeCounts = aggregateAgeGroupCounts(allIntervals.map { it.ageGroupCounts })
        val totalEmotionCounts = aggregateEmotionCounts(allIntervals.map { toEmotionMap(it.expressionCounts) })

        val cameraData = CameraData(
            batchesProcessed = batchesProcessed,
            runningTotalPeople = runningTotalPeople,
            totalPeopleSeenTotal = totalPeopleSeenTotal,
            genderCountsTotal = totalGenderCounts,
            ageCategoryCounts = totalAgeCounts,
            emotionCountsTotal = totalEmotionCounts,
            batches = batches
        )

        return BatchData(
            cameras = mapOf(cameraName to cameraData),
            deviceId = deviceId,
            endAt = endAt,
            startedAt = startedAt
        )
    }

    /**
     * NEW: Helper methods for aggregation
     */
    private fun toEmotionMap(ec: ImageVectorUseCase.ExpressionCounts): Map<String, Int> = mapOf(
        "neutral" to ec.neutralCount,
        "happy" to ec.happyCount,
        "surprised" to ec.surprisedCount,
        "sad" to ec.sadCount,
        "angry" to ec.angerCount,
        "disgust" to 0,
        "fear" to ec.fearCount,
        "contempt" to 0
    )

    private fun aggregateGenderCounts(genders: List<ImageVectorUseCase.GenderCounts>): Map<String, Int> {
        var male = 0
        var female = 0
        genders.forEach {
            male += it.maleCount
            female += it.femaleCount
        }
        return mapOf("Male" to male, "Female" to female, "Unknown" to 0)
    }

    private fun aggregateAgeGroupCounts(ages: List<ImageVectorUseCase.AgeGroupCounts>): Map<String, Int> {
        var child = 0
        var young = 0
        var adult = 0
        var elderly = 0
        ages.forEach {
            child += it.childCount
            young += it.youngAdultCount
            adult += it.adultCount
            elderly += it.elderlyCount
        }
        return mapOf(
            "Child (0-14)" to child,
            "Young (15-25)" to young,
            "Adult (26-55)" to adult,
            "Elderly (56+)" to elderly
        )
    }

    private fun aggregateEmotionCounts(emotions: List<Map<String, Int>>): Map<String, Int> {
        val total = mutableMapOf<String, Int>()
        emotions.forEach { map ->
            map.forEach { (key, value) ->
                total[key] = (total[key] ?: 0) + value
            }
        }
        return total
    }

    /** Remove face image records whose createdAt is within [fromMillis, toMillis]. */
    fun removeFaceRecordsInWindow(fromMillis: Long, toMillis: Long): Int {
        // findIds() returns LongArray (primitive). Convert to List<Long> for removeByIds.
        val idsPrimitive: LongArray = faceBox.query {
            between(FaceImageRecord_.createdAt, fromMillis, toMillis)
        }.findIds()

        if (idsPrimitive.isEmpty()) return 0

        // boxing conversion: LongArray -> List<Long>
        val idsList: List<Long> = idsPrimitive.toList()
        faceBox.removeByIds(idsList)
        return idsList.size
    }

    /** Remove person records whose addTime is within [fromMillis, toMillis]. */
    fun removePersonRecordsInWindow(fromMillis: Long, toMillis: Long): Int {
        val idsPrimitive: LongArray = personBox.query {
            between(PersonRecord_.addTime, fromMillis, toMillis)
        }.findIds()

        if (idsPrimitive.isEmpty()) return 0

        val idsList: List<Long> = idsPrimitive.toList()
        personBox.removeByIds(idsList)
        return idsList.size
    }

    /**
     * Aggressive: remove all face image records and (optionally) all person records.
     */
    fun clearAllStoredData(removePersonsToo: Boolean = false) {
        faceBox.removeAll()
        if (removePersonsToo) personBox.removeAll()
    }
}