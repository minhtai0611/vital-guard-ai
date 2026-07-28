package com.vitalguard.ai

import android.content.Context

interface VoiceAlertGateway {
    fun triggerAlert()
    fun stopAlert()
}

class FakeVoiceAlertGateway : VoiceAlertGateway {
    var alertTriggered: Boolean = false
    var stopCalled: Boolean = false
    var throwOnTrigger: Boolean = false
    var throwOnStop: Boolean = false

    override fun triggerAlert() {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        alertTriggered = true
    }

    override fun stopAlert() {
        if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
        stopCalled = true
    }
}

/** Real implementation — wraps the existing VoiceEmergencyAssistant unchanged. */
class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway {
    private val assistant = VoiceEmergencyAssistant(context)

    override fun triggerAlert() {
        assistant.executeVoiceIntervention()
    }

    override fun stopAlert() {
        assistant.releaseFocus()
    }
}
