package com.vitalguard.ai

import android.util.Log

/**
 * Thin FSM — trusts trigger_emitter.py's hysteresis/sustain/cooldown (any
 * CRITICAL payload received here already represents a sustained state); this
 * class only owns latch-until-explicit-signal, idempotency, connection-loss
 * fallback, and gateway crash-safety. See design doc Decision 6.
 */
class DrowsinessController(
    private val climateGateway: ClimateActuatorGateway,
    private val voiceGateway: VoiceAlertGateway
) {
    enum class GatewayActionStatus { NONE, OVERRIDE_APPLIED, OVERRIDE_FAILED, REVERTED, REVERT_FAILED }

    private val TAG = "VitalGuardController"

    var lastGatewayAction: GatewayActionStatus = GatewayActionStatus.NONE
        private set

    private var latched = false
    private var lastCorrelationId: String? = null

    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return // duplicate delivery of an already-processed payload — idempotency
        }
        lastCorrelationId = payload.correlationId

        when (payload.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical()
            else -> handleNonCritical() // NORMAL, WARNING, UNKNOWN all revert to baseline
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost (3 consecutive poll failures) — reverting to safe baseline")
        revertToBaseline()
    }

    private fun handleCritical() {
        if (latched) return // already applied for the current episode
        latched = true
        try {
            climateGateway.applyDrowsinessOverride()
            voiceGateway.triggerAlert()
            lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure applying drowsiness override: ${t.message}")
            lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
            // no retry: GATEWAY_MODE is the intended recovery path for a broken Real gateway
        }
    }

    private fun handleNonCritical() {
        if (!latched) return // nothing to revert — never fabricate an action from missing prior state
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        try {
            climateGateway.revertToBaseline()
            voiceGateway.stopAlert()
            lastGatewayAction = GatewayActionStatus.REVERTED
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
        }
    }
}
