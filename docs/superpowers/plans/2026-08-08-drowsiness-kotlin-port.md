# Drowsiness Pipeline Kotlin Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the real, already-tested Python drowsiness math (`dms-ai-engine/services/{eye_state,head_pose,score_calculator,trigger_emitter,escalation_tracker}.py`) into a new Kotlin package, wire it into `MediaPipeReplayDetectionSource`, and retire `TriggerPollClient`'s HTTP-polling dependency on the Python Container Node.

**Architecture:** New `com.vitalguard.ai.drowsiness` package holds 1:1 Kotlin ports of the 5 Python service modules, each independently unit-tested against translated versions of the existing Python test suite. `MediaPipeReplayDetectionSource` becomes the orchestrator wiring them together (mirroring `main.py::run_real_video()`'s drowsiness-only slice), replacing its current inline simplified threshold logic. `TriggerPollClient`/`HttpTriggerFetcher` and their wiring in `VitalGuardMonitorService` are deleted.

**Tech Stack:** Kotlin, JUnit4, `kotlinx-coroutines-test`, MediaPipe Tasks Vision 1.0.0 (`FaceLandmarkerResult`), Android `MediaMetadataRetriever`.

## Global Constraints

- **Branch:** all work happens on `feature/drowsiness-kotlin-port` (already created). Never commit to `main`.
- **Scope:** drowsiness only — PERCLOS, head-pose, sustain/hysteresis, escalation levels 1/2/3, face-loss → UNKNOWN. Distraction detection (hands-off-wheel, gaze-off-road) is explicitly out of scope; `MediaPipeReplayDetectionSource` keeps emitting `NO_DISTRACTION` in every payload, unchanged.
- **No live-camera work.** Only the Replay-file path (`/data/local/tmp/replay_test.mp4`) changes. `LiveDetectionSource` does not exist yet.
- **Sampling rate:** native video frame rate (queried at runtime, fallback 30.0 fps), never a fixed Hz — see design doc decision D2. Do not "fix" this back to a fixed rate.
- **Timing:** every `now` parameter passed to `TriggerEmitter`/`FacePresenceTracker`/`EscalationTracker` must be real elapsed seconds (a `Double`), never a sample index or frame count — decision D3.
- **On face loss (UNKNOWN):** hard-reset `EscalationTracker` to level 1 via `reset()`. Never freeze, decay, or increase escalation on face loss — decision D4.
- **`FacePresenceTracker` hysteresis stays asymmetric** (enter `UNKNOWN` needs ≥2.0s continuous absence; recover to `PRESENT` fires on a single good frame) — decision D5. Do not add symmetric debounce.
- Every new Kotlin file must carry a kdoc comment naming the exact Python file it was ported from (matches existing project convention, e.g. `FaceLandmarkerClient.kt`'s and `MediaPipeReplayDetectionSource.kt`'s own kdoc headers).
- Full design rationale and evidence: `docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md`.

---

## Task 1: `DrowsinessPipelineConfig` — single source of truth for tunables

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineConfig.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineConfigTest.kt`

**Interfaces:**
- Produces: `DrowsinessPipelineConfig` object with constants `WINDOW_SECONDS: Double`, `SAMPLE_HZ: Double`, `MAX_DROOP_DEG: Double`, `ENTER_THRESHOLD: Double`, `EXIT_THRESHOLD: Double`, `SUSTAIN_SECONDS: Double`, `COOLDOWN_SECONDS: Double`, `FACE_ABSENCE_SUSTAIN_SECONDS: Double`, `LEVEL_UP_SECONDS: List<Double>`, `REPEAT_INTERVAL_SECONDS: List<Double>`, `BASELINE_CALIBRATION_SECONDS: Double`, `FALLBACK_FPS: Double`. Every later task consumes this.

- [ ] **Step 1: Write the sanity test locking in the exact values from `main.py::run_real_video()`**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Test

class DrowsinessPipelineConfigTest {
    @Test
    fun `values match dms-ai-engine main py's run_real_video construction`() {
        assertEquals(2.0, DrowsinessPipelineConfig.WINDOW_SECONDS, 0.0)
        assertEquals(10.0, DrowsinessPipelineConfig.SAMPLE_HZ, 0.0)
        assertEquals(25.0, DrowsinessPipelineConfig.MAX_DROOP_DEG, 0.0)
        assertEquals(0.85, DrowsinessPipelineConfig.ENTER_THRESHOLD, 0.0)
        assertEquals(0.50, DrowsinessPipelineConfig.EXIT_THRESHOLD, 0.0)
        assertEquals(2.0, DrowsinessPipelineConfig.SUSTAIN_SECONDS, 0.0)
        assertEquals(10.0, DrowsinessPipelineConfig.COOLDOWN_SECONDS, 0.0)
        assertEquals(2.0, DrowsinessPipelineConfig.FACE_ABSENCE_SUSTAIN_SECONDS, 0.0)
        assertEquals(listOf(8.0, 16.0), DrowsinessPipelineConfig.LEVEL_UP_SECONDS)
        assertEquals(listOf(10.0, 5.0, 4.0), DrowsinessPipelineConfig.REPEAT_INTERVAL_SECONDS)
        assertEquals(1.0, DrowsinessPipelineConfig.BASELINE_CALIBRATION_SECONDS, 0.0)
        assertEquals(30.0, DrowsinessPipelineConfig.FALLBACK_FPS, 0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (class doesn't exist yet)**

Run (from `aaos-cockpit-app/`, with `JAVA_HOME` set per the emulator guide):
```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessPipelineConfigTest"
```
Expected: FAIL — `unresolved reference: DrowsinessPipelineConfig`.

- [ ] **Step 3: Create `DrowsinessPipelineConfig.kt`**

```kotlin
package com.vitalguard.ai.drowsiness

/**
 * Single source of truth for every tunable constant in the on-device
 * drowsiness pipeline. Values are copied verbatim from
 * dms-ai-engine/main.py's construction of DrowsinessScoreCalculator/
 * TriggerEmitter/FacePresenceTracker/EscalationTracker inside
 * run_real_video() -- the exact values already validated in
 * dms-ai-engine/CV_REMEDIATION_RESULTS.md's acceptance gates.
 *
 * Any future LiveDetectionSource MUST reuse this object rather than
 * hard-coding its own constants -- see
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md,
 * decision D1. No live source exists yet; this guards against future drift.
 */
object DrowsinessPipelineConfig {
    // DrowsinessScoreCalculator window
    const val WINDOW_SECONDS = 2.0
    const val SAMPLE_HZ = 10.0
    const val MAX_DROOP_DEG = 25.0

    // TriggerEmitter
    const val ENTER_THRESHOLD = 0.85
    const val EXIT_THRESHOLD = 0.50
    const val SUSTAIN_SECONDS = 2.0
    const val COOLDOWN_SECONDS = 10.0

    // FacePresenceTracker
    const val FACE_ABSENCE_SUSTAIN_SECONDS = 2.0

    // EscalationTracker (drowsiness)
    val LEVEL_UP_SECONDS = listOf(8.0, 16.0)
    val REPEAT_INTERVAL_SECONDS = listOf(10.0, 5.0, 4.0)

    // Baseline pitch calibration
    const val BASELINE_CALIBRATION_SECONDS = 1.0

    // Replay-file sampling fallback when the video's own frame rate can't be
    // read (METADATA_KEY_CAPTURE_FRAMERATE is officially "if available" --
    // usually absent on normally-recorded, non slow-motion clips). Matches
    // dms-ai-engine/main.py's own `fps = 30.0` fallback exactly.
    const val FALLBACK_FPS = 30.0
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessPipelineConfigTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineConfig.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineConfigTest.kt
git commit -m "Add DrowsinessPipelineConfig: single source of truth for drowsiness tunables

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: Port `BlinkStateTracker` (from `eye_state.py`)

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/BlinkStateTracker.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/BlinkStateTrackerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: top-level `fun blinkScore(blendshapes: Map<String, Float>): Float`; `class BlinkStateTracker` with `fun update(score: Float, now: Double): Boolean`; top-level `const val BLINK_CLOSE_THRESHOLD: Float = 0.55f`, `const val BLINK_REOPEN_THRESHOLD: Float = 0.35f`. Task 9 (orchestrator) consumes all three.

- [ ] **Step 1: Write the failing tests (ported from `test_eye_state.py`)**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkStateTrackerTest {

    @Test
    fun `blinkScore averages both eyes`() {
        val blendshapes = mapOf("eyeBlinkLeft" to 0.8f, "eyeBlinkRight" to 0.6f, "jawOpen" to 0.1f)
        assertEquals(0.7f, blinkScore(blendshapes), 0.0001f)
    }

    @Test
    fun `blinkScore missing category defaults to zero`() {
        // If Face Landmarker doesn't report a category for a frame (e.g. face
        // partially out of view), treat it as eyes-open rather than crashing.
        assertEquals(0.0f, blinkScore(mapOf("jawOpen" to 0.1f)), 0.0001f)
    }

    @Test
    fun `stays open below close threshold`() {
        val tracker = BlinkStateTracker()
        assertFalse(tracker.update(BLINK_CLOSE_THRESHOLD - 0.05f, now = 0.0))
    }

    @Test
    fun `closes above close threshold`() {
        val tracker = BlinkStateTracker()
        assertTrue(tracker.update(BLINK_CLOSE_THRESHOLD + 0.05f, now = 0.0))
    }

    @Test
    fun `ignores a single dip between the two thresholds`() {
        // Exact failure mode from the real drowsy-video finding: one noisy
        // frame dipping between the close and reopen thresholds must NOT
        // flip the state back to open -- only dropping below the LOWER
        // reopen threshold should.
        val tracker = BlinkStateTracker()
        assertTrue(tracker.update(BLINK_CLOSE_THRESHOLD + 0.10f, now = 0.0))
        val midpoint = (BLINK_CLOSE_THRESHOLD + BLINK_REOPEN_THRESHOLD) / 2f
        assertTrue("a dip that doesn't cross the reopen threshold must stay closed",
            tracker.update(midpoint, now = 0.03))
        assertTrue(tracker.update(BLINK_CLOSE_THRESHOLD + 0.10f, now = 0.07))
    }

    @Test
    fun `reopens only below reopen threshold`() {
        val tracker = BlinkStateTracker()
        tracker.update(BLINK_CLOSE_THRESHOLD + 0.10f, now = 0.0)
        assertFalse(tracker.update(BLINK_REOPEN_THRESHOLD - 0.05f, now = 0.03))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.BlinkStateTrackerTest"
```
Expected: FAIL — `unresolved reference: blinkScore` / `BlinkStateTracker`.

- [ ] **Step 3: Create `BlinkStateTracker.kt`**

```kotlin
package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/eye_state.py. Eye-closure signal from
 * MediaPipe Face Landmarker's face_blendshapes (eyeBlinkLeft/eyeBlinkRight,
 * continuous [0,1]).
 *
 * BLINK_CLOSE_THRESHOLD/BLINK_REOPEN_THRESHOLD form a two-threshold
 * hysteresis: values between the two thresholds are deliberately "sticky"
 * in whichever state was last entered. This targets an evidenced failure: a
 * real drowsy video's score climbed to 0.800 then dropped sharply the
 * instant one frame's raw signal crossed a single instantaneous threshold,
 * right before the 0.85 CRITICAL threshold would have been reached.
 */
const val BLINK_CLOSE_THRESHOLD = 0.55f
const val BLINK_REOPEN_THRESHOLD = 0.35f

fun blinkScore(blendshapes: Map<String, Float>): Float {
    val left = blendshapes["eyeBlinkLeft"] ?: 0f
    val right = blendshapes["eyeBlinkRight"] ?: 0f
    return (left + right) / 2f
}

class BlinkStateTracker {
    private var closed = false

    // `now` is accepted but unused in the body -- matches
    // eye_state.py::BlinkStateTracker.update()'s own signature exactly
    // (kept for interface parity with the other trackers the orchestrator
    // drives identically), not an oversight.
    fun update(score: Float, now: Double): Boolean {
        if (!closed && score >= BLINK_CLOSE_THRESHOLD) {
            closed = true
        } else if (closed && score <= BLINK_REOPEN_THRESHOLD) {
            closed = false
        }
        return closed
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.BlinkStateTrackerTest"
```
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/BlinkStateTracker.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/BlinkStateTrackerTest.kt
git commit -m "Port BlinkStateTracker from dms-ai-engine/services/eye_state.py

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: Port `HeadPose` (from `head_pose.py`) — pure math only

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/HeadPose.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/HeadPoseTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `object HeadPose` with `fun rotationMatrixToEulerDeg(matrix: FloatArray): Triple<Double, Double, Double>`, `fun extractPitchDeg(matrix: FloatArray): Double`, `fun extractYawDeg(matrix: FloatArray): Double`. `matrix` is a flat 16-element **row-major** array (`matrix[row*4+col]`). Task 9 (orchestrator) consumes `extractPitchDeg` and empirically decides whether the real device's `FaceLandmarkerResult.facialTransformationMatrixes()` output needs transposing before being passed in — this task only proves the math is correct for a *given* row-major convention.

- [ ] **Step 1: Write the failing tests (ported from `test_head_pose.py`)**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HeadPoseTest {

    private fun identity4x4(): FloatArray {
        val m = FloatArray(16)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return m
    }

    /** Pure rotation about the X axis, as a flat 16-element row-major 4x4 matrix. */
    private fun rotationMatrixX(angleDeg: Double): FloatArray {
        val a = Math.toRadians(angleDeg)
        val m = identity4x4()
        m[1 * 4 + 1] = cos(a).toFloat()
        m[1 * 4 + 2] = (-sin(a)).toFloat()
        m[2 * 4 + 1] = sin(a).toFloat()
        m[2 * 4 + 2] = cos(a).toFloat()
        return m
    }

    private fun rotationMatrixY(angleDeg: Double): FloatArray {
        val a = Math.toRadians(angleDeg)
        val m = identity4x4()
        m[0 * 4 + 0] = cos(a).toFloat()
        m[0 * 4 + 2] = sin(a).toFloat()
        m[2 * 4 + 0] = (-sin(a)).toFloat()
        m[2 * 4 + 2] = cos(a).toFloat()
        return m
    }

    private fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[row * 4 + k] * b[k * 4 + col]
                result[row * 4 + col] = sum
            }
        }
        return result
    }

    @Test
    fun `pure X rotation recovers the known angle`() {
        for (angle in listOf(-30.0, -10.0, 10.0, 30.0)) {
            val (x, y, z) = HeadPose.rotationMatrixToEulerDeg(rotationMatrixX(angle))
            assertTrue("expected x~$angle, got $x", abs(x - angle) < 0.01)
            assertTrue("pure X rotation leaked into y/z: y=$y z=$z", abs(y) < 0.01 && abs(z) < 0.01)
        }
    }

    @Test
    fun `pure Y rotation recovers the known angle`() {
        for (angle in listOf(-30.0, -10.0, 10.0, 30.0)) {
            val (x, y, z) = HeadPose.rotationMatrixToEulerDeg(rotationMatrixY(angle))
            assertTrue("expected y~$angle, got $y", abs(y - angle) < 0.01)
            assertTrue("pure Y rotation leaked into x/z: x=$x z=$z", abs(x) < 0.01 && abs(z) < 0.01)
        }
    }

    @Test
    fun `extractPitchDeg increases monotonically with the chosen axis rotation`() {
        val angles = listOf(-20.0, -10.0, 0.0, 10.0, 20.0, 30.0)
        val pitches = angles.map { HeadPose.extractPitchDeg(rotationMatrixX(it)) }
        for (i in 0 until pitches.size - 1) {
            assertTrue("pitch must be strictly increasing, got $pitches", pitches[i + 1] > pitches[i])
        }
    }

    @Test
    fun `extractPitchDeg is insensitive to the other axes`() {
        for (angle in listOf(-30.0, -15.0, 15.0, 30.0)) {
            val pitch = HeadPose.extractPitchDeg(rotationMatrixY(angle))
            assertTrue("non-pitch axis rotation of $angle deg leaked into pitch: got $pitch", abs(pitch) < 2.5)
        }
    }

    @Test
    fun `combined rotation does not corrupt pitch extraction`() {
        val pitchAngle = 20.0
        val otherAngle = 15.0
        // Ry left-multiplies Rx -- same composition order as head_pose.py's own test.
        val combined = multiply4x4(rotationMatrixY(otherAngle), rotationMatrixX(pitchAngle))
        val (x, y, z) = HeadPose.rotationMatrixToEulerDeg(combined)
        assertTrue("expected pitch axis ~$pitchAngle, got $x", abs(x - pitchAngle) < 0.01)
        assertTrue("expected other axis ~$otherAngle, got $y", abs(y - otherAngle) < 0.01)
        assertTrue("expected no leakage into the third axis, got $z", abs(z) < 0.01)
    }

    @Test
    fun `extractYawDeg increases monotonically with the chosen axis rotation`() {
        val angles = listOf(-20.0, -10.0, 0.0, 10.0, 20.0, 30.0)
        val yaws = angles.map { HeadPose.extractYawDeg(rotationMatrixY(it)) }
        for (i in 0 until yaws.size - 1) {
            assertTrue("yaw must be strictly increasing, got $yaws", yaws[i + 1] > yaws[i])
        }
    }

    @Test
    fun `extractYawDeg is insensitive to pitch rotation`() {
        for (angle in listOf(-30.0, -15.0, 15.0, 30.0)) {
            val yaw = HeadPose.extractYawDeg(rotationMatrixX(angle))
            assertTrue("non-yaw axis rotation of $angle deg leaked into yaw: got $yaw", abs(yaw) < 2.5)
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.HeadPoseTest"
```
Expected: FAIL — `unresolved reference: HeadPose`.

- [ ] **Step 3: Create `HeadPose.kt`**

```kotlin
package com.vitalguard.ai.drowsiness

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ported from dms-ai-engine/services/head_pose.py. Head pitch/yaw estimation
 * from MediaPipe Face Landmarker's facial_transformation_matrixes -- a full
 * learned 3D face model fit, not a from-scratch solvePnP solve, which is why
 * this doesn't suffer the PnP flip-ambiguity that affected the project's old
 * head-pose extraction.
 *
 * `matrix` is a flat 16-element array, interpreted here as **row-major**
 * (matrix[row*4+col]) -- matching numpy's natural 2D indexing that Python's
 * head_pose.py assumes. This module's own unit tests validate the pure math
 * for that convention only; whether the real Android
 * FaceLandmarkerResult.facialTransformationMatrixes() output needs
 * transposing before being passed in is a separate, empirical, device-level
 * question answered in Task 9's on-device verification step -- see
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md.
 */
object HeadPose {
    private fun at(matrix: FloatArray, row: Int, col: Int): Double = matrix[row * 4 + col].toDouble()

    /** Returns (x, y, z) Euler angles in degrees. */
    fun rotationMatrixToEulerDeg(matrix: FloatArray): Triple<Double, Double, Double> {
        val r00 = at(matrix, 0, 0)
        val r10 = at(matrix, 1, 0)
        val r20 = at(matrix, 2, 0)
        val r21 = at(matrix, 2, 1)
        val r22 = at(matrix, 2, 2)

        val x = Math.toDegrees(atan2(r21, r22))
        val y = Math.toDegrees(atan2(-r20, sqrt(r21 * r21 + r22 * r22)))
        val z = Math.toDegrees(atan2(r10, r00))
        return Triple(x, y, z)
    }

    /** Empirically determined to be pitch -- see module kdoc. */
    fun extractPitchDeg(matrix: FloatArray): Double = rotationMatrixToEulerDeg(matrix).first

    /** Empirically confirmed to be yaw -- see module kdoc. */
    fun extractYawDeg(matrix: FloatArray): Double = rotationMatrixToEulerDeg(matrix).second
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.HeadPoseTest"
```
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/HeadPose.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/HeadPoseTest.kt
git commit -m "Port HeadPose rotation-matrix math from dms-ai-engine/services/head_pose.py

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: Port `DrowsinessScoreCalculator` (from `score_calculator.py`)

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculator.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculatorTest.kt`

**Interfaces:**
- Consumes: `DrowsinessPipelineConfig` (Task 1) for default constructor values.
- Produces: `data class FrameFeatures(val timestamp: Double, val eyeClosed: Boolean, val headPitchDeg: Double)`; `class DrowsinessScoreCalculator(windowSeconds: Double = ..., sampleHz: Double = ..., maxDroopDeg: Double = ...)` with `fun calibrateBaseline(pitchDeg: Double)`, `fun addFrame(frame: FrameFeatures): Double`, `fun computeScore(): Double`, `val baselinePitchDeg: Double`. Tasks 7, 8, 9 consume all of this.

- [ ] **Step 1: Write the failing tests (ported from `test_dms.py`'s calculator section, plus the new documented-known-limitation case)**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrowsinessScoreCalculatorTest {

    @Test
    fun `all eyes open no droop gives zero score`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        for (i in 0 until 20) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = false, headPitchDeg = 0.0))
        }
        assertEquals(0.0, calc.computeScore(), 0.0001)
    }

    @Test
    fun `sustained closed eyes and droop gives high score`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        for (i in 0 until 20) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = true, headPitchDeg = 30.0))
        }
        assertTrue(calc.computeScore() > 0.85)
    }

    @Test
    fun `single normal blink does not spike perclos`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        for (i in 0 until 19) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = false, headPitchDeg = 0.0))
        }
        val score = calc.addFrame(FrameFeatures(timestamp = 1.9, eyeClosed = true, headPitchDeg = 0.0))
        assertTrue("one normal blink must not cause a high score", score < 0.85)
    }

    @Test
    fun `baseline calibration removes seat tilt offset`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        calc.calibrateBaseline(pitchDeg = 10.0) // seat already tilted 10deg when sitting upright
        for (i in 0 until 20) {
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = false, headPitchDeg = 10.0))
        }
        // head is still at "upright" after subtracting baseline -> droop must be 0
        assertEquals(0.0, calc.computeScore(), 0.0001)
    }

    @Test
    fun `known limitation - non-neutral pose during the calibration window caps the score`() {
        // If the first BASELINE_CALIBRATION_SECONDS aren't neutral (e.g. driver
        // still adjusting the mirror), the wrong baseline is locked in for the
        // rest of the run. Documented known limitation (design doc decision
        // D7) -- Python has no test for this scenario either; ported as-is,
        // not fixed here.
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        calc.calibrateBaseline(pitchDeg = 20.0) // wrongly calibrated against a droopy first second
        for (i in 0 until 20) {
            // Driver is actually fully drowsy: eyes closed, head at the SAME
            // droop as the bad calibration window -- droop reads as ~0, not 1.
            calc.addFrame(FrameFeatures(timestamp = i * 0.1, eyeClosed = true, headPitchDeg = 20.0))
        }
        val score = calc.computeScore()
        // 0.55*perclos(1.0) + 0.25*eyeClosedNow(1.0) + 0.20*droop(0.0) = 0.80
        assertEquals(0.80, score, 0.001)
        assertTrue("known limitation: capped below the 0.85 CRITICAL threshold by a bad calibration window",
            score < 0.85)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessScoreCalculatorTest"
```
Expected: FAIL — `unresolved reference: DrowsinessScoreCalculator`.

- [ ] **Step 3: Create `DrowsinessScoreCalculator.kt`**

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
 */
data class FrameFeatures(
    val timestamp: Double,
    val eyeClosed: Boolean,
    val headPitchDeg: Double,
)

class DrowsinessScoreCalculator(
    windowSeconds: Double = DrowsinessPipelineConfig.WINDOW_SECONDS,
    sampleHz: Double = DrowsinessPipelineConfig.SAMPLE_HZ,
    private val maxDroopDeg: Double = DrowsinessPipelineConfig.MAX_DROOP_DEG,
) {
    private val maxSamples: Int = maxOf(1, (windowSeconds * sampleHz).toInt())
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
        while (window.size > maxSamples) window.removeFirst()
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

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessScoreCalculatorTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculator.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessScoreCalculatorTest.kt
git commit -m "Port DrowsinessScoreCalculator from dms-ai-engine/services/score_calculator.py

Includes a new test documenting the known non-neutral-calibration-window
limitation (design doc D7) -- not fixed, same treatment as other disclosed
unvalidated behavior in this project.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: Port `TriggerEmitter` + `FacePresenceTracker` (from `trigger_emitter.py`)

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/TriggerEmitter.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/TriggerEmitterTest.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/FacePresenceTrackerTest.kt`

**Interfaces:**
- Consumes: `DrowsinessPipelineConfig` (Task 1) for default constructor values.
- Produces: `sealed class TriggerSignal { object Critical; object Recovered }`; `class TriggerEmitter(enterThreshold, exitThreshold, sustainSeconds, cooldownSeconds)` with `fun update(score: Double, now: Double): TriggerSignal?` and `val criticalActive: Boolean`. `sealed class FacePresenceSignal { object Unknown; object Present }`; `class FacePresenceTracker(sustainSeconds)` with `fun update(hasFace: Boolean, now: Double): FacePresenceSignal?`. Tasks 8, 9 consume all of this.

- [ ] **Step 1: Write the failing `TriggerEmitter` tests (ported from `test_dms.py`)**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerEmitterTest {

    @Test
    fun `no trigger before sustain window elapses`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertNull(emitter.update(0.9, now = 0.0))
        assertNull(emitter.update(0.9, now = 1.0))
    }

    @Test
    fun `trigger fires once after sustain`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 1.0)
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 2.1))
    }

    @Test
    fun `no duplicate trigger while still above threshold`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        val firedFirst = emitter.update(0.9, now = 2.1)
        val firedAgain = emitter.update(0.9, now = 3.0)
        assertEquals(TriggerSignal.Critical, firedFirst)
        assertNull("must not trigger again while still in the old episode", firedAgain)
    }

    @Test
    fun `trigger rearms after dropping below exit threshold`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 3.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 2.1) // fires 1st time
        emitter.update(0.3, now = 5.0) // drops below exit -> re-arms
        assertNull("not sustained again yet", emitter.update(0.9, now = 5.1))
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 7.2))
    }

    @Test
    fun `hysteresis prevents flicker around 0_85`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0)
        var t = 0.0
        var firedAny = false
        for (i in 0 until 10) {
            val score = if (i % 2 == 0) 0.86 else 0.84 // never drops to exitThreshold=0.50
            if (emitter.update(score, now = t) != null) firedAny = true
            t += 0.3
        }
        assertFalse(firedAny)
    }

    @Test
    fun `criticalActive property reflects internal state`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertFalse(emitter.criticalActive)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 2.1)
        assertTrue(emitter.criticalActive)
        emitter.update(0.3, now = 5.0)
        assertFalse(emitter.criticalActive)
    }

    @Test
    fun `criticalActive does not flap on warning zone oscillation`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 2.1)
        assertTrue(emitter.criticalActive)
        var t = 2.1
        for (i in 0 until 10) {
            val score = if (i % 2 == 0) 0.80 else 0.60 // oscillates in WARNING zone, never <=0.50
            emitter.update(score, now = t)
            assertTrue("must stay true at t=$t, score=$score", emitter.criticalActive)
            t += 0.3
        }
    }

    @Test
    fun `short dip below enter but above exit resets sustain timer`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0)
        emitter.update(0.9, now = 0.0)
        emitter.update(0.9, now = 1.5)
        emitter.update(0.7, now = 1.6) // dips but stays above exit -> aboveSince resets
        assertNull("must restart sustain timer", emitter.update(0.9, now = 1.7))
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 3.8))
    }

    @Test
    fun `update returns critical on fire`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 2.1))
    }

    @Test
    fun `update returns null when not firing`() {
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertNull(emitter.update(0.9, now = 0.0))
    }

    @Test
    fun `recovered fires once on down edge after critical`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 10.0)
        emitter.update(0.9, now = 0.0)
        assertEquals(TriggerSignal.Critical, emitter.update(0.9, now = 2.1))
        assertEquals(TriggerSignal.Recovered, emitter.update(0.3, now = 5.0))
        assertNull("must not repeat RECOVERED every call", emitter.update(0.3, now = 5.1))
    }

    @Test
    fun `recovered does not fire without a prior critical`() {
        val emitter = TriggerEmitter(enterThreshold = 0.85, exitThreshold = 0.50, sustainSeconds = 2.0, cooldownSeconds = 10.0)
        assertNull(emitter.update(0.3, now = 0.0))
        assertNull(emitter.update(0.2, now = 1.0))
    }
}
```

- [ ] **Step 2: Write the failing `FacePresenceTracker` tests (ported from `test_dms.py`)**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacePresenceTrackerTest {

    @Test
    fun `no signal while face is visible`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        assertNull(tracker.update(hasFace = true, now = 0.0))
        assertNull(tracker.update(hasFace = true, now = 1.0))
    }

    @Test
    fun `unknown fires once after sustained loss`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        assertNull(tracker.update(hasFace = false, now = 0.0))
        assertNull(tracker.update(hasFace = false, now = 1.0))
        assertEquals(FacePresenceSignal.Unknown, tracker.update(hasFace = false, now = 2.1))
        assertNull("must not repeat UNKNOWN every call", tracker.update(hasFace = false, now = 3.0))
    }

    @Test
    fun `present fires once on face returning`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        tracker.update(hasFace = false, now = 0.0)
        assertEquals(FacePresenceSignal.Unknown, tracker.update(hasFace = false, now = 2.1))
        assertEquals(FacePresenceSignal.Present, tracker.update(hasFace = true, now = 3.0))
        assertNull("must not repeat PRESENT every call", tracker.update(hasFace = true, now = 3.1))
    }

    @Test
    fun `brief loss under sustain window emits nothing`() {
        val tracker = FacePresenceTracker(sustainSeconds = 2.0)
        tracker.update(hasFace = false, now = 0.0)
        assertNull("face came back before sustain elapsed", tracker.update(hasFace = true, now = 1.0))
    }
}
```

- [ ] **Step 3: Run to verify both fail**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.TriggerEmitterTest" --tests "com.vitalguard.ai.drowsiness.FacePresenceTrackerTest"
```
Expected: FAIL — `unresolved reference: TriggerEmitter` / `FacePresenceTracker`.

- [ ] **Step 4: Create `TriggerEmitter.kt`**

```kotlin
package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/trigger_emitter.py.
 *
 * TriggerEmitter decides WHEN to fire a Trigger from a continuous stream of
 * Drowsiness Scores:
 *   - Enter threshold 0.85, must SUSTAIN continuously >= sustainSeconds to fire.
 *   - Does not fire repeatedly while still above threshold (once per episode).
 *   - Only re-arms to fire again once score drops <= exitThreshold (2-threshold
 *     hysteresis, not a single threshold -- avoids oscillation around 0.85).
 *   - cooldownSeconds is an additional safety net in case the sustain logic
 *     has a bug.
 */
sealed class TriggerSignal {
    object Critical : TriggerSignal()
    object Recovered : TriggerSignal()
}

class TriggerEmitter(
    private val enterThreshold: Double = DrowsinessPipelineConfig.ENTER_THRESHOLD,
    private val exitThreshold: Double = DrowsinessPipelineConfig.EXIT_THRESHOLD,
    private val sustainSeconds: Double = DrowsinessPipelineConfig.SUSTAIN_SECONDS,
    private val cooldownSeconds: Double = DrowsinessPipelineConfig.COOLDOWN_SECONDS,
) {
    init {
        require(exitThreshold < enterThreshold) { "exitThreshold must be lower than enterThreshold (hysteresis)" }
    }

    private var aboveSince: Double? = null
    private var lastEmitTime: Double = Double.NEGATIVE_INFINITY
    private var armed = true

    var criticalActive: Boolean = false
        private set

    fun update(score: Double, now: Double): TriggerSignal? {
        if (score >= enterThreshold) {
            if (aboveSince == null) aboveSince = now
            val sustained = (now - aboveSince!!) >= sustainSeconds
            val cooldownOk = (now - lastEmitTime) >= cooldownSeconds
            if (sustained && cooldownOk && armed) {
                armed = false
                lastEmitTime = now
                criticalActive = true
                return TriggerSignal.Critical
            }
        } else {
            aboveSince = null
            if (score <= exitThreshold) {
                armed = true
                if (criticalActive) {
                    criticalActive = false
                    return TriggerSignal.Recovered
                }
            }
        }
        return null
    }
}

/**
 * Detects sustained loss of face (camera occluded, driver out of frame) to
 * emit UNKNOWN -- separate from TriggerEmitter because this is a signal
 * about face PRESENCE, not about the score value.
 */
sealed class FacePresenceSignal {
    object Unknown : FacePresenceSignal()
    object Present : FacePresenceSignal()
}

class FacePresenceTracker(
    private val sustainSeconds: Double = DrowsinessPipelineConfig.FACE_ABSENCE_SUSTAIN_SECONDS,
) {
    private var absentSince: Double? = null
    private var unknownActive = false

    fun update(hasFace: Boolean, now: Double): FacePresenceSignal? {
        if (!hasFace) {
            if (absentSince == null) absentSince = now
            val sustained = (now - absentSince!!) >= sustainSeconds
            if (sustained && !unknownActive) {
                unknownActive = true
                return FacePresenceSignal.Unknown
            }
        } else {
            absentSince = null
            if (unknownActive) {
                unknownActive = false
                return FacePresenceSignal.Present
            }
        }
        return null
    }
}
```

- [ ] **Step 5: Run to verify both pass**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.TriggerEmitterTest" --tests "com.vitalguard.ai.drowsiness.FacePresenceTrackerTest"
```
Expected: PASS (12 + 4 tests).

- [ ] **Step 6: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/TriggerEmitter.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/TriggerEmitterTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/FacePresenceTrackerTest.kt
git commit -m "Port TriggerEmitter + FacePresenceTracker from dms-ai-engine/services/trigger_emitter.py

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: Port `EscalationTracker` (from `escalation_tracker.py`)

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/EscalationTracker.kt`
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/EscalationTrackerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (constructed with explicit lists by callers).
- Produces: `class EscalationTracker(levelUpSeconds: List<Double>, repeatIntervalSeconds: List<Double>)` with `fun update(criticalActive: Boolean, now: Double): Triple<Int, Boolean, Boolean>` (level, repeatDue, levelChanged) and `fun reset()`. Tasks 8, 9 consume this.

- [ ] **Step 1: Write the failing tests (ported from `test_escalation_tracker.py`)**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationTrackerTest {

    @Test(expected = IllegalArgumentException::class)
    fun `constructor validates length mismatch`() {
        EscalationTracker(levelUpSeconds = listOf(8.0, 16.0), repeatIntervalSeconds = listOf(10.0, 5.0))
    }

    @Test
    fun `not critical returns level 1 and no repeat`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = false, now = 0.0)
        assertEquals(1, level)
        assertFalse(repeatDue)
        assertFalse(levelChanged)
    }

    @Test
    fun `onset tick is level 1 and repeat due fires immediately`() {
        // First tick of a new CRITICAL episode -- repeatDue=true is expected
        // (it anchors lastRepeatTime; it does NOT cause an extra publish,
        // since the emitter's own CRITICAL edge already publishes this same tick).
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = true, now = 0.0)
        assertEquals(1, level)
        assertTrue(repeatDue)
        assertFalse(levelChanged)
    }

    @Test
    fun `level up boundary is inclusive at exact elapsed`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        val (level, _, levelChanged) = tracker.update(criticalActive = true, now = 8.0)
        assertEquals("elapsed==8.0 must already count as level 2 (>=, not >)", 2, level)
        assertTrue(levelChanged)
    }

    @Test
    fun `single tick jump from level 1 straight to level 3`() {
        // Simulates a large frame gap (e.g. a face-loss stretch) where
        // elapsed jumps past both level-up boundaries in one update() call.
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = true, now = 20.0)
        assertEquals(3, level)
        assertTrue(levelChanged)
        assertTrue(repeatDue)
    }

    @Test
    fun `level changed updates last repeat time to avoid double fire`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)           // level 1, lastRepeatTime=0
        tracker.update(criticalActive = true, now = 8.0)            // levelChanged -> 2, lastRepeatTime=8
        val r1 = tracker.update(criticalActive = true, now = 13.0)  // 13-8=5>=interval[1]=5.0
        assertEquals(2, r1.first); assertTrue(r1.second); assertFalse(r1.third)

        // level changes to 3 here; lastRepeatTime must become 16, NOT stay at 13
        val r2 = tracker.update(criticalActive = true, now = 16.0)
        assertEquals(3, r2.first); assertTrue(r2.third); assertTrue(r2.second)

        // without the fix, interval[2]=4.0 measured from the OLD anchor (13)
        // would make this next check fire at 17.0 (13+4); with the fix it
        // must NOT fire until 20.0 (16+4).
        val r3 = tracker.update(criticalActive = true, now = 17.0)
        assertFalse("must not double-fire 1s after the level-change announcement", r3.second)
        val r4 = tracker.update(criticalActive = true, now = 20.0)
        assertTrue(r4.second)
    }

    @Test
    fun `continues through a warning dip without resetting`() {
        // criticalActive is assumed to already be hysteresis-protected by the
        // emitter (it only goes false at score<=exitThreshold, not on a
        // WARNING dip) -- this locks in that EscalationTracker trusts that
        // input as-is and does not add its own reset logic on top.
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        tracker.update(criticalActive = true, now = 16.0) // level 3
        val (level, _, _) = tracker.update(criticalActive = true, now = 22.0)
        assertEquals("a caller passing criticalActive=true continuously must never see the level drop", 3, level)
    }

    @Test
    fun `recovered resets to level 1 and level changed fires exactly once`() {
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        tracker.update(criticalActive = true, now = 16.0) // level 3
        val (level, repeatDue, levelChanged) = tracker.update(criticalActive = false, now = 20.0)
        assertEquals(1, level); assertFalse(repeatDue); assertTrue(levelChanged)
        val (_, _, levelChanged2) = tracker.update(criticalActive = false, now = 21.0)
        assertFalse("must not repeat levelChanged on every subsequent non-critical tick", levelChanged2)
    }

    @Test
    fun `reset forces level 1 regardless of critical active`() {
        // reset() is for the face-loss path: criticalActive on the underlying
        // emitter is frozen (not updated) while hasFace=false, so the tracker
        // cannot detect a reset via criticalActive alone -- the orchestrator
        // calls reset() explicitly instead.
        val tracker = EscalationTracker(listOf(8.0, 16.0), listOf(10.0, 5.0, 4.0))
        tracker.update(criticalActive = true, now = 0.0)
        tracker.update(criticalActive = true, now = 16.0) // level 3
        tracker.reset()
        val (level, _, _) = tracker.update(criticalActive = true, now = 16.1)
        assertEquals("reset() must force the next update() to start counting from level 1 again", 1, level)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.EscalationTrackerTest"
```
Expected: FAIL — `unresolved reference: EscalationTracker`.

- [ ] **Step 3: Create `EscalationTracker.kt`**

```kotlin
package com.vitalguard.ai.drowsiness

/**
 * Ported from dms-ai-engine/services/escalation_tracker.py. Computes an
 * escalation level (1/2/3) from how long the underlying emitter's
 * criticalActive has been continuously true -- driven by TriggerEmitter's
 * hysteresis-protected criticalActive, NOT a raw score>threshold check per
 * frame.
 */
class EscalationTracker(
    private val levelUpSeconds: List<Double>,
    private val repeatIntervalSeconds: List<Double>,
) {
    init {
        require(repeatIntervalSeconds.size == levelUpSeconds.size + 1) {
            "repeatIntervalSeconds must have exactly one more entry than levelUpSeconds"
        }
    }

    private var criticalSince: Double? = null
    private var lastRepeatTime: Double? = null
    private var lastLevel: Int = 1

    /** Returns (level, repeatDue, levelChanged). */
    fun update(criticalActive: Boolean, now: Double): Triple<Int, Boolean, Boolean> {
        if (!criticalActive) {
            criticalSince = null
            lastRepeatTime = null
            val levelChanged = lastLevel != 1
            lastLevel = 1
            return Triple(1, false, levelChanged)
        }

        if (criticalSince == null) {
            criticalSince = now
            lastRepeatTime = null // no repeat yet in this episode
        }

        val elapsed = now - criticalSince!!
        val level = 1 + levelUpSeconds.count { elapsed >= it }

        val levelChanged = level != lastLevel
        lastLevel = level

        val interval = repeatIntervalSeconds[level - 1]
        // levelChanged also counts as "just repeated" -- otherwise a stale
        // lastRepeatTime (anchored to the PREVIOUS level's interval) could
        // make the next repeatDue fire only 1-2s after the level-change
        // announcement, cutting off the utterance just spoken for the new level.
        val repeatDue = levelChanged || lastRepeatTime == null || (now - lastRepeatTime!!) >= interval
        if (repeatDue) lastRepeatTime = now

        return Triple(level, repeatDue, levelChanged)
    }

    /** Called on UNKNOWN (face lost) -- forces back to level 1 regardless of
     * criticalActive, since the underlying emitter is frozen (not updated)
     * while hasFace=false and cannot itself signal a reset. */
    fun reset() {
        criticalSince = null
        lastRepeatTime = null
        lastLevel = 1
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.EscalationTrackerTest"
```
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/drowsiness/EscalationTracker.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/EscalationTrackerTest.kt
git commit -m "Port EscalationTracker from dms-ai-engine/services/escalation_tracker.py

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 7: Golden-file regression test vs `evidence_run.csv` (Tier B-1)

**Files:**
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineGoldenTest.kt`

**Interfaces:**
- Consumes: `FrameFeatures`, `DrowsinessScoreCalculator` (Task 4), `TriggerEmitter`, `TriggerSignal` (Task 5).
- Produces: nothing consumed by later tasks — this is a standalone regression guard.

This test proves the **wiring order** (calculator → emitter) matches Python's real recorded output for a scenario that actually exercises the full sustain→fire cycle, which `drowsy.mp4` does not (see Task 9 and the design doc's Tier B/C split).

- [ ] **Step 1: Write the test**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden-file regression test. Feeds the exact same synthetic scenario as
 * dms-ai-engine/main.py::run_mock_stream() (30 frames eyes-open, 40 frames
 * eyes-closed+droop(28deg), 30 frames eyes-open, 0.1s steps, t accumulated
 * via repeated += 0.1 -- replicated exactly here, not as i*0.1, to reproduce
 * the same floating-point step boundaries as the recorded reference) through
 * the ported DrowsinessScoreCalculator -> TriggerEmitter chain, and asserts
 * against checkpoints read directly from the repo's own root evidence_run.csv
 * (the recorded output of that exact Python run -- its trigger_fired column
 * shows a real 1 at t=6.5, confirming this scenario, unlike drowsy.mp4,
 * actually reaches a debounced CRITICAL fire).
 */
class DrowsinessPipelineGoldenTest {

    private data class Row(val t: Double, val eyeClosed: Boolean, val pitch: Double)

    private fun scenario(): List<Row> {
        val rows = mutableListOf<Row>()
        var t = 0.0
        repeat(30) { rows.add(Row(t, eyeClosed = false, pitch = 0.0)); t += 0.1 }
        repeat(40) { rows.add(Row(t, eyeClosed = true, pitch = 28.0)); t += 0.1 }
        repeat(30) { rows.add(Row(t, eyeClosed = false, pitch = 0.0)); t += 0.1 }
        return rows
    }

    @Test
    fun `matches evidence_run csv checkpoints`() {
        val calc = DrowsinessScoreCalculator(windowSeconds = 2.0, sampleHz = 10.0)
        val emitter = TriggerEmitter(sustainSeconds = 2.0, cooldownSeconds = 10.0)
        var triggerFiredAt: Double? = null

        for (row in scenario()) {
            val score = calc.addFrame(FrameFeatures(row.t, row.eyeClosed, row.pitch))
            val signal = emitter.update(score, row.t)
            if (signal == TriggerSignal.Critical && triggerFiredAt == null) triggerFiredAt = row.t

            // Checkpoints taken verbatim from the repo's root evidence_run.csv:
            when {
                closeTo(row.t, 2.9) -> assertEquals("t=2.9 (still eyes-open segment)", 0.000, score, 0.001)
                closeTo(row.t, 4.4) -> assertEquals("t=4.4 (mid ramp-up)", 0.863, score, 0.001)
                closeTo(row.t, 4.9) -> assertEquals("t=4.9 (saturated)", 1.000, score, 0.001)
                closeTo(row.t, 8.9) -> assertEquals("t=8.9 (recovered)", 0.000, score, 0.001)
            }
        }

        assertEquals("evidence_run.csv records trigger_fired=1 at exactly t=6.5",
            6.5, triggerFiredAt ?: -1.0, 0.001)
    }

    private fun closeTo(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.001
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessPipelineGoldenTest"
```
Expected: FAIL (the class compiles fine since Tasks 4-6 exist, but this is the first run — if it unexpectedly fails on an assertion, that is a real signal something in Tasks 4-6's port is wrong; re-check against `dms-ai-engine/services/score_calculator.py`/`trigger_emitter.py` before proceeding).

- [ ] **Step 3: Confirm it passes as written (no separate implementation step — this task only adds a test against already-ported code)**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessPipelineGoldenTest"
```
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessPipelineGoldenTest.kt
git commit -m "Add golden-file regression test vs root evidence_run.csv

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 8: Escalation × UNKNOWN integration tests (Tier B-2, new — no Python equivalent)

**Files:**
- Test: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessEscalationIntegrationTest.kt`

**Interfaces:**
- Consumes: `FacePresenceTracker`, `FacePresenceSignal` (Task 5), `EscalationTracker` (Task 6).
- Produces: nothing consumed by later tasks.

Locks in design decision D4 (hard reset to level 1 on UNKNOWN, not freeze/decay/increase) at the integration level, since no unit test — Python or Kotlin — exercises `FacePresenceTracker` and `EscalationTracker` together, and `drowsy.mp4` (Task 9) never reaches a sustained-enough face loss to exercise this either.

- [ ] **Step 1: Write the tests**

```kotlin
package com.vitalguard.ai.drowsiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New integration tests (no Python equivalent exists). Drive
 * FacePresenceTracker + EscalationTracker together the same way the
 * orchestrator (MediaPipeReplayDetectionSource, Task 9) does, without
 * needing a real FaceLandmarker/video. See
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md,
 * decisions D4 and D5.
 */
class DrowsinessEscalationIntegrationTest {

    @Test
    fun `escalation at level 2 resets to level 1 after a sustained face loss, then rebuilds from scratch`() {
        val faceTracker = FacePresenceTracker(sustainSeconds = 2.0)
        val escalation = EscalationTracker(levelUpSeconds = listOf(8.0, 16.0), repeatIntervalSeconds = listOf(10.0, 5.0, 4.0))

        // Drive escalation to level 2 while critical is active.
        escalation.update(criticalActive = true, now = 0.0)
        val (levelBeforeLoss, _, _) = escalation.update(criticalActive = true, now = 8.0)
        assertEquals(2, levelBeforeLoss)

        // Face is lost for 3 continuous seconds (>= the 2.0s sustain window).
        var unknownFired = false
        var t = 8.5
        while (t <= 11.5) {
            val signal = faceTracker.update(hasFace = false, now = t)
            if (signal == FacePresenceSignal.Unknown) {
                unknownFired = true
                escalation.reset() // exactly what the orchestrator does on this edge (decision D4)
            }
            t += 0.1
        }
        assertTrue("a 3s continuous face loss must cross the 2.0s sustain window", unknownFired)

        // Face returns; critical is still active (driver still drowsy) --
        // escalation must rebuild from level 1, NOT resume at the pre-loss level 2.
        faceTracker.update(hasFace = true, now = 11.6)
        val (levelAfterReturn, _, _) = escalation.update(criticalActive = true, now = 11.7)
        assertEquals("must restart from level 1 after reset(), not resume at the pre-loss level",
            1, levelAfterReturn)

        // And it can climb again given enough continued critical time.
        val (levelRebuilt, _, levelChangedRebuilt) = escalation.update(criticalActive = true, now = 19.7) // 11.7+8.0
        assertEquals(2, levelRebuilt)
        assertTrue(levelChangedRebuilt)
    }

    @Test
    fun `a face flicker of 1 sample amid a longer absence delays but does not permanently suppress UNKNOWN`() {
        // Documents the inherited, tested Python trade-off (decision D5): a
        // single good frame immediately cancels the absence timer
        // (FacePresenceTracker has no debounce on the "recover" side), so a
        // flicker delays -- but does not permanently prevent -- UNKNOWN from
        // eventually firing on renewed absence.
        val faceTracker = FacePresenceTracker(sustainSeconds = 2.0)
        assertNull(faceTracker.update(hasFace = false, now = 0.0))
        assertNull(faceTracker.update(hasFace = false, now = 1.0))
        // Single good frame at t=1.5 resets the absence timer entirely.
        assertNull(faceTracker.update(hasFace = true, now = 1.5))
        // Absence resumes -- must count a fresh 2.0s from here, not resume the old timer.
        assertNull(faceTracker.update(hasFace = false, now = 1.6))
        assertNull("only 1.9s of fresh continuous absence elapsed -- must not have fired yet",
            faceTracker.update(hasFace = false, now = 3.5))
        assertEquals(FacePresenceSignal.Unknown, faceTracker.update(hasFace = false, now = 3.7))
    }
}
```

- [ ] **Step 2: Run to verify it passes**

```bash
./gradlew.bat testDebugUnitTest --tests "com.vitalguard.ai.drowsiness.DrowsinessEscalationIntegrationTest"
```
Expected: PASS. (No separate "make it pass" implementation step — Tasks 5-6 already implement everything this test drives; if it fails, that means Task 5 or 6 has a defect, not that new production code is needed here.)

- [ ] **Step 3: Commit**

```bash
git add aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/drowsiness/DrowsinessEscalationIntegrationTest.kt
git commit -m "Add escalation x UNKNOWN integration tests (new, no Python equivalent)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 9: Rewrite `MediaPipeReplayDetectionSource` — orchestrate the real pipeline

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MediaPipeReplayDetectionSource.kt` (full rewrite of `handleResult()`/`runIfPresent()`)

**Interfaces:**
- Consumes: everything from Tasks 1-6 (`DrowsinessPipelineConfig`, `blinkScore`, `BlinkStateTracker`, `HeadPose`, `DrowsinessScoreCalculator`, `FrameFeatures`, `TriggerEmitter`, `TriggerSignal`, `FacePresenceTracker`, `FacePresenceSignal`, `EscalationTracker`). Also `FaceLandmarkerClient` (unchanged) and `TriggerPayload`/`TriggerFeatures`/`DistractionInfo` (unchanged, `com.vitalguard.ai` package).
- Produces: same public surface as before — `class MediaPipeReplayDetectionSource(context, onPayload, onFrameDecoded)` with `fun runIfPresent(videoFile: File)` and `fun close()`. `VitalGuardMonitorService` (Task 10) consumes this unchanged.

This task has two parts: **(A)** an on-device empirical check to determine whether `FaceLandmarkerResult.facialTransformationMatrixes()`'s flat `float[16]` needs transposing before `HeadPose.extractPitchDeg()` (Task 3's kdoc flags this as the highest-risk, device-dependent unknown — do not skip this check), then **(B)** finalizing the rewrite with that answer locked in.

- [ ] **Step 1: Add temporary dual-path pitch logging to decide the matrix layout**

Edit `handleResult` temporarily (this logging is removed in Step 4) to compute pitch both as-is and transposed, so both can be compared on-device against known-good values:

```kotlin
// TEMPORARY -- removed in Step 4 once the correct layout is confirmed.
private fun transpose4x4(m: FloatArray): FloatArray {
    val t = FloatArray(16)
    for (row in 0 until 4) for (col in 0 until 4) t[col * 4 + row] = m[row * 4 + col]
    return t
}
```

Add this logging inside `handleResult` right after extracting `matrix` (see Step 5's final version for exact placement):
```kotlin
if (matrix != null) {
    Log.d(TAG, "MATRIX_PROBE t=$now asIs=${HeadPose.extractPitchDeg(matrix)} transposed=${HeadPose.extractPitchDeg(transpose4x4(matrix))}")
}
```

- [ ] **Step 2: Build, install, and run against `drowsy.mp4` to collect both candidate pitch sequences**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export MSYS_NO_PATHCONV=1
ADB="/c/Users/Admin/AppData/Local/Android/Sdk/platform-tools/adb.exe"
cd aaos-cockpit-app
./gradlew.bat assembleRelease -q
"$ADB" install -r "app/build/outputs/apk/release/app-release.apk"
"$ADB" root
"$ADB" shell setenforce 0
"$ADB" push "../dms-ai-engine/out/drowsy.mp4" /data/local/tmp/replay_test.mp4
"$ADB" shell chmod 644 /data/local/tmp/replay_test.mp4
"$ADB" logcat -c
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity
sleep 5
"$ADB" logcat -d -s MediaPipeReplayDetection:D | grep MATRIX_PROBE
```

- [ ] **Step 3: Compare both candidate sequences against `dms-ai-engine/out/evidence_drowsy_fresh_build.csv`'s already-known-correct pitch trajectory**

Reference checkpoints (from `evidence_drowsy_fresh_build.csv`, regenerated from a freshly-built Docker image — do not use older cached image tags):

| t (s) | expected pitch (deg) |
|---|---|
| ~0.00 | -3.9 |
| ~1.20 | 7.1 |
| ~2.17 | 21.8 |

Whichever of `asIs`/`transposed` in the logcat output is within ~±3° of these three checkpoints (allow this tolerance for Android/desktop MediaPipe numeric differences) is the correct layout. Record which one it is — used in Step 4.

- [ ] **Step 4: Remove the temporary probe code, finalize `HeadPose` usage with the confirmed layout**

If Step 3 found `transposed` is correct, wrap every call site with `transpose4x4(matrix)` before passing to `HeadPose.extractPitchDeg`/`extractYawDeg` (keep `transpose4x4` as a private helper in this file, not temporary). If `asIs` was correct, delete `transpose4x4` entirely and call `HeadPose.extractPitchDeg(matrix)` directly. Delete the `Log.d(TAG, "MATRIX_PROBE...")` line either way.

- [ ] **Step 5: Replace the full file with the orchestrated pipeline**

```kotlin
package com.vitalguard.ai.detection.mediapipe

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Local-dev-only replacement for the Container Node -> HTTP TriggerPollClient
 * path that used to run alongside this (root CLAUDE.md's original "Trigger
 * Delivery" decision -- retired by
 * docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md, which
 * this class now fully implements). Decodes a device-local MP4 via
 * MediaMetadataRetriever at the video's own native frame rate, runs each
 * frame through [FaceLandmarkerClient], and turns sustained eye-closure +
 * head-droop into real [TriggerPayload]s using the same PERCLOS/sustain/
 * escalation math as dms-ai-engine/main.py::run_real_video() (drowsiness
 * slice only -- distraction stays hardcoded to NO_DISTRACTION, see the
 * design doc's non-goals).
 */
class MediaPipeReplayDetectionSource(
    context: Context,
    private val onPayload: (TriggerPayload) -> Unit,
    private val onFrameDecoded: (Bitmap) -> Unit = {},
) {
    private val client = FaceLandmarkerClient(
        context = context,
        onResult = ::handleResult,
        onError = { e -> Log.e(TAG, "FaceLandmarker error", e) },
    )
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
                // documented trade-off (design doc D2), not a new gap.
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
                    val bitmap = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
                Log.d(TAG, "Replay detection: finished, fed $sampledCount frames")
            } catch (e: Exception) {
                Log.e(TAG, "Replay detection failed", e)
            } finally {
                retriever.release()
            }
        }.start()
    }

    // LIVE_STREAM delivers results from MediaPipe's own internal thread pool
    // -- observed on-device coming from several distinct worker threads
    // concurrently, so all state mutation below must be serialized or the
    // sustain/escalation counters get corrupted by unsynchronized
    // read-modify-write. Wrapped in catch(Throwable) per this module's
    // "Catch Throwable" rule -- one bad frame (malformed matrix, unexpected
    // index) must not silently kill this callback thread.
    @Synchronized
    private fun handleResult(result: FaceLandmarkerResult) {
        try {
            handleResultUnsafe(result)
        } catch (t: Throwable) {
            Log.e(TAG, "handleResult failed for this frame -- continuing", t)
        }
    }

    private fun handleResultUnsafe(result: FaceLandmarkerResult) {
        val now = result.timestampMs() / 1_000.0
        val hasFace = result.faceBlendshapes().map { it.isNotEmpty() }.orElse(false)

        val faceSignal = faceTracker.update(hasFace, now)
        if (faceSignal == FacePresenceSignal.Unknown) {
            escalation.reset()
            publish(
                state = TriggerPayload.STATE_UNKNOWN,
                score = 0.0, perclos = 0.0, eyeOpenProbability = 0.0, headEulerAngleX = 0.0,
                escalationLevel = 1, reason = "lost_face",
            )
            return
        }
        if (!hasFace) return // still within FacePresenceTracker's grace window -- no payload

        val blendshapes = result.faceBlendshapes().get().first().associate { it.categoryName() to it.score() }
        val blink = blinkScore(blendshapes)
        val eyeClosed = blinkTracker.update(blink, now)

        val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()
        val pitchDeg = if (matrix != null) HeadPose.extractPitchDeg(matrix) else 0.0

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

        if (signal != null || repeatDue || levelChanged) {
            publish(
                state = state, score = score, perclos = calc.computeScore(),
                eyeOpenProbability = 1.0 - blink, headEulerAngleX = pitchDeg,
                escalationLevel = level,
                reason = when (signal) {
                    TriggerSignal.Critical -> "sustained_high_score"
                    TriggerSignal.Recovered -> "recovered"
                    null -> "unchanged"
                },
            )
        }
    }

    private fun stateForScore(score: Double): String = when {
        score >= DrowsinessPipelineConfig.ENTER_THRESHOLD -> TriggerPayload.STATE_CRITICAL
        score > DrowsinessPipelineConfig.EXIT_THRESHOLD -> TriggerPayload.STATE_WARNING
        else -> TriggerPayload.STATE_NORMAL
    }

    private fun publish(
        state: String, score: Double, perclos: Double, eyeOpenProbability: Double,
        headEulerAngleX: Double, escalationLevel: Int, reason: String,
    ) {
        onPayload(
            TriggerPayload(
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
        )
    }

    fun close() = client.close()

    companion object {
        private const val TAG = "MediaPipeReplayDetection"

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

(If Step 4 determined the matrix needs transposing, add the private `transpose4x4` helper back permanently and wrap the `matrix` argument at the one call site: `HeadPose.extractPitchDeg(transpose4x4(matrix))`.)

- [ ] **Step 6: Build and confirm no compile errors**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat assembleRelease -q
```
Expected: silent success (no output = success, per this project's convention).

- [ ] **Step 7: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/detection/mediapipe/MediaPipeReplayDetectionSource.kt
git commit -m "Rewrite MediaPipeReplayDetectionSource to orchestrate the real drowsiness pipeline

Replaces the inline simplified threshold spike with the ported
BlinkStateTracker/HeadPose/DrowsinessScoreCalculator/TriggerEmitter/
FacePresenceTracker/EscalationTracker chain, mirroring
dms-ai-engine/main.py::run_real_video()'s drowsiness-only slice. Switches
replay sampling from a fixed 1Hz to the video's native frame rate
(design doc D2). Empirically confirmed the facialTransformationMatrixes()
layout on-device against dms-ai-engine/out/evidence_drowsy_fresh_build.csv.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 10: Delete `TriggerPollClient` and its wiring in `VitalGuardMonitorService`

**Files:**
- Delete: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPollClient.kt`
- Delete: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `VitalGuardMonitorService` no longer references `TriggerPollClient`/`HttpTriggerFetcher`/`TriggerFetcher`/`FetchResult`/`CONTAINER_NODE_BASE_URL`.

Verified before this task (see design doc §5): grepping the whole `app/src` tree shows `TriggerFetcher`/`HttpTriggerFetcher`/`FetchResult`/`TriggerPollClient` are referenced **only** in the two files being deleted and in `VitalGuardMonitorService.kt` — safe to delete outright.

- [ ] **Step 1: Delete the two files**

```bash
git rm aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPollClient.kt
git rm aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt
```

- [ ] **Step 2: Edit `VitalGuardMonitorService.kt` — update the class kdoc**

Find:
```kotlin
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
```

Replace with:
```kotlin
/**
 * Foreground service hosting the on-device trigger pipeline:
 * [MediaPipeReplayDetectionSource] -> DrowsinessController/DistractionController
 * -> Climate/Voice gateways. Also keeps [ClimateOverrideReceiver] registered
 * as the dormant manual on-stage fallback (see design doc Decision 3) --
 * unrelated to the automated path below. Being a foreground service (rather
 * than a receiver tied to [MainActivity]) is what keeps this alive regardless
 * of whether the Activity is on screen -- a dynamic receiver tied only to the
 * Activity silently stopped firing once the app left the foreground
 * (confirmed on-device 2026-07-24).
 *
 * As of the alert-preferences-parked-suppression feature, this also owns a
 * [VehicleContextPollClient] (1Hz vehicle-speed poll -> [ParkedStateTracker] ->
 * fan-out to both controllers' `onParkedStateChanged`) and constructs a single
 * shared [PrefsAlertPreferencesStore] passed to every gateway/controller that
 * needs it.
 *
 * As of docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md,
 * this fully retires the earlier `TriggerPollClient` HTTP-polling path to the
 * Python Container Node (root CLAUDE.md's original "Trigger Delivery"
 * decision) -- [MediaPipeReplayDetectionSource] is now the sole trigger
 * source, no-oping when no replay file is present on the device.
 *
 * Also dynamically registers [GatewayModeReceiver] here (confirmed on-device
 * 2026-08-05 that its former manifest declaration never fired -- same
 * "Background execution not allowed" failure mode as [ClimateOverrideReceiver]'s
 * TRIGGER_ALERT), so `adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE`
 * only reaches the app while this service is already running.
 */
```

- [ ] **Step 3: Remove the `pollClient` field**

Find:
```kotlin
    private lateinit var pollClient: TriggerPollClient
    private lateinit var vehicleContextPollClient: VehicleContextPollClient
```
Replace with:
```kotlin
    private lateinit var vehicleContextPollClient: VehicleContextPollClient
```

- [ ] **Step 4: Remove the `pollClient` construction and `.start()` call**

Find:
```kotlin
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
```
Replace with:
```kotlin
        // On-device MediaPipe path (see MediaPipeReplayDetectionSource's
```
(This keeps the rest of that comment block and the `replayFile`/`replayDetectionSource` construction that already follows it — unchanged, since it already wires into `drowsinessController`/`distractionController` correctly.)

- [ ] **Step 5: Remove `pollClient.stop()` from `onDestroy()`**

Find:
```kotlin
    override fun onDestroy() {
        pollClient.stop()
        vehicleContextPollClient.stop()
```
Replace with:
```kotlin
    override fun onDestroy() {
        vehicleContextPollClient.stop()
```

- [ ] **Step 6: Remove the now-unused `CONTAINER_NODE_BASE_URL` constant**

Find:
```kotlin
        // Placeholder — replace with the room-internal network-pin's real address
        // once confirmed (Day-1 verification task, see the reconciliation design doc).
        private const val CONTAINER_NODE_BASE_URL = "http://192.168.49.2:8765"

        // Must match the filename MediaPipeReplayDetectionSource's caller (this
```
Replace with:
```kotlin
        // Must match the filename MediaPipeReplayDetectionSource's caller (this
```

- [ ] **Step 7: Build and confirm no compile errors, run the full unit test suite**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat testDebugUnitTest
```
Expected: all tests pass (the deleted `TriggerPollClientTest.kt` is gone, everything else — including `DrowsinessControllerTest`/`DistractionControllerTest`, unaffected per design decision D6 — stays green).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Retire TriggerPollClient — MediaPipeReplayDetectionSource is now the sole trigger source

Deletes the HTTP-polling path to the Python Container Node
(HttpTriggerFetcher/TriggerFetcher/FetchResult/TriggerPollClient and its
test), completing docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 11: On-device Tier C confirmation

**Files:** none (verification only).

**Interfaces:** none.

- [ ] **Step 1: Confirm the emulator (`vitalguard_aaos`) is running**

```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/Admin/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" devices -l
```
Expected: `emulator-5554  device  product:sdk_gcar_x86_64 ...`. If not running: `"/c/Users/Admin/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd vitalguard_aaos -no-snapshot -camera-front emulated &` then poll `adb shell getprop sys.boot_completed` until `1`.

- [ ] **Step 2: Build, install, push the test video, run**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
cd aaos-cockpit-app
./gradlew.bat assembleRelease -q
"$ADB" install -r "app/build/outputs/apk/release/app-release.apk"
"$ADB" root
"$ADB" shell setenforce 0
"$ADB" push "../dms-ai-engine/out/drowsy.mp4" /data/local/tmp/replay_test.mp4
"$ADB" shell chmod 644 /data/local/tmp/replay_test.mp4
"$ADB" logcat -c
"$ADB" shell am start -n com.vitalguard.ai/.MainActivity
```

- [ ] **Step 3: Verify against the concrete checkpoint table (from `evidence_drowsy_fresh_build.csv`)**

```bash
sleep 8
"$ADB" logcat -d -s MediaPipeReplayDetection:D
```

Pass/fail table (not subjective — score tolerance ±0.01, state exact match, signal exact match):

| t (s) | expected score | expected state | expected debounced signal |
|---|---|---|---|
| 0.00 | 0.000 | NORMAL | none |
| 1.20 | 0.880 | CRITICAL (instantaneous) | none (not yet sustained) |
| 2.17 | 0.998 (peak) | CRITICAL (instantaneous) | none |
| 2.23–2.77 | (no face) | — | must **not** emit UNKNOWN (gap 0.54s < 2.0s sustain) |
| 3.30 | 0.110 | NORMAL | none |

If any row mismatches by more than the stated tolerance, stop and re-check Task 9's matrix-layout decision and the orchestration wiring before proceeding — do not adjust the expected table to match a wrong result.

- [ ] **Step 4: Screenshot for visual confirmation and as evidence**

```bash
"$ADB" exec-out screencap -p > "aaos-cockpit-app/screenshot-drowsiness-port.png"
```
Open the PNG and confirm the on-screen debug overlay shows a real decoded video frame, `Eye open prob`, `PERCLOS`, `Head pitch`, and `Receiving trigger: true` — consistent with the checkpoint table above.

- [ ] **Step 5: Confirm no crash occurred**

```bash
"$ADB" logcat -d | grep -iE "FATAL|AndroidRuntime.*Exception" | grep -i vitalguard
```
Expected: no output.

(No commit for this task — it is a verification checkpoint. If a mismatch is found, fix it in the relevant earlier task and re-commit there, then re-run this task.)

---

## Task 12: Update root `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md` (repo root)

**Interfaces:** none.

- [ ] **Step 1: Update the "Trigger Delivery" section**

Find (the section starting with):
```markdown
### Trigger Delivery — Container Node → App (decided, do not revert to custom VHAL property)
The drowsiness trigger goes from the Container Node straight to the Android app over a **network pin added directly between the Container Node and the Skycraft VM** (self-service, same mechanism as adding the video-replay container — no BTC approval needed). The Container Node serves the trigger over a lightweight local HTTP/WebSocket endpoint conforming to `contracts/trigger.schema.json`; the app connects/polls directly. **This bypasses the Script Node and VHAL entirely for the trigger signal** — VHAL/Script Node is used only for the HVAC actuation direction (a standard AOSP property, not a custom one). Reason for this decision: routing the trigger through a custom VHAL property would require BTC to define a new property ID/areaId first — an external dependency with unpredictable response time. This workaround removes that blocker for the MVP.
```

Replace with:
```markdown
### Trigger Delivery — on-device Kotlin pipeline (current, supersedes the network-pin decision below)
As of `docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md`, the Android app's drowsiness trigger no longer depends on the Python Container Node at runtime. `MediaPipeReplayDetectionSource` runs MediaPipe Face Landmarker directly on-device (Kotlin), porting the same PERCLOS/head-pose/sustain/escalation math dms-ai-engine's `services/*.py` already had tested (`eye_state.py`, `head_pose.py`, `score_calculator.py`, `trigger_emitter.py`, `escalation_tracker.py` → `com.vitalguard.ai.drowsiness`). `TriggerPollClient`'s HTTP-polling path to the Container Node has been deleted.

The original network-pin architecture described below is **kept for historical context and because `dms-ai-engine/` still exists in the repo** (untouched, still buildable) — but it is no longer what the shipped Android app depends on. If a future need re-introduces a Container Node in the loop (e.g. distraction detection, which is NOT yet ported to Kotlin — see root CLAUDE.md's Distraction Detection section), revisit this decision explicitly rather than assuming it still applies.

**Original decision (superseded for drowsiness, still describes the Container Node's own standalone capability):** the drowsiness trigger could go from the Container Node straight to the Android app over a **network pin added directly between the Container Node and the Skycraft VM** (self-service, same mechanism as adding the video-replay container — no BTC approval needed). The Container Node serves the trigger over a lightweight local HTTP/WebSocket endpoint conforming to `contracts/trigger.schema.json`. **This bypasses the Script Node and VHAL entirely for the trigger signal** — VHAL/Script Node is used only for the HVAC actuation direction (a standard AOSP property, not a custom one).
```

- [ ] **Step 2: Update the "Architectural Decisions Already Locked In" bullet**

Find:
```markdown
- The drowsiness trigger is delivered via a **direct network pin between the Container Node and the Skycraft App** — never through a custom VHAL property pushed by the Script Node. This was a deliberate change to remove a BTC dependency from the MVP critical path.
```

Replace with:
```markdown
- The drowsiness trigger is computed **on-device in Kotlin** (`com.vitalguard.ai.drowsiness`, see `docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md`) — the Python Container Node is no longer a runtime dependency for drowsiness. HVAC actuation is still never a custom VHAL property pushed by the Script Node (that part of the original decision is unchanged).
- Distraction detection (hands-off-wheel, gaze-off-road) is **not yet ported** to Kotlin — it still requires the Python Container Node's `hand_tracker.py`/`distraction_score_calculator.py` if/when that feature needs to run; `MediaPipeReplayDetectionSource` currently emits `NO_DISTRACTION` unconditionally.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "Update CLAUDE.md: drowsiness trigger is now on-device Kotlin, not network-pin

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Plan self-review notes

- **Spec coverage:** every design-doc section (§4 decisions D1-D7, §5 architecture, §6 data flow, §7 error handling, §8 testing tiers A/B/C, §9 rollout) maps to a task above. One implementation-level refinement made while writing this plan: the design doc's Tier B "real-video numeric parity" bullet described a check that cannot run as a JVM unit test (MediaPipe's native library requires an Android runtime) — folded into Task 9's on-device matrix-layout verification and Task 11's Tier C confirmation instead, using the same `evidence_drowsy_fresh_build.csv` reference data.
- **Type consistency:** `TriggerSignal`/`FacePresenceSignal` (Task 5) are used identically in Tasks 7, 8, 9. `FrameFeatures`/`DrowsinessScoreCalculator` (Task 4) signatures match their use in Tasks 7 and 9. `EscalationTracker.update()`'s `Triple<Int, Boolean, Boolean>` return shape is destructured identically in Tasks 8 and 9.
- **No placeholders:** every step has literal, runnable code or an exact shell command; no "TBD"/"add error handling"/"similar to Task N" remain.
