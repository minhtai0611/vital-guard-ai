package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/trigger_emitter.py.
 *
 * TriggerEmitter decides WHEN to fire a Trigger from a continuous stream of
 * Drowsiness Scores:
 *   - Enter threshold 0.85, must SUSTAIN continuously >= sustainSeconds to fire.
 *   - Does not fire repeatedly while still above threshold (once per episode).
 *   - Only re-arms to fire again once score drops <= exitThreshold (2-threshold
 *     hysteresis, not a single threshold -- avoids oscillation around 0.85).
 *   - cooldownSeconds is an additional safety net in case the sustain logic
 *     has a bug.
 */
sealed class TriggerSignal {
    object Critical : TriggerSignal()
    object Recovered : TriggerSignal()
}

class TriggerEmitter(
    private val enterThreshold: Double = DrowsinessPipelineConfig.ENTER_THRESHOLD,
    private val exitThreshold: Double = DrowsinessPipelineConfig.EXIT_THRESHOLD,
    private val sustainSeconds: Double = DrowsinessPipelineConfig.SUSTAIN_SECONDS,
    private val cooldownSeconds: Double = DrowsinessPipelineConfig.COOLDOWN_SECONDS,
) {
    init {
        require(exitThreshold < enterThreshold) { "exitThreshold must be lower than enterThreshold (hysteresis)" }
    }

    private var aboveSince: Double? = null
    private var lastEmitTime: Double = Double.NEGATIVE_INFINITY
    private var armed = true

    var criticalActive: Boolean = false
        private set

    fun update(score: Double, now: Double): TriggerSignal? {
        if (score >= enterThreshold) {
            if (aboveSince == null) aboveSince = now
            val sustained = (now - aboveSince!!) >= sustainSeconds
            val cooldownOk = (now - lastEmitTime) >= cooldownSeconds
            if (sustained && cooldownOk && armed) {
                armed = false
                lastEmitTime = now
                criticalActive = true
                return TriggerSignal.Critical
            }
        } else {
            aboveSince = null
            if (score <= exitThreshold) {
                armed = true
                if (criticalActive) {
                    criticalActive = false
                    return TriggerSignal.Recovered
                }
            }
        }
        return null
    }
}

/**
 * Detects sustained loss of face (camera occluded, driver out of frame) to
 * emit UNKNOWN -- separate from TriggerEmitter because this is a signal
 * about face PRESENCE, not about the score value.
 */
sealed class FacePresenceSignal {
    object Unknown : FacePresenceSignal()
    object Present : FacePresenceSignal()
}

class FacePresenceTracker(
    private val sustainSeconds: Double = DrowsinessPipelineConfig.FACE_ABSENCE_SUSTAIN_SECONDS,
) {
    private var absentSince: Double? = null
    private var unknownActive = false

    fun update(hasFace: Boolean, now: Double): FacePresenceSignal? {
        if (!hasFace) {
            if (absentSince == null) absentSince = now
            val sustained = (now - absentSince!!) >= sustainSeconds
            if (sustained && !unknownActive) {
                unknownActive = true
                return FacePresenceSignal.Unknown
            }
        } else {
            absentSince = null
            if (unknownActive) {
                unknownActive = false
                return FacePresenceSignal.Present
            }
        }
        return null
    }
}
