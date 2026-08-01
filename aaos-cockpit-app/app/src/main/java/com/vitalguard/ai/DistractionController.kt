package com.vitalguard.ai

import android.util.Log

/**
 * Independent FSM for distraction, arbitrated through the same
 * [AlertArbiter] as [DrowsinessController] but tracking its own latch
 * state entirely separately -- drowsiness and distraction are
 * physiologically independent and must never be merged into one state
 * enum (see design doc Decision 5).
 *
 * As of the alert-escalation feature, `handleCritical()` re-acts on every
 * CRITICAL payload's `distraction.escalationLevel` rather than latching
 * into a no-op after the first call -- see
 * docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3.
 */
class DistractionController(private val alertArbiter: AlertArbiter) {
    private val TAG = "VitalGuardDistractionController"

    private var latched = false
    private var lastCorrelationId: String? = null

    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return
        }
        lastCorrelationId = payload.correlationId

        when (payload.distraction.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical(payload.distraction.escalationLevel)
            else -> handleNonCritical()
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost -- reverting distraction reminder to baseline")
        revertToBaseline()
    }

    // No latch-and-return: every CRITICAL payload is meaningful (original edge,
    // repeat_due, or level_changed) -- same reasoning as DrowsinessController,
    // see docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3.
    // There is no climate channel here, so unlike DrowsinessController there is
    // no per-level dedup needed -- voice simply fires every time.
    private fun handleCritical(level: Int) {
        latched = true
        try {
            alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION, level)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure requesting distraction reminder at level $level: ${t.message}")
        }
    }

    private fun handleNonCritical() {
        if (!latched) return
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        try {
            alertArbiter.stopAlert(AlertSource.DISTRACTION)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure stopping distraction reminder: ${t.message}")
        }
    }
}
