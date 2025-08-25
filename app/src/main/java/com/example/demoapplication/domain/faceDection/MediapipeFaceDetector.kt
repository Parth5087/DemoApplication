package com.example.demoapplication.domain.faceDection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class MediapipeFaceDetector(private val context: Context) {

    // The model is stored in the assets folder
    private val modelName = "blaze_face_short_range.tflite"
    private val baseOptions = BaseOptions.builder().setModelAssetPath(modelName).build()

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.1f)
        .enableTracking()
        .build()
//    private val options1 = FaceDetector.FaceDetectorOptions.builder()
//        .setBaseOptions(baseOptions)
//        .setRunningMode(RunningMode.LIVE_STREAM)

    private val detector: FaceDetector = FaceDetection.getClient(options)

    private val trackedFaces = mutableMapOf<Int, FaceAngleData>()
    private val SAME_ANGLE_TOLERANCE = 5f // degrees
    private val STABLE_TIME_MS = 3000 // 3 seconds

    data class FaceAngleData(
        var yaw: Float,
        var pitch: Float,
        var roll: Float,
        var firstSeenTime: Long,
        var lastSeenTime: Long,
        var isStable: Boolean = false // Added to track stability
    )

    // Detects multiple faces from the `frameBitmap` and returns triples of (croppedFace, boundingBoxRect, FaceAngleData)
    suspend fun getAllCroppedFacesWithAngle(frameBitmap: Bitmap): List<Triple<Bitmap, Rect, FaceAngleData?>> =
        withContext(Dispatchers.IO) {
            val inputImage = InputImage.fromBitmap(frameBitmap, 0)
            val faces: List<Face> = detector.process(inputImage).await()

            val currentTime = System.currentTimeMillis()
            val results = mutableListOf<Triple<Bitmap, Rect, FaceAngleData?>>()

            faces.forEach { face ->
                val id = face.trackingId ?: return@forEach
                val yaw = face.headEulerAngleY
                val pitch = face.headEulerAngleX
                val roll = face.headEulerAngleZ

                val existing = trackedFaces[id]
                if (existing == null) {
                    // First time seeing this face
                    trackedFaces[id] = FaceAngleData(yaw, pitch, roll, currentTime, currentTime, false)
                } else {
                    val yawDiff = abs(yaw - existing.yaw)
                    val pitchDiff = abs(pitch - existing.pitch)
                    val rollDiff = abs(roll - existing.roll)

                    if (yawDiff <= SAME_ANGLE_TOLERANCE &&
                        pitchDiff <= SAME_ANGLE_TOLERANCE &&
                        rollDiff <= SAME_ANGLE_TOLERANCE
                    ) {
                        // Same angle → update last seen and check stability
                        existing.lastSeenTime = currentTime
                        existing.isStable = (currentTime - existing.firstSeenTime) >= STABLE_TIME_MS
                    } else {
                        // Angle changed → reset timer and stability
                        existing.yaw = yaw
                        existing.pitch = pitch
                        existing.roll = roll
                        existing.firstSeenTime = currentTime
                        existing.lastSeenTime = currentTime
                        existing.isStable = false
                    }
                }

                val rect = face.boundingBox
                val safeLeft = rect.left.coerceAtLeast(0)
                val safeTop = rect.top.coerceAtLeast(0)
                val safeRight = rect.right.coerceAtMost(frameBitmap.width)
                val safeBottom = rect.bottom.coerceAtMost(frameBitmap.height)

                val safeWidth = (safeRight - safeLeft).coerceAtLeast(1)
                val safeHeight = (safeBottom - safeTop).coerceAtLeast(1)

                if (safeWidth > 1 && safeHeight > 1) {
                    val croppedBitmap = Bitmap.createBitmap(
                        frameBitmap,
                        safeLeft,
                        safeTop,
                        safeWidth,
                        safeHeight
                    )
                    results.add(Triple(croppedBitmap, Rect(safeLeft, safeTop, safeRight, safeBottom), trackedFaces[id]))
                }
            }

            // Remove old faces that are gone for >1s
            trackedFaces.entries.removeIf { currentTime - it.value.lastSeenTime > 1000 }

            return@withContext results
        }

    // DEBUG: For testing purpose, saves the Bitmap to the app's private storage
    fun saveBitmap(context: Context, image: Bitmap, name: String) {
        val fileOutputStream = FileOutputStream(File(context.filesDir.absolutePath + "/$name.png"))
        image.compress(Bitmap.CompressFormat.PNG, 100, fileOutputStream)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    // Check if the bounds of `boundingBox` fit within the limits of `cameraFrameBitmap`
    private fun validateRect(cameraFrameBitmap: Bitmap, boundingBox: Rect): Boolean {
        return boundingBox.left >= 0 &&
                boundingBox.top >= 0 &&
                (boundingBox.left + boundingBox.width()) < cameraFrameBitmap.width &&
                (boundingBox.top + boundingBox.height()) < cameraFrameBitmap.height
    }
}