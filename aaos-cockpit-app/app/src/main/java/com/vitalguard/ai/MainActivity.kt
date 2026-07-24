package com.vitalguard.ai

import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Cockpit entry point. Registers [ClimateOverrideReceiver] dynamically so the
 * TRIGGER_ALERT intervention fires while the app is in the foreground during
 * the demo, in addition to the manifest-declared receiver.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var voiceAssistant: VoiceEmergencyAssistant
    private lateinit var climateOverrideReceiver: ClimateOverrideReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceAssistant = VoiceEmergencyAssistant(this)
        climateOverrideReceiver = ClimateOverrideReceiver(voiceAssistant)

        val filter = IntentFilter("com.vitalguard.ai.TRIGGER_ALERT")
        ContextCompat.registerReceiver(this, climateOverrideReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(climateOverrideReceiver)
        voiceAssistant.releaseFocus()
        super.onDestroy()
    }
}
