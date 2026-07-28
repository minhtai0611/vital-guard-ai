package com.vitalguard.ai

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
