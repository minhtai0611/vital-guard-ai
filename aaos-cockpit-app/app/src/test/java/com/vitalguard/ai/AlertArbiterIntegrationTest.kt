package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlertArbiterIntegrationTest {
    private lateinit var climate: FakeClimateActuatorGateway
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter
    private lateinit var preferencesStore: InMemoryAlertPreferencesStore
    private lateinit var drowsinessController: DrowsinessController
    private lateinit var distractionController: DistractionController

    @Before
    fun setUp() {
        climate = FakeClimateActuatorGateway()
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
        preferencesStore = InMemoryAlertPreferencesStore()
        drowsinessController = DrowsinessController(climate, arbiter, preferencesStore)
        distractionController = DistractionController(arbiter, preferencesStore)
    }

    private fun drowsinessPayload(state: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f, state = state, escalationLevel = 1,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test",
        ),
    )

    @Test
    fun `drowsiness connection-lost while critical clears the arbiter flag so distraction can speak`() {
        drowsinessController.onPayload(drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertTrue(voice.alertTriggered) // drowsiness genuinely spoke

        drowsinessController.onConnectionLost()

        // With the flag still set, this would be silently suppressed --
        // asserting it speaks proves setDrowsinessCriticalActive(false)
        // actually ran, not just that climateGateway.revertToBaseline() did.
        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_NORMAL, "vg-9001").copy(
                distraction = DistractionInfo(
                    score = 0.9f, state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1, yawDeg = 45.0f, pitchDeg = 5.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                )
            )
        )

        assertTrue(voice.distractionReminderTriggered)
    }

    @Test
    fun `stopAlert cross-source cutoff bug does not regress end-to-end`() {
        drowsinessController.onPayload(drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0002").copy(
                distraction = DistractionInfo(
                    score = 0.9f, state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1, yawDeg = 45.0f, pitchDeg = 5.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                )
            )
        ) // suppressed, never spoke

        // distraction's own score recovers -- its controller reverts and calls stopAlert
        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0003").copy(
                distraction = DistractionInfo(
                    score = 0.1f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = true, reason = "test",
                )
            )
        )

        assertFalse(voice.stopCalled) // drowsiness's still-active alert must survive
    }

    @Test
    fun `drowsiness critical with voice disabled still suppresses concurrent distraction`() {
        preferencesStore.save(AlertPreferences(voiceEnabled = false)) // climate-only drowsiness response

        drowsinessController.onPayload(drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertTrue(climate.overrideApplied) // drowsiness genuinely active, just silently on the voice side
        assertFalse(voice.alertTriggered)   // voice never fired -- as intended by the preference

        // Re-enable voice before distraction's payload: both controllers share
        // one AlertPreferencesStore, so if voiceEnabled stayed false here,
        // DistractionController's OWN voiceEnabled gate (Task 8) would also
        // block it -- masking whether setDrowsinessCriticalActive(true)
        // actually ran while voice was disabled above. Re-enabling isolates
        // the one thing this test exists to check: if that call were wrongly
        // nested inside handleCritical()'s `if (prefs.voiceEnabled)` block
        // (the bug this test locks in), drowsinessCriticalActive would have
        // stayed false and this would incorrectly let distraction speak.
        preferencesStore.save(AlertPreferences(voiceEnabled = true))
        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_NORMAL, "vg-9001").copy(
                distraction = DistractionInfo(
                    score = 0.9f, state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1, yawDeg = 45.0f, pitchDeg = 5.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                )
            )
        )

        assertFalse(voice.distractionReminderTriggered)
    }
}
