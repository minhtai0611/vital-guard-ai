package com.vitalguard.ai

import android.content.Context

interface VoiceAlertGateway {
    fun triggerAlert()
    fun triggerDistractionReminder()
    fun stopAlert()
}

class FakeVoiceAlertGateway : VoiceAlertGateway {
    var alertTriggered: Boolean = false
    var distractionReminderTriggered: Boolean = false
    var stopCalled: Boolean = false
    var throwOnTrigger: Boolean = false
    var throwOnStop: Boolean = false

    /** Records call order (e.g. "stopAlert", "triggerAlert") so handoff-ordering tests can assert on it. */
    val callLog: MutableList<String> = mutableListOf()

    override fun triggerAlert() {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        alertTriggered = true
        callLog.add("triggerAlert")
    }

    override fun triggerDistractionReminder() {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        distractionReminderTriggered = true
        callLog.add("triggerDistractionReminder")
    }

    override fun stopAlert() {
        if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
        stopCalled = true
        callLog.add("stopAlert")
    }
}

/** Real implementation — wraps the existing VoiceEmergencyAssistant unchanged. */
class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway {
    private val assistant = VoiceEmergencyAssistant(context)

    override fun triggerAlert() {
        assistant.executeVoiceIntervention()
    }

    override fun triggerDistractionReminder() {
        assistant.executeDistractionReminder()
    }

    override fun stopAlert() {
        assistant.releaseFocus()
    }
}
