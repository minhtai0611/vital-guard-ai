package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrowsinessControllerTest {
    private lateinit var climate: FakeClimateActuatorGateway
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter
    private lateinit var controller: DrowsinessController

    @Before
    fun setUp() {
        climate = FakeClimateActuatorGateway()
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
        controller = DrowsinessController(climate, arbiter)
    }

    private fun payload(state: String, correlationId: String, escalationLevel: Int = 1) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
        state = state, escalationLevel = escalationLevel,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test"
        ),
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

    @Test
    fun `duplicate correlationId does not call the gateway or arbiter again`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))
        assertTrue(climate.overrideApplied)
        climate.overrideApplied = false
        voice.alertTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))

        assertFalse(climate.overrideApplied)
        assertFalse(voice.alertTriggered)
    }

    @Test
    fun `consecutive CRITICAL with the same level does not re-apply climate but still fires voice every payload`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 2))
        assertEquals(2, climate.lastAppliedLevel)
        climate.overrideApplied = false // reset to prove no SECOND climate call happened
        voice.alertTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 2))

        assertFalse(climate.overrideApplied)
        assertTrue(voice.alertTriggered)
        assertEquals(2, voice.lastAlertLevel)
    }

    @Test
    fun `CRITICAL with an increased level re-applies climate at the new level`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))
        assertEquals(1, climate.lastAppliedLevel)

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 2))

        assertTrue(climate.overrideApplied)
        assertEquals(2, climate.lastAppliedLevel)
    }

    @Test
    fun `climate failure sets OVERRIDE_FAILED and retries the same level on the next payload`() {
        climate.throwOnApply = true

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))

        assertEquals(DrowsinessController.GatewayActionStatus.OVERRIDE_FAILED, controller.lastGatewayAction)
        assertFalse(climate.overrideApplied)

        climate.throwOnApply = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 1))

        assertTrue(climate.overrideApplied)
        assertEquals(1, climate.lastAppliedLevel)
    }

    @Test
    fun `UNKNOWN reverts to baseline and clears the last applied climate level`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 3))

        controller.onPayload(payload(TriggerPayload.STATE_UNKNOWN, "vg-0002"))

        assertTrue(climate.revertCalled)
        assertTrue(voice.stopCalled)
    }

    @Test
    fun `after UNKNOWN a fresh CRITICAL at level 1 re-applies the override from scratch`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 3))
        controller.onPayload(payload(TriggerPayload.STATE_UNKNOWN, "vg-0002"))
        climate.overrideApplied = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003", escalationLevel = 1))

        assertTrue(climate.overrideApplied)
        assertEquals(1, climate.lastAppliedLevel)
    }
}
