package com.vitalguard.ai

import android.content.Context

interface AlertPreferencesStore {
    fun get(): AlertPreferences
    fun save(prefs: AlertPreferences)
}

class InMemoryAlertPreferencesStore(
    initial: AlertPreferences = AlertPreferences()
) : AlertPreferencesStore {
    @Volatile private var current: AlertPreferences = initial

    override fun get(): AlertPreferences = current

    override fun save(prefs: AlertPreferences) {
        require(prefs.isSafe()) { "Cannot save: both voice and climate channels are disabled" }
        current = prefs
    }
}
