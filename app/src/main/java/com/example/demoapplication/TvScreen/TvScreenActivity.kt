package com.example.demoapplication.TvScreen

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.demoapplication.R
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import android.util.Size
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class TvScreenActivity : AppCompatActivity() {
    private lateinit var logView: TextView
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
//        setContentView(R.layout.activity_tv_screen)
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        logView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER   // text center inside TextView
        }
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                800 // fixed height preview window
            )
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER   // child views center in parent
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            addView(ScrollView(this@TvScreenActivity).apply { addView(logView) }, 0)
            addView(previewView, 1)
        }

        setContentView(container)

        log("=== Camera Diagnostic ===")

        // 1. System feature check
        val pm = packageManager
        val hasCamera = pm.hasSystemFeature("android.hardware.camera.any")
        log("System reports camera support? $hasCamera")

        // 2. Camera2 check
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        if (cameraIds.isNotEmpty()) {
            log("Camera2 cameras found:")
            cameraIds.forEach { id ->
                log(" - Camera ID: $id")
            }
        } else {
            log("No Camera2 cameras found")
        }

        // 3. CameraX preview test
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val infos = provider.availableCameraInfos
            if (infos.isNotEmpty()) {
                log("CameraX cameras found: ${infos.size}")
                try {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(this, cameraSelector, preview)
                    log("✅ Preview started (DEFAULT_FRONT_CAMERA)")
                } catch (e: Exception) {
                    log("❌ Failed to start preview: ${e.message}")
                }
            } else {
                log("No CameraX cameras found")
            }
        }, ContextCompat.getMainExecutor(this))

        // 4. USB devices
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.deviceList.isNotEmpty()) {
            log("USB devices detected:")
            usbManager.deviceList.forEach { (name, device) ->
                log(" - $name: vendor=${device.vendorId}, product=${device.productId}, class=${device.deviceClass}")
            }
        } else {
            log("No USB devices connected")
        }
    }

    private fun log(msg: String) {
        Log.d("CameraDiag", msg)
        logView.append(msg + "\n")
    }

//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//            startCamera()
//        }
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT) // or BACK
                .build()

            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            previewView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

            // Force 16:9 to match TV screen, avoid letterboxing
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(Surface.ROTATION_0) // force sensor output
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)

                // Rotate preview to fix sideways feed
                previewView.post {
                    previewView.rotation = 270f // adjust to match correct upright orientation
                }

            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
}