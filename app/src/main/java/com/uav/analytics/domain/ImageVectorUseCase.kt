package com.uav.analytics.domain

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import com.uav.analytics.MainActivityViewModel
import com.uav.analytics.RemoteConfigHelper
import com.uav.analytics.data.FaceImageRecord
import com.uav.analytics.data.ImagesVectorDB
import com.uav.analytics.data.RecognitionMetrics
import com.uav.analytics.domain.analytics.AggregatesSender
import com.uav.analytics.domain.analytics.IntervalCounts
import com.uav.analytics.domain.embeddings.FaceNet
import com.uav.analytics.domain.faceDection.FaceSpoofDetector
import com.uav.analytics.domain.faceDection.MediapipeFaceDetector
import com.uav.analytics.utils.DataTableLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ImageVectorUseCase(
    val mediapipeFaceDetector: MediapipeFaceDetector,
    private val faceSpoofDetector: FaceSpoofDetector,
    val imagesVectorDB: ImagesVectorDB,
    val faceNet: FaceNet,
    private val context: Context
) {

    // Add AggregatesSender instance
    private val aggregatesSender = AggregatesSender(context)
    private val prefs = context.getSharedPreferences("tracking_data", Context.MODE_PRIVATE)

    val ids = arrayListOf<Long>()

    data class FaceRecognitionResult(
        val personName: String,
        val personID: Long,
        val boundingBox: Rect,
        val spoofResult: FaceSpoofDetector.FaceSpoofResult? = null,
        val gender: String? = null,
        val age: Float? = null,
        val ageGroup: String? = null,
        val expression: String? = null
    )

    data class ExpressionCounts(
        val neutralCount: Int = 0,
        val happyCount: Int = 0,
        val surprisedCount: Int = 0,
        val sadCount: Int = 0,
        val angerCount: Int = 0,
        val fearCount: Int = 0,
    )

    data class GenderCounts(
        val maleCount: Int = 0,
        val femaleCount: Int = 0
    )

    data class AgeGroupCounts(
        val childCount: Int = 0,
        val youngAdultCount: Int = 0,
        val adultCount: Int = 0,
        val elderlyCount: Int = 0
    )

    // Interval tracking logic as per PDF - 1-min intervals over 5-min tracking for testing
    private var sessionStartTime: Long = 0L
    private var currentIntervalStart: Long = 0L
    private val intervalPersonSets: MutableMap<Long, MutableSet<Long>> = mutableMapOf()
    private var previousIntervalPersons: Set<Long> = emptySet()
    private var runningTotalNewArrivals: Int = 0
    private val globalAllSeen: MutableSet<Long> = mutableSetOf()
    private val intervalMillis = RemoteConfigHelper.getIntervalTime() * 1000L  // Get from remote config
    private val trackingPeriodMillis = RemoteConfigHelper.uploadIntervalDataMs()  // Get from remote config
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val batchIdFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val fullTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fullTimeParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Tracking variables for PDF logic
    private var totalPeopleSeenCumulative: Int = 0
    private var tableHeaderPrinted: Boolean = false
    private var currentIntervalNewFaces: MutableSet<Long> = mutableSetOf()
    private var currentIntervalReenteredFaces: MutableSet<Long> = mutableSetOf()

    // For API batching: Collect 1-min intervals for 5-min period
    private val currentPeriodIntervals: MutableList<IntervalCounts> = mutableListOf()
    private val pendingIntervals: MutableList<IntervalCounts> = mutableListOf()

    // Save pending intervals when app goes to background
    private fun savePendingData() {
        val allPendingData = (pendingIntervals + currentPeriodIntervals).toList()
        val json = Gson().toJson(allPendingData)
        prefs.edit().putString("pending_intervals", json).apply()
        Log.d("PDFTracking", "💾 Saved ${allPendingData.size} pending intervals to storage")
    }

    // Load pending intervals when app starts
    private fun loadPendingData(): List<IntervalCounts> {
        val json = prefs.getString("pending_intervals", null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<IntervalCounts>>() {}.type
                val loadedData = Gson().fromJson<List<IntervalCounts>>(json, type) ?: emptyList()
                Log.d("PDFTracking", "📂 Loaded ${loadedData.size} pending intervals from storage")
                loadedData
            } catch (e: Exception) {
                Log.e("PDFTracking", "❌ Error loading pending data: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // Clear saved data when successfully sent
    private fun clearSavedData() {
        prefs.edit().remove("pending_intervals").apply()
        Log.d("PDFTracking", "🗑️ Cleared saved pending intervals")
    }

    fun startTracking(startTime: Long = System.currentTimeMillis()) {
        sessionStartTime = startTime
        currentIntervalStart = sessionStartTime
        intervalPersonSets.clear()
        previousIntervalPersons = emptySet()
        runningTotalNewArrivals = 0
        totalPeopleSeenCumulative = 0
        tableHeaderPrinted = false
        globalAllSeen.clear()
        currentIntervalNewFaces.clear()
        currentIntervalReenteredFaces.clear()
        currentPeriodIntervals.clear()

        // 🚨 CRITICAL: Load pending data from storage when starting new session
        if (pendingIntervals.isEmpty()) {
            val loadedData = loadPendingData()
            pendingIntervals.addAll(loadedData)
            if (loadedData.isNotEmpty()) {
                Log.d("PDFTracking", "🔄 Restored ${loadedData.size} pending intervals from storage")
            }
        }

        intervalPersonSets[currentIntervalStart] = mutableSetOf()

        // Print initial table header
        DataTableLogger.printTableHeader()
        tableHeaderPrinted = true
    }

    fun stopTracking() {
        savePendingData()
        intervalPersonSets.clear()
        globalAllSeen.clear()
        sessionStartTime = 0L
        runningTotalNewArrivals = 0
        totalPeopleSeenCumulative = 0
        currentPeriodIntervals.clear()
        // pendingIntervals.clear()
    }

    private fun getIntervalStart(time: Long): Long {
        val elapsed = time - sessionStartTime
        val intervalOffset = (elapsed / intervalMillis) * intervalMillis
        return sessionStartTime + intervalOffset
    }

    private fun formatTime(time: Long): String = timeFormat.format(Date(time))

    private suspend fun processIntervalUpdate(viewModel: MainActivityViewModel, currentTime: Long, currentFramePersons: Set<Long>) {
        val currentInterval = getIntervalStart(currentTime)

        Log.d("PDFTracking", "⏰ Processing: CurrentTime=${formatTime(currentTime)}, CurrentInterval=${formatTime(currentInterval)}, CurrentIntervalStart=${formatTime(currentIntervalStart)}")
        Log.d("PDFTracking", "👥 Current Frame Persons: ${currentFramePersons.size} - IDs: $currentFramePersons")

        // Always add current frame persons to current interval set
        val currentSet = intervalPersonSets.getOrPut(currentIntervalStart) { mutableSetOf() }
        val previousSize = currentSet.size
        currentSet.addAll(currentFramePersons)
        val addedCount = currentSet.size - previousSize

        if (addedCount > 0) {
            Log.d("PDFTracking", "✅ Added $addedCount persons to interval ${formatTime(currentIntervalStart)} - Now: ${currentSet.size} persons")
        }

        // If we're still in the same interval, wait for it to complete
        if (currentInterval == currentIntervalStart) {
            return
        }

        // ==================== CALCULATE STATISTICS FOR COMPLETED INTERVAL ====================
        val completedIntervalPersons = intervalPersonSets[currentIntervalStart] ?: emptySet()

        // OLD PEOPLE: From previous interval who are still in completed interval (PDF Page 2 logic)
        val oldPeople = (completedIntervalPersons intersect previousIntervalPersons).size
        // Calculate old faces for notes
        val oldFaces = completedIntervalPersons intersect previousIntervalPersons
        // NEW PEOPLE: Fresh arrivals in current period (PDF Page 1: "Fresh arrivals detected in current period")
        val newPeople = completedIntervalPersons.minus(previousIntervalPersons).size

        // UNIQUE NEW ARRIVALS: Only faces never seen before in entire session
        val uniqueNewArrivals = completedIntervalPersons.count { !globalAllSeen.contains(it) }

        // TOTAL PEOPLE SEEN: All faces detected in this interval (Old + New + Re-entering)
        val totalPeopleSeen = completedIntervalPersons.size
        totalPeopleSeenCumulative += totalPeopleSeen

        // Identify new faces and re-entered faces for notes
        currentIntervalNewFaces.clear()
        currentIntervalReenteredFaces.clear()

        completedIntervalPersons.forEach { personId ->
            if (!globalAllSeen.contains(personId)) {
                currentIntervalNewFaces.add(personId)
            } else if (!previousIntervalPersons.contains(personId)) {
                currentIntervalReenteredFaces.add(personId)
            }
        }

        // Update running total and global tracking
        runningTotalNewArrivals += uniqueNewArrivals
        globalAllSeen.addAll(completedIntervalPersons)

        val periodStr = "${formatTime(currentIntervalStart)} - ${formatTime(currentIntervalStart + intervalMillis)}"

        // Compute per-interval attribute counts
        val genderCounts = computeIntervalGenderCounts(completedIntervalPersons)
        val ageGroupCounts = computeIntervalAgeGroupCounts(completedIntervalPersons)
        val expressionCounts = computeIntervalExpressionCounts(completedIntervalPersons)

        // Generate notes like PDF
        val notes = buildNotes(newFaces = currentIntervalNewFaces, reenteredFaces = currentIntervalReenteredFaces, oldFaces = oldFaces)

        val batchId = batchIdFormatter.format(Date(currentIntervalStart))
        val batchStart = fullTimeFormatter.format(Date(currentIntervalStart))
        val batchEnd = fullTimeFormatter.format(Date(currentIntervalStart + intervalMillis))

        val intervalCounts = IntervalCounts(
            timePeriod = periodStr,
            oldPeople = oldPeople,
            newPeople = newPeople,
            totalPeopleSeen = totalPeopleSeen,
            uniqueNewArrivals = uniqueNewArrivals,
            runningTotalNewArrivals = runningTotalNewArrivals,
            genderCounts = genderCounts,
            ageGroupCounts = ageGroupCounts,
            expressionCounts = expressionCounts,
            notes = notes,
            batchId = batchId,
            batchStartTime = batchStart,
            batchEndTime = batchEnd
        )

        // PRINT TABLE ROW
        DataTableLogger.printTableRow(
            timePeriod = periodStr,
            oldPeople = oldPeople,
            newPeople = newPeople,
            totalPeopleSeen = totalPeopleSeen,
            uniqueNewArrivals = uniqueNewArrivals,
            runningTotalNewArrivals = runningTotalNewArrivals,
            notes = notes
        )

        // Log detailed breakdown like PDF
        logDetailedBreakdown(periodStr, oldPeople, newPeople, totalPeopleSeen, uniqueNewArrivals,
            runningTotalNewArrivals, completedIntervalPersons, previousIntervalPersons)

        // Collect for API batch
        currentPeriodIntervals.add(intervalCounts)

        // Update for next interval
        previousIntervalPersons = completedIntervalPersons
        currentIntervalStart = currentInterval
        intervalPersonSets[currentIntervalStart] = mutableSetOf()

        Log.d("PDFTracking", "🆕 Started new interval: ${formatTime(currentIntervalStart)}")

        savePendingData()
        // Check for new tracking period (5 minutes completed)
        val periodEndTime = sessionStartTime + trackingPeriodMillis
        if (currentIntervalStart >= periodEndTime) {
            Log.d("PDFTracking", "🕐 TRACKING PERIOD COMPLETED: ${formatTime(sessionStartTime)} to ${formatTime(periodEndTime)}")

            // Send API data for the completed period
            val periodStartTime = sessionStartTime
            val periodEndTimeLocal = currentIntervalStart
            // Print footer for completed period
            DataTableLogger.printTableFooter(totalPeopleSeenCumulative, runningTotalNewArrivals)

            // 🚨 Only send if we have pending intervals OR current intervals
            if (pendingIntervals.isNotEmpty() || currentPeriodIntervals.isNotEmpty()) {
                sendPeriodData(viewModel, periodStartTime, periodEndTimeLocal, currentPeriodIntervals.toList())
            }
            // 🧹 COMPLETELY RESET ALL TRACKING VARIABLES
            Log.d("PDFTracking", "🧹 CLEARING ALL DATA FOR FRESH START...")
            Log.d("PDFTracking", "🎊 NEW TRACKING PERIOD STARTED AT ${formatTime(sessionStartTime)}")

            tableHeaderPrinted = true
        }
    }

    @SuppressLint("HardwareIds")
    private suspend fun sendPeriodData(viewModel: MainActivityViewModel, periodStartTime: Long, periodEndTime: Long, intervals: List<IntervalCounts>) {
        if (pendingIntervals.isEmpty() && intervals.isEmpty()) return

        val allIntervals = pendingIntervals + intervals
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val cameraId = prefs.getString("camera_id", "camera1") ?: "camera1"

        val success = aggregatesSender.sendBatchData(allIntervals, deviceId, cameraID = cameraId)

        if (success) {
            pendingIntervals.clear()
            currentPeriodIntervals.clear()
            clearSavedData() // Clear saved data too
            viewModel.resetAllFaceCounts()
            // Reset all tracking variables
            resetTrackingForNewPeriod()
            // Initialize new interval set
            intervalPersonSets[currentIntervalStart] = mutableSetOf()
            Log.d("PDFTracking", "✅ Successfully sent ${allIntervals.size} intervals to API")
        } else {
            Log.e("PDFTracking", "❌ API call failed, saving ${allIntervals.size} intervals to storage")

            pendingIntervals.clear()
            pendingIntervals.addAll(allIntervals)
            savePendingData() // Save to persistent storage
            resetTrackingForNewPeriod()
            Log.d("PDFTracking", "📦 Pending intervals count: ${pendingIntervals.size}")
        }
    }

    private fun resetTrackingForNewPeriod() {
        totalPeopleSeenCumulative = 0
        tableHeaderPrinted = false
        sessionStartTime = currentIntervalStart
        currentIntervalStart = sessionStartTime
        intervalPersonSets.clear()
        currentIntervalNewFaces.clear()
        currentIntervalReenteredFaces.clear()

        // Initialize new interval set
        intervalPersonSets[currentIntervalStart] = mutableSetOf()

        // Always clear currentPeriodIntervals to prevent duplicates
        currentPeriodIntervals.clear()
        // Print header for new period
        DataTableLogger.printTableHeader()
        tableHeaderPrinted = true
    }

    private fun computeIntervalGenderCounts(persons: Set<Long>): GenderCounts {
        var male = 0
        var female = 0
        persons.forEach { personId ->
            val record = imagesVectorDB.getLatestFaceImageRecord(personId)
            when (record?.gender) {
                "Male" -> male++
                "Female" -> female++
            }
        }
        return GenderCounts(maleCount = male, femaleCount = female)
    }

    private fun computeIntervalAgeGroupCounts(persons: Set<Long>): AgeGroupCounts {
        var child = 0
        var young = 0
        var adult = 0
        var elderly = 0
        persons.forEach { personId ->
            val record = imagesVectorDB.getLatestFaceImageRecord(personId)
            when (record?.ageGroup) {
                "Child (0-14)" -> child++
                "Young (15-25)" -> young++
                "Adult (26-55)" -> adult++
                "Elderly (56+)" -> elderly++
            }
        }
        return AgeGroupCounts(childCount = child, youngAdultCount = young, adultCount = adult, elderlyCount = elderly)
    }

    private fun computeIntervalExpressionCounts(persons: Set<Long>): ExpressionCounts {
        var neutral = 0
        var happy = 0
        var surprised = 0
        var sad = 0
        var anger = 0
        var fear = 0
        persons.forEach { personId ->
            val record = imagesVectorDB.getLatestFaceImageRecord(personId)
            when (record?.expression) {
                "neutral" -> neutral++
                "happy" -> happy++
                "surprised" -> surprised++
                "sad" -> sad++
                "anger" -> anger++
                "fear" -> fear++
            }
        }
        return ExpressionCounts(
            neutralCount = neutral,
            happyCount = happy,
            surprisedCount = surprised,
            sadCount = sad,
            angerCount = anger,
            fearCount = fear
        )
    }

    private fun buildNotes(
        newFaces: Set<Long>,
        reenteredFaces: Set<Long>,
        oldFaces: Set<Long>,
    ): String {
        val notes = StringBuilder()

        // Old faces information
        if (oldFaces.isNotEmpty()) {
            val sortedOldFaces = oldFaces.sorted()
            if (sortedOldFaces.size <= 5) {
                notes.append("Faces ${sortedOldFaces.joinToString(", ")} (old)")
            } else {
                notes.append("Faces ${sortedOldFaces.take(3).joinToString(", ")}... (${sortedOldFaces.size} old)")
            }
        }

        // New arrivals
        if (newFaces.isNotEmpty()) {
            if (notes.isNotEmpty()) notes.append("\n")
            val sortedNewFaces = newFaces.sorted()

            if (sortedNewFaces.size <= 5) {
                notes.append("Faces ${sortedNewFaces.joinToString(", ")} (new arrivals)")
            } else {
                notes.append("Faces ${sortedNewFaces.take(3).joinToString(", ")}... (${sortedNewFaces.size} new arrivals)")
            }
        }

        // Returning faces
        if (reenteredFaces.isNotEmpty()) {
            if (notes.isNotEmpty()) notes.append("\n")
            val sortedReturnFaces = reenteredFaces.sorted()
            if (sortedReturnFaces.size == 1) {
                notes.append("Face ${sortedReturnFaces.first()} returns")
            } else if (sortedReturnFaces.size <= 3) {
                notes.append("Faces ${sortedReturnFaces.joinToString(", ")} return")
            } else {
                notes.append("Faces ${sortedReturnFaces.take(3).joinToString(", ")}... return")
            }
        }

        // No new arrivals case
        if (newFaces.isEmpty() && reenteredFaces.isEmpty() && oldFaces.isNotEmpty()) {
            if (notes.isNotEmpty()) notes.append("\n")
            notes.append("No new arrivals")
        }

        // Completely empty
        if (notes.isEmpty()) {
            notes.append("No activity detected")
        }

        return notes.toString()
    }

    private fun logDetailedBreakdown(
        period: String,
        oldPeople: Int,
        newPeople: Int,
        totalPeopleSeen: Int,
        uniqueNewArrivals: Int,
        runningTotal: Int,
        currentPersons: Set<Long>,
        previousPersons: Set<Long>
    ) {
        Log.d("PDFBreakdown", "🔍 DETAILED BREAKDOWN for $period:")
        Log.d("PDFBreakdown", "   • Old People: $oldPeople (from previous interval still present)")
        Log.d("PDFBreakdown", "   • New People: $newPeople (fresh arrivals this period)")
        Log.d("PDFBreakdown", "   • Total People Seen: $totalPeopleSeen (all faces detected)")
        Log.d("PDFBreakdown", "   • Unique New Arrivals: $uniqueNewArrivals (never seen before)")
        Log.d("PDFBreakdown", "   • Running Total New Arrivals: $runningTotal")
        Log.d("PDFBreakdown", "   • Current Persons: ${currentPersons.sorted()}")
        Log.d("PDFBreakdown", "   • Previous Persons: ${previousPersons.sorted()}")
        Log.d("PDFBreakdown", "   • People Left: ${previousPersons.minus(currentPersons).size}")
        Log.d("PDFBreakdown", "   • Explanation: ${generateExplanation(oldPeople, newPeople, currentPersons, previousPersons)}")
    }

    private fun generateExplanation(
        oldPeople: Int,
        newPeople: Int,
        currentPersons: Set<Long>,
        previousPersons: Set<Long>
    ): String {
        val leftCount = previousPersons.minus(currentPersons).size
        return "Of the previous ${previousPersons.size}, $leftCount left, and $newPeople new arrived. " +
                "${oldPeople} remained from previous interval."
    }

    // Rest of your existing methods remain exactly the same...
    private val expressionLabels: List<String> by lazy {
        FileUtil.loadLabels(context, "fer_model.names")
    }

    private val expressionInterpreter: Interpreter? by lazy {
        try {
            val interpreterOptions = Interpreter.Options().apply {
                numThreads = 4
                useXNNPACK = true
            }
            Interpreter(FileUtil.loadMappedFile(context, "fer_model.tflite"), interpreterOptions)
        } catch (e: Exception) {
            Log.e("ImageVectorUseCase", "Failed to load fer_model.tflite: ${e.message}")
            null
        }
    }

    private val ageInterpreter: Interpreter? by lazy {
        try {
            val interpreterOptions = Interpreter.Options().apply {
                numThreads = 4
                useXNNPACK = true
            }
            Interpreter(FileUtil.loadMappedFile(context, "model_age_q.tflite"), interpreterOptions)
        } catch (e: Exception) {
            Log.e("ImageVectorUseCase", "Failed to load model_age_q.tflite: ${e.message}")
            null
        }
    }

    private val genderInterpreter: Interpreter? by lazy {
        try {
            val interpreterOptions = Interpreter.Options().apply {
                numThreads = 4
                useXNNPACK = true
            }
            Interpreter(FileUtil.loadMappedFile(context, "model_gender_q.tflite"), interpreterOptions)
        } catch (e: Exception) {
            Log.e("ImageVectorUseCase", "Failed to load model_gender_q.tflite: ${e.message}")
            null
        }
    }

    private val ageImageProcessor: ImageProcessor by lazy {
        ImageProcessor.Builder()
            .add(ResizeOp(200, 200, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()
    }

    private val genderImageProcessor: ImageProcessor by lazy {
        ImageProcessor.Builder()
            .add(ResizeOp(128, 128, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()
    }

    private fun l2Normalize(embedding: FloatArray): FloatArray {
        var sum = 0f
        for (v in embedding) sum += v * v
        val norm = sqrt(sum)
        return embedding.map { it / norm }.toFloatArray()
    }

    suspend fun getNearestPersonName(
        frameBitmap: Bitmap,
        viewModel: MainActivityViewModel
    ): Pair<RecognitionMetrics?, List<FaceRecognitionResult>> {
        if (sessionStartTime == 0L) {
            startTracking()
        }

        val currentTime = System.currentTimeMillis()
        val (faceDetectionResult, t1) = measureTimedValue {
            mediapipeFaceDetector.getAllCroppedFacesWithAngle(frameBitmap)
        }
        val faceRecognitionResults = ArrayList<FaceRecognitionResult>()
        var avgT2 = 0L
        var avgT3 = 0L
        var avgT4 = 0L
        var avgT5 = 0L
        var avgT6 = 0L

        val currentFramePersons = mutableSetOf<Long>()

        for (result in faceDetectionResult) {
            val (croppedBitmap, boundingBox, faceAngleData) = result
            val (embedding, t2) = measureTimedValue {
                val rawEmbedding = faceNet.getFaceEmbedding(croppedBitmap)
                l2Normalize(rawEmbedding)
            }
            avgT2 += t2.toLong(DurationUnit.MILLISECONDS)

            val (recognitionResult, t3) = measureTimedValue {
                imagesVectorDB.getNearestEmbeddingPersonName(embedding)
            }
            avgT3 += t3.toLong(DurationUnit.MILLISECONDS)

            val spoofResult = faceSpoofDetector.detectSpoof(frameBitmap, boundingBox)
            avgT4 += spoofResult.timeMillis

            val (expression, t5) = measureTimedValue {
                detectExpression(croppedBitmap)
            }
            avgT5 += t5.toLong(DurationUnit.MILLISECONDS)

            val (ageGender, t6) = measureTimedValue {
                detectAgeAndGender(croppedBitmap)
            }
            avgT6 += t6.toLong(DurationUnit.MILLISECONDS)

            val similarityThreshold = 0.5f
            var distance = 1f
            var personID: Long = 0L
            var personName: String = ""

            if (recognitionResult != null) {
                if (!ids.contains(recognitionResult.personID)) ids.add(recognitionResult.personID)

                distance = cosineDistance(embedding, recognitionResult.faceEmbedding)

                // Update existing record
                imagesVectorDB.removeDataByPersonId(recognitionResult.personID)
                imagesVectorDB.addFaceImageRecord(FaceImageRecord(
                    personID = recognitionResult.personID,
                    personName = recognitionResult.personName,
                    faceEmbedding = embedding,
                    gender = ageGender.gender,
                    ageGroup = ageGender.ageGroup,
                    expression = expression,
                    createdAt = System.currentTimeMillis(),
                    isOldPerson = false
                ))
                Log.d("UniqueFace", "Matched with existing -> ID: ${recognitionResult.personID}, Name: ${recognitionResult.personName}")
                personID = recognitionResult.personID
                personName = recognitionResult.personName
            }

            if (personID == 0L || distance < similarityThreshold) {
                // Add new
                personID = imagesVectorDB.getCount() + 1
                personName = "Person_$personID"

                imagesVectorDB.addFaceImageRecord(
                    FaceImageRecord(
                        personID = personID,
                        personName = personName,
                        faceEmbedding = embedding,
                        gender = ageGender.gender,
                        ageGroup = ageGender.ageGroup,
                        expression = expression,
                        createdAt = System.currentTimeMillis()
                    )
                )
                Log.d("UniqueFace", "New face added -> ID: $personID, Name: $personName")
            }

            currentFramePersons.add(personID)

            faceRecognitionResults.add(
                FaceRecognitionResult(
                    personName, personID, boundingBox, spoofResult,
                    gender = ageGender.gender,
                    age = ageGender.age,
                    ageGroup = ageGender.ageGroup,
                    expression
                )
            )
        }

        // Process interval update with current frame data
        processIntervalUpdate(viewModel, currentTime, currentFramePersons)

        // Update counts (per frame)
        val storedCount = imagesVectorDB.getCount()
        viewModel.updateFaceCounts(faceDetectionResult.size, storedCount)

        // Calculate expression counts (per frame)
        val expressionCounts = ExpressionCounts(
            neutralCount = faceRecognitionResults.count { it.expression == "neutral" },
            happyCount = faceRecognitionResults.count { it.expression == "happy" },
            surprisedCount = faceRecognitionResults.count { it.expression == "surprised" },
            sadCount = faceRecognitionResults.count { it.expression == "sad" },
            angerCount = faceRecognitionResults.count { it.expression == "anger" },
            fearCount = faceRecognitionResults.count { it.expression == "fear" },
        )
        viewModel.updateExpressionCounts(expressionCounts)

        // Calculate gender counts (per frame)
        val genderCounts = GenderCounts(
            maleCount = faceRecognitionResults.count { it.gender == "Male" },
            femaleCount = faceRecognitionResults.count { it.gender == "Female" }
        )
        viewModel.updateGenderCounts(genderCounts)

        // Calculate age group counts (per frame)
        val ageGroupCounts = AgeGroupCounts(
            childCount = faceRecognitionResults.count { it.ageGroup == "Child (0-14)" },
            youngAdultCount = faceRecognitionResults.count { it.ageGroup == "Young (15-25)" },
            adultCount = faceRecognitionResults.count { it.ageGroup == "Adult (26-55)" },
            elderlyCount = faceRecognitionResults.count { it.ageGroup == "Elderly (56+)" }
        )
        viewModel.updateAgeGroupCounts(ageGroupCounts)

        // Update stored counts
        viewModel.updateStoredGenderCounts(getStoredGenderCounts())
        viewModel.updateStoredAgeGroupCounts(getStoredAgeGroupCounts())
        viewModel.updateStoredExpressionCounts(getStoredExpressionCounts())

        val metrics = if (faceDetectionResult.isNotEmpty()) {
            RecognitionMetrics(
                timeFaceDetection = t1.toLong(DurationUnit.MILLISECONDS),
                timeFaceEmbedding = avgT2 / faceDetectionResult.size,
                timeVectorSearch = avgT3 / faceDetectionResult.size,
                timeFaceSpoofDetection = avgT4 / faceDetectionResult.size,
                timeExpressionDetection = avgT5 / faceDetectionResult.size,
                timeAgeGenderDetection = avgT6 / faceDetectionResult.size
            )
        } else {
            null
        }

        return Pair(metrics, faceRecognitionResults)
    }

    fun getStoredGenderCounts(): GenderCounts {
        // Implement in ImagesVectorDB: query COUNT(*) WHERE gender = ?
        val maleCount = imagesVectorDB.getCountByGender("Male").toInt()
        val femaleCount = imagesVectorDB.getCountByGender("Female").toInt()
        return GenderCounts(maleCount, femaleCount)
    }

    fun getStoredAgeGroupCounts(): AgeGroupCounts {
        // Implement in ImagesVectorDB: query COUNT(*) WHERE ageGroup = ?
        val childCount = imagesVectorDB.getCountByAgeGroup("Child (0-14)").toInt()
        val youngAdultCount = imagesVectorDB.getCountByAgeGroup("Young (15-25)").toInt()
        val adultCount = imagesVectorDB.getCountByAgeGroup("Adult (26-55)").toInt()
        val elderlyCount = imagesVectorDB.getCountByAgeGroup("Elderly (56+)").toInt()
        return AgeGroupCounts(childCount, youngAdultCount, adultCount, elderlyCount)
    }

    fun getStoredExpressionCounts(): ExpressionCounts {
        val neutralCount = imagesVectorDB.getCountByExpression("neutral").toInt()
        val happyCount = imagesVectorDB.getCountByExpression("happy").toInt()
        val surprisedCount = imagesVectorDB.getCountByExpression("surprised").toInt()
        val sadCount = imagesVectorDB.getCountByExpression("sad").toInt()
        val angerCount = imagesVectorDB.getCountByExpression("anger").toInt()
        val fearCount = imagesVectorDB.getCountByExpression("fear").toInt()
        return ExpressionCounts(neutralCount, happyCount, surprisedCount, sadCount, angerCount, fearCount)
    }

    private suspend fun detectExpression(faceBitmap: Bitmap): String = withContext(Dispatchers.Default) {
        // Convert to grayscale
        val grayscaleBitmap = convertToGrayscale(faceBitmap)
        // Resize to 48x48
        val resizedBitmap = Bitmap.createScaledBitmap(grayscaleBitmap, 48, 48, true)
        // Convert to single-channel float32 buffer
        val buffer = bitmapToGrayscaleBuffer(resizedBitmap)
        val output = Array(1) { FloatArray(expressionLabels.size) }
        expressionInterpreter?.run(buffer, output)
        val probabilities = output[0]
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
        expressionLabels[maxIndex]
    }

    private data class AgeGenderResult(val age: Float?, val gender: String?, val ageGroup: String?)

    private suspend fun detectAgeAndGender(faceBitmap: Bitmap): AgeGenderResult = withContext(
        Dispatchers.Default) {
        try {
            // Process age
            val ageTensorImage = TensorImage.fromBitmap(faceBitmap)
            val ageProcessedImage = ageImageProcessor.process(ageTensorImage).buffer
            val ageOutput = Array(1) { FloatArray(1) }
            ageInterpreter?.run(ageProcessedImage, ageOutput)
            val age = ageOutput[0][0].let { it * 116f }.let { if (it in 0f..116f) it else null }
            val ageGroup = age?.let {
                when (it.toInt()) {
                    in 0..14 -> "Child (0-14)"
                    in 15..25 -> "Young (15-25)"
                    in 26..55 -> "Adult (26-55)"
                    else -> "Elderly (56+)"
                }
            }

            // Process gender
            val genderTensorImage = TensorImage.fromBitmap(faceBitmap)
            val genderProcessedImage = genderImageProcessor.process(genderTensorImage).buffer
            val genderOutput = Array(1) { FloatArray(2) }
            genderInterpreter?.run(genderProcessedImage, genderOutput)
            val genderProbabilities = genderOutput[0]
            val gender = if (genderProbabilities[0] > genderProbabilities[1]) "Male" else "Female"

            Log.d("AgeGenderDetection", "Age: $age, Age Group: $ageGroup, Gender: $gender, Gender Probabilities: ${genderProbabilities.joinToString()}")
            AgeGenderResult(age, gender, ageGroup)
        } catch (e: Exception) {
            Log.e("AgeGenderDetection", "Error detecting age/gender: ${e.message}")
            AgeGenderResult(null, null, null)
        }
    }

    private fun convertToGrayscale(bitmap: Bitmap): Bitmap {
        val grayscaleBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        val paint = Paint()
        val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return grayscaleBitmap
    }

    private fun bitmapToGrayscaleBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(48 * 48 * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                // Extract grayscale value (R=G=B after desaturation)
                val gray = Color.red(pixel).toFloat() / 255f // Normalize to [0,1]
                buffer.putFloat(gray)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun cosineDistance(x1: FloatArray, x2: FloatArray): Float {
        var mag1 = 0.0f
        var mag2 = 0.0f
        var product = 0.0f
        for (i in x1.indices) {
            mag1 += x1[i].pow(2)
            mag2 += x2[i].pow(2)
            product += x1[i] * x2[i]
        }
        mag1 = sqrt(mag1)
        mag2 = sqrt(mag2)
        return product / (mag1 * mag2)
    }

    fun removeImages(personID: Long) {
        imagesVectorDB.removeFaceRecordsWithPersonID(personID)
    }
    fun clearAllPeople() =
        imagesVectorDB.clearAll()

}
