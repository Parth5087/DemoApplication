package com.example.demoapplication.services
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.usb.UsbManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import com.example.demoapplication.R
import com.example.demoapplication.RemoteConfigHelper
import com.example.demoapplication.utils.ImageUtils
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CameraBackgroundService : Service() {

    companion object {
        private const val TAG = "CameraBackgroundService"
        private const val NOTIFICATION_CHANNEL_ID = "camera_service_channel"
        private const val NOTIFICATION_ID = 101
    }

    // Remote Config properties
    private var intervalMillis: Long = 1_000L
    private var uploadIntervalMillis: Long = 60_000L
    private var webpQuality = 80
    private var webpMaxDim = 1200
    private var autoUploadEnabled = true
    private var capturedCount: Int = 0

    // Photo folder (app-specific external to satisfy scoped storage)
    private val photoFolder: File by lazy {
        val base = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
        File(base, "USBCamera").apply { if (!exists()) mkdirs() }
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private var cameraId: String? = null
    private var isAutoCaptureRunning = false
    private var captureRunnable: Runnable? = null
    private var uploadRunnable: Runnable? = null
    private var captureJob: Job? = null
    private var zipInProgress = false
    private var lastUploadWindowStart = 0L
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraExecutor = Executors.newSingleThreadScheduledExecutor()


    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            Log.d(TAG, "Capture completed successfully")
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: android.hardware.camera2.CaptureFailure
        ) {
            super.onCaptureFailed(session, request, failure)
            Log.e(TAG, "Capture failed: ${failure.reason}")
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened: ${camera.id}")
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected: ${camera.id}")
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
        }
    }

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        Log.d(TAG, "Image available, saving photo")
        backgroundHandler.post {
            val image = reader.acquireLatestImage()
            image?.let {
                saveImage(it)
                it.close()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    pickCameraId()
                    scheduleRetry()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    closeCamera("USB camera detached. Please connect a camera.")
                    pickCameraId()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Register USB receiver
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)


        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()

        fetchAndApplyRemoteConfig()
        setupCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyRc() {
        intervalMillis = RemoteConfigHelper.captureIntervalMs()
        uploadIntervalMillis = RemoteConfigHelper.uploadIntervalPhotosMs()
        webpQuality = RemoteConfigHelper.webpQuality()
        webpMaxDim = RemoteConfigHelper.webpMaxDim()
        autoUploadEnabled = RemoteConfigHelper.autoUploadEnabled()
    }

    private fun fetchAndApplyRemoteConfig() {
        RemoteConfigHelper.fetchAndActivate {
            applyRc()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Camera Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background camera capture service"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("USB Camera Service")
            .setContentText("Capturing photos in background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply {
            start()
            backgroundHandler = Handler(looper)
        }
        Log.d(TAG, "Background thread started")
    }

    private fun pickCameraId() {
        try {
            val cameraIds = cameraManager.cameraIdList
            Log.d(TAG, "Available cameras: ${cameraIds.joinToString()}")

            // Try to find USB camera (usually external camera)
            cameraId = cameraIds.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL
            } ?: cameraIds.firstOrNull()

            if (cameraId != null) {
                Log.d(TAG, "Selected camera: $cameraId")
            } else {
                Log.e(TAG, "No camera found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error picking camera: ${e.message}")
        }
    }

    private fun setupCamera() {
        pickCameraId()
        if (cameraId != null) {
            openCamera()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            if (cameraId != null) {
                cameraManager.openCamera(cameraId!!, cameraStateCallback, backgroundHandler)
                Log.d(TAG, "Opening camera: $cameraId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: ${e.message}")
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
            val streamConfigMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val largestSize = streamConfigMap?.getOutputSizes(ImageReader::class.java)?.maxByOrNull { it.height * it.width }
            val size = largestSize ?: Size(1920, 1080)

            Log.d(TAG, "Creating image reader with size: $size")

            imageReader = ImageReader.newInstance(
                size.width, size.height,
                android.graphics.ImageFormat.JPEG, 2
            )
            imageReader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Use the non-deprecated createCaptureSession method with SessionConfiguration
                val outputConfigurations = listOf(
                    OutputConfiguration(imageReader.surface)
                )

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigurations,
                    cameraExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Capture session configured")
                            captureSession = session
                            startAutoCapture()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed")
                        }
                    }
                )

                cameraDevice.createCaptureSession(sessionConfig)
            } else {
                // Legacy path for pre-P devices
                val surfaces = listOf(imageReader.surface)
                cameraDevice.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.d(TAG, "Capture session configured (legacy)")
                            captureSession = session
                            startAutoCapture()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed (legacy)")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating preview session: ${e.message}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startAutoCapture() {
        if (captureJob?.isActive == true) return
        capturedCount = 0
        lastUploadWindowStart = System.currentTimeMillis()

        captureJob = CoroutineScope(Dispatchers.Main).launch {
            var lastUploadTs = System.currentTimeMillis()
            while (isActive) {
                captureAndProcess()
                val now = System.currentTimeMillis()

                if (autoUploadEnabled && (now - lastUploadTs) >= uploadIntervalMillis) {
                    lastUploadWindowStart
                    if (!zipInProgress) {
                        zipInProgress = true
                        ioScope.launch {
                            try {
                                doZipAndUploadBlockBackground()
                            } finally {
                                zipInProgress = false
                            }
                        }
                        lastUploadWindowStart = now
                        lastUploadTs = now
                    }
                }
                delay(intervalMillis)
            }
        }
        Log.d(TAG, "Capturing every ${intervalMillis/1000}s… (uploads every ${uploadIntervalMillis/1000}s)")
    }

    private fun stopAutoCapture() {
        isAutoCaptureRunning = false
        captureRunnable?.let { backgroundHandler.removeCallbacks(it) }
        uploadRunnable?.let { backgroundHandler.removeCallbacks(it) }
        captureJob?.cancel()
        Log.d(TAG, "Auto capture stopped")
    }

    private fun captureAndProcess() {
        try {
            capturePhoto()
        } catch (e: Exception) {
            Log.e(TAG, "Error in captureAndProcess: ${e.message}")
        }
    }


    private fun scheduleRetry() {
        backgroundHandler.postDelayed({
            if (cameraId == null) {
                pickCameraId()
                if (cameraId != null) {
                    openCamera()
                } else {
                    scheduleRetry()
                }
            }
        }, 5000) // Retry every 5 seconds
    }

    private fun capturePhoto() {
        try {
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            captureSession.capture(
                captureBuilder.build(),
                captureCallback,
                backgroundHandler
            )
            Log.d(TAG, "Capture request sent at ${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo: ${e.message}")
        }
    }

    private fun saveImage(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // 1) save JPG first
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(System.currentTimeMillis())
            val jpgFile = File(photoFolder, "IMG_$ts.jpg")
            jpgFile.parentFile?.let { pf ->
                if (pf.exists() && !pf.isDirectory) pf.delete()
                if (!pf.exists()) pf.mkdirs()
            }
            FileOutputStream(jpgFile).use { fos ->
                fos.write(bytes)
                try { fos.fd.sync() } catch (_: Throwable) {}
            }
            Log.d(
                TAG, "📸 JPG saved: ${jpgFile.name} (${
                    ImageUtils.formatFileSize(
                        jpgFile.length()
                    )
                })")

            // 2) convert to WebP and remove the JPG
            val webpFile = ImageUtils.convertJpgToWebP(
                jpgFile = jpgFile,
                quality = webpQuality,
                maxDim = webpMaxDim,
                deleteJpg = true
            )

            if (webpFile != null) {
                Log.d(
                    TAG, "🖼️ WebP ready: ${webpFile.name} (${
                        ImageUtils.formatFileSize(
                            webpFile.length()
                        )
                    })")
            } else {
                Log.e(TAG, "WebP conversion failed; keeping JPG (if exists).")
            }

            // UI count
            capturedCount += 1

        } catch (e: Exception) {
            Log.e(TAG, "Error saving image: ${e.message}")
        }
    }

    // ===== ZIP + Upload (background) =====
    private suspend fun doZipAndUploadBlockBackground() {
        Log.d(TAG, "Creating ZIP...")
        val zip = createZipOfFolder(photoFolder)

        Log.d(TAG, "Uploading ZIP...")
        val success = uploadZipFile(zip)

        if (success) {
            Log.d(TAG, "Upload success ✅ | Photos Captured: $capturedCount")
        } else {
            Log.d(TAG, "Upload failed ❌ (see log) | Photos Captured: $capturedCount")
        }

        try {
            if (zip.exists()) zip.delete()
        } catch (_: Exception) {
            Log.w(TAG, "Failed to delete zip file after upload")
        }
    }

    // ------------------ ZIP CREATION ------------------

    /**
     * Creates ZIP of all files inside [photoFolder]. Only includes files, not subfolders.
     * Returns the created ZIP File.
     */
    private fun createZipOfFolder(folder: File): File {
        // Format ZIP name with timestamp
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(System.currentTimeMillis())
        // Write ZIP into app-specific external Pictures dir to avoid scoped storage EPERM
        val zipDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "zips").apply {
            if (!exists()) mkdirs()
        }
        val zipFile = File(zipDir, "photos_$ts.zip")

        // Snapshot files and their lastModified to avoid comparator instability
        val files: List<File> = folder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("IMG_") }
            ?.map { file ->
                // snapshot attributes once
                val lm = try { file.lastModified() } catch (_: Throwable) { 0L }
                Triple(file, lm, file.name)
            }
            ?.sortedWith(compareBy<Triple<File, Long, String>> { it.second }.thenBy { it.third })
            ?.map { it.first }
            ?.filter { it.exists() }
            ?.toList()
            ?: emptyList()

        val totalOriginalBytes = files.sumOf { it.length() }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
            for (file in files) {
                try {
                    if (!file.exists()) continue
                    FileInputStream(file).use { fis ->
                        zos.putNextEntry(ZipEntry(file.name))
                        fis.copyTo(zos, bufferSize = 8 * 1024)
                        zos.closeEntry()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Skipping file during zip due to error: ${file.name}", t)
                }
            }
        }

        Log.d(
            TAG, "Created ZIP: ${zipFile.name} — ${files.size} photos; " +
                "orig=${ImageUtils.formatFileSize(totalOriginalBytes)}, zip=${
                    ImageUtils.formatFileSize(
                        zipFile.length()
                    )
                }")
        return zipFile
    }

    /**
     * Uploads given ZIP to sample API using Retrofit.
     * Change endpoint to your own: in NetworkModule, BASE_URL and here the path if needed.
     */
    private suspend fun uploadZipFile(zipFile: File, cameraId: String = "cam1"): Boolean {
        return try {
            if (!zipFile.exists() || zipFile.length() == 0L) {
                Log.e("UPLOAD", "Zip does not exist or empty.")
                return false
            }

            // file part
            val mediaType = "application/zip".toMediaTypeOrNull()
            val requestBody = zipFile.asRequestBody(mediaType)
            val filePart = MultipartBody.Part.createFormData("file", zipFile.name, requestBody)

            val response = NetworkModule.api.ingestZip(
                cameraIdQuery = cameraId,
                file = filePart,
            )

            val ok = response.isSuccessful
            Log.d("UPLOAD", "Upload response: ${response.code()} ${response.message()}")

            if (ok) {
                deleteAllPhotos()   // delete only on success
                Log.d("UPLOAD", "Photos deleted after successful upload.")
            } else {
                Log.w("UPLOAD", "Upload failed — keeping photos for next attempt.")
            }

            ok
        } catch (e: Exception) {
            Log.e("UPLOAD", "Upload failed", e)
            false
        } finally {
            // Optionally delete the temporary ZIP itself (not the photos)
            try { if (zipFile.exists()) zipFile.delete() } catch (_: Exception) {}
        }
    }


    private fun deleteAllPhotos() {
        try {
            val files = photoFolder.listFiles()?.filter { it.isFile }
            files?.forEach { file ->
                if (file.delete()) {
                    Log.d(TAG, "Deleted: ${file.name}")
                }
            }
            capturedCount = 0
            Log.d(TAG, "All photos deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting photos: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        // Unregister USB receiver
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering USB receiver: ${e.message}")
        }
        
        stopAutoCapture()
        ioScope.cancel()
        cameraExecutor.shutdown()
        stopBackgroundThread()
        closeCamera()

    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
        Log.d(TAG, "Background thread stopped")
    }

    private fun closeCamera(reason: String? = null) {
        try {
            if (::captureSession.isInitialized) {
                captureSession.close()
            }
            if (::cameraDevice.isInitialized) {
                cameraDevice.close()
            }
            if (::imageReader.isInitialized) {
                imageReader.close()
            }
            Log.d(TAG, "Camera resources released${reason?.let { ": $it" } ?: ""}")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        }
    }
}