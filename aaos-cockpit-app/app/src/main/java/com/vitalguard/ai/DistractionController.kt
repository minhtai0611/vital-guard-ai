package com.vitalguard.ai

import android.util.Log

/**
 * Independent FSM for distraction, arbitrated through the same
 * [AlertArbiter] as [DrowsinessController] but tracking its own latch
 * state entirely separately -- drowsiness and distraction are
 * physiologically independent and must never be merged into one state
 * enum (see design doc Decision 5).
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
            TriggerPayload.STATE_CRITICAL -> handleCritical()
            else -> handleNonCritical()
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost -- reverting distraction reminder to baseline")
        revertToBaseline()
    }

    private fun handleCritical() {
        if (latched) return
        latched = true
        try {
            alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure requesting distraction reminder: ${t.message}")
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
