# Tài — Week 1 Remaining Tasks (T5 30/07 → CN 03/08) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement the *codable* tasks (3 and 4) task-by-task. Tasks 1, 2, 5, and 6 require physical access to the Skycraft VM / CarSky room network and root ADB — they cannot be executed by an agent; they are written as step-by-step runbooks for Tài to execute by hand, with exact verification commands and evidence to capture.

**Goal:** Close the gap between what's code-complete-but-unverified (network pin,
HVAC writes) and what hasn't been started at all (priv-app permission
resolution, VHAL Bridge Service safety net, debug overlay UI), so Week 1 ends
with a verified, demoable Critical→HVAC path instead of just unit-tested Kotlin.

**Architecture:** No architectural changes to what's already built — this plan
only *closes gaps* against the already-committed `dms-delivery-fsm-reconciliation`
design (see `docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md`).
`ClimateActuatorGateway`/`VoiceAlertGateway`, `DrowsinessController`,
`TriggerPollClient`, and `trigger_server.py` are done and unit-tested; this plan
adds the priv-app permission resolution, the Option C bridge-service safety
net, the mandatory debug overlay, and live verification evidence — the parts
that need the real Skycraft VM instead of a JVM unit test.

**Tech Stack:** Kotlin 1.9.22 / AGP 8.2.2 (existing `aaos-cockpit-app` project,
namespace `com.vitalguard.ai`, applicationId `com.vitalguard.ai`), root ADB on
the Skycraft VM (`trout_arm64:/ $` prompt per CLAUDE.md), Python 3 / `http.server`
(existing `dms-ai-engine` container, already serving `GET /latest-trigger` on
port 8765).

## Global Constraints

- No cloud/external-host calls anywhere in the trigger path — every fix in
  this plan stays on the room-internal network pin (CLAUDE.md "Security & IPC").
- Never guess `areaId`/min/max for a VHAL property — query `getCarPropertyConfig()`
  at runtime and clamp (already implemented in `RealClimateActuatorGateway`;
  do not regress this when touching that file).
- `GATEWAY_MODE` must stay runtime-toggleable via the existing
  `com.vitalguard.ai.SET_GATEWAY_MODE` broadcast (`GatewayModeReceiver.kt`) —
  do not introduce a second, build-flavor-only toggle.
- Self-test before escalating: every "if X fails" branch below says exactly
  what evidence to capture (exception text, dumpsys output, log line) before
  messaging the mentor — per CLAUDE.md's "no speculative escalation" rule.
- Do not touch `DrowsinessController.kt`'s hysteresis/debounce/cooldown scope —
  that responsibility deliberately lives in Python's `TriggerEmitter`
  (documented in the reconciliation design, Decision 6). Adding a second
  hysteresis layer in Kotlin would violate that decision, not fix a gap.

---

## Current State (read this before starting — avoids re-doing finished work)

Verified by reading the actual repo on 2026-07-29 (branch `feature/cv-backend-remediation`, gitnexus index refreshed at commit `7daf64b`):

| Item | Status |
|---|---|
| `contracts/trigger.schema.json` | ✅ Done, validated by `test_schema.py` |
| `ClimateActuatorGateway` / `VoiceAlertGateway` split, Fake+Real each | ✅ Done |
| `DrowsinessController.kt` FSM (latch/idempotency/fallback/crash-safety) + 6 unit tests | ✅ Done — see note above on scope (hysteresis intentionally in Python) |
| `trigger_server.py` (Container-side HTTP `/latest-trigger`) | ✅ Done, 4 tests passing |
| `TriggerPollClient.kt` (App-side poller, 500ms, 3-failure connection-loss) | ✅ Done, 4 tests passing |
| `GatewayModeStore`/`GatewayModeReceiver` (runtime Fake↔Real toggle) | ✅ Done |
| **HVAC signature-permission resolution (Option A/B)** | ❌ **Not started.** `AndroidManifest.xml` only has a normal `<uses-permission>` for `CONTROL_CAR_CLIMATE` — no `privapp-permissions-*.xml`, no `/system/priv-app` placement, no `adb shell dumpsys package` evidence anywhere in the repo. |
| **VHAL Bridge Service (Option C priv-app)** | ❌ **Not started.** `settings.gradle` only includes `:app`; `RealClimateActuatorGateway` calls `CarPropertyManager` directly from the main app. Explicitly logged as "untouched by this design" in the reconciliation spec. |
| **Network pin real IP** | ⚠️ **Code done, not verified live.** `VitalGuardMonitorService.kt` hardcodes `http://192.168.49.2:8765` with a comment calling it a placeholder pending Day-1 verification. |
| **Debug Overlay UI** | ❌ **Not started.** `activity_main.xml` is a single static TextView. `DrowsinessController.lastGatewayAction` exists as a property specifically so an overlay can read it later — but no UI reads it yet. |
| Container "Driver Video Replay" blueprint node | ❓ Unknown — this is a CarSky platform action, not a repo file; confirm with Tài directly. |
| Video dự phòng bản 1 | ❓ Unknown — not a repo artifact either way. |

**Also flag, don't silently fix:** `git status` currently shows 3 tracked `.mp4`
files under `dms-ai-engine/public/` deleted (uncommitted) and `evidence_run.csv`
modified. Confirm with whoever deleted them (intentional cleanup vs. accidental)
before the next commit touches that directory — don't `git add -A` over it.

---

## Definition of Done — Week 1 Closeout (this plan)

This plan is done when **all** of the following are true — not when the code
compiles, but when each has the evidence artifact listed:

- [ ] **Permission proven, not assumed:** `adb shell dumpsys package com.vitalguard.ai`
  shows `CONTROL_CAR_CLIMATE` as `granted=true` on the real Skycraft VM, with a
  saved before/after output (Task 1).
- [ ] **Network pin proven live, not just unit-tested:** a logcat capture shows
  the app reacting to a trigger that actually crossed the room network from the
  Container Node — not `localhost`, not a JVM test double (Task 2).
- [ ] **A safety net exists and has been exercised at least once:** the Bridge
  Service applies/reverts HVAC via its own broadcast path, observed in its own
  logcat tag, independent of `RealClimateActuatorGateway` (Task 3).
- [ ] **The overlay is on screen, not just in a StateFlow:** running the app
  against `main.py --mock` shows all mandated fields updating live on the
  device/emulator screen (Task 4).
- [ ] **Every open question has a yes/no answer on record**, not "assumed
  fine": blueprint node + network pin existence confirmed by whoever has CarSky
  console access, dated (Task 5).
- [ ] **A contingency asset exists on disk**, not just "we could record one":
  the fallback video clip is saved at an agreed path and playable (Task 6).
- [ ] **The uncommitted `.mp4`/`evidence_run.csv` state from "Current State"
  above has been explicitly resolved** (kept, restored, or confirmed
  intentional) — not left dangling into next week's work.
- [ ] Every task below's own Definition of Done is checked off.

If any box above can't be checked, Week 1 is not closed for Tài's scope —
say so plainly in the CN 02-03/08 team review rather than reporting "done."

---

## File Structure

**New Kotlin module (Task 3):**
```
aaos-cockpit-app/
├── settings.gradle              <- Modify: add `include(":bridge-service")`
├── bridge-service/               <- Create: new Gradle module, priv-app
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/vitalguard/ai/bridge/
│           └── HvacBridgeReceiver.kt
```

**Existing app module (Tasks 3 & 4):**
```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/
├── ClimateActuatorGateway.kt     <- Modify: add BridgeClimateActuatorGateway (Task 3)
├── DebugOverlayState.kt          <- Create (Task 4)
├── DrowsinessController.kt       <- Modify: publish state to DebugOverlayState (Task 4)
├── TriggerPollClient.kt          <- Modify: publish poll stats to DebugOverlayState (Task 4)
├── VitalGuardMonitorService.kt   <- Modify: real IP (Task 2), wire overlay (Task 4)
└── MainActivity.kt               <- Modify: render DebugOverlayState (Task 4)
aaos-cockpit-app/app/src/main/res/layout/
└── activity_main.xml             <- Modify: overlay fields (Task 4)
aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/
└── DebugOverlayStateTest.kt      <- Create (Task 4)
```

---

## Task 1: Resolve the HVAC signature-permission risk (Option A, fallback B)

**This is the single highest-risk open item** — until it's resolved, `RealClimateActuatorGateway.applyDrowsinessOverride()` will silently fail or throw `SecurityException` on the real VM no matter how correct the Kotlin code is, and Task 3 (Bridge Service) has nothing to fall back to if this isn't proven first.

**Files:** none in this repo — this is Skycraft VM system configuration. Produces a `privapp-permissions-com.vitalguard.ai.xml` file that lives on the VM's `/system/etc/permissions/`, not in git (system image state, not app source).

- [ ] **Step 1: Build and install the current app**
  ```bash
  cd aaos-cockpit-app
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

- [ ] **Step 2: Confirm the permission is currently denied (baseline evidence)**
  ```bash
  adb shell dumpsys package com.vitalguard.ai | grep -A2 CONTROL_CAR_CLIMATE
  ```
  Expected: the permission is listed but **not granted** (no `granted=true`), because a third-party (non-priv-app) APK cannot hold a signature|privileged permission. Save this output — it's the "before" evidence CLAUDE.md's mandatory rule requires before escalating.

- [ ] **Step 3: Apply Option A — priv-app + allowlist**
  ```bash
  adb root
  adb remount
  adb shell mkdir -p /system/priv-app/VitalGuardAI
  adb push app/build/outputs/apk/debug/app-debug.apk /system/priv-app/VitalGuardAI/VitalGuardAI.apk
  ```
  Create the allowlist file locally, then push it:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <permissions>
      <privapp-permissions package="com.vitalguard.ai">
          <permission name="android.car.permission.CONTROL_CAR_CLIMATE" />
      </privapp-permissions>
  </permissions>
  ```
  ```bash
  adb push privapp-permissions-com.vitalguard.ai.xml /system/etc/permissions/
  adb reboot
  ```

- [ ] **Step 4: Verify Option A worked**
  ```bash
  adb wait-for-device
  adb shell dumpsys package com.vitalguard.ai | grep -A2 CONTROL_CAR_CLIMATE
  ```
  Expected: now shows `granted=true`. **Save this output as the Step 4 evidence file** —
  this is the exact artifact CLAUDE.md's T3 row asks for
  (`log kết quả adb shell dumpsys package | grep CONTROL_CAR_CLIMATE`).

- [ ] **Step 5: If Option A fails, capture the exact error before falling back**
  If the reboot loops, or `dumpsys` still shows the permission ungranted, capture:
  - The exact `adb logcat` output around `PackageManager` at boot (look for
    `Privileged permission not in privapp-permissions allowlist` or similar).
  - Whether `/system/priv-app/VitalGuardAI/VitalGuardAI.apk` actually persisted
    after reboot (`adb shell ls /system/priv-app/VitalGuardAI/`).
  This is the concrete evidence CLAUDE.md requires before trying Option B or
  escalating to the mentor — do not skip straight to Option B without it.

- [ ] **Step 6 (only if Step 4 failed): Apply Option B fallback**
  ```bash
  adb shell "getprop ro.control_privapp_permissions"
  adb shell setprop ro.control_privapp_permissions disable
  adb reboot
  adb wait-for-device
  adb shell dumpsys package com.vitalguard.ai | grep -A2 CONTROL_CAR_CLIMATE
  ```
  Note in the runbook that Option B was used and why (faster but broader risk
  surface — disables the allowlist check system-wide, not just for this app).

- [ ] **Step 7: Record the result**
  Append a new `## HVAC Permission Resolution` section to
  `docs/superpowers/plans/2026-07-28-dms-delivery-fsm-reconciliation-design.md`
  (or a new `docs/HVAC_PERMISSION_RESULTS.md` if that file feels like the wrong
  place) with: which option worked, the before/after `dumpsys` output, and the
  date. This becomes the acceptance-gate evidence, matching the pattern already
  used in `dms-ai-engine/CV_REMEDIATION_RESULTS.md` for the CV side.

**Definition of Done:**
- [ ] Baseline "before" `dumpsys` output captured (Step 2) — proves the risk was real, not assumed.
- [ ] "After" `dumpsys` output shows `granted=true` for `CONTROL_CAR_CLIMATE` on `com.vitalguard.ai` (Step 4 or 6).
- [ ] Which option worked (A or B) is written down, including the reboot survived it (permission re-checked after a fresh reboot, not just right after pushing files).
- [ ] If Option B: the system-wide risk tradeoff (disables the allowlist check for *every* privapp, not just this one) is explicitly acknowledged in the written record, not silently accepted.
- [ ] Result recorded in a doc per Step 7 — a teammate who wasn't there can read it and know which option is live without re-running `dumpsys` themselves.
- [ ] If both options failed: concrete failure evidence (exact logcat lines, not "it didn't work") is attached to the mentor escalation.

---

## Task 2: Wire and verify the real network-pin IP

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt:96`

**Interfaces:** none new — `CONTAINER_NODE_BASE_URL` is a private constant only
consumed inside this file.

- [ ] **Step 1: Get the Container Node's real room-internal IP**
  On the Container Node (where `dms-ai-engine` runs):
  ```bash
  cd dms-ai-engine
  python main.py --mock --host 0.0.0.0 --port 8765
  ```
  From the CarSky control plane, find the IP the network pin assigned the
  Container Node (the same pin added per CLAUDE.md's "Trigger Delivery" section
  — self-service, no BTC approval needed if not already added).

- [ ] **Step 2: Update the constant**
  ```kotlin
  // aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt
  private const val CONTAINER_NODE_BASE_URL = "http://<real-room-ip>:8765"
  ```

- [ ] **Step 3: Rebuild, install, and verify live delivery**
  ```bash
  cd aaos-cockpit-app
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb logcat -s VitalGuardController VitalGuardClimate VitalGuardVoice
  ```
  With `main.py --mock` still running on the Container Node, confirm the app's
  logcat shows the controller reacting to the mock CRITICAL→RECOVERED cycle
  within a few seconds (poll interval is 500ms) — this proves the network pin
  path end-to-end, not just each side in isolation.

- [ ] **Step 4: If the app never receives a trigger**
  Capture concrete evidence before escalating: `adb shell ping <container-ip>`
  (network reachability), whether `main.py --mock`'s own console shows the
  server bound (`Xong. CSV:...` doesn't print until the run ends, but the
  server starts immediately — confirm with `curl http://<ip>:8765/latest-trigger`
  from a machine on the same room network), and any `TriggerPollClient`
  exception text from logcat. Only escalate with one of these three pieces of
  evidence attached.

- [ ] **Step 5: Commit**
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt
  git commit -m "Wire real Container Node IP into the network-pin trigger poll client"
  ```

**Definition of Done:**
- [ ] `CONTAINER_NODE_BASE_URL` contains the real room-internal IP, not `192.168.49.2`, and the "Day-1 verification" placeholder comment is removed.
- [ ] A logcat capture exists showing `VitalGuardController` reacting to a mock CRITICAL→RECOVERED cycle served by the Container Node over the actual room network (not `adb reverse`/localhost — that only proves the code, not the network pin).
- [ ] The full round trip (Container emits → app polls → controller acts) is observed to complete within a few poll intervals (~1-2s) — a controller reaction that never appears, or appears only after minutes, is a fail even if it eventually shows up.
- [ ] If it failed: reachability (`ping`), server-liveness (`curl`), and client-side exception evidence were all checked before concluding it needs mentor/infra escalation.
- [ ] Change committed (Step 5).

---

## Task 3: VHAL Bridge Service (Option C safety-net priv-app)

**Files:**
- Modify: `aaos-cockpit-app/settings.gradle`
- Create: `aaos-cockpit-app/bridge-service/build.gradle`
- Create: `aaos-cockpit-app/bridge-service/src/main/AndroidManifest.xml`
- Create: `aaos-cockpit-app/bridge-service/src/main/java/com/vitalguard/ai/bridge/HvacBridgeReceiver.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/BridgeClimateActuatorGatewayTest.kt`

**Interfaces:**
- Consumes: none from earlier tasks.
- Produces: a new `ClimateActuatorGateway` implementation
  (`BridgeClimateActuatorGateway`) that sends an internal Broadcast Intent
  instead of calling `CarPropertyManager` directly — selectable via
  `GatewayMode` the same way `RealClimateActuatorGateway` is today, so a
  broken bridge priv-app doesn't take down the main app.

**Why this task exists:** per CLAUDE.md's HVAC Permission Risk section, Option
C isolates HVAC-write logic into its own small priv-app so that *if the bridge
breaks during the demo, only one small service needs fixing* — the main app
never touches `CarPropertyManager` directly in this path. This is additive: it
does not replace `RealClimateActuatorGateway` (which stays as the direct-write
fallback if the bridge itself won't install/boot).

- [ ] **Step 1: Add the new Gradle module**
  ```gradle
  // aaos-cockpit-app/settings.gradle
  include(":app")
  include(":bridge-service")
  ```

- [ ] **Step 2: Create `bridge-service/build.gradle`**
  ```gradle
  plugins {
      id("com.android.application")
      id("org.jetbrains.kotlin.android")
  }

  android {
      namespace = "com.vitalguard.ai.bridge"
      compileSdk = 34

      defaultConfig {
          applicationId = "com.vitalguard.ai.bridge"
          minSdk = 29
          targetSdk = 34
          versionCode = 1
          versionName = "1.0"
      }

      buildTypes {
          release {
              minifyEnabled false
              signingConfig signingConfigs.debug
          }
      }

      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_17
          targetCompatibility = JavaVersion.VERSION_17
      }
      kotlinOptions {
          jvmTarget = "17"
      }
  }

  dependencies {
      compileOnly files("${android.sdkDirectory}/platforms/android-${android.compileSdk}/optional/android.car.jar")
  }
  ```

- [ ] **Step 3: Create `bridge-service/src/main/AndroidManifest.xml`**
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <manifest xmlns:android="http://schemas.android.com/apk/res/android">
      <uses-permission android:name="android.car.permission.CONTROL_CAR_CLIMATE" />

      <application android:label="VitalGuard HVAC Bridge">
          <receiver
              android:name=".HvacBridgeReceiver"
              android:exported="true">
              <intent-filter>
                  <action android:name="com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE" />
                  <action android:name="com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE" />
              </intent-filter>
          </receiver>
      </application>
  </manifest>
  ```

- [ ] **Step 4: Create `HvacBridgeReceiver.kt`** — this is the *only* place in
  the bridge module that touches `CarPropertyManager`, moved verbatim from
  `RealClimateActuatorGateway`'s logic (do not duplicate/rewrite the
  clamping behavior — copy it so both call sites stay in sync until one is
  retired):
  ```kotlin
  package com.vitalguard.ai.bridge

  import android.car.Car
  import android.car.VehiclePropertyIds
  import android.car.hardware.property.CarPropertyManager
  import android.content.BroadcastReceiver
  import android.content.Context
  import android.content.Intent
  import android.util.Log

  class HvacBridgeReceiver : BroadcastReceiver() {
      private val TAG = "VitalGuardHvacBridge"

      companion object {
          const val ACTION_APPLY_OVERRIDE = "com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"
          const val ACTION_REVERT_BASELINE = "com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"
          private const val FALLBACK_AREA_ID = 1
          private const val FALLBACK_FAN_SPEED = 7
          private const val COLD_TEMPERATURE_C = 20.0f
          private const val BASELINE_FAN_SPEED = 2
          private const val BASELINE_TEMPERATURE_C = 25.0f
      }

      override fun onReceive(context: Context, intent: Intent) {
          when (intent.action) {
              ACTION_APPLY_OVERRIDE -> applyOverride(context)
              ACTION_REVERT_BASELINE -> revertBaseline(context)
          }
      }

      private fun applyOverride(context: Context) {
          try {
              val carPropertyManager = carPropertyManager(context)
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                  carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
              }
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                  val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                  @Suppress("DEPRECATION")
                  val maxFanSpeed = (config?.getMaxValue(area) as? Int) ?: FALLBACK_FAN_SPEED
                  carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, maxFanSpeed)
              }
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                  carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, COLD_TEMPERATURE_C)
              }
              Log.d(TAG, "Bridge applied climate override: AC=ON, Fan=max, Temp=${COLD_TEMPERATURE_C}C")
          } catch (t: Throwable) {
              Log.e(TAG, "Bridge failed to apply VHAL climate override: ${t.message}")
          }
      }

      private fun revertBaseline(context: Context) {
          try {
              val carPropertyManager = carPropertyManager(context)
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                  carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, false)
              }
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                  carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, BASELINE_FAN_SPEED)
              }
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                  carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, BASELINE_TEMPERATURE_C)
              }
              Log.d(TAG, "Bridge reverted climate to baseline")
          } catch (t: Throwable) {
              Log.e(TAG, "Bridge failed to revert VHAL climate baseline: ${t.message}")
          }
      }

      private fun carPropertyManager(context: Context): CarPropertyManager {
          val car = Car.createCar(context)
          return car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
      }

      private fun forEachSupportedArea(
          carPropertyManager: CarPropertyManager,
          propertyId: Int,
          action: (Int) -> Unit
      ) {
          val areaIds = carPropertyManager.getCarPropertyConfig(propertyId)?.areaIds
              ?: intArrayOf(FALLBACK_AREA_ID)
          for (area in areaIds) {
              try {
                  action(area)
              } catch (t: Throwable) {
                  Log.w(TAG, "Bridge failed to set property 0x${propertyId.toString(16)} for area 0x${area.toString(16)}: ${t.message}")
              }
          }
      }
  }
  ```

- [ ] **Step 5: Add `BridgeClimateActuatorGateway` to the main app** — this is
  the piece the main app actually depends on; it never touches
  `CarPropertyManager`, only sends broadcasts:
  ```kotlin
  // aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt — append
  /** Sends an internal Broadcast Intent to the bridge-service priv-app instead of
   * calling CarPropertyManager directly — CLAUDE.md's Option C safety net. If the
   * bridge apk is missing/crashed, this call still returns normally (fire-and-forget
   * broadcast); it does not throw, so it cannot itself trip the controller's
   * OVERRIDE_FAILED path — bridge health must be verified separately via logcat. */
  class BridgeClimateActuatorGateway(private val context: Context) : ClimateActuatorGateway {
      override fun applyDrowsinessOverride() {
          context.sendBroadcast(Intent("com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"))
      }

      override fun revertToBaseline() {
          context.sendBroadcast(Intent("com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"))
      }
  }
  ```
  Add the `Intent` import at the top of the file if not already present.

- [ ] **Step 6: Write the unit test**
  ```kotlin
  // aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/BridgeClimateActuatorGatewayTest.kt
  package com.vitalguard.ai

  import android.content.Context
  import android.content.Intent
  import org.junit.Assert.assertEquals
  import org.junit.Test
  import org.mockito.ArgumentCaptor
  import org.mockito.Mockito.mock
  import org.mockito.Mockito.verify

  class BridgeClimateActuatorGatewayTest {
      @Test
      fun `applyDrowsinessOverride sends the APPLY_HVAC_OVERRIDE broadcast`() {
          val context = mock(Context::class.java)
          val gateway = BridgeClimateActuatorGateway(context)

          gateway.applyDrowsinessOverride()

          val captor = ArgumentCaptor.forClass(Intent::class.java)
          verify(context).sendBroadcast(captor.capture())
          assertEquals("com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE", captor.value.action)
      }

      @Test
      fun `revertToBaseline sends the REVERT_HVAC_BASELINE broadcast`() {
          val context = mock(Context::class.java)
          val gateway = BridgeClimateActuatorGateway(context)

          gateway.revertToBaseline()

          val captor = ArgumentCaptor.forClass(Intent::class.java)
          verify(context).sendBroadcast(captor.capture())
          assertEquals("com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE", captor.value.action)
      }
  }
  ```
  This needs a Mockito dependency — add if not already present:
  ```gradle
  // aaos-cockpit-app/app/build.gradle — inside dependencies {}
  testImplementation("org.mockito:mockito-core:5.8.0")
  ```

- [ ] **Step 7: Run the test**
  ```bash
  cd aaos-cockpit-app
  ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.BridgeClimateActuatorGatewayTest"
  ```
  Expected: both tests PASS.

- [ ] **Step 8: Build, install, and verify on the VM**
  ```bash
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb root && adb remount
  adb shell mkdir -p /system/priv-app/VitalGuardHvacBridge
  adb push bridge-service/build/outputs/apk/debug/bridge-service-debug.apk /system/priv-app/VitalGuardHvacBridge/VitalGuardHvacBridge.apk
  ```
  Push a second allowlist entry for `com.vitalguard.ai.bridge` (same pattern as
  Task 1 Step 3), reboot, then trigger the app's automated path (or manually
  broadcast) and confirm via `adb logcat -s VitalGuardHvacBridge` that the
  bridge receives and applies the override — independent of whether the main
  app's own `RealClimateActuatorGateway` path is in use.

- [ ] **Step 9: Do not switch the default gateway yet** — leave
  `VitalGuardMonitorService.kt` constructing `RealClimateActuatorGateway` as
  today. Switching to `BridgeClimateActuatorGateway` as the default is a
  judgment call for after both paths have been demo-tested side by side; make
  that switch a deliberate one-line change reviewed with Phát, not a default
  of this task.

- [ ] **Step 10: Commit**
  ```bash
  git add aaos-cockpit-app/settings.gradle aaos-cockpit-app/bridge-service \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/BridgeClimateActuatorGatewayTest.kt \
          aaos-cockpit-app/app/build.gradle
  git commit -m "Add VHAL Bridge Service (Option C) priv-app as an HVAC safety net"
  ```

**Definition of Done:**
- [ ] `./gradlew :bridge-service:assembleDebug` succeeds and produces an installable APK.
- [ ] `BridgeClimateActuatorGatewayTest`'s 2 tests pass (Step 7).
- [ ] The bridge APK is installed as a priv-app on the real VM with `CONTROL_CAR_CLIMATE` granted — its own `dumpsys` evidence, separate from Task 1's main-app evidence (different `applicationId`).
- [ ] A broadcast sent to `com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE` is observed in `VitalGuardHvacBridge` logcat actually calling `CarPropertyManager`, independent of whether `RealClimateActuatorGateway` is also wired in the main app.
- [ ] The main app still defaults to `RealClimateActuatorGateway` (Step 9) — a deliberate, undone-by-default switch, confirmed by reading `VitalGuardMonitorService.kt`, not assumed.
- [ ] A one-line decision is recorded (even if it's "not switching yet") on whether/when the default becomes `BridgeClimateActuatorGateway`.
- [ ] Committed (Step 10).

---

## Task 4: Mandatory Debug Overlay UI

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DebugOverlayState.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPollClient.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/MainActivity.kt`
- Modify: `aaos-cockpit-app/app/src/main/res/layout/activity_main.xml`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DebugOverlayStateTest.kt`

**Interfaces:**
- Produces: `DebugOverlayState` — a process-wide, single-process
  `MutableStateFlow<OverlaySnapshot>` singleton (no AIDL/Binder needed since
  the poller, controller, and Activity all run in this one app process).
  `OverlaySnapshot` carries every field CLAUDE.md's "Debug Overlay" section
  mandates: `perclos`, `eyeOpenProbability`, `headEulerAngleX`, `state`,
  `receivingTrigger: Boolean`, `lastPollAt: Long`, `lastGatewayAction: String`.

- [ ] **Step 1: Write the failing test**
  ```kotlin
  // aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DebugOverlayStateTest.kt
  package com.vitalguard.ai

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class DebugOverlayStateTest {
      @Test
      fun `default snapshot starts in an unknown, not-receiving state`() {
          val state = DebugOverlayState()
          val snapshot = state.flow.value

          assertEquals("UNKNOWN", snapshot.driverState)
          assertEquals(false, snapshot.receivingTrigger)
          assertEquals("NONE", snapshot.lastGatewayAction)
      }

      @Test
      fun `updateFromPayload reflects the latest trigger's feature values`() {
          val state = DebugOverlayState()
          val payload = TriggerPayload(
              timestampMs = 1000L, source = "test", score = 0.9f, confidence = 1.0f,
              state = TriggerPayload.STATE_CRITICAL,
              features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
              reason = "test", correlationId = "vg-0001",
          )

          state.updateFromPayload(payload)
          val snapshot = state.flow.value

          assertEquals(0.8f, snapshot.perclos)
          assertEquals(0.1f, snapshot.eyeOpenProbability)
          assertEquals(28.0f, snapshot.headEulerAngleX)
          assertEquals("CRITICAL", snapshot.driverState)
          assertEquals(true, snapshot.receivingTrigger)
      }

      @Test
      fun `markConnectionLost flips receivingTrigger to false without touching last feature values`() {
          val state = DebugOverlayState()
          val payload = TriggerPayload(
              timestampMs = 1000L, source = "test", score = 0.9f, confidence = 1.0f,
              state = TriggerPayload.STATE_CRITICAL,
              features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
              reason = "test", correlationId = "vg-0001",
          )
          state.updateFromPayload(payload)

          state.markConnectionLost()
          val snapshot = state.flow.value

          assertEquals(false, snapshot.receivingTrigger)
          assertEquals(0.8f, snapshot.perclos, 0.001f) // last-known value preserved, not zeroed
      }

      @Test
      fun `updateGatewayAction reflects the controller's last outcome`() {
          val state = DebugOverlayState()

          state.updateGatewayAction("OVERRIDE_FAILED")

          assertEquals("OVERRIDE_FAILED", state.flow.value.lastGatewayAction)
      }
  }
  ```

- [ ] **Step 2: Run to verify it fails**
  ```bash
  cd aaos-cockpit-app
  ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DebugOverlayStateTest"
  ```
  Expected: FAIL — `DebugOverlayState` doesn't exist yet.

- [ ] **Step 3: Create `DebugOverlayState.kt`**
  ```kotlin
  package com.vitalguard.ai

  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow

  data class OverlaySnapshot(
      val perclos: Float = 0f,
      val eyeOpenProbability: Float = 0f,
      val headEulerAngleX: Float = 0f,
      val driverState: String = "UNKNOWN",
      val receivingTrigger: Boolean = false,
      val lastPollAt: Long = 0L,
      val lastGatewayAction: String = "NONE",
  )

  /** Process-wide observable snapshot for the mandatory debug overlay
   * (CLAUDE.md "Debug Overlay" section). Single-process app — a plain
   * singleton StateFlow is sufficient, no cross-process IPC needed. */
  class DebugOverlayState {
      private val _flow = MutableStateFlow(OverlaySnapshot())
      val flow: StateFlow<OverlaySnapshot> = _flow

      fun updateFromPayload(payload: TriggerPayload) {
          _flow.value = _flow.value.copy(
              perclos = payload.features.perclos,
              eyeOpenProbability = payload.features.eyeOpenProbability,
              headEulerAngleX = payload.features.headEulerAngleX,
              driverState = payload.state,
              receivingTrigger = true,
              lastPollAt = payload.timestampMs,
          )
      }

      fun markConnectionLost() {
          _flow.value = _flow.value.copy(receivingTrigger = false, driverState = "UNKNOWN")
      }

      fun updateGatewayAction(action: String) {
          _flow.value = _flow.value.copy(lastGatewayAction = action)
      }

      companion object {
          val instance = DebugOverlayState()
      }
  }
  ```

- [ ] **Step 4: Run again to verify it passes**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DebugOverlayStateTest"
  ```
  Expected: all 4 tests PASS.

- [ ] **Step 5: Wire `DrowsinessController` to publish gateway actions** —
  add a call after each `lastGatewayAction` assignment (3 call sites: apply
  success, apply failure, revert):
  ```kotlin
  // DrowsinessController.kt — inside handleCritical()'s try block, after:
  lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
  // add:
  DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
  ```
  Repeat for the `catch` block's `OVERRIDE_FAILED` assignment and for both
  assignments inside `revertToBaseline()` (`REVERTED`, `REVERT_FAILED`).

- [ ] **Step 6: Wire `VitalGuardMonitorService` to publish payload/connection state**
  ```kotlin
  // VitalGuardMonitorService.kt — inside the TriggerPollClient construction:
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
  ```

- [ ] **Step 7: Update `activity_main.xml`** — add one `TextView` per overlay
  field, each with a stable `android:id` for `MainActivity` to bind to:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
      android:layout_width="match_parent"
      android:layout_height="match_parent"
      android:orientation="vertical"
      android:padding="24dp"
      android:background="#101418">

      <TextView
          android:id="@+id/statusText"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:text="@string/cockpit_status_normal"
          android:textColor="#FFFFFF"
          android:textSize="28sp" />

      <TextView
          android:id="@+id/overlayPerclos"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:textColor="#B0B8C0"
          android:textSize="16sp"
          android:layout_marginTop="16dp" />

      <TextView
          android:id="@+id/overlayEyeOpen"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:textColor="#B0B8C0"
          android:textSize="16sp" />

      <TextView
          android:id="@+id/overlayHeadPitch"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:textColor="#B0B8C0"
          android:textSize="16sp" />

      <TextView
          android:id="@+id/overlayReceiving"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:textColor="#B0B8C0"
          android:textSize="16sp" />

      <TextView
          android:id="@+id/overlayGatewayAction"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:textColor="#B0B8C0"
          android:textSize="16sp" />

  </LinearLayout>
  ```

- [ ] **Step 8: Update `MainActivity.kt`** to collect the flow and render it:
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

          val perclosView = findViewById<TextView>(R.id.overlayPerclos)
          val eyeOpenView = findViewById<TextView>(R.id.overlayEyeOpen)
          val headPitchView = findViewById<TextView>(R.id.overlayHeadPitch)
          val receivingView = findViewById<TextView>(R.id.overlayReceiving)
          val gatewayActionView = findViewById<TextView>(R.id.overlayGatewayAction)
          val statusView = findViewById<TextView>(R.id.statusText)

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
  Add `androidx.lifecycle:lifecycle-runtime-ktx:2.7.0` to `app/build.gradle`'s
  `dependencies {}` if `lifecycleScope` doesn't resolve.

- [ ] **Step 9: Manual on-device verification**
  ```bash
  cd aaos-cockpit-app
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  With `python main.py --mock` running on the Container Node (real IP wired
  per Task 2), open the app and confirm all 5 overlay fields update live as
  the mock CRITICAL→RECOVERED cycle runs — this is what makes threshold tuning
  and later demo debugging possible without reading logcat, per CLAUDE.md's
  "never tune blind" rule.

- [ ] **Step 10: Commit**
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DebugOverlayState.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/MainActivity.kt \
          aaos-cockpit-app/app/src/main/res/layout/activity_main.xml \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DebugOverlayStateTest.kt \
          aaos-cockpit-app/app/build.gradle
  git commit -m "Add mandatory debug overlay: perclos/eye/head-pitch/state/gateway-action"
  ```

**Definition of Done:**
- [ ] All 4 `DebugOverlayStateTest` cases pass (Step 4).
- [ ] Against CLAUDE.md's "Debug Overlay" mandate, this task delivers: `perclos` ✅, `eyeOpenProbability` ✅, `headEulerAngleX` ✅, current state ✅, whether a trigger is currently being received ✅, last gateway action ✅.
- [ ] **Explicitly not delivered by this task** (do not claim these are done): "remaining cooldown" and "trigger frame rate/frequency" — both live in Python's `TriggerEmitter`/Container Node per Decision 6, and neither value crosses the wire in `contracts/trigger.schema.json` today. Closing this gap needs a schema change (adding e.g. `cooldownRemainingMs`/`frameRateHz` fields) — out of scope for this task; file it as a follow-up rather than silently treating the overlay as 100% CLAUDE.md-compliant.
- [ ] On-device manual check (Step 9): all 5 delivered fields visibly update in real time while `main.py --mock` runs — a value that's wired in code but never observed changing on screen does not count as done.
- [ ] Committed (Step 10).

---

## Task 5: Confirm CarSky blueprint items (ops check, not code)

Not verifiable from this repo — confirm directly with whoever has CarSky
console access (Tài, per the plan table):

- [ ] Is the Container "Driver Video Replay" node actually added to the
  blueprint yet? If not, add it now (self-service, no BTC approval needed per
  CLAUDE.md).
- [ ] Is the network pin between the Container Node and the Skycraft VM
  actually provisioned in the CarSky room (distinct from Task 2's app-side IP
  config — that task assumes this pin already exists)?

If either is missing, do this **before** Task 2 — Task 2's live verification
step is meaningless without the pin existing first.

**Definition of Done:**
- [ ] Both questions above have an explicit yes/no answer, dated, from someone with actual CarSky console access — "probably yes" does not count.
- [ ] If either was "no", it has since been added and re-confirmed "yes" with a second check — not just "added, assumed working."
- [ ] The answer is written down somewhere Task 2 can be started from without re-asking (e.g. a line in this plan's "Current State" table, updated).

---

## Task 6: Record video dự phòng bản 1 (Fake-gateway local E2E)

This is the one deliverable in this batch that needs zero new code — the Fake
gateway path (`GatewayMode.FAKE`, default) is already fully wired and unit
tested.

- [ ] **Step 1:** Ensure `GATEWAY_MODE` is `FAKE` (default; or force it):
  ```bash
  adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode FAKE
  ```
- [ ] **Step 2:** Start the app, start `python main.py --mock` on the
  Container Node (with Task 2's real IP wired, or `adb reverse` to localhost
  if testing on an emulator without the room network).
- [ ] **Step 3:** Screen-record the AAOS emulator/VM showing the debug overlay
  (Task 4) transition NORMAL → WARNING → CRITICAL → RECOVERED, narrating that
  gateways are Fake (no real HVAC/audio change expected) — this is the
  contingency clip per CLAUDE.md's Demo Script storyboard, timeline-compressed
  since the mock scenario runs in ~10 seconds rather than the full 20-minute
  stage timing.
- [ ] **Step 4:** Save the clip alongside `docs/` (or wherever the team's
  video assets already live — check the deleted-file situation from the
  "Current State" section above before choosing a path, so this doesn't land
  in a directory that's mid-reorganization).

**Definition of Done:**
- [ ] A playable clip file exists at an agreed, stable path (not someone's local Downloads folder) and is committed or otherwise backed up — not just recorded once and left on a phone/laptop.
- [ ] The clip visibly shows the full NORMAL → WARNING → CRITICAL → RECOVERED cycle on the debug overlay (Task 4), not just a CRITICAL freeze-frame.
- [ ] `GATEWAY_MODE=FAKE` is stated on-camera or in an accompanying note, so nobody mistakes this for a real-HVAC demo recording.
- [ ] The clip's location is referenced from wherever the team keeps the demo runbook/README, so it's findable during a live-demo failure without searching.

---

## Notes for execution order

1. **Task 5 first** (5 minutes, just a question) — unblocks Task 2's premise.
2. **Task 1 next** (highest risk, blocks Task 3 and any live HVAC demo).
3. **Task 2** once Task 1's permission is granted and Task 5 confirms the pin exists.
4. **Task 4** can happen in parallel with Task 1 — it's pure Kotlin, testable
   without VM access, and unblocks Task 6.
5. **Task 3** after Task 1 succeeds (no point building a safety net for a
   permission that isn't proven to work at all yet).
6. **Task 6** last, once Task 4's overlay exists to show in the recording.
