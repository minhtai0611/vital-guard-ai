# Live Camera Drowsiness Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-time Camera2-based drowsiness detection source (`MediaPipeLiveDetectionSource`) that reuses 100% of the already-tested scoring/trigger/escalation logic, switchable against the existing replay-file path via a persisted `DetectionBackendMode`, after fixing a pre-existing PERCLOS-window timing bug that live camera's irregular frame timing would otherwise make worse.

**Architecture:** Extract the per-frame orchestration currently inline in `MediaPipeReplayDetectionSource` into a shared `DrowsinessDetectionCore`, used identically by the existing replay source and a new Camera2-based live source (bypassing CameraX, which cannot resolve any camera on this AVD's HAL). `DetectionBackendMode` (mirrors the existing `GatewayMode` pattern exactly) selects which one `VitalGuardMonitorService` constructs, read once per process lifetime.

**Tech Stack:** Kotlin, JUnit4, Mockito, `android.hardware.camera2` (Camera2 API, not CameraX), MediaPipe Tasks Vision 1.0.0.

## Global Constraints

- **Branch:** create `feature/live-camera-detection` off the current `feature/drowsiness-kotlin-port` branch (which already has the `com.vitalguard.ai.drowsiness` package and `MediaPipeReplayDetectionSource` this plan modifies) — never commit to `main`.
- **Scope:** live camera drowsiness detection only. Distraction detection stays hardcoded to `NO_DISTRACTION` in every payload, unchanged. No Settings UI (separate plan). No auto-reconnect on camera disconnect.
- **Target:** the `vitalguard_aaos` dev AVD. Code must not preclude real hardware (no AVD-specific branches in the primary code path, only in documented fallback branches), but is only verified against this AVD.
- Every new Kotlin file must carry a kdoc comment naming which prior file/spec it was extracted from or is replacing, matching this project's existing convention.
- Full design rationale and evidence: `docs/superpowers/specs/2026-08-08-live-camera-detection-design.md`.

---

## Task 0 (prerequisite): Fix `DrowsinessScoreCalculator`'s window to evict by real elapsed time

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculator.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineConfig.kt` (kdoc only)
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculatorTest.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineGoldenTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `DrowsinessScoreCalculator(windowSeconds: Double = ..., maxDroopDeg: Double = ...)` — **`sampleHz` constructor parameter removed** (was dead weight: only ever sized a fixed-count eviction window, never actually throttled the input rate). `addFrame`/`computeScore`/`calibrateBaseline`/`baselinePitchDeg` signatures unchanged. Task 1 (`DrowsinessDetectionCore`) and all later tasks construct it with defaults only (`DrowsinessScoreCalculator()`), same as today.

- [ ] **Step 1: Write the two new failing regression tests (append to the existing file)**

Add these two tests to the end of `DrowsinessScoreCalculatorTest.kt`, just before the closing `}` of the class (leave the 5 existing tests untouched for this step):

```kotlin
    // --- Regression tests for docs/superpowers/specs/2026-08-08-live-camera-detection-design.md decision D1 ---

    @Test
    fun `window spans real elapsed time for a native-fps-like feed, not just eyes-closed the whole time`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0)
        var score = 0.0
        // Simulate a native ~30fps feed (MediaPipeReplayDetectionSource's actual
        // behavior): 60 frames spanning ~1.967s (< the 2.0s window), eyes closed
        // and head drooped the whole time -- total span never exceeds
        // windowSeconds, so nothing should ever be evicted.
        for (i in 0 until 60) {
            score = calc.addFrame(FrameFeatures(timestamp = i / 30.0, eyeClosed = true, headPitchDeg = 30.0))
        }
        assertTrue("all-closed native-fps feed must still score high", score > 0.85)
    }

    @Test
    fun `a closure older than 20 samples but within the true 2s window still counts`() {
        // THE regression test: fails under the OLD fixed-count (maxSamples=20)
        // implementation, passes under the time-based fix. Feed at native
        // ~30fps: first 30 frames (~1.0s) eyes CLOSED, next 30 frames (~1.0s)
        // eyes OPEN -- total span ~1.967s, within the true 2.0s window.
        //
        // Old behavior: only the last 20 of these 60 addFrame() calls survive
        // eviction (fixed count), all from the eyes-OPEN tail -> perclos reads
        // ~0.0, completely missing the earlier 1s closure. score = 0.0.
        //
        // New (time-based) behavior: nothing gets evicted (total span < 2.0s),
        // so perclos = 30 closed / 60 total = 0.5 exactly.
        // score = 0.55*0.5 + 0.25*0(eyeClosedNow=false) + 0.20*0 = 0.275.
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0)
        var score = 0.0
        for (i in 0 until 30) {
            score = calc.addFrame(FrameFeatures(timestamp = i / 30.0, eyeClosed = true, headPitchDeg = 0.0))
        }
        for (i in 30 until 60) {
            score = calc.addFrame(FrameFeatures(timestamp = i / 30.0, eyeClosed = false, headPitchDeg = 0.0))
        }
        assertEquals("a real 1s closure within the true 2s window must still be visible in PERCLOS",
            0.275, score, 0.01)
    }
```

- [ ] **Step 2: Run to verify both new tests fail for the right reason**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessScoreCalculatorTest"
```
Expected: the 5 pre-existing tests PASS unchanged; the 2 new tests FAIL — the first with `score` around `0.55` (droop capped at exactly the boundary is fine, but well under `0.85` because... actually verify it prints an actual value, don't guess) and the second with an actual score around `0.0` instead of the expected `0.275`. If a test errors instead of asserting a wrong value, or fails for an unrelated reason (e.g. a typo), fix the test first — do not proceed until both fail specifically on the `assertEquals`/`assertTrue` score comparison.

- [ ] **Step 3: Fix `DrowsinessScoreCalculator.kt` — time-based eviction, drop `sampleHz`**

Replace the entire file with:

```kotlin
package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/score_calculator.py. Composite
 * Drowsiness Score in [0,1] from a sliding window of frame features. Not
 * scientifically validated as the specific "correct" weights -- see root
 * CLAUDE.md's disclosed-limitations section; only the general PERCLOS-based
 * fusion concept and the 0.85 threshold have real grounding.
 *
 * score = 0.55 * perclosWindow + 0.25 * eyeClosedNow + 0.20 * headDroopNorm
 *
 * The sliding window evicts by REAL ELAPSED TIME (frames older than
 * [windowSeconds] relative to the newest frame's timestamp), not by a fixed
 * sample count. The original version of this class (and the Python
 * reference it was ported from) evicted by count, sized as
 * `windowSeconds * sampleHz` -- silently assuming callers feed frames at
 * exactly `sampleHz`. Both this class's caller
 * (`MediaPipeReplayDetectionSource`, via `DrowsinessDetectionCore`) and the
 * Python reference actually feed frames at native video fps (~30fps for
 * drowsy.mp4), not the assumed 10Hz, so the count-based window was silently
 * only ~0.67s of real time instead of the intended 2.0s -- see
 * docs/superpowers/specs/2026-08-08-live-camera-detection-design.md
 * decision D1. Time-based eviction is correct regardless of the caller's
 * frame rate, which matters even more now that live camera (irregular
 * frame timing: autofocus, thermal throttling, backpressure drops) is a
 * caller too.
 */
data class FrameFeatures(
    val timestamp: Double,
    val eyeClosed: Boolean,
    val headPitchDeg: Double,
)

class DrowsinessScoreCalculator(
    private val windowSeconds: Double = DrowsinessPipelineConfig.WINDOW_SECONDS,
    private val maxDroopDeg: Double = DrowsinessPipelineConfig.MAX_DROOP_DEG,
) {
    private val window = ArrayDeque<FrameFeatures>()

    var baselinePitchDeg: Double = 0.0
        private set

    /** Call during the first few seconds while the driver sits upright, to
     * subtract seat/camera mount tilt from head-droop measurements. */
    fun calibrateBaseline(pitchDeg: Double) {
        baselinePitchDeg = pitchDeg
    }

    fun addFrame(frame: FrameFeatures): Double {
        window.addLast(frame)
        val newestTimestamp = frame.timestamp
        while (window.isNotEmpty() && newestTimestamp - window.first().timestamp > windowSeconds) {
            window.removeFirst()
        }
        return computeScore()
    }

    fun computeScore(): Double {
        if (window.isEmpty()) return 0.0
        val perclos = window.count { it.eyeClosed }.toDouble() / window.size
        val eyeClosedNow = if (window.last().eyeClosed) 1.0 else 0.0
        val headDroopNorm = normalizedHeadDroop()
        val score = 0.55 * perclos + 0.25 * eyeClosedNow + 0.20 * headDroopNorm
        return score.coerceIn(0.0, 1.0)
    }

    private fun normalizedHeadDroop(): Double {
        val latestPitch = window.last().headPitchDeg - baselinePitchDeg
        return (latestPitch / maxDroopDeg).coerceIn(0.0, 1.0)
    }
}
```

- [ ] **Step 4: Update the 6 existing call sites that pass the now-removed `sampleHz` argument**

In `DrowsinessScoreCalculatorTest.kt`, there are 5 occurrences of:
```kotlin
DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
```
Replace every one with:
```kotlin
DrowsinessScoreCalculator(windowSeconds = 2.0)
```

In `DrowsinessPipelineGoldenTest.kt`, line with:
```kotlin
val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
```
Replace with:
```kotlin
val calc = DrowsinessScoreCalculator(windowSeconds = 2.0)
```
(This test's scenario is built at exactly 0.1s/10Hz steps, matching the old assumed rate exactly — its checkpoint values are expected to be unaffected by this fix. This step exists only to keep the file compiling, not to change its behavior.)

- [ ] **Step 5: Add a clarifying kdoc note to `DrowsinessPipelineConfig.kt`**

Find:
```kotlin
    // DrowsinessScoreCalculator window
    const val WINDOW_SECONDS = 2.0
    const val SAMPLE_HZ = 10.0
    const val MAX_DROOP_DEG = 25.0
```
Replace with:
```kotlin
    // DrowsinessScoreCalculator window
    const val WINDOW_SECONDS = 2.0
    // No longer consumed by DrowsinessScoreCalculator (its window now evicts
    // by real elapsed time, not a sample count -- see
    // docs/superpowers/specs/2026-08-08-live-camera-detection-design.md
    // decision D1). Kept as a historical/reference value: it still matches
    // dms-ai-engine/main.py's own DrowsinessScoreCalculator(sample_hz=10.0)
    // construction argument, which has the same now-unused-for-eviction
    // status on the Python side.
    const val SAMPLE_HZ = 10.0
    const val MAX_DROOP_DEG = 25.0
```

- [ ] **Step 6: Run to verify everything passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.*"
```
Expected: PASS, including the 2 new tests, all 5 pre-existing `DrowsinessScoreCalculatorTest` cases, and `DrowsinessPipelineGoldenTest`'s checkpoints unchanged.

- [ ] **Step 7: Run the full existing drowsiness-package + overlay-state suite to confirm no wider regression**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.*" --tests "com.vitalguard.ai.DebugOverlayStateTest"
```
Expected: PASS (this doesn't touch anything new, just confirms the fix didn't ripple anywhere unexpected).

- [ ] **Step 8: On-device re-run to capture the new real checkpoint numbers against `drowsy.mp4`**

The JVM tests above are expected to stay green because their fixtures already use 10Hz-consistent synthetic timestamps. The REAL divergence only shows up against `drowsy.mp4` at native ~30fps. Re-run the exact on-device procedure from the prior plan's Task 11 (build → install → push `drowsy.mp4` → run → `adb logcat -d -s MediaPipeReplayDetection:D` → screenshot) and record what you actually observe at the same checkpoints (t≈1.20s, t≈2.17s peak, t≈3.30s). Do not assume the old numbers (0.880 / 0.998 / 0.110) still hold — they were computed against the old, shorter (~0.67s) window and are very likely to shift now that the window is a true 2.0s. Confirm only that: (a) the app doesn't crash, (b) the drowsy segment of the video still reaches `CRITICAL` at some point, (c) the score returns to `NORMAL` after the driver's eyes reopen at the end. Write the new observed numbers into a comment or short note alongside this task in your own working notes — they become the new baseline for Task 1's regression check below.

- [ ] **Step 9: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculator.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineConfig.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculatorTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineGoldenTest.kt
git commit -m "Fix DrowsinessScoreCalculator: evict PERCLOS window by real elapsed time

Cross-language latent bug (present in both the Python reference and the
Kotlin port, not introduced by this change): the window evicted by a fixed
sample count sized for a 10Hz feed, but both the Python reference and
MediaPipeReplayDetectionSource feed frames at native video fps (~30fps) --
the window was silently ~0.67s of real time, not the intended 2.0s.

Live camera's irregular frame timing (autofocus, thermal throttling,
backpressure drops) would make this worse in an unpredictable way, so
fixing it now as a prerequisite to introducing that caller. Removed the
now-functionally-dead sampleHz constructor parameter.

See docs/superpowers/specs/2026-08-08-live-camera-detection-design.md
decision D1.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 1: Extract `DrowsinessDetectionCore` from `MediaPipeReplayDetectionSource`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/DrowsinessDetectionCore.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/detection/mediapipe/DrowsinessDetectionCoreTest.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MediaPipeReplayDetectionSource.kt`

**Interfaces:**
- Consumes: `DrowsinessScoreCalculator`/`FrameFeatures` (Task 0's fixed version), `BlinkStateTracker`, `HeadPose`, `TriggerEmitter`/`FacePresenceSignal`/`FacePresenceTracker`, `EscalationTracker`, `DrowsinessPipelineConfig` (all unchanged from the prior plan), `TriggerPayload`/`TriggerFeatures`/`DistractionInfo`.
- Produces: `class DrowsinessDetectionCore(onPayload: (TriggerPayload) -> Unit, onTelemetry: (TriggerPayload) -> Unit = {})` with `@Synchronized fun handleResult(result: FaceLandmarkerResult)` and `@Synchronized fun forceUnknown(reason: String)`. Task 2's `MediaPipeReplayDetectionSource` (modified) and Task 4's `MediaPipeLiveDetectionSource` (new) both construct and delegate to their own instance of this class.

- [ ] **Step 1: Write the failing test for the one genuinely new piece of behavior (`forceUnknown`)**

This is the only part of `DrowsinessDetectionCore` that doesn't need a `FaceLandmarkerResult` to exercise (everything else in this extraction is a pure move of already-tested-via-`MediaPipeReplayDetectionSource`'s-on-device-verification logic — see this task's Step 5 note on why the rest isn't independently unit tested). `forceUnknown` is new in this task (needed by Task 4's camera-loss handling) and is plain, MediaPipe-free logic, so it gets a real test:

```kotlin
package com.vitalguard.ai.detection.mediapipe

import com.vitalguard.ai.TriggerPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class DrowsinessDetectionCoreTest {

    @Test
    fun `forceUnknown emits an UNKNOWN payload to both onTelemetry and onPayload`() {
        val telemetryPayloads = mutableListOf<TriggerPayload>()
        val payloads = mutableListOf<TriggerPayload>()
        val core = DrowsinessDetectionCore(
            onPayload = { payloads.add(it) },
            onTelemetry = { telemetryPayloads.add(it) },
        )

        core.forceUnknown(reason = "camera_lost")

        assertEquals(1, payloads.size)
        assertEquals(TriggerPayload.STATE_UNKNOWN, payloads[0].state)
        assertEquals("camera_lost", payloads[0].reason)
        assertEquals(1, telemetryPayloads.size)
        assertEquals(TriggerPayload.STATE_UNKNOWN, telemetryPayloads[0].state)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.detection.mediapipe.DrowsinessDetectionCoreTest"
```
Expected: FAIL — `unresolved reference: DrowsinessDetectionCore`.

- [ ] **Step 3: Create `DrowsinessDetectionCore.kt`**

```kotlin
package com.vitalguard.ai.detection.mediapipe

import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.vitalguard.ai.DistractionInfo
import com.vitalguard.ai.TriggerFeatures
import com.vitalguard.ai.TriggerPayload
import com.vitalguard.ai.drowsiness.BlinkStateTracker
import com.vitalguard.ai.drowsiness.DrowsinessPipelineConfig
import com.vitalguard.ai.drowsiness.DrowsinessScoreCalculator
import com.vitalguard.ai.drowsiness.EscalationTracker
import com.vitalguard.ai.drowsiness.FacePresenceSignal
import com.vitalguard.ai.drowsiness.FacePresenceTracker
import com.vitalguard.ai.drowsiness.FrameFeatures
import com.vitalguard.ai.drowsiness.HeadPose
import com.vitalguard.ai.drowsiness.TriggerEmitter
import com.vitalguard.ai.drowsiness.TriggerSignal
import com.vitalguard.ai.drowsiness.blinkScore
import java.util.concurrent.atomic.AtomicLong

/**
 * Extracted from MediaPipeReplayDetectionSource's handleResultUnsafe()/
 * buildPayload() (see
 * docs/superpowers/specs/2026-08-08-live-camera-detection-design.md,
 * decision D2) so both it and the new MediaPipeLiveDetectionSource share
 * the exact same PERCLOS/sustain/escalation orchestration -- one source of
 * truth for the scoring/trigger/escalation math, regardless of where
 * frames come from. Pure move: no behavior change from what
 * MediaPipeReplayDetectionSource did before this extraction (verified via
 * on-device re-run, see this task's own verification step).
 *
 * Each frame source (replay or live camera) owns its own
 * DrowsinessDetectionCore instance -- state is never shared between modes
 * (DetectionBackendMode is read once per process lifetime, so only one
 * instance of either source, hence only one DrowsinessDetectionCore, is
 * ever active at a time -- decision D6).
 */
class DrowsinessDetectionCore(
    private val onPayload: (TriggerPayload) -> Unit,
    // Fires on EVERY processed frame, independent of the publish gate below
    // -- root CLAUDE.md's mandatory Debug Overlay section requires showing
    // perclos/eyeOpenProbability/headEulerAngleX/state continuously ("never
    // tune blind"), which the gated onPayload alone cannot satisfy.
    private val onTelemetry: (TriggerPayload) -> Unit = {},
) {
    private val correlationCounter = AtomicLong(0)

    private val blinkTracker = BlinkStateTracker()
    private val calc = DrowsinessScoreCalculator()
    private val triggerEmitter = TriggerEmitter()
    private val faceTracker = FacePresenceTracker()
    private val escalation = EscalationTracker(
        levelUpSeconds = DrowsinessPipelineConfig.LEVEL_UP_SECONDS,
        repeatIntervalSeconds = DrowsinessPipelineConfig.REPEAT_INTERVAL_SECONDS,
    )

    private val calibrationPitchSamples = mutableListOf<Double>()
    private var baselineCalibrated = false

    // Empirically confirmed on-device (prior plan's Task 9) against
    // dms-ai-engine/out/evidence_drowsy_fresh_build.csv's known-correct pitch
    // trajectory: FaceLandmarkerResult.facialTransformationMatrixes()'s flat
    // float[16] is laid out opposite to the row-major convention
    // HeadPose.kt's math assumes (matrix[row*4+col]), so it must be
    // transposed before being passed in. Identical for every
    // FaceLandmarkerClient instance regardless of frame source (replay or
    // live camera) -- this is a MediaPipe/Android characteristic, not
    // something specific to how frames were acquired.
    private fun transpose4x4(m: FloatArray): FloatArray {
        val t = FloatArray(16)
        for (row in 0 until 4) for (col in 0 until 4) t[col * 4 + row] = m[row * 4 + col]
        return t
    }

    // LIVE_STREAM delivers results from MediaPipe's own internal thread pool
    // -- observed on-device coming from several distinct worker threads
    // concurrently, so all state mutation below must be serialized or the
    // sustain/escalation counters get corrupted by unsynchronized
    // read-modify-write. This guard lives here (not in the frame sources)
    // because this class is the sole owner of the mutable trackers both
    // frame sources call into -- decision D3. Wrapped in catch(Throwable)
    // per this module's "Catch Throwable" rule -- one bad frame (malformed
    // matrix, unexpected index) must not silently kill the calling thread.
    @Synchronized
    fun handleResult(result: FaceLandmarkerResult) {
        try {
            handleResultUnsafe(result)
        } catch (t: Throwable) {
            Log.e(TAG, "handleResult failed for this frame -- continuing", t)
        }
    }

    /** Forces an immediate UNKNOWN payload, bypassing FacePresenceTracker's
     * sustain window -- for callers that know detection has stopped
     * entirely (e.g. the camera disconnected), not just lost a face
     * momentarily. See MediaPipeLiveDetectionSource's camera-loss handling
     * (design doc decision D7). Synchronized for the same reason as
     * handleResult -- both mutate escalation/trigger state. */
    @Synchronized
    fun forceUnknown(reason: String) {
        escalation.reset()
        val payload = buildPayload(
            state = TriggerPayload.STATE_UNKNOWN,
            score = 0.0, perclos = 0.0, eyeOpenProbability = 0.0, headEulerAngleX = 0.0,
            escalationLevel = 1, reason = reason,
        )
        onTelemetry(payload)
        onPayload(payload)
    }

    private fun handleResultUnsafe(result: FaceLandmarkerResult) {
        val now = result.timestampMs() / 1_000.0
        val hasFace = result.faceBlendshapes().map { it.isNotEmpty() }.orElse(false)

        val faceSignal = faceTracker.update(hasFace, now)
        if (faceSignal == FacePresenceSignal.Unknown) {
            escalation.reset()
            val payload = buildPayload(
                state = TriggerPayload.STATE_UNKNOWN,
                score = 0.0, perclos = 0.0, eyeOpenProbability = 0.0, headEulerAngleX = 0.0,
                escalationLevel = 1, reason = "lost_face",
            )
            onTelemetry(payload)
            onPayload(payload)
            return
        }
        if (!hasFace) {
            // Still within FacePresenceTracker's grace window -- no trigger
            // payload (unchanged behavior), but still surface a live "no face
            // this frame" telemetry sample so the overlay doesn't freeze on
            // stale data while the grace window runs out. Does not touch
            // escalation/trigger state.
            onTelemetry(
                buildPayload(
                    state = TriggerPayload.STATE_UNKNOWN,
                    score = 0.0, perclos = calc.computeScore(), eyeOpenProbability = 0.0, headEulerAngleX = 0.0,
                    escalationLevel = 1, reason = "no_face_this_frame",
                )
            )
            return
        }

        val blendshapes = result.faceBlendshapes().get().first().associate { it.categoryName() to it.score() }
        val blink = blinkScore(blendshapes)
        val eyeClosed = blinkTracker.update(blink, now)

        val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
        val pitchDeg = if (matrix != null) HeadPose.extractPitchDeg(transpose4x4(matrix)) else 0.0

        if (!baselineCalibrated) {
            if (now < DrowsinessPipelineConfig.BASELINE_CALIBRATION_SECONDS) {
                calibrationPitchSamples.add(pitchDeg)
            } else {
                if (calibrationPitchSamples.isNotEmpty()) {
                    calc.calibrateBaseline(calibrationPitchSamples.average())
                }
                baselineCalibrated = true
            }
        }

        val score = calc.addFrame(FrameFeatures(now, eyeClosed, pitchDeg))
        val signal = triggerEmitter.update(score, now)
        val state = stateForScore(score)
        val (level, repeatDue, levelChanged) = escalation.update(triggerEmitter.criticalActive, now)

        val payload = buildPayload(
            state = state, score = score, perclos = calc.computeScore(),
            eyeOpenProbability = 1.0 - blink, headEulerAngleX = pitchDeg,
            escalationLevel = level,
            reason = when (signal) {
                TriggerSignal.Critical -> "sustained_high_score"
                TriggerSignal.Recovered -> "recovered"
                null -> "unchanged"
            },
        )
        // Every processed frame gets a telemetry sample (overlay); onPayload
        // keeps the original publish gate unchanged (controllers only react
        // to trigger-worthy edges).
        onTelemetry(payload)
        if (signal != null || repeatDue || levelChanged) {
            onPayload(payload)
        }
    }

    private fun stateForScore(score: Double): String = when {
        score >= DrowsinessPipelineConfig.ENTER_THRESHOLD -> TriggerPayload.STATE_CRITICAL
        score > DrowsinessPipelineConfig.EXIT_THRESHOLD -> TriggerPayload.STATE_WARNING
        else -> TriggerPayload.STATE_NORMAL
    }

    // Pure construction only -- callers decide who gets notified (onTelemetry
    // always, onPayload only past the publish gate).
    private fun buildPayload(
        state: String, score: Double, perclos: Double, eyeOpenProbability: Double,
        headEulerAngleX: Double, escalationLevel: Int, reason: String,
    ): TriggerPayload = TriggerPayload(
        timestampMs = System.currentTimeMillis(),
        source = "on-device-kotlin",
        score = score.toFloat(),
        confidence = 1f,
        state = state,
        escalationLevel = escalationLevel,
        features = TriggerFeatures(
            perclos = perclos.toFloat(),
            eyeOpenProbability = eyeOpenProbability.toFloat(),
            headEulerAngleX = headEulerAngleX.toFloat(),
        ),
        reason = reason,
        correlationId = "vg-ondevice-${correlationCounter.incrementAndGet()}",
        distraction = NO_DISTRACTION,
    )

    companion object {
        private const val TAG = "DrowsinessDetectionCore"

        private val NO_DISTRACTION = DistractionInfo(
            score = 0f,
            state = TriggerPayload.STATE_NORMAL,
            escalationLevel = 0,
            yawDeg = 0f,
            pitchDeg = 0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN,
            handsOnWheel = true,
            reason = "",
        )
    }
}
```

- [ ] **Step 4: Run to verify the new test passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.detection.mediapipe.DrowsinessDetectionCoreTest"
```
Expected: PASS.

- [ ] **Step 5: Rewrite `MediaPipeReplayDetectionSource.kt` to delegate to the core**

Replace the entire file with:

```kotlin
package com.vitalguard.ai.detection.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.vitalguard.ai.TriggerPayload
import com.vitalguard.ai.drowsiness.DrowsinessPipelineConfig
import java.io.File

/**
 * Local-dev-only replacement for the Container Node -> HTTP TriggerPollClient
 * path that used to run alongside this (root CLAUDE.md's original "Trigger
 * Delivery" decision -- retired by
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md, which
 * this class now fully implements). Decodes a device-local MP4 via
 * MediaMetadataRetriever at the video's own native frame rate, runs each
 * frame through [FaceLandmarkerClient], and delegates the resulting
 * FaceLandmarkerResult to a [DrowsinessDetectionCore] instance -- the same
 * scoring/trigger/escalation orchestration [MediaPipeLiveDetectionSource]
 * uses for its live-camera frames (see
 * docs/superpowers/specs/2026-08-08-live-camera-detection-design.md,
 * decision D2). Owns only frame ACQUISITION (decode loop, fed/received
 * frame counting) -- all scoring/trigger/escalation state lives in the
 * core.
 */
class MediaPipeReplayDetectionSource(
    context: Context,
    onPayload: (TriggerPayload) -> Unit,
    private val onFrameDecoded: (Bitmap) -> Unit = {},
    onTelemetry: (TriggerPayload) -> Unit = {},
) {
    private val core = DrowsinessDetectionCore(onPayload = onPayload, onTelemetry = onTelemetry)

    private val client = FaceLandmarkerClient(
        context = context,
        onResult = { result -> receivedCount++; core.handleResult(result) },
        onError = { e -> Log.e(TAG, "FaceLandmarker error", e) },
    )

    // Counts callbacks actually RECEIVED from MediaPipe's LIVE_STREAM mode,
    // as opposed to sampledCount in runIfPresent which only counts frames SENT
    // into detectAsync(). LIVE_STREAM drops inputs when its internal graph is
    // busy, so fed != received is the signal that frames were silently
    // dropped. Only ever incremented from FaceLandmarkerClient's onResult
    // callback above (MediaPipe serializes its own result-listener callbacks
    // per client instance), so no separate lock is needed here.
    private var receivedCount = 0

    fun runIfPresent(videoFile: File) {
        if (!videoFile.exists()) {
            Log.d(TAG, "No replay file at ${videoFile.absolutePath}, skipping")
            return
        }
        Thread {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val endUs = durationMs * 1_000L

                // Native fps, matching dms-ai-engine/main.py's own fallback
                // semantics (fps=30.0 if missing/non-finite/non-positive).
                // METADATA_KEY_CAPTURE_FRAMERATE is officially "if available"
                // -- usually absent on normally-recorded clips, so this
                // commonly falls back to 30.0 in practice. Accepted,
                // documented trade-off, not a new gap.
                val reportedFps = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()
                val fps = if (reportedFps != null && reportedFps.isFinite() && reportedFps > 0.0) {
                    reportedFps
                } else {
                    DrowsinessPipelineConfig.FALLBACK_FPS
                }
                val sampleIntervalUs = (1_000_000.0 / fps).toLong()

                Log.d(TAG, "Replay detection: duration=${durationMs}ms, sampling at ${fps}fps")
                var sampledCount = 0
                var tUs = 0L
                while (tUs < endUs) {
                    // OPTION_CLOSEST (not OPTION_CLOSEST_SYNC): sparse-keyframe
                    // replay files (e.g. drowsy.mp4, 1 keyframe / 100 frames)
                    // make OPTION_CLOSEST_SYNC snap to the same nearby sync
                    // frame on every seek, silently pinning pitch/score near-
                    // constant for the whole run. OPTION_CLOSEST decodes
                    // forward from the nearest keyframe to the exact requested
                    // timestamp. Slower per-seek on long clips, but this is a
                    // dev/test-only replay path, not the real-time on-device
                    // pipeline -- accepted trade-off.
                    val bitmap = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        val argbBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                            bitmap
                        } else {
                            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
                        }
                        onFrameDecoded(argbBitmap)
                        client.detectAsync(argbBitmap, tUs / 1_000.0)
                        sampledCount++
                    } else {
                        Log.w(TAG, "Replay detection: no frame decoded at t=${tUs / 1_000}ms")
                    }
                    tUs += sampleIntervalUs
                }
                Log.d(TAG, "Replay detection: finished, fed $sampledCount frames, received $receivedCount callbacks")
            } catch (e: Exception) {
                Log.e(TAG, "Replay detection failed", e)
            } finally {
                retriever.release()
            }
        }.start()
    }

    fun close() = client.close()

    companion object {
        private const val TAG = "MediaPipeReplayDetection"
    }
}
```

- [ ] **Step 6: Run the full unit test suite to confirm no regression**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.*" --tests "com.vitalguard.ai.detection.mediapipe.*" --tests "com.vitalguard.ai.DebugOverlayStateTest"
```
Expected: PASS. None of these tests exercise `MediaPipeReplayDetectionSource` directly (it was never JVM-testable — depends on `MediaMetadataRetriever`/`FaceLandmarkerResult`), so this only confirms the pure classes it composes are untouched.

- [ ] **Step 7: On-device re-run to confirm zero behavior change from the extraction**

Repeat the exact on-device procedure from Task 0's Step 8 (build → install → push `drowsy.mp4` → run → `adb logcat -d -s MediaPipeReplayDetection:D` → screenshot). This task is a pure refactor, so the checkpoint numbers you observe here MUST match what you recorded at the end of Task 0 exactly (same code path, just moved to a different class). If they differ at all, do not proceed to Task 2 — the extraction introduced a behavior change and must be fixed first.

- [ ] **Step 8: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/DrowsinessDetectionCore.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/detection/mediapipe/DrowsinessDetectionCoreTest.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MediaPipeReplayDetectionSource.kt
git commit -m "Extract DrowsinessDetectionCore from MediaPipeReplayDetectionSource

Pure move of the scoring/trigger/escalation orchestration (previously
inline in handleResultUnsafe()/buildPayload()) into its own class, so the
upcoming MediaPipeLiveDetectionSource can share the exact same logic
instead of duplicating it. Zero behavior change, confirmed via matching
on-device checkpoint numbers before/after.

Also adds forceUnknown(reason), a new method needed by the live camera
source's camera-loss handling (not used yet, wired up in a later task) --
genuinely unit-testable since it doesn't touch FaceLandmarkerResult.

See docs/superpowers/specs/2026-08-08-live-camera-detection-design.md
decisions D2 and D7.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: `DetectionBackendMode` + `PrefsDetectionBackendModeStore` + `DetectionBackendModeReceiver`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DetectionBackendMode.kt`
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DetectionBackendModeReceiver.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DetectionBackendModeStoreTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `enum class DetectionBackendMode { REPLAY_FILE, LIVE_CAMERA }`; `interface DetectionBackendModeStore { fun get(): DetectionBackendMode; fun set(mode: DetectionBackendMode) }` with `InMemoryDetectionBackendModeStore`/`PrefsDetectionBackendModeStore` implementations (default `REPLAY_FILE`); `class DetectionBackendModeReceiver : BroadcastReceiver()` with `companion object { const val ACTION_SET_DETECTION_BACKEND_MODE; const val EXTRA_MODE }`. Task 5 consumes `PrefsDetectionBackendModeStore` and `DetectionBackendModeReceiver`.

- [ ] **Step 1: Write the failing store test**

```kotlin
package com.vitalguard.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionBackendModeStoreTest {
    @Test
    fun `defaults to REPLAY_FILE when nothing has been set`() {
        val store = InMemoryDetectionBackendModeStore()
        assertEquals(DetectionBackendMode.REPLAY_FILE, store.get())
    }

    @Test
    fun `set then get round-trips`() {
        val store = InMemoryDetectionBackendModeStore()
        store.set(DetectionBackendMode.LIVE_CAMERA)
        assertEquals(DetectionBackendMode.LIVE_CAMERA, store.get())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.DetectionBackendModeStoreTest"
```
Expected: FAIL — `unresolved reference: DetectionBackendMode`.

- [ ] **Step 3: Create `DetectionBackendMode.kt`**

```kotlin
package com.vitalguard.ai

import android.content.Context

/**
 * Selects which detection source drives the drowsiness pipeline:
 * REPLAY_FILE (MediaPipeReplayDetectionSource, decodes a bundled/pushed MP4
 * -- the default, safe, already-verified path and the demo-stage fallback
 * per root CLAUDE.md's Demo Script contingency) or LIVE_CAMERA
 * (MediaPipeLiveDetectionSource, Camera2). Mirrors GatewayMode/
 * GatewayModeStore exactly -- see
 * docs/superpowers/specs/2026-08-08-live-camera-detection-design.md,
 * decision D6: read once at VitalGuardMonitorService.onCreate(), switching
 * takes effect on the next service (re)start, not hot-swapped mid-session.
 */
enum class DetectionBackendMode { REPLAY_FILE, LIVE_CAMERA }

interface DetectionBackendModeStore {
    fun get(): DetectionBackendMode
    fun set(mode: DetectionBackendMode)
}

class InMemoryDetectionBackendModeStore(
    initial: DetectionBackendMode = DetectionBackendMode.REPLAY_FILE
) : DetectionBackendModeStore {
    @Volatile private var current: DetectionBackendMode = initial
    override fun get(): DetectionBackendMode = current
    override fun set(mode: DetectionBackendMode) {
        current = mode
    }
}

/** Production store: persists across process restarts via SharedPreferences,
 * so a live LIVE_CAMERA demo survives an app/service restart. */
class PrefsDetectionBackendModeStore(private val context: Context) : DetectionBackendModeStore {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(): DetectionBackendMode {
        val raw = prefs.getString(KEY_MODE, DetectionBackendMode.REPLAY_FILE.name)
            ?: DetectionBackendMode.REPLAY_FILE.name
        return runCatching { DetectionBackendMode.valueOf(raw) }.getOrDefault(DetectionBackendMode.REPLAY_FILE)
    }

    override fun set(mode: DetectionBackendMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "vital_guard_detection_backend_mode"
        private const val KEY_MODE = "mode"
    }
}
```

- [ ] **Step 4: Create `DetectionBackendModeReceiver.kt`**

```kotlin
package com.vitalguard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DetectionBackendModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_DETECTION_BACKEND_MODE) return
        val requested = intent.getStringExtra(EXTRA_MODE) ?: return
        val mode = runCatching { DetectionBackendMode.valueOf(requested) }.getOrNull() ?: run {
            Log.w("VitalGuardDetectionBackendMode", "Ignoring invalid DETECTION_BACKEND_MODE value: $requested")
            return
        }
        PrefsDetectionBackendModeStore(context).set(mode)
        Log.w("VitalGuardDetectionBackendMode", "DETECTION_BACKEND_MODE switched to $mode")
    }

    companion object {
        const val ACTION_SET_DETECTION_BACKEND_MODE = "com.vitalguard.ai.SET_DETECTION_BACKEND_MODE"
        const val EXTRA_MODE = "mode"
    }
}
```

- [ ] **Step 5: Run to verify the test passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.DetectionBackendModeStoreTest"
```
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DetectionBackendMode.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DetectionBackendModeReceiver.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DetectionBackendModeStoreTest.kt
git commit -m "Add DetectionBackendMode: switchable LIVE_CAMERA/REPLAY_FILE selector

Mirrors GatewayMode/GatewayModeStore/GatewayModeReceiver exactly. Not
wired into VitalGuardMonitorService yet -- that's a later task, once
MediaPipeLiveDetectionSource exists to switch to.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: `CameraFacingResolver` — testable camera ID selection

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/camera2/CameraFacingResolver.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/detection/camera2/CameraFacingResolverTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object CameraFacingResolver` with `fun resolveFrontCameraId(cameraManager: CameraManager): String`. Task 4 consumes this.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.vitalguard.ai.detection.camera2

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class CameraFacingResolverTest {

    private fun characteristicsWithFacing(facing: Int?): CameraCharacteristics {
        val characteristics = mock(CameraCharacteristics::class.java)
        `when`(characteristics.get(CameraCharacteristics.LENS_FACING)).thenReturn(facing)
        return characteristics
    }

    @Test
    fun `resolves the camera id that reports LENS_FACING_FRONT`() {
        val manager = mock(CameraManager::class.java)
        `when`(manager.cameraIdList).thenReturn(arrayOf("0", "1"))
        `when`(manager.getCameraCharacteristics("0"))
            .thenReturn(characteristicsWithFacing(CameraCharacteristics.LENS_FACING_BACK))
        `when`(manager.getCameraCharacteristics("1"))
            .thenReturn(characteristicsWithFacing(CameraCharacteristics.LENS_FACING_FRONT))

        assertEquals("1", CameraFacingResolver.resolveFrontCameraId(manager))
    }

    @Test
    fun `falls back to the emulator convention id when no camera reports FRONT`() {
        // Reproduces vitalguard_aaos's confirmed HAL behavior: LENS_FACING
        // reads null for every camera id (confirmed via CameraX's
        // CameraValidator log -- see the design doc's §1.1).
        val manager = mock(CameraManager::class.java)
        `when`(manager.cameraIdList).thenReturn(arrayOf("0", "1"))
        `when`(manager.getCameraCharacteristics("0")).thenReturn(characteristicsWithFacing(null))
        `when`(manager.getCameraCharacteristics("1")).thenReturn(characteristicsWithFacing(null))

        assertEquals("1", CameraFacingResolver.resolveFrontCameraId(manager))
    }

    @Test
    fun `falls back to the first available id when neither FRONT nor the emulator convention id exist`() {
        val manager = mock(CameraManager::class.java)
        `when`(manager.cameraIdList).thenReturn(arrayOf("7"))
        `when`(manager.getCameraCharacteristics("7")).thenReturn(characteristicsWithFacing(null))

        assertEquals("7", CameraFacingResolver.resolveFrontCameraId(manager))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.detection.camera2.CameraFacingResolverTest"
```
Expected: FAIL — `unresolved reference: CameraFacingResolver`.

- [ ] **Step 3: Create `CameraFacingResolver.kt`**

```kotlin
package com.vitalguard.ai.detection.camera2

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Resolves which camera ID to open for driver-facing (front) detection.
 * Pulled out as pure, testable logic separate from actual Camera2 I/O --
 * see docs/superpowers/specs/2026-08-08-live-camera-detection-design.md,
 * decision D4.
 *
 * `vitalguard_aaos`'s camera HAL was confirmed (via CameraX's
 * CameraValidator log: "lensFacingInteger: null") to not populate
 * LENS_FACING for CameraX's reader of it; whether Camera2's own
 * CameraCharacteristics.get(LENS_FACING) is also null on this HAL was not
 * empirically confirmed at design time -- this resolver handles both
 * cases: it prefers a real LENS_FACING=FRONT match, and only falls back to
 * the AOSP emulator ID convention (front="1") if no camera characteristics
 * report FRONT. On real hardware where LENS_FACING reads normally, the
 * primary branch resolves and the fallback never triggers.
 */
object CameraFacingResolver {
    private const val TAG = "CameraFacingResolver"
    private const val EMULATOR_FRONT_CAMERA_ID = "1"

    fun resolveFrontCameraId(cameraManager: CameraManager): String {
        val ids = cameraManager.cameraIdList
        val frontId = ids.firstOrNull { id ->
            val facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_FRONT
        }
        if (frontId != null) return frontId

        if (ids.contains(EMULATOR_FRONT_CAMERA_ID)) {
            Log.w(TAG, "No camera reported LENS_FACING=FRONT; falling back to the AOSP emulator " +
                "convention (camera id=$EMULATOR_FRONT_CAMERA_ID)")
            return EMULATOR_FRONT_CAMERA_ID
        }

        // Caller must have already confirmed at least one camera exists
        // (e.g. a non-empty cameraIdList check) before calling this -- an
        // empty list means there is nothing left to fall back to, and
        // `.first()` throwing NoSuchElementException is the correct signal
        // for that caller bug, not something to swallow here.
        val firstId = ids.first()
        Log.w(TAG, "No LENS_FACING=FRONT match and no emulator-convention id=" +
            "$EMULATOR_FRONT_CAMERA_ID available; using first camera id=$firstId as a low-confidence guess")
        return firstId
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.detection.camera2.CameraFacingResolverTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/camera2/CameraFacingResolver.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/detection/camera2/CameraFacingResolverTest.kt
git commit -m "Add CameraFacingResolver: testable front-camera ID selection with emulator fallback

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: `MediaPipeLiveDetectionSource` — Camera2 capture, backpressure, wired to `DrowsinessDetectionCore`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MediaPipeLiveDetectionSource.kt`

**Interfaces:**
- Consumes: `DrowsinessDetectionCore` (Task 1, including `forceUnknown`), `CameraFacingResolver.resolveFrontCameraId` (Task 3), `FaceLandmarkerClient` (existing, unchanged).
- Produces: `class MediaPipeLiveDetectionSource(context: Context, onPayload: (TriggerPayload) -> Unit, onFrameDecoded: (Bitmap) -> Unit = {}, onTelemetry: (TriggerPayload) -> Unit = {})` with `fun start()` and `fun close()`. Task 5 consumes this (same shape as `MediaPipeReplayDetectionSource`, minus the file argument).

Not independently unit-tested: same established precedent as `MediaPipeReplayDetectionSource` and `FaceLandmarkerClient` -- this class has a hard dependency on `android.hardware.camera2`/`FaceLandmarkerResult` (native, Android-runtime-only). Verified via on-device confirmation only, in Task 6.

- [ ] **Step 1: Create `MediaPipeLiveDetectionSource.kt`**

```kotlin
package com.vitalguard.ai.detection.mediapipe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.vitalguard.ai.TriggerPayload
import com.vitalguard.ai.detection.camera2.CameraFacingResolver
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time drowsiness detection from a live Camera2 feed -- see
 * docs/superpowers/specs/2026-08-08-live-camera-detection-design.md.
 * Deliberately bypasses CameraX: CameraX's CameraValidator reads
 * CameraCharacteristics.LENS_FACING as null on vitalguard_aaos's camera HAL
 * and treats every camera as unusable regardless of CameraSelector (root
 * `IllegalArgumentException: Provided camera selector unable to resolve a
 * camera`, confirmed on-device 2026-08-08) -- Camera2 does not require this
 * field to open a camera by ID.
 *
 * Owns all Camera2-specific threading (a dedicated HandlerThread) and the
 * backpressure decision (decision D3): at most one frame is ever in flight
 * to FaceLandmarkerClient.detectAsync at a time -- a new camera frame that
 * arrives while the previous one is still being processed is dropped
 * (closed) immediately, never queued, so the camera callback thread is
 * never blocked waiting on MediaPipe. Delegates all scoring/trigger/
 * escalation orchestration to its own DrowsinessDetectionCore instance --
 * the same class MediaPipeReplayDetectionSource uses (decision D2).
 */
class MediaPipeLiveDetectionSource(
    private val context: Context,
    onPayload: (TriggerPayload) -> Unit,
    private val onFrameDecoded: (Bitmap) -> Unit = {},
    onTelemetry: (TriggerPayload) -> Unit = {},
) {
    private val core = DrowsinessDetectionCore(onPayload = onPayload, onTelemetry = onTelemetry)

    private val client = FaceLandmarkerClient(
        context = context,
        onResult = { result -> busy.set(false); core.handleResult(result) },
        onError = { e -> busy.set(false); Log.e(TAG, "FaceLandmarker error", e) },
    )

    private val backgroundThread = HandlerThread("MediaPipeLiveDetection").also { it.start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    // Backpressure (decision D3): true while a frame is in flight to
    // detectAsync() and hasn't returned yet. The ImageReader callback checks
    // this before submitting a new frame -- if busy, the new frame is closed
    // immediately instead of queued, so the camera pipeline is never
    // stalled waiting on MediaPipe inference.
    private val busy = AtomicBoolean(false)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    /** Opens the front (driver-facing) camera and starts feeding frames into
     * detection. No-ops (logs + returns) if CAMERA permission isn't granted
     * -- VitalGuardMonitorService can start before MainActivity has ever run
     * to request it (see the design doc's error-handling table), so this is
     * a required defensive check here, not just in the Activity. */
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CAMERA permission not granted -- skipping live camera detection")
            return
        }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = CameraFacingResolver.resolveFrontCameraId(cameraManager)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Camera $cameraId has no stream configuration map")
            val size = chooseSize(configMap)
            Log.d(TAG, "Opening camera id=$cameraId at size=$size")

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ onImageAvailable(it) }, backgroundHandler)
            imageReader = reader

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startCaptureSession(device, reader)
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "Camera disconnected mid-session")
                    handleCameraLoss(device)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error, code=$error")
                    handleCameraLoss(device)
                }
            }, backgroundHandler)
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception -- this module's
            // established rule for CameraX/Car APIs applies equally to
            // Camera2 here.
            Log.e(TAG, "Failed to start live camera detection -- continuing without it", t)
        }
    }

    private fun chooseSize(configMap: StreamConfigurationMap): Size {
        val sizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)
        // Smallest size at or above a practical minimum for MediaPipe Face
        // Landmarker, queried at runtime rather than hard-coded (root
        // CLAUDE.md's "verify, don't assume" convention) -- falls back to
        // the smallest available size if nothing meets the minimum.
        return sizes.filter { it.width >= MIN_WIDTH }.minByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: throw IllegalStateException("Camera reported no output sizes for YUV_420_888")
    }

    private fun startCaptureSession(device: CameraDevice, reader: ImageReader) {
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        requestBuilder.addTarget(reader.surface)
        device.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                }
            },
            backgroundHandler,
        )
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        if (!busy.compareAndSet(false, true)) {
            // Previous frame still in flight -- drop this one immediately,
            // never queue (decision D3).
            image.close()
            return
        }
        try {
            val bitmap = yuv420888ToBitmap(image)
            val timestampMs = image.timestamp / 1_000_000.0
            onFrameDecoded(bitmap)
            client.detectAsync(bitmap, timestampMs)
        } catch (t: Throwable) {
            busy.set(false)
            Log.e(TAG, "Failed to process a live camera frame -- continuing", t)
        } finally {
            image.close()
        }
    }

    // YUV_420_888 -> NV21 -> JPEG -> Bitmap. Simpler and far less error-prone
    // than a hand-rolled pixel-format conversion; costs one JPEG encode/
    // decode round-trip of latency/quality -- an accepted MVP trade-off
    // (decision D5), first place to optimize if live-camera latency becomes
    // a problem.
    private fun yuv420888ToBitmap(image: Image): Bitmap {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)

        yPlane.buffer.get(nv21, 0, ySize)
        vPlane.buffer.get(nv21, ySize, vSize)
        uPlane.buffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun handleCameraLoss(device: CameraDevice) {
        // No auto-reconnect (design doc's non-goals, decision D7) -- feed
        // the core's face-loss/UNKNOWN path once so the FSM doesn't stay
        // frozen on a stale non-UNKNOWN state, then tear everything down.
        device.close()
        cameraDevice = null
        captureSession = null
        core.forceUnknown(reason = "camera_lost")
        close()
    }

    fun close() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        client.close()
        backgroundThread.quitSafely()
    }

    companion object {
        private const val TAG = "MediaPipeLiveDetection"
        private const val MIN_WIDTH = 480
    }
}
```

- [ ] **Step 2: Build to confirm it compiles**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat compileDebugKotlin -q
```
Expected: no errors. This class has no unit test (see this task's Interfaces note) — on-device verification happens in Task 6, after Task 5 wires it in.

- [ ] **Step 3: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MediaPipeLiveDetectionSource.kt
git commit -m "Add MediaPipeLiveDetectionSource: Camera2-based real-time drowsiness detection

Bypasses CameraX (confirmed incompatible with vitalguard_aaos's camera
HAL -- CameraValidator reads LENS_FACING as null and rejects every
camera). Uses Camera2 directly: CameraManager/CameraDevice/ImageReader on
a dedicated HandlerThread, single-frame-in-flight backpressure (drop, never
queue), YUV_420_888->NV21->JPEG->Bitmap conversion. Delegates all scoring/
trigger/escalation to the same DrowsinessDetectionCore
MediaPipeReplayDetectionSource uses.

Not yet wired into VitalGuardMonitorService -- next task.

See docs/superpowers/specs/2026-08-08-live-camera-detection-design.md
decisions D3, D4, D5, D7.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: Wire `DetectionBackendMode` into `VitalGuardMonitorService`; remove `MainActivity`'s dead CameraX code

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/MainActivity.kt`
- Modify: `aaos-cockpit-app/app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `DetectionBackendMode`/`PrefsDetectionBackendModeStore`/`DetectionBackendModeReceiver` (Task 2), `MediaPipeLiveDetectionSource` (Task 4), `MediaPipeReplayDetectionSource` (Task 1, unchanged signature).
- Produces: nothing new for later tasks -- this is the final wiring point for this plan.

- [ ] **Step 1: Rewrite `VitalGuardMonitorService.kt`**

Replace the entire file with:

```kotlin
package com.vitalguard.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vitalguard.ai.detection.mediapipe.MediaPipeLiveDetectionSource
import com.vitalguard.ai.detection.mediapipe.MediaPipeReplayDetectionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Foreground service hosting the on-device trigger pipeline:
 * MediaPipeReplayDetectionSource or MediaPipeLiveDetectionSource (selected
 * by DetectionBackendMode, read once here) -> DrowsinessController/
 * DistractionController -> Climate/Voice gateways. Also keeps
 * [ClimateOverrideReceiver] registered as the dormant manual on-stage
 * fallback -- unrelated to the automated path below. Being a foreground
 * service (rather than a receiver tied to [MainActivity]) is what keeps
 * this alive regardless of whether the Activity is on screen -- a dynamic
 * receiver tied only to the Activity silently stopped firing once the app
 * left the foreground (confirmed on-device 2026-07-24).
 *
 * As of the alert-preferences-parked-suppression feature, this also owns a
 * [VehicleContextPollClient] (1Hz vehicle-speed poll -> [ParkedStateTracker] ->
 * fan-out to both controllers' `onParkedStateChanged`) and constructs a single
 * shared [PrefsAlertPreferencesStore] passed to every gateway/controller that
 * needs it.
 *
 * As of docs/superpowers/specs/2026-08-08-live-camera-detection-design.md,
 * DetectionBackendMode selects between the replay-file and live-camera
 * sources -- exactly one is ever constructed per process lifetime (decision
 * D6), so exactly one FaceLandmarkerClient (native model) is ever loaded at
 * a time.
 *
 * Dynamically registers [GatewayModeReceiver] and [DetectionBackendModeReceiver]
 * here (confirmed on-device 2026-08-05 that a manifest-declared receiver for
 * these implicit actions never fires -- same "Background execution not
 * allowed" failure mode as [ClimateOverrideReceiver]'s TRIGGER_ALERT), so
 * `adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE` / `...SET_DETECTION_BACKEND_MODE`
 * only reach the app while this service is already running.
 */
class VitalGuardMonitorService : Service() {

    private val voiceAssistant by lazy { VoiceEmergencyAssistant(this) }
    private val climateOverrideReceiver by lazy { ClimateOverrideReceiver(voiceAssistant) }
    private val gatewayModeReceiver by lazy { GatewayModeReceiver() }
    private val detectionBackendModeReceiver by lazy { DetectionBackendModeReceiver() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vehicleContextPollClient: VehicleContextPollClient
    private var realVehicleContextGateway: RealVehicleContextGateway? = null
    private var replayDetectionSource: MediaPipeReplayDetectionSource? = null
    private var liveDetectionSource: MediaPipeLiveDetectionSource? = null

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

        val detectionBackendModeFilter = IntentFilter(DetectionBackendModeReceiver.ACTION_SET_DETECTION_BACKEND_MODE)
        ContextCompat.registerReceiver(
            this, detectionBackendModeReceiver, detectionBackendModeFilter, ContextCompat.RECEIVER_EXPORTED
        )

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

        val onDetectionPayload: (TriggerPayload) -> Unit = { payload ->
            drowsinessController.onPayload(payload)
            distractionController.onPayload(payload)
        }
        val onDetectionTelemetry: (TriggerPayload) -> Unit = { payload ->
            DebugOverlayState.instance.updateFromPayload(payload)
        }
        val onDetectionFrame: (Bitmap) -> Unit = { bitmap -> DebugOverlayState.instance.updateFrame(bitmap) }

        // Exactly one branch runs per process lifetime (DetectionBackendMode
        // is read once here, decision D6) -- guarded + caught (Throwable, not
        // just RuntimeException -- see this module's CLAUDE.md "Catch
        // Throwable" rule) exactly like the pre-existing MediaPipe init-crash
        // guard below, so a detection-source failure never takes down the
        // whole service.
        when (PrefsDetectionBackendModeStore(this).get()) {
            DetectionBackendMode.REPLAY_FILE -> {
                val replayFile = File("/data/local/tmp", REPLAY_FILE_NAME)
                if (replayFile.exists()) {
                    replayDetectionSource = try {
                        MediaPipeReplayDetectionSource(
                            context = this,
                            onPayload = onDetectionPayload,
                            onFrameDecoded = onDetectionFrame,
                            onTelemetry = onDetectionTelemetry,
                        ).also { it.runIfPresent(replayFile) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "MediaPipe replay detection init failed -- continuing without it", t)
                        null
                    }
                } else {
                    Log.d(TAG, "No replay file at ${replayFile.absolutePath}, skipping on-device detection")
                }
            }
            DetectionBackendMode.LIVE_CAMERA -> {
                liveDetectionSource = try {
                    MediaPipeLiveDetectionSource(
                        context = this,
                        onPayload = onDetectionPayload,
                        onFrameDecoded = onDetectionFrame,
                        onTelemetry = onDetectionTelemetry,
                    ).also { it.start() }
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaPipe live detection init failed -- continuing without it", t)
                    null
                }
            }
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
        vehicleContextPollClient.stop()
        realVehicleContextGateway?.disconnect()
        replayDetectionSource?.close()
        liveDetectionSource?.close()
        serviceScope.cancel()
        unregisterReceiver(climateOverrideReceiver)
        unregisterReceiver(gatewayModeReceiver)
        unregisterReceiver(detectionBackendModeReceiver)
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

        // Must match the filename MediaPipeReplayDetectionSource's caller (this
        // service) looks for at /data/local/tmp -- see aaos-cockpit-app/docs/
        // EMULATOR_TESTING_GUIDE.md Section 6.5 for how it gets pushed there.
        private const val REPLAY_FILE_NAME = "replay_test.mp4"
    }
}
```

- [ ] **Step 2: Rewrite `MainActivity.kt` — remove the dead CameraX code**

Replace the entire file with:

```kotlin
package com.vitalguard.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Cockpit entry point. The actual TRIGGER_ALERT handling AND the on-device
 * MediaPipe detection pipeline (both replay-file and live-camera) live in
 * [VitalGuardMonitorService] (started here and by [BootCompletedReceiver])
 * so both keep working whether or not this Activity is on screen -- see
 * [VitalGuardMonitorService]'s kdoc and root CLAUDE.md's "State Machine
 * Hardening" section. This Activity is UI only: it renders the mandatory
 * debug overlay (perclos/eyeOpenProbability/headEulerAngleX/driver state/
 * receivingTrigger/lastGatewayAction, plus the most recently decoded
 * on-device frame from whichever detection source is active) by collecting
 * [DebugOverlayState.instance] live.
 *
 * Requests CAMERA permission on launch so it's already granted by the time
 * a driver (or `adb shell am broadcast ...SET_DETECTION_BACKEND_MODE`)
 * switches to DetectionBackendMode.LIVE_CAMERA -- the actual camera open
 * happens in VitalGuardMonitorService's MediaPipeLiveDetectionSource
 * (Camera2), not here. This Activity previously also bound a CameraX
 * PreviewView as a placeholder for a future live-camera mode; removed as of
 * docs/superpowers/specs/2026-08-08-live-camera-detection-design.md,
 * decision D8 -- that future mode arrived via Camera2 instead (CameraX
 * could not bind any camera on this AVD's HAL, confirmed on-device), and
 * frame visibility is served by the existing onFrameDecoded ->
 * DebugOverlayState.lastFrame -> replayFramePreview mechanism, shared by
 * both detection sources.
 */
class MainActivity : AppCompatActivity() {

    private var replayFramePreview: ImageView? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Log.w(TAG, "Camera permission denied -- LIVE_CAMERA detection mode will stay unavailable")
        }
    }

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
        replayFramePreview = findViewById(R.id.replayFramePreview)

        lifecycleScope.launch {
            DebugOverlayState.instance.flow.collect { snapshot ->
                statusView.text = snapshot.driverState
                perclosView.text = "PERCLOS: %.3f".format(snapshot.perclos)
                eyeOpenView.text = "Eye open prob: %.3f".format(snapshot.eyeOpenProbability)
                headPitchView.text = "Head pitch: %.1f°".format(snapshot.headEulerAngleX)
                receivingView.text = "Receiving trigger: ${snapshot.receivingTrigger}"
                gatewayActionView.text = "Last gateway action: ${snapshot.lastGatewayAction}"

                val frame = snapshot.lastFrame
                if (frame != null) {
                    replayFramePreview?.visibility = View.VISIBLE
                    replayFramePreview?.setImageBitmap(frame)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
```

- [ ] **Step 3: Remove the dead `cameraPreview` `PreviewView` from the layout**

In `aaos-cockpit-app/app/src/main/res/layout/activity_main.xml`, find:
```xml
    <!-- On-device MediaPipe FaceLandmarker/HandLandmarker camera feed. Kept in the layout
         so CameraX still has a surface to bind to once its front-camera-selector bug on
         this AVD is fixed, but hidden (visibility="gone") rather than shown as a dead
         black box while that bind always fails. See aaos-cockpit-app/MEMORY.md. Only
         populated in DetectionBackendMode.LIVE_CAMERA; stays hidden in REPLAY_FILE mode. -->
    <androidx.camera.view.PreviewView
        android:id="@+id/cameraPreview"
        android:layout_width="320dp"
        android:layout_height="240dp"
        android:layout_marginBottom="16dp"
        android:visibility="gone" />

    <!-- ReplayFileFrameSource spike only (MainActivity.runReplayFileSpikeIfPresent):
         shows the actual decoded video frame being fed into FaceLandmarker. -->
    <ImageView
        android:id="@+id/replayFramePreview"
        android:layout_width="320dp"
        android:layout_height="240dp"
        android:layout_marginBottom="16dp"
        android:scaleType="fitCenter"
        android:visibility="gone" />
```
Replace with:
```xml
    <!-- Shows the most recently decoded frame from whichever on-device detection
         source is active (MediaPipeReplayDetectionSource or MediaPipeLiveDetectionSource)
         -- see DebugOverlayState.lastFrame / MainActivity's onFrameDecoded wiring. The
         separate CameraX PreviewView that used to sit here was removed once live camera
         arrived via Camera2 instead (design doc decision D8) -- this single ImageView now
         covers both detection backend modes. -->
    <ImageView
        android:id="@+id/replayFramePreview"
        android:layout_width="320dp"
        android:layout_height="240dp"
        android:layout_marginBottom="16dp"
        android:scaleType="fitCenter"
        android:visibility="gone" />
```

- [ ] **Step 4: Build to confirm everything compiles and remove now-unused CameraX Gradle dependencies if any are exclusively used by the deleted code**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat compileDebugKotlin -q
```
Expected: no errors. (Leave any `androidx.camera:camera-*` Gradle dependencies in place even if now unused by app code -- removing build config is out of scope for this plan and not worth the risk of breaking something else that might reference them; flag it as a follow-up cleanup if you notice it, don't act on it here.)

- [ ] **Step 5: Run the full unit test suite**

```bash
./gradlew.bat testDebugUnitTest -q
```
Expected: PASS, except the one already-known pre-existing flaky failure in `VehicleContextPollClientTest` (confirmed unrelated to this plan's changes, fails identically on a clean checkout of this branch before any of this plan's tasks).

- [ ] **Step 6: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/MainActivity.kt \
        aaos-cockpit-app/app/src/main/res/layout/activity_main.xml
git commit -m "Wire DetectionBackendMode into VitalGuardMonitorService; remove dead CameraX code

VitalGuardMonitorService now reads PrefsDetectionBackendModeStore once at
onCreate() and constructs exactly one of MediaPipeReplayDetectionSource
(default) or MediaPipeLiveDetectionSource. Both wire into the same
onPayload/onFrameDecoded/onTelemetry callbacks as before.

MainActivity's CameraX Preview/PreviewView/ImageAnalysis/bindToLifecycle
code is removed -- it was scaffolding for exactly this feature, arriving
instead via Camera2 in the Service (CameraX cannot bind any camera on this
AVD's HAL). CAMERA permission request stays in the Activity. Frame
visibility is served by the pre-existing onFrameDecoded ->
DebugOverlayState.lastFrame -> replayFramePreview mechanism, now shared by
both detection sources -- the layout's separate cameraPreview PreviewView
is removed as dead weight.

See docs/superpowers/specs/2026-08-08-live-camera-detection-design.md
decisions D6 and D8.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: On-device verification

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Reconfigure the AVD for a real face via webcam passthrough**

```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/Admin/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" emu kill
sleep 5
```
Edit `/c/Users/Admin/.android/avd/vitalguard_aaos.avd/config.ini`: change `hw.camera.front=emulated` to `hw.camera.front=webcam0` (the AVD's own emulated image shows a synthetic static picture, not a real face -- MediaPipe Face Landmarker needs an actual face to produce meaningful blendshapes; `emulator.exe -webcam-list` must show a `webcam0` entry for this to work, confirmed present on this dev machine as "ACER HD User Facing").

- [ ] **Step 2: Cold-boot the AVD**

```bash
EMULATOR="/c/Users/Admin/AppData/Local/Android/Sdk/emulator/emulator.exe"
nohup "$EMULATOR" -avd vitalguard_aaos -no-snapshot > /tmp/emulator.log 2>&1 &
"$ADB" wait-for-device
for i in $(seq 1 40); do
  status=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  if [ "$status" = "1" ]; then echo "BOOTED"; break; fi
  sleep 5
done
```

- [ ] **Step 3: Build, install, and switch to `LIVE_CAMERA` mode**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat assembleRelease -q
"$ADB" install -r "app/build/outputs/apk/release/app-release.apk"
"$ADB" shell am broadcast -a com.vitalguard.ai.SET_DETECTION_BACKEND_MODE --es mode LIVE_CAMERA
```
Note: the broadcast only reaches the app while `VitalGuardMonitorService` is already running (dynamic-registration-only receiver, see its kdoc) -- launch the app once first if this is a fresh install, THEN send the broadcast, THEN force-stop + relaunch so the new mode takes effect on the next `onCreate()` (decision D6: not hot-swapped).

```bash
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity
sleep 3
"$ADB" shell am broadcast -a com.vitalguard.ai.SET_DETECTION_BACKEND_MODE --es mode LIVE_CAMERA
"$ADB" shell am force-stop com.vitalguard.ai
"$ADB" logcat -c
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity
```

- [ ] **Step 4: Verify live detection on a real face**

```bash
sleep 5
"$ADB" logcat -d -s MediaPipeLiveDetection:V
"$ADB" exec-out screencap -p > aaos-cockpit-app/screenshot-live-camera.png
```
Open the PNG and confirm the on-screen debug overlay shows a real decoded webcam frame (your own face, not a static AVD placeholder image) with plausible non-zero `Eye open prob`/`Head pitch` values that respond as you look at the camera (open/close your eyes, tilt your head) -- take a second screenshot after doing so and confirm the overlay's numbers actually changed. `Receiving trigger` will mostly read `false` between publish-worthy edges, same documented behavior as the replay path (publish gate) -- `PERCLOS`/`Eye open prob`/`Head pitch` update every frame regardless, since `onTelemetry` is unconditional.

- [ ] **Step 5: Confirm no crash**

```bash
"$ADB" logcat -d | grep -iE "FATAL|AndroidRuntime.*Exception" | grep -i vitalguard
```
Expected: no output.

- [ ] **Step 6: Confirm the replay-file fallback still works**

```bash
"$ADB" shell am broadcast -a com.vitalguard.ai.SET_DETECTION_BACKEND_MODE --es mode REPLAY_FILE
"$ADB" shell am force-stop com.vitalguard.ai
"$ADB" push "../dms-ai-engine/out/drowsy.mp4" /data/local/tmp/replay_test.mp4
"$ADB" logcat -c
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity
sleep 30
"$ADB" logcat -d -s MediaPipeReplayDetection:D
```
Expected: `Replay detection: finished, fed ... frames, received ... callbacks` with fed≈received (matching Task 0/1's prior on-device confirmation), no crash -- confirming the demo-stage fallback (root CLAUDE.md's Demo Script contingency) is intact after all of this plan's changes.

(No commit for this task -- it is a verification checkpoint. If any step fails, fix it in the relevant earlier task and re-commit there, then re-run this task from Step 1.)

---

## Plan self-review notes

- **Spec coverage:** every design-doc decision (D1-D8) maps to a task above: D1→Task 0, D2→Task 1, D3→Tasks 1&4, D4→Task 3, D5→Task 4, D6→Tasks 2&5, D7→Tasks 1&4, D8→Task 5. §6 error-handling table maps to Task 4's `start()`/`handleCameraLoss` and Task 5's `catch (Throwable)` wiring. §7's rollout order maps 1:1 to this plan's task order. §8's testing section is reflected in each task's Interfaces block noting what is/isn't JVM-testable and why.
- **Type consistency:** `DrowsinessDetectionCore(onPayload, onTelemetry)` (Task 1) is constructed identically in both `MediaPipeReplayDetectionSource` (Task 1) and `MediaPipeLiveDetectionSource` (Task 4). `CameraFacingResolver.resolveFrontCameraId(CameraManager): String` (Task 3) is consumed with the exact same signature in Task 4. `DetectionBackendMode`/`PrefsDetectionBackendModeStore` (Task 2) are consumed with matching names in Task 5's `VitalGuardMonitorService` rewrite.
- **No placeholders:** every step has literal, runnable code or an exact shell command; Task 0's Step 8 and Task 6 intentionally describe a verification *procedure* rather than pre-computed numbers, since those numbers genuinely cannot be known without running the code this plan produces -- this is not a "TBD, fill in later," it's the correct way to plan a step whose output is the point of running it.
