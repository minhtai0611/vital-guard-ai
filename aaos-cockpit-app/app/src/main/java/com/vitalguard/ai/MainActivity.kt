package com.vitalguard.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Cockpit entry point. The actual TRIGGER_ALERT handling AND the on-device MediaPipe
 * detection pipeline both live in [VitalGuardMonitorService] (started here and by
 * [BootCompletedReceiver]) so both keep working whether or not this Activity is on
 * screen -- see [VitalGuardMonitorService]'s kdoc and root CLAUDE.md's "State Machine
 * Hardening" section. This Activity is UI only: it renders the mandatory debug
 * overlay (perclos/eyeOpenProbability/headEulerAngleX/driver state/receivingTrigger/
 * lastGatewayAction, plus the most recently decoded on-device frame) by collecting
 * [DebugOverlayState.instance] live.
 *
 * `cameraPreview` (CameraX) is kept wired up but hidden (see activity_main.xml) since
 * `bindToLifecycle()` currently always throws `IllegalArgumentException` on this AVD
 * (front camera unresolvable) -- a separate, unrelated, still-open bug. Its analyzer
 * intentionally does not feed detection: that responsibility belongs to
 * [VitalGuardMonitorService]'s MediaPipeReplayDetectionSource, not this Activity.
 */
class MainActivity : AppCompatActivity() {

    private var replayFramePreview: ImageView? = null
    private lateinit var cameraExecutor: ExecutorService

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Log.w(TAG, "Camera permission denied -- driver-facing feed will stay dark")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startForegroundService(Intent(this, VitalGuardMonitorService::class.java))

        val statusView = findViewById<TextView>(R.id.statusText)
        val perclosView = findViewById<TextView>(R.id.overlayPerclos)
        val eyeOpenView = findViewById<TextView>(R.id.overlayEyeOpen)
        val headPitchView = findViewById<TextView>(R.id.overlayHeadPitch)
        val receivingView = findViewById<TextView>(R.id.overlayReceiving)
        val gatewayActionView = findViewById<TextView>(R.id.overlayGatewayAction)
        replayFramePreview = findViewById(R.id.replayFramePreview)

        lifecycleScope.launch {
            DebugOverlayState.instance.flow.collect { snapshot ->
                statusView.text = snapshot.driverState
                perclosView.text = "PERCLOS: %.3f".format(snapshot.perclos)
                eyeOpenView.text = "Eye open prob: %.3f".format(snapshot.eyeOpenProbability)
                headPitchView.text = "Head pitch: %.1f°".format(snapshot.headEulerAngleX)
                receivingView.text = "Receiving trigger: ${snapshot.receivingTrigger}"
                gatewayActionView.text = "Last gateway action: ${snapshot.lastGatewayAction}"

                val frame = snapshot.lastFrame
                if (frame != null) {
                    replayFramePreview?.visibility = View.VISIBLE
                    replayFramePreview?.setImageBitmap(frame)
                }
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val previewView = findViewById<PreviewView>(R.id.cameraPreview)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Bound but intentionally not analyzed here -- detection is owned by
            // VitalGuardMonitorService's MediaPipeReplayDetectionSource. This use
            // case exists only so a future live-camera DetectionBackendMode has
            // somewhere to plug in without re-deriving the CameraX bind boilerplate.
            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy -> imageProxy.close() }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (e: RuntimeException) {
                Log.e(TAG, "CameraX bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
