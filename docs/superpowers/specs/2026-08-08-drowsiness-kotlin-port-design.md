# Drowsiness pipeline: complete the Python → Kotlin port, retire `TriggerPollClient`

**Date:** 2026-08-08
**Status:** Design approved, pending plan
**Branch:** dedicated feature branch, **not** `main` / the current demo branch (see "Rollout" below)

## 1. Context

`aaos-cockpit-app` currently runs **two parallel trigger sources** feeding the
same `DrowsinessController`/`DistractionController` instances
(`VitalGuardMonitorService.onCreate()`):

1. `TriggerPollClient` — polls a Python Container Node (`dms-ai-engine/`) over
   HTTP (`GET /latest-trigger`), per root `CLAUDE.md`'s "Trigger Delivery"
   decision. This is the architecture that was locked in for the CarSky demo.
2. `MediaPipeReplayDetectionSource` — an on-device Kotlin MediaPipe
   FaceLandmarker pipeline, explicitly documented in its own kdoc as a
   **"local-dev-only... spike"** that "does not revert" decision #1. It only
   computes a raw eye-blink signal with a simplified inline threshold —
   no real PERCLOS, no head-pose, no escalation levels above 1.

This is a deliberate, working, already-demoed hybrid state (confirmed on the
AAOS emulator: build → install → push test video → run → screenshot showing
real `Eye open prob` computed on-device). This spec is about finishing the
on-device side properly and retiring path (1), **not** about fixing anything
broken.

## 2. Goals

- Port the real, already-tested Python drowsiness math
  (`dms-ai-engine/services/{eye_state,head_pose,score_calculator,
  trigger_emitter,escalation_tracker}.py`) to Kotlin, 1:1, preserving tested
  behavior exactly.
- Wire it into `MediaPipeReplayDetectionSource` so it replaces the current
  simplified inline threshold, mirroring `main.py::run_real_video()`'s
  orchestration (drowsiness-only slice of it).
- Delete `TriggerPollClient`/`HttpTriggerFetcher`/`TriggerFetcher`/
  `FetchResult` and the HTTP-polling wiring in `VitalGuardMonitorService`.
- Update root `CLAUDE.md`'s "Trigger Delivery" section to reflect the new
  architecture.

## 3. Non-goals (explicitly out of scope for this piece of work)

- **Distraction detection** (gaze-off-road, hands-off-wheel via MediaPipe
  Hand Landmarker) — stays exactly as-is today: `MediaPipeReplayDetectionSource`
  keeps hardcoding `NO_DISTRACTION` in every payload. Porting
  `hand_tracker.py`/`distraction_score_calculator.py`/
  `distraction_trigger_emitter.py` and integrating a new
  `HandLandmarker` on Android is a separate, larger follow-up (new native
  model integration, not a pure math port) — not attempted here.
- **Real (live-camera) detection.** `CameraX` still cannot bind the emulator's
  front camera on x86_64 dev machines (`IllegalArgumentException: Provided
  camera selector unable to resolve a camera`, independent, already-known,
  not root-caused). This work only changes the Replay-file path
  (`/data/local/tmp/replay_test.mp4`); no `LiveDetectionSource` exists yet, so
  there is nothing to keep in sync for now (see decision D1 below for the
  guard against future drift).
- **Deleting the Python `dms-ai-engine`.** It stays in the repo, untouched.
  Nothing here requires removing it — only the *Android app's* runtime
  dependency on it via HTTP polling goes away.

## 4. Key design decisions (with evidence — nothing here is a guess)

| # | Decision | Evidence |
|---|---|---|
| D1 | New `DrowsinessPipelineConfig.kt` is the single source of truth for every tunable constant (thresholds, sustain/cooldown, escalation timings, calibration window). Any future `LiveDetectionSource` **must** reuse it. | No live source exists yet (confirmed: `find -iname "*Live*"` empty) — this is a guard against *future* drift, not a fix for an existing one. |
| D2 | Sampling rate for the Replay path: **use the video's own native frame rate** (query via `MediaMetadataRetriever`, fall back to 30fps if unavailable/invalid — mirroring `main.py`'s own `fps = 30.0 if not finite/positive` guard), **not** a fixed Hz. | Traced `main.py::run_real_video()`: every decoded frame is fed straight into `calc.add_frame()`, no downsampling anywhere. `sample_hz=10.0` only sizes the calculator's window (`max_samples = window_seconds * sample_hz = 20`) — it does **not** throttle the input rate. Confirmed empirically: re-ran `drowsy.mp4` through a freshly-built Docker image (`vital-guard-dms:fresh-check`) and it produced exactly 100 rows for a 100-frame/30fps/3.333s file. A fixed-Hz Kotlin replay (1Hz *or* my earlier-proposed 10Hz) would **not** reproduce this reference behavior — native-fps is the only correct match. |
| D3 | `EscalationTracker`/`TriggerEmitter`/`FacePresenceTracker` timing is **always real elapsed seconds** (`now: Double`, video timestamp), never a sample index or frame count. | `escalation_tracker.py::update(critical_active, now)` and its tests (`test_continues_through_a_warning_dip_without_resetting` etc.) use explicit second-valued `now`. This makes D2's rate choice safe: changing feed rate cannot change escalation timing as long as `now` is real time. |
| D4 | On face loss (`FacePresenceTracker` → `UNKNOWN`): **hard reset** `EscalationTracker` to level 1 (`reset()`), and the existing `DrowsinessController`/`DistractionController` revert-to-baseline behavior (already shipped) stops any active alert. **Not** freeze, **not** decay, **not** increase-for-danger. | `main.py` lines 253-265 call `drowsy_escalation.reset()` on the `UNKNOWN` edge; `escalation_tracker.py::reset()`'s own test (`test_reset_forces_level_1_regardless_of_critical_active`) locks this in. Matches root `CLAUDE.md`'s mandatory Fallback rule ("never fabricate an alert from missing data") verbatim. |
| D5 | `FacePresenceTracker`'s asymmetric hysteresis (enter `UNKNOWN` needs ≥2.0s continuous absence; recover to `PRESENT` fires on a single good frame) is ported **as-is**, no added symmetric debounce. | Explicitly tested in Python (`test_face_presence_unknown_fires_once_after_sustained_loss`, `test_face_presence_brief_loss_under_sustain_window_emits_nothing`, `test_face_presence_present_fires_once_on_face_returning`) — an intentional, shipped "alarm slow, recover fast" trade-off, not an oversight. Changing it would be a new, untested design decision this spec explicitly declines to make under deadline pressure. |
| D6 | `TriggerPayload.STATE_UNKNOWN` needs **no schema or controller change**. | Verified, not assumed: `DrowsinessControllerTest.kt` already has 3 passing cases exercising `STATE_UNKNOWN` (lines 65, 163, 173 — reverts baseline immediately, clears applied climate level, re-applies fresh after recovery). What changes is only that the Kotlin *emitter* will finally produce this state for real. |
| D7 | Baseline pitch calibration (first `BASELINE_CALIBRATION_SECONDS = 1.0s`) is ported as-is. A non-neutral starting pose during that window is a **documented known limitation**, not silently ignored and not fixed here. | Python has no test for this scenario either (checked `test_dms.py` in full) — same treatment already used elsewhere in this project for disclosed-but-unvalidated behavior (e.g. the 0.55/0.25/0.20 weights). |

## 5. Architecture & components

New package `com.vitalguard.ai.drowsiness`:

| File | Ported from | Responsibility |
|---|---|---|
| `DrowsinessPipelineConfig.kt` | *(new)* | Single source of truth: enter/exit thresholds (0.85/0.50), sustain/cooldown (2.0s/10.0s), PERCLOS window (2.0s @ sample_hz=10 → 20-sample deque), escalation (`levelUpSeconds=[8,16]`, `repeatIntervalSeconds=[10,5,4]`), `BASELINE_CALIBRATION_SECONDS=1.0`. |
| `BlinkStateTracker.kt` | `eye_state.py` | `blinkScore()` + 2-threshold hysteresis (close 0.55 / reopen 0.35). |
| `HeadPose.kt` | `head_pose.py` | Rotation-matrix → (pitch, yaw) degrees, pure functions. |
| `DrowsinessScoreCalculator.kt` | `score_calculator.py` | `FrameFeatures` + sliding-window PERCLOS composite (0.55/0.25/0.20), `calibrateBaseline()`. |
| `TriggerEmitter.kt` | `trigger_emitter.py` | `TriggerEmitter` (enter/exit/sustain/cooldown → CRITICAL/RECOVERED) + `FacePresenceTracker` (→ UNKNOWN/PRESENT), same file grouping as the Python source. |
| `EscalationTracker.kt` | `escalation_tracker.py` | Level 1/2/3, repeat interval, `reset()`. |

**Modified:** `MediaPipeReplayDetectionSource.kt` — `handleResult()`/`runIfPresent()`
rewritten to orchestrate the classes above (see §6), replacing the current
`consecutiveHigh`/`consecutiveLow` inline logic. Class name and the
replay-file gate (`/data/local/tmp/replay_test.mp4` existence check in
`VitalGuardMonitorService`) are unchanged. `source` field in emitted
payloads renamed from `"on-device-replay-spike"` to `"on-device-kotlin"`
(cosmetic only — `TriggerPayload.source` is a free-form `String`, not an
enforced enum anywhere in the Kotlin code, so this is non-breaking).

**Deleted:** `TriggerPollClient.kt`, `TriggerPollClientTest.kt`, the
`HttpTriggerFetcher`/`TriggerFetcher`/`FetchResult` types (in
`TriggerPollClient.kt`), and the `pollClient` field + `.start()`/`.stop()`
calls in `VitalGuardMonitorService.kt`. (Verify via grep before deleting that
nothing else references these types — expected to be isolated, not yet
double-checked.)

## 6. Data flow (per decoded frame, at real timestamp `t` seconds)

Mirrors `main.py::run_real_video()`, drowsiness slice only:

```
frame → FaceLandmarker.detectAsync()
  → hasFace = faceBlendshapes present and non-empty?
       (previously: empty list silently read as avgBlink=0f / "eyes open" — FIXED here)
  → faceSignal = FacePresenceTracker.update(hasFace, t)
       if "UNKNOWN": escalationTracker.reset()
                     → emit TriggerPayload(state=UNKNOWN, score=0, reason="lost_face")
                     → return (no further processing this frame)
  → if hasFace:
       eyeClosed = BlinkStateTracker.update(blinkScore, t)
       pitchDeg  = HeadPose.extractPitchDeg(transformationMatrix)
       (first BASELINE_CALIBRATION_SECONDS: accumulate pitch samples;
        once elapsed, calc.calibrateBaseline(average) — once per run)
       score     = calc.addFrame(FrameFeatures(t, eyeClosed, pitchDeg))
       signal    = triggerEmitter.update(score, t)              // CRITICAL/RECOVERED/null
       state     = stateForScore(score)                          // instantaneous 0.85/0.50 read
       (level, repeatDue, levelChanged) = escalationTracker.update(triggerEmitter.criticalActive, t)
       if (signal != null || repeatDue || levelChanged):
           emit TriggerPayload(state, score, perclos=calc.computeScore(),
                                eyeOpenProbability=1-blinkScore, headEulerAngleX=pitchDeg,
                                escalationLevel=level, ...)
       else: no payload this frame (publish gate — not every frame publishes)
  → if !hasFace and faceSignal != "UNKNOWN" (still within the 2.0s grace window):
       no payload, no state change
```

Deliberately preserved subtlety: recovering from `UNKNOWN` (`PRESENT` edge)
does **not** itself force a payload — only `signal`/`repeatDue`/`levelChanged`
on that same frame do. The prior `UNKNOWN` payload already told the app to
revert to baseline, so no gap exists; changing this would be a new,
untested behavior.

## 7. Error handling

- `handleResult()` (called from MediaPipe's own callback thread) gets wrapped
  in `catch (Throwable, ...)`, matching the existing "Catch Throwable" pattern
  already used in `VitalGuardMonitorService`'s replay-source init — one bad
  frame must not silently kill the callback thread.
- All `Optional`/list access on `FaceLandmarkerResult` stays defensive
  (`.orElse(...)`, `firstOrNull()`), consistent with current style.
- Known, explicitly-documented (not silently swallowed) limitations:
  - Baseline calibration assumes a neutral pose in the first 1.0s (D7).
  - `FacePresenceTracker`'s asymmetric hysteresis (D5) can, in principle,
    delay entering `UNKNOWN` if presence flickers right at the 2.0s boundary
    — inherited from Python, not new.
  - Native-fps replay sampling (D2) means a long test video (e.g. the
    184s `full-stream-facemp4.mp4`) issues many more `MediaMetadataRetriever.
    getFrameAtTime()` seeks than the current 1Hz spike — acceptable for an
    offline dev/test replay path (already documented as "not a real-time
    benchmark" in `EMULATOR_TESTING_GUIDE.md" §10.2), but noted as a
    possible future slowness concern for long clips.

## 8. Testing plan

**Tier A — direct 1:1 port of existing Python unit tests → JUnit** (behavior
already validated, low risk):
`test_head_pose.py` → `HeadPoseTest.kt`, `test_eye_state.py` →
`BlinkStateTrackerTest.kt`, `test_escalation_tracker.py` (incl. `reset()`) →
`EscalationTrackerTest.kt`, `test_dms.py`'s `TriggerEmitter`/
`FacePresenceTracker` cases → `TriggerEmitterTest.kt`/`FacePresenceTrackerTest.kt`.

**Tier B — golden-file regression tests** (catches wiring-order bugs the
isolated unit tests above can't):
- **Synthetic scenario, already has a golden file:** the repo's own
  `evidence_run.csv` (root, 101 rows) is the recorded output of
  `main.py --mock`'s scenario (30 frames open / 40 frames closed+droop(28°) /
  30 frames open, 0.1s steps) — and its `trigger_fired` column shows a real
  `1` at t=6.5, confirming this scenario actually exercises the full
  sustain→fire cycle end-to-end (unlike `drowsy.mp4`, see below). Feed the
  identical synthetic `FrameFeatures` sequence through the ported Kotlin
  chain (`DrowsinessScoreCalculator` → `TriggerEmitter` → `EscalationTracker`)
  and assert row-for-row parity against this file.
- **Extended synthetic scenario (new, no Python equivalent exists):** craft a
  longer synthetic sequence that (a) crosses the 8s/16s escalation
  thresholds to reach level 2 and 3, then (b) inserts a ≥2.0s face-absent
  gap, asserting `EscalationTracker.reset()` fires and level returns to 1 —
  this directly tests D4, which no existing test (Python or Kotlin) covers
  in integration. Written and documented as new, not a port.
- **Real-video numeric parity:** re-generated ground truth for the current
  `dms-ai-engine/out/drowsy.mp4` via a **freshly-built** Docker image
  (`vital-guard-dms:fresh-check` — do **not** trust older cached tags,
  confirmed one is stale from before the distraction feature existed):
  `dms-ai-engine/out/evidence_drowsy_fresh_build.csv`. Feed the same video
  through the Kotlin replay pipeline and assert score/pitch/blink match
  within ±0.01 at every sampled frame. **Important finding baked into this
  test's assertions:** this specific video never produces a debounced
  `signal` (CRITICAL/RECOVERED) at all — score crosses 0.85 at t≈1.20s but a
  face-loss gap at t=2.23-2.77s resets the sustain timer before 2.0s
  continuous elapses, so `TriggerEmitter.update()` never returns non-null
  anywhere in this clip (verified: `grep RECOVERED`/checking the `signal`
  column both come back empty on every row). The test therefore asserts
  `state`/score/pitch parity and that `signal` stays `null` throughout and
  that `UNKNOWN` never fires (the 0.54s gap is under the 2.0s sustain) — it
  is **not** a suitable fixture for testing sustain/escalation firing (Tier B
  synthetic tests above cover that instead).
- `DrowsinessControllerTest.kt`/`DistractionControllerTest.kt`: re-run
  unchanged, confirm still green (contract unaffected, per D6).

**Tier C — on-device confirmation (emulator, visual + log):** re-run the
existing build → install → push `replay_test.mp4` → run → screenshot flow.
Concrete pass/fail (not subjective), using `drowsy.mp4` and the table below
(from `evidence_drowsy_fresh_build.csv`):

| t (s) | expected score (±0.01) | expected state | expected `signal` |
|---|---|---|---|
| 0.00 | 0.000 | NORMAL | — |
| 1.20 | 0.880 | CRITICAL (instantaneous) | none (not yet sustained) |
| 2.17 | 0.998 (peak) | CRITICAL (instantaneous) | none |
| 2.23–2.77 | (no face) | — | must **not** emit UNKNOWN (gap < 2.0s) |
| 3.30 | 0.110 | NORMAL | — |

Debounced-CRITICAL firing and escalation levels are **not** re-verified
visually on the emulator with this clip — that's Tier B's job. Using this
video for that purpose would be an unfalsifiable/subjective check.

## 9. Rollout

- All of the above happens on a **dedicated feature branch**, not `main` and
  not whatever branch the working hybrid demo currently lives on — the
  hybrid state stays available as a safe fallback for the (~2-day-out)
  Round 2 deadline if this work runs long.
- Once Tier A/B/C all pass on the branch: update root `CLAUDE.md`'s "Trigger
  Delivery" section and "Architectural Decisions Already Locked In" list to
  describe the new on-device-only architecture, and note the Python
  Container Node / network-pin path as no longer the Android app's runtime
  dependency (still present in-repo, still buildable, just not polled).

## 10. Follow-ups (explicitly deferred, not silently dropped)

- Distraction detection port (Hand Landmarker integration) — separate,
  larger piece of work, own design pass.
- Live-camera `LiveDetectionSource` — blocked on the unrelated CameraX
  emulator bug; when unblocked, must reuse `DrowsinessPipelineConfig.kt`
  (D1).
- Long-video replay performance under native-fps sampling (D2) — not
  measured yet for multi-minute clips; revisit if it becomes a real dev-loop
  pain point.
