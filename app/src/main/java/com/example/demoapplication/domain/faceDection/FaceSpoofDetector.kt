package com.example.demoapplication.domain.faceDection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import kotlin.math.exp
import kotlin.time.DurationUnit
import kotlin.time.measureTime

class FaceSpoofDetector(
    context: Context,
    useGpu: Boolean = false,
    useXNNPack: Boolean = false,
    useNNAPI: Boolean = false
) {

    data class FaceSpoofResult(val isSpoof: Boolean, val score: Float, val timeMillis: Long)

    private val scale1 = 2.7f
    private val scale2 = 4.0f
    private val inputImageDim = 80
    private val outputDim = 3

    private var firstModelInterpreter: Interpreter
    private var secondModelInterpreter: Interpreter
    private val imageTensorProcessor = ImageProcessor.Builder()
        .add(CastOp(DataType.FLOAT32))
        .build()

    init {
        val interpreterOptions = Interpreter.Options().apply {
            if (useGpu) {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                    val delegateOptions = GpuDelegateFactory.Options()
                    addDelegate(GpuDelegate(delegateOptions))
                }
            } else {
                numThreads = 4
            }
            this.useXNNPACK = useXNNPack
            this.useNNAPI = useNNAPI
        }

        // Load spoof detection models
        firstModelInterpreter = Interpreter(
            FileUtil.loadMappedFile(context, "spoof_model_scale_2_7.tflite"),
            interpreterOptions
        )
        secondModelInterpreter = Interpreter(
            FileUtil.loadMappedFile(context, "spoof_model_scale_4_0.tflite"),
            interpreterOptions
        )
    }

    /**
     * Detect if the face is spoofed from the given image and bounding box.
     */
    suspend fun detectSpoof(frameImage: Bitmap, faceRect: Rect): FaceSpoofResult =
        withContext(Dispatchers.Default) {
            // Preprocess images for two different scales
            val croppedImage1 = crop(frameImage, faceRect, scale1, inputImageDim, inputImageDim)
            convertRgbToBgr(croppedImage1)

            val croppedImage2 = crop(frameImage, faceRect, scale2, inputImageDim, inputImageDim)
            convertRgbToBgr(croppedImage2)

            val input1 = imageTensorProcessor.process(TensorImage.fromBitmap(croppedImage1)).buffer
            val input2 = imageTensorProcessor.process(TensorImage.fromBitmap(croppedImage2)).buffer
            val output1 = arrayOf(FloatArray(outputDim))
            val output2 = arrayOf(FloatArray(outputDim))

            val time = measureTime {
                firstModelInterpreter.run(input1, output1)
                secondModelInterpreter.run(input2, output2)
            }.toLong(DurationUnit.MILLISECONDS)

            val output = softMax(output1[0]).zip(softMax(output2[0])) { a, b -> a + b }
            val label = output.indexOf(output.maxOrNull() ?: 0f)
            val isSpoof = label != 1
            val score = output[label] / 2f

            return@withContext FaceSpoofResult(isSpoof = isSpoof, score = score, timeMillis = time)
        }

    /**
     * Apply softmax to output logits.
     */
    private fun softMax(x: FloatArray): FloatArray {
        val expValues = x.map { exp(it) }
        val expSum = expValues.sum()
        return expValues.map { it / expSum }.toFloatArray()
    }

    /**
     * Convert RGB image to BGR.
     */
    private fun convertRgbToBgr(bitmap: Bitmap) {
        for (i in 0 until bitmap.width) {
            for (j in 0 until bitmap.height) {
                bitmap.setPixel(
                    i, j, Color.rgb(
                        Color.blue(bitmap.getPixel(i, j)),
                        Color.green(bitmap.getPixel(i, j)),
                        Color.red(bitmap.getPixel(i, j))
                    )
                )
            }
        }
    }

    /**
     * Crop the image with scaling and resize to target size.
     */
    private fun crop(
        origImage: Bitmap,
        bbox: Rect,
        bboxScale: Float,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val srcWidth = origImage.width
        val srcHeight = origImage.height
        val scaledBox = getScaledBox(srcWidth, srcHeight, bbox, bboxScale)
        val croppedBitmap = Bitmap.createBitmap(
            origImage,
            scaledBox.left,
            scaledBox.top,
            scaledBox.width(),
            scaledBox.height()
        )
        return Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)
    }

    /**
     * Get scaled bounding box coordinates.
     */
    private fun getScaledBox(srcWidth: Int, srcHeight: Int, box: Rect, bboxScale: Float): Rect {
        val x = box.left
        val y = box.top
        val w = box.width()
        val h = box.height()
        val scale = floatArrayOf((srcHeight - 1f) / h, (srcWidth - 1f) / w, bboxScale).minOrNull() ?: 1f

        val newWidth = w * scale
        val newHeight = h * scale
        val centerX = w / 2 + x
        val centerY = h / 2 + y

        var topLeftX = centerX - newWidth / 2
        var topLeftY = centerY - newHeight / 2
        var bottomRightX = centerX + newWidth / 2
        var bottomRightY = centerY + newHeight / 2

        if (topLeftX < 0) {
            bottomRightX -= topLeftX
            topLeftX = 0f
        }
        if (topLeftY < 0) {
            bottomRightY -= topLeftY
            topLeftY = 0f
        }
        if (bottomRightX > srcWidth - 1) {
            topLeftX -= (bottomRightX - (srcWidth - 1))
            bottomRightX = (srcWidth - 1).toFloat()
        }
        if (bottomRightY > srcHeight - 1) {
            topLeftY -= (bottomRightY - (srcHeight - 1))
            bottomRightY = (srcHeight - 1).toFloat()
        }
        return Rect(topLeftX.toInt(), topLeftY.toInt(), bottomRightX.toInt(), bottomRightY.toInt())
    }
}