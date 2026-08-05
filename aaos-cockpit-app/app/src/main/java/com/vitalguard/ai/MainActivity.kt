package com.vitalguard.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vitalguard.ai.detection.mediapipe.FaceLandmarkerClient
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Cockpit entry point. The actual TRIGGER_ALERT handling lives in
 * [VitalGuardMonitorService] (started here and by [BootCompletedReceiver]) so the
 * intervention keeps working whether or not this Activity is on screen.
 *
 * Also renders the mandatory debug overlay (root CLAUDE.md "Debug Overlay"
 * section) by collecting [DebugOverlayState.instance] live — perclos,
 * eyeOpenProbability, headEulerAngleX, driver state, whether a trigger is
 * currently being received, and the last gateway action.
 *
 * Camera wiring here is a spike (see [FaceLandmarkerClient]'s kdoc): CameraX feeds
 * frames into MediaPipe's FaceLandmarker in LIVE_STREAM mode purely to capture real
 * blendshape/facialTransformationMatrixes values on-device ahead of the HeadPose.kt
 * port. It does not yet feed DrowsinessController/DebugOverlayState -- that wiring
 * lands once DetectionPipeline replaces the HTTP trigger path.
 */
class MainActivity : AppCompatActivity() {

    private var faceLandmarkerClient: FaceLandmarkerClient? = null
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

        lifecycleScope.launch {
            DebugOverlayState.instance.flow.collect { snapshot ->
                statusView.text = snapshot.driverState
                perclosView.text = "PERCLOS: %.3f".format(snapshot.perclos)
                eyeOpenView.text = "Eye open prob: %.3f".format(snapshot.eyeOpenProbability)
                headPitchView.text = "Head pitch: %.1f°".format(snapshot.headEulerAngleX)
                receivingView.text = "Receiving trigger: ${snapshot.receivingTrigger}"
                gatewayActionView.text = "Last gateway action: ${snapshot.lastGatewayAction}"
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        faceLandmarkerClient = FaceLandmarkerClient(
            context = this,
            onResult = { result -> logFaceLandmarkerResult(result) },
            onError = { e -> Log.e(TAG, "FaceLandmarker error", e) },
        )

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

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            faceLandmarkerClient?.detectAsync(
                                imageProxy.toRgbaBitmap(),
                                imageProxy.imageInfo.timestamp.toDouble(),
                            )
                        } catch (e: RuntimeException) {
                            Log.e(TAG, "Frame analysis failed", e)
                        } finally {
                            imageProxy.close()
                        }
                    }
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

    private fun logFaceLandmarkerResult(
        result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
    ) {
        val blendshapes = result.faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty()
        val eyeBlinkLeft = blendshapes.find { it.categoryName() == "eyeBlinkLeft" }?.score()
        val eyeBlinkRight = blendshapes.find { it.categoryName() == "eyeBlinkRight" }?.score()
        Log.d(TAG, "eyeBlinkLeft=$eyeBlinkLeft eyeBlinkRight=$eyeBlinkRight")

        val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
        Log.d(TAG, "facialTransformationMatrix=${matrix?.contentToString()}")
    }

    /**
     * camera-core 1.3.1 (pinned to match dms-ai-engine's mediapipe==0.10.14 -- see
     * build.gradle) predates the built-in ImageProxy.toBitmap() extension, so RGBA_8888
     * single-plane output is unpacked manually. Row padding is stripped by cropping back
     * to the reported width, matching the pixelStride/rowStride handling CameraX's own
     * extension does internally.
     */
    private fun ImageProxy.toRgbaBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
        return if (rowPadding == 0) {
            paddedBitmap
        } else {
            Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceLandmarkerClient?.close()
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
