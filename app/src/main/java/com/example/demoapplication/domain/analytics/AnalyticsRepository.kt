package com.example.demoapplication.domain.analytics

import android.content.Context
import com.example.demoapplication.data.FaceImageRecord
import com.example.demoapplication.data.PersonRecord
import com.example.demoapplication.data.FaceImageRecord_
import com.example.demoapplication.data.PersonRecord_
import com.example.demoapplication.data.ObjectBoxStore
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

    fun buildHourlyPayloadAsWrapper(
        cameras: List<String>,
        fromMillis: Long,
        toMillis: Long,
        intervalTime: Long = 60L, // <-- interval in SECONDS (default: 60s)
    ): HourlyPayload {

        // Convert seconds to milliseconds for internal calculations
        val intervalMillis = (if (intervalTime <= 0) 60L else intervalTime) * 1000L

        val camerasMap = mutableMapOf<String, Map<String, Any>>()

        cameras.forEach { cameraId ->

            // Query all records for this camera in the hour (single query).
            val allRecsInHour: List<FaceImageRecord> = faceBox.query {
                between(FaceImageRecord_.createdAt, fromMillis, toMillis)
                // If FaceImageRecord has cameraId field, un-comment the next line:
                // equal(FaceImageRecord_.cameraId, cameraId)
            }.find()

            // latest record timestamp for this camera (for last_updated); fallback to toMillis
            val latestRecTimestamp = allRecsInHour.maxOfOrNull { it.createdAt } ?: toMillis

            // Partition records by minute bucket index
            val numBuckets = ((toMillis - fromMillis + intervalMillis - 1) / intervalMillis).toInt().coerceAtLeast(1)
            val buckets: Array<MutableList<FaceImageRecord>> = Array(numBuckets) { mutableListOf() }

            allRecsInHour.forEach { rec ->
                val idx = ((rec.createdAt - fromMillis) / intervalMillis).toInt().coerceIn(0, numBuckets - 1)
                buckets[idx].add(rec)
            }

            // Prepare camera-level totals (hour)
            var batchesProcessed = 0
            var imagesProcessed = 0
            val headClearIds = 0
            var headClearTotal = 0
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

            // Build per-minute batch entries and aggregate to camera totals
            val cameraEntries = mutableMapOf<String, Any>() // dynamic map containing totals + batch_* keys

            for (idx in 0 until numBuckets) {
                val bucketRecs = buckets[idx]
                val windowStart = fromMillis + idx * intervalMillis
                min(windowStart + intervalMillis, toMillis)

                // compute per-minute counts
                val headCount = bucketRecs.size // approximate; adjust if you compute head vs face differently
                val faceCount = bucketRecs.size
                val looking3plus = 0 // placeholder; compute from rec if available

                // per-minute gender counts
                val gMale = bucketRecs.count { it.gender?.trim() == "Male" }
                val gFemale = bucketRecs.count { it.gender?.trim() == "Female" }
                val gUnknown = bucketRecs.count { it.gender == null || it.gender!!.trim().isEmpty() || it.gender!!.trim() !in setOf("Male", "Female") }

                // emotions
                val eNeutral = bucketRecs.count { it.expression?.lowercase()?.trim() == "neutral" }
                val eHappy = bucketRecs.count { it.expression?.lowercase()?.trim() == "happy" }
                val eSurprised = bucketRecs.count { listOf("surprised","surprise").contains(it.expression?.lowercase()?.trim()) }
                val eSad = bucketRecs.count { it.expression?.lowercase()?.trim() == "sad" }
                val eAngry = bucketRecs.count { listOf("anger","angry").contains(it.expression?.lowercase()?.trim()) }
                val eDisgust = bucketRecs.count { it.expression?.lowercase()?.trim() == "disgust" }
                val eFear = bucketRecs.count { it.expression?.lowercase()?.trim() == "fear" }
                val eContempt = bucketRecs.count { it.expression?.lowercase()?.trim() == "contempt" }

                // age category counts mapping back to your strings
                val aChild = bucketRecs.count { it.ageGroup?.trim() == "Child (0-14)" }
                val aYoung = bucketRecs.count { it.ageGroup?.trim() == "Young (15-25)" }
                val aAdult = bucketRecs.count { it.ageGroup?.trim() == "Adult (26-55)" }
                val aElderly = bucketRecs.count { it.ageGroup?.trim() == "Elderly (56+)" }

                // images processed for this bucket (count of image records)
                val images = bucketRecs.size

                // Build the per-minute batch map matching your sample shape
                val batchKeyShort = keyFmt.format(Date(windowStart)) // e.g. 20250905_191400
                val batchKeyTimestamp = ts.format(Date(windowStart)) // e.g. "2025-09-05 19:14:00"
                // If you prefer no spaces/colons in key, you can use only batchKeyShort
                val batchKey = "batch_${batchKeyShort}_${batchKeyTimestamp}"

                val batchMap = mapOf(
                    "head_count" to headCount,
                    "face_count" to faceCount,
                    "looking_3plus" to looking3plus,
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
                    "time" to isoNoZone.format(Date(windowStart)) // "yyyy-MM-dd HH:mm:ss" (Asia/Kolkata)
                )

                // put batch in cameraEntries
                cameraEntries[batchKey] = batchMap

                // aggregate hourly totals
                batchesProcessed += 1
                imagesProcessed += images
                headClearTotal += headCount
                // headClearIds left as 0 unless you compute head-clear IDs logic elsewhere

                genderCountsTotal["Male"] = genderCountsTotal["Male"]!! + gMale
                genderCountsTotal["Female"] = genderCountsTotal["Female"]!! + gFemale
                // treat unknown as "Unknown"
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

                // collect unique personIDs (if present)
                bucketRecs.map { it.personID }.forEach { pid -> uniquePersonIdsAcrossHour.add(pid) }
            } // end of minute buckets loop

            // unique persons overall for this camera (union)
            val uniquePersonsOverall = uniquePersonIdsAcrossHour.size

            // Build camera-level totals and put them into cameraEntries (these appear at same level as batch_* keys)
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

            // Merge totals at top of cameraEntries (so totals are siblings of batch_* keys)
            // Ensure totals come before batches in JSON by inserting them first into a LinkedHashMap
            val cameraFinalMap = linkedMapOf<String, Any>()
            cameraTotalsMap.forEach { (k, v) -> cameraFinalMap[k] = v }
            // then append the dynamic batch entries
            cameraEntries.forEach { (k, v) -> cameraFinalMap[k] = v }

            // put this camera into camerasMap
            camerasMap[cameraId] = cameraFinalMap
        }

        val startedAt = ts.format(Date(fromMillis))
        val endAt = ts.format(Date(toMillis))

        return HourlyPayload(
            startedAt = startedAt,
            endAt = endAt,
            cameras = camerasMap.toMap()
        )
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