package com.vitalguard.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
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
import java.io.File
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

        runReplayFileSpikeIfPresent()
    }

    /**
     * ReplayFileFrameSource spike (see the migration handoff doc's Step 5) -- decodes a
     * bundled MP4 with MediaMetadataRetriever and feeds sampled frames straight into
     * FaceLandmarkerClient, completely bypassing CameraX/the camera. Exists to get real
     * blendshape/facialTransformationMatrixes values from a real driver-facing video on
     * this specific x86_64 dev machine, which cannot exercise the live camera path today
     * (CameraX can't resolve a front camera on this AVD -- separate, unrelated issue).
     * No-op if the file isn't present on the device (`adb push` to the app's external
     * files dir); this is a manual test hook, not the real DetectionBackendMode wiring.
     */
    private fun runReplayFileSpikeIfPresent() {
        // /data/local/tmp, not getExternalFilesDir() -- this AAOS emulator runs the app
        // under a secondary user profile (uid 10's /storage/emulated/10 is FUSE-isolated
        // even from adb root), so /data/local/tmp is the one path adb push can reach that
        // the app can also read (world-readable, requires `adb shell setenforce 0` first
        // since untrusted_app can't read shell_data_file under enforcing SELinux).
        val videoFile = File("/data/local/tmp", REPLAY_FILE_NAME)
        if (!videoFile.exists()) {
            Log.d(TAG, "Replay spike: no file at ${videoFile.absolutePath}, skipping")
            return
        }
        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val endUs = durationMs * 1_000L
                Log.d(TAG, "Replay spike: duration=${durationMs}ms, sampling every 1s")
                runOnUiThread { replayFramePreview?.visibility = android.view.View.VISIBLE }
                var sampledCount = 0
                var tUs = 0L
                while (tUs < endUs) {
                    val bitmap = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        // MediaPipe's AndroidPacketCreator.createImage() requires ARGB_8888
                        // strictly -- getFrameAtTime() returns RGB_565 on this device, which
                        // throws UnsupportedOperationException if passed straight through.
                        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                            bitmap
                        } else {
                            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        // Render the actual decoded frame so the spike is visible on
                        // screen, not just the derived text overlay (cameraPreview stays
                        // black in this mode -- see the kdoc above this function).
                        runOnUiThread { replayFramePreview?.setImageBitmap(argbBitmap) }
                        faceLandmarkerClient?.detectAsync(argbBitmap, tUs / 1_000.0)
                        sampledCount++
                    } else {
                        Log.w(TAG, "Replay spike: no frame decoded at t=${tUs / 1_000}ms")
                    }
                    tUs += REPLAY_SAMPLE_INTERVAL_US
                }
                Log.d(TAG, "Replay spike: finished, fed $sampledCount frames")
            } catch (e: Exception) {
                Log.e(TAG, "Replay spike failed", e)
            } finally {
                retriever.release()
            }
        }.start()
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

        // Spike-only: push raw blendshape scores onto the on-screen overlay so a manual
        // test run is visible on the emulator/device screen, not just in logcat. Not
        // PERCLOS -- that's still DrowsinessScoreCalculator.kt's unbuilt job.
        val avgBlink = ((eyeBlinkLeft ?: 0f) + (eyeBlinkRight ?: 0f)) / 2f
        DebugOverlayState.instance.updateFromReplaySpike(
            eyeOpenProbability = 1f - avgBlink,
            driverState = if (avgBlink > 0.5f) "SPIKE: EYES CLOSING" else "SPIKE: EYES OPEN",
        )
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
        const val REPLAY_FILE_NAME = "replay_test.mp4"
        const val REPLAY_SAMPLE_INTERVAL_US = 1_000_000L
    }
}
