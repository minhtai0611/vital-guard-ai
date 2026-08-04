package com.vitalguard.ai

enum class IntensityLevel { LOW, MEDIUM, HIGH }

/** Fan/temp values for the CRITICAL-response override. Baseline, unvalidated
 * numbers (matches the project's existing convention for such constants) --
 * HIGH is the pre-existing fixed CRITICAL behavior, kept as the default so
 * this feature does not change today's demo experience unless the driver
 * explicitly changes it. Never maps to zero -- there is no OFF intensity. */
object IntensityMapping {
    fun fanSpeedFor(intensity: IntensityLevel): Int = when (intensity) {
        IntensityLevel.HIGH -> 8
        IntensityLevel.MEDIUM -> 5
        IntensityLevel.LOW -> 3
    }

    fun temperatureCFor(intensity: IntensityLevel): Float = when (intensity) {
        IntensityLevel.HIGH -> 20f
        IntensityLevel.MEDIUM -> 22f
        IntensityLevel.LOW -> 23f
    }
}

data class AlertPreferences(
    val voiceEnabled: Boolean = true,
    val voiceVolume: Float = 1.0f,
    val climateEnabled: Boolean = true,
    val climateIntensity: IntensityLevel = IntensityLevel.HIGH,
) {
    init {
        require(voiceVolume in 0f..1f) { "voiceVolume must be in [0,1], got $voiceVolume" }
    }

    /** At least one response channel must be able to fire during CRITICAL --
     * a safety requirement (EU GSR/DDAW), not a UX preference. Enforced by
     * AlertPreferencesStore.save(), not here, so a transient "unsafe" state
     * can exist in-memory while a Settings UI is mid-edit. */
    fun isSafe(): Boolean = voiceEnabled || climateEnabled
}
