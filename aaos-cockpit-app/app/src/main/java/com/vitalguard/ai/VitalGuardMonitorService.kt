package com.vitalguard.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service so [ClimateOverrideReceiver] stays registered — and TRIGGER_ALERT
 * therefore keeps working — regardless of whether [MainActivity] is on screen. A dynamic
 * receiver tied only to the Activity silently stopped firing once the app left the
 * foreground (confirmed on-device 2026-07-24); this service is the fix.
 */
class VitalGuardMonitorService : Service() {

    private val voiceAssistant by lazy { VoiceEmergencyAssistant(this) }
    private val climateOverrideReceiver by lazy { ClimateOverrideReceiver(voiceAssistant) }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val filter = IntentFilter(ClimateOverrideReceiver.ACTION_TRIGGER_ALERT)
        ContextCompat.registerReceiver(this, climateOverrideReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(climateOverrideReceiver)
        voiceAssistant.releaseFocus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vital-Guard Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vital-Guard AI")
            .setContentText("Monitoring driver alertness")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vital_guard_monitor"
    }
}
