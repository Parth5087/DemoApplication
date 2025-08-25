package com.example.demoapplication.domain.embeddings

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorOperator
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class FaceNet(context: Context, useGpu: Boolean = true, useXNNPack: Boolean = true) {
    private val imgSize = 160
    private val embeddingDim = 512
    private var interpreter: Interpreter
    private val imageTensorProcessor =
        ImageProcessor.Builder()
            .add(ResizeOp(imgSize, imgSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(StandardizeOp())
            .build()

    init {
        val interpreterOptions =
            Interpreter.Options().apply {
                if (useGpu) {
                    if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                        addDelegate(GpuDelegate(CompatibilityList().bestOptionsForThisDevice))
                    }
                } else {
                    numThreads = 4
                }
//                useXNNPACK = useXNNPack
//                useNNAPI = true
            }
        interpreter =
            Interpreter(FileUtil.loadMappedFile(context, "facenet_512.tflite"), interpreterOptions)
    }

    suspend fun getFaceEmbedding(image: Bitmap) =
        withContext(Dispatchers.Main) {
            return@withContext runFaceNet(convertBitmapToBuffer(image))[0]
        }

    private fun runFaceNet(inputs: Any): Array<FloatArray> {
        val faceNetModelOutputs = Array(1) { FloatArray(embeddingDim) }
        interpreter.run(inputs, faceNetModelOutputs)
        return faceNetModelOutputs
    }

    private fun convertBitmapToBuffer(image: Bitmap): ByteBuffer {
        return imageTensorProcessor.process(TensorImage.fromBitmap(image)).buffer
    }

    class StandardizeOp : TensorOperator {
        override fun apply(p0: TensorBuffer?): TensorBuffer {
            val pixels = p0!!.floatArray
            val mean = pixels.average().toFloat()
            var std = sqrt(pixels.map { pi -> (pi - mean).pow(2) }.sum() / pixels.size.toFloat())
            std = max(std, 1f / sqrt(pixels.size.toFloat()))
            for (i in pixels.indices) {
                pixels[i] = (pixels[i] - mean) / std
            }
            val output = TensorBufferFloat.createFixedSize(p0.shape, DataType.FLOAT32)
            output.loadArray(pixels)
            return output
        }
    }
}