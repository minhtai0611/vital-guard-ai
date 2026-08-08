package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/eye_state.py. Eye-closure signal from
 * MediaPipe Face Landmarker's face_blendshapes (eyeBlinkLeft/eyeBlinkRight,
 * continuous [0,1]).
 *
 * BLINK_CLOSE_THRESHOLD/BLINK_REOPEN_THRESHOLD form a two-threshold
 * hysteresis: values between the two thresholds are deliberately "sticky"
 * in whichever state was last entered. This targets an evidenced failure: a
 * real drowsy video's score climbed to 0.800 then dropped sharply the
 * instant one frame's raw signal crossed a single instantaneous threshold,
 * right before the 0.85 CRITICAL threshold would have been reached.
 */
const val BLINK_CLOSE_THRESHOLD = 0.55f
const val BLINK_REOPEN_THRESHOLD = 0.35f

fun blinkScore(blendshapes: Map<String, Float>): Float {
    val left = blendshapes["eyeBlinkLeft"] ?: 0f
    val right = blendshapes["eyeBlinkRight"] ?: 0f
    return (left + right) / 2f
}

class BlinkStateTracker {
    private var closed = false

    // `now` is accepted but unused in the body -- matches
    // eye_state.py::BlinkStateTracker.update()'s own signature exactly
    // (kept for interface parity with the other trackers the orchestrator
    // drives identically), not an oversight.
    fun update(score: Float, now: Double): Boolean {
        if (!closed && score >= BLINK_CLOSE_THRESHOLD) {
            closed = true
        } else if (closed && score <= BLINK_REOPEN_THRESHOLD) {
            closed = false
        }
        return closed
    }
}
