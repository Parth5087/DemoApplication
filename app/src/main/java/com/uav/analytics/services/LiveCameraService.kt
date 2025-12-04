package com.uav.analytics.services

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import com.uav.analytics.LauncherActivity
import com.uav.analytics.MainActivityViewModel
import com.uav.analytics.MainActivityViewModelFactory
import com.uav.analytics.RemoteConfigHelper
import com.uav.analytics.domain.analytics.AggregatesSender
import com.uav.analytics.models.DeviceStatusRequest
import com.uav.analytics.utils.NetworkCameraLogger
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LiveCameraDetectService : LifecycleService() {

    companion object {
        private const val TAG = "LiveCameraDetectService"
        private const val NOTIF_CHANNEL = "live_camera_detect_channel"
        private const val NOTIF_ID = 102
        private const val REINIT_DEBOUNCE_MS = 2500L

        // Add device status constants
        private const val STATUS_ACTIVE = "active"
        private const val STATUS_INACTIVE = "inactive"
    }

    // ---------- ViewModel & helpers ----------
    private lateinit var viewModel: MainActivityViewModel

    // ---------- CameraX / analysis ----------
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val analyzeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    // ---------- Headless preview surface to force frames ----------
    // Create lazily when preview.setSurfaceProvider is called
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private val previewProviderExecutor = Executors.newSingleThreadExecutor()
    // ---------- NetworkCameraLogger ----------
    private lateinit var networkCameraLogger: NetworkCameraLogger

    // ---------- Reused buffers ----------
    private var nv21Buf: ByteArray? = null
    private var argbBuf: IntArray? = null
    private var rgbBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null

    // ---------- throttling / state ----------
    private val MIN_GAP_MS = 200L
    private val RECOG_EVERY = 5
    private var lastProcTime = 0L
    private var frameIdx = 0
    @Volatile
    private var isProcessing = false

    // ---------- Notification / handler ----------
    private val handler = Handler(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- USB receiver and reinit guard ----------
    private val usbReceiverRegistered = AtomicBoolean(false)
    // reinitInProgress indicates we are retrying; reinitScheduledToken used to cancel pending runnable
    private val reinitInProgress = AtomicBoolean(false)
    private val bindInProgress = AtomicBoolean(false)
    private var reinitScheduledToken: Runnable? = null
    private var lastSuccessfulBindMs: Long = 0L

    // ---------- Power State Receiver ----------
    private val powerStateReceiverRegistered = AtomicBoolean(false)
    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "Screen turned off - stopping camera")
                    networkCameraLogger.logCameraDisconnect(cameraId, "Screen turned off")
                    cleanupCamera()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "Screen turned on - restarting camera with delay")
                    val km = getSystemService(KeyguardManager::class.java)
                    val isLocked = km?.isDeviceLocked ?: false
                    handler.postDelayed({
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (isLocked) {
                                Log.i(TAG, "Screen on but device locked - deferring until user present")
                            } else {
                                scheduleReinit()
                            }
                        }
                    }, 1500)
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "User unlocked device - starting camera")
                    lifecycleScope.launch(Dispatchers.Main) {
                        scheduleReinit()
                    }
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "USB receiver got action: $action")
            when (action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUsbCamera(device)) {
                        Log.i(TAG, "USB camera detached -> cleaning up camera resources")
                        networkCameraLogger.logCameraDisconnect(cameraId, "USB camera detached: ${device.deviceName}")
                        cleanupCamera()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isUsbCamera(device)) {
                        Log.i(TAG, "USB camera attached -> reinitializing camera")
                        scheduleReinit()
                    }
                }
            }
        }
    }

    // ---------- periodic sending job ----------
    private var uploadIntervalMillis: Long = 60_000L
    private var senderJob: Job? = null

    // ---------- USER_UNLOCKED handling ----------
    private var waitingForUserUnlock = AtomicBoolean(false)
    private var userUnlockReceiverRegistered = AtomicBoolean(false)
    private val userUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "ACTION_USER_UNLOCKED received - starting camera init if needed")
            if (userUnlockReceiverRegistered.getAndSet(false)) {
                try { unregisterReceiver(this) } catch (_: Exception) {}
            }
            waitingForUserUnlock.set(false)
            lifecycleScope.launch(Dispatchers.Main) { startCameraHeadless() }
        }
    }

    // ---------- CameraManager availability diagnostics ----------
    private var cameraAvailabilityRegistered = AtomicBoolean(false)
    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            Log.i(TAG, "CameraManager: camera AVAILABLE -> id=$cameraId")
            val now = System.currentTimeMillis()
            val justBoundRecently = (now - lastSuccessfulBindMs) < REINIT_DEBOUNCE_MS
            if (imageAnalysis == null && !reinitInProgress.get() && !justBoundRecently) {
                Log.i(TAG, "Camera became available — scheduling reinit")
                scheduleReinit()
            } else {
                Log.d(TAG, "Camera available ignored (imageAnalysis!=null=${imageAnalysis != null}, reinitInProgress=${reinitInProgress.get()}, justBoundRecently=$justBoundRecently)")
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            Log.i(TAG, "CameraManager: camera UNAVAILABLE -> id=$cameraId")
        }
    }

    // ---------- Device Status Management ----------
    private var deviceId: String = ""
    private var cameraId: String = ""
    private var isDeviceStatusActive = false

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created")

        // Initialize NetworkCameraLogger
        networkCameraLogger = NetworkCameraLogger(this)

        // Initialize device and camera IDs
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        deviceId = getAndroidId(this) ?: "Unavailable"
        cameraId = prefs.getString("camera_id", "camera1") ?: "camera1"

        // Start as foreground service immediately to prevent ANR
        createNotificationChannel()
        try {
            startForeground(NOTIF_ID, buildNotificationSafe())
            Log.d(TAG, "Foreground service started (notification posted)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
        }

        // Register receivers
        registerPowerStateReceiver()
        registerUsbReceiver()

        // Register CameraManager availability callback
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (!cameraAvailabilityRegistered.getAndSet(true)) {
                cm.registerAvailabilityCallback(cameraAvailabilityCallback, handler)
                Log.d(TAG, "Camera availability callback registered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register camera availability callback: ${e.message}")
        }

        // Initialize ViewModel in background
        lifecycleScope.launch(Dispatchers.IO) {
            initializeViewModel()
        }

        // Fetch RemoteConfig in background
        lifecycleScope.launch(Dispatchers.IO) {
            fetchRemoteConfig()
        }

        // Check device lock state and start camera
        val km = getSystemService(KeyguardManager::class.java)
        val isLocked = km?.isDeviceLocked ?: false
        if (isLocked) {
            Log.i(TAG, "Device is locked at boot - deferring camera start until ACTION_USER_UNLOCKED")
            waitingForUserUnlock.set(true)
            if (!userUnlockReceiverRegistered.getAndSet(true)) {
                registerReceiver(userUnlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            }
        } else {
            // Start camera with delay to prevent ANR during startup
            handler.postDelayed({
                lifecycleScope.launch(Dispatchers.Main) {
                    startCameraHeadless()
                }
            }, 25000)
        }

        Log.d(TAG, "Service onCreate completed")
    }

    private fun getAndroidId(context: Context): String? {
        return try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            Log.d(TAG, "Android ID: $androidId")
            androidId
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving Android ID", e)
            null
        }
    }

    private suspend fun initializeViewModel() {
        try {
            withContext(Dispatchers.Main) {
                val factory = MainActivityViewModelFactory(this@LiveCameraDetectService)
                viewModel = factory.create(MainActivityViewModel::class.java)
                observeViewModelStates()
                Log.d(TAG, "ViewModel initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ViewModel: ${e.message}")
        }
    }

    private fun fetchRemoteConfig() {
        try {
            RemoteConfigHelper.fetchAndActivate {
                uploadIntervalMillis = RemoteConfigHelper.uploadIntervalDataMs()
                Log.d(TAG, "Upload interval set to: $uploadIntervalMillis ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "RemoteConfig fetch failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand: Service starting with intent: ${intent?.action}")

        // Handle power state commands
        when (intent?.action) {
            "START_CAMERA" -> {
                Log.i(TAG, "Starting camera from power state change")
                lifecycleScope.launch(Dispatchers.Main) {
                    startCameraHeadless()
                }
            }
            "STOP_CAMERA" -> {
                Log.i(TAG, "Stopping camera from power state change")
                cleanupCamera()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service shutting down")

        // Send inactive status when service is destroyed
        updateDeviceStatusInactive()

        stopPeriodicUpload()
        cleanupCamera()
        analyzeScope.cancel()
        analyzerExecutor.shutdown()
        unregisterUsbReceiver()
        unregisterPowerStateReceiver()

        if (userUnlockReceiverRegistered.get()) {
            try { unregisterReceiver(userUnlockReceiver) } catch (_: Exception) {}
            userUnlockReceiverRegistered.set(false)
        }

        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (cameraAvailabilityRegistered.getAndSet(false)) {
                cm.unregisterAvailabilityCallback(cameraAvailabilityCallback)
                Log.d(TAG, "Camera availability callback unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister camera availability callback: ${e.message}")
        }

        try { previewProviderExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroy()
        Log.d(TAG, "Service cleanup completed")
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: Service bound")
        super.onBind(intent)
        return null
    }

    // ---------------- Device Status API ----------------
    private suspend fun sendDeviceStatus(status: String) {
        try {
            withContext(Dispatchers.IO) {
                val request = DeviceStatusRequest(
                    cameraId = cameraId,
                    deviceId = deviceId,
                    status = status
                )

                val response = NetworkModule.api.deviceStatus(request)
                if (response.isSuccessful) {
                    Log.d(TAG, "Device status updated to: $status")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Failed to update device status:  ${response.code()} ${response.message()}.\nError Body: $errorBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device status: ${e.message}")
        }
    }

    private fun updateDeviceStatusActive() {
        if (!isDeviceStatusActive) {
            isDeviceStatusActive = true
            lifecycleScope.launch {
                networkCameraLogger.logCameraConnect(cameraId, "Camera connected")
                sendDeviceStatus(STATUS_ACTIVE)
            }
        }
    }

    private fun updateDeviceStatusInactive() {
        if (isDeviceStatusActive) {
            isDeviceStatusActive = false
            lifecycleScope.launch {
                networkCameraLogger.logCameraDisconnect(cameraId, "Camera disconnected")
                sendDeviceStatus(STATUS_INACTIVE)
            }
        }
    }

    // ---------------- Power State Receiver ----------------
    private fun registerPowerStateReceiver() {
        if (powerStateReceiverRegistered.getAndSet(true)) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(powerStateReceiver, filter)
            Log.i(TAG, "Power state receiver registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "registerPowerStateReceiver failed: ${e.message}")
        }
    }

    private fun unregisterPowerStateReceiver() {
        if (!powerStateReceiverRegistered.getAndSet(false)) return
        try {
            unregisterReceiver(powerStateReceiver)
            Log.i(TAG, "Power state receiver unregistered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "unregisterPowerStateReceiver failed: ${e.message}")
        }
    }

    // ---------------- ViewModel observers ----------------
    private fun observeViewModelStates() {
        Log.d(TAG, "Setting up ViewModel observers")
        try {
            viewModel.faceCountsState.observe(this) { counts ->
                Log.d(TAG, "Faces=${counts.detectedCount}, Stored=${counts.storedCount}")
            }
            viewModel.storedGenderCountsState.observe(this) { counts ->
                Log.d(TAG, "Stored Male=${counts.maleCount}, Female=${counts.femaleCount}")
            }
            viewModel.storedAgeGroupCountsState.observe(this) { counts ->
                Log.d(TAG, "AgeGroups Child=${counts.childCount}, Young=${counts.youngAdultCount}, Adult=${counts.adultCount}, Elderly=${counts.elderlyCount}")
            }
            viewModel.storedExpressionCountsState.observe(this) { counts ->
                val total = counts.neutralCount + counts.happyCount + counts.surprisedCount + counts.sadCount + counts.angerCount + counts.fearCount
                fun pct(v: Int) = if (total > 0) (v.toFloat() / total * 100).toInt() else 0
                Log.d(TAG,
                    "Expressions: " +
                            "Neutral=${pct(counts.neutralCount)}%, " +
                            "Happy=${pct(counts.happyCount)}%, " +
                            "Surprised=${pct(counts.surprisedCount)}%, " +
                            "Sad=${pct(counts.sadCount)}%, " +
                            "Anger=${pct(counts.angerCount)}%, " +
                            "Fear=${pct(counts.fearCount)}%"
                )
            }
            // add other observers if required
        } catch (e: Exception) {
            Log.w(TAG, "observeViewModelStates: ${e.message}")
        }
    }

    // ---------------- Camera startup (non-blocking) ----------------
    private suspend fun startCameraHeadless() = withContext(Dispatchers.Main) {
        Log.d(TAG, "startCameraHeadless: Checking camera permission")

        val perm = ContextCompat.checkSelfPermission(this@LiveCameraDetectService, Manifest.permission.CAMERA)
        Log.d(TAG, "Runtime CAMERA permission: $perm (expected ${PackageManager.PERMISSION_GRANTED})")

        if (perm != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission missing - stopping service")
            stopSelf()
            return@withContext
        }

        checkCameraStatus()

        try {
            Log.d(TAG, "startCameraHeadless: Requesting ProcessCameraProvider")
            val providerFuture = ProcessCameraProvider.getInstance(this@LiveCameraDetectService)
            val mainExecutor = ContextCompat.getMainExecutor(this@LiveCameraDetectService)

            providerFuture.addListener({
                try {
                    cameraProvider = providerFuture.get()
                    Log.d(TAG, "ProcessCameraProvider ready")
                    // Schedule bind with delay to prevent ANR
                    mainHandler.postDelayed({
                        performBindAnalysisStep()
                    }, 500L)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to obtain ProcessCameraProvider: ${e.message}")
                    scheduleRetry()
                }
            }, mainExecutor)
        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed: ${e.message}")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        Log.d(TAG, "Scheduling camera retry in 5 seconds")
        handler.postDelayed({
            lifecycleScope.launch(Dispatchers.Main) {
                Log.d(TAG, "Retrying camera setup...")
                startCameraHeadless()
            }
        }, 5000)
    }

    // ---------------- Camera binding (optimized for ANR prevention) ----------------
    private fun performBindAnalysisStep() {
        Log.d(TAG, "performBindAnalysisStep: Starting camera binding process")

        // Clear existing analyzer on background thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                imageAnalysis?.clearAnalyzer()
            } catch (e: Exception) {
                Log.d(TAG, "Error clearing analyzer: ${e.message}")
            }
        }

        // Build ImageAnalysis with minimal configuration
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build().also { ia ->
                ia.setAnalyzer(analyzerExecutor) { imageProxy ->
                    analyzeImageProxy(imageProxy)
                }
            }

        // Prepare Preview with headless surface
        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()

        preview.setSurfaceProvider { request ->
            previewProviderExecutor.execute {
                try {
                    if (previewSurfaceTexture == null) {
                        previewSurfaceTexture = SurfaceTexture(0)
                    }
                    previewSurfaceTexture!!.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                    if (previewSurface == null) {
                        previewSurface = Surface(previewSurfaceTexture)
                    }
                    try {
                        request.provideSurface(previewSurface!!, previewProviderExecutor) { result ->
                            Log.v(TAG, "Preview surface release callback: $result")
                        }
                        Log.d(TAG, "Provided pre-created preview surface for request ${request.resolution}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error while providing surface: ${e.message}")
                        try { request.willNotProvideSurface() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create/prepare preview surface: ${e.message}")
                    try { request.willNotProvideSurface() } catch (_: Exception) {}
                }
            }
        }

        val provider = cameraProvider
        if (provider == null) {
            Log.e(TAG, "performBindAnalysisStep: cameraProvider is null - can't bind")
            scheduleRetry()
            return
        }

        // Try to bind to available cameras
        val cameraInfos = provider.availableCameraInfos.toList()
        Log.d(TAG, "Available camera infos: ${cameraInfos.size}")

        if (cameraInfos.isEmpty()) {
            Log.e(TAG, "No camera infos available - scheduling retry")
            updateDeviceStatusInactive()
            scheduleRetry()
            return
        }

        // Try each camera sequentially with minimal delay
        for ((index, cameraInfo) in cameraInfos.withIndex()) {
            try {
                Log.d(TAG, "Trying camera $index: $cameraInfo")

                if (!bindInProgress.compareAndSet(false, true)) {
                    Log.d(TAG, "Bind already in progress — skipping additional attempt")
                    continue
                }

                provider.unbindAll()

                val selector = CameraSelector.Builder()
                    .addCameraFilter { listOf(cameraInfo) }
                    .build()

                // Perform bind on main thread but with timeout protection
                mainHandler.post {
                    try {
                        provider.bindToLifecycle(this@LiveCameraDetectService, selector, preview, imageAnalysis)
                        Log.i(TAG, "Successfully bound to camera $index")
                        lastSuccessfulBindMs = System.currentTimeMillis()
                        cancelScheduledReinit()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to bind to camera $index: ${e.message}")
                        networkCameraLogger.logCameraDisconnect(cameraId, "Camera disconnected")
                    } finally {
                        bindInProgress.set(false)
                    }
                }

                // Short delay before trying next camera
                Thread.sleep(100)
                if (imageAnalysis != null) return

            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare bind to camera $index: ${e.message}")
                networkCameraLogger.logCameraDisconnect(cameraId, "Camera disconnected")
                bindInProgress.set(false)
            }
        }

        Log.e(TAG, "All camera binding attempts failed - scheduling retry")
        networkCameraLogger.logCameraDisconnect(cameraId, "Camera disconnected")
        scheduleRetry()
    }

    // ------------ Core analyzer (optimized for performance) ------------
    private fun analyzeImageProxy(image: ImageProxy) {
        val now = System.currentTimeMillis()
        if ((now - lastProcTime) < MIN_GAP_MS) {
            image.close()
            return
        }

        if (isProcessing) {
            image.close()
            return
        }

        lastProcTime = now
        frameIdx++
        isProcessing = true

        // Update device status to ACTIVE when we start processing frames
        updateDeviceStatusActive()

        try {
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

            // Start periodic upload when we start detecting frames (only once)
            if (senderJob?.isActive != true) {
                startPeriodicUpload()
            }

            // Offload heavy processing to background scope
            analyzeScope.launch {
                try {
                    val doRecog = (frameIdx % RECOG_EVERY == 0)
                    val (metrics, results) = if (doRecog) {
                        viewModel.imageVectorUseCase.getNearestPersonName(finalBitmap, viewModel)
                    } else {
                        val faceDetectionResult = viewModel.imageVectorUseCase.mediapipeFaceDetector.getAllCroppedFacesWithAngle(finalBitmap)
                        val results = faceDetectionResult.map { (_, boundingBox, _) ->
                            com.uav.analytics.domain.ImageVectorUseCase.FaceRecognitionResult(personName = "Detecting...", personID = 0,boundingBox = boundingBox)
                        }
                        Pair(null, results)
                    }

                    val faceCount = results.size

                    withContext(Dispatchers.Main) {
                        try {
                            viewModel.setMetrics(metrics)
                            Log.d(TAG, "Detected faces (headless) = $faceCount")
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analysis coroutine error: ${e.message}")
                } finally {
                    isProcessing = false
                    image.close()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyzeImageProxy error", t)
            isProcessing = false
            image.close()
        }
    }

    // ---------------- buffer & conversion helpers ----------------
    private fun ensureBuffers(w: Int, h: Int) {
        // This function must be called on analyzerExecutor / background thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "ensureBuffers called on main thread - avoid large allocations on main")
        }
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

    // ---------------- USB receiver helpers ----------------
    private fun registerUsbReceiver() {
        if (usbReceiverRegistered.getAndSet(true)) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            registerReceiver(usbReceiver, filter)
            Log.i(TAG, "USB receiver registered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "registerUsbReceiver failed: ${e.message}")
        }
    }

    private fun unregisterUsbReceiver() {
        if (!usbReceiverRegistered.getAndSet(false)) return
        try {
            unregisterReceiver(usbReceiver)
            Log.i(TAG, "USB receiver unregistered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "unregisterUsbReceiver failed: ${e.message}")
        }
    }

    private fun isUsbCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == 14) return true
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.interfaceClass == 14) return true
        }
        return false
    }

    // SCHEDULE / CANCEL reinit with debounce and cancelation support
    private fun scheduleReinit() {
        val now = System.currentTimeMillis()
        if ((now - lastSuccessfulBindMs) < REINIT_DEBOUNCE_MS) {
            Log.d(TAG, "scheduleReinit: Ignored due to recent successful bind (${now - lastSuccessfulBindMs}ms)")
            return
        }
        if (reinitInProgress.getAndSet(true)) {
            Log.d(TAG, "scheduleReinit: Reinit already in progress")
            return
        }
        cancelScheduledReinit()

        val runnable = Runnable {
            reinitializeCameraWithRetry()
        }
        reinitScheduledToken = runnable
        handler.postDelayed(runnable, 800)
        Log.d(TAG, "scheduleReinit: Reinit scheduled (token set)")
    }

    private fun cancelScheduledReinit() {
        reinitScheduledToken?.let {
            try {
                handler.removeCallbacks(it)
            } catch (_: Exception) {}
            reinitScheduledToken = null
            reinitInProgress.set(false)
            Log.d(TAG, "cancelScheduledReinit: pending reinit canceled")
        }
    }

    private fun reinitializeCameraWithRetry() {
        Log.d(TAG, "reinitializeCameraWithRetry: Starting camera reinitialization")
        cleanupCamera()

        val retryDelays = longArrayOf(400, 900, 1800, 3000)
        var attempt = 0

        val runnable = object : Runnable {
            override fun run() {
                if (attempt >= retryDelays.size) {
                    Log.e(TAG, "All camera reinit attempts failed")
                    reinitInProgress.set(false)
                    lifecycleScope.launch(Dispatchers.Main) {
                        Log.w(TAG, "Fallback: Directly starting camera headless after all retries failed")
                        startCameraHeadless()
                    }
                    return
                }
                Log.d(TAG, "Reinit attempt ${attempt + 1}")
                lifecycleScope.launch(Dispatchers.Main) { startCameraHeadless() }
                handler.postDelayed({
                    val state = imageAnalysis != null
                    if (!state) {
                        attempt++
                        if (attempt < retryDelays.size) {
                            Log.d(TAG, "Reinit failed, retrying in ${retryDelays[attempt]}ms")
                            handler.postDelayed(this, retryDelays[attempt])
                        } else {
                            Log.e(TAG, "All camera reinit attempts failed")
                            reinitInProgress.set(false)
                        }
                    } else {
                        Log.i(TAG, "Preview (headless) started on attempt ${attempt + 1}")
                        reinitInProgress.set(false)
                    }
                }, 1000)
            }
        }
        handler.postDelayed(runnable, 0)
    }

    // ---------------- periodic sender ----------------
    private fun startPeriodicUpload() {
        // Only start if not already active and camera is running
        if (senderJob?.isActive == true) return
        uploadIntervalMillis = try {
            RemoteConfigHelper.uploadIntervalDataMs()
        } catch (_: Exception) { uploadIntervalMillis }
        senderJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(uploadIntervalMillis)
            /*while (isActive) {
                try {
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val cameraId = prefs.getString("camera_id", "camera1") ?: "camera1"

                    val sender = AggregatesSender(this@LiveCameraDetectService)
                    val now = System.currentTimeMillis()
                    val from = now - uploadIntervalMillis
                    val ok = sender.sendStoredPersonsAggregates(
                        cameras = listOf(cameraId),
                        fromMillis = from,
                        toMillis = now,
                        deviceId = deviceId,
                        intervalTime = RemoteConfigHelper.getIntervalTime()
                    )
                    Log.d(TAG, if (ok) "Sent aggregates ✅" else "Send failed ❌")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending aggregates: ${e.message}")
                }
                delay(uploadIntervalMillis)
            }*/
        }
        Log.d(TAG, "Periodic upload started")
    }

    private fun stopPeriodicUpload() {
        senderJob?.cancel()
        senderJob = null
    }

    // ---------------- cleanup ----------------
    private fun cleanupCamera() {
        try {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()

            // Send inactive status when camera is cleaned up
            updateDeviceStatusInactive()

            Log.d(TAG, "Camera resources cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "cleanupCamera exception: ${e.message}")
            networkCameraLogger.logCameraDisconnect(cameraId, "Camera disconnected")
        } finally {
            imageAnalysis = null
            cameraProvider = null
            isProcessing = false

            // release preview surface + texture
            try { previewSurface?.release() } catch (_: Exception) {}
            previewSurface = null
            try { previewSurfaceTexture?.release() } catch (_: Exception) {}
            previewSurfaceTexture = null
        }
    }

    // ---------------- camera status helper ----------------
    private fun checkCameraStatus() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "checkCameraStatus: Available camera IDs: ${cameraIds.joinToString()}")
            for (cameraId in cameraIds) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    val facingStr = when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }
                    Log.d(TAG, "Camera $cameraId - Facing: $facingStr")
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "CameraAccessException checking camera $cameraId: reason=${e.reason} msg=${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking camera $cameraId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkCameraStatus: Error checking camera status: ${e.message}")
        }
    }

    // ---------------- notification helpers ----------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL, "Live camera detection", NotificationManager.IMPORTANCE_LOW))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create notification channel: ${e.message}")
            }
        }
    }

    private fun buildNotificationSafe(): Notification {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: Intent(this, com.uav.analytics.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(this, 0, launchIntent, flags)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Live Camera Detection")
            .setContentText("Detecting faces in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
}