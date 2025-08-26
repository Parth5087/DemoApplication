package com.example.demoapplication.domain

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.util.Rational
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * A FrameLayout containing:
 * - SurfaceView for CameraX preview
 * - OverlayView (normal View) for drawings
 */
class CameraSurfacePreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context).apply {
        setZOrderOnTop(false) // preview at bottom
        holder.addCallback(this@CameraSurfacePreview)
    }
    private val overlayView = OverlayView(context).apply {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    private var surface: Surface? = null
    private var currentProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraInfo: CameraInfo? = null

    private var pendingBind: (() -> Unit)? = null
    private var addedAnalysisOnce = false

    init {
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
    }

    fun bindWhenReady(provider: ProcessCameraProvider) {
        currentProvider = provider
        if (surface != null) bindPreviewOnly() else {
            pendingBind = { bindPreviewOnly() }
        }
    }

    fun unbindAll() {
        currentProvider?.unbindAll()
        addedAnalysisOnce = false
    }

    // region SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        pendingBind?.invoke()
        pendingBind = null
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // no-op
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
    }
    // endregion

    @OptIn(ExperimentalLensFacing::class)
    private fun bindPreviewOnly() {
        val provider = currentProvider ?: return
        val owner = lifecycleOwner ?: return
        val surf = surface ?: return

        val rotation = display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()

        // Supply CameraX with our Surface from the SurfaceView
        // inside preview.setSurfaceProvider { request -> ... }
        preview.setSurfaceProvider { request ->
            try {
                val holder = surfaceView.holder
                val size = request.resolution
                // 1) Tell SurfaceView to produce a buffer at the exact size CameraX wants
                holder.setFixedSize(size.width, size.height)

                // 2) Make sure we provide the *current* Surface after size is applied
                //    (post to end of queue so SF applies fixed size)
                surfaceView.post {
                    val surf = holder.surface
                    if (surf == null || !surf.isValid) {
                        request.willNotProvideSurface()
                        return@post
                    }
                    request.provideSurface(surf, ContextCompat.getMainExecutor(context)) {
                        // surface released callback (no-op)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "provideSurface failed", t)
                request.willNotProvideSurface()
            }
        }


        val selector = CameraSelector.DEFAULT_BACK_CAMERA // will re-select below if EXTERNAL exists
        provider.unbindAll()

        // Prefer EXTERNAL camera if available
        val externalInfo = provider.availableCameraInfos.firstOrNull {
            (it as CameraInfo).lensFacing == CameraSelector.LENS_FACING_EXTERNAL
        }
        val useSelector = if (externalInfo != null) {
            CameraSelector.Builder().addCameraFilter { listOf(externalInfo) }.build()
        } else selector

        try {
            cameraInfo = provider.bindToLifecycle(owner, useSelector, preview).cameraInfo
            Log.i(TAG, "✅ Bound camera: ${when((cameraInfo as CameraInfo).lensFacing){
                CameraSelector.LENS_FACING_EXTERNAL -> "EXTERNAL"
                CameraSelector.LENS_FACING_FRONT -> "FRONT"
                CameraSelector.LENS_FACING_BACK -> "BACK"
                else -> "UNKNOWN"
            }}")

            // Add analysis once frames are flowing (via Preview's state)
            addAnalysisOnceStreaming(provider, preview, rotation)

        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed", e)
        }
    }

    private fun addAnalysisOnceStreaming(
        provider: ProcessCameraProvider,
        preview: Preview,
        rotation: Int
    ) {
        if (addedAnalysisOnce) return
        addedAnalysisOnce = true

        // Tiny delay helps ExternalCameraProvider settle
        postDelayed({
            try {
                provider.unbindAll()
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                // (No analyzer attached in this preview-only build)

                val owner = lifecycleOwner ?: return@postDelayed
                val useSelector = cameraInfo?.let { info ->
                    CameraSelector.Builder().addCameraFilter { listOf(info) }.build()
                } ?: CameraSelector.DEFAULT_BACK_CAMERA

                provider.bindToLifecycle(owner, useSelector, preview, analysis)
                Log.i(TAG, "📈 Added Analysis after streaming")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to upgrade with analysis", e)
            }
        }, 300) // adjust if needed
    }

    /** Simple overlay that can draw messages or boxes (extend as needed). */
    class OverlayView(context: Context) : View(context) {
        var message: String? = null
            set(value) { field = value; invalidate() }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 44f
            isAntiAlias = true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            message?.let {
                val x = width / 2f
                val y = height / 2f
                val bg = Paint().apply { color = 0x88000000.toInt() }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
                val tw = textPaint.measureText(it)
                canvas.drawText(it, x - tw / 2f, y, textPaint)
            }
        }
    }

    fun showMessage(text: String) {
        overlayView.message = text
    }

    fun clearMessage() {
        overlayView.message = null
    }

    companion object { private const val TAG = "CameraSurfacePreview" }
}