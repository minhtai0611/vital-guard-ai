# 2026-08-04 — MediaPipe Android Migration: Session Handoff

## Why this file exists

The prior session was stopped by the user before the migration below was finished. This
file is the self-contained resume point for the *next* session — it inlines the full
approved plan (originally written to a plan-mode file outside the repo, at
`C:\Users\Tai Minh\.claude\plans\twinkly-swinging-ritchie.md`, which a future session will
not see automatically) plus the exact current progress state and the next concrete action.

**Branch:** `feature/mediapipe-unified-rebuild` (already created from `main`, already checked out).

**Do not re-litigate the two locked decisions below** — they were scoped with the user via
`AskUserQuestion` and approved via plan mode:
1. `aaos-cockpit-app/` and `dms-ai-engine/` are combined into one codebase (architectural
   consolidation, not just a shared parent folder).
2. The rebuild swaps in MediaPipe's **Android** Tasks Vision library
   (`com.google.mediapipe:tasks-vision`, `FaceLandmarker`/`HandLandmarker` in `LIVE_STREAM`
   mode) as the on-device detection backend, eliminating the Python Container Node and the
   HTTP network-pin (`TriggerPollClient`/`HttpTriggerFetcher`) from the runtime path.

This is an explicit mentor directive and is the carve-out root `CLAUDE.md` allows for
revisiting decisions otherwise marked "locked in" (Container Node/Python split, HTTP
network-pin delivery, "DMS never ported to Kotlin").

---

## Current progress state (as of 2026-08-04, session stopped mid Step 1)

Working tree (uncommitted, on `feature/mediapipe-unified-rebuild`):
```
 M aaos-cockpit-app/app/build.gradle
 M aaos-cockpit-app/app/src/main/AndroidManifest.xml
?? aaos-cockpit-app/app/src/main/assets/
?? aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/
```

Nothing has been committed yet on this branch. **Before doing anything destructive, run
`git status` again** — do not assume this listing is still current.

There is also an unrelated stash (`stash@{0}`, "WIP on
feature/alert-escalation-preferences-parked-suppression before switching to main" — bridge-
service `GatewayMode.BRIDGE` work-in-progress). It predates this migration, is deliberately
untouched, and should not be popped/dropped without asking the user first.

### Done (Step 1 — spike — ~90% complete)

- `aaos-cockpit-app/app/build.gradle` — added `com.google.mediapipe:tasks-vision:0.10.14`
  (pinned to match `dms-ai-engine/requirements.txt`'s `mediapipe==0.10.14`) and
  `androidx.camera:camera-{core,camera2,lifecycle}:1.3.1`.
- `aaos-cockpit-app/app/src/main/AndroidManifest.xml` — added
  `<uses-permission android:name="android.permission.CAMERA" />` and
  `<uses-feature android:name="android.hardware.camera" android:required="false" />`
  (`required="false"` so `REPLAY_FILE` mode — see Step 5 below — still installs on a
  no-camera CarSky emulator image). Also added the pre-existing `TTS_SERVICE` `<queries>`
  fix from an earlier unrelated task — already committed history, not part of this
  migration's diff, don't touch it.
- `aaos-cockpit-app/app/src/main/assets/models/face_landmarker.task` (3,758,596 bytes) and
  `hand_landmarker.task` (7,819,105 bytes) — downloaded and byte-verified against
  `dms-ai-engine/Dockerfile`'s pinned GCS URLs. Confirmed identical.
- `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MonotonicTimestamp.kt`
  — complete. Direct port of `dms-ai-engine/services/face_landmarker_client.py`'s
  `MonotonicTimestamp`, guarding `LIVE_STREAM`'s strictly-increasing-timestamp requirement
  (handles non-finite and non-increasing raw readings via fallback to `last+1`). Full
  content:
  ```kotlin
  package com.vitalguard.ai.detection.mediapipe

  /**
   * Guards MediaPipe's LIVE_STREAM mode, which requires strictly increasing
   * timestamps -- direct port of dms-ai-engine/services/face_landmarker_client.py's
   * MonotonicTimestamp, including its non-finite and non-increasing fallbacks.
   */
  class MonotonicTimestamp {
      private var last: Long? = null

      fun next(rawMs: Double): Long {
          val candidate: Long = if (!rawMs.isFinite()) {
              last?.plus(1) ?: 0L
          } else {
              val raw = rawMs.toLong()
              if (last != null && raw <= last!!) last!! + 1 else raw
          }
          last = candidate
          return candidate
      }
  }
  ```
- `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/FaceLandmarkerClient.kt`
  — complete. Throwaway spike wrapper around `FaceLandmarker.createFromOptions()` in
  `LIVE_STREAM` mode — proves the Android tasks-vision library accepts the same `.task`
  bundle Python uses, and is meant to capture real blendshape/`facialTransformationMatrixes`
  layout data ahead of the `HeadPose.kt` port (see Risk notes below). Full content:
  ```kotlin
  package com.vitalguard.ai.detection.mediapipe

  import android.content.Context
  import android.graphics.Bitmap
  import com.google.mediapipe.framework.image.BitmapImageBuilder
  import com.google.mediapipe.tasks.core.BaseOptions
  import com.google.mediapipe.tasks.core.Delegate
  import com.google.mediapipe.tasks.vision.core.RunningMode
  import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
  import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

  /**
   * Throwaway spike client -- proves the Android tasks-vision library accepts the
   * same .task bundle dms-ai-engine's Python side uses, and is meant to capture
   * real blendshape/facialTransformationMatrixes layout data on-device ahead of
   * the HeadPose.kt port (plan's flagged highest-risk port: the pitch/yaw axis
   * mapping is empirically, not analytically, derived from the matrix layout).
   *
   * LIVE_STREAM + detectAsync() replaces Python's synchronous VIDEO +
   * detect_for_video() -- the one genuinely architectural change in the whole
   * port (see services/face_landmarker_client.py).
   */
  class FaceLandmarkerClient(
      context: Context,
      onResult: (FaceLandmarkerResult) -> Unit,
      onError: (RuntimeException) -> Unit,
  ) {
      private val timestamp = MonotonicTimestamp()

      private val landmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
          context,
          FaceLandmarker.FaceLandmarkerOptions.builder()
              .setBaseOptions(
                  BaseOptions.builder()
                      .setModelAssetPath(MODEL_ASSET_PATH)
                      .setDelegate(Delegate.CPU)
                      .build()
              )
              .setRunningMode(RunningMode.LIVE_STREAM)
              .setNumFaces(1)
              .setOutputFaceBlendshapes(true)
              .setOutputFacialTransformationMatrixes(true)
              .setMinFaceDetectionConfidence(0.5f)
              .setMinTrackingConfidence(0.5f)
              .setResultListener { result, _ -> onResult(result) }
              .setErrorListener(onError)
              .build()
      )

      fun detectAsync(bitmap: Bitmap, rawTimestampMs: Double) {
          val ts = timestamp.next(rawTimestampMs)
          landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), ts)
      }

      fun close() {
          landmarker.close()
      }

      companion object {
          private const val MODEL_ASSET_PATH = "models/face_landmarker.task"
      }
  }
  ```
  **Not yet compiled/built** — this is a first-pass direct translation, never run through
  `./gradlew`. Verify it compiles as part of the very next action below.

### Not started

Everything else: `HandLandmarkerClient.kt`, all of Step 1's remaining wiring (CameraX
preview + permission flow into `MainActivity.kt`), and Steps 2–8 in full (see "Migration
order" below).

### Read but not edited

- `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/MainActivity.kt` — current content
  (unchanged):
  ```kotlin
  package com.vitalguard.ai

  import android.content.Intent
  import android.os.Bundle
  import android.widget.TextView
  import androidx.appcompat.app.AppCompatActivity
  import androidx.lifecycle.lifecycleScope
  import kotlinx.coroutines.launch

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
  ```
  No camera code, no CameraX imports, no `CAMERA` runtime-permission request flow exist yet.
  **`activity_main.xml`'s content has never been read this migration** — read it before
  deciding whether to add a `PreviewView` to the layout XML or construct one
  programmatically.

### Confirmed unchanged (re-verified, safe to trust as current)

`aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt` — full
current content:
```kotlin
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

class VitalGuardMonitorService : Service() {

    private val voiceAssistant by lazy { VoiceEmergencyAssistant(this) }
    private val climateOverrideReceiver by lazy { ClimateOverrideReceiver(voiceAssistant) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pollClient: TriggerPollClient
    private lateinit var vehicleContextPollClient: VehicleContextPollClient
    private var realVehicleContextGateway: RealVehicleContextGateway? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val filter = IntentFilter(ClimateOverrideReceiver.ACTION_TRIGGER_ALERT)
        ContextCompat.registerReceiver(this, climateOverrideReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

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
```
This is the Step 6 integration target — the `pollClient = TriggerPollClient(...)` block
(lines 64–78 above) gets replaced with a `DetectionPipeline(...)` construction. GitNexus
`impact()` already confirmed **LOW risk / 0 callers** for this file (checked this session) —
the impact-check gate is cleared for editing it, but the edit itself has not been made yet.

GitNexus `impact()` was also run on `MainActivity` — also **LOW risk / 0 callers**, cleared.
`impact()` has **not** yet been run on `TriggerPayload` — do that before Step 6 touches it.

---

## The exact next concrete action

1. Read `aaos-cockpit-app/app/src/main/res/layout/activity_main.xml` (never inspected this
   migration).
2. Decide whether to add a `androidx.camera.view.PreviewView` to that layout XML, or build a
   throwaway camera preview programmatically for the spike — either is fine for a spike,
   prefer whichever is faster to wire up given what the XML already contains.
3. Wire CameraX (`ProcessCameraProvider`, `ImageAnalysis` bound to `LifecycleOwner`) +
   runtime `CAMERA` permission request + `FaceLandmarkerClient` into `MainActivity.kt`, log
   the raw blendshape values (`eyeBlinkLeft`/`eyeBlinkRight`) and
   `facialTransformationMatrixes()` output to `Log.d` so the real matrix layout can be
   captured ahead of the `HeadPose.kt` port (see Risk notes below — this is the reason Step
   1 exists before Step 2 starts).
4. Build (`./gradlew assembleRelease` from `aaos-cockpit-app/`) — this is the **first
   compile** of `FaceLandmarkerClient.kt`/`MonotonicTimestamp.kt`; expect to fix import/API
   mismatches against the actual `tasks-vision:0.10.14` AAR (the Kotlin was hand-translated
   from the plan, never compiler-checked).
5. Once it builds, run on-device (or CarSky emulator) and confirm real blendshape/matrix
   values land in logcat. This closes out Step 1.
6. Then proceed to Step 2 (pure-logic ports + unit tests) below.

---

## Full migration plan (verbatim from the approved plan-mode file)

### Recommended approach

Add a new `com.vitalguard.ai.detection` package inside the existing `aaos-cockpit-app/app`
module (no new Gradle module — unnecessary overhead for a 2-person team, single process
anyway). Port each Python service file to a same-shaped Kotlin file, preserving every
threshold/constant exactly (they encode real, already-tuned/bug-fixed behavior, not invented
numbers). Keep `TriggerPayload.kt`/`TriggerFeatures`/`DistractionInfo` exactly as-is — they
become the in-process contract between the new detection layer and the untouched
`DrowsinessController`/`DistractionController`/`DebugOverlayState`, just constructed directly
instead of deserialized from HTTP. Delete `TriggerPollClient.kt`/`HttpTriggerFetcher` once
nothing constructs them.

#### Target layout (new code only)

```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/
  camera/     FrameSource.kt, CameraXFrameSource.kt, ReplayFileFrameSource.kt, DetectionBackendMode.kt
  mediapipe/  FaceLandmarkerClient.kt, HandLandmarkerClient.kt, MonotonicTimestamp.kt
  signal/     EyeState.kt, HeadPose.kt, HandTracker.kt
  scoring/    DrowsinessScoreCalculator.kt, DistractionScoreCalculator.kt
  trigger/    TriggerEmitter.kt (+FacePresenceTracker), DistractionTriggerEmitter.kt, EscalationTracker.kt, TriggerPayloadBuilder.kt
  pipeline/   DetectionPipeline.kt   // orchestrator; replaces main.py's run_real_video loop
```

`mediapipe/MonotonicTimestamp.kt` and `mediapipe/FaceLandmarkerClient.kt` already exist —
see "Current progress state" above. `HandLandmarkerClient.kt` does not exist yet.

#### Class-by-class port (constants must stay byte-identical; see risk notes for the two non-mechanical ports)

| Python source | Kotlin target | Notes |
|---|---|---|
| `services/eye_state.py` | `detection/signal/EyeState.kt` | Pure port. Keep `BLINK_CLOSE_THRESHOLD=0.55`/`BLINK_REOPEN_THRESHOLD=0.35` hysteresis exactly. |
| `services/head_pose.py` | `detection/signal/HeadPose.kt` | **Highest-risk port**: the pitch/yaw axis mapping is empirically (not analytically) derived from numpy's row-major matrix layout. Android's `facialTransformationMatrixes()` accessor layout must be verified against captured real values *before* porting the index expressions — otherwise this silently reproduces the exact "head-droop capped at 0" bug the Python code already fixed once. |
| `services/hand_tracker.py` | `detection/signal/HandTracker.kt` | Port `WHEEL_REGION`, `classifyHandsVisibility`, `handsOnWheel` verbatim now; flag for recalibration later — it was calibrated against one test video's framing, already noted in the Python docstring as unverified against real camera mounting. |
| `services/score_calculator.py` | `detection/scoring/DrowsinessScoreCalculator.kt` | `deque(maxlen=...)` → `ArrayDeque` with manual eviction. Keep `0.55/0.25/0.20` weights, `window_seconds=2.0, sample_hz=10.0, max_droop_deg=25.0`. |
| `services/distraction_score_calculator.py` | `detection/scoring/DistractionScoreCalculator.kt` | Keep `W_GAZE=0.80/W_HANDS=0.20`, UNKNOWN-excluded-from-denominator logic. |
| `services/trigger_emitter.py` | `detection/trigger/TriggerEmitter.kt` | Keep `enter=0.85/exit=0.50/sustain=2.0s/cooldown=10.0s`. Also carries `FacePresenceTracker` (lost-face → UNKNOWN, sustain 2.0s). |
| `services/distraction_trigger_emitter.py` | `detection/trigger/DistractionTriggerEmitter.kt` | Keep `enter=0.70/exit=0.40/sustain=1.5s/cooldown=5.0s`. Stays a separate class — do not merge with `TriggerEmitter`. |
| `services/escalation_tracker.py` | `detection/trigger/EscalationTracker.kt` | Two instances in `DetectionPipeline` mirroring `main.py`'s module-level pair: drowsy `levelUp=[8,16]s/repeat=[10,5,4]s`, distraction `levelUp=[6,12]s/repeat=[7,5,3]s`. Sole timing authority moves from "Python's main loop" to "`DetectionPipeline`" — `DrowsinessController`/`DistractionController` remain pure reactors, must not gain their own escalation timing. |
| `services/face_landmarker_client.py` | `detection/mediapipe/FaceLandmarkerClient.kt` + `MonotonicTimestamp.kt` | **The one genuinely architectural change**: `RunningMode.VIDEO`+`detect_for_video()` (sync) → `RunningMode.LIVE_STREAM`+`detectAsync()`+result listener (async). Keep `MonotonicTimestamp`'s non-finite/non-increasing guard — LIVE_STREAM still requires strictly increasing timestamps. **Already ported — see above.** |
| `main.py` (`build_trigger_payload`, `_state_for_score`, orchestration) | `detection/pipeline/DetectionPipeline.kt` + `detection/trigger/TriggerPayloadBuilder.kt` | Loop-driven → event-driven (joined async face+hand results trigger scoring/emission). Keep `BASELINE_CALIBRATION_SECONDS=1.0`, `PITCH_OFF_ROAD_THRESHOLD=20.0`, `YAW_OFF_ROAD_THRESHOLD=30.0`, and the exact emission gate (`state in CRITICAL/RECOVERED OR *_repeat_due OR *_level_changed`) that prevents payload spam. |
| `services/trigger_server.py` | *(deleted, no port)* | `LatestTriggerStore`/HTTP server has no replacement — superseded by direct in-process callback. |

#### Camera pipeline (new problem Python never had)

`FrameSource` interface abstracts live camera vs replay file. `CameraXFrameSource`
(front/driver-facing camera, `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`) and
`ReplayFileFrameSource` (decodes a bundled MP4, paced to source frame interval) both feed
`DetectionPipeline.onFrame(bitmap, timestampMs)`, which calls `detectAsync()` on both
landmarkers. Because face and hand results now arrive **asynchronously and independently**
(Python ran them synchronously back-to-back), `DetectionPipeline` must join them by
timestamp itself — this is new code with no Python precedent, needs its own unit tests, and
all state mutation (scoring/trigger/escalation) must be funneled onto one serial dispatcher
since MediaPipe's result listeners may fire off the caller's thread.

`DetectionBackendMode` (`LIVE_CAMERA`/`REPLAY_FILE`), persisted via
`PrefsDetectionBackendModeStore`, mirrors the existing `GatewayModeStore.kt` pattern exactly
— this preserves the demo-contingency capability (root `CLAUDE.md`'s Demo Script fallback:
replay a bundled MP4 if live camera fails on stage) without needing Python/Docker at all.
Copy the three demo MP4s from `dms-ai-engine/public/` into
`app/src/main/assets/replay/` (renamed to ASCII filenames).

#### Wiring into `VitalGuardMonitorService.kt`

Replace the `TriggerPollClient(fetcher = HttpTriggerFetcher(...))` construction with a
`DetectionPipeline(...)` constructed with the active `FrameSource`, whose `onPayload`
callback feeds `DebugOverlayState.updateFromPayload`, `drowsinessController.onPayload`,
`distractionController.onPayload` exactly as today. `onConnectionLost` semantics shift from
"3 consecutive HTTP failures" to "no frame processed in N seconds." Delete
`TriggerPollClient.kt`, `HttpTriggerFetcher`/`TriggerFetcher`/`FetchResult` once
unreferenced.

#### Gradle/manifest changes — DONE, see "Current progress state" above

#### Fate of `dms-ai-engine/`

Keep it, unmodified except docs — not deleted. It remains the fastest way to numerically
validate a threshold change end-to-end (`run_real_video` → `evidence_run.csv`) against the
three demo MP4s, and stays useful as a ground-truth diff target while porting. Add a short
banner to its README (or root `README.md`) marking it as an offline reference/validation
tool, no longer in the runtime path. Update root `CLAUDE.md`'s "Architectural Decisions
Already Locked In" section — the line stating DMS is "never ported to Kotlin" is now
reversed by mentor directive and must not be left stale.

### Migration order (keeps the app buildable/testable at every step; front-loads the biggest unknown)

1. **Spike** (in progress, ~90% done — see above): add deps + bundled models, throwaway
   `FaceLandmarkerClient` in `LIVE_STREAM` wired to a bare CameraX preview, log raw
   blendshape/matrix values. Proves on-device MediaPipe viability and captures real
   matrix-layout data before any porting depends on assumptions about it.
2. **Pure-logic ports + unit tests** (no MediaPipe/camera dependency): `EyeState`,
   `HeadPose` (verify matrix layout against step 1's captured data first), `HandTracker`,
   both score calculators, both trigger emitters, `EscalationTracker`,
   `TriggerPayloadBuilder`. Port the corresponding Python test files' edge cases into Kotlin
   JUnit under `app/src/test/java/.../detection/` — fits the existing no-Robolectric setup
   (`testOptions.unitTests.returnDefaultValues=true`).
3. **`ReplayFileFrameSource` + a standalone `DetectionPipeline` test harness** dumping a CSV
   like `evidence_run.csv` — diff against a `dms-ai-engine` run of the same MP4 to
   numerically validate the port before introducing live-camera risk.
4. **Wire `FaceLandmarkerClient`/`HandLandmarkerClient` in `LIVE_STREAM`** + join-by-timestamp
   logic, first against `ReplayFileFrameSource` (isolates "does async joining work" from
   "does CameraX work").
5. **`CameraXFrameSource`** + `CAMERA` permission flow + `DetectionBackendMode`/store/receiver
   — swap in behind the same `FrameSource` interface.
6. **Wire into `VitalGuardMonitorService.kt`**, delete `TriggerPollClient`/HTTP fetcher
   classes. Existing `DrowsinessControllerTest.kt` etc. should pass unchanged (they operate
   on `TriggerPayload`, agnostic to its origin).
7. **On-device smoke test** per `aaos-cockpit-app/CLAUDE.md`'s Verify step
   (`./gradlew assembleRelease` + `adb install` + logcat), replaying the Demo Script scenario
   via `REPLAY_FILE` first (deterministic), then `LIVE_CAMERA` if hardware allows.
8. **Docs**: update root `CLAUDE.md`, root `README.md`'s stale smoke-test snippet, add a
   dated design doc under `docs/superpowers/specs/` recording this decision (this file
   partially serves that purpose already — a follow-up doc after completion should record
   the final as-built state), and log an entry to `aaos-cockpit-app/MEMORY.md` per that
   module's mandatory loop-learning rule (root `aaos-cockpit-app/CLAUDE.md` — **not yet done
   for any of this session's work**, including `MonotonicTimestamp.kt` and
   `FaceLandmarkerClient.kt`; do this retroactively as soon as Step 1 is confirmed working).

### What does NOT change

`bridge-service/` priv-app module; the Fake/Real gateway split
(`ClimateActuatorGateway`/`VoiceAlertGateway`, never merged); `VehicleContextPollClient`/
`ParkedStateTracker` (unrelated 1Hz VHAL poll); `GATEWAY_MODE=FAKE|REAL` pattern; VHAL
area-id/range query-at-runtime discipline in `ClimateActuatorGateway.kt`; FSM hardening in
`DrowsinessController.kt`/`DistractionController.kt`; `contracts/trigger.schema.json` (kept
as documentation even though nothing deserializes it from HTTP anymore).

### Key risks to flag, not block on

- **Inference cost unknown** on real AAOS/CarSky hardware until the spike runs — may affect
  the ≤150ms p95 KPI, which was characterized against the old architecture.
- **CameraX availability on the CarSky/AAOS emulator image** is unverified — `REPLAY_FILE`
  mode is the deliberate fallback if it doesn't work, hence `uses-feature required="false"`.
- **`HeadPose.kt` matrix layout** and **async result-joining correctness** are the two
  places most likely to introduce a silent regression — both called out explicitly in
  migration steps 1–2 above rather than assumed correct from a blind port.
- **`WHEEL_REGION`** will likely need re-calibration once real camera framing is available —
  a config-constant tweak, not a blocker to the port itself.

### Critical files

- `dms-ai-engine/main.py`, `dms-ai-engine/services/{score_calculator,distraction_score_calculator,eye_state,head_pose,hand_tracker,trigger_emitter,distraction_trigger_emitter,escalation_tracker,face_landmarker_client}.py` — source logic to port.
- `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt` — integration point being rewired.
- `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt` — contract kept as-is.
- `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPollClient.kt` — deleted once unreferenced.
- `aaos-cockpit-app/app/build.gradle`, `aaos-cockpit-app/app/src/main/AndroidManifest.xml` — dependency/permission changes (done).
- `aaos-cockpit-app/CLAUDE.md` — governs the loop-learning process for all of this work; log to its `MEMORY.md` after each step.
- Root `CLAUDE.md` — "Architectural Decisions Already Locked In" section needs updating post-migration.

### Verification

- Each ported Kotlin class gets a JUnit test mirroring its Python test file's edge cases
  (`app/src/test/java/.../detection/`), run via `./gradlew testDebugUnitTest`.
- Numeric parity check: run `dms-ai-engine`'s `run_real_video` on a bundled demo MP4 to
  produce `evidence_run.csv`, then diff frame-by-frame scores/states against
  `DetectionPipeline`'s own CSV dump fed the same MP4 via `ReplayFileFrameSource` — this is
  the strongest available regression signal since it validates the whole
  scoring/trigger/escalation chain against known-good output.
- Full build: `./gradlew assembleRelease` from `aaos-cockpit-app/`.
- On-device smoke test per `aaos-cockpit-app/CLAUDE.md`'s Verify step: install, run in
  `REPLAY_FILE` mode first (deterministic), watch `adb logcat` for the detection pipeline +
  existing `VitalGuardClimate`/`VitalGuardVoice` tags, confirm `DebugOverlayState`'s
  mandatory fields populate correctly; then attempt `LIVE_CAMERA` mode if camera support is
  present.
- Run GitNexus `detect_changes()` before committing, and `impact()` on
  `VitalGuardMonitorService`/`TriggerPayload` before editing them, per root `CLAUDE.md`'s
  standing GitNexus rules. (`VitalGuardMonitorService`/`MainActivity` impact already checked
  this session — both LOW risk. `TriggerPayload` impact still needed.)

---

## Session-specific operational notes for whoever resumes

- **GitNexus multi-repo disambiguation:** always pass `repo: "vital-guard-ai"` explicitly on
  every `mcp__gitnexus__*` tool call in this project — there are multiple repos indexed and
  omitting it errors.
- **GitNexus impact-check status:** `MainActivity` and `VitalGuardMonitorService` — both
  checked, LOW risk / 0 callers, cleared to edit. `TriggerPayload` — not yet checked, do
  this before Step 6 touches it.
- **`aaos-cockpit-app/CLAUDE.md` loop-learning log:** this module has a standing rule to
  append an entry to its `MEMORY.md` after every task (Goal → Understand impact → Change →
  Verify → Log outcome). **Not yet done for any work in this migration** — back-fill an entry
  for the Step 1 spike work once it's confirmed building/running, don't skip it going
  forward.
- **Glob tool note:** earlier this migration, `Glob` intermittently returned "No files
  found" for files that demonstrably existed. It has also succeeded cleanly on other calls
  in the same session. Treat it as generally usable, but fall back to direct `Read`/`Write`
  with absolute paths if it misbehaves rather than trusting a negative result blindly.
