package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugOverlayStateTest {
    @Test
    fun `default snapshot starts in an unknown, not-receiving state`() {
        val state = DebugOverlayState()
        val snapshot = state.flow.value

        assertEquals("UNKNOWN", snapshot.driverState)
        assertEquals(false, snapshot.receivingTrigger)
        assertEquals("NONE", snapshot.lastGatewayAction)
    }

    @Test
    fun `updateFromPayload reflects the latest trigger's feature values`() {
        val state = DebugOverlayState()
        val payload = TriggerPayload(
            timestampMs = 1000L, source = "test", score = 0.9f, confidence = 1.0f,
            state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1,
            features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
            reason = "test", correlationId = "vg-0001",
            distraction = DistractionInfo(
                score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
                handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test",
            ),
        )

        state.updateFromPayload(payload)
        val snapshot = state.flow.value

        assertEquals(0.8f, snapshot.perclos)
        assertEquals(0.1f, snapshot.eyeOpenProbability)
        assertEquals(28.0f, snapshot.headEulerAngleX)
        assertEquals("CRITICAL", snapshot.driverState)
        assertEquals(true, snapshot.receivingTrigger)
    }

    @Test
    fun `markConnectionLost flips receivingTrigger to false without touching last feature values`() {
        val state = DebugOverlayState()
        val payload = TriggerPayload(
            timestampMs = 1000L, source = "test", score = 0.9f, confidence = 1.0f,
            state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1,
            features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
            reason = "test", correlationId = "vg-0001",
            distraction = DistractionInfo(
                score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
                handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test",
            ),
        )
        state.updateFromPayload(payload)

        state.markConnectionLost()
        val snapshot = state.flow.value

        assertEquals(false, snapshot.receivingTrigger)
        assertEquals(0.8f, snapshot.perclos, 0.001f) // last-known value preserved, not zeroed
    }

    @Test
    fun `updateGatewayAction reflects the controller's last outcome`() {
        val state = DebugOverlayState()

        state.updateGatewayAction("OVERRIDE_FAILED")

        assertEquals("OVERRIDE_FAILED", state.flow.value.lastGatewayAction)
    }
}
