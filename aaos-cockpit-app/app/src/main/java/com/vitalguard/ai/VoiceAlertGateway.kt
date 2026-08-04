package com.vitalguard.ai

import android.content.Context

interface VoiceAlertGateway {
    fun triggerAlert(level: Int)
    fun triggerDistractionReminder(level: Int)
    fun stopAlert()
}

class FakeVoiceAlertGateway : VoiceAlertGateway {
    var alertTriggered: Boolean = false
    var distractionReminderTriggered: Boolean = false
    var stopCalled: Boolean = false
    var throwOnTrigger: Boolean = false
    var throwOnStop: Boolean = false
    var lastAlertLevel: Int? = null
    var lastDistractionReminderLevel: Int? = null

    /** Records call order (e.g. "stopAlert", "triggerAlert") so handoff-ordering tests can assert on it. */
    val callLog: MutableList<String> = mutableListOf()

    override fun triggerAlert(level: Int) {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        alertTriggered = true
        lastAlertLevel = level
        callLog.add("triggerAlert")
    }

    override fun triggerDistractionReminder(level: Int) {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        distractionReminderTriggered = true
        lastDistractionReminderLevel = level
        callLog.add("triggerDistractionReminder")
    }

    override fun stopAlert() {
        if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
        stopCalled = true
        callLog.add("stopAlert")
    }
}

/** Real implementation — wraps the existing VoiceEmergencyAssistant unchanged. */
class RealVoiceAlertGateway(
    context: Context,
    private val alertPreferencesStore: AlertPreferencesStore,
) : VoiceAlertGateway {
    private val assistant = VoiceEmergencyAssistant(context)

    override fun triggerAlert(level: Int) {
        assistant.executeVoiceIntervention(level, alertPreferencesStore.get().voiceVolume)
    }

    override fun triggerDistractionReminder(level: Int) {
        assistant.executeDistractionReminder(level, alertPreferencesStore.get().voiceVolume)
    }

    override fun stopAlert() {
        assistant.releaseFocus()
    }
}
