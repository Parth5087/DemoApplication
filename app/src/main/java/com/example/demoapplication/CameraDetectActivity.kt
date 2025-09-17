package com.example.demoapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.demoapplication.services.NetworkModule
import com.example.demoapplication.utils.ImageUtils
import com.example.demoapplication.utils.ImageUtils.formatFileSize
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CameraDetectActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TvUsbCam"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var countText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnGallery: Button
    private lateinit var btnClearCounts: Button

    // Counters
    @Volatile private var capturedCount = 0

    // RC-controlled tunables (no clamps)
    private var intervalMillis: Long = 1_000L
    private var uploadIntervalMillis: Long = 60_000L
    private var webpQuality = 80
    private var webpMaxDim = 1200
    private var autoUploadEnabled = true

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Camera2
    private var imageReader: android.media.ImageReader? = null
    private var previewSize: Size? = null
    private var jpegSize: Size? = null

    // Background
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // Jobs / handlers
    private var captureJob: Job? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // state
    private var surfaceReady = false
    private var permissionRequested = false
    private var openInProgress = false
    private var retryIndex = 0
    private var autoStartedForThisOpen = false

    private val photoFolder by lazy {
        File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photos").apply { mkdirs() }
    }

    // background scope for zip/upload
    private val ioScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    @Volatile private var zipInProgress = false
    private var lastUploadWindowStart = 0L

    private fun show(msg: String) {
        Log.d(TAG, msg)
    }

    // ===== Remote Config =====
    private fun applyRc() {
        intervalMillis        = RemoteConfigHelper.captureIntervalMs()
        uploadIntervalMillis  = RemoteConfigHelper.uploadIntervalPhotosMs()
        webpQuality           = RemoteConfigHelper.webpQuality()
        webpMaxDim            = RemoteConfigHelper.webpMaxDim()
        autoUploadEnabled     = RemoteConfigHelper.autoUploadEnabled()
    }

    private fun fetchAndApplyRemoteConfig() {
        RemoteConfigHelper.fetchAndActivate {
            applyRc()
        }
    }

    // ===== Availability / USB callbacks =====
    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        @RequiresPermission(Manifest.permission.CAMERA)
        override fun onCameraAvailable(id: String) {
            if (cameraDevice == null) {
                pickCameraId()
                maybeOpenCamera()
            }
        }
        override fun onCameraUnavailable(id: String) {
            if (id == cameraId) {
                closeCamera("Camera in use. Waiting… [unavailable]")
                scheduleRetry()
            }
        }
        override fun onCameraAccessPrioritiesChanged() {
            if (cameraDevice == null) scheduleRetry()
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
                    if (cameraDevice != null) closeCamera("USB camera detached. Please connect a camera.")
                    pickCameraId()
                }
            }
        }
    }

    // ===== Lifecycle =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_camera_detect)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        surfaceView = findViewById(R.id.previewView)
        countText   = findViewById(R.id.countText)
        btnStart    = findViewById(R.id.btnStart)
        btnStop     = findViewById(R.id.btnStop)
        btnGallery  = findViewById(R.id.btnShowImages)
        btnClearCounts = findViewById(R.id.btnClearCounts)
        cameraManager = getSystemService(CameraManager::class.java)

        btnStart.setOnClickListener { startAutoCapture() }
        btnStop.setOnClickListener { stopAutoCapture() }
        btnClearCounts.setOnClickListener { deleteAllPhotos() }

        // Ensure defaults are set in Application or here; then fetch latest
        fetchAndApplyRemoteConfig()
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            @RequiresPermission(Manifest.permission.CAMERA)
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                maybeOpenCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceReady = holder.surface?.isValid == true
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                closeCamera("Surface destroyed")
            }
        })
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onResume() {
        super.onResume()
        startBgThread()

        registerReceiver(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        cameraManager.registerAvailabilityCallback(availabilityCallback, mainHandler)

        if (!hasCameraPermission() && !permissionRequested) {
            permissionRequested = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
            return
        }

        fetchAndApplyRemoteConfig()
        dumpCameras()
        pickCameraId()
        maybeOpenCamera()
    }

    override fun onPause() {
        super.onPause()
        cameraManager.unregisterAvailabilityCallback(availabilityCallback)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        stopAutoCapture()
        closeCamera("onPause()")
        openInProgress = false
        stopBgThread()
    }

    // ===== Background thread =====
    private fun startBgThread() {
        if (bgThread != null) return
        bgThread = HandlerThread("CamBg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBgThread() {
        try { bgThread?.quitSafely() } catch (_: Exception) {}
        try { bgThread?.join() } catch (_: Exception) {}
        bgThread = null
        bgHandler = null
    }

    // ===== Permission =====
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "CAMERA permission granted")
            pickCameraId()
            maybeOpenCamera()
        } else {
            show("Camera permission required")
        }
    }

    // ===== UI actions =====
    @SuppressLint("SetTextI18n")
    private fun startAutoCapture() {
        if (captureJob?.isActive == true) return
        capturedCount = 0
        countText.text = "Captured: $capturedCount (continuous)"
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
        show("Capturing every ${intervalMillis/1000}s… (uploads every ${uploadIntervalMillis/1000}s)")
    }

    @SuppressLint("SetTextI18n")
    private fun stopAutoCapture() {
        captureJob?.cancel()
        captureJob = null
        show("Auto capture stopped")
        countText.text = "Captured: $capturedCount (stopped)"
    }

    @SuppressLint("SetTextI18n")
    private fun deleteAllPhotos() {
        photoFolder.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        capturedCount = 0
        countText.text = "Captured: 0"
        Log.d(TAG, "🗑️ All photos deleted")
    }

    // ===== Camera listing / pick =====
    private fun dumpCameras() {
        try {
            val ids = cameraManager.cameraIdList.joinToString()
            Log.d(TAG, "cameraIdList=[$ids]")
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                val facing = c.get(LENS_FACING)
                val ext = if (facing == LENS_FACING_EXTERNAL) "EXTERNAL" else "$facing"
                val caps = c.get(REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()
                Log.d(TAG, "id=$id facing=$ext caps=$caps")
            }
        } catch (e: Exception) {
            Log.e(TAG, "dumpCameras() failed: ${e.message}", e)
        }
    }

    private fun pickCameraId() {
        cameraId = null
        try {
            var fallback: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                when (chars.get(LENS_FACING)) {
                    LENS_FACING_EXTERNAL -> { cameraId = id; break }
                    else -> if (fallback == null) fallback = id
                }
            }
            if (cameraId == null) cameraId = fallback
            Log.d(TAG, "pickCameraId() -> $cameraId")
        } catch (e: Exception) {
            Log.e(TAG, "pickCameraId() error: ${e.message}", e)
            show("Error reading cameras: ${e.message}")
        }
    }

    // ===== Open / Retry =====
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun maybeOpenCamera() {
        if (openInProgress || cameraDevice != null) return
        if (!hasCameraPermission()) return
        if (!surfaceReady) { show("Preparing preview surface…"); return }
        if (cameraId == null) { show("Please connect a camera"); return }

        openInProgress = true
        autoStartedForThisOpen = false
        show("Please wait, starting camera…")

        try {
            cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    retryIndex = 0
                    cameraDevice = device
                    openInProgress = false
                    startPreview(surfaceView.holder.surface)
                }
                override fun onDisconnected(device: CameraDevice) {
                    openInProgress = false
                    stopAutoCapture()
                    closeCamera("Camera disconnected. Please connect a camera.")
                    scheduleRetry()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    openInProgress = false
                    stopAutoCapture()
                    closeCamera("Camera error: $error")
                    scheduleRetry()
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            openInProgress = false
            show("Permission error")
        } catch (e: Exception) {
            openInProgress = false
            show("Open failed: ${e.message}")
            scheduleRetry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleRetry() {
        val delays = longArrayOf(300, 600, 1200, 2400, 4000)
        val delay = delays[retryIndex.coerceAtMost(delays.lastIndex)]
        retryIndex = (retryIndex + 1).coerceAtMost(delays.lastIndex)
        mainHandler.postDelayed({ maybeOpenCamera() }, delay)
    }

    // ===== Preview / Session =====
    private fun chooseSizes() {
        val id = cameraId ?: return
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("No stream config map")

        val previewChoices = map.getOutputSizes(SurfaceHolder::class.java)
        previewSize = previewChoices
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.maxByOrNull { it.width.toLong() * it.height } ?: previewChoices?.first()

        val jpegChoices = map.getOutputSizes(ImageFormat.JPEG)
        jpegSize = jpegChoices?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)

        // optional: align SurfaceView buffer
        previewSize?.let { surfaceView.holder.setFixedSize(it.width, it.height) }
    }

    @SuppressLint("SetTextI18n")
    private fun createImageReader() {
        val size = jpegSize ?: Size(1280, 720)
        imageReader?.close()
        imageReader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
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
                Log.d(TAG, "📸 JPG saved: ${jpgFile.name} (${formatFileSize(jpgFile.length())})")

                // 2) convert to WebP and remove the JPG
                val webpFile = ImageUtils.convertJpgToWebP(
                    jpgFile = jpgFile,
                    quality = webpQuality,
                    maxDim = webpMaxDim,
                    deleteJpg = true
                )

                if (webpFile != null) {
                    Log.d(TAG, "🖼️ WebP ready: ${webpFile.name} (${formatFileSize(webpFile.length())})")
                } else {
                    Log.e(TAG, "WebP conversion failed; keeping JPG (if exists).")
                }

                // UI count
                capturedCount += 1
                mainHandler.post { countText.text = "Captured: $capturedCount" }
            } catch (e: Exception) {
                Log.e(TAG, "Save/convert failed: ${e.message}", e)
            } finally {
                image.close()
            }
        }, bgHandler)
    }


    private fun startPreview(previewSurface: Surface) {
        val device = cameraDevice ?: return
        try {
            chooseSizes()
            createImageReader()
            val irSurface = imageReader!!.surface

            val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            device.createCaptureSession(
                listOf(previewSurface, irSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewReq.build(), null, bgHandler)
                            show("")
                            if (!autoStartedForThisOpen) {
                                autoStartedForThisOpen = true
                                startAutoCapture()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "setRepeatingRequest() failed: ${e.message}", e)
                            show("Preview error: ${e.message}")
                            scheduleRetry()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "onConfigureFailed()")
                        show("Preview config failed")
                        scheduleRetry()
                    }
                }, bgHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startPreview() exception: ${e.message}", e)
            show("Start preview failed: ${e.message}")
            scheduleRetry()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun captureAndProcess() {
        captureStillImage()
    }

    private fun captureStillImage() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val readerSurface = imageReader?.surface ?: return

        try {
            val captureReq = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            }.build()

            // Temporarily stop preview, take picture, then resume
            session.stopRepeating()
            session.capture(captureReq, object : CameraCaptureSession.CaptureCallback() {}, bgHandler)

            val previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surfaceView.holder.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            session.setRepeatingRequest(previewReq, null, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "captureStillImage() failed: ${e.message}", e)
            show("Capture failed: ${e.message}")
        }
    }

    // ===== Close =====
    private fun closeCamera(message: String? = null) {
        Log.d(TAG, "closeCamera(): message=$message")
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        imageReader = null
        message?.let { show(it) } ?: show("Please connect a camera")
    }

    // ===== ZIP + Upload (background) =====
    @SuppressLint("SetTextI18n")
    private suspend fun doZipAndUploadBlockBackground() {
        withContext(Dispatchers.Main) { countText.text = "Creating ZIP..." }
        val zip = createZipOfFolder(photoFolder)

        withContext(Dispatchers.Main) { countText.text = "Uploading ZIP..." }
        val success = uploadZipFile(zip)

        withContext(Dispatchers.Main) {
            countText.text = if (success) {
                "Upload success ✅\nPhotos Captured: $capturedCount"
            } else {
                "Upload failed ❌ (see log)\nPhotos Captured: $capturedCount"
            }
        }

        try { if (zip.exists()) zip.delete() } catch (_: Exception) {}
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
        val zipFile = File(folder.parentFile, "photos_$ts.zip")

        val files = folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("IMG_") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

        val totalOriginalBytes = files.sumOf { it.length() }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
            for (file in files) {
                FileInputStream(file).use { fis ->
                    zos.putNextEntry(ZipEntry(file.name))
                    fis.copyTo(zos, bufferSize = 8 * 1024)
                    zos.closeEntry()
                }
            }
        }

        Log.d("ZIP", "Created ZIP: ${zipFile.name} — ${files.size} photos; " +
                "orig=${formatFileSize(totalOriginalBytes)}, zip=${formatFileSize(zipFile.length())}")
        return zipFile
    }



    // ------------------ UPLOAD ------------------

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
}
