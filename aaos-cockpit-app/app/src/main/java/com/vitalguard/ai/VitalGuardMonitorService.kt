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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service hosting the automated trigger pipeline: TriggerPollClient
 * -> DrowsinessController -> Climate/Voice gateways. Also keeps
 * [ClimateOverrideReceiver] registered as the dormant manual on-stage fallback
 * (see design doc Decision 3) — unrelated to the automated path below. Being a
 * foreground service (rather than a receiver tied to [MainActivity]) is what
 * keeps both paths alive regardless of whether the Activity is on screen — a
 * dynamic receiver tied only to the Activity silently stopped firing once the
 * app left the foreground (confirmed on-device 2026-07-24).
 */
class VitalGuardMonitorService : Service() {

    private val voiceAssistant by lazy { VoiceEmergencyAssistant(this) }
    private val climateOverrideReceiver by lazy { ClimateOverrideReceiver(voiceAssistant) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pollClient: TriggerPollClient

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter(ClimateOverrideReceiver.ACTION_TRIGGER_ALERT)
        ContextCompat.registerReceiver(this, climateOverrideReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        val gatewayModeStore = PrefsGatewayModeStore(this)
        val climateGateway: ClimateActuatorGateway = when (gatewayModeStore.get()) {
            GatewayMode.REAL -> RealClimateActuatorGateway(this)
            GatewayMode.FAKE -> FakeClimateActuatorGateway()
        }
        val voiceGateway: VoiceAlertGateway = when (gatewayModeStore.get()) {
            GatewayMode.REAL -> RealVoiceAlertGateway(this)
            GatewayMode.FAKE -> FakeVoiceAlertGateway()
        }
        val controller = DrowsinessController(climateGateway, voiceGateway)

        pollClient = TriggerPollClient(
            fetcher = HttpTriggerFetcher(CONTAINER_NODE_BASE_URL),
            scope = serviceScope,
            onPayload = { payload ->
                DebugOverlayState.instance.updateFromPayload(payload)
                controller.onPayload(payload)
            },
            onConnectionLost = {
                DebugOverlayState.instance.markConnectionLost()
                controller.onConnectionLost()
            },
        )
        pollClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pollClient.stop()
        serviceScope.cancel()
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

        // Placeholder — replace with the room-internal network-pin's real address
        // once confirmed (Day-1 verification task, see the reconciliation design doc).
        private const val CONTAINER_NODE_BASE_URL = "http://192.168.49.2:8765"
    }
}
