package com.example.demoapplication.domain.faceDection

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toRectF
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.example.demoapplication.MainActivityViewModel
import com.example.demoapplication.domain.ImageVectorUseCase
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.min
import java.util.concurrent.Executors

@ExperimentalGetImage
@SuppressLint("ViewConstructor")
class FaceDetectionOverlay(
    private val lifecycleOwner: LifecycleOwner,
    private val ctx: Context,
    private val viewModel: MainActivityViewModel,
    private val onFaceCountDetected: ((Int) -> Unit)? = null,
    private val previewOnly: Boolean = false             // true = preview test only (no analysis)
) : FrameLayout(ctx) {

    // ---------- layout / transforms ----------
    private var overlayWidth = 0
    private var overlayHeight = 0

    private var imageTransform = Matrix()
    private var bboxTransform = Matrix()
    private var isImageTransformInit = false
    private var isBboxTransformInit  = false

    // ---------- perf toggles ----------
    private val TARGET_RES = Size(480, 360) // Lower resolution for faster processing
    private val MIN_GAP_MS = 200L // Process at ~5 FPS for detection
    private val RECOG_EVERY = 5 // Run recognition every 5th detected frame
    private val MIRROR_EXTERNAL = true                       // run recognition 1 of every N frames

    // ---------- state / buffers ----------
    private var lastProcTime = 0L
    private var frameIdx = 0
    private var rgbBitmap: Bitmap? = null                // YUV→ARGB target (reused)
    private var rotatedBitmap: Bitmap? = null            // after rotation (reused)
    private var nv21Buf: ByteArray? = null               // reused NV21 buffer
    private var argbBuf: IntArray? = null                // reused ARGB int buffer

    private var isProcessing = false
    private var boundCameraInfo: CameraInfo? = null

    private lateinit var previewView: PreviewView
    private lateinit var bboxOverlay: BoundingBoxOverlay

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(ctx) }
    private val analyzeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var predictions: Array<Prediction> = emptyArray()

    init {
        doOnLayout {
            overlayHeight = it.measuredHeight
            overlayWidth = it.measuredWidth
            initializeCamera()
        }
    }

    // ================= CAMERA SETUP =================

    private fun buildPreview(displayRotation: Int): Preview {
        if (this::previewView.isInitialized) removeView(previewView)
        previewView = PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(previewView)

        return Preview.Builder()
            .setTargetRotation(displayRotation)
            .setTargetResolution(TARGET_RES)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
    }

    private fun ensureOverlay() {
        if (this::bboxOverlay.isInitialized) removeView(bboxOverlay)
        bboxOverlay = BoundingBoxOverlay(ctx).apply {
            setWillNotDraw(false)
            setZOrderOnTop(true)
        }
        addView(bboxOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildAnalysis(displayRotation: Int): ImageAnalysis =
        ImageAnalysis.Builder()
            .setTargetRotation(displayRotation)
            .setTargetResolution(TARGET_RES)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { ia -> ia.setAnalyzer(analyzerExecutor, ::analyzeFrame) }

    /** Bind the first camera that works. (Preview+Analysis share same ViewPort to keep boxes aligned.) */
    @OptIn(ExperimentalLensFacing::class)
    private fun tryBindAnyCamera(
        provider: ProcessCameraProvider,
        displayRotation: Int,
        preview: Preview,
        analysis: ImageAnalysis?
    ): Boolean {
        val infos = provider.availableCameraInfos.toList()
        if (infos.isEmpty()) return false

        fun score(info: CameraInfo) = when (info.lensFacing) {
            CameraSelector.LENS_FACING_EXTERNAL -> 3
            CameraSelector.LENS_FACING_FRONT -> 2
            CameraSelector.LENS_FACING_BACK -> 1
            else -> 0
        }
        val sorted = infos.sortedByDescending { score(it as CameraInfo) }

        for (info in sorted) {
            val selector = CameraSelector.Builder().addCameraFilter { listOf(info) }.build()
            try {
                provider.unbindAll()
                val viewPort = ViewPort.Builder(
                    Rational(overlayWidth, overlayHeight),
                    displayRotation
                ).setScaleType(ViewPort.FIT).build()

                val group = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .apply { if (analysis != null) addUseCase(analysis) }
                    .build()

                provider.bindToLifecycle(lifecycleOwner, selector, group)
                boundCameraInfo = info as CameraInfo
                isBboxTransformInit = false
                Log.i(TAG, "✅ Bound camera: ${facingString(info.lensFacing)}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Bind failed for ${facingString((info as CameraInfo).lensFacing)}; trying next…", e)
            }
        }
        return false
    }

    fun switchCamera() {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get()
            val rot = if (ctx is Activity) ctx.windowManager.defaultDisplay.rotation else Surface.ROTATION_0
            val preview  = buildPreview(rot)
            val analysis = if (previewOnly) null else buildAnalysis(rot)
            val ok = tryBindAnyCamera(p, rot, preview, analysis)
            if (!ok) Log.e(TAG, "No alternate camera available.")
        }, mainExecutor)
    }

    fun initializeCamera() {
        isImageTransformInit = false
        isBboxTransformInit = false

        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                if (provider.availableCameraInfos.isEmpty()) {
                    Log.e(TAG, "❌ No cameras found on this device")
                    showNoCameraMessage()
                    return@addListener
                }

                val rot = if (ctx is Activity) ctx.windowManager.defaultDisplay.rotation else Surface.ROTATION_0
                val preview = buildPreview(rot)
                val analysis = if (previewOnly) null else buildAnalysis(rot)

                ensureOverlay()
                val ok = tryBindAnyCamera(provider, rot, preview, analysis)
                if (!ok) {
                    Log.e(TAG, "❌ Could not bind ANY camera.")
                    showNoCameraMessage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ CameraProvider initialization failed", e)
                showNoCameraMessage()
            }
        }, mainExecutor)
    }


    // ================= ANALYSIS =================

    @OptIn(ExperimentalLensFacing::class)
    private fun analyzeFrame(image: ImageProxy) {
        if (previewOnly || isProcessing || (System.currentTimeMillis() - lastProcTime) < MIN_GAP_MS) {
            image.close()
            return
        }
        lastProcTime = System.currentTimeMillis()
        isProcessing = true
        frameIdx++

        try {
            // Convert YUV -> ARGB
            val w = image.width
            val h = image.height
            ensureBuffers(w, h)

            if (image.format == ImageFormat.YUV_420_888) {
                packToNV21(image, nv21Buf!!)
                yuvToArgb(nv21Buf!!, argbBuf!!, w, h)
                rgbBitmap!!.setPixels(argbBuf!!, 0, w, 0, 0, w, h)
            } else {
                val buf: ByteBuffer = image.planes[0].buffer
                buf.rewind()
                rgbBitmap!!.copyPixelsFromBuffer(buf)
            }

            // Rotate bitmap
            val rotation = image.imageInfo.rotationDegrees
            val dstW = if (rotation % 180 == 0) w else h
            val dstH = if (rotation % 180 == 0) h else w
            ensureRotatedBitmap(dstW, dstH)

            val matrix = Matrix().apply { postRotate(rotation.toFloat(), w / 2f, h / 2f) }
            val canvas = Canvas(rotatedBitmap!!)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            when (rotation) {
                90 -> canvas.translate((dstW - h).toFloat(), 0f)
                180 -> canvas.translate((dstW - w).toFloat(), (dstH - h).toFloat())
                270 -> canvas.translate(0f, (dstH - w).toFloat())
            }
            canvas.drawBitmap(rgbBitmap!!, matrix, null)

            val finalBitmap = rotatedBitmap!!

            if (!isBboxTransformInit) {
                val facing = boundCameraInfo?.lensFacing
                val needMirror =
                    (facing == CameraSelector.LENS_FACING_FRONT) ||
                            (facing == CameraSelector.LENS_FACING_EXTERNAL && MIRROR_EXTERNAL)

                bboxTransform = computeFitCenterMatrix(
                    srcW = finalBitmap.width.toFloat(),
                    srcH = finalBitmap.height.toFloat(),
                    viewW = overlayWidth.toFloat(),
                    viewH = overlayHeight.toFloat(),
                    mirror = needMirror
                )
                isBboxTransformInit = true
            }

            // Perform face detection (and optional recognition)
            analyzeScope.launch {
                val doRecog = (frameIdx % RECOG_EVERY == 0)
                val (metrics, results) = if (doRecog) {
                    viewModel.imageVectorUseCase.getNearestPersonName(finalBitmap, viewModel)
                } else {
                    // Perform only face detection for faster processing
                    val faceDetectionResult = viewModel.imageVectorUseCase.mediapipeFaceDetector.getAllCroppedFacesWithAngle(finalBitmap)
                    val results = faceDetectionResult.map { (croppedBitmap, boundingBox, _) ->
                        ImageVectorUseCase.FaceRecognitionResult(
                            personName = "Detecting...",
                            boundingBox = boundingBox
                        )
                    }
                    Pair(null, results)
                }

                val preds = ArrayList<Prediction>(results.size)
                results.forEach { r ->
                    val box = r.boundingBox.toRectF()
                    bboxTransform.mapRect(box)
                    val label = if (doRecog && r.personName != "Detecting...") r.personName else ""
                    preds.add(Prediction(box, label))
                }

                withContext(Dispatchers.Main) {
                    viewModel.setMetrics(metrics)
                    predictions = preds.toTypedArray()
                    onFaceCountDetected?.invoke(predictions.size)
                    bboxOverlay.invalidate()
                    isProcessing = false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyzeFrame error", t)
            isProcessing = false
        } finally {
            image.close()
        }
    }

    // ================== FIT_CENTER mapping ==================
    private fun computeFitCenterMatrix(
        srcW: Float, srcH: Float, viewW: Float, viewH: Float, mirror: Boolean
    ): Matrix {
        val scale = min(viewW / srcW, viewH / srcH)
        val dx = (viewW - srcW * scale) / 2f
        val dy = (viewH - srcH * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
//            if (mirror) postScale(-1f, 1f, viewW / 2f, viewH / 2f)
        }
    }

    // ================== buffers / converters ==================
    private fun ensureBuffers(w: Int, h: Int) {
        if (rgbBitmap == null || rgbBitmap!!.width != w || rgbBitmap!!.height != h) {
            rgbBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val needNv21 = (w * h * 3) / 2
        if (nv21Buf == null || nv21Buf!!.size != needNv21) {
            nv21Buf = ByteArray(needNv21)
        }
        val needArgb = w * h
        if (argbBuf == null || argbBuf!!.size != needArgb) {
            argbBuf = IntArray(needArgb)
        }
    }

    private fun ensureRotatedBitmap(w: Int, h: Int) {
        if (rotatedBitmap == null || rotatedBitmap!!.width != w || rotatedBitmap!!.height != h) {
            rotatedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
    }

    private fun packToNV21(image: ImageProxy, out: ByteArray) {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val w = image.width
        val h = image.height

        val ySize = w * h
        y.get(out, 0, ySize)

        val chromaRowStride = image.planes[1].rowStride
        val chromaPixelStride = image.planes[1].pixelStride
        val vBytes = ByteArray(v.remaining()).also { v.get(it) }
        val uBytes = ByteArray(u.remaining()).also { u.get(it) }

        var offset = ySize
        for (row in 0 until h / 2) {
            var col = 0
            while (col < w / 2) {
                val vuIndex = row * chromaRowStride + col * chromaPixelStride
                val vv = if (vuIndex < vBytes.size) vBytes[vuIndex] else 0
                val uu = if (vuIndex < uBytes.size) uBytes[vuIndex] else 0
                out[offset++] = vv
                out[offset++] = uu
                col++
            }
        }
    }

    private fun yuvToArgb(nv21: ByteArray, out: IntArray, width: Int, height: Int) {
        var yp = 0
        var uvp: Int
        var u = 0
        var v = 0
        for (j in 0 until height) {
            uvp = width * height + (j shr 1) * width
            var uvi = 0
            for (i in 0 until width) {
                val Y = (0xff and nv21[yp].toInt()) - 16
                if ((i and 1) == 0) {
                    v = (0xff and nv21[uvp + uvi].toInt()) - 128
                    u = (0xff and nv21[uvp + uvi + 1].toInt()) - 128
                    uvi += 2
                }
                val y1192 = 1192 * (if (Y < 0) 0 else Y)
                var r = y1192 + 1634 * v
                var g = y1192 - 833 * v - 400 * u
                var b = y1192 + 2066 * u
                r = r.coerceIn(0, 262143)
                g = g.coerceIn(0, 262143)
                b = b.coerceIn(0, 262143)
                out[yp] = (0xff000000.toInt()
                        or ((r shl 6) and 0xff0000)
                        or ((g shr 2) and 0xff00)
                        or ((b shr 10) and 0xff))
                yp++
            }
        }
    }

    private fun showNoCameraMessage() {
        val textView = android.widget.TextView(ctx).apply {
            text = "No camera available on this device"
            setTextColor(Color.RED)
            textSize = 18f
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            gravity = android.view.Gravity.CENTER
        }
        removeAllViews()
        addView(textView)
    }

    // ================== drawing ==================
    data class Prediction(var bbox: RectF, var label: String)

    inner class BoundingBoxOverlay(context: Context, attrs: AttributeSet? = null) :
        SurfaceView(context, attrs), SurfaceHolder.Callback {

        private val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            strokeWidth = 2.0f
            textSize = 36f
            color = Color.WHITE
            isAntiAlias = true
        }

        override fun surfaceCreated(holder: SurfaceHolder) {}
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {}

        override fun onDraw(canvas: Canvas) {
            predictions.forEach {
                canvas.drawRoundRect(it.bbox, 16f, 16f, boxPaint)
                if (it.label.isNotEmpty()) {
                    canvas.drawText(it.label, it.bbox.left + 8f, it.bbox.top - 8f, textPaint)
                }
            }
        }
    }

    companion object {
        private const val TAG = "FaceDetectionOverlay"

        @OptIn(ExperimentalLensFacing::class)
        private fun facingString(f: Int?): String = when (f) {
            CameraSelector.LENS_FACING_FRONT -> "FRONT"
            CameraSelector.LENS_FACING_BACK -> "BACK"
            CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    }
}
