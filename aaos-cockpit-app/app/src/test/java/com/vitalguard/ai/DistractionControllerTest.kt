package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DistractionControllerTest {
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter
    private lateinit var controller: DistractionController

    @Before
    fun setUp() {
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
        controller = DistractionController(arbiter)
    }

    private fun payload(distractionState: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.1f, confidence = 1.0f,
        state = TriggerPayload.STATE_NORMAL,
        features = TriggerFeatures(perclos = 0.0f, eyeOpenProbability = 1.0f, headEulerAngleX = 0.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.9f, state = distractionState, yawDeg = 45.0f, pitchDeg = 5.0f,
            handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
        ),
    )

    @Test
    fun `normal operation below threshold never calls the gateway`() {
        controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0001"))

        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `idempotency - repeated CRITICAL with same correlationId fires gateway only once`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

        assertTrue(voice.distractionReminderTriggered)
        voice.distractionReminderTriggered = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `explicit NORMAL after CRITICAL reverts (stops the reminder)`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0002"))

        assertTrue(voice.stopCalled)
    }

    @Test
    fun `WARNING state never calls the gateway (overlay-only, no action)`() {
        controller.onPayload(payload(TriggerPayload.STATE_WARNING, "vg-0001"))

        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `connection-lost reverts without an explicit payload`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        controller.onConnectionLost()

        assertTrue(voice.stopCalled)
    }

    @Test
    fun `gateway throwing on trigger is caught, does not crash, does not retry`() {
        voice.throwOnTrigger = true

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        // no crash reaching this line is itself part of what's being verified.
        // latched is set to true BEFORE the try block in handleCritical(), so
        // it stays true even though the call inside it threw.

        // A second call with the SAME correlationId would be blocked by
        // onPayload()'s own correlationId dedupe before ever reaching
        // handleCritical() again -- that would make this test pass without
        // ever actually exercising the `if (latched) return` retry-prevention
        // logic it claims to test. Use a DIFFERENT correlationId so this call
        // genuinely reaches handleCritical() and is blocked by latched, not
        // by the unrelated dedupe check.
        voice.throwOnTrigger = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002"))
        assertFalse(voice.distractionReminderTriggered)
    }
}
