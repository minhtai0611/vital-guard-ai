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

class PrefsAlertPreferencesStore(private val context: Context) : AlertPreferencesStore {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): AlertPreferences = runCatching {
        AlertPreferences(
            voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true),
            voiceVolume = prefs.getFloat(KEY_VOICE_VOLUME, 1.0f),
            climateEnabled = prefs.getBoolean(KEY_CLIMATE_ENABLED, true),
            climateIntensity = IntensityLevel.valueOf(
                prefs.getString(KEY_CLIMATE_INTENSITY, IntensityLevel.HIGH.name)!!
            ),
        )
    }.getOrDefault(AlertPreferences())

    override fun save(prefs: AlertPreferences) {
        require(prefs.isSafe()) { "Cannot save: both voice and climate channels are disabled" }
        this.prefs.edit()
            .putBoolean(KEY_VOICE_ENABLED, prefs.voiceEnabled)
            .putFloat(KEY_VOICE_VOLUME, prefs.voiceVolume)
            .putBoolean(KEY_CLIMATE_ENABLED, prefs.climateEnabled)
            .putString(KEY_CLIMATE_INTENSITY, prefs.climateIntensity.name)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "vital_guard_alert_preferences"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_CLIMATE_ENABLED = "climate_enabled"
        private const val KEY_CLIMATE_INTENSITY = "climate_intensity"
    }
}
