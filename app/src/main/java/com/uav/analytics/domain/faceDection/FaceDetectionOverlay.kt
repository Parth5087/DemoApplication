package com.uav.analytics.domain.faceDection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toRectF
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.uav.analytics.MainActivityViewModel
import com.uav.analytics.domain.ImageVectorUseCase
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@ExperimentalGetImage
@SuppressLint("ViewConstructor")
class FaceDetectionOverlay(
    private val lifecycleOwner: LifecycleOwner,
    private val ctx: Context,
    private val viewModel: MainActivityViewModel,
    private val onFaceCountDetected: ((Int) -> Unit)? = null,
    private val previewOnly: Boolean = false
) : FrameLayout(ctx) {

    // ---------- layout / transforms ----------
    private var overlayWidth = 0
    private var overlayHeight = 0

    private var bboxTransform = Matrix()
    private var isBboxTransformInit = false

    // ---------- perf toggles ----------
    private val TARGET_RES = Size(480, 360)
    private val MIN_GAP_MS = 200L
    private val RECOG_EVERY = 5
    private val MIRROR_EXTERNAL = false

    // ---------- buffers / state ----------
    private var lastProcTime = 0L
    private var frameIdx = 0
    private var rgbBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var nv21Buf: ByteArray? = null
    private var argbBuf: IntArray? = null

    private var isProcessing = false
    private var boundCameraInfo: CameraInfo? = null

    private lateinit var previewView: PreviewView
    private lateinit var bboxOverlay: BoundingBoxOverlayView

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val analyzeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(ctx) }

    // CameraX objects
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null

    // Handler (guaranteed non-null)
    private val handler = Handler(Looper.getMainLooper())

    // preview mode fallback
    private var preferredPreviewMode = PreviewView.ImplementationMode.PERFORMANCE
    private var attemptedPreviewFallback = false

    // USB receiver and reinit guard
    private val usbReceiverRegistered = AtomicBoolean(false)
    private val reinitInProgress = AtomicBoolean(false)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUsbCamera(device)) {
                        android.util.Log.i(TAG, "USB attached -> schedule reinit")
                        scheduleReinit()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUsbCamera(device)) {
                        android.util.Log.i(TAG, "USB detached -> schedule reinit")
                        scheduleReinit()
                    }
                }
            }
        }
    }

    var predictions: Array<Prediction> = emptyArray()

    init {
        // measure sizes and start initialization after layout
        doOnLayout {
            overlayHeight = it.measuredHeight
            overlayWidth = it.measuredWidth
            initializeCamera()
            registerUsbReceiver()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            android.util.Log.d(TAG, "layout changed: $left,$top,$right,$bottom")
        }
    }

    // ---------- USB helpers ----------
    private fun scheduleReinit() {
        if (reinitInProgress.getAndSet(true)) {
            // already scheduled/in-progress
            return
        }
        // debounce a bit and attempt reinit/cleanup then re-bind
        handler.postDelayed({
            reinitializeCameraWithRetry()
        }, 800)
    }

    private fun registerUsbReceiver() {
        if (usbReceiverRegistered.get()) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            ctx.registerReceiver(usbReceiver, filter)
            usbReceiverRegistered.set(true)
            android.util.Log.i(TAG, "USB receiver registered")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "registerUsbReceiver failed: ${e.message}")
        }
    }

    private fun unregisterUsbReceiver() {
        if (!usbReceiverRegistered.getAndSet(false)) return
        try {
            ctx.unregisterReceiver(usbReceiver)
            android.util.Log.i(TAG, "USB receiver unregistered")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "unregisterUsbReceiver failed: ${e.message}")
        }
    }

    private fun isUsbCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == 14) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    // ---------- build preview/overlay ----------
    private fun buildPreview(displayRotation: Int): Preview {
        if (this::previewView.isInitialized) {
            try { removeView(previewView) } catch (_: Exception) {}
        }
        previewView = PreviewView(ctx).apply {
            implementationMode = preferredPreviewMode
            scaleType = PreviewView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        addView(previewView, 0) // behind overlay
        val newPreview = Preview.Builder()
            .setTargetRotation(displayRotation)
            .setTargetResolution(TARGET_RES)
            .build()
        preview = newPreview
        return newPreview
    }

    private fun ensureOverlay() {
        if (this::bboxOverlay.isInitialized) {
            try { removeView(bboxOverlay) } catch (_: Exception) {}
        }
        bboxOverlay = BoundingBoxOverlayView(ctx)
        bboxOverlay.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(bboxOverlay)
    }

    // Simple canvas-based overlay (NOT SurfaceView)
    inner class BoundingBoxOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
        private val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            predictions.forEach { pred ->
                canvas.drawRoundRect(pred.bbox, 16f, 16f, boxPaint)
                if (pred.label.isNotEmpty()) {
                    canvas.drawText(pred.label, pred.bbox.left + 8f, pred.bbox.top - 8f, textPaint)
                }
            }
        }
    }

    // ---------- analysis ----------
    @SuppressLint("UnsafeOptInUsageError")
    private fun buildAnalysis(displayRotation: Int): ImageAnalysis {
        val newAnalysis = ImageAnalysis.Builder()
            .setTargetRotation(displayRotation)
            .setTargetResolution(TARGET_RES)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { ia -> ia.setAnalyzer(analyzerExecutor, ::analyzeFrame) }
        analysis = newAnalysis
        return newAnalysis
    }

    @OptIn(ExperimentalLensFacing::class)
    private fun tryBindAnyCamera(provider: ProcessCameraProvider, displayRotation: Int, preview: Preview, analysis: ImageAnalysis?): Boolean {
        val infos = provider.availableCameraInfos.toList()
        if (infos.isEmpty()) {
            android.util.Log.w(TAG, "No camera infos available")
            return false
        }

        android.util.Log.d(TAG, "Available cameras: ${infos.size}")
        infos.forEachIndexed { i, info -> android.util.Log.d(TAG, "Camera $i: ${facingString((info as CameraInfo).lensFacing)}") }

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
                val useCases = mutableListOf<UseCase>(preview)
                if (analysis != null) useCases.add(analysis)
                val cam = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
                camera = cam
                boundCameraInfo = info as CameraInfo
                isBboxTransformInit = false

                // Some devices need setSurfaceProvider after bind
                try {
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "setSurfaceProvider failed after bind: ${e.message}")
                }

                android.util.Log.i(TAG, "Bound camera: ${facingString(info.lensFacing)}")
                // delayed check to toggle preview mode if needed
                handler.postDelayed({
                    val st = previewView.previewStreamState.value
                    android.util.Log.d(TAG, "delayed preview state: $st (mode=${previewView.implementationMode})")
                    if (st == PreviewView.StreamState.IDLE && !attemptedPreviewFallback) {
                        attemptedPreviewFallback = true
                        preferredPreviewMode = if (preferredPreviewMode == PreviewView.ImplementationMode.PERFORMANCE)
                            PreviewView.ImplementationMode.COMPATIBLE else PreviewView.ImplementationMode.PERFORMANCE
                        restartPreview()
                    }
                }, 900)
                return true
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Bind failed for ${facingString((info as CameraInfo).lensFacing)}: ${e.message}")
            }
        }
        return false
    }

    private fun reinitializeCameraWithRetry() {
        cleanupCamera()
        // simple retry schedule
        val delays = longArrayOf(300L, 800L, 1700L)
        var attempt = 0
        val r = object : Runnable {
            override fun run() {
                if (attempt >= delays.size) {
                    reinitInProgress.set(false)
                    android.util.Log.e(TAG, "reinit attempts exhausted")
                    return
                }
                android.util.Log.d(TAG, "reinit attempt ${attempt + 1}")
                initializeCamera()
                handler.postDelayed({
                    val st = if (this@FaceDetectionOverlay::previewView.isInitialized) previewView.previewStreamState.value else null
                    if (st != PreviewView.StreamState.STREAMING) {
                        attempt++
                        handler.postDelayed(this, delays.getOrNull(attempt) ?: 1000L)
                    } else {
                        android.util.Log.i(TAG, "Preview streaming after attempt ${attempt + 1}")
                        reinitInProgress.set(false)
                    }
                }, 1000L)
            }
        }
        handler.postDelayed(r, 300L)
    }

    private fun cleanupCamera() {
        try {
            // Unbind & clear
            cameraProvider?.unbindAll()
            analysis?.clearAnalyzer()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cleanupCamera unbind error: ${e.message}")
        }
        camera = null
        analysis = null
        preview = null
        boundCameraInfo = null
        isBboxTransformInit = false

        try {
            if (this::previewView.isInitialized) removeView(previewView)
        } catch (_: Exception) {}
        try {
            if (this::bboxOverlay.isInitialized) removeView(bboxOverlay)
        } catch (_: Exception) {}

        // Do not shutdown the analyzerExecutor yet if you intend to reuse; only on full destroy.
        android.util.Log.d(TAG, "camera cleaned up")
    }

    fun initializeCamera() {
        attemptedPreviewFallback = false
        android.util.Log.d(TAG, "Initializing camera (mode=$preferredPreviewMode)")

        // permission
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w(TAG, "Camera permission missing")
            if (ctx is Activity) {
                ActivityCompat.requestPermissions(ctx, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            } else {
                showNoCameraMessage("Camera permission required")
            }
            return
        }

        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                if (provider.availableCameraInfos.isEmpty()) {
                    android.util.Log.e(TAG, "No cameras available")
                    showNoCameraMessage()
                    reinitInProgress.set(false)
                    return@addListener
                }

                val rot = if (ctx is Activity) ctx.windowManager.defaultDisplay.rotation else Surface.ROTATION_0
                val p = buildPreview(rot)
                val a = if (previewOnly) null else buildAnalysis(rot)
                ensureOverlay()

                // bind when lifecycle ready
                bindWhenLifecycleReady {
                    val ok = tryBindAnyCamera(provider, rot, p, a)
                    if (!ok) {
                        android.util.Log.e(TAG, "Could not bind camera")
                        showNoCameraMessage()
                        reinitInProgress.set(false)
                    } else {
                        android.util.Log.i(TAG, "Camera initialized")
                        observePreviewState()
                        // small delayed check/fixes
                        handler.postDelayed({ checkAndFixPreview() }, 1000)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "CameraProvider init failed: ${e.message}")
                showNoCameraMessage()
                reinitInProgress.set(false)
            }
        }, mainExecutor)
    }

    private fun observePreviewState() {
        if (!this::previewView.isInitialized) return
        previewView.previewStreamState.observe(lifecycleOwner) { state ->
            android.util.Log.d(TAG, "Preview state: $state, attached=${previewView.isAttachedToWindow}, dims=${previewView.width}x${previewView.height}")
            when (state) {
                PreviewView.StreamState.STREAMING -> {
                    android.util.Log.i(TAG, "Preview streaming")
                }
                PreviewView.StreamState.IDLE -> {
                    android.util.Log.w(TAG, "Preview idle")
                }
            }
        }
    }

    private fun bindWhenLifecycleReady(bindAction: () -> Unit) {
        val lifecycle = lifecycleOwner.lifecycle
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            bindAction()
            return
        }
        val obs = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lifecycle.removeObserver(this)
                bindAction()
            }
        }
        lifecycle.addObserver(obs)
    }

    private fun checkAndFixPreview() {
        if (!this::previewView.isInitialized) return
        if (previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) {
            android.util.Log.w(TAG, "Preview not streaming -> restart/fallback sequence")
            handler.postDelayed({ restartPreview() }, 500)
            handler.postDelayed({ togglePreviewMode() }, 1200)
            handler.postDelayed({ forceSurfaceProviderReset() }, 1800)
        }
    }

    private fun restartPreview() {
        val prov = cameraProvider ?: return
        try {
            prov.unbindAll()
            val rot = if (ctx is Activity) ctx.windowManager.defaultDisplay.rotation else Surface.ROTATION_0
            val newPreview = buildPreview(rot)
            val newAnalysis = if (previewOnly) null else buildAnalysis(rot)
            ensureOverlay()
            tryBindAnyCamera(prov, rot, newPreview, newAnalysis)
            android.util.Log.i(TAG, "restartPreview attempted")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "restartPreview error: ${e.message}")
        }
    }

    private fun togglePreviewMode() {
        if (!this::previewView.isInitialized) return
        preferredPreviewMode = if (preferredPreviewMode == PreviewView.ImplementationMode.PERFORMANCE)
            PreviewView.ImplementationMode.COMPATIBLE else PreviewView.ImplementationMode.PERFORMANCE
        previewView.implementationMode = preferredPreviewMode
        android.util.Log.d(TAG, "toggled preview mode to $preferredPreviewMode")
    }

    private fun forceSurfaceProviderReset() {
        val cur = preview ?: return
        try {
            cur.surfaceProvider = null
            handler.postDelayed({
                try {
                    cur.surfaceProvider = previewView.surfaceProvider
                    android.util.Log.d(TAG, "surfaceProvider reset succeeded")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "surfaceProvider reset failed: ${e.message}")
                }
            }, 300)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "forceSurfaceProviderReset error: ${e.message}")
        }
    }

    // ---------- image analysis ----------
    @OptIn(ExperimentalLensFacing::class)
    private fun analyzeFrame(image: ImageProxy) {
//        android.util.Log.d(TAG, "Analyzing frame ${image.width}x${image.height}, fmt=${image.format}")
        if (previewOnly || isProcessing || (System.currentTimeMillis() - lastProcTime) < MIN_GAP_MS) {
            image.close(); return
        }
        lastProcTime = System.currentTimeMillis()
        isProcessing = true
        frameIdx++

        try {
            val w = image.width; val h = image.height
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
                val needMirror = (facing == CameraSelector.LENS_FACING_FRONT) || (facing == CameraSelector.LENS_FACING_EXTERNAL && MIRROR_EXTERNAL)
                bboxTransform = computeFitCenterMatrix(finalBitmap.width.toFloat(), finalBitmap.height.toFloat(), overlayWidth.toFloat(), overlayHeight.toFloat(), needMirror)
                isBboxTransformInit = true
            }

            analyzeScope.launch {
                try {
                    val doRecog = (frameIdx % RECOG_EVERY == 0)
                    val (metrics, results) = if (doRecog) {
                        viewModel.imageVectorUseCase.getNearestPersonName(finalBitmap, viewModel)
                    } else {
                        val faceDetectionResult = viewModel.imageVectorUseCase.mediapipeFaceDetector.getAllCroppedFacesWithAngle(finalBitmap)
                        val results = faceDetectionResult.map { (croppedBitmap, boundingBox, _) ->
                            ImageVectorUseCase.FaceRecognitionResult(personName = "Detecting...", boundingBox = boundingBox)
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
                        if (this@FaceDetectionOverlay::bboxOverlay.isInitialized) bboxOverlay.invalidate()
                        isProcessing = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "analysis coroutine error: ${e.message}")
                    withContext(Dispatchers.Main) { isProcessing = false }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "analyzeFrame error", t)
            isProcessing = false
        } finally {
            image.close()
        }
    }

    // ---------- helpers ----------
    private fun computeFitCenterMatrix(srcW: Float, srcH: Float, viewW: Float, viewH: Float, mirror: Boolean): Matrix {
        val scale = minOf(viewW / srcW, viewH / srcH)
        val dx = (viewW - srcW * scale) / 2f
        val dy = (viewH - srcH * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
            if (mirror) postScale(-1f, 1f, viewW / 2f, viewH / 2f)
        }
    }

    private fun ensureBuffers(w: Int, h: Int) {
        if (rgbBitmap == null || rgbBitmap!!.width != w || rgbBitmap!!.height != h)
            rgbBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val needNv21 = (w * h * 3) / 2
        if (nv21Buf == null || nv21Buf!!.size != needNv21) nv21Buf = ByteArray(needNv21)
        val needArgb = w * h
        if (argbBuf == null || argbBuf!!.size != needArgb) argbBuf = IntArray(needArgb)
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
        val w = image.width; val h = image.height
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
        var u = 0; var v = 0
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

    private fun showNoCameraMessage(message: String = "No camera available on this device") {
        try {
            val textView = TextView(ctx).apply {
                text = message
                setTextColor(Color.RED)
                textSize = 18f
                setBackgroundColor(Color.argb(160, 0, 0, 0))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                gravity = android.view.Gravity.CENTER
            }
            removeAllViews()
            addView(textView)
        } catch (e: Exception) { android.util.Log.w(TAG, "showNoCameraMessage failed: ${e.message}") }
    }

    // Proper cleanup to call from parent (Activity/Fragment) when overlay is destroyed
    fun cleanup() {
        try {
            unregisterUsbReceiver()
            analyzeScope.cancel()
            analyzerExecutor.shutdown()
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "cleanup error: ${e.message}")
        } finally {
            camera = null
            cameraProvider = null
            analysis = null
            preview = null
            try { if (this::previewView.isInitialized) removeView(previewView) } catch (_: Exception) {}
            try { if (this::bboxOverlay.isInitialized) removeView(bboxOverlay) } catch (_: Exception) {}
        }
    }

    @OptIn(ExperimentalLensFacing::class)
    private fun facingString(f: Int?): String = when (f) {
        CameraSelector.LENS_FACING_FRONT -> "FRONT"
        CameraSelector.LENS_FACING_BACK -> "BACK"
        CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    data class Prediction(var bbox: RectF, var label: String)

    companion object {
        private const val TAG = "FaceDetectionOverlay"
        private const val REQUEST_CAMERA = 101
    }
}