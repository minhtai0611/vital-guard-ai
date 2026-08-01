package com.vitalguard.ai

/** Hysteresis + sustain over vehicle speed, same shape as trigger_emitter.py's
 * TriggerEmitter. Enter/exit use different threshold VALUES (not just
 * different sustain windows) to avoid flicker right at one boundary speed.
 * Exit sustain is intentionally much shorter than enter sustain -- erring
 * toward resuming the safety response quickly beats erring toward staying
 * suppressed. All 4 defaults are an unvalidated baseline. */
class ParkedStateTracker(
    private val enterThresholdKmh: Float = 10f,
    private val enterSustainMs: Long = 30_000L,
    private val exitThresholdKmh: Float = 15f,
    private val exitSustainMs: Long = 2_000L,
) {
    private var isParked = false
    private var belowSince: Long? = null
    private var aboveSince: Long? = null

    /** true = just entered parked, false = just resumed, null = no transition. */
    fun update(speedKmh: Float?, nowMs: Long): Boolean? {
        if (speedKmh == null) {
            belowSince = null
            if (isParked) {
                isParked = false
                return false // lost the speed signal -- never fabricate "still parked"
            }
            return null
        }
        if (!isParked) {
            if (speedKmh < enterThresholdKmh) {
                if (belowSince == null) belowSince = nowMs
                if (nowMs - belowSince!! >= enterSustainMs) {
                    isParked = true
                    belowSince = null
                    return true
                }
            } else {
                belowSince = null
            }
        } else {
            if (speedKmh > exitThresholdKmh) {
                if (aboveSince == null) aboveSince = nowMs
                if (nowMs - aboveSince!! >= exitSustainMs) {
                    isParked = false
                    aboveSince = null
                    return false
                }
            } else {
                aboveSince = null
            }
        }
        return null
    }
}
