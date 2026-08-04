package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DistractionControllerTest {
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter
    private lateinit var preferencesStore: InMemoryAlertPreferencesStore
    private lateinit var controller: DistractionController

    @Before
    fun setUp() {
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
        preferencesStore = InMemoryAlertPreferencesStore()
        controller = DistractionController(arbiter, preferencesStore)
    }

    private fun payload(distractionState: String, correlationId: String, escalationLevel: Int = 1) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.1f, confidence = 1.0f,
        state = TriggerPayload.STATE_NORMAL, escalationLevel = 1,
        features = TriggerFeatures(perclos = 0.0f, eyeOpenProbability = 1.0f, headEulerAngleX = 0.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.9f, state = distractionState, escalationLevel = escalationLevel, yawDeg = 45.0f, pitchDeg = 5.0f,
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
    fun `gateway throwing on trigger is caught, does not crash; a later CRITICAL payload still fires`() {
        // Regression note: this test used to assert "does not retry" via the
        // OLD `if (latched) return` early-exit in handleCritical(). The
        // alert-escalation feature removed that early-exit -- every CRITICAL
        // payload is now meaningful (Python is the sole timing authority) --
        // so a later payload on a DIFFERENT correlationId is now EXPECTED to
        // fire the reminder again, even after an earlier throw.
        voice.throwOnTrigger = true

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        // no crash reaching this line is itself part of what's being verified.

        voice.throwOnTrigger = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002"))
        assertTrue(voice.distractionReminderTriggered)
    }

    @Test
    fun `duplicate correlationId does not call the gateway again`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        voice.distractionReminderTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `consecutive CRITICAL fires the voice reminder every payload with the current level`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))
        assertEquals(1, voice.lastDistractionReminderLevel)
        voice.distractionReminderTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 2))

        assertTrue(voice.distractionReminderTriggered)
        assertEquals(2, voice.lastDistractionReminderLevel)
    }

    @Test
    fun `NORMAL after CRITICAL reverts, and a fresh CRITICAL fires again`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 3))
        controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0002"))
        assertTrue(voice.stopCalled)
        voice.distractionReminderTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003", escalationLevel = 1))

        assertTrue(voice.distractionReminderTriggered)
        assertEquals(1, voice.lastDistractionReminderLevel)
    }

    @Test
    fun `does not freeze latch across park then unpark while still critical`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertTrue(voice.distractionReminderTriggered)

        controller.onParkedStateChanged(true)
        voice.distractionReminderTriggered = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002"))
        assertFalse(voice.distractionReminderTriggered) // suppressed, latch not poisoned

        controller.onParkedStateChanged(false)
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003"))
        assertTrue(voice.distractionReminderTriggered) // must fire again
    }

    @Test
    fun `park while critical active reverts to baseline`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertTrue(voice.distractionReminderTriggered)

        controller.onParkedStateChanged(true)

        assertTrue(voice.stopCalled)
    }

    @Test
    fun `voiceEnabled false suppresses distraction reminder`() {
        preferencesStore.save(AlertPreferences(voiceEnabled = false))

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

        assertFalse(voice.distractionReminderTriggered)
    }
}
