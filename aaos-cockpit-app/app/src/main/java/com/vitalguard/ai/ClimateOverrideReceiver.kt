package com.vitalguard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Dormant manual on-stage fallback only (see design doc Decision 3) — not
 * wired to any automated sender. A person can still trigger this by hand via
 * `adb shell am broadcast -a com.vitalguard.ai.TRIGGER_ALERT` if the automated
 * HTTP network-pin pipeline fails live. The automated path is
 * DrowsinessController -> ClimateActuatorGateway/VoiceAlertGateway (Task 14/16).
 */
class ClimateOverrideReceiver(
    private val voiceAssistant: VoiceEmergencyAssistant? = null
) : BroadcastReceiver() {
    private val TAG = "VitalGuardClimate"

    companion object {
        const val ACTION_TRIGGER_ALERT = "com.vitalguard.ai.TRIGGER_ALERT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_ALERT) return
        val score = intent.getFloatExtra("drowsiness_score", 0f)
        Log.w(TAG, "Manual fallback TRIGGER_ALERT received. Score: $score")
        // Manual one-shot fallback -- no escalation state of its own, always level 1
        // (the original pre-escalation baseline behavior).
        RealClimateActuatorGateway(context).applyDrowsinessOverride(1)
        voiceAssistant?.executeVoiceIntervention(1)
    }
}
