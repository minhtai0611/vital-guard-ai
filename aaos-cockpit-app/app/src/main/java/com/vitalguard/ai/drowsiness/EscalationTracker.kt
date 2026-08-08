/**
 * Ported from dms-ai-engine/services/escalation_tracker.py. Computes an
 * escalation level (1/2/3) from how long the underlying emitter's
 * criticalActive has been continuously true -- driven by TriggerEmitter's
 * hysteresis-protected criticalActive, NOT a raw score>threshold check per
 * frame.
 */
package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/escalation_tracker.py. Computes an
 * escalation level (1/2/3) from how long the underlying emitter's
 * criticalActive has been continuously true -- driven by TriggerEmitter's
 * hysteresis-protected criticalActive, NOT a raw score>threshold check per
 * frame.
 */
class EscalationTracker(
    private val levelUpSeconds: List<Double>,
    private val repeatIntervalSeconds: List<Double>,
) {
    init {
        require(repeatIntervalSeconds.size == levelUpSeconds.size + 1) {
            "repeatIntervalSeconds must have exactly one more entry than levelUpSeconds"
        }
    }

    private var criticalSince: Double? = null
    private var lastRepeatTime: Double? = null
    private var lastLevel: Int = 1

    /** Returns (level, repeatDue, levelChanged). */
    fun update(criticalActive: Boolean, now: Double): Triple<Int, Boolean, Boolean> {
        if (!criticalActive) {
            criticalSince = null
            lastRepeatTime = null
            val levelChanged = lastLevel != 1
            lastLevel = 1
            return Triple(1, false, levelChanged)
        }

        if (criticalSince == null) {
            criticalSince = now
            lastRepeatTime = null // no repeat yet in this episode
        }

        val elapsed = now - criticalSince!!
        val level = 1 + levelUpSeconds.count { elapsed >= it }

        val levelChanged = level != lastLevel
        lastLevel = level

        val interval = repeatIntervalSeconds[level - 1]
        // levelChanged also counts as "just repeated" -- otherwise a stale
        // lastRepeatTime (anchored to the PREVIOUS level's interval) could
        // make the next repeatDue fire only 1-2s after the level-change
        // announcement, cutting off the utterance just spoken for the new level.
        val repeatDue = levelChanged || lastRepeatTime == null || (now - lastRepeatTime!!) >= interval
        if (repeatDue) lastRepeatTime = now

        return Triple(level, repeatDue, levelChanged)
    }

    /** Called on UNKNOWN (face lost) -- forces back to level 1 regardless of
     * criticalActive, since the underlying emitter is frozen (not updated)
     * while hasFace=false and cannot itself signal a reset. */
    fun reset() {
        criticalSince = null
        lastRepeatTime = null
        lastLevel = 1
    }
}
