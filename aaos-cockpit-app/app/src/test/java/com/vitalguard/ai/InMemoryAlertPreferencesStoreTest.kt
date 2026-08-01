package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryAlertPreferencesStoreTest {
    @Test
    fun `defaults to safe default preferences`() {
        val store = InMemoryAlertPreferencesStore()
        assertEquals(AlertPreferences(), store.get())
    }

    @Test
    fun `save then get round trips`() {
        val store = InMemoryAlertPreferencesStore()
        val prefs = AlertPreferences(voiceEnabled = false, climateIntensity = IntensityLevel.LOW)
        store.save(prefs)
        assertEquals(prefs, store.get())
    }

    @Test
    fun `save rejects unsafe preferences and leaves prior value unchanged`() {
        val store = InMemoryAlertPreferencesStore()
        val original = store.get()
        val unsafe = AlertPreferences(voiceEnabled = false, climateEnabled = false)

        try {
            store.save(unsafe)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertEquals(original, store.get())
        assertTrue(store.get().isSafe())
    }
}
