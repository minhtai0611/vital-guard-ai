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
 *
 * As of the alert-escalation feature, `handleCritical()` also no longer
 * latches into a no-op after the first call: every CRITICAL payload's
 * `escalationLevel` is trusted directly too, for the same reason --
 * Python's EscalationTracker (services/escalation_tracker.py) is the sole
 * timing authority deciding when a repeat/level-change publish is
 * meaningful, this class only reacts to it (see
 * docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3).
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

    private var lastAppliedClimateLevel: Int? = null // null = no override currently applied

    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return // duplicate delivery of an already-processed payload — idempotency
        }
        lastCorrelationId = payload.correlationId

        when (payload.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical(payload.escalationLevel)
            else -> handleNonCritical() // NORMAL, WARNING, UNKNOWN all revert to baseline
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost (3 consecutive poll failures) — reverting to safe baseline")
        revertToBaseline()
    }

    // Every CRITICAL payload from here on is meaningful (original edge, a
    // repeat_due tick, or a level_changed tick -- Python is the sole timing
    // authority, see docs/superpowers/specs/2026-07-31-alert-escalation-design.md
    // Section 2/3) -- so this no longer early-returns on `latched`. Climate is
    // only re-applied when the level actually changes; voice fires every time.
    private fun handleCritical(level: Int) {
        latched = true
        if (lastAppliedClimateLevel != level) {
            try {
                climateGateway.applyDrowsinessOverride(level)
                lastAppliedClimateLevel = level
                alertArbiter.setDrowsinessCriticalActive(true)
                lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
                DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
            } catch (t: Throwable) {
                Log.e(TAG, "Gateway failure applying drowsiness override at level $level: ${t.message}")
                lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
                DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
                // lastAppliedClimateLevel is NOT set here (this line only runs if the
                // try block above threw before reaching it) -- the next payload at the
                // same level will retry naturally, it is not treated as "already applied".
            }
        } else {
            alertArbiter.setDrowsinessCriticalActive(true)
        }
        try {
            alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS, level)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure requesting drowsiness voice alert at level $level: ${t.message}")
        }
    }

    private fun handleNonCritical() {
        if (!latched) return // nothing to revert — never fabricate an action from missing prior state
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        lastAppliedClimateLevel = null
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
