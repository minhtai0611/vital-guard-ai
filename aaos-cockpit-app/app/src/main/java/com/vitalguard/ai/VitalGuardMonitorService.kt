package com.vitalguard.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vitalguard.ai.detection.mediapipe.MediaPipeReplayDetectionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Foreground service hosting the automated trigger pipeline: TriggerPollClient
 * -> DrowsinessController -> Climate/Voice gateways. Also keeps
 * [ClimateOverrideReceiver] registered as the dormant manual on-stage fallback
 * (see design doc Decision 3) — unrelated to the automated path below. Being a
 * foreground service (rather than a receiver tied to [MainActivity]) is what
 * keeps both paths alive regardless of whether the Activity is on screen — a
 * dynamic receiver tied only to the Activity silently stopped firing once the
 * app left the foreground (confirmed on-device 2026-07-24).
 *
 * As of the alert-preferences-parked-suppression feature, this also owns a
 * [VehicleContextPollClient] (1Hz vehicle-speed poll -> [ParkedStateTracker] ->
 * fan-out to both controllers' `onParkedStateChanged`) and constructs a single
 * shared [PrefsAlertPreferencesStore] passed to every gateway/controller that
 * needs it.
 *
 * As of the MediaPipe migration spike, this also owns a
 * [MediaPipeReplayDetectionSource] feeding the exact same `drowsinessController`/
 * `distractionController` instances as the HTTP path above -- a local-dev-only
 * addition (see that class's kdoc) that no-ops when no replay file is present on
 * the device, so it is safe to run alongside the real Container Node path.
 *
 * Also dynamically registers [GatewayModeReceiver] here (confirmed on-device
 * 2026-08-05 that its former manifest declaration never fired -- same
 * "Background execution not allowed" failure mode as [ClimateOverrideReceiver]'s
 * TRIGGER_ALERT), so `adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE`
 * only reaches the app while this service is already running.
 */
class VitalGuardMonitorService : Service() {

    private val voiceAssistant by lazy { VoiceEmergencyAssistant(this) }
    private val climateOverrideReceiver by lazy { ClimateOverrideReceiver(voiceAssistant) }
    private val gatewayModeReceiver by lazy { GatewayModeReceiver() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pollClient: TriggerPollClient
    private lateinit var vehicleContextPollClient: VehicleContextPollClient
    private var realVehicleContextGateway: RealVehicleContextGateway? = null
    private var replayDetectionSource: MediaPipeReplayDetectionSource? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter(ClimateOverrideReceiver.ACTION_TRIGGER_ALERT)
        ContextCompat.registerReceiver(this, climateOverrideReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        // Dynamic registration only -- see this class's kdoc and the manifest's comment:
        // a manifest-declared receiver for this implicit action never fires ("Background
        // execution not allowed"), confirmed on-device 2026-08-05.
        val gatewayModeFilter = IntentFilter(GatewayModeReceiver.ACTION_SET_GATEWAY_MODE)
        ContextCompat.registerReceiver(this, gatewayModeReceiver, gatewayModeFilter, ContextCompat.RECEIVER_EXPORTED)

        val alertPreferencesStore: AlertPreferencesStore = PrefsAlertPreferencesStore(this)

        val gatewayModeStore = PrefsGatewayModeStore(this)
        val climateGateway: ClimateActuatorGateway = when (gatewayModeStore.get()) {
            GatewayMode.REAL -> RealClimateActuatorGateway(this, alertPreferencesStore)
            GatewayMode.FAKE -> FakeClimateActuatorGateway()
        }
        val voiceGateway: VoiceAlertGateway = when (gatewayModeStore.get()) {
            GatewayMode.REAL -> RealVoiceAlertGateway(this, alertPreferencesStore)
            GatewayMode.FAKE -> FakeVoiceAlertGateway()
        }
        val alertArbiter = AlertArbiter(voiceGateway)
        val drowsinessController = DrowsinessController(climateGateway, alertArbiter, alertPreferencesStore)
        val distractionController = DistractionController(alertArbiter, alertPreferencesStore)

        pollClient = TriggerPollClient(
            fetcher = HttpTriggerFetcher(CONTAINER_NODE_BASE_URL),
            scope = serviceScope,
            onPayload = { payload ->
                DebugOverlayState.instance.updateFromPayload(payload)
                drowsinessController.onPayload(payload)
                distractionController.onPayload(payload)
            },
            onConnectionLost = {
                DebugOverlayState.instance.markConnectionLost()
                drowsinessController.onConnectionLost()
                distractionController.onConnectionLost()
            },
        )
        pollClient.start()

        // Local-dev-only on-device MediaPipe path (see MediaPipeReplayDetectionSource's
        // kdoc). Guarded on the replay file's presence *before* constructing anything --
        // FaceLandmarkerClient's constructor calls FaceLandmarker.createFromOptions()
        // eagerly, which loads MediaPipe's native lib immediately. That native-lib load
        // is exactly what threw UnsatisfiedLinkError (an Error, not an Exception) on this
        // x86_64 dev machine before the tasks-vision 1.0.0 bump -- it has never
        // successfully run on arm64 hardware yet, so the same class of failure is
        // plausible there too. Without this guard + catch (Throwable, not just
        // RuntimeException -- see this module's CLAUDE.md "Catch Throwable" rule), an
        // init crash here would propagate out of onCreate() and take down the whole
        // service, including the real Container Node -> HTTP path below it -- exactly
        // the failure mode a "local-dev-only, safe to run alongside the real path"
        // feature must not be able to cause.
        val replayFile = File("/data/local/tmp", REPLAY_FILE_NAME)
        if (replayFile.exists()) {
            replayDetectionSource = try {
                MediaPipeReplayDetectionSource(
                    context = this,
                    onPayload = { payload ->
                        DebugOverlayState.instance.updateFromPayload(payload)
                        drowsinessController.onPayload(payload)
                        distractionController.onPayload(payload)
                    },
                    onFrameDecoded = { bitmap -> DebugOverlayState.instance.updateFrame(bitmap) },
                ).also { it.runIfPresent(replayFile) }
            } catch (t: Throwable) {
                Log.e(TAG, "MediaPipe replay detection init failed -- continuing without it", t)
                null
            }
        } else {
            Log.d(TAG, "No replay file at ${replayFile.absolutePath}, skipping on-device detection")
        }

        val vehicleContextGateway = RealVehicleContextGateway(this)
        realVehicleContextGateway = vehicleContextGateway
        vehicleContextPollClient = VehicleContextPollClient(
            gateway = vehicleContextGateway,
            tracker = ParkedStateTracker(),
            scope = serviceScope,
            onParkedStateChanged = { parked ->
                drowsinessController.onParkedStateChanged(parked)
                distractionController.onParkedStateChanged(parked)
            },
        )
        vehicleContextPollClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        pollClient.stop()
        vehicleContextPollClient.stop()
        realVehicleContextGateway?.disconnect()
        replayDetectionSource?.close()
        serviceScope.cancel()
        unregisterReceiver(climateOverrideReceiver)
        unregisterReceiver(gatewayModeReceiver)
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
        private const val TAG = "VitalGuardMonitorService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vital_guard_monitor"

        // Placeholder — replace with the room-internal network-pin's real address
        // once confirmed (Day-1 verification task, see the reconciliation design doc).
        private const val CONTAINER_NODE_BASE_URL = "http://192.168.49.2:8765"

        // Must match the filename MediaPipeReplayDetectionSource's caller (this
        // service) looks for at /data/local/tmp -- see aaos-cockpit-app/docs/
        // EMULATOR_TESTING_GUIDE.md Section 6.5 for how it gets pushed there.
        private const val REPLAY_FILE_NAME = "replay_test.mp4"
    }
}
