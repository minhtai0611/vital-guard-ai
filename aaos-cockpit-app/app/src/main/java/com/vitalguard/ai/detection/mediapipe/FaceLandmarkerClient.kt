package com.vitalguard.ai.detection.mediapipe

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Throwaway spike client -- proves the Android tasks-vision library accepts the
 * same .task bundle dms-ai-engine's Python side uses, and is meant to capture
 * real blendshape/facialTransformationMatrixes layout data on-device ahead of
 * the HeadPose.kt port (plan's flagged highest-risk port: the pitch/yaw axis
 * mapping is empirically, not analytically, derived from the matrix layout).
 *
 * LIVE_STREAM + detectAsync() replaces Python's synchronous VIDEO +
 * detect_for_video() -- the one genuinely architectural change in the whole
 * port (see services/face_landmarker_client.py).
 */
class FaceLandmarkerClient(
    context: Context,
    onResult: (FaceLandmarkerResult) -> Unit,
    onError: (RuntimeException) -> Unit,
) {
    private val timestamp = MonotonicTimestamp()

    private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .setDelegate(Delegate.CPU)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener(onError)
            .build()
    )

    fun detectAsync(bitmap: Bitmap, rawTimestampMs: Double) {
        val ts = timestamp.next(rawTimestampMs)
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
    }

    fun close() {
        landmarker.close()
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/face_landmarker.task"
    }
}
