package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPreferencesTest {
    @Test
    fun `default preferences is safe`() {
        assertTrue(AlertPreferences().isSafe())
    }

    @Test
    fun `isSafe returns false when both channels disabled`() {
        val prefs = AlertPreferences(voiceEnabled = false, climateEnabled = false)
        assertFalse(prefs.isSafe())
    }

    @Test
    fun `isSafe returns true when only voice enabled`() {
        val prefs = AlertPreferences(voiceEnabled = true, climateEnabled = false)
        assertTrue(prefs.isSafe())
    }

    @Test
    fun `isSafe returns true when only climate enabled`() {
        val prefs = AlertPreferences(voiceEnabled = false, climateEnabled = true)
        assertTrue(prefs.isSafe())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `voiceVolume above 1 throws on construction`() {
        AlertPreferences(voiceVolume = 1.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `voiceVolume below 0 throws on construction`() {
        AlertPreferences(voiceVolume = -0.1f)
    }

    @Test
    fun `voiceVolume at boundary 0 and 1 is valid`() {
        AlertPreferences(voiceVolume = 0f)
        AlertPreferences(voiceVolume = 1f)
    }
}
