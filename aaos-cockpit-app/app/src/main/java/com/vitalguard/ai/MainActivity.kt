package com.vitalguard.ai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Cockpit entry point. The actual TRIGGER_ALERT handling lives in
 * [VitalGuardMonitorService] (started here and by [BootCompletedReceiver]) so the
 * intervention keeps working whether or not this Activity is on screen.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startForegroundService(Intent(this, VitalGuardMonitorService::class.java))
    }
}
