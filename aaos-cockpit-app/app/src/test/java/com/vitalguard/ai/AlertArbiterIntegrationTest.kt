package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlertArbiterIntegrationTest {
    private lateinit var climate: FakeClimateActuatorGateway
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter
    private lateinit var drowsinessController: DrowsinessController
    private lateinit var distractionController: DistractionController

    @Before
    fun setUp() {
        climate = FakeClimateActuatorGateway()
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
        drowsinessController = DrowsinessController(climate, arbiter)
        distractionController = DistractionController(arbiter)
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
}
