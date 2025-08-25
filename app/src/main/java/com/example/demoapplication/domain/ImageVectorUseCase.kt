package com.example.demoapplication.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.example.demoapplication.MainActivityViewModel
import com.example.demoapplication.data.FaceImageRecord
import com.example.demoapplication.data.ImagesVectorDB
import com.example.demoapplication.data.RecognitionMetrics
import com.example.demoapplication.domain.embeddings.FaceNet
import com.example.demoapplication.domain.faceDection.FaceSpoofDetector
import com.example.demoapplication.domain.faceDection.MediapipeFaceDetector
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
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

class ImageVectorUseCase(
    val mediapipeFaceDetector: MediapipeFaceDetector,
    private val faceSpoofDetector: FaceSpoofDetector,
    val imagesVectorDB: ImagesVectorDB,
    val faceNet: FaceNet,
    private val context: Context
) {

    data class FaceRecognitionResult(
        val personName: String,
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

    private val expressionLabels: List<String> by lazy {
        FileUtil.loadLabels(context, "fer_model.names")
    }

    private val expressionInterpreter: Interpreter? by lazy {
        try {
            val interpreterOptions = Interpreter.Options().apply {
                numThreads = 4
                // Disable GPU delegate forcefully
                useXNNPACK = true // CPU optimized execution
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
                // Disable GPU delegate forcefully
                useXNNPACK = true // CPU optimized execution
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
                // Disable GPU delegate forcefully
                useXNNPACK = true // CPU optimized execution
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

    // From the given frame, return the name of the person by performing
    // face recognition
    suspend fun getNearestPersonName(
        frameBitmap: Bitmap,
        viewModel: MainActivityViewModel
    ): Pair<RecognitionMetrics?, List<FaceRecognitionResult>> {
        val (faceDetectionResult, t1) = measureTimedValue {
            mediapipeFaceDetector.getAllCroppedFacesWithAngle(frameBitmap)
        }
        val faceRecognitionResults = ArrayList<FaceRecognitionResult>()
        var avgT2 = 0L
        var avgT3 = 0L
        var avgT4 = 0L
        var avgT5 = 0L
        var avgT6 = 0L

        for (result in faceDetectionResult) {
            val (croppedBitmap, boundingBox, faceAngleData) = result
            val (embedding, t2) = measureTimedValue {
                // Normalize embedding
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

            // Detect expression
            val (expression, t5) = measureTimedValue {
                detectExpression(croppedBitmap)
            }
            avgT5 += t5.toLong(DurationUnit.MILLISECONDS)

            // Detect age and gender
            val (ageGender, t6) = measureTimedValue {
                detectAgeAndGender(croppedBitmap)
            }
            avgT6 += t6.toLong(DurationUnit.MILLISECONDS)

            val similarityThreshold = 0.6f
            var distance = 1f

            if (recognitionResult != null) {
                distance = cosineDistance(embedding, recognitionResult.faceEmbedding)
                Log.d("CosineDistance", "Similarity with ${recognitionResult.personName}: $distance")
            }

            val personName = if (recognitionResult == null || distance < similarityThreshold) {
                val personID = imagesVectorDB.getCount() + 1
                val newName = "Person_$personID"

                // ✅ Directly add new face without angle stability check
                imagesVectorDB.addFaceImageRecord(
                    FaceImageRecord(
                        personID = personID,
                        personName = newName,
                        faceEmbedding = embedding,
                        gender = ageGender.gender,
                        ageGroup = ageGender.ageGroup,
                        expression = expression,
                        createdAt = System.currentTimeMillis()
                    )
                )
                Log.d("UniqueFace", "✅ New face added -> ID: $personID, Name: $newName")

                newName
            } else {
                Log.d(
                    "UniqueFace",
                    "Matched with existing -> ID: ${recognitionResult.personID}, Name: ${recognitionResult.personName}"
                )
                recognitionResult.personName
            }


            faceRecognitionResults.add(
                FaceRecognitionResult(
                    personName, boundingBox, spoofResult,
                    gender = ageGender.gender,
                    age = ageGender.age,
                    ageGroup = ageGender.ageGroup,
                    expression
                )
            )
        }

        // Update counts
        val storedCount = imagesVectorDB.getCount()
        viewModel.updateFaceCounts(faceDetectionResult.size, storedCount)

        // Calculate expression counts
        val expressionCounts = ExpressionCounts(
            neutralCount = faceRecognitionResults.count { it.expression == "neutral" },
            happyCount = faceRecognitionResults.count { it.expression == "happy" },
            surprisedCount = faceRecognitionResults.count { it.expression == "surprised" },
            sadCount = faceRecognitionResults.count { it.expression == "sad" },
            angerCount = faceRecognitionResults.count { it.expression == "anger" },
            fearCount = faceRecognitionResults.count { it.expression == "fear" },
        )
        viewModel.updateExpressionCounts(expressionCounts)

        // Calculate gender counts
        val genderCounts = GenderCounts(
            maleCount = faceRecognitionResults.count { it.gender == "Male" },
            femaleCount = faceRecognitionResults.count { it.gender == "Female" }
        )
        viewModel.updateGenderCounts(genderCounts)

        // Calculate age group counts
        val ageGroupCounts = AgeGroupCounts(
            childCount = faceRecognitionResults.count { it.ageGroup == "Child (0-14)" },
            youngAdultCount = faceRecognitionResults.count { it.ageGroup == "Young (15-25)" },
            adultCount = faceRecognitionResults.count { it.ageGroup == "Adult (26-55)" },
            elderlyCount = faceRecognitionResults.count { it.ageGroup == "Elderly (56+)" }
        )
        viewModel.updateAgeGroupCounts(ageGroupCounts)

        // Update stored counts (assuming DB methods are implemented)
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
