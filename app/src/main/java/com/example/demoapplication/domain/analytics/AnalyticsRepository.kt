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
import java.util.Locale

class AnalyticsRepository(context: Context) {

    private val faceBox = ObjectBoxStore.store.boxFor<FaceImageRecord>()
    private val personBox = ObjectBoxStore.store.boxFor<PersonRecord>()

    // yyyy-MM-dd HH:mm:ss
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Build envelope for single camera within [fromMillis, toMillis]
     */
    fun buildStoredPersonsStats(
        cameraId: String,
        fromMillis: Long,
        toMillis: Long,
        fallbackToAnyLatest: Boolean = true
    ): Pair<String, CameraEnvelope> {
        // 1) Pull all records in window
        val windowRecs = faceBox.query {
            between(FaceImageRecord_.createdAt, fromMillis, toMillis)
        }.find()

        // 2) Group by personID → pick latest in window
        val latestByPersonInWindow: MutableMap<Long, FaceImageRecord> =
            windowRecs.groupBy { it.personID }
                .mapValues { (_, list) -> list.maxByOrNull { it.createdAt }!! }
                .toMutableMap()

        // 3) If fallback enabled, for persons missing in window, pick overall latest
        if (fallbackToAnyLatest) {
            val allPersonsIds: Set<Long> = personBox.all.map { it.personID }.toSet()
            val missing = allPersonsIds - latestByPersonInWindow.keys
            if (missing.isNotEmpty()) {
                // query overall latest per missing person
                // (fast path: one query; then reduce in memory)
                val allRecs = faceBox.query {}.find()
                val latestOverallByPerson = allRecs.groupBy { it.personID }
                    .mapValues { (_, list) -> list.maxByOrNull { it.createdAt }!! }

                missing.forEach { pid ->
                    latestOverallByPerson[pid]?.let { latestByPersonInWindow[pid] = it }
                }
            }
        }

        val personReps: Collection<FaceImageRecord> = latestByPersonInWindow.values
        val uniquePersons = personReps.size // “uniqueFaceCount” = stored unique persons considered

        // ----- Buckets from these representative records -----
        // gender
        var male = 0; var female = 0; var unrec = 0
        // expression
        var happy = 0; var sad = 0; var anger = 0; var surprise = 0; var disgust = 0; var neutral = 0; var other = 0
        // age groups (approx mapping)
        var minor = 0; var adult = 0; var senior = 0

        personReps.forEach { rec ->
            // gender
            when (rec.gender?.trim()) {
                "Male" -> male++
                "Female" -> female++
                null, "", "Unknown", "Unrecognized", "Unrecognised" -> unrec++
                else -> unrec++
            }

            // expression
            when (rec.expression?.lowercase()?.trim()) {
                "happy" -> happy++
                "sad" -> sad++
                "anger", "angry" -> anger++
                "surprised", "surprise" -> surprise++
                "disgust" -> disgust++
                "neutral" -> neutral++
                null, "", "unknown" -> other++
                else -> other++ // e.g. "fear" → other
            }

            // age (string buckets → target buckets)
            when (rec.ageGroup?.trim()) {
                "Child (0-14)" -> minor++
                "Young (15-25)" -> adult++   // 15–17 minor ideally, but we don't have exact age
                "Adult (26-55)" -> adult++
                "Elderly (56+)" -> senior++  // 56–64 senior approx
                null, "" -> adult++
                else -> adult++
            }
        }

        // “totalHeadCount” — since we’re sending **stored persons**, set to unique persons
        val totalHeadCount = uniquePersons

        val envelope = CameraEnvelope(
            cameraId = cameraId,
            timestamp = TimestampRange(
                from = ts.format(fromMillis),
                to   = ts.format(toMillis)
            ),
            data = CameraData(
                totalHeadCount = totalHeadCount,
                genderCount = mapOf(
                    "Male" to male,
                    "Female" to female,
                    "Unrecognised" to unrec
                ),
                expression = mapOf(
                    "happy" to happy,
                    "sad" to sad,
                    "anger" to anger,
                    "surprise" to surprise,
                    "disgust" to disgust,
                    "neutral" to neutral,
                    "other" to other
                ),
                ageGroup = mapOf(
                    "minor" to minor,
                    "adult" to adult,
                    "senior" to senior
                ),
                uniqueFaceCount = uniquePersons
            )
        )

        return cameraId to envelope
    }

    /**
     * Build body for multiple cameras.
     * If each camera writes to a separate DB, call buildCameraStats per repo.
     * If same DB, you may pass different cameraIds but same result (or filter by camera field if you have one).
     */
    fun buildPayloadForStoredPersons(
        cameras: List<String>,
        fromMillis: Long,
        toMillis: Long,
        fallbackToAnyLatest: Boolean = true
    ): Map<String, CameraEnvelope> {
        return cameras.map {
            buildStoredPersonsStats(it, fromMillis, toMillis, fallbackToAnyLatest)
        }.toMap()
    }
}