# Alert Preferences & Parked-State Suppression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the driver adjust voice/climate alert intensity without ever
being able to disable both channels during CRITICAL, and suppress
drowsiness/distraction *responses* (never detection) while the vehicle is
parked, based on real vehicle speed — not a manual off switch.

**Architecture:** Pure Kotlin, `aaos-cockpit-app` only. No changes to
`contracts/trigger.schema.json` or any file under `dms-ai-engine/`. Two new
Fake/Real gateway pairs (`AlertPreferencesStore`, `VehicleContextGateway`)
feed into `DrowsinessController`/`DistractionController`, which gain a
`isParked` gate and an `AlertPreferencesStore` dependency. Two pre-existing
bugs (unrelated to these two features, but which these features make
load-bearing) are fixed as part of this plan: climate/voice gateway calls
were coupled in one try/catch, and the AlertArbiter priority flag must never
be gated by a channel-mute preference.

**Tech Stack:** Kotlin (`aaos-cockpit-app/app`), SharedPreferences,
`CarPropertyManager`, JUnit4 + `kotlinx-coroutines-test` + Mockito (already a
`testImplementation` dependency — confirmed in `app/build.gradle`).

**Full design rationale:** `docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md`
— read it if a decision here looks unmotivated; every number and code shape
below traces back to a specific section there.

## Global Constraints

- No changes to `contracts/trigger.schema.json` or any `dms-ai-engine/` file.
- No changes to `DrowsinessController`'s hysteresis/debounce/cooldown scope —
  that stays owned entirely by Python's `TriggerEmitter` (spec Decision 6,
  unchanged by this plan).
- `AlertPreferences.voiceVolume` invariant lives in the data class's own
  `init` block. `AlertPreferences.isSafe()` (at least one channel enabled) is
  enforced only at `AlertPreferencesStore.save()`, never at construction.
- `alertArbiter.setDrowsinessCriticalActive(...)` must be called
  unconditionally in both `handleCritical()` and `revertToBaseline()` —
  **never** gated by `voiceEnabled`. This is the single most important
  invariant in this plan; violating it silently breaks distraction
  suppression during a voice-muted CRITICAL episode.
- `handleCritical()`/`revertToBaseline()` never set `latched = true` at the
  moment a CRITICAL is suppressed for being parked — only when a gateway
  call is actually attempted.
- Every numeric constant introduced (fan/temp intensity table, speed
  thresholds/sustain windows) is an explicitly-unvalidated baseline — keep
  the existing project convention of saying so in a comment, don't present
  them as tuned.

---

## File Structure

**New files:**
```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/
├── AlertPreferences.kt            # IntensityLevel, IntensityMapping, AlertPreferences
├── AlertPreferencesStore.kt       # interface + InMemory + Prefs
├── VehicleContextGateway.kt       # interface + Fake + Real
├── ParkedStateTracker.kt
└── VehicleContextPollClient.kt
aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/
├── AlertPreferencesTest.kt
├── IntensityMappingTest.kt
├── InMemoryAlertPreferencesStoreTest.kt
├── ParkedStateTrackerTest.kt
└── VehicleContextPollClientTest.kt
```

**Modified files:**
```
aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/
├── DrowsinessController.kt        # +AlertPreferencesStore, +isParked, decoupled try/catch
├── DistractionController.kt       # +AlertPreferencesStore, +isParked
├── ClimateActuatorGateway.kt      # RealClimateActuatorGateway reads IntensityMapping + clamps
├── VoiceAlertGateway.kt           # RealVoiceAlertGateway reads voiceVolume
├── VoiceEmergencyAssistant.kt     # speakAlert() passes a Bundle volume param
└── VitalGuardMonitorService.kt    # wires AlertPreferencesStore + VehicleContextPollClient
aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/
├── DrowsinessControllerTest.kt      # setUp() gains a store param; +6 tests
├── DistractionControllerTest.kt     # setUp() gains a store param; +3 tests
└── AlertArbiterIntegrationTest.kt   # setUp() gains a store param; +1 test
```

---

## Task 1: `AlertPreferences` data model + `IntensityMapping`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferences.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertPreferencesTest.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/IntensityMappingTest.kt`

**Interfaces:**
- Produces: `enum class IntensityLevel { LOW, MEDIUM, HIGH }`,
  `data class AlertPreferences(voiceEnabled: Boolean = true, voiceVolume: Float = 1.0f, climateEnabled: Boolean = true, climateIntensity: IntensityLevel = IntensityLevel.HIGH)`
  with `fun isSafe(): Boolean`, and `object IntensityMapping` with
  `fun fanSpeedFor(intensity: IntensityLevel): Int` /
  `fun temperatureCFor(intensity: IntensityLevel): Float`. Task 2 depends on
  `AlertPreferences`/`isSafe()`; Task 10 depends on `IntensityMapping`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing tests**

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertPreferencesTest.kt
package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPreferencesTest {
    @Test
    fun `default preferences is safe`() {
        assertTrue(AlertPreferences().isSafe())
    }

    @Test
    fun `isSafe returns false when both channels disabled`() {
        val prefs = AlertPreferences(voiceEnabled = false, climateEnabled = false)
        assertFalse(prefs.isSafe())
    }

    @Test
    fun `isSafe returns true when only voice enabled`() {
        val prefs = AlertPreferences(voiceEnabled = true, climateEnabled = false)
        assertTrue(prefs.isSafe())
    }

    @Test
    fun `isSafe returns true when only climate enabled`() {
        val prefs = AlertPreferences(voiceEnabled = false, climateEnabled = true)
        assertTrue(prefs.isSafe())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `voiceVolume above 1 throws on construction`() {
        AlertPreferences(voiceVolume = 1.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `voiceVolume below 0 throws on construction`() {
        AlertPreferences(voiceVolume = -0.1f)
    }

    @Test
    fun `voiceVolume at boundary 0 and 1 is valid`() {
        AlertPreferences(voiceVolume = 0f)
        AlertPreferences(voiceVolume = 1f)
    }
}
```

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/IntensityMappingTest.kt
package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IntensityMappingTest {
    @Test
    fun `high maps to fan8 temp20`() {
        assertEquals(8, IntensityMapping.fanSpeedFor(IntensityLevel.HIGH))
        assertEquals(20f, IntensityMapping.temperatureCFor(IntensityLevel.HIGH), 0.001f)
    }

    @Test
    fun `medium maps to fan5 temp22`() {
        assertEquals(5, IntensityMapping.fanSpeedFor(IntensityLevel.MEDIUM))
        assertEquals(22f, IntensityMapping.temperatureCFor(IntensityLevel.MEDIUM), 0.001f)
    }

    @Test
    fun `low maps to fan3 temp23`() {
        assertEquals(3, IntensityMapping.fanSpeedFor(IntensityLevel.LOW))
        assertEquals(23f, IntensityMapping.temperatureCFor(IntensityLevel.LOW), 0.001f)
    }

    @Test
    fun `no intensity level maps to zero fan or temperature`() {
        for (level in IntensityLevel.values()) {
            assertNotEquals(0, IntensityMapping.fanSpeedFor(level))
            assertNotEquals(0f, IntensityMapping.temperatureCFor(level), 0.001f)
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.AlertPreferencesTest" --tests "com.vitalguard.ai.IntensityMappingTest"`
Expected: FAIL — `AlertPreferences`/`IntensityMapping`/`IntensityLevel` do not exist yet.

- [ ] **Step 3: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferences.kt
package com.vitalguard.ai

enum class IntensityLevel { LOW, MEDIUM, HIGH }

/** Fan/temp values for the CRITICAL-response override. Baseline, unvalidated
 * numbers (matches the project's existing convention for such constants) --
 * HIGH is the pre-existing fixed CRITICAL behavior, kept as the default so
 * this feature does not change today's demo experience unless the driver
 * explicitly changes it. Never maps to zero -- there is no OFF intensity. */
object IntensityMapping {
    fun fanSpeedFor(intensity: IntensityLevel): Int = when (intensity) {
        IntensityLevel.HIGH -> 8
        IntensityLevel.MEDIUM -> 5
        IntensityLevel.LOW -> 3
    }

    fun temperatureCFor(intensity: IntensityLevel): Float = when (intensity) {
        IntensityLevel.HIGH -> 20f
        IntensityLevel.MEDIUM -> 22f
        IntensityLevel.LOW -> 23f
    }
}

data class AlertPreferences(
    val voiceEnabled: Boolean = true,
    val voiceVolume: Float = 1.0f,
    val climateEnabled: Boolean = true,
    val climateIntensity: IntensityLevel = IntensityLevel.HIGH,
) {
    init {
        require(voiceVolume in 0f..1f) { "voiceVolume must be in [0,1], got $voiceVolume" }
    }

    /** At least one response channel must be able to fire during CRITICAL --
     * a safety requirement (EU GSR/DDAW), not a UX preference. Enforced by
     * AlertPreferencesStore.save(), not here, so a transient "unsafe" state
     * can exist in-memory while a Settings UI is mid-edit. */
    fun isSafe(): Boolean = voiceEnabled || climateEnabled
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.AlertPreferencesTest" --tests "com.vitalguard.ai.IntensityMappingTest"`
Expected: all 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferences.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertPreferencesTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/IntensityMappingTest.kt
git commit -m "Add AlertPreferences data model and IntensityMapping"
```

---

## Task 2: `AlertPreferencesStore` — interface + `InMemoryAlertPreferencesStore`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferencesStore.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/InMemoryAlertPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `AlertPreferences`, `AlertPreferences.isSafe()` (Task 1).
- Produces: `interface AlertPreferencesStore { fun get(): AlertPreferences; fun save(prefs: AlertPreferences) }`
  and `class InMemoryAlertPreferencesStore(initial: AlertPreferences = AlertPreferences())`.
  Tasks 7/8/9 construct `InMemoryAlertPreferencesStore` directly in tests;
  Task 3 adds the `Prefs`-backed implementation to this same file.

- [ ] **Step 1: Write the failing tests**

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/InMemoryAlertPreferencesStoreTest.kt
package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryAlertPreferencesStoreTest {
    @Test
    fun `defaults to safe default preferences`() {
        val store = InMemoryAlertPreferencesStore()
        assertEquals(AlertPreferences(), store.get())
    }

    @Test
    fun `save then get round trips`() {
        val store = InMemoryAlertPreferencesStore()
        val prefs = AlertPreferences(voiceEnabled = false, climateIntensity = IntensityLevel.LOW)
        store.save(prefs)
        assertEquals(prefs, store.get())
    }

    @Test
    fun `save rejects unsafe preferences and leaves prior value unchanged`() {
        val store = InMemoryAlertPreferencesStore()
        val original = store.get()
        val unsafe = AlertPreferences(voiceEnabled = false, climateEnabled = false)

        try {
            store.save(unsafe)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertEquals(original, store.get())
        assertTrue(store.get().isSafe())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.InMemoryAlertPreferencesStoreTest"`
Expected: FAIL — `AlertPreferencesStore`/`InMemoryAlertPreferencesStore` do not exist yet.

- [ ] **Step 3: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferencesStore.kt
package com.vitalguard.ai

import android.content.Context

interface AlertPreferencesStore {
    fun get(): AlertPreferences
    fun save(prefs: AlertPreferences)
}

class InMemoryAlertPreferencesStore(
    initial: AlertPreferences = AlertPreferences()
) : AlertPreferencesStore {
    @Volatile private var current: AlertPreferences = initial

    override fun get(): AlertPreferences = current

    override fun save(prefs: AlertPreferences) {
        require(prefs.isSafe()) { "Cannot save: both voice and climate channels are disabled" }
        current = prefs
    }
}
```
(`PrefsAlertPreferencesStore` is added to this same file in Task 3 — the
`import android.content.Context` above is unused until then; that's expected
and resolved by the next task, not a mistake to fix now.)

- [ ] **Step 4: Run to verify pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.InMemoryAlertPreferencesStoreTest"`
Expected: all 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferencesStore.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/InMemoryAlertPreferencesStoreTest.kt
git commit -m "Add AlertPreferencesStore interface and InMemoryAlertPreferencesStore"
```

---

## Task 3: `PrefsAlertPreferencesStore` (Real, SharedPreferences-backed)

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferencesStore.kt`

**Interfaces:**
- Consumes: `AlertPreferences`, `AlertPreferencesStore` (Tasks 1–2).
- Produces: `class PrefsAlertPreferencesStore(context: Context) : AlertPreferencesStore`.
  Task 12 constructs this in `VitalGuardMonitorService`.

No unit test for this class — matches the existing, confirmed convention:
`PrefsGatewayModeStore` (the equivalent SharedPreferences-backed store
already in this codebase) has zero unit tests today; only its in-memory
counterpart (`InMemoryGatewayModeStore`) is tested. Verified by reading
`GatewayModeStoreTest.kt` during design — it only exercises
`InMemoryGatewayModeStore`.

- [ ] **Step 1: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferencesStore.kt — append
class PrefsAlertPreferencesStore(private val context: Context) : AlertPreferencesStore {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): AlertPreferences = runCatching {
        AlertPreferences(
            voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true),
            voiceVolume = prefs.getFloat(KEY_VOICE_VOLUME, 1.0f),
            climateEnabled = prefs.getBoolean(KEY_CLIMATE_ENABLED, true),
            climateIntensity = IntensityLevel.valueOf(
                prefs.getString(KEY_CLIMATE_INTENSITY, IntensityLevel.HIGH.name)!!
            ),
        )
    }.getOrDefault(AlertPreferences())

    override fun save(prefs: AlertPreferences) {
        require(prefs.isSafe()) { "Cannot save: both voice and climate channels are disabled" }
        this.prefs.edit()
            .putBoolean(KEY_VOICE_ENABLED, prefs.voiceEnabled)
            .putFloat(KEY_VOICE_VOLUME, prefs.voiceVolume)
            .putBoolean(KEY_CLIMATE_ENABLED, prefs.climateEnabled)
            .putString(KEY_CLIMATE_INTENSITY, prefs.climateIntensity.name)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "vital_guard_alert_preferences"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_CLIMATE_ENABLED = "climate_enabled"
        private const val KEY_CLIMATE_INTENSITY = "climate_intensity"
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd aaos-cockpit-app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (this class has no unit test to run, but must compile).

- [ ] **Step 3: Manual verification on device (do once Task 12 has wired this in)**

```bash
adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode FAKE  # unrelated, just ensure app is in a safe demo state
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Open the app, change a setting (once Task-12's Settings entry point exists —
if it doesn't yet, this step can instead be verified by temporarily calling
`PrefsAlertPreferencesStore(context).save(...)` from a debug button or
`adb shell am start` extra), force-stop and relaunch the app, confirm the
changed value persisted. Record the result — pass/fail — in a short note
appended to `docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md`
under a new "Manual Verification Log" heading, so this doesn't get silently
skipped.

- [ ] **Step 4: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertPreferencesStore.kt
git commit -m "Add PrefsAlertPreferencesStore (SharedPreferences-backed)"
```

---

## Task 4: `ParkedStateTracker`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ParkedStateTracker.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/ParkedStateTrackerTest.kt`

**Interfaces:**
- Produces: `class ParkedStateTracker(enterThresholdKmh: Float = 10f, enterSustainMs: Long = 30_000L, exitThresholdKmh: Float = 15f, exitSustainMs: Long = 2_000L)`
  with `fun update(speedKmh: Float?, nowMs: Long): Boolean?` (`true` = just
  entered parked, `false` = just resumed, `null` = no transition). Task 6
  calls this every poll tick; Tasks 7/8 react to the transition via
  `onParkedStateChanged`.
- Consumes: nothing.

- [ ] **Step 1: Write the failing tests**

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/ParkedStateTrackerTest.kt
package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedStateTrackerTest {
    private fun tracker() = ParkedStateTracker(
        enterThresholdKmh = 10f, enterSustainMs = 30_000L,
        exitThresholdKmh = 15f, exitSustainMs = 2_000L,
    )

    @Test
    fun `below threshold but not yet sustained does not enter parked`() {
        val t = tracker()
        assertNull(t.update(5f, nowMs = 0L))
        assertNull(t.update(5f, nowMs = 29_999L)) // 1ms short of the 30s sustain
    }

    @Test
    fun `brief dip above threshold mid sustain resets belowSince`() {
        val t = tracker()
        assertNull(t.update(5f, nowMs = 0L))
        assertNull(t.update(20f, nowMs = 10_000L)) // red light -- briefly above threshold
        assertNull(t.update(5f, nowMs = 15_000L))  // back below, but the clock restarted
        // total elapsed since the dip is only 30_000 - 15_000 = 15_000ms -- not enough
        assertNull(t.update(5f, nowMs = 30_000L))
    }

    @Test
    fun `sustained below threshold enters parked exactly once`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertEquals(true, t.update(5f, nowMs = 30_000L))
        assertNull(t.update(5f, nowMs = 31_000L)) // already parked -- no repeat transition
    }

    @Test
    fun `speed in dead zone between exit and enter threshold while parked does not exit`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertEquals(true, t.update(5f, nowMs = 30_000L))
        // 12 km/h is above the enter threshold (10) but below the exit threshold (15)
        assertNull(t.update(12f, nowMs = 32_000L))
        assertNull(t.update(12f, nowMs = 40_000L))
    }

    @Test
    fun `null speed while not parked does not enter parked`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertNull(t.update(null, nowMs = 10_000L))
        assertNull(t.update(5f, nowMs = 30_000L)) // belowSince was reset by the null reading
    }

    @Test
    fun `null speed while parked resumes immediately`() {
        val t = tracker()
        t.update(5f, nowMs = 0L)
        assertEquals(true, t.update(5f, nowMs = 30_000L))
        assertEquals(false, t.update(null, nowMs = 30_100L))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.ParkedStateTrackerTest"`
Expected: FAIL — `ParkedStateTracker` does not exist yet.

- [ ] **Step 3: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ParkedStateTracker.kt
package com.vitalguard.ai

/** Hysteresis + sustain over vehicle speed, same shape as trigger_emitter.py's
 * TriggerEmitter. Enter/exit use different threshold VALUES (not just
 * different sustain windows) to avoid flicker right at one boundary speed.
 * Exit sustain is intentionally much shorter than enter sustain -- erring
 * toward resuming the safety response quickly beats erring toward staying
 * suppressed. All 4 defaults are an unvalidated baseline. */
class ParkedStateTracker(
    private val enterThresholdKmh: Float = 10f,
    private val enterSustainMs: Long = 30_000L,
    private val exitThresholdKmh: Float = 15f,
    private val exitSustainMs: Long = 2_000L,
) {
    private var isParked = false
    private var belowSince: Long? = null
    private var aboveSince: Long? = null

    /** true = just entered parked, false = just resumed, null = no transition. */
    fun update(speedKmh: Float?, nowMs: Long): Boolean? {
        if (speedKmh == null) {
            belowSince = null
            if (isParked) {
                isParked = false
                return false // lost the speed signal -- never fabricate "still parked"
            }
            return null
        }
        if (!isParked) {
            if (speedKmh < enterThresholdKmh) {
                if (belowSince == null) belowSince = nowMs
                if (nowMs - belowSince!! >= enterSustainMs) {
                    isParked = true
                    belowSince = null
                    return true
                }
            } else {
                belowSince = null
            }
        } else {
            if (speedKmh > exitThresholdKmh) {
                if (aboveSince == null) aboveSince = nowMs
                if (nowMs - aboveSince!! >= exitSustainMs) {
                    isParked = false
                    aboveSince = null
                    return false
                }
            } else {
                aboveSince = null
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.ParkedStateTrackerTest"`
Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ParkedStateTracker.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/ParkedStateTrackerTest.kt
git commit -m "Add ParkedStateTracker: asymmetric hysteresis over vehicle speed"
```

---

## Task 5: `VehicleContextGateway` — interface + Fake + Real

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VehicleContextGateway.kt`

**Interfaces:**
- Produces: `interface VehicleContextGateway { fun getCurrentSpeedKmh(): Float? }`,
  `class FakeVehicleContextGateway` (mutable `speedKmh: Float?` and
  `throwOnGet: Boolean`, matching the existing `FakeClimateActuatorGateway`/
  `FakeVoiceAlertGateway` throw-flag convention), `class RealVehicleContextGateway(context: Context)`
  with `fun disconnect()`. Task 6's test uses `FakeVehicleContextGateway`;
  Task 12 constructs `RealVehicleContextGateway`.
- Consumes: nothing.

No unit test for `RealVehicleContextGateway` — same convention as
`RealClimateActuatorGateway`/`RealVoiceAlertGateway` (Real classes touching
`Car`/`CarPropertyManager` are verified manually, not unit tested, in this
codebase today).

- [ ] **Step 1: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VehicleContextGateway.kt
package com.vitalguard.ai

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

interface VehicleContextGateway {
    fun getCurrentSpeedKmh(): Float?   // null if the property is unreadable -- never fabricate
}

class FakeVehicleContextGateway : VehicleContextGateway {
    var speedKmh: Float? = 0f
    var throwOnGet: Boolean = false

    override fun getCurrentSpeedKmh(): Float? {
        if (throwOnGet) throw IllegalStateException("simulated vehicle context gateway failure")
        return speedKmh
    }
}

/** Real VHAL implementation. Connects once and keeps the Car reference --
 * polling this every second for the life of the app would otherwise leak a
 * binder connection to Car Service on every tick (see design doc). Call
 * disconnect() from the owning Service's onDestroy(). */
class RealVehicleContextGateway(context: Context) : VehicleContextGateway {
    private val TAG = "VitalGuardVehicleContext"
    private val car: Car = Car.createCar(context)
    private val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

    override fun getCurrentSpeedKmh(): Float? = try {
        carPropertyManager
            .getProperty(Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            .value * 3.6f // VHAL reports m/s
    } catch (t: Throwable) {
        Log.w(TAG, "PERF_VEHICLE_SPEED unreadable: ${t.message}")
        null
    }

    fun disconnect() = car.disconnect()
}
```

- [ ] **Step 2: Compile check**

Run: `cd aaos-cockpit-app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VehicleContextGateway.kt
git commit -m "Add VehicleContextGateway: Fake + Real (PERF_VEHICLE_SPEED)"
```

---

## Task 6: `VehicleContextPollClient`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VehicleContextPollClient.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/VehicleContextPollClientTest.kt`

**Interfaces:**
- Consumes: `VehicleContextGateway` (Task 5), `ParkedStateTracker` (Task 4).
- Produces: `class VehicleContextPollClient(gateway, tracker, scope, onParkedStateChanged: (Boolean) -> Unit, pollIntervalMs: Long = 1000L, nowMsProvider: () -> Long = { System.currentTimeMillis() })`
  with `fun start()` / `fun stop()`. Task 12 constructs and starts/stops this
  in `VitalGuardMonitorService`.

**Addition beyond the design spec, called out explicitly:** the spec's
`VehicleContextPollClient` reads `System.currentTimeMillis()` directly. That
makes the sustain-window math untestable under `runTest`'s virtual clock
(`advanceTimeBy` fast-forwards coroutine delays, not the real wall clock, so
a test can't control how much time `ParkedStateTracker` believes has
passed). Adding an injectable `nowMsProvider` (defaulting to the real clock,
so production behavior is unchanged) is a testability detail, not a design
change — flagging it here rather than silently diverging from the spec.

- [ ] **Step 1: Write the failing test**

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/VehicleContextPollClientTest.kt
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vitalguard.ai

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleContextPollClientTest {
    @Test
    fun `a failing tick does not stop subsequent polling`() = runTest {
        val gateway = FakeVehicleContextGateway()
        gateway.throwOnGet = true
        val tracker = ParkedStateTracker(
            enterThresholdKmh = 10f, enterSustainMs = 20L,
            exitThresholdKmh = 15f, exitSustainMs = 20L,
        )
        val transitions = mutableListOf<Boolean>()
        var fakeNow = 0L

        val client = VehicleContextPollClient(
            gateway = gateway, tracker = tracker, scope = this,
            onParkedStateChanged = { transitions.add(it) },
            pollIntervalMs = 10L,
            nowMsProvider = { fakeNow },
        )
        client.start()

        advanceTimeBy(10); fakeNow += 10   // tick 1: gateway throws -- loop must survive
        gateway.throwOnGet = false
        gateway.speedKmh = 5f
        advanceTimeBy(10); fakeNow += 10   // tick 2: belowSince = 10
        advanceTimeBy(10); fakeNow += 10   // tick 3: 30 - 10 = 20 >= enterSustainMs(20) -> parked

        client.stop()
        assertEquals(listOf(true), transitions)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.VehicleContextPollClientTest"`
Expected: FAIL — `VehicleContextPollClient` does not exist yet.

- [ ] **Step 3: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VehicleContextPollClient.kt
package com.vitalguard.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VehicleContextPollClient(
    private val gateway: VehicleContextGateway,
    private val tracker: ParkedStateTracker,
    private val scope: CoroutineScope,
    private val onParkedStateChanged: (Boolean) -> Unit,
    private val pollIntervalMs: Long = 1000L,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (true) {
                try {
                    tracker.update(gateway.getCurrentSpeedKmh(), nowMsProvider())
                        ?.let { onParkedStateChanged(it) }
                } catch (t: Throwable) {
                    Log.e("VitalGuardVehicleContext", "Poll tick failed: ${t.message}")
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.VehicleContextPollClientTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VehicleContextPollClient.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/VehicleContextPollClientTest.kt
git commit -m "Add VehicleContextPollClient: 1Hz poll loop, resilient to per-tick failures"
```

---

## Task 7: `DrowsinessController` — `isParked` gate + `AlertPreferences` + decoupled channels + priority-flag fix

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt`

**Interfaces:**
- Consumes: `AlertPreferencesStore`/`InMemoryAlertPreferencesStore` (Task 2),
  `AlertPreferences` (Task 1).
- Produces: `DrowsinessController(climateGateway, alertArbiter, alertPreferencesStore)`
  (constructor gains a 3rd parameter) and a new public method
  `fun onParkedStateChanged(parked: Boolean)`. Task 12 constructs this with
  the new signature; Task 9's integration test also depends on it.

This task changes an existing public constructor — every existing call site
must be updated in the same commit or the module will not compile. The only
call sites are this file's own tests (fixed below) and Task 9's
`AlertArbiterIntegrationTest.kt` (fixed in Task 9) and
`VitalGuardMonitorService.kt` (fixed in Task 12). Do not run
`:app:testDebugUnitTest` for the whole module until Task 9 is also done —
`AlertArbiterIntegrationTest.kt` will fail to compile until then. Running
`--tests "com.vitalguard.ai.DrowsinessControllerTest"` alone is safe at the
end of this task.

- [ ] **Step 1: Write the failing tests** (append to the existing file;
  existing 6 tests' bodies are unaffected, only `setUp()` changes)

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt
// Replace the existing setUp() with:
private lateinit var climate: FakeClimateActuatorGateway
private lateinit var voice: FakeVoiceAlertGateway
private lateinit var arbiter: AlertArbiter
private lateinit var preferencesStore: InMemoryAlertPreferencesStore
private lateinit var controller: DrowsinessController

@Before
fun setUp() {
    climate = FakeClimateActuatorGateway()
    voice = FakeVoiceAlertGateway()
    arbiter = AlertArbiter(voice)
    preferencesStore = InMemoryAlertPreferencesStore()
    controller = DrowsinessController(climate, arbiter, preferencesStore)
}

// Then append these new tests:
@Test
fun `does not freeze latch across park then unpark while still critical`() {
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
    assertTrue(climate.overrideApplied)

    controller.onParkedStateChanged(true)
    climate.overrideApplied = false // reset so we can prove the NEXT call is fresh, not stale
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002")) // still critical while parked
    assertFalse(climate.overrideApplied) // suppressed, and crucially: latch was NOT poisoned

    controller.onParkedStateChanged(false)
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003")) // still critical after unparking
    assertTrue(climate.overrideApplied) // must fire again -- this is the bug this test locks in
}

@Test
fun `park while critical active reverts to baseline`() {
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
    assertTrue(climate.overrideApplied)

    controller.onParkedStateChanged(true)

    assertTrue(climate.revertCalled)
    assertTrue(voice.stopCalled)
}

@Test
fun `climate failure does not prevent voice alert from firing`() {
    climate.throwOnApply = true

    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

    assertTrue(voice.alertTriggered)
    assertEquals(DrowsinessController.GatewayActionStatus.OVERRIDE_APPLIED, controller.lastGatewayAction)
}

@Test
fun `voice failure does not prevent climate override from applying`() {
    voice.throwOnTrigger = true

    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

    assertTrue(climate.overrideApplied)
    assertEquals(DrowsinessController.GatewayActionStatus.OVERRIDE_APPLIED, controller.lastGatewayAction)
}

@Test
fun `climateEnabled false skips climate but still fires voice`() {
    preferencesStore.save(AlertPreferences(climateEnabled = false))

    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

    assertFalse(climate.overrideApplied)
    assertTrue(voice.alertTriggered)
}

@Test
fun `voiceEnabled false skips voice but still applies climate`() {
    preferencesStore.save(AlertPreferences(voiceEnabled = false))

    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

    assertTrue(climate.overrideApplied)
    assertFalse(voice.alertTriggered)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DrowsinessControllerTest"`
Expected: FAIL — compile error (constructor signature mismatch) or, once
that's fixed by Step 1's `setUp()` change, the 6 new tests fail against the
old `handleCritical()`/`revertToBaseline()`.

- [ ] **Step 3: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt
package com.vitalguard.ai

import android.util.Log

class DrowsinessController(
    private val climateGateway: ClimateActuatorGateway,
    private val alertArbiter: AlertArbiter,
    private val alertPreferencesStore: AlertPreferencesStore,
) {
    enum class GatewayActionStatus { NONE, OVERRIDE_APPLIED, OVERRIDE_FAILED, REVERTED, REVERT_FAILED }

    private val TAG = "VitalGuardController"

    var lastGatewayAction: GatewayActionStatus = GatewayActionStatus.NONE
        private set

    private var latched = false
    private var lastCorrelationId: String? = null
    private var isParked = false

    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) return
        lastCorrelationId = payload.correlationId

        when (payload.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical()
            else -> handleNonCritical()
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost (3 consecutive poll failures) — reverting to safe baseline")
        revertToBaseline()
    }

    fun onParkedStateChanged(parked: Boolean) {
        isParked = parked
        if (parked && latched) revertToBaseline()
    }

    private fun handleCritical() {
        if (latched) return
        if (isParked) {
            Log.i(TAG, "Suppressed: vehicle parked")
            return // never set latched=true here -- see design doc's latch-freeze bug
        }
        latched = true
        alertArbiter.setDrowsinessCriticalActive(true) // always -- never gate this by voiceEnabled

        val prefs = alertPreferencesStore.get()
        var anySucceeded = false

        if (prefs.climateEnabled) {
            try {
                climateGateway.applyDrowsinessOverride()
                anySucceeded = true
            } catch (t: Throwable) {
                Log.e(TAG, "Climate gateway failure: ${t.message}")
            }
        }
        if (prefs.voiceEnabled) {
            try {
                alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS)
                anySucceeded = true
            } catch (t: Throwable) {
                Log.e(TAG, "Voice gateway failure: ${t.message}")
            }
        }

        lastGatewayAction = if (anySucceeded) GatewayActionStatus.OVERRIDE_APPLIED else GatewayActionStatus.OVERRIDE_FAILED
        DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
    }

    private fun handleNonCritical() {
        if (!latched) return
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        alertArbiter.setDrowsinessCriticalActive(false) // always -- symmetric with above
        alertArbiter.stopAlert(AlertSource.DROWSINESS)   // safe unconditionally -- has its own ownership check
        try {
            climateGateway.revertToBaseline()
            lastGatewayAction = GatewayActionStatus.REVERTED
        } catch (t: Throwable) {
            Log.e(TAG, "Climate gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
        }
        DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DrowsinessControllerTest"`
Expected: all 12 tests PASS (6 pre-existing + 6 new).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt
git commit -m "DrowsinessController: parked-state gate, AlertPreferences, decouple climate/voice"
```

---

## Task 8: `DistractionController` — `isParked` gate + `AlertPreferences`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt`

**Interfaces:**
- Consumes: `AlertPreferencesStore` (Task 2), `AlertPreferences` (Task 1).
- Produces: `DistractionController(alertArbiter, alertPreferencesStore)`
  (constructor gains a 2nd parameter) and `fun onParkedStateChanged(parked: Boolean)`.
  Task 12 and Task 9 both depend on this new signature.

Same compile-break note as Task 7: `AlertArbiterIntegrationTest.kt` also
constructs `DistractionController` with the old 1-argument signature and
will not compile until Task 9. Run only
`--tests "com.vitalguard.ai.DistractionControllerTest"` at the end of this task.

- [ ] **Step 1: Write the failing tests**

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt
// Replace the existing setUp() with:
private lateinit var voice: FakeVoiceAlertGateway
private lateinit var arbiter: AlertArbiter
private lateinit var preferencesStore: InMemoryAlertPreferencesStore
private lateinit var controller: DistractionController

@Before
fun setUp() {
    voice = FakeVoiceAlertGateway()
    arbiter = AlertArbiter(voice)
    preferencesStore = InMemoryAlertPreferencesStore()
    controller = DistractionController(arbiter, preferencesStore)
}

// Then append these new tests:
@Test
fun `does not freeze latch across park then unpark while still critical`() {
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
    assertTrue(voice.distractionReminderTriggered)

    controller.onParkedStateChanged(true)
    voice.distractionReminderTriggered = false
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002"))
    assertFalse(voice.distractionReminderTriggered) // suppressed, latch not poisoned

    controller.onParkedStateChanged(false)
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003"))
    assertTrue(voice.distractionReminderTriggered) // must fire again
}

@Test
fun `park while critical active reverts to baseline`() {
    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
    assertTrue(voice.distractionReminderTriggered)

    controller.onParkedStateChanged(true)

    assertTrue(voice.stopCalled)
}

@Test
fun `voiceEnabled false suppresses distraction reminder`() {
    preferencesStore.save(AlertPreferences(voiceEnabled = false))

    controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

    assertFalse(voice.distractionReminderTriggered)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DistractionControllerTest"`
Expected: FAIL until Step 3 lands.

- [ ] **Step 3: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt
package com.vitalguard.ai

import android.util.Log

class DistractionController(
    private val alertArbiter: AlertArbiter,
    private val alertPreferencesStore: AlertPreferencesStore,
) {
    private val TAG = "VitalGuardDistractionController"

    private var latched = false
    private var lastCorrelationId: String? = null
    private var isParked = false

    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) return
        lastCorrelationId = payload.correlationId

        when (payload.distraction.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical()
            else -> handleNonCritical()
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost -- reverting distraction reminder to baseline")
        revertToBaseline()
    }

    fun onParkedStateChanged(parked: Boolean) {
        isParked = parked
        if (parked && latched) revertToBaseline()
    }

    private fun handleCritical() {
        if (latched) return
        if (isParked) {
            Log.i(TAG, "Suppressed: vehicle parked")
            return
        }
        latched = true
        if (alertPreferencesStore.get().voiceEnabled) {
            try {
                alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION)
            } catch (t: Throwable) {
                Log.e(TAG, "Gateway failure requesting distraction reminder: ${t.message}")
            }
        }
    }

    private fun handleNonCritical() {
        if (!latched) return
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        try {
            alertArbiter.stopAlert(AlertSource.DISTRACTION)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure stopping distraction reminder: ${t.message}")
        }
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DistractionControllerTest"`
Expected: all 9 tests PASS (6 pre-existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt
git commit -m "DistractionController: parked-state gate, AlertPreferences voiceEnabled check"
```

---

## Task 9: `AlertArbiterIntegrationTest` — fix constructor calls + priority-flag regression test

**Files:**
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt`

**Interfaces:**
- Consumes: `DrowsinessController(climate, arbiter, alertPreferencesStore)`
  (Task 7), `DistractionController(arbiter, alertPreferencesStore)` (Task 8).

This is the task that makes the whole module compile again after Tasks 7–8.

- [ ] **Step 1: Write the failing test** (fix `setUp()`, keep the 2 existing
  tests' bodies unchanged, append the new regression test)

```kotlin
// aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt
package com.vitalguard.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlertArbiterIntegrationTest {
    private lateinit var climate: FakeClimateActuatorGateway
    private lateinit var voice: FakeVoiceAlertGateway
    private lateinit var arbiter: AlertArbiter
    private lateinit var preferencesStore: InMemoryAlertPreferencesStore
    private lateinit var drowsinessController: DrowsinessController
    private lateinit var distractionController: DistractionController

    @Before
    fun setUp() {
        climate = FakeClimateActuatorGateway()
        voice = FakeVoiceAlertGateway()
        arbiter = AlertArbiter(voice)
        preferencesStore = InMemoryAlertPreferencesStore()
        drowsinessController = DrowsinessController(climate, arbiter, preferencesStore)
        distractionController = DistractionController(arbiter, preferencesStore)
    }

    private fun drowsinessPayload(state: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f, state = state,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test",
        ),
    )

    @Test
    fun `drowsiness connection-lost while critical clears the arbiter flag so distraction can speak`() {
        drowsinessController.onPayload(drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertTrue(voice.alertTriggered)

        drowsinessController.onConnectionLost()

        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_NORMAL, "vg-9001").copy(
                distraction = DistractionInfo(
                    score = 0.9f, state = TriggerPayload.STATE_CRITICAL, yawDeg = 45.0f, pitchDeg = 5.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                )
            )
        )

        assertTrue(voice.distractionReminderTriggered)
    }

    @Test
    fun `stopAlert cross-source cutoff bug does not regress end-to-end`() {
        drowsinessController.onPayload(drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0002").copy(
                distraction = DistractionInfo(
                    score = 0.9f, state = TriggerPayload.STATE_CRITICAL, yawDeg = 45.0f, pitchDeg = 5.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                )
            )
        )

        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0003").copy(
                distraction = DistractionInfo(
                    score = 0.1f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = true, reason = "test",
                )
            )
        )

        assertFalse(voice.stopCalled)
    }

    @Test
    fun `drowsiness critical with voice disabled still suppresses concurrent distraction`() {
        preferencesStore.save(AlertPreferences(voiceEnabled = false)) // climate-only drowsiness response

        drowsinessController.onPayload(drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        assertTrue(climate.overrideApplied) // drowsiness genuinely active, just silently on the voice side
        assertFalse(voice.alertTriggered)   // voice never fired -- as intended by the preference

        // If setDrowsinessCriticalActive(true) were skipped because voiceEnabled=false
        // (the bug this test locks in), this would incorrectly speak.
        distractionController.onPayload(
            drowsinessPayload(TriggerPayload.STATE_NORMAL, "vg-9001").copy(
                distraction = DistractionInfo(
                    score = 0.9f, state = TriggerPayload.STATE_CRITICAL, yawDeg = 45.0f, pitchDeg = 5.0f,
                    handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                )
            )
        )

        assertFalse(voice.distractionReminderTriggered)
    }
}
```

- [ ] **Step 2: Run to verify failure, then pass**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.AlertArbiterIntegrationTest"`
Expected: first run FAILs to compile (old constructor calls) if Tasks 7–8's
production code already landed without this fix; after Step 1's rewrite,
all 3 tests PASS immediately (the production code was already fixed in
Tasks 7–8 — this task only fixes the test file).

- [ ] **Step 3: Run the full module's unit tests to confirm nothing else broke**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests across the module PASS.

- [ ] **Step 4: Commit**

```bash
git add aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt
git commit -m "Fix AlertArbiterIntegrationTest constructor calls, lock priority-flag fix"
```

---

## Task 10: `RealClimateActuatorGateway` — read `IntensityMapping`, clamp against VHAL config

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt`

**Interfaces:**
- Consumes: `IntensityMapping`, `AlertPreferences` (Task 1),
  `AlertPreferencesStore` (Task 2).
- Produces: `RealClimateActuatorGateway(context: Context, alertPreferencesStore: AlertPreferencesStore)`
  — constructor gains a 2nd parameter. Task 12 constructs this with the new
  signature.

No unit test — same convention as the rest of `RealClimateActuatorGateway`
(untested today; its existing `forEachSupportedArea`/fan-max-clamp logic has
never had a unit test either). Verified manually on-device in Step 3.

- [ ] **Step 1: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt
// Replace RealClimateActuatorGateway's class header and applyDrowsinessOverride() with:
class RealClimateActuatorGateway(
    private val context: Context,
    private val alertPreferencesStore: AlertPreferencesStore,
) : ClimateActuatorGateway {
    private val TAG = "VitalGuardClimate"

    companion object {
        private const val FALLBACK_AREA_ID = 1
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f
    }

    override fun applyDrowsinessOverride() {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val intensity = alertPreferencesStore.get().climateIntensity

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                val targetFan = IntensityMapping.fanSpeedFor(intensity)
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                val clamped = clampFanSpeed(targetFan, config, area)
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, clamped)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                val targetTemp = IntensityMapping.temperatureCFor(intensity)
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
                val clamped = clampTemperature(targetTemp, config, area)
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, clamped)
            }
            Log.d(TAG, "Climate override applied at intensity=$intensity")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply VHAL climate override: ${t.message}")
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun clampFanSpeed(target: Int, config: android.car.hardware.CarPropertyConfig<*>?, area: Int): Int {
        if (config == null) {
            Log.w(TAG, "HVAC_FAN_SPEED clamp skipped — config unavailable, using raw intensity value $target")
            return target
        }
        val min = config.getMinValue(area) as? Int ?: return target
        val max = config.getMaxValue(area) as? Int ?: return target
        val clamped = target.coerceIn(min, max)
        if (clamped != target) Log.w(TAG, "HVAC_FAN_SPEED clamped $target -> $clamped (range [$min,$max])")
        return clamped
    }

    @Suppress("DEPRECATION")
    private fun clampTemperature(target: Float, config: android.car.hardware.CarPropertyConfig<*>?, area: Int): Float {
        if (config == null) {
            Log.w(TAG, "HVAC_TEMPERATURE_SET clamp skipped — config unavailable, using raw intensity value $target")
            return target
        }
        val min = config.getMinValue(area) as? Float ?: return target
        val max = config.getMaxValue(area) as? Float ?: return target
        val clamped = target.coerceIn(min, max)
        if (clamped != target) Log.w(TAG, "HVAC_TEMPERATURE_SET clamped $target -> $clamped (range [$min,$max])")
        return clamped
    }

    override fun revertToBaseline() {
        // unchanged from before -- baseline is fixed, never depends on climateIntensity
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, false)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, BASELINE_FAN_SPEED)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, BASELINE_TEMPERATURE_C)
            }
            Log.d(TAG, "Climate reverted to baseline: AC=OFF, Fan=$BASELINE_FAN_SPEED, Temp=${BASELINE_TEMPERATURE_C}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to revert VHAL climate to baseline: ${t.message}")
            throw t
        }
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
                Log.w(TAG, "Failed to set property 0x${propertyId.toString(16)} for area 0x${area.toString(16)}: ${t.message}")
            }
        }
    }
}
```
Note: `FALLBACK_FAN_SPEED` and `COLD_TEMPERATURE_C` companion constants are
removed — the fan-speed-max behavior they backed is replaced by
`IntensityMapping`, and the clamp fallback is now "use the raw intensity
table value" per the design doc, not a separate hardcoded fallback constant.

- [ ] **Step 2: Compile check**

Run: `cd aaos-cockpit-app && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (This will fail until Task 12 updates the
construction call site in `VitalGuardMonitorService.kt` — if doing tasks
strictly in order, expect this specific compile check to fail here and pass
once Task 12 lands; note that in the commit message rather than blocking.)

- [ ] **Step 3: Manual verification on device (once Task 12 wires this in)**

```bash
adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode REAL
adb logcat -s VitalGuardClimate
```
Trigger a CRITICAL (mock stream or manual broadcast), confirm the log line
shows the intensity used and any clamp warnings, then confirm via
`adb shell dumpsys car_service --list` or the CarSky Signal Watch tool that
`HVAC_FAN_SPEED`/`HVAC_TEMPERATURE_SET` actually landed at the clamped
values. Record pass/fail in the design doc's "Manual Verification Log"
(same log started in Task 3).

- [ ] **Step 4: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt
git commit -m "RealClimateActuatorGateway: read climateIntensity via IntensityMapping, clamp against VHAL config"
```

---

## Task 11: `RealVoiceAlertGateway` / `VoiceEmergencyAssistant` — `voiceVolume`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt`

**Interfaces:**
- Consumes: `AlertPreferencesStore` (Task 2).
- Produces: `RealVoiceAlertGateway(context: Context, alertPreferencesStore: AlertPreferencesStore)`
  (constructor gains a 2nd parameter); `VoiceEmergencyAssistant.executeVoiceIntervention(volume: Float)`
  and `executeDistractionReminder(volume: Float)` (both gain a parameter).
  Task 12 constructs `RealVoiceAlertGateway` with the new signature.

No unit test — same convention as `RealVoiceAlertGateway`/
`VoiceEmergencyAssistant` today (untested; they touch `TextToSpeech`/
`AudioManager` directly). Verified manually on-device.

- [ ] **Step 1: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt
// Modify speakAlert() and add a volume Bundle; executeVoiceIntervention/executeDistractionReminder gain a volume param:
import android.os.Bundle
import android.speech.tts.TextToSpeech

fun executeVoiceIntervention(volume: Float) {
    val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "Audio focus state changed to: $focusChange")
        }
        .build()

    val result = audioManager.requestAudioFocus(focusRequest!!)
    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        Log.w(TAG, "🔇 Audio Focus Obtained! Vehicle Media Muted.")
        speakAlert(volume)
    } else {
        Log.e(TAG, "❌ Audio Focus Request Denied.")
    }
}

private fun speakAlert(volume: Float) {
    val alertText = "Warning! Drowsiness detected! Climate safety mode engaged. Please stay awake. Shall I guide you to the nearest rest stop?"
    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
    tts?.speak(alertText, TextToSpeech.QUEUE_FLUSH, params, "EMERGENCY_ALERT")
    Log.i(TAG, "🗣️ Speaking Alert: '$alertText' at volume=$volume")
}

fun executeDistractionReminder(volume: Float) {
    val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    val reminderFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "Distraction reminder audio focus state changed to: $focusChange")
        }
        .build()

    val result = audioManager.requestAudioFocus(reminderFocusRequest)
    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        focusRequest = reminderFocusRequest
        val reminderText = "Please keep your eyes on the road and both hands on the wheel."
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        tts?.speak(reminderText, TextToSpeech.QUEUE_FLUSH, params, "DISTRACTION_REMINDER")
        Log.i(TAG, "🗣️ Speaking distraction reminder: '$reminderText' at volume=$volume")
    } else {
        Log.e(TAG, "❌ Distraction reminder audio focus request denied.")
    }
}
```

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt
// Replace RealVoiceAlertGateway with:
class RealVoiceAlertGateway(
    context: Context,
    private val alertPreferencesStore: AlertPreferencesStore,
) : VoiceAlertGateway {
    private val assistant = VoiceEmergencyAssistant(context)

    override fun triggerAlert() {
        assistant.executeVoiceIntervention(alertPreferencesStore.get().voiceVolume)
    }

    override fun triggerDistractionReminder() {
        assistant.executeDistractionReminder(alertPreferencesStore.get().voiceVolume)
    }

    override fun stopAlert() {
        assistant.releaseFocus()
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd aaos-cockpit-app && ./gradlew :app:compileDebugKotlin`
Expected: fails until Task 12 updates the construction call site — same note
as Task 10's Step 2.

- [ ] **Step 3: Manual verification on device (once Task 12 wires this in)**

```bash
adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode REAL
adb logcat -s VitalGuardVoice
```
Trigger CRITICAL with `voiceVolume` set to a low value (e.g. 0.2) via a
temporary debug call to `PrefsAlertPreferencesStore(context).save(...)`,
confirm the spoken alert is audibly quieter than at `1.0`. Record pass/fail
in the design doc's "Manual Verification Log".

- [ ] **Step 4: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt
git commit -m "RealVoiceAlertGateway/VoiceEmergencyAssistant: apply voiceVolume via TTS Bundle param"
```

---

## Task 12: Wire everything into `VitalGuardMonitorService`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt`

**Interfaces:**
- Consumes: `PrefsAlertPreferencesStore` (Task 3), `RealVehicleContextGateway`/
  `VehicleContextPollClient` (Tasks 5–6), the new constructor signatures for
  `DrowsinessController`/`DistractionController`/`RealClimateActuatorGateway`/
  `RealVoiceAlertGateway` (Tasks 7/8/10/11).

This task is what makes Tasks 10 and 11's compile checks pass. There is also
a **pre-existing, unrelated compile bug** in this file's current
`onConnectionLost` block (it references `payload` and `controller`, neither
of which exist in that lambda's scope, and a misplaced
`DebugOverlayState.instance.updateFromPayload(payload)` call that belongs in
`onPayload`, not `onConnectionLost`) — fix it in the same pass since this
task rewrites the whole `onCreate()` body anyway.

- [ ] **Step 1: Implement**

```kotlin
// aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt
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
Note on the `DebugOverlayState` fix above: `updateFromPayload(payload)` moved
into `onPayload` (where it belongs — it needs the payload) and
`markConnectionLost()` moved into `onConnectionLost` (it takes no argument) —
this corrects the pre-existing swap/undefined-reference bug noted at the top
of this task, discovered while re-reading this file during design.

- [ ] **Step 2: Run the full module's tests**

Run: `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all unit tests across the module PASS. This is
the point where every prior task's "compile check fails until Task 12"
caveat resolves.

- [ ] **Step 3: Manual on-device smoke test**

```bash
cd aaos-cockpit-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s VitalGuardController VitalGuardVehicleContext VitalGuardClimate VitalGuardVoice
```
With `dms-ai-engine`'s `python main.py --mock` running (per the existing
network-pin runbook), confirm: (1) the app starts without crashing, (2) the
existing mock CRITICAL→RECOVERED cycle still drives the climate/voice
gateways as before, (3) `VitalGuardVehicleContext` logs appear roughly once
per second with no repeated exceptions. Leave running for a few minutes to
confirm no `Car.createCar` leak warnings or slowdown (informal check ahead of
the full 20-minute load test, which stays a separate acceptance-gate task).

- [ ] **Step 4: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt
git commit -m "Wire AlertPreferencesStore and VehicleContextPollClient into VitalGuardMonitorService; fix pre-existing onConnectionLost bug"
```

---

## Task 13 (ops, parallelizable — do not block Tasks 1–12 on this): Bước 0 — verify `PERF_VEHICLE_SPEED` on the real Skycraft VM

**Files:** none — this is a runtime verification step, no repo changes
beyond the log entry in Step 3.

Tasks 1–12 are entirely testable with `FakeVehicleContextGateway` and do not
require VM access — do this task whenever VM access is available, but
complete it **before** trusting `RealVehicleContextGateway`'s output in a
live demo or before Task 5/12's Real path is considered done in practice.

- [ ] **Step 1: Build and install, then query the property**

```bash
cd aaos-cockpit-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode REAL
adb logcat -s VitalGuardVehicleContext
```
Drive the Skycraft VM (or use its simulated-speed control, if the CarSky
blueprint provides one) and confirm `RealVehicleContextGateway` either logs
no warnings (property readable) or logs
`"PERF_VEHICLE_SPEED unreadable: ..."` repeatedly (property not readable).

- [ ] **Step 2a: If readable (Nhánh A)**

No further action — Tasks 1–12's design already assumes this branch. Record
a confirmation line in the design doc's "Manual Verification Log".

- [ ] **Step 2b: If NOT readable (Nhánh B)**

Do not invent a replacement signal source. Escalate to the mentor with the
exact `adb logcat` output attached (not a paraphrase), and ask whether an
alternate speed source is wired in the blueprint (simulated GPS, a different
CAN bus signal). This mirrors the Container Node Provisioning precedent — no
new design work here until the mentor responds; if a different signal source
turns out to be available, that needs a fresh brainstorming round before any
code changes, since `VehicleContextGateway`'s interface may need to change
shape depending on what that source actually looks like.

- [ ] **Step 3: Record the result**

Append the outcome (branch taken, exact log evidence, date) to
`docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md`
under the "Manual Verification Log" heading (same place Tasks 3/10/11 log
their results) — one running log for this feature's real-device evidence,
matching the pattern `dms-ai-engine/CV_REMEDIATION_RESULTS.md` already
established for the CV side.

---

## Self-Review Notes (writing-plans skill checklist, run against the spec)

- **Spec coverage:** every spec section has a task — Vấn đề 1 data
  model/store (Tasks 1–3), intensity mapping (Task 1/10), voice volume (Task
  11), enable/disable-in-controller (Tasks 7–8), UI Settings screen
  explicitly deferred to a separate spec/plan per the user's own choice
  (confirmed before writing this plan — not a silent omission). Vấn đề 2:
  gateway/tracker/poll client (Tasks 4–6), controller hooks (Tasks 7–8),
  Bước 0 (Task 13). Cross-cutting fixes: channel decoupling + priority-flag
  fix (Task 7, locked by Task 9's regression test), binder leak fix (Task 5).
- **Placeholder scan:** no TBD/TODO; every code step is complete, runnable
  code, not a description of what to write.
- **Type consistency:** `DrowsinessController(climateGateway, alertArbiter, alertPreferencesStore)`,
  `DistractionController(alertArbiter, alertPreferencesStore)`,
  `RealClimateActuatorGateway(context, alertPreferencesStore)`,
  `RealVoiceAlertGateway(context, alertPreferencesStore)` are the same
  signatures across every task that constructs them (Tasks 7/8/9/10/11/12).
  `ParkedStateTracker.update(speedKmh: Float?, nowMs: Long): Boolean?` and
  `VehicleContextGateway.getCurrentSpeedKmh(): Float?` match between Tasks
  4/5/6/7/8.
