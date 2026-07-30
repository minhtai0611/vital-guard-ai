package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlertArbiterTest {
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter

    @Before
    fun setUp() {
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
    }

    @Test
    fun `drowsiness and distraction critical simultaneously - only drowsiness speaks`() {
        arbiter.setDrowsinessCriticalActive(true)
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS)
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION)

        assertTrue(voice.alertTriggered)
        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `distraction critical alone with no drowsiness active - speaks normally`() {
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION)

        assertTrue(voice.distractionReminderTriggered)
    }

    @Test
    fun `stopAlert from suppressed source does not stop active alert from other source`() {
        arbiter.setDrowsinessCriticalActive(true)
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS)   // genuinely speaking
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION)  // suppressed, never spoke

        arbiter.stopAlert(AlertSource.DISTRACTION)

        assertFalse(voice.stopCalled)
    }

    @Test
    fun `stopAlert from owning source stops its own alert normally`() {
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION)  // not suppressed, drowsiness inactive

        arbiter.stopAlert(AlertSource.DISTRACTION)

        assertTrue(voice.stopCalled)
    }
}
