package com.vitalguard.ai

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Cockpit entry point. The actual TRIGGER_ALERT handling lives in
 * [VitalGuardMonitorService] (started here and by [BootCompletedReceiver]) so the
 * intervention keeps working whether or not this Activity is on screen.
 *
 * Also renders the mandatory debug overlay (root CLAUDE.md "Debug Overlay"
 * section) by collecting [DebugOverlayState.instance] live — perclos,
 * eyeOpenProbability, headEulerAngleX, driver state, whether a trigger is
 * currently being received, and the last gateway action.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        startForegroundService(Intent(this, VitalGuardMonitorService::class.java))

        val statusView = findViewById<TextView>(R.id.statusText)
        val perclosView = findViewById<TextView>(R.id.overlayPerclos)
        val eyeOpenView = findViewById<TextView>(R.id.overlayEyeOpen)
        val headPitchView = findViewById<TextView>(R.id.overlayHeadPitch)
        val receivingView = findViewById<TextView>(R.id.overlayReceiving)
        val gatewayActionView = findViewById<TextView>(R.id.overlayGatewayAction)

        lifecycleScope.launch {
            DebugOverlayState.instance.flow.collect { snapshot ->
                statusView.text = snapshot.driverState
                perclosView.text = "PERCLOS: %.3f".format(snapshot.perclos)
                eyeOpenView.text = "Eye open prob: %.3f".format(snapshot.eyeOpenProbability)
                headPitchView.text = "Head pitch: %.1f°".format(snapshot.headEulerAngleX)
                receivingView.text = "Receiving trigger: ${snapshot.receivingTrigger}"
                gatewayActionView.text = "Last gateway action: ${snapshot.lastGatewayAction}"
            }
        }
    }
}
