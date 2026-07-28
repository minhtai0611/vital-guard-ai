package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrowsinessControllerTest {
    private lateinit var climate: FakeClimateActuatorGateway
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var controller: DrowsinessController

    @Before
    fun setUp() {
        climate = FakeClimateActuatorGateway()
        voice = FakeVoiceAlertGateway()
        controller = DrowsinessController(climate, voice)
    }

    private fun payload(state: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
        state = state,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
    )

    @Test
    fun `normal operation below threshold never calls gateways`() {
        controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0001"))

        assertFalse(climate.overrideApplied)
        assertFalse(voice.alertTriggered)
    }

    @Test
    fun `idempotency - repeated CRITICAL with same correlationId fires gateways only once`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

        assertTrue(climate.overrideApplied)
        climate.overrideApplied = false // reset the flag to prove no SECOND call happened
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertFalse(climate.overrideApplied)
    }

    @Test
    fun `explicit RECOVERED reverts to safe baseline immediately`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0002"))

        assertTrue(climate.revertCalled)
        assertTrue(voice.stopCalled)
    }

    @Test
    fun `explicit UNKNOWN (lost-face) reverts to safe baseline immediately`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onPayload(payload(TriggerPayload.STATE_UNKNOWN, "vg-0002"))

        assertTrue(climate.revertCalled)
        assertTrue(voice.stopCalled)
    }

    @Test
    fun `connection-lost reverts to safe baseline without an explicit payload`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onConnectionLost()

        assertTrue(climate.revertCalled)
        assertTrue(voice.stopCalled)
    }

    @Test
    fun `gateway throwing on apply is caught, does not crash, does not retry`() {
        climate.throwOnApply = true

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

        assertEquals(DrowsinessController.GatewayActionStatus.OVERRIDE_FAILED, controller.lastGatewayAction)
        // no crash reaching this line is itself part of what's being verified;
        // and a second identical payload must not trigger a retry of the same call:
        climate.throwOnApply = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertFalse(climate.overrideApplied)
    }
}
