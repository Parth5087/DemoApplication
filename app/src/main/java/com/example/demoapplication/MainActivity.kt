package com.example.demoapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.demoapplication.domain.analytics.AggregatesSender
import com.example.demoapplication.domain.faceDection.FaceDetectionOverlay
import com.example.demoapplication.services.UsbCameraWatcherService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainActivityViewModel
    @ExperimentalGetImage
    private lateinit var faceDetectionOverlay: FaceDetectionOverlay
    private lateinit var permissionLayout: LinearLayout
    private lateinit var btnAllow: Button
    private lateinit var tvRecognition: TextView
    private lateinit var tvCounts: TextView
    private lateinit var tvStoredGenders: TextView
    private lateinit var tvStoredAgeGroups: TextView
    private lateinit var tvStoredExpressions: TextView
    private lateinit var cameraPreview: FrameLayout
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnClearCounts: Button

    // flip this to true to run **preview-only** (no analysis)
    private val previewOnly = false   // <<–– set true for TV webcam debugging

    @RequiresApi(Build.VERSION_CODES.O)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) showCamera() else permissionLayout.visibility = View.VISIBLE
        }

    @RequiresApi(Build.VERSION_CODES.O)
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // UI refs
        permissionLayout = findViewById(R.id.permissionLayout)
        btnAllow = findViewById(R.id.btnAllow)
        tvRecognition = findViewById(R.id.tvRecognition)
        tvCounts = findViewById(R.id.tvCounts)
        tvStoredGenders = findViewById(R.id.tvStoredGenders)
        tvStoredAgeGroups = findViewById(R.id.tvStoredAgeGroups)
        tvStoredExpressions = findViewById(R.id.tvStoredExpressions)
        cameraPreview = findViewById(R.id.cameraPreview)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnClearCounts = findViewById(R.id.btnClearCounts)

        btnAllow.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        btnSwitchCamera.setOnClickListener {
            if (::faceDetectionOverlay.isInitialized) {
                faceDetectionOverlay.switchCamera()
            }
        }

        val factory = MainActivityViewModelFactory(this)
        viewModel = ViewModelProvider(this, factory)[MainActivityViewModel::class.java]

        btnClearCounts.setOnClickListener {
            viewModel.resetAllFaceCounts()
            tvCounts.text = "Detected Faces: 0\nStored Persons: 0"
            tvRecognition.text = "Detected Faces: 0"
            tvStoredExpressions.text = "Neutral: 0\nHappy: 0\nSurprised: 0\nSad: 0\nAnger: 0\nFear: 0"
            tvStoredGenders.text = "Stored Male: 0\nStored Female: 0"
            tvStoredAgeGroups.text = "Child (0-14): 0\nYoung Adult (15-25): 0\nAdult (26-55): 0\nElderly (56+): 0"
        }

        viewModel.faceCountsState.observe(this) { counts ->
            tvCounts.text = "Detected Faces: ${counts.detectedCount}\nStored Persons: ${counts.storedCount}"
            tvCounts.visibility = View.VISIBLE
        }
        viewModel.storedGenderCountsState.observe(this) { counts ->
            tvStoredGenders.text = "Stored Male: ${counts.maleCount}\nStored Female: ${counts.femaleCount}"
            tvStoredGenders.visibility = View.VISIBLE
        }
        viewModel.storedAgeGroupCountsState.observe(this) { counts ->
            tvStoredAgeGroups.text = """
                Child (0-14): ${counts.childCount}
                Young Adult (15-25): ${counts.youngAdultCount}
                Adult (26-55): ${counts.adultCount}
                Elderly (56+): ${counts.elderlyCount}
            """.trimIndent()
            tvStoredAgeGroups.visibility = View.VISIBLE
        }
        viewModel.storedExpressionCountsState.observe(this) { counts ->
            val total = counts.neutralCount + counts.happyCount + counts.surprisedCount +
                    counts.sadCount + counts.angerCount + counts.fearCount
            fun pct(v: Int) = if (total > 0) (v.toFloat() / total * 100).toInt() else 0
            tvStoredExpressions.text = """
                Neutral: ${pct(counts.neutralCount)}%
                Happy: ${pct(counts.happyCount)}%
                Surprised: ${pct(counts.surprisedCount)}%
                Sad: ${pct(counts.sadCount)}%
                Anger: ${pct(counts.angerCount)}%
                Fear: ${pct(counts.fearCount)}%
            """.trimIndent()
            tvStoredExpressions.visibility = View.VISIBLE
        }

        checkCameraPermission()
    }

    private var testJob: Job? = null

    private fun startTestSendingEveryMinute() {
        // avoid duplicate jobs
        if (testJob?.isActive == true) return

        testJob = lifecycleScope.launch {
            // ⏳ wait 1 minute BEFORE first send
            delay(60_000L)

            while (isActive) {
                val sender = AggregatesSender(this@MainActivity)
                val now = System.currentTimeMillis()
                val oneMinuteAgo = now - 60_000L

                val ok = sender.sendStoredPersonsAggregates(
                    cameras = listOf("camera1"),
                    fromMillis = oneMinuteAgo,
                    toMillis = now,
                    fallbackToAnyLatest = true
                )

                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Sent ✅ at ${Date()}" else "Send failed ❌",
                    Toast.LENGTH_SHORT
                ).show()

                // wait next minute
                delay(60_000L)
            }
        }
    }

    private fun stopTestSending() {
        testJob?.cancel()
        testJob = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkCameraPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showCamera()
        } else {
            permissionLayout.visibility = View.VISIBLE
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun showCamera() {
        permissionLayout.visibility = View.GONE
        cameraPreview.removeAllViews()

        faceDetectionOverlay = FaceDetectionOverlay(
            lifecycleOwner = this,
            ctx = this,
            viewModel = viewModel,
            onFaceCountDetected = { count -> tvRecognition.text = "Detected Faces: $count" },
            previewOnly = previewOnly              // <<–– flip true to test webcam only
        )
        cameraPreview.addView(faceDetectionOverlay)
//        startTestSendingEveryMinute()

    }
}
