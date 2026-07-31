package com.vitalguard.ai

import android.util.Log

/**
 * Thin FSM — owns latch-until-explicit-signal, idempotency, connection-loss
 * fallback, and gateway crash-safety. Trusts `payload.state` directly and
 * does not debounce it itself: `payload.state` is `_state_for_score()`'s
 * instantaneous threshold read, not a sustained value. Sustain/cooldown
 * lives entirely in the emitters' *publish gate* (trigger_emitter.py),
 * which now fires on either the drowsiness OR the distraction emitter's
 * edge (main.py's `run_real_video()`: `signal in (...) or
 * distraction_signal in (...)`) -- so a delivered CRITICAL can arrive on
 * a distraction-only publish edge without drowsiness's own emitter having
 * sustained it. See design doc Decision 6.
 */
class DrowsinessController(
    private val climateGateway: ClimateActuatorGateway,
    private val alertArbiter: AlertArbiter
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
            alertArbiter.setDrowsinessCriticalActive(true)
            alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS)
            lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure applying drowsiness override: ${t.message}")
            lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
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
            alertArbiter.setDrowsinessCriticalActive(false)
            alertArbiter.stopAlert(AlertSource.DROWSINESS)
            lastGatewayAction = GatewayActionStatus.REVERTED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        }
    }
}
