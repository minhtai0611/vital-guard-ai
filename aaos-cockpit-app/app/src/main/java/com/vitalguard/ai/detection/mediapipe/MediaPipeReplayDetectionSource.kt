package com.vitalguard.ai.detection.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.vitalguard.ai.DistractionInfo
import com.vitalguard.ai.TriggerFeatures
import com.vitalguard.ai.TriggerPayload
import com.vitalguard.ai.drowsiness.BlinkStateTracker
import com.vitalguard.ai.drowsiness.DrowsinessPipelineConfig
import com.vitalguard.ai.drowsiness.DrowsinessScoreCalculator
import com.vitalguard.ai.drowsiness.EscalationTracker
import com.vitalguard.ai.drowsiness.FacePresenceSignal
import com.vitalguard.ai.drowsiness.FacePresenceTracker
import com.vitalguard.ai.drowsiness.FrameFeatures
import com.vitalguard.ai.drowsiness.HeadPose
import com.vitalguard.ai.drowsiness.TriggerEmitter
import com.vitalguard.ai.drowsiness.TriggerSignal
import com.vitalguard.ai.drowsiness.blinkScore
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-dev-only replacement for the Container Node -> HTTP TriggerPollClient
 * path that used to run alongside this (root CLAUDE.md's original "Trigger
 * Delivery" decision -- retired by
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md, which
 * this class now fully implements). Decodes a device-local MP4 via
 * MediaMetadataRetriever at the video's own native frame rate, runs each
 * frame through [FaceLandmarkerClient], and turns sustained eye-closure +
 * head-droop into real [TriggerPayload]s using the same PERCLOS/sustain/
 * escalation math as dms-ai-engine/main.py::run_real_video() (drowsiness
 * slice only -- distraction stays hardcoded to NO_DISTRACTION, see the
 * design doc's non-goals).
 */
class MediaPipeReplayDetectionSource(
    context: Context,
    private val onPayload: (TriggerPayload) -> Unit,
    private val onFrameDecoded: (Bitmap) -> Unit = {},
) {
    private val client = FaceLandmarkerClient(
        context = context,
        onResult = ::handleResult,
        onError = { e -> Log.e(TAG, "FaceLandmarker error", e) },
    )
    private val correlationCounter = AtomicLong(0)

    private val blinkTracker = BlinkStateTracker()
    private val calc = DrowsinessScoreCalculator()
    private val triggerEmitter = TriggerEmitter()
    private val faceTracker = FacePresenceTracker()
    private val escalation = EscalationTracker(
        levelUpSeconds = DrowsinessPipelineConfig.LEVEL_UP_SECONDS,
        repeatIntervalSeconds = DrowsinessPipelineConfig.REPEAT_INTERVAL_SECONDS,
    )

    private val calibrationPitchSamples = mutableListOf<Double>()
    private var baselineCalibrated = false

    // Counts callbacks actually RECEIVED from MediaPipe's LIVE_STREAM mode,
    // as opposed to sampledCount in runIfPresent which only counts frames SENT
    // into detectAsync(). LIVE_STREAM drops inputs when its internal graph is
    // busy, so fed != received is the signal that frames were silently
    // dropped. Mutated only inside handleResult, which is @Synchronized, so
    // no separate lock is needed here.
    private var receivedCount = 0

    fun runIfPresent(videoFile: File) {
        if (!videoFile.exists()) {
            Log.d(TAG, "No replay file at ${videoFile.absolutePath}, skipping")
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

                // Native fps, matching dms-ai-engine/main.py's own fallback
                // semantics (fps=30.0 if missing/non-finite/non-positive).
                // METADATA_KEY_CAPTURE_FRAMERATE is officially "if available"
                // -- usually absent on normally-recorded clips, so this
                // commonly falls back to 30.0 in practice. Accepted,
                // documented trade-off (design doc D2), not a new gap.
                val reportedFps = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()
                val fps = if (reportedFps != null && reportedFps.isFinite() && reportedFps > 0.0) {
                    reportedFps
                } else {
                    DrowsinessPipelineConfig.FALLBACK_FPS
                }
                val sampleIntervalUs = (1_000_000.0 / fps).toLong()

                Log.d(TAG, "Replay detection: duration=${durationMs}ms, sampling at ${fps}fps")
                var sampledCount = 0
                var tUs = 0L
                while (tUs < endUs) {
                    // OPTION_CLOSEST (not OPTION_CLOSEST_SYNC): sparse-keyframe
                    // replay files (e.g. drowsy.mp4, 1 keyframe / 100 frames)
                    // make OPTION_CLOSEST_SYNC snap to the same nearby sync
                    // frame on every seek, silently pinning pitch/score near-
                    // constant for the whole run (empirically confirmed during
                    // Task 9 probing -- see task-9-report.md's Method section
                    // and the Task 9 fix-up report). OPTION_CLOSEST decodes
                    // forward from the nearest keyframe to the exact requested
                    // timestamp, matching the reference CSV. Slower per-seek on
                    // long clips, but this is a dev/test-only replay path, not
                    // the real-time on-device pipeline -- accepted trade-off.
                    val bitmap = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                            bitmap
                        } else {
                            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
                        }
                        onFrameDecoded(argbBitmap)
                        client.detectAsync(argbBitmap, tUs / 1_000.0)
                        sampledCount++
                    } else {
                        Log.w(TAG, "Replay detection: no frame decoded at t=${tUs / 1_000}ms")
                    }
                    tUs += sampleIntervalUs
                }
                Log.d(TAG, "Replay detection: finished, fed $sampledCount frames, received $receivedCount callbacks")
            } catch (e: Exception) {
                Log.e(TAG, "Replay detection failed", e)
            } finally {
                retriever.release()
            }
        }.start()
    }

    // Empirically confirmed on-device (Task 9) against
    // dms-ai-engine/out/evidence_drowsy_fresh_build.csv's known-correct pitch
    // trajectory: FaceLandmarkerResult.facialTransformationMatrixes()'s flat
    // float[16] is laid out opposite to the row-major convention
    // HeadPose.kt's math assumes (matrix[row*4+col]), so it must be
    // transposed before being passed in. As-is was off by 7-46 degrees with
    // the wrong sign at all three reference checkpoints; transposed matched
    // within ~1 degree at all three. See HeadPose.kt's kdoc for context.
    private fun transpose4x4(m: FloatArray): FloatArray {
        val t = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) t[col * 4 + row] = m[row * 4 + col]
        return t
    }

    // LIVE_STREAM delivers results from MediaPipe's own internal thread pool
    // -- observed on-device coming from several distinct worker threads
    // concurrently, so all state mutation below must be serialized or the
    // sustain/escalation counters get corrupted by unsynchronized
    // read-modify-write. Wrapped in catch(Throwable) per this module's
    // "Catch Throwable" rule -- one bad frame (malformed matrix, unexpected
    // index) must not silently kill this callback thread.
    @Synchronized
    private fun handleResult(result: FaceLandmarkerResult) {
        receivedCount++
        try {
            handleResultUnsafe(result)
        } catch (t: Throwable) {
            Log.e(TAG, "handleResult failed for this frame -- continuing", t)
        }
    }

    private fun handleResultUnsafe(result: FaceLandmarkerResult) {
        val now = result.timestampMs() / 1_000.0
        val hasFace = result.faceBlendshapes().map { it.isNotEmpty() }.orElse(false)

        val faceSignal = faceTracker.update(hasFace, now)
        if (faceSignal == FacePresenceSignal.Unknown) {
            escalation.reset()
            publish(
                state = TriggerPayload.STATE_UNKNOWN,
                score = 0.0, perclos = 0.0, eyeOpenProbability = 0.0, headEulerAngleX = 0.0,
                escalationLevel = 1, reason = "lost_face",
            )
            return
        }
        if (!hasFace) return // still within FacePresenceTracker's grace window -- no payload

        val blendshapes = result.faceBlendshapes().get().first().associate { it.categoryName() to it.score() }
        val blink = blinkScore(blendshapes)
        val eyeClosed = blinkTracker.update(blink, now)

        val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
        val pitchDeg = if (matrix != null) HeadPose.extractPitchDeg(transpose4x4(matrix)) else 0.0

        if (!baselineCalibrated) {
            if (now < DrowsinessPipelineConfig.BASELINE_CALIBRATION_SECONDS) {
                calibrationPitchSamples.add(pitchDeg)
            } else {
                if (calibrationPitchSamples.isNotEmpty()) {
                    calc.calibrateBaseline(calibrationPitchSamples.average())
                }
                baselineCalibrated = true
            }
        }

        val score = calc.addFrame(FrameFeatures(now, eyeClosed, pitchDeg))
        val signal = triggerEmitter.update(score, now)
        val state = stateForScore(score)
        val (level, repeatDue, levelChanged) = escalation.update(triggerEmitter.criticalActive, now)

        if (signal != null || repeatDue || levelChanged) {
            publish(
                state = state, score = score, perclos = calc.computeScore(),
                eyeOpenProbability = 1.0 - blink, headEulerAngleX = pitchDeg,
                escalationLevel = level,
                reason = when (signal) {
                    TriggerSignal.Critical -> "sustained_high_score"
                    TriggerSignal.Recovered -> "recovered"
                    null -> "unchanged"
                },
            )
        }
    }

    private fun stateForScore(score: Double): String = when {
        score >= DrowsinessPipelineConfig.ENTER_THRESHOLD -> TriggerPayload.STATE_CRITICAL
        score > DrowsinessPipelineConfig.EXIT_THRESHOLD -> TriggerPayload.STATE_WARNING
        else -> TriggerPayload.STATE_NORMAL
    }

    private fun publish(
        state: String, score: Double, perclos: Double, eyeOpenProbability: Double,
        headEulerAngleX: Double, escalationLevel: Int, reason: String,
    ) {
        onPayload(
            TriggerPayload(
                timestampMs = System.currentTimeMillis(),
                source = "on-device-kotlin",
                score = score.toFloat(),
                confidence = 1f,
                state = state,
                escalationLevel = escalationLevel,
                features = TriggerFeatures(
                    perclos = perclos.toFloat(),
                    eyeOpenProbability = eyeOpenProbability.toFloat(),
                    headEulerAngleX = headEulerAngleX.toFloat(),
                ),
                reason = reason,
                correlationId = "vg-ondevice-${correlationCounter.incrementAndGet()}",
                distraction = NO_DISTRACTION,
            )
        )
    }

    fun close() = client.close()

    companion object {
        private const val TAG = "MediaPipeReplayDetection"

        private val NO_DISTRACTION = DistractionInfo(
            score = 0f,
            state = TriggerPayload.STATE_NORMAL,
            escalationLevel = 0,
            yawDeg = 0f,
            pitchDeg = 0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN,
            handsOnWheel = true,
            reason = "",
        )
    }
}
