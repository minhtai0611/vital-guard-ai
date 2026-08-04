package com.vitalguard.ai

import org.junit.Assert.assertEquals
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
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS, 1)
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 1)

        assertTrue(voice.alertTriggered)
        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `distraction critical alone with no drowsiness active - speaks normally`() {
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 1)

        assertTrue(voice.distractionReminderTriggered)
    }

    @Test
    fun `stopAlert from suppressed source does not stop active alert from other source`() {
        arbiter.setDrowsinessCriticalActive(true)
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS, 1)   // genuinely speaking
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 1)  // suppressed, never spoke

        arbiter.stopAlert(AlertSource.DISTRACTION)

        assertFalse(voice.stopCalled)
    }

    @Test
    fun `stopAlert from owning source stops its own alert normally`() {
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 1)  // not suppressed, drowsiness inactive

        arbiter.stopAlert(AlertSource.DISTRACTION)

        assertTrue(voice.stopCalled)
    }

    @Test
    fun `test_requestVoiceAlert_stops_the_previous_owner_before_switching_to_a_new_source`() {
        // Distraction is speaking first (drowsiness not yet CRITICAL, so not suppressed).
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 1)
        assertTrue(voice.distractionReminderTriggered)
        assertFalse(voice.stopCalled)  // no outgoing owner yet -- nothing to stop

        // Drowsiness becomes CRITICAL and requests the alert while distraction still owns activeSpeaker.
        arbiter.setDrowsinessCriticalActive(true)
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS, 1)

        // The handoff must stop the outgoing owner (distraction) before/as part of granting drowsiness.
        assertTrue(voice.stopCalled)
        assertTrue(voice.alertTriggered)
        // stopAlert() must happen before triggerAlert() -- a clean handoff, not an overwrite.
        assertEquals(listOf("triggerDistractionReminder", "stopAlert", "triggerAlert"), voice.callLog)
    }

    @Test
    fun `requestVoiceAlert passes the level through to the gateway unchanged`() {
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 3)

        assertEquals(3, voice.lastDistractionReminderLevel)
    }
}
