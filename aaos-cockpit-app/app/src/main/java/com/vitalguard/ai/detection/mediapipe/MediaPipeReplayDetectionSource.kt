package com.vitalguard.ai.detection.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.vitalguard.ai.DistractionInfo
import com.vitalguard.ai.TriggerFeatures
import com.vitalguard.ai.TriggerPayload
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-dev-only replacement for the Container Node -> HTTP TriggerPollClient path
 * (root CLAUDE.md's "Trigger Delivery" section still names the network pin as the
 * decided architecture for the real CarSky demo -- this class does not revert that
 * decision, it only gives this on-device MediaPipe port something to feed into
 * DrowsinessController/DistractionController on a dev machine that has no Container
 * Node reachable). Decodes a device-local MP4 via MediaMetadataRetriever, runs each
 * sampled frame through [FaceLandmarkerClient], and turns sustained high eye-closure
 * into real [TriggerPayload]s.
 *
 * The HIGH/LOW_SUSTAIN_FRAMES hysteresis gate below exists here, in Kotlin, only
 * because there is no Python EscalationTracker upstream of this path the way there
 * is for the real Container Node -> HTTP flow: [com.vitalguard.ai.DrowsinessController]
 * trusts `payload.state` directly by design (see its kdoc) and does not itself
 * debounce -- so *something* upstream of it must, per root CLAUDE.md's mandatory
 * "State Machine Hardening" debounce/hysteresis requirement.
 *
 * Not the final ReplayFileFrameSource/DetectionBackendMode abstraction from the
 * MediaPipe migration plan -- this is a spike proving the wiring end-to-end
 * (MediaPipe -> FSM -> real gateways) with a live-camera path and a real PERCLOS
 * port (DrowsinessScoreCalculator.kt) still to come.
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

    private var consecutiveHigh = 0
    private var consecutiveLow = 0
    private var currentState = TriggerPayload.STATE_NORMAL
    private var escalationLevel = 0
    private var lastStateChangeAtMs = 0L
    private var lastReportedState: String? = null
    private var lastCriticalReportAtMs = 0L

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
                Log.d(TAG, "Replay detection: duration=${durationMs}ms, sampling every 1s")
                var sampledCount = 0
                var tUs = 0L
                while (tUs < endUs) {
                    val bitmap = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bitmap != null) {
                        // MediaPipe's AndroidPacketCreator.createImage() requires ARGB_8888
                        // strictly -- getFrameAtTime() returns RGB_565 on some devices.
                        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                            bitmap
                        } else {
                            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        onFrameDecoded(argbBitmap)
                        client.detectAsync(argbBitmap, tUs / 1_000.0)
                        sampledCount++
                    } else {
                        Log.w(TAG, "Replay detection: no frame decoded at t=${tUs / 1_000}ms")
                    }
                    tUs += SAMPLE_INTERVAL_US
                }
                Log.d(TAG, "Replay detection: finished, fed $sampledCount frames")
            } catch (e: Exception) {
                Log.e(TAG, "Replay detection failed", e)
            } finally {
                retriever.release()
            }
        }.start()
    }

    // LIVE_STREAM delivers results from MediaPipe's own internal thread pool -- observed
    // on-device coming from several distinct worker threads concurrently, so the
    // consecutiveHigh/consecutiveLow/currentState mutation below must be serialized or
    // concurrent callbacks corrupt the sustain counters via unsynchronized read-modify-write.
    @Synchronized
    private fun handleResult(result: FaceLandmarkerResult) {
        val blendshapes = result.faceBlendshapes().orElse(emptyList()).firstOrNull().orEmpty()
        val eyeBlinkLeft = blendshapes.find { it.categoryName() == "eyeBlinkLeft" }?.score() ?: 0f
        val eyeBlinkRight = blendshapes.find { it.categoryName() == "eyeBlinkRight" }?.score() ?: 0f
        val avgBlink = (eyeBlinkLeft + eyeBlinkRight) / 2f
        val eyeOpenProbability = 1f - avgBlink

        // Debounce/hysteresis gate: a single noisy frame must not flip state either
        // way -- needs HIGH_SUSTAIN_FRAMES consecutive high-blink samples to enter
        // CRITICAL, LOW_SUSTAIN_FRAMES consecutive low-blink samples to recover.
        when {
            avgBlink >= HIGH_THRESHOLD -> {
                consecutiveHigh++
                consecutiveLow = 0
            }
            avgBlink <= LOW_THRESHOLD -> {
                consecutiveLow++
                consecutiveHigh = 0
            }
            else -> {
                consecutiveHigh = 0
                consecutiveLow = 0
            }
        }

        Log.d(TAG, "avgBlink=$avgBlink consecutiveHigh=$consecutiveHigh consecutiveLow=$consecutiveLow state=$currentState")

        // Cooldown (root CLAUDE.md "State Machine Hardening"): even once sustained, a
        // state change is only honored if enough wall-clock time passed since the last
        // one -- otherwise LIVE_STREAM's async, out-of-temporal-order result delivery
        // (see this function's kdoc) can flip CRITICAL/NORMAL back and forth within the
        // same second, thrashing the real climate/voice gateways downstream.
        val now = System.currentTimeMillis()
        val cooledDown = now - lastStateChangeAtMs >= STATE_CHANGE_COOLDOWN_MS
        if (cooledDown && currentState != TriggerPayload.STATE_CRITICAL && consecutiveHigh >= HIGH_SUSTAIN_FRAMES) {
            currentState = TriggerPayload.STATE_CRITICAL
            escalationLevel = 1
            lastStateChangeAtMs = now
        } else if (cooledDown && currentState == TriggerPayload.STATE_CRITICAL && consecutiveLow >= LOW_SUSTAIN_FRAMES) {
            currentState = TriggerPayload.STATE_NORMAL
            escalationLevel = 0
            lastStateChangeAtMs = now
        }

        // Publish gate mirroring the real Container Node path's Python EscalationTracker
        // (root CLAUDE.md's "Known Deviations" section): DrowsinessController fires its
        // gateways on every delivered CRITICAL payload by design, trusting that upstream
        // only publishes on a meaningful edge/repeat/level-change tick -- MediaPipe's
        // LIVE_STREAM callback has no such throttling built in (it fires per sampled
        // frame, ~10/s here), so without this gate voice/climate would be invoked on
        // nearly every frame while CRITICAL persists (confirmed on-device: TTS utterances
        // overlapping and cutting each other off).
        val stateChanged = currentState != lastReportedState
        val repeatDue = currentState == TriggerPayload.STATE_CRITICAL &&
            now - lastCriticalReportAtMs >= REPEAT_INTERVAL_MS
        if (!stateChanged && !repeatDue) return
        lastReportedState = currentState
        if (currentState == TriggerPayload.STATE_CRITICAL) lastCriticalReportAtMs = now

        val payload = TriggerPayload(
            timestampMs = System.currentTimeMillis(),
            source = "on-device-replay-spike",
            score = avgBlink,
            confidence = 1f,
            state = currentState,
            escalationLevel = escalationLevel,
            features = TriggerFeatures(
                perclos = 0f, // real PERCLOS port (DrowsinessScoreCalculator.kt) not built yet
                eyeOpenProbability = eyeOpenProbability,
                headEulerAngleX = 0f, // HeadPose.kt port not built yet
            ),
            reason = if (currentState == TriggerPayload.STATE_CRITICAL) {
                "sustained high eye closure (on-device replay spike)"
            } else {
                ""
            },
            correlationId = "replay-${correlationCounter.incrementAndGet()}",
            distraction = NO_DISTRACTION,
        )
        onPayload(payload)
    }

    fun close() = client.close()

    companion object {
        private const val TAG = "MediaPipeReplayDetection"
        private const val SAMPLE_INTERVAL_US = 1_000_000L
        private const val HIGH_THRESHOLD = 0.6f
        private const val LOW_THRESHOLD = 0.3f
        private const val HIGH_SUSTAIN_FRAMES = 2
        private const val LOW_SUSTAIN_FRAMES = 2
        private const val STATE_CHANGE_COOLDOWN_MS = 3_000L
        private const val REPEAT_INTERVAL_MS = 5_000L

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
