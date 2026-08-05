package com.vitalguard.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OverlaySnapshot(
    val perclos: Float = 0f,
    val eyeOpenProbability: Float = 0f,
    val headEulerAngleX: Float = 0f,
    val driverState: String = "UNKNOWN",
    val receivingTrigger: Boolean = false,
    val lastPollAt: Long = 0L,
    val lastGatewayAction: String = "NONE",
)

/**
 * Process-wide observable snapshot for the mandatory debug overlay (root
 * CLAUDE.md's "Debug Overlay" section). The poller, controller, and Activity
 * all run in this single app process, so a plain singleton StateFlow is
 * sufficient — no cross-process IPC needed.
 *
 * Explicitly NOT covered by this class (per the Task 4 plan's Definition of
 * Done): "remaining cooldown" and "trigger frame rate/frequency" — neither
 * value crosses the wire in contracts/trigger.schema.json today.
 */
class DebugOverlayState {
    private val _flow = MutableStateFlow(OverlaySnapshot())
    val flow: StateFlow<OverlaySnapshot> = _flow

    fun updateFromPayload(payload: TriggerPayload) {
        _flow.value = _flow.value.copy(
            perclos = payload.features.perclos,
            eyeOpenProbability = payload.features.eyeOpenProbability,
            headEulerAngleX = payload.features.headEulerAngleX,
            driverState = payload.state,
            receivingTrigger = true,
            lastPollAt = payload.timestampMs,
        )
    }

    /**
     * Manual test-hook update path for [MainActivity]'s ReplayFileFrameSource spike --
     * NOT part of the real TriggerPayload/FSM contract. Lets a raw on-device
     * FaceLandmarker result move the on-screen overlay live during a spike test, since
     * [updateFromPayload] requires a full [TriggerPayload] this spike doesn't produce.
     */
    fun updateFromReplaySpike(eyeOpenProbability: Float, driverState: String) {
        _flow.value = _flow.value.copy(
            eyeOpenProbability = eyeOpenProbability,
            driverState = driverState,
            receivingTrigger = true,
        )
    }

    fun markConnectionLost() {
        _flow.value = _flow.value.copy(receivingTrigger = false, driverState = "UNKNOWN")
    }

    fun updateGatewayAction(action: String) {
        _flow.value = _flow.value.copy(lastGatewayAction = action)
    }

    companion object {
        val instance = DebugOverlayState()
    }
}
