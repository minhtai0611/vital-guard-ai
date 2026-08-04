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
 *
 * As of the alert-preferences-parked-suppression feature, this class also
 * gates responses on `isParked` (via `onParkedStateChanged()`, never on the
 * escalation-level re-fire logic above) and on the driver's `AlertPreferences`
 * (climate/voice can each be independently disabled). `lastGatewayAction` was
 * redefined from "did the climate gateway succeed" to "did ANY enabled
 * channel succeed" (`anySucceeded`), since climate and voice now fail
 * independently and neither should mask the other's status on the Debug
 * Overlay. `revertToBaseline()`'s gateway calls were decoupled from a single
 * try/catch so a climate-gateway exception can never suppress
 * `setDrowsinessCriticalActive(false)` -- see
 * docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md.
 */
class DrowsinessController(
    private val climateGateway: ClimateActuatorGateway,
    private val alertArbiter: AlertArbiter,
    private val alertPreferencesStore: AlertPreferencesStore,
) {
    enum class GatewayActionStatus { NONE, OVERRIDE_APPLIED, OVERRIDE_FAILED, REVERTED, REVERT_FAILED }

    private val TAG = "VitalGuardController"

    var lastGatewayAction: GatewayActionStatus = GatewayActionStatus.NONE
        private set

    private var latched = false
    private var lastCorrelationId: String? = null
    private var isParked = false

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

    fun onParkedStateChanged(parked: Boolean) {
        isParked = parked
        if (parked && latched) revertToBaseline()
    }

    // Every CRITICAL payload from here on is meaningful (original edge, a
    // repeat_due tick, or a level_changed tick -- Python is the sole timing
    // authority, see docs/superpowers/specs/2026-07-31-alert-escalation-design.md
    // Section 2/3) -- so this no longer early-returns on `latched`. Climate is
    // only re-applied when the level actually changes; voice fires every time.
    // `isParked` is checked first and, per design, must never set latched=true
    // when suppressing for being parked -- only when a gateway call is
    // actually attempted (docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md).
    private fun handleCritical(level: Int) {
        if (isParked) {
            Log.i(TAG, "Suppressed: vehicle parked")
            return // never set latched=true here -- see design doc's latch-freeze bug
        }
        latched = true
        alertArbiter.setDrowsinessCriticalActive(true) // always -- never gate this by preferences

        val prefs = alertPreferencesStore.get()
        var anySucceeded = false

        if (prefs.climateEnabled) {
            if (lastAppliedClimateLevel != level) {
                try {
                    climateGateway.applyDrowsinessOverride(level)
                    lastAppliedClimateLevel = level
                    anySucceeded = true
                } catch (t: Throwable) {
                    Log.e(TAG, "Climate gateway failure applying drowsiness override at level $level: ${t.message}")
                    // lastAppliedClimateLevel is NOT set here -- the next payload at the
                    // same level will retry naturally, it is not treated as "already applied".
                }
            } else {
                anySucceeded = true // already applied at this level -- current state is correct
            }
        }
        if (prefs.voiceEnabled) {
            try {
                alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS, level)
                anySucceeded = true
            } catch (t: Throwable) {
                Log.e(TAG, "Voice gateway failure requesting drowsiness voice alert at level $level: ${t.message}")
            }
        }

        lastGatewayAction = if (anySucceeded) GatewayActionStatus.OVERRIDE_APPLIED else GatewayActionStatus.OVERRIDE_FAILED
        DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
    }

    private fun handleNonCritical() {
        if (!latched) return // nothing to revert — never fabricate an action from missing prior state
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        lastAppliedClimateLevel = null
        alertArbiter.setDrowsinessCriticalActive(false) // always -- decoupled from climate's try/catch below
        alertArbiter.stopAlert(AlertSource.DROWSINESS)   // safe unconditionally -- has its own ownership check
        try {
            climateGateway.revertToBaseline()
            lastGatewayAction = GatewayActionStatus.REVERTED
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
        }
        DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
    }
}
