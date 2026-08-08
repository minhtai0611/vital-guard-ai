# Live camera drowsiness detection (Camera2, coexisting with replay-file)

**Date:** 2026-08-08
**Status:** Design approved, pending plan
**Branch:** dedicated feature branch, **not** `main`

## 1. Context

Today `MediaPipeReplayDetectionSource` is the sole trigger source
(`docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md`,
`docs/superpowers/plans/2026-08-08-drowsiness-kotlin-port.md`). It only
proves the pipeline against a bundled MP4 (`/data/local/tmp/replay_test.mp4`)
— it is a **test harness, not a usable product**: it never looks at a real
driver. This spec covers making the drowsiness pipeline consume a real
camera feed on the `vitalguard_aaos` dev AVD, while keeping the replay-file
path as a demo-stage fallback (root `CLAUDE.md`'s Demo Script contingency:
"if a live component fails during the demo, fall back to a simulated
video/log").

This is the first of two independent follow-on directions the user asked to
develop; the second (an in-app Settings UI for alert volume/climate/voice
customization) is a separate sub-project with its own spec, brainstormed
after this one.

### 1.1 Root-cause investigation done during brainstorming (not guesses)

The prior spec's non-goals section flagged live-camera detection as blocked
on `CameraX` throwing `IllegalArgumentException: Provided camera selector
unable to resolve a camera`, "independent, already-known, not root-caused."
Root-caused now, on-device, in two layers:

1. **`vitalguard_aaos`'s AVD `config.ini` hard-codes `hw.camera.front=none`.**
   `adb shell dumpsys media.camera` confirmed only one camera existed
   (Back-facing) before any code ran. Changed to
   `hw.camera.front=emulated` + cold boot (not just `adb reboot` — AVD
   hardware config only applies on a fresh QEMU boot) → `dumpsys` now shows
   both Front and Back cameras present at the HAL/Camera2 level.
2. **`CameraX` still cannot use either camera on this device profile,
   independent of (1).** After the front camera existed, `bindToLifecycle()`
   still threw the identical exception. Logcat's real reason:
   `CameraValidator: Verifying camera lens facing on emulator_car64_x86_64,
   lensFacingInteger: null` — CameraX's internal validator reads
   `CameraCharacteristics.LENS_FACING` and gets `null` on this device's
   camera HAL (a known class of AAOS-emulator/CameraX incompatibility), so
   CameraX treats every camera as unusable regardless of which
   `CameraSelector` is requested. This is a CameraX-library-level
   incompatibility, not fixable by app code choosing a different selector.

**Decision: bypass CameraX entirely, use the Camera2 API directly**
(`CameraManager`/`CameraDevice`/`ImageReader`). Camera2 does not require
`LENS_FACING` to be non-null to open a camera by ID — only CameraX's
higher-level validator does.

3. **The AVD's `hw.camera.front=emulated` shows a synthetic static/animated
   image, not a human face.** MediaPipe Face Landmarker needs an actual
   face to produce meaningful blendshapes/transformation matrices, so this
   default would make on-device functional testing meaningless (it would
   only prove plumbing, never real detection accuracy). Checked
   `emulator.exe -webcam-list`: this dev machine has a real webcam
   (`webcam0`, "ACER HD User Facing", NV12) available for passthrough. The
   AVD's `hw.camera.front` should be set to `webcam0` (not `emulated`) for
   any testing that needs a real face — this requires the same
   edit-config.ini-then-cold-boot procedure as (1) and should be documented
   as the testing setup, not assumed obvious.

4. **A cross-language, pre-existing correctness bug was found while
   reasoning about live-camera frame timing (not introduced by this spec,
   but must be fixed before live camera makes it worse — see §4 D1 and
   §7 Task 0).**

## 2. Goals

- Add a real-time, on-device Camera2-based drowsiness detection source
  (`MediaPipeLiveDetectionSource`) that reuses 100% of the already-tested
  scoring/trigger/escalation logic (`com.vitalguard.ai.drowsiness`), with
  zero duplicated business logic against the replay path.
- Make the two sources switchable via a persisted `DetectionBackendMode`
  (mirrors the existing `GatewayMode`/`GatewayModeReceiver` pattern exactly),
  read once at `VitalGuardMonitorService.onCreate()`. `REPLAY_FILE` stays the
  default (safety: proven, already-verified behavior does not change for
  anyone who doesn't explicitly opt in).
- Fix the pre-existing PERCLOS-window timing bug (§4 D1) as a prerequisite,
  since it directly threatens correctness under live camera's irregular
  frame timing.
- Remove `MainActivity`'s dead CameraX preview code, since it was written
  as "somewhere for a future live-camera mode to plug in" and this spec is
  that future mode arriving, via a different mechanism (Camera2, not
  CameraX).

## 3. Non-goals

- **Settings UI** for switching modes or tuning anything — separate spec
  (Direction 2). For now, mode switching stays a dev-only broadcast
  (`adb shell am broadcast`), exactly like `GatewayModeReceiver` today.
- **Distraction detection** (hands-off-wheel/gaze-off-road) — untouched.
  `MediaPipeLiveDetectionSource` hardcodes `NO_DISTRACTION`, same as replay.
- **Real target hardware / production device.** This spec targets the
  `vitalguard_aaos` dev AVD only (confirmed with the user: this AVD is a
  local dev/test stand-in, not confirmed identical to the actual Skycraft
  VM used on-stage in the CarSky room). Code is written in a way that
  doesn't *preclude* real hardware (no AVD-specific hacks in the main
  code path, only in the documented fallback branches), but is not verified
  against it.
- **Auto-reconnect on camera disconnect mid-session.** Documented limitation
  (§6), not silently missing.
- **Fixing CameraX itself, or `MainActivity`'s camera-permission-denied UX
  beyond what exists today.**
- **Re-tuning the 0.85/0.55/0.25/0.20 constants** in response to Task 0's
  window fix. The fix makes the *window* correct; whether the existing
  threshold constants are still the right numbers against a correctly-timed
  window is a follow-up empirical question (re-run against
  `evidence_run.csv`-equivalent data and eyeball it), not blocked on here.

## 4. Key design decisions (with evidence)

| # | Decision | Evidence |
|---|---|---|
| D1 | **`DrowsinessScoreCalculator`'s sliding window must evict by real elapsed time (`now - frame.timestamp > windowSeconds`), not by a fixed sample count.** Fixed as a prerequisite (Task 0), applied to both replay and live paths via the shared core. | Traced both `score_calculator.py` (`deque(maxlen=window_seconds*sample_hz)`) and `DrowsinessScoreCalculator.kt` (`ArrayDeque` + `while (window.size > maxSamples) removeFirst()`): both evict by **count**, sized assuming `sample_hz=10.0` (`maxSamples=20`). But both `main.py::run_real_video()` and `MediaPipeReplayDetectionSource.kt` (decision D2 in the prior spec) deliberately feed frames at **native video fps** (~30fps for `drowsy.mp4`), not a throttled 10Hz. At 30fps, 20 samples ≈ 0.67s of real time, not the intended 2.0s `WINDOW_SECONDS`. This is a **pre-existing, cross-language latent bug** (not introduced by this spec) — `FrameFeatures.timestamp: Double` already exists and is unused for eviction. Confirmed with the user this needs fixing now, not deferred, because live camera's frame arrival is irregular (autofocus, thermal throttling, backpressure drops) — a fixed-*count* window's real-time span would vary unpredictably frame to frame, unlike replay's at-least-*consistent* (if wrong) ~0.67s. Accepted consequence: this changes computed scores on `drowsy.mp4`, so the golden-file test (`DrowsinessPipelineGoldenTest` vs `evidence_run.csv`) and Task 11's on-device checkpoint table both need re-baselining as part of the same change, not after. |
| D2 | Architecture: extract the per-frame orchestration in `MediaPipeReplayDetectionSource.handleResultUnsafe()`/`buildPayload()` into a new `DrowsinessDetectionCore` class, owned identically by both `MediaPipeReplayDetectionSource` (unchanged behavior, now delegates) and the new `MediaPipeLiveDetectionSource`. Rejected: (a) copy-paste duplicate class — DRY violation, drift risk; (c) a full `FrameSource` interface abstraction — YAGNI, no test-driven need for polymorphism here (this whole layer is already outside JVM-unit-test coverage per existing precedent), and higher effort than the hackathon timeline justifies for exactly two concrete sources. | User explicitly chose this 3-way tradeoff after seeing all three during brainstorming. |
| D3 | `MediaPipeLiveDetectionSource` owns all Camera2-specific threading (a dedicated `HandlerThread`) and the backpressure decision (single frame in flight; `ImageReader.setOnImageAvailableListener` drops/closes a new frame immediately if the previous `detectAsync()` result hasn't returned, never blocks). The `@Synchronized` guard currently on `MediaPipeReplayDetectionSource.handleResult()` moves onto the equivalent method in `DrowsinessDetectionCore`, since the core is now the sole owner of the mutable trackers both sources call into concurrently from their own callback threads. | User's point: ImageReader callbacks must never block waiting on MediaPipe, or the camera pipeline stalls. Matches the existing, already-shipped comment in `MediaPipeReplayDetectionSource` about `LIVE_STREAM` delivering results from MediaPipe's own thread pool "coming from several distinct worker threads concurrently." |
| D4 | Camera ID / lens-facing resolution: query `CameraCharacteristics.LENS_FACING` per ID first; if **no** ID reports `FRONT` (expected on this AVD, given D-context finding #2 above may extend to raw Camera2 characteristics too — not yet empirically confirmed whether Camera2's `LENS_FACING` is *also* null on this HAL, only that CameraX's read of it is), fall back to the AOSP emulator convention (camera ID `"1"` = front, `"0"` = back), logging which path was taken. On real hardware where `LENS_FACING` reads normally, the primary branch resolves and the fallback never triggers. | Root CLAUDE.md's "self-resolve, query at runtime, log when a fallback happens" convention, same pattern already used for VHAL `areaId`/min/max. |
| D5 | YUV_420_888 → Bitmap conversion via NV21 byte assembly → `YuvImage.compressToJpeg()` → `BitmapFactory.decodeByteArray()`, not a hand-rolled pixel-format conversion. | Deliberate MVP trade-off: simpler, far less error-prone than manual color-space math; costs one JPEG encode/decode round-trip of latency/quality. Documented here explicitly as a trade-off, not an oversight — first place to optimize if live-camera latency becomes a problem. |
| D6 | `DetectionBackendMode` (`LIVE_CAMERA`/`REPLAY_FILE`) is read **once**, at `VitalGuardMonitorService.onCreate()`. Switching via broadcast persists to `SharedPreferences` and takes effect on the **next** service (re)start, not hot-swapped mid-session. Default: `REPLAY_FILE`. | Mirrors `GatewayModeStore`/`GatewayModeReceiver` exactly (already-shipped, already-understood pattern) — avoids new teardown/rebuild-state complexity for a live mode switch that has no demonstrated need yet. Direct consequence, stated explicitly per user's point: since only one branch of the mode `when` ever executes per process lifetime, at most one `FaceLandmarkerClient` (hence one loaded native model) exists at a time — never two competing for CPU. |
| D7 | On sustained camera failure (disconnect/error mid-session), `MediaPipeLiveDetectionSource` calls the core's existing face-loss/`UNKNOWN` path **once**, then stops feeding frames. No auto-reconnect attempt. | Root CLAUDE.md's FSM Fallback rule: "never fabricate an alert from missing data" / never leave the FSM frozen on a stale non-`UNKNOWN` state. Auto-reconnect is explicitly out of scope (§3), not a silent gap. |
| D8 | `MainActivity`'s CameraX `Preview`/`PreviewView`/`ImageAnalysis`/`bindToLifecycle()` code is deleted. The CAMERA runtime-permission request (still needs an Activity) stays. Frame visibility on screen is served by the existing `onFrameDecoded` → `DebugOverlayState.lastFrame` → `replayFramePreview` `ImageView` mechanism, which `MediaPipeLiveDetectionSource` feeds identically to how the replay source already does. | This dead code's own kdoc says it exists only as "somewhere for a future live-camera `DetectionBackendMode` to plug in" — this spec is that mode, via Camera2 not CameraX, so the CameraX scaffold is now not just unused but actively misleading (a future reader could assume it's the live-camera path). |

## 5. Architecture & data flow

```
                              ┌─ REPLAY_FILE ──► MediaPipeReplayDetectionSource
                              │                   (MediaMetadataRetriever decode loop,
                              │                    unchanged except delegating to the
                              │                    extracted core)
DetectionBackendMode ─(switch)┤
  (persisted SharedPreferences,
   read once in                └─ LIVE_CAMERA ───► MediaPipeLiveDetectionSource
   Service.onCreate())                             (Camera2: CameraManager/CameraDevice/
                                                     ImageReader on a dedicated
                                                     HandlerThread, drop-if-busy backpressure)
                                                          │
                        exactly one of the two owns a FaceLandmarkerClient at a time (D6)
                                                          │
                                                          ▼
                                              DrowsinessDetectionCore
                                    (blinkTracker / calc / triggerEmitter /
                                     faceTracker / escalation -- extracted from
                                     today's handleResultUnsafe(), now the
                                     single owner of this mutable state,
                                     @Synchronized per D3)
                                                          │
                                          onTelemetry (every frame) / onPayload (gated, unchanged)
                                                          │
                                                          ▼
                                    DebugOverlayState / DrowsinessController / DistractionController
                                              (unchanged)
```

## 6. Error handling (explicit, not silent)

| Situation | Behavior |
|---|---|
| `CAMERA` permission not granted when `VitalGuardMonitorService.onCreate()` runs `LIVE_CAMERA` mode (possible: `BootCompletedReceiver` can start the service before the user ever opens `MainActivity`) | Log + skip constructing `MediaPipeLiveDetectionSource` entirely. Service continues with no detection running — identical precedent to today's "no replay file present" skip-path. No crash. |
| Camera already in use by another process/app (`CameraAccessException`) at open time | Catch (`Throwable`, per this module's established "Catch Throwable" rule for CameraX/Car APIs), log, skip. No detection running. No crash. |
| Camera disconnects or errors mid-session (`CameraDevice.StateCallback.onDisconnected`/`onError`) | Close the session cleanly, log, call the core's face-loss/`UNKNOWN` path once (D7), stop. No auto-reconnect (§3). |
| `LENS_FACING` unresolvable for every camera ID | Fall back to the AOSP emulator ID convention, log that the fallback path was used (D4). |
| A single captured frame fails to convert/decode | Same per-frame `catch (Throwable)` + continue pattern already used in `DrowsinessDetectionCore` (moved from `MediaPipeReplayDetectionSource.handleResult`) — one bad frame must not kill the capture thread. |

## 7. Rollout order

1. **Task 0 (prerequisite): fix `DrowsinessScoreCalculator`'s window to evict by real elapsed time.** Re-run/re-baseline the golden-file test and Task 11's on-device checkpoint table against the corrected numbers. Land and verify this alone before touching camera code, so a live-camera bug is never confused with a pre-existing scoring bug.
2. Extract `DrowsinessDetectionCore` out of `MediaPipeReplayDetectionSource` with **zero behavior change** — verify via the existing 47 drowsiness-package tests plus an on-device replay-path re-run (same checkpoint-table method as Task 11), to isolate refactor risk from new-feature risk.
3. Add `DetectionBackendMode`/`PrefsDetectionBackendModeStore`/`DetectionBackendModeReceiver` (mechanical, mirrors `GatewayMode` exactly).
4. Build `MediaPipeLiveDetectionSource` (Camera2 capture, YUV→Bitmap, backpressure) wired to the same `DrowsinessDetectionCore`.
5. Wire into `VitalGuardMonitorService`'s `when`; delete `MainActivity`'s dead CameraX code.
6. On-device verification: cold-boot the AVD with `hw.camera.front=webcam0` (real face passthrough, not `emulated`'s synthetic image — §1.1 finding #3), switch to `LIVE_CAMERA` via broadcast, confirm the debug overlay updates live off a real face (open/close eyes, tilt head) with no crash.

## 8. Testing

- Task 0's fix and the `DrowsinessDetectionCore` extraction are both JVM-unit-testable (pure logic, no MediaPipe/Camera dependency) — full unit test coverage expected, same TDD discipline as the original port.
- `MediaPipeLiveDetectionSource` itself is **not** JVM-unit-testable, same established precedent as `MediaPipeReplayDetectionSource` (hard dependency on `Camera2`/`FaceLandmarkerResult`, native, Android-runtime-only) — verified via on-device confirmation only (§7 step 6), not a new gap this spec introduces.
- Testing live detection quality (not just plumbing) on `vitalguard_aaos` requires `hw.camera.front=webcam0`, not `emulated` — documented so this isn't rediscovered the hard way later.
