# Distraction Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new distraction signals (gaze/head-off-road via pitch+yaw, hands-off-wheel via a new MediaPipe Hand Landmarker) to the DMS pipeline, deliver them as an independent `distraction` object alongside the existing drowsiness payload, and react to them in the Android app via a separate `DistractionController` arbitrated through a shared `AlertArbiter` so a distraction reminder never overrides — or gets wrongly cut off by — an active drowsiness alert.

**Architecture:** Python (Container Node) computes two independent composite scores per frame — the existing drowsiness score (unchanged) and a new distraction score (`gaze_off_road_ratio` + `hands_off_wheel_ratio`, weighted) — and serves both in one HTTP JSON payload. Kotlin (Skycraft app) runs two independent, idempotent FSM controllers against that one payload; a small `AlertArbiter` is the single point of contact with the shared voice gateway so exactly one message speaks at a time, with drowsiness always winning.

**Tech Stack:** Python 3.12, MediaPipe Tasks API (Face Landmarker + new Hand Landmarker), pytest, Docker; Kotlin, JUnit, kotlinx.serialization.

## Global Constraints

- `PITCH_OFF_ROAD_THRESHOLD = 20.0`, `YAW_OFF_ROAD_THRESHOLD = 30.0` — separate from drowsiness's `max_droop_deg=25.0`; reasoned starting points, not scientifically validated, revisited against real footage at the acceptance gate (Task 12).
- `head_off_road = abs(pitch_deg) > PITCH_OFF_ROAD_THRESHOLD or abs(yaw_deg) > YAW_OFF_ROAD_THRESHOLD`. The pitch used here is the SAME calibrated-baseline pitch drowsiness uses (`main.py`'s `BASELINE_CALIBRATION_SECONDS` window) — never a second independent baseline.
- `is_gaze_off_road(head_off_road, eye_closed) = head_off_road and not eye_closed`, where `eye_closed` is `BlinkStateTracker.update()`'s already-debounced output — never a fresh raw `blink_score < BLINK_CLOSE_THRESHOLD` check.
- `hands_visibility: "FULL"|"PARTIAL"|"UNKNOWN"` (2/1/0 hands detected). `hands_on_wheel` is `True` only when **all** currently-visible hands are inside the wheel region — a `PARTIAL` frame with its one visible hand outside the region is `False`, never optimistically `True`. `UNKNOWN` frames are excluded from `hands_off_wheel_ratio`'s denominator, never counted as "off wheel."
- `W_GAZE = 0.80`, `W_HANDS = 0.20` — gaze alone at `ratio=1.0` must clear `enter_threshold=0.70` (this exact arithmetic was verified during design review; do not silently change these four numbers without re-deriving that gaze alone still clears the threshold with margin).
- `DistractionTriggerEmitter` defaults: `enter_threshold=0.70, exit_threshold=0.40, sustain_seconds=1.5, cooldown_seconds=5.0`. Never reuse `TriggerEmitter` — this is a separate class with separate reasoning.
- Distraction is delivered as a new top-level `distraction` object in the existing trigger payload/schema (sibling to `features`, not nested inside it), added to `required`. No backward-compatibility shim — Python and Kotlin are updated together in this plan.
- `VoiceAlertGateway` gets a second explicit method, `triggerDistractionReminder()` — never retrofit a `message: String` parameter onto `triggerAlert()`.
- `AlertArbiter` tracks which `AlertSource` actually owns the currently-sounding alert (`activeSpeaker`). `stopAlert(source)` is a no-op unless `source == activeSpeaker` — a suppressed source must never be able to cut off the source that is genuinely speaking.
- `AlertArbiter.setDrowsinessCriticalActive(false)` and `alertArbiter.stopAlert(DROWSINESS)` must live inside `DrowsinessController.revertToBaseline()` specifically (the function both `handleNonCritical()` and `onConnectionLost()` already funnel through) — never duplicated separately in each caller.
- `DistractionController` has no heartbeat/timeout parameter of its own — connection-loss detection is owned entirely by the single shared `TriggerPollClient` instance.
- `DrowsinessController`/`DistractionController` are two independent FSM instances, never merged into one state enum. Each keeps its own `latched` flag and `lastCorrelationId` — no shared correlationId namespace.
- Distraction never touches `ClimateActuatorGateway` — confirmed HVAC-free by design.

---

## Task 1: Bake `hand_landmarker.task` into the Docker image

**Files:**
- Modify: `dms-ai-engine/Dockerfile`
- Modify: `dms-ai-engine/requirements.txt` (comment only, no version change — `mediapipe==0.10.14` already supports `HandLandmarker`)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `/app/models/hand_landmarker.task` inside the built image, for Task 3's `hand_tracker.py` to load at runtime.

- [ ] **Step 1:** Find the current model-bundle download step in `dms-ai-engine/Dockerfile` (added for `face_landmarker.task` in the CV remediation plan — it looks like this):
  ```dockerfile
  RUN mkdir -p /app/models && curl -fL -o /app/models/face_landmarker.task \
      "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
  ```
  Add a second, version-pinned download for the Hand Landmarker directly below it:
  ```dockerfile
  RUN curl -fL -o /app/models/hand_landmarker.task \
      "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
  ```
- [ ] **Step 2:** Verify the pinned URL resolves to the same bytes as `latest`, the same way Task 1 of the CV remediation plan verified `face_landmarker.task` (this project's own precedent — do not skip this check, an earlier drift risk was caught exactly here):
  ```bash
  curl -sI "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task" | grep -i etag
  curl -sI "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task" | grep -i etag
  ```
  Confirm both ETags match. Record the byte size too (`curl -sI ... | grep -i content-length`) — it will be used again in Task 12's container-startup sanity check.
- [ ] **Step 3:** Rebuild the image and confirm the file lands correctly:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:distraction-check .
  MSYS_NO_PATHCONV=1 docker run --rm --entrypoint stat vital-guard-dms:distraction-check -c%s /app/models/hand_landmarker.task
  ```
  Expected: prints a byte count matching the `Content-Length` from Step 2, no error.
- [ ] **Step 4:** Update the comment block in `requirements.txt` (near the existing `mediapipe==0.10.14` pin) to note the Hand Landmarker task is also covered by this version — do not bump the pin, just document:
  ```python
  # mediapipe==0.10.14 covers both the Face Landmarker Tasks API
  # (output_facial_transformation_matrixes/output_face_blendshapes) and the
  # Hand Landmarker Tasks API (added for distraction detection) -- verified
  # both APIs import and construct successfully on this pinned version,
  # on both Windows dev and the Linux container.
  ```
- [ ] **Step 5:** Commit:
  ```bash
  git add dms-ai-engine/Dockerfile dms-ai-engine/requirements.txt
  git commit -m "Bake hand_landmarker.task into the Docker image at build time"
  ```

---

## Task 2: `extract_yaw_deg()` in `head_pose.py`

**Files:**
- Modify: `dms-ai-engine/services/head_pose.py`
- Modify: `dms-ai-engine/tests/test_head_pose.py`

**Interfaces:**
- Consumes: the existing `rotation_matrix_to_euler_deg(matrix) -> Tuple[float, float, float]`.
- Produces: `extract_yaw_deg(transformation_matrix: np.ndarray) -> float`, consumed by Task 8's `main.py` wiring.

**Already established, no new axis probe needed:** `head_pose.py`'s own docstring already reports Y as yaw from a real combined-motion probe against `distracted.mp4` (y ranging ~+23° to -40° during a head turn). This task adds the accessor function and locks in the existing synthetic coverage plus one new real-data quantification the docstring currently only states qualitatively.

- [ ] **Step 1:** Write the failing tests. Add to `dms-ai-engine/tests/test_head_pose.py` (the existing `test_pure_y_rotation_recovers_known_angle` and `test_combined_rotation_does_not_corrupt_pitch_extraction` already cover the synthetic case for Y — these two are the wrapper-specific regression guards, mirroring the existing `test_extract_pitch_deg_is_insensitive_to_the_other_axes` pattern exactly, including reusing its `< 2.5` tolerance rather than inventing a new number):
  ```python
  from services.head_pose import rotation_matrix_to_euler_deg, extract_pitch_deg, extract_yaw_deg


  def test_extract_yaw_deg_increases_with_the_chosen_axis_rotation():
      angles = [-20.0, -10.0, 0.0, 10.0, 20.0, 30.0]
      yaws = [extract_yaw_deg(_rotation_matrix_y(a)) for a in angles]
      for earlier, later in zip(yaws, yaws[1:]):
          assert later > earlier, f"yaw must be strictly increasing, got {yaws}"


  def test_extract_yaw_deg_is_insensitive_to_pitch_rotation():
      """Mirrors test_extract_pitch_deg_is_insensitive_to_the_other_axes exactly
      (same synthetic builder, same 2.5deg tolerance) -- a regression guard on
      extract_yaw_deg() specifically, in case a future change accidentally
      reads the wrong axis, even though the underlying single-axis math is
      already covered by test_pure_x_rotation_recovers_known_angle."""
      for angle in (-30.0, -15.0, 15.0, 30.0):
          yaw = extract_yaw_deg(_rotation_matrix_x(angle))
          assert abs(yaw) < 2.5, f"non-yaw axis rotation of {angle} deg leaked into yaw: got {yaw}"
  ```
  Add the import line to the top of the existing import block (do not duplicate the existing `from services.head_pose import rotation_matrix_to_euler_deg, extract_pitch_deg` line — merge `extract_yaw_deg` into it).
- [ ] **Step 2:** Run to verify failure:
  `pytest dms-ai-engine/tests/test_head_pose.py -v`
  Expected: FAIL — `extract_yaw_deg` does not exist yet.
- [ ] **Step 3:** Add `extract_yaw_deg()` to `dms-ai-engine/services/head_pose.py`, directly below `extract_pitch_deg()`:
  ```python
  def extract_yaw_deg(transformation_matrix: np.ndarray) -> float:
      x, y, z = rotation_matrix_to_euler_deg(transformation_matrix)
      return y  # empirically confirmed to be yaw -- see module docstring
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/tests/test_head_pose.py -v`
  Expected: all tests PASS, including the 2 new ones.
- [ ] **Step 5: Real-data quantification (not a permanent test — a one-time investigation, same category as Task 3's original probe).** The current docstring only says pitch's insensitivity to yaw motion was quantified (`~-9 to -3 deg band`) but never quantified the reverse (yaw's insensitivity to pitch motion during a real head-droop). Close that gap now, inside the real container:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:yaw-check .
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd)/out/drowsy.mp4:/data/drowsy.mp4:ro" \
    -v "$(pwd)/out:/app/out" \
    --entrypoint python vital-guard-dms:yaw-check -c "
  import cv2, mediapipe as mp
  from services.face_landmarker_client import build_video_mode_landmarker, MonotonicTimestamp
  from services.head_pose import extract_yaw_deg

  landmarker = build_video_mode_landmarker('/app/models/face_landmarker.task')
  guard = MonotonicTimestamp()
  cap = cv2.VideoCapture('/data/drowsy.mp4')
  t = 0.0
  fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
  while cap.isOpened():
      ret, frame = cap.read()
      if not ret: break
      rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
      mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
      raw_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
      result = landmarker.detect_for_video(mp_image, guard.next(raw_ms))
      if result.facial_transformation_matrixes:
          yaw = extract_yaw_deg(result.facial_transformation_matrixes[0])
          print(f't={t:.2f} yaw={yaw:.2f}')
      t += 1.0 / fps
  "
  ```
  This reproduces the droop window (~t=0.6-2.2s per the existing docstring). Record the min/max yaw value across that window (expect a small band, consistent with the docstring's existing qualitative note "y and z also drift... by a smaller margin than x").
- [ ] **Step 6:** Add the quantified finding to `head_pose.py`'s module docstring, directly after the existing pitch-insensitivity sentence (append, do not rewrite the existing paragraph):
  ```python
  # (append to the existing docstring paragraph that currently ends
  # "...i.e. pitch (x) is not grossly contaminated by a large yaw excursion.")
  # The reverse direction was quantified too: during drowsy.mp4's droop window
  # (t=0.6-2.2s), yaw (y) stayed within a [RECORD THE ACTUAL MIN/MAX FROM STEP 5]
  # band while pitch (x) swung from -4.97 to 21.82 -- i.e. yaw is not grossly
  # contaminated by a real head-droop motion either.
  ```
  Replace the bracketed placeholder with the actual numbers from Step 5's output before committing — this is real data being recorded, not a template.
- [ ] **Step 7:** Visual cross-check (mirrors Task 3's own methodology): extract 2-3 frames from `distracted.mp4` at timestamps where the head-turn is visible (`ffmpeg -ss <t> -i dms-ai-engine/out/distracted.mp4 -frames:v 1 <out>.png`), confirm the sign of `extract_yaw_deg()`'s output at those same timestamps matches the visible turn direction (left turn should give one consistent sign, right the other — check against the actual frames, don't assume). Record the specific frames/timestamps/values checked in the commit message or a one-line docstring note.
- [ ] **Step 8:** Run the full suite once more to confirm nothing else broke: `pytest dms-ai-engine -v`
- [ ] **Step 9:** Commit:
  ```bash
  git add dms-ai-engine/services/head_pose.py dms-ai-engine/tests/test_head_pose.py
  git commit -m "Add extract_yaw_deg(), quantify yaw's insensitivity to pitch motion on real footage"
  ```

---

## Task 3: Hand Landmarker client and wheel-region logic (`hand_tracker.py`)

**Files:**
- Create: `dms-ai-engine/services/hand_tracker.py`
- Create: `dms-ai-engine/tests/test_hand_tracker.py`

**Interfaces:**
- Consumes: `hand_landmarker.task` baked in by Task 1.
- Produces: `build_video_mode_hand_landmarker(model_path: str) -> HandLandmarker`; `classify_hands_visibility(num_hands_detected: int) -> str`; `hands_on_wheel(hand_landmark_lists, wheel_region) -> bool`. Consumed by Task 8's `main.py` wiring.

**The wheel region is a real empirical value, not a guess — determine it before writing the "on wheel" logic.**

- [ ] **Step 1:** Extract a representative frame and inspect real hand-landmark coordinates inside the container:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:handprobe .
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd)/out/normal.mp4:/data/normal.mp4:ro" \
    --entrypoint python vital-guard-dms:handprobe -c "
  import cv2, mediapipe as mp
  from mediapipe.tasks.python import vision, BaseOptions

  options = vision.HandLandmarkerOptions(
      base_options=BaseOptions(model_asset_path='/app/models/hand_landmarker.task', delegate=BaseOptions.Delegate.CPU),
      running_mode=vision.RunningMode.VIDEO, num_hands=2,
  )
  landmarker = vision.HandLandmarker.create_from_options(options)
  cap = cv2.VideoCapture('/data/normal.mp4')
  t = 0.0
  fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
  frame_idx = 0
  while cap.isOpened():
      ret, frame = cap.read()
      if not ret: break
      rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
      mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
      result = landmarker.detect_for_video(mp_image, int(t * 1000))
      if frame_idx % 15 == 0 and result.hand_landmarks:
          for hand in result.hand_landmarks:
              xs = [lm.x for lm in hand]
              ys = [lm.y for lm in hand]
              print(f't={t:.2f} hand bbox: x=[{min(xs):.3f},{max(xs):.3f}] y=[{min(ys):.3f},{max(ys):.3f}]')
      t += 1.0 / fps
      frame_idx += 1
  "
  ```
  This prints normalized (0-1) bounding boxes for each detected hand every 15th frame across `normal.mp4` (both hands on the wheel throughout, per the design's "Camera framing confirmed" section). Record the observed x/y ranges.
- [ ] **Step 2:** Cross-check against the actual visible wheel position: extract the same frame(s) via `ffmpeg -ss <t> -i out/normal.mp4 -frames:v 1 <out>.png` and confirm the printed bounding boxes visually correspond to the driver's hands on the wheel, not some other part of the frame (e.g. face, dashboard reflection).
- [ ] **Step 3:** From Step 1's real output, define a wheel region as a normalized bounding box with a safety margin (widen the observed hand-position range slightly, since a hand adjusting grip is still "on the wheel," not just the exact landmark centroid). Write the values you actually observed into the constants below (do not invent numbers — use Step 1's real min/max, widened by ~0.05-0.1 in each direction, clamped to `[0, 1]`).
- [ ] **Step 4:** Write the failing tests:
  ```python
  # dms-ai-engine/tests/test_hand_tracker.py
  from services.hand_tracker import classify_hands_visibility, hands_on_wheel, WHEEL_REGION


  def test_classify_hands_visibility_full_when_two_hands():
      assert classify_hands_visibility(2) == "FULL"


  def test_classify_hands_visibility_partial_when_one_hand():
      assert classify_hands_visibility(1) == "PARTIAL"


  def test_classify_hands_visibility_unknown_when_no_hands():
      assert classify_hands_visibility(0) == "UNKNOWN"


  def _hand_at(cx, cy):
      """A minimal fake hand landmark list: one point at the given normalized
      center, matching the .x/.y attribute interface hands_on_wheel() reads."""
      import types
      return [types.SimpleNamespace(x=cx, y=cy)]


  def test_hands_on_wheel_true_only_when_all_visible_hands_in_wheel_region():
      wheel_cx = (WHEEL_REGION["x_min"] + WHEEL_REGION["x_max"]) / 2
      wheel_cy = (WHEEL_REGION["y_min"] + WHEEL_REGION["y_max"]) / 2
      both_hands_on_wheel = [_hand_at(wheel_cx, wheel_cy), _hand_at(wheel_cx, wheel_cy)]
      assert hands_on_wheel(both_hands_on_wheel, WHEEL_REGION) is True


  def test_hands_on_wheel_false_when_partial_visibility_and_visible_hand_is_off_wheel():
      """The dangerous case Feature B exists to catch: one hand visible, and
      that one hand is NOT on the wheel (e.g. holding a phone) -- must not be
      optimistically classified as safe just because a hand is visible."""
      off_wheel_hand = [_hand_at(0.02, 0.02)]  # top-left corner, nowhere near the wheel region
      assert hands_on_wheel(off_wheel_hand, WHEEL_REGION) is False


  def test_hands_on_wheel_false_when_any_visible_hand_is_outside_the_region():
      wheel_cx = (WHEEL_REGION["x_min"] + WHEEL_REGION["x_max"]) / 2
      wheel_cy = (WHEEL_REGION["y_min"] + WHEEL_REGION["y_max"]) / 2
      one_on_one_off = [_hand_at(wheel_cx, wheel_cy), _hand_at(0.02, 0.02)]
      assert hands_on_wheel(one_on_one_off, WHEEL_REGION) is False
  ```
- [ ] **Step 5:** Run to verify failure: `pytest dms-ai-engine/tests/test_hand_tracker.py -v`
  Expected: FAIL — module doesn't exist yet.
- [ ] **Step 6:** Write `dms-ai-engine/services/hand_tracker.py`. Replace the `x_min`/`x_max`/`y_min`/`y_max` values below with the ones you actually derived in Steps 1-3 (do not use these illustrative placeholders as-is — they must come from your own probe output):
  ```python
  """
  hand_tracker
  ------------
  Hand-presence signal from MediaPipe's Hand Landmarker Tasks API, used as a
  proxy for "hands off wheel" -- MediaPipe has no phone/object detector, so
  this tracks hand LANDMARK POSITION relative to a fixed wheel region, not
  true phone detection (see design doc's Scope framing).

  WHEEL_REGION is a normalized (0-1) bounding box calibrated against the
  current stock-footage test clips' camera framing (see the probe run
  documented in this task's commit) -- it is NOT verified against the real
  Container Node/Skycraft camera's actual mounting angle, and may need
  recalibration before being trusted on that hardware (design doc Known
  Limitations).
  """
  from typing import List

  import mediapipe as mp
  from mediapipe.tasks.python import vision
  from mediapipe.tasks.python.core.base_options import BaseOptions

  WHEEL_REGION = {
      "x_min": 0.30,  # REPLACE with your Step 1-3 derived value
      "x_max": 0.75,  # REPLACE with your Step 1-3 derived value
      "y_min": 0.55,  # REPLACE with your Step 1-3 derived value
      "y_max": 0.95,  # REPLACE with your Step 1-3 derived value
  }


  def build_video_mode_hand_landmarker(model_path: str) -> vision.HandLandmarker:
      options = vision.HandLandmarkerOptions(
          base_options=BaseOptions(model_asset_path=model_path, delegate=BaseOptions.Delegate.CPU),
          running_mode=vision.RunningMode.VIDEO,
          num_hands=2,
      )
      return vision.HandLandmarker.create_from_options(options)


  def classify_hands_visibility(num_hands_detected: int) -> str:
      if num_hands_detected >= 2:
          return "FULL"
      if num_hands_detected == 1:
          return "PARTIAL"
      return "UNKNOWN"


  def _in_wheel_region(hand, region) -> bool:
      cx = sum(lm.x for lm in hand) / len(hand)
      cy = sum(lm.y for lm in hand) / len(hand)
      return region["x_min"] <= cx <= region["x_max"] and region["y_min"] <= cy <= region["y_max"]


  def hands_on_wheel(hand_landmark_lists: List[list], region: dict) -> bool:
      """True only when ALL currently-visible hands are inside `region` --
      a single visible hand outside the region (e.g. holding a phone while
      the other hand is out of frame) must classify as False, never
      optimistically True. Call only when hand_landmark_lists is non-empty
      (i.e. hands_visibility != "UNKNOWN") -- the return value is a
      don't-care otherwise."""
      if not hand_landmark_lists:
          return False
      return all(_in_wheel_region(hand, region) for hand in hand_landmark_lists)
  ```
- [ ] **Step 7:** Run: `pytest dms-ai-engine/tests/test_hand_tracker.py -v`
  Expected: all 6 tests PASS.
- [ ] **Step 8:** Run the full suite: `pytest dms-ai-engine -v`
- [ ] **Step 9:** Commit:
  ```bash
  git add dms-ai-engine/services/hand_tracker.py dms-ai-engine/tests/test_hand_tracker.py
  git commit -m "Add Hand Landmarker client and wheel-region hands-on-wheel classification"
  ```

---

## Task 4: `is_gaze_off_road()` and `DistractionScoreCalculator`

**Files:**
- Create: `dms-ai-engine/services/distraction_score_calculator.py`
- Create: `dms-ai-engine/tests/test_distraction_score_calculator.py`

**Interfaces:**
- Consumes: nothing (pure data — takes booleans/floats already computed elsewhere).
- Produces: `is_gaze_off_road(head_off_road: bool, eye_closed: bool) -> bool`; `DistractionFrameFeatures` dataclass; `DistractionScoreCalculator`. Consumed by Task 8's `main.py` wiring.

- [ ] **Step 1:** Write the failing tests:
  ```python
  # dms-ai-engine/tests/test_distraction_score_calculator.py
  from services.distraction_score_calculator import (
      is_gaze_off_road, DistractionFrameFeatures, DistractionScoreCalculator,
  )


  def test_is_gaze_off_road_treats_head_down_with_eyes_open_as_distraction():
      assert is_gaze_off_road(head_off_road=True, eye_closed=False) is True


  def test_is_gaze_off_road_treats_head_down_with_eyes_closed_as_not_distraction():
      assert is_gaze_off_road(head_off_road=True, eye_closed=True) is False


  def test_is_gaze_off_road_false_when_head_is_not_off_road_regardless_of_eyes():
      assert is_gaze_off_road(head_off_road=False, eye_closed=False) is False
      assert is_gaze_off_road(head_off_road=False, eye_closed=True) is False


  def test_all_normal_gives_zero_score():
      calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
      for i in range(20):
          calc.add_frame(DistractionFrameFeatures(
              timestamp=i * 0.1, gaze_off_road=False,
              hands_visibility="FULL", hands_on_wheel=True,
          ))
      assert calc.compute_score() == 0.0


  def test_gaze_alone_at_full_ratio_clears_the_enter_threshold():
      """Locks in the exact arithmetic this design's weights depend on: gaze
      off-road for the entire window, hands always on wheel (matching
      distracted.mp4's real ground truth), must still produce a score that
      would clear DistractionTriggerEmitter's default enter_threshold=0.70 --
      an earlier weight split (0.65/0.35) made this mathematically
      impossible; this test exists so that regression can never silently
      return."""
      calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
      score = 0.0
      for i in range(20):
          score = calc.add_frame(DistractionFrameFeatures(
              timestamp=i * 0.1, gaze_off_road=True,
              hands_visibility="FULL", hands_on_wheel=True,
          ))
      assert score >= 0.70, f"gaze alone at ratio=1.0 must clear 0.70, got {score}"


  def test_sustained_off_road_and_hands_off_wheel_gives_max_score():
      calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
      score = 0.0
      for i in range(20):
          score = calc.add_frame(DistractionFrameFeatures(
              timestamp=i * 0.1, gaze_off_road=True,
              hands_visibility="FULL", hands_on_wheel=False,
          ))
      assert score == 1.0


  def test_unknown_visibility_frames_are_excluded_not_treated_as_off_wheel():
      """Mirrors FacePresenceTracker's principle: a missing detection must
      never be fabricated into evidence of the worst case. All frames report
      hands_visibility="UNKNOWN" (hands_on_wheel is a don't-care in that
      state) and gaze is never off-road -- score must stay exactly 0.0, not
      get inflated by treating the unknown hand state as "off wheel"."""
      calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
      score = 0.0
      for i in range(20):
          score = calc.add_frame(DistractionFrameFeatures(
              timestamp=i * 0.1, gaze_off_road=False,
              hands_visibility="UNKNOWN", hands_on_wheel=False,
          ))
      assert score == 0.0
  ```
- [ ] **Step 2:** Run to verify failure: `pytest dms-ai-engine/tests/test_distraction_score_calculator.py -v`
  Expected: FAIL — module doesn't exist yet.
- [ ] **Step 3:** Write `dms-ai-engine/services/distraction_score_calculator.py`:
  ```python
  """
  distraction_score_calculator
  -----------------------------
  Composite Distraction Score [0..1] from a sliding window of
  DistractionFrameFeatures. Two independent sub-signals:
    score = W_GAZE * gaze_off_road_ratio + W_HANDS * hands_off_wheel_ratio

  W_GAZE=0.80/W_HANDS=0.20 -- a fresh, reasoned starting point, NOT copied
  from drowsiness's 0.55/0.25/0.20 weights and NOT scientifically validated.
  Gaze is the more direct, more dangerous signal (looking away from the road
  while still holding the wheel is worse than briefly one-handing the wheel
  while still watching the road) and it's the only sub-signal with real
  verifiable ground truth right now (distracted.mp4 has hands on the wheel
  throughout). An earlier 0.65/0.35 draft made the only real positive-case
  video's max reachable score 0.65 -- strictly below any sane enter
  threshold -- a hard arithmetic ceiling, not a tunable-later gap. With
  0.80/0.20, gaze alone at ratio=1.0 reaches 0.80, clearing
  DistractionTriggerEmitter's default enter_threshold=0.70 with margin.
  Hands-off-wheel alone, even at ratio=1.0, only contributes 0.20 -- a
  supporting/amplifying signal in this iteration, not independently
  sufficient (mirrors how drowsiness's own head_droop_norm term alone can't
  reach CRITICAL either). Revisit once real footage combining sustained
  hands-off-wheel WITHOUT gaze-off-road exists to test against.
  """
  from dataclasses import dataclass
  from collections import deque

  W_GAZE = 0.80
  W_HANDS = 0.20


  def is_gaze_off_road(head_off_road: bool, eye_closed: bool) -> bool:
      """A drooped/turned head with closed eyes is drowsiness, not
      distraction; a drooped/turned head with open eyes (looking down at a
      phone, or turned to talk) is distraction, not drowsiness. `eye_closed`
      must be the already-debounced BlinkStateTracker output, not a fresh
      raw blink_score threshold check -- reusing a second raw check would
      reintroduce the single-frame-noise problem hysteresis was built to
      solve."""
      return head_off_road and not eye_closed


  @dataclass
  class DistractionFrameFeatures:
      timestamp: float
      gaze_off_road: bool      # = is_gaze_off_road(head_off_road, eye_closed)
      hands_visibility: str    # "FULL"|"PARTIAL"|"UNKNOWN"
      hands_on_wheel: bool     # meaningful only when hands_visibility != "UNKNOWN"


  class DistractionScoreCalculator:
      def __init__(self, window_seconds: float = 2.0, sample_hz: float = 10.0):
          self.max_samples = max(1, int(window_seconds * sample_hz))
          self.window: deque[DistractionFrameFeatures] = deque(maxlen=self.max_samples)

      def add_frame(self, frame: DistractionFrameFeatures) -> float:
          self.window.append(frame)
          return self.compute_score()

      def compute_score(self) -> float:
          if not self.window:
              return 0.0
          gaze_off_road_ratio = sum(1 for f in self.window if f.gaze_off_road) / len(self.window)
          countable = [f for f in self.window if f.hands_visibility != "UNKNOWN"]
          hands_off_wheel_ratio = (
              sum(1 for f in countable if not f.hands_on_wheel) / len(countable)
              if countable else 0.0  # no countable frames -> neutral, not fabricated
          )
          score = W_GAZE * gaze_off_road_ratio + W_HANDS * hands_off_wheel_ratio
          return max(0.0, min(1.0, score))
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/tests/test_distraction_score_calculator.py -v`
  Expected: all 7 tests PASS.
- [ ] **Step 5:** Run the full suite: `pytest dms-ai-engine -v`
- [ ] **Step 6:** Commit:
  ```bash
  git add dms-ai-engine/services/distraction_score_calculator.py dms-ai-engine/tests/test_distraction_score_calculator.py
  git commit -m "Add is_gaze_off_road() and DistractionScoreCalculator"
  ```

---

## Task 5: `DistractionTriggerEmitter`

**Files:**
- Create: `dms-ai-engine/services/distraction_trigger_emitter.py`
- Create: `dms-ai-engine/tests/test_distraction_trigger_emitter.py`

**Interfaces:**
- Consumes: nothing from earlier tasks (structurally identical to `TriggerEmitter`, different numbers).
- Produces: `DistractionTriggerEmitter`, consumed by Task 8's `main.py` wiring.

- [ ] **Step 1:** Write the failing tests — mirrors all 10 of `TriggerEmitter`'s existing tests from `tests/test_dms.py`, translated to `DistractionTriggerEmitter`'s own numbers (`enter=0.70, exit=0.40, sustain=1.5, cooldown=5.0`):
  ```python
  # dms-ai-engine/tests/test_distraction_trigger_emitter.py
  from services.distraction_trigger_emitter import DistractionTriggerEmitter


  def test_no_trigger_before_sustain_window_elapses():
      emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
      assert emitter.update(0.9, now=0.0) is None
      assert emitter.update(0.9, now=1.0) is None


  def test_trigger_fires_once_after_sustain():
      emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
      emitter.update(0.9, now=0.0)
      emitter.update(0.9, now=1.0)
      assert emitter.update(0.9, now=1.6) == "CRITICAL"


  def test_no_duplicate_trigger_while_still_above_threshold():
      emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
      emitter.update(0.9, now=0.0)
      fired_first = emitter.update(0.9, now=1.6)
      fired_again = emitter.update(0.9, now=2.5)
      assert fired_first == "CRITICAL"
      assert fired_again is None, "không được trigger lặp khi vẫn đang ở episode cũ"


  def test_trigger_rearms_after_dropping_below_exit_threshold():
      # cooldown_seconds đặt nhỏ hơn khoảng cách 2 lần fire trong test này để tách riêng
      # 2 cơ chế: re-arm (do hysteresis) và cooldown (lớp bảo vệ bổ sung) không che lấp nhau
      emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                           sustain_seconds=1.5, cooldown_seconds=2.0)
      emitter.update(0.9, now=0.0)
      emitter.update(0.9, now=1.6)           # fire lần 1
      emitter.update(0.3, now=3.0)           # rơi dưới exit -> re-arm
      assert emitter.update(0.9, now=3.1) is None    # chưa sustain đủ lại
      assert emitter.update(0.9, now=4.7) == "CRITICAL"    # sustain đủ (>=1.5s) và qua cooldown (>=2.0s kể từ 1.6) -> fire lần 2


  def test_hysteresis_prevents_flicker_around_0_70():
      """Score dao động 0.71 <-> 0.69 liên tục quanh ngưỡng -> KHÔNG được bắn trigger
      vì chưa từng sustain >=1.5s liên tục ở trên 0.70."""
      emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40, sustain_seconds=1.5)
      t = 0.0
      fired_any = False
      for i in range(10):
          score = 0.71 if i % 2 == 0 else 0.69  # không bao giờ rơi xuống exit_threshold=0.40
          fired_any = fired_any or emitter.update(score, now=t)
          t += 0.3
      assert fired_any is None or fired_any is False


  def test_short_dip_below_enter_but_above_exit_resets_sustain_timer():
      emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40, sustain_seconds=1.5)
      emitter.update(0.9, now=0.0)
      emitter.update(0.9, now=1.0)
      emitter.update(0.5, now=1.1)   # tụt xuống nhưng vẫn trên exit -> above_since reset về None
      assert emitter.update(0.9, now=1.2) is None    # mới bắt đầu sustain lại
      assert emitter.update(0.9, now=2.8) == "CRITICAL"    # đủ 1.6s kể từ 1.2 -> fire


  def test_update_returns_critical_string_on_fire():
      emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
      emitter.update(0.9, now=0.0)
      result = emitter.update(0.9, now=1.6)
      assert result == "CRITICAL"


  def test_update_returns_none_when_not_firing():
      emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
      assert emitter.update(0.9, now=0.0) is None


  def test_recovered_fires_once_on_down_edge_after_critical():
      emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                           sustain_seconds=1.5, cooldown_seconds=5.0)
      emitter.update(0.9, now=0.0)
      assert emitter.update(0.9, now=1.6) == "CRITICAL"
      assert emitter.update(0.3, now=3.0) == "RECOVERED"
      assert emitter.update(0.3, now=3.1) is None, "must not repeat RECOVERED every call"


  def test_recovered_does_not_fire_without_a_prior_critical():
      emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                           sustain_seconds=1.5, cooldown_seconds=5.0)
      assert emitter.update(0.3, now=0.0) is None
      assert emitter.update(0.2, now=1.0) is None
  ```
- [ ] **Step 2:** Run to verify failure: `pytest dms-ai-engine/tests/test_distraction_trigger_emitter.py -v`
  Expected: FAIL — module doesn't exist yet.
- [ ] **Step 3:** Write `dms-ai-engine/services/distraction_trigger_emitter.py` (structurally identical to `services/trigger_emitter.py`'s `TriggerEmitter`, different defaults, own class — never subclass or reuse `TriggerEmitter` directly):
  ```python
  """
  DistractionTriggerEmitter
  --------------------------
  Same two-threshold hysteresis shape as TriggerEmitter (services/trigger_emitter.py),
  but a SEPARATE class with separate reasoning -- distraction's sustain/cooldown
  timing is genuinely different from drowsiness's, not a copy:
    - sustain_seconds=1.5 (vs drowsiness's 2.0): a phone glance or a hand off
      the wheel is dangerous on a shorter timescale than the drowsiness FSM's
      own drowsiness-confirmation window.
    - cooldown_seconds=5.0 (vs drowsiness's 10.0): distraction events plausibly
      recur more often and shouldn't be suppressed as long.
  All four defaults are reasoned starting points, explicitly unvalidated,
  revisited at the acceptance gate (same disclosed status as TriggerEmitter's
  own numbers).
  """
  from typing import Optional


  class DistractionTriggerEmitter:
      def __init__(self, enter_threshold: float = 0.70, exit_threshold: float = 0.40,
                   sustain_seconds: float = 1.5, cooldown_seconds: float = 5.0):
          assert exit_threshold < enter_threshold, "exit phải thấp hơn enter (hysteresis)"
          self.enter_threshold = enter_threshold
          self.exit_threshold = exit_threshold
          self.sustain_seconds = sustain_seconds
          self.cooldown_seconds = cooldown_seconds

          self._above_since: Optional[float] = None
          self._last_emit_time: float = float("-inf")
          self._armed = True
          self._critical_active = False

      def update(self, score: float, now: float) -> Optional[str]:
          if score >= self.enter_threshold:
              if self._above_since is None:
                  self._above_since = now
              sustained = (now - self._above_since) >= self.sustain_seconds
              cooldown_ok = (now - self._last_emit_time) >= self.cooldown_seconds
              if sustained and cooldown_ok and self._armed:
                  self._armed = False
                  self._last_emit_time = now
                  self._critical_active = True
                  return "CRITICAL"
          else:
              self._above_since = None
              if score <= self.exit_threshold:
                  self._armed = True
                  if self._critical_active:
                      self._critical_active = False
                      return "RECOVERED"
          return None
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/tests/test_distraction_trigger_emitter.py -v`
  Expected: all 10 tests PASS.
- [ ] **Step 5:** Run the full suite: `pytest dms-ai-engine -v`
- [ ] **Step 6:** Commit:
  ```bash
  git add dms-ai-engine/services/distraction_trigger_emitter.py dms-ai-engine/tests/test_distraction_trigger_emitter.py
  git commit -m "Add DistractionTriggerEmitter (separate sustain/cooldown reasoning from TriggerEmitter)"
  ```

---

## Task 6: Schema — add the `distraction` object

**Files:**
- Modify: `contracts/trigger.schema.json`
- Modify: `dms-ai-engine/tests/test_schema.py`

**Interfaces:**
- Consumes: nothing.
- Produces: the `distraction` shape both Task 8 (`main.py`) and the Kotlin tasks (`TriggerPayload.kt`) must match exactly.

- [ ] **Step 1:** Write the failing test. Add to `dms-ai-engine/tests/test_schema.py`:
  ```python
  def test_valid_payload_with_distraction_object_passes():
      payload = {
          "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
          "state": "NORMAL",
          "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
          "reason": "test", "correlationId": "vg-test-0001",
          "distraction": {
              "score": 0.9, "state": "CRITICAL", "yawDeg": 45.0, "pitchDeg": 5.0,
              "handsVisibility": "FULL", "handsOnWheel": True, "reason": "gaze_off_road",
          },
      }
      jsonschema.validate(payload, SCHEMA)


  def test_missing_distraction_object_fails():
      payload = {
          "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
          "state": "NORMAL",
          "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
          "reason": "test", "correlationId": "vg-test-0001",
      }
      with pytest.raises(jsonschema.ValidationError):
          jsonschema.validate(payload, SCHEMA)
  ```
  (Check the existing test file's top-of-file fixture name for the loaded schema object — reuse whatever it's already called, e.g. `SCHEMA`, don't introduce a second loader.)
- [ ] **Step 2:** Run to verify failure: `pytest dms-ai-engine/tests/test_schema.py -v`
  Expected: `test_missing_distraction_object_fails` currently PASSES for the wrong reason (no `distraction` requirement exists yet, so nothing to fail on) and `test_valid_payload_with_distraction_object_passes` PASSES already too (extra properties are allowed by default in JSON Schema unless `additionalProperties: false` is set) — the real signal to check is that BOTH tests behave as named once Step 3 lands: the missing-object test must then raise, and the with-object test must still pass. Note this explicitly when running Step 2 — an unusual case where the "before" state isn't a clean failure signal, but the "after" state (Step 4) is what actually proves the change.
- [ ] **Step 3:** Modify `contracts/trigger.schema.json` — add `distraction` to `required` and its shape to `properties`:
  ```json
  {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "Vital-Guard AI Trigger Payload",
    "description": "Wire format for DMS trigger events, delivered as an HTTP JSON body over the local network-pin (GET /latest-trigger). Kept nested (not flattened) because the transport is HTTP JSON, not Android Intent extras — see docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md Decision 4.",
    "type": "object",
    "required": ["timestampMs", "source", "score", "confidence", "state", "features", "reason", "correlationId", "distraction"],
    "properties": {
      "timestampMs": { "type": "integer", "description": "Epoch milliseconds." },
      "source": { "type": "string", "enum": ["container-python", "debug", "replay", "mock-stream"] },
      "score": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
      "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
      "state": { "type": "string", "enum": ["NORMAL", "WARNING", "CRITICAL", "UNKNOWN"] },
      "features": {
        "type": "object",
        "required": ["perclos", "eyeOpenProbability", "headEulerAngleX"],
        "properties": {
          "perclos": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
          "eyeOpenProbability": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
          "headEulerAngleX": { "type": "number" }
        }
      },
      "reason": { "type": "string" },
      "correlationId": { "type": "string" },
      "distraction": {
        "type": "object",
        "required": ["score", "state", "yawDeg", "pitchDeg", "handsVisibility", "handsOnWheel", "reason"],
        "properties": {
          "score": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
          "state": { "type": "string", "enum": ["NORMAL", "WARNING", "CRITICAL"] },
          "yawDeg": { "type": "number" },
          "pitchDeg": { "type": "number" },
          "handsVisibility": { "type": "string", "enum": ["FULL", "PARTIAL", "UNKNOWN"] },
          "handsOnWheel": { "type": "boolean" },
          "reason": { "type": "string" }
        }
      }
    }
  }
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/tests/test_schema.py -v`
  Expected: all tests PASS, including the 2 new ones behaving as named now.
- [ ] **Step 5:** Run the full suite: `pytest dms-ai-engine -v`
  Expected: this will likely break `test_build_trigger_payload_matches_schema` in `test_main.py` (it builds a payload via `build_trigger_payload()`, which doesn't produce a `distraction` field yet) — that's expected and exactly what Task 8 fixes; do not attempt to fix it in this task, just confirm the failure is that specific, expected one and no other test regressed.
- [ ] **Step 6:** Commit:
  ```bash
  git add contracts/trigger.schema.json dms-ai-engine/tests/test_schema.py
  git commit -m "Add distraction object to trigger.schema.json"
  ```

---

## Task 7: Wire distraction detection into `main.py`'s `run_real_video()`

**Files:**
- Modify: `dms-ai-engine/main.py:152-256` (the whole `run_real_video()` function, plus `build_trigger_payload()` at lines 75-92)
- Modify: `dms-ai-engine/tests/test_main.py`

**Interfaces:**
- Consumes: `extract_yaw_deg` (Task 2), `build_video_mode_hand_landmarker`/`classify_hands_visibility`/`hands_on_wheel`/`WHEEL_REGION` (Task 3), `is_gaze_off_road`/`DistractionFrameFeatures`/`DistractionScoreCalculator` (Task 4), `DistractionTriggerEmitter` (Task 5), the `distraction` schema shape (Task 6).
- Produces: `run_real_video()`'s new behavior and `build_trigger_payload()`'s new `distraction` parameter, consumed by Task 9's `measure_latency.py` update and by the Kotlin side (indirectly, via the payload shape).

**Verified before writing this task (not assumed from memory):** `run_real_video()`'s current full body is at `main.py:152-256` (read directly, current as of this plan). It already computes `pitch_deg` and calibrates `baseline_pitch_deg` via the `calibration_pitch_samples`/`baseline_calibrated` mechanism at lines 180, 222-228. Distraction's pitch-based off-road check must reuse the SAME `calc.baseline_pitch_deg` value (via `DrowsinessScoreCalculator`, which already stores it after `calibrate_baseline()` is called) — do not add a second calibration window.

- [ ] **Step 1:** Write the failing test first — a fake `HandLandmarker` and an end-to-end assertion that the payload now carries a real `distraction` object. Add to `dms-ai-engine/tests/test_main.py`, near the existing `_fake_landmarker_with_face_detected` helper. Note: `_fake_landmarker_with_time_varying_pitch(calibration_frames, calibration_pitch_deg, drooped_pitch_deg, blink_left=0.9, blink_right=0.85)` used by the test below already exists in this file (added for the baseline-calibration regression test) — reuse it as-is, do not redefine it:
  ```python
  def _fake_hand_landmarker_with_hands_at(*hand_centers):
      """hand_centers: list of (x, y) normalized centers, one per detected
      hand this frame (0-2 items). Fakes build_video_mode_hand_landmarker's
      return value: a HandLandmarker whose detect_for_video() reports hands
      at the given fixed positions every frame."""
      hand_landmarks = [
          [types.SimpleNamespace(x=cx, y=cy)] for cx, cy in hand_centers
      ]
      hand_result = types.SimpleNamespace(hand_landmarks=hand_landmarks)

      class _FakeHandLandmarker:
          def detect_for_video(self, mp_image, timestamp_ms):
              return hand_result

          def close(self):
              pass

      return _FakeHandLandmarker()


  def test_run_real_video_populates_distraction_payload_fields_end_to_end(tmp_path, monkeypatch):
      """Exercises the full distraction glue code end-to-end: yaw extraction,
      hand-region classification, is_gaze_off_road exclusion, composite score,
      trigger emitter -- none of this is touched by any has-face test written
      before this task, so a regression here would go uncaught otherwise.

      Uses the same time-varying-pitch fake already established for the
      baseline-calibration regression test (`_fake_landmarker_with_time_varying_pitch`,
      in this same file) rather than a constant pitch -- a constant pitch
      would be fully cancelled out by calibration (baseline_pitch_deg would
      converge to that same constant), making it silently impossible for
      gaze_off_road to ever become True in this test. Enough frames are used
      to run past the 1.0s calibration window (BASELINE_CALIBRATION_SECONDS)
      AND fill distraction_calc's window with post-calibration frames --
      without this, the fix for the calibration-window false-positive risk
      (this task's Step 3) would leave distraction scoring silently
      never-exercised for the whole test."""
      import cv2
      import main as main_module
      from services.hand_tracker import WHEEL_REGION

      frame = np.zeros((64, 64, 3), dtype=np.uint8)
      fps = 30.0
      calibration_frames = 30       # 1.0s at 30fps, matches BASELINE_CALIBRATION_SECONDS
      post_calibration_frames = 40  # comfortably fills distraction_calc's window post-calibration
      frames = [frame] * (calibration_frames + post_calibration_frames)
      monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture(frames, fps=fps))
      monkeypatch.setattr(
          "services.face_landmarker_client.build_video_mode_landmarker",
          lambda model_path: _fake_landmarker_with_time_varying_pitch(
              calibration_frames=calibration_frames,
              calibration_pitch_deg=0.0,    # neutral baseline
              drooped_pitch_deg=45.0,       # clearly past PITCH_OFF_ROAD_THRESHOLD=20.0 once calibrated
              blink_left=0.1, blink_right=0.1,  # eyes open throughout -> is_gaze_off_road can be True
          ),
      )
      wheel_cx = (WHEEL_REGION["x_min"] + WHEEL_REGION["x_max"]) / 2
      wheel_cy = (WHEEL_REGION["y_min"] + WHEEL_REGION["y_max"]) / 2
      monkeypatch.setattr(
          "services.hand_tracker.build_video_mode_hand_landmarker",
          lambda model_path: _fake_hand_landmarker_with_hands_at((wheel_cx, wheel_cy), (wheel_cx, wheel_cy)),
      )

      out_csv = tmp_path / "out.csv"
      main_module.run_real_video("does-not-matter.mp4", out_csv, host="127.0.0.1", port=0)

      rows = out_csv.read_text(encoding="utf-8").strip().splitlines()
      header = rows[0].split(",")
      assert "distraction_score" in header, f"CSV header missing distraction columns: {header}"

      data_rows = rows[1:]
      last_row = dict(zip(header, data_rows[-1].split(",")))
      assert float(last_row["distraction_score"]) > 0.5, (
          "gaze_off_road should be driving distraction_score up well after the "
          f"calibration window elapsed, got {last_row['distraction_score']}"
      )
      assert last_row["distraction_state"] != "NORMAL"
  ```
- [ ] **Step 2:** Run to verify failure:
  `pytest dms-ai-engine/tests/test_main.py::test_run_real_video_populates_distraction_payload_fields_end_to_end -v`
  Expected: FAIL — `services.hand_tracker.build_video_mode_hand_landmarker` doesn't exist as an importable path from `main.py` yet / CSV header has no distraction columns.
- [ ] **Step 3:** Rewrite `run_real_video()` in `main.py`. Replace the whole function (lines 152-256) with:
  ```python
  def run_real_video(video_path: str, out_csv: Path, host: str, port: int,
                      model_path: str = "/app/models/face_landmarker.task",
                      hand_model_path: str = "/app/models/hand_landmarker.task") -> None:
      import cv2
      import mediapipe as mp
      from services.face_landmarker_client import build_video_mode_landmarker, MonotonicTimestamp
      from services.head_pose import extract_pitch_deg, extract_yaw_deg
      from services.eye_state import blink_score, BlinkStateTracker
      from services.hand_tracker import (
          build_video_mode_hand_landmarker, classify_hands_visibility, hands_on_wheel, WHEEL_REGION,
      )
      from services.distraction_score_calculator import (
          is_gaze_off_road, DistractionFrameFeatures, DistractionScoreCalculator,
      )
      from services.distraction_trigger_emitter import DistractionTriggerEmitter

      store = LatestTriggerStore()
      server = start_background_server(store, host=host, port=port)
      landmarker = build_video_mode_landmarker(model_path)
      hand_landmarker = build_video_mode_hand_landmarker(hand_model_path)
      timestamp_guard = MonotonicTimestamp()
      blink_tracker = BlinkStateTracker()

      cap = cv2.VideoCapture(video_path)
      if not cap.isOpened():
          landmarker.close()
          hand_landmarker.close()
          server.shutdown()
          raise RuntimeError(
              f"Could not open video file: {video_path} "
              "(bad path, or a codec/container OpenCV's build doesn't support)"
          )
      calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
      emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                                sustain_seconds=2.0, cooldown_seconds=10.0)
      face_tracker = FacePresenceTracker(sustain_seconds=2.0)
      distraction_calc = DistractionScoreCalculator(window_seconds=2.0, sample_hz=10.0)
      distraction_emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                                        sustain_seconds=1.5, cooldown_seconds=5.0)
      event_counter = 0
      t = 0.0
      calibration_pitch_samples = []
      baseline_calibrated = False
      fps = cap.get(cv2.CAP_PROP_FPS)
      if not math.isfinite(fps) or fps <= 0:
          fps = 30.0
      frame_dt = 1.0 / fps

      try:
          with open(out_csv, "w", newline="", encoding="utf-8") as f:
              writer = csv.writer(f)
              writer.writerow(["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal",
                                "yaw_deg", "hands_visibility", "hands_on_wheel", "distraction_score",
                                "distraction_state", "distraction_signal"])
              while cap.isOpened():
                  if _shutdown_requested:
                      break
                  ret, frame = cap.read()
                  if not ret:
                      break
                  rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                  mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                  raw_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
                  timestamp_ms = timestamp_guard.next(raw_ms)
                  result = landmarker.detect_for_video(mp_image, timestamp_ms)
                  hand_result = hand_landmarker.detect_for_video(mp_image, timestamp_ms)
                  has_face = bool(result.face_blendshapes)

                  hands_visibility = classify_hands_visibility(len(hand_result.hand_landmarks))
                  on_wheel = hands_on_wheel(hand_result.hand_landmarks, WHEEL_REGION)

                  face_signal = face_tracker.update(has_face=has_face, now=t)
                  if face_signal == "UNKNOWN":
                      event_counter += 1
                      store.update_latest(build_trigger_payload(
                          state="UNKNOWN", score=0.0, confidence=0.0,
                          perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                          reason="lost_face", source="container-python", event_counter=event_counter,
                          distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                          hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                          distraction_reason="lost_face",
                      ))

                  yaw_deg = 0.0
                  distraction_score = 0.0
                  distraction_state = "NORMAL"
                  distraction_signal = None

                  if has_face:
                      blendshapes = {c.category_name: c.score for c in result.face_blendshapes[0]}
                      score_blink = blink_score(blendshapes)
                      eye_closed = blink_tracker.update(score_blink, now=t)
                      pitch_deg = extract_pitch_deg(result.facial_transformation_matrixes[0])
                      yaw_deg = extract_yaw_deg(result.facial_transformation_matrixes[0])

                      if not baseline_calibrated:
                          if t < BASELINE_CALIBRATION_SECONDS:
                              calibration_pitch_samples.append(pitch_deg)
                          else:
                              if calibration_pitch_samples:
                                  calc.calibrate_baseline(sum(calibration_pitch_samples) / len(calibration_pitch_samples))
                              baseline_calibrated = True

                      score = calc.add_frame(FrameFeatures(timestamp=t, eye_closed=eye_closed, head_pitch_deg=pitch_deg))
                      signal = emitter.update(score, now=t)
                      state = _state_for_score(score)

                      baseline_corrected_pitch = pitch_deg - calc.baseline_pitch_deg
                      if baseline_calibrated:
                          # Do NOT feed distraction_calc during the ~1s
                          # calibration window -- calc.baseline_pitch_deg is
                          # still its 0.0 default until baseline_calibrated
                          # flips True, so baseline_corrected_pitch would be
                          # raw/uncalibrated pitch here. Scoring on that
                          # reproduces the exact false-positive/false-ceiling
                          # bug class already found and fixed for drowsiness's
                          # own score (see CV_REMEDIATION_RESULTS.md) -- on a
                          # camera whose neutral pitch isn't ~0deg, the first
                          # second of every video could spuriously read as
                          # head_off_road=True. Skip scoring entirely rather
                          # than score on a known-wrong baseline.
                          head_off_road = abs(baseline_corrected_pitch) > PITCH_OFF_ROAD_THRESHOLD or abs(yaw_deg) > YAW_OFF_ROAD_THRESHOLD
                          gaze_off_road = is_gaze_off_road(head_off_road, eye_closed)
                          distraction_score = distraction_calc.add_frame(DistractionFrameFeatures(
                              timestamp=t, gaze_off_road=gaze_off_road,
                              hands_visibility=hands_visibility, hands_on_wheel=on_wheel,
                          ))
                          distraction_signal = distraction_emitter.update(distraction_score, now=t)
                          # Verified against the real current source (main.py:67)
                          # before writing this call: _state_for_score(score,
                          # enter_threshold=0.85, exit_threshold=0.50) already
                          # accepts custom threshold kwargs with those defaults
                          # -- this override is not a guess.
                          distraction_state = _state_for_score(
                              distraction_score, enter_threshold=0.70, exit_threshold=0.40,
                          )

                      if signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED"):
                          event_counter += 1
                          store.update_latest(build_trigger_payload(
                              state=state, score=score, confidence=1.0,
                              perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                              head_euler_angle_x=pitch_deg,
                              reason=("sustained_high_score" if signal == "CRITICAL" else "recovered") if signal else "unchanged",
                              source="container-python", event_counter=event_counter,
                              distraction_score=distraction_score, distraction_state=distraction_state,
                              yaw_deg=yaw_deg, pitch_deg=baseline_corrected_pitch,
                              hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                              distraction_reason=("gaze_off_road_or_hands_off_wheel" if distraction_signal == "CRITICAL"
                                                   else "recovered" if distraction_signal == "RECOVERED" else "unchanged"),
                          ))
                      writer.writerow([f"{t:.2f}", 1, f"{score_blink:.3f}", f"{pitch_deg:.1f}", f"{score:.3f}", state, signal or "",
                                        f"{yaw_deg:.1f}", hands_visibility, int(on_wheel),
                                        f"{distraction_score:.3f}", distraction_state, distraction_signal or ""])
                  else:
                      writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or "",
                                        "", hands_visibility, int(on_wheel), "", "", ""])

                  t += frame_dt
      finally:
          cap.release()
          landmarker.close()
          hand_landmarker.close()
          server.shutdown()
  ```
  Add the two new module-level constants right below `BASELINE_CALIBRATION_SECONDS`:
  ```python
  PITCH_OFF_ROAD_THRESHOLD = 20.0
  YAW_OFF_ROAD_THRESHOLD = 30.0
  ```
- [ ] **Step 4:** Rewrite `build_trigger_payload()` (lines 75-92) to accept and emit the `distraction` object:
  ```python
  def build_trigger_payload(state: str, score: float, confidence: float,
                             perclos: float, eye_open_probability: float,
                             head_euler_angle_x: float, reason: str, source: str,
                             event_counter: int, distraction_score: float, distraction_state: str,
                             yaw_deg: float, pitch_deg: float, hands_visibility: str,
                             hands_on_wheel_flag: bool, distraction_reason: str) -> dict:
      return {
          "timestampMs": int(time.time() * 1000),
          "source": source,
          "score": round(score, 3),
          "confidence": round(confidence, 3),
          "state": state,
          "features": {
              "perclos": round(perclos, 3),
              "eyeOpenProbability": round(eye_open_probability, 3),
              "headEulerAngleX": round(head_euler_angle_x, 3),
          },
          "reason": reason,
          "correlationId": f"vg-{source}-{event_counter:04d}",
          "distraction": {
              "score": round(distraction_score, 3),
              "state": distraction_state,
              "yawDeg": round(yaw_deg, 3),
              "pitchDeg": round(pitch_deg, 3),
              "handsVisibility": hands_visibility,
              "handsOnWheel": hands_on_wheel_flag,
              "reason": distraction_reason,
          },
      }
  ```
- [ ] **Step 5:** Update every OTHER existing caller of `build_trigger_payload()` to pass the new required arguments — `run_mock_stream()` at `main.py:134-139` (pass neutral defaults: `distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0, hands_visibility="UNKNOWN", hands_on_wheel_flag=False, distraction_reason="mock_stream_no_distraction_signal"`).
- [ ] **Step 6:** Update `test_main.py::test_build_trigger_payload_matches_schema` (the test broken by Task 6's schema change) to pass the new required arguments too, and assert the new nested object round-trips:
  ```python
  def test_build_trigger_payload_matches_schema():
      schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
      payload = build_trigger_payload(
          state="CRITICAL", score=0.91, confidence=1.0,
          perclos=0.8, eye_open_probability=0.1, head_euler_angle_x=28.0,
          reason="sustained_high_score", source="mock-stream", event_counter=1,
          distraction_score=0.75, distraction_state="CRITICAL", yaw_deg=35.0, pitch_deg=5.0,
          hands_visibility="PARTIAL", hands_on_wheel_flag=False, distraction_reason="gaze_off_road",
      )
      jsonschema.validate(payload, schema)
      assert payload["state"] == "CRITICAL"
      assert payload["correlationId"] == "vg-mock-stream-0001"
      assert payload["features"]["perclos"] == 0.8
      assert payload["distraction"]["state"] == "CRITICAL"
      assert payload["distraction"]["handsOnWheel"] is False
  ```
- [ ] **Step 7:** Every other call site of `build_trigger_payload()` inside `test_main.py`'s existing tests (the lost-face test, the has-face-end-to-end test, etc.) constructs its OWN payloads via `run_real_video()`/`run_mock_stream()`, not by calling `build_trigger_payload()` directly — they don't need argument updates, but re-run the full suite (`pytest dms-ai-engine -v`) and fix any that fail for a DIFFERENT reason than the ones already anticipated in this task (e.g. a test asserting on an exact CSV column count that this task's new columns changed) before moving on.
- [ ] **Step 8:** Run the specific new test: `pytest dms-ai-engine/tests/test_main.py::test_run_real_video_populates_distraction_payload_fields_end_to_end -v`
  Expected: PASS.
- [ ] **Step 9:** Run the full suite: `pytest dms-ai-engine -v`
  Expected: all tests PASS.
- [ ] **Step 10:** Commit:
  ```bash
  git add dms-ai-engine/main.py dms-ai-engine/tests/test_main.py
  git commit -m "Wire hand tracking and distraction scoring into run_real_video()"
  ```

---

## Task 8: `measure_latency.py` — mirror the distraction path too

**Files:**
- Modify: `dms-ai-engine/measure_latency.py`

**Interfaces:**
- Consumes: everything Task 7 wired into `run_real_video()`.
- Produces: nothing new — a measurement tool, not part of the served pipeline.

**Verified before writing this task:** GitNexus confirmed exactly 2 files import `services/head_pose.py`'s functions for real use — `main.py` and `measure_latency.py`. The prior CV remediation plan's Task 6 was caught missing part of the real per-frame body (it only timed through `calc.add_frame(...)`, omitting the trigger-store write path) — do not repeat that mistake here for the distraction path.

- [ ] **Step 1:** Read the current `dms-ai-engine/measure_latency.py` in full before editing — it already mirrors `run_real_video()`'s drowsiness path exactly (per the CV remediation plan's Task 6 fix). Add the equivalent distraction calls to its `measure()` function, in the same places drowsiness's calls appear: construct `hand_landmarker`, `distraction_calc`, `distraction_emitter` alongside the existing drowsiness objects; inside the per-frame loop, call `hand_landmarker.detect_for_video(...)`, compute `hands_visibility`/`on_wheel`/`yaw_deg`/`head_off_road`/`gaze_off_road`, call `distraction_calc.add_frame(...)` and `distraction_emitter.update(...)`, and include the `store.update_latest(build_trigger_payload(...))` call with ALL of `build_trigger_payload()`'s now-required arguments (Task 7's new signature) on a firing frame from EITHER emitter — mirror the exact structure of `run_real_video()`'s `if signal in (...) or distraction_signal in (...):` block, not a simplified version of it. Close `hand_landmarker` in the same `finally`/cleanup path `landmarker` already uses.
- [ ] **Step 2:** Confirm no argument-count/name mismatch against Task 7's final `build_trigger_payload()` signature by running a quick static check — no automated test exists for this script (matches the CV remediation plan's own precedent: a measurement tool, not part of the served pipeline), so verify by actually running it against one short video inside the container:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:measure-check .
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd)/out/normal.mp4:/data/normal.mp4:ro" \
    -v "$(pwd)/measure_latency.py:/app/measure_latency.py:ro" \
    --entrypoint python vital-guard-dms:measure-check /app/measure_latency.py /data/normal.mp4
  ```
  Expected: runs to completion, prints `p50=...ms p95=...ms`, no exception.
- [ ] **Step 3:** Commit:
  ```bash
  git add dms-ai-engine/measure_latency.py
  git commit -m "Mirror the distraction detection path in measure_latency.py"
  ```

---

**Kotlin toolchain note — read before starting Task 9.** Verified directly:
`aaos-cockpit-app/` has no `gradlew`/`gradlew.bat` wrapper committed (`ls
aaos-cockpit-app/gradlew*` finds nothing) — the module name it references
(`:app`) IS confirmed correct from `settings.gradle.kts`'s `include(":app")`,
but every `./gradlew ...` command in Tasks 9-15 below will fail outright in
an environment without a wrapper generated and without a configured
Android SDK/JDK toolchain (this Kotlin app has been built/tested on a
teammate's machine with the full toolchain, not in this session's
environment, for every Kotlin task so far in this project). Before each
`./gradlew` step below: attempt it once. If it fails for a toolchain reason
(missing wrapper, missing SDK, `command not found`) rather than a real
compile/test failure, fall back to a careful manual verification instead —
read the edited file(s) end-to-end, confirm the referenced classes/methods/
imports actually exist and the syntax is valid Kotlin, and cross-check every
new test's logic by hand against the production code it exercises. State
explicitly in that task's commit message which verification method was
actually used (`gradlew` ran successfully vs. manual review only) — never
silently claim a test suite passed without saying which way it was
confirmed.

## Task 9: `VoiceAlertGateway` — add `triggerDistractionReminder()`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (Kotlin side starts here).
- Produces: `VoiceAlertGateway.triggerDistractionReminder()`, consumed by Task 10's `AlertArbiter`.

**Verified before writing this task:** the real interface (`VoiceAlertGateway.kt`) is `triggerAlert()` with no parameters; `RealVoiceAlertGateway` wraps `VoiceEmergencyAssistant.executeVoiceIntervention()`, which speaks one hardcoded string via `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`. This task adds a second, purpose-named method — never a `message: String` parameter on the existing one.

- [ ] **Step 1:** Add the interface method and `FakeVoiceAlertGateway` support to `VoiceAlertGateway.kt`:
  ```kotlin
  interface VoiceAlertGateway {
      fun triggerAlert()
      fun triggerDistractionReminder()
      fun stopAlert()
  }

  class FakeVoiceAlertGateway : VoiceAlertGateway {
      var alertTriggered: Boolean = false
      var distractionReminderTriggered: Boolean = false
      var stopCalled: Boolean = false
      var throwOnTrigger: Boolean = false
      var throwOnStop: Boolean = false

      override fun triggerAlert() {
          if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
          alertTriggered = true
      }

      override fun triggerDistractionReminder() {
          if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
          distractionReminderTriggered = true
      }

      override fun stopAlert() {
          if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
          stopCalled = true
      }
  }

  /** Real implementation — wraps the existing VoiceEmergencyAssistant unchanged. */
  class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway {
      private val assistant = VoiceEmergencyAssistant(context)

      override fun triggerAlert() {
          assistant.executeVoiceIntervention()
      }

      override fun triggerDistractionReminder() {
          assistant.executeDistractionReminder()
      }

      override fun stopAlert() {
          assistant.releaseFocus()
      }
  }
  ```
- [ ] **Step 2:** Add `executeDistractionReminder()` to `VoiceEmergencyAssistant.kt`, mirroring `executeVoiceIntervention()`'s structure but with a lighter audio-focus request (`AUDIOFOCUS_GAIN_TRANSIENT`, not `_EXCLUSIVE` — per the "response nhẹ hơn" decision) and its own message. Note in a comment that the exact wording/focus behavior is a placeholder pending Tài's sign-off (per the design doc's Decision 5), not a final UX decision:
  ```kotlin
  fun executeDistractionReminder() {
      // Lighter than executeVoiceIntervention()'s _EXCLUSIVE request -- a brief
      // distraction reminder shouldn't seize/mute all cabin audio the way a
      // sustained drowsiness alert does. Placeholder wording/focus behavior,
      // pending Tài's sign-off (design doc Decision 5) -- functional default,
      // not a final UX decision.
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
          tts?.speak(reminderText, TextToSpeech.QUEUE_FLUSH, null, "DISTRACTION_REMINDER")
          Log.i(TAG, "🗣️ Speaking distraction reminder: '$reminderText'")
      } else {
          Log.e(TAG, "❌ Distraction reminder audio focus request denied.")
      }
  }
  ```
  (Reuses the existing `focusRequest` field so `releaseFocus()` already handles cleanup for either kind of alert without changes.)
- [ ] **Step 3:** Compile-check: attempt `./gradlew :app:compileDebugKotlin`; if it fails for a toolchain reason, fall back per the Kotlin toolchain note above.
- [ ] **Step 4:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt
  git commit -m "Add VoiceAlertGateway.triggerDistractionReminder() with a lighter audio-focus request"
  ```

---

## Task 10: `AlertArbiter`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertArbiter.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterTest.kt`

**Interfaces:**
- Consumes: `VoiceAlertGateway` (Task 9).
- Produces: `AlertSource`, `AlertArbiter` with `setDrowsinessCriticalActive(Boolean)`, `requestVoiceAlert(AlertSource)`, `stopAlert(AlertSource)`. Consumed by Task 11 (`DrowsinessController`) and Task 13 (`DistractionController`).

- [ ] **Step 1:** Write the failing tests:
  ```kotlin
  package com.vitalguard.ai

  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  class AlertArbiterTest {
      private lateinit var voice: FakeVoiceAlertGateway
      private lateinit var arbiter: AlertArbiter

      @Before
      fun setUp() {
          voice = FakeVoiceAlertGateway()
          arbiter = AlertArbiter(voice)
      }

      @Test
      fun `drowsiness and distraction critical simultaneously - only drowsiness speaks`() {
          arbiter.setDrowsinessCriticalActive(true)
          arbiter.requestVoiceAlert(AlertSource.DROWSINESS)
          arbiter.requestVoiceAlert(AlertSource.DISTRACTION)

          assertTrue(voice.alertTriggered)
          assertFalse(voice.distractionReminderTriggered)
      }

      @Test
      fun `distraction critical alone with no drowsiness active - speaks normally`() {
          arbiter.requestVoiceAlert(AlertSource.DISTRACTION)

          assertTrue(voice.distractionReminderTriggered)
      }

      @Test
      fun `stopAlert from suppressed source does not stop active alert from other source`() {
          arbiter.setDrowsinessCriticalActive(true)
          arbiter.requestVoiceAlert(AlertSource.DROWSINESS)   // genuinely speaking
          arbiter.requestVoiceAlert(AlertSource.DISTRACTION)  // suppressed, never spoke

          arbiter.stopAlert(AlertSource.DISTRACTION)

          assertFalse(voice.stopCalled)
      }

      @Test
      fun `stopAlert from owning source stops its own alert normally`() {
          arbiter.requestVoiceAlert(AlertSource.DISTRACTION)  // not suppressed, drowsiness inactive

          arbiter.stopAlert(AlertSource.DISTRACTION)

          assertTrue(voice.stopCalled)
      }
  }
  ```
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.AlertArbiterTest"`
  Expected: FAIL — `AlertArbiter`/`AlertSource` don't exist yet. (If Gradle isn't runnable in this environment, verify by static reasoning that the referenced classes don't exist yet, and re-run once Step 3 lands.)
- [ ] **Step 3:** Write `AlertArbiter.kt`:
  ```kotlin
  package com.vitalguard.ai

  import android.util.Log

  enum class AlertSource { DROWSINESS, DISTRACTION }

  /**
   * Single point of contact with [VoiceAlertGateway] for both
   * [DrowsinessController] and [DistractionController] -- prevents two
   * independently-triggered TTS messages from being spoken over each other.
   * Drowsiness always wins (fixed 2-source precedence, not a generic
   * priority scheme -- there are exactly two sources and no third is
   * planned). Tracks which source actually owns the currently-sounding
   * alert so a suppressed source's stopAlert() call can never cut off
   * whichever source IS legitimately active.
   */
  class AlertArbiter(private val voiceAlertGateway: VoiceAlertGateway) {
      private val TAG = "VitalGuardAlertArbiter"
      private var drowsinessCriticalActive = false
      private var activeSpeaker: AlertSource? = null

      fun setDrowsinessCriticalActive(active: Boolean) {
          drowsinessCriticalActive = active
      }

      fun requestVoiceAlert(source: AlertSource) {
          if (source == AlertSource.DISTRACTION && drowsinessCriticalActive) {
              Log.i(TAG, "Suppressed distraction alert -- drowsiness CRITICAL has priority")
              return
          }
          activeSpeaker = source
          when (source) {
              AlertSource.DROWSINESS -> voiceAlertGateway.triggerAlert()
              AlertSource.DISTRACTION -> voiceAlertGateway.triggerDistractionReminder()
          }
      }

      fun stopAlert(source: AlertSource) {
          if (activeSpeaker != source) {
              Log.i(TAG, "Ignored stopAlert from $source -- does not own the active alert (owner: $activeSpeaker)")
              return
          }
          activeSpeaker = null
          voiceAlertGateway.stopAlert()
      }
  }
  ```
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.AlertArbiterTest"`
  Expected: all 4 tests PASS.
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertArbiter.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterTest.kt
  git commit -m "Add AlertArbiter with ownership-tracked stopAlert()"
  ```

---

## Task 11: Route `DrowsinessController` through `AlertArbiter`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt`

**Interfaces:**
- Consumes: `AlertArbiter` (Task 10).
- Produces: `DrowsinessController(ClimateActuatorGateway, AlertArbiter)` — the changed constructor signature Task 15's wiring must use.

**Verified before writing this task:** the current `DrowsinessController.kt` (11-72) has `handleCritical()` calling `voiceGateway.triggerAlert()` directly, and `revertToBaseline()` (the single function both `handleNonCritical()` and `onConnectionLost()` funnel through — confirmed by reading the source, `onConnectionLost()` calls `revertToBaseline()` directly at line 39) calling `voiceGateway.stopAlert()` directly. This task must NOT duplicate the arbiter flag-clear in both callers separately — it belongs inside `revertToBaseline()` only.

- [ ] **Step 1:** Update `DrowsinessControllerTest.kt`'s `setUp()` first (TDD: this makes every existing test fail to compile until the constructor changes, which is the "write the failing test" step here — a signature change, not new behavior, so the failing signal is a compile error, not a runtime assertion):
  ```kotlin
  private lateinit var climate: FakeClimateActuatorGateway
  private lateinit var voice: FakeVoiceAlertGateway
  private lateinit var arbiter: AlertArbiter
  private lateinit var controller: DrowsinessController

  @Before
  fun setUp() {
      climate = FakeClimateActuatorGateway()
      voice = FakeVoiceAlertGateway()
      arbiter = AlertArbiter(voice)
      controller = DrowsinessController(climate, arbiter)
  }
  ```
  Leave every existing `@Test` function's body and assertions completely unchanged — they already assert against `voice.overrideApplied`/`voice.revertCalled`/`voice.stopCalled`-equivalents (actually `climate.overrideApplied`/`climate.revertCalled` plus `voice.alertTriggered`/`voice.stopCalled`), which still work unmodified since `voice` still exists as the fake underneath `arbiter`.
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DrowsinessControllerTest"`
  Expected: FAIL — compile error, `DrowsinessController(climate, arbiter)` doesn't match the old `(ClimateActuatorGateway, VoiceAlertGateway)` constructor.
- [ ] **Step 3:** Modify `DrowsinessController.kt`'s constructor and its two gateway-call sites:
  ```kotlin
  class DrowsinessController(
      private val climateGateway: ClimateActuatorGateway,
      private val alertArbiter: AlertArbiter
  ) {
      enum class GatewayActionStatus { NONE, OVERRIDE_APPLIED, OVERRIDE_FAILED, REVERTED, REVERT_FAILED }

      private val TAG = "VitalGuardController"

      var lastGatewayAction: GatewayActionStatus = GatewayActionStatus.NONE
          private set

      private var latched = false
      private var lastCorrelationId: String? = null

      fun onPayload(payload: TriggerPayload) {
          if (payload.correlationId == lastCorrelationId) {
              return
          }
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

      private fun handleCritical() {
          if (latched) return
          latched = true
          try {
              climateGateway.applyDrowsinessOverride()
              alertArbiter.setDrowsinessCriticalActive(true)
              alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS)
              lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
          } catch (t: Throwable) {
              Log.e(TAG, "Gateway failure applying drowsiness override: ${t.message}")
              lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
          }
      }

      private fun handleNonCritical() {
          if (!latched) return
          revertToBaseline()
      }

      private fun revertToBaseline() {
          latched = false
          try {
              climateGateway.revertToBaseline()
              alertArbiter.setDrowsinessCriticalActive(false)
              alertArbiter.stopAlert(AlertSource.DROWSINESS)
              lastGatewayAction = GatewayActionStatus.REVERTED
          } catch (t: Throwable) {
              Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
              lastGatewayAction = GatewayActionStatus.REVERT_FAILED
          }
      }
  }
  ```
  (`setDrowsinessCriticalActive(false)`/`stopAlert(AlertSource.DROWSINESS)` are placed inside `revertToBaseline()` specifically — since `onConnectionLost()` calls `revertToBaseline()` directly, both the ordinary score-drop path and the connection-lost fallback path are covered by this one placement, with no risk of one being updated and the other forgotten.)
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DrowsinessControllerTest"`
  Expected: all 6 existing tests PASS unmodified (assertions unchanged, only construction updated).
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt
  git commit -m "Route DrowsinessController's voice calls through AlertArbiter"
  ```

---

## Task 12: `TriggerPayload.kt` — add the `distraction` field

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPayloadTest.kt`

**Interfaces:**
- Consumes: the `distraction` schema shape (Task 6).
- Produces: `TriggerPayload.distraction: DistractionInfo`, consumed by Task 13's `DistractionController`.

- [ ] **Step 1:** Read the current `TriggerPayloadTest.kt` in full first to match its existing style/imports before adding a test. Add a test asserting the new field deserializes correctly from a JSON string matching Task 6's schema shape:
  ```kotlin
  @Test
  fun `deserializes the distraction object correctly`() {
      val json = """
          {
            "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
            "state": "NORMAL",
            "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
            "reason": "test", "correlationId": "vg-test-0001",
            "distraction": {
              "score": 0.9, "state": "CRITICAL", "yawDeg": 45.0, "pitchDeg": 5.0,
              "handsVisibility": "FULL", "handsOnWheel": true, "reason": "gaze_off_road"
            }
          }
      """.trimIndent()
      val payload = Json { ignoreUnknownKeys = true }.decodeFromString<TriggerPayload>(json)

      assertEquals(TriggerPayload.STATE_CRITICAL, payload.distraction.state)
      assertEquals(45.0f, payload.distraction.yawDeg)
      assertEquals("FULL", payload.distraction.handsVisibility)
      assertTrue(payload.distraction.handsOnWheel)
  }
  ```
  (Match the existing test file's actual JSON-decoding call style if it differs from the snippet above — read the file before writing this, don't assume.)
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.TriggerPayloadTest"`
  Expected: FAIL — `TriggerPayload` has no `distraction` property yet.
- [ ] **Step 3:** Modify `TriggerPayload.kt`:
  ```kotlin
  package com.vitalguard.ai

  import kotlinx.serialization.Serializable

  @Serializable
  data class TriggerFeatures(
      val perclos: Float,
      val eyeOpenProbability: Float,
      val headEulerAngleX: Float
  )

  @Serializable
  data class DistractionInfo(
      val score: Float,
      val state: String,
      val yawDeg: Float,
      val pitchDeg: Float,
      val handsVisibility: String,
      val handsOnWheel: Boolean,
      val reason: String
  ) {
      companion object {
          const val VISIBILITY_FULL = "FULL"
          const val VISIBILITY_PARTIAL = "PARTIAL"
          const val VISIBILITY_UNKNOWN = "UNKNOWN"
      }
  }

  @Serializable
  data class TriggerPayload(
      val timestampMs: Long,
      val source: String,
      val score: Float,
      val confidence: Float,
      val state: String,
      val features: TriggerFeatures,
      val reason: String,
      val correlationId: String,
      val distraction: DistractionInfo
  ) {
      companion object {
          const val STATE_NORMAL = "NORMAL"
          const val STATE_WARNING = "WARNING"
          const val STATE_CRITICAL = "CRITICAL"
          const val STATE_UNKNOWN = "UNKNOWN"
      }
  }
  ```
- [ ] **Step 4:** Exactly two existing call sites construct `TriggerPayload(...)` directly and now fail to compile (verified via `grep -rn "TriggerPayload(" aaos-cockpit-app/app/src/test aaos-cockpit-app/app/src/main` — the only other match is the class definition itself in `TriggerPayload.kt`): `DrowsinessControllerTest.kt:21`'s `payload()` helper, and `TriggerPollClientTest.kt:19`'s `samplePayload()` helper. Add a `distraction = DistractionInfo(score = 0.0f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f, handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test")` argument to both (a neutral, non-distracting default — neither test file is about distraction behavior, so the distraction object should never influence their outcome).
- [ ] **Step 5:** Run: `./gradlew :app:testDebugUnitTest`
  Expected: the full existing Kotlin suite (including the tests fixed in Step 4) PASSES.
- [ ] **Step 6:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPayloadTest.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt
  git commit -m "Add DistractionInfo to TriggerPayload"
  ```

---

## Task 13: `DistractionController`

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt`

**Interfaces:**
- Consumes: `AlertArbiter` (Task 10), `TriggerPayload.distraction` (Task 12).
- Produces: `DistractionController(AlertArbiter)`, consumed by Task 15's wiring.

- [ ] **Step 1:** Write the failing tests, mirroring `DrowsinessControllerTest`'s 6 mandatory cases against `payload.distraction.state` instead of `payload.state`, and asserting on the arbiter's underlying fake voice gateway (`distractionReminderTriggered`/`stopCalled`) instead of climate:
  ```kotlin
  package com.vitalguard.ai

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  class DistractionControllerTest {
      private lateinit var voice: FakeVoiceAlertGateway
      private lateinit var arbiter: AlertArbiter
      private lateinit var controller: DistractionController

      @Before
      fun setUp() {
          voice = FakeVoiceAlertGateway()
          arbiter = AlertArbiter(voice)
          controller = DistractionController(arbiter)
      }

      private fun payload(distractionState: String, correlationId: String) = TriggerPayload(
          timestampMs = 0L, source = "test", score = 0.1f, confidence = 1.0f,
          state = TriggerPayload.STATE_NORMAL,
          features = TriggerFeatures(perclos = 0.0f, eyeOpenProbability = 1.0f, headEulerAngleX = 0.0f),
          reason = "test", correlationId = correlationId,
          distraction = DistractionInfo(
              score = 0.9f, state = distractionState, yawDeg = 45.0f, pitchDeg = 5.0f,
              handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
          ),
      )

      @Test
      fun `normal operation below threshold never calls the gateway`() {
          controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0001"))

          assertFalse(voice.distractionReminderTriggered)
      }

      @Test
      fun `idempotency - repeated CRITICAL with same correlationId fires gateway only once`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

          assertTrue(voice.distractionReminderTriggered)
          voice.distractionReminderTriggered = false
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          assertFalse(voice.distractionReminderTriggered)
      }

      @Test
      fun `explicit NORMAL after CRITICAL reverts (stops the reminder)`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0002"))

          assertTrue(voice.stopCalled)
      }

      @Test
      fun `WARNING state never calls the gateway (overlay-only, no action)`() {
          controller.onPayload(payload(TriggerPayload.STATE_WARNING, "vg-0001"))

          assertFalse(voice.distractionReminderTriggered)
      }

      @Test
      fun `connection-lost reverts without an explicit payload`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onConnectionLost()

          assertTrue(voice.stopCalled)
      }

      @Test
      fun `gateway throwing on trigger is caught, does not crash, does not retry`() {
          voice.throwOnTrigger = true

          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          // no crash reaching this line is itself part of what's being verified.
          // latched is set to true BEFORE the try block in handleCritical(), so
          // it stays true even though the call inside it threw.

          // A second call with the SAME correlationId would be blocked by
          // onPayload()'s own correlationId dedupe before ever reaching
          // handleCritical() again -- that would make this test pass without
          // ever actually exercising the `if (latched) return` retry-prevention
          // logic it claims to test. Use a DIFFERENT correlationId so this call
          // genuinely reaches handleCritical() and is blocked by latched, not
          // by the unrelated dedupe check.
          voice.throwOnTrigger = false
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002"))
          assertFalse(voice.distractionReminderTriggered)
      }
  }
  ```
  Note: `TriggerPayload.STATE_WARNING`/`STATE_NORMAL`/etc. constants exist on `TriggerPayload`, not `DistractionInfo` — reuse them for the `distractionState` string argument since the state strings are the same 3-of-4 values (`DistractionInfo`'s `state` field never uses `"UNKNOWN"`, only `NORMAL`/`WARNING`/`CRITICAL`, per Task 6's schema).
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DistractionControllerTest"`
  Expected: FAIL — `DistractionController` doesn't exist yet.
- [ ] **Step 3:** Write `DistractionController.kt`, mirroring `DrowsinessController`'s idempotency/fallback shape but with no climate gateway and no distinct "gateway failure status" enum (that enum was drowsiness-specific bookkeeping, not a shared FSM requirement — keep this class minimal, matching its narrower scope):
  ```kotlin
  package com.vitalguard.ai

  import android.util.Log

  /**
   * Independent FSM for distraction, arbitrated through the same
   * [AlertArbiter] as [DrowsinessController] but tracking its own latch
   * state entirely separately -- drowsiness and distraction are
   * physiologically independent and must never be merged into one state
   * enum (see design doc Decision 5).
   */
  class DistractionController(private val alertArbiter: AlertArbiter) {
      private val TAG = "VitalGuardDistractionController"

      private var latched = false
      private var lastCorrelationId: String? = null

      fun onPayload(payload: TriggerPayload) {
          if (payload.correlationId == lastCorrelationId) {
              return
          }
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

      private fun handleCritical() {
          if (latched) return
          latched = true
          try {
              alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION)
          } catch (t: Throwable) {
              Log.e(TAG, "Gateway failure requesting distraction reminder: ${t.message}")
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
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DistractionControllerTest"`
  Expected: all 6 tests PASS.
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt
  git commit -m "Add DistractionController"
  ```

---

## Task 14: `AlertArbiterIntegrationTest` — the cross-component regression

**Files:**
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt`

**Interfaces:**
- Consumes: `DrowsinessController` (Task 11), `AlertArbiter` (Task 10), `DistractionController` (Task 13) — all real, only `VoiceAlertGateway`/`ClimateActuatorGateway` are faked.

- [ ] **Step 1:** Write the failing test. This is the one scenario that needs all three real components together — it doesn't fit `DrowsinessControllerTest` (fakes the arbiter), `AlertArbiterTest` (fakes the gateway, no controllers involved), or `DistractionControllerTest` (distraction alone):
  ```kotlin
  package com.vitalguard.ai

  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  class AlertArbiterIntegrationTest {
      private lateinit var climate: FakeClimateActuatorGateway
      private lateinit var voice: FakeVoiceAlertGateway
      private lateinit var arbiter: AlertArbiter
      private lateinit var drowsinessController: DrowsinessController
      private lateinit var distractionController: DistractionController

      @Before
      fun setUp() {
          climate = FakeClimateActuatorGateway()
          voice = FakeVoiceAlertGateway()
          arbiter = AlertArbiter(voice)
          drowsinessController = DrowsinessController(climate, arbiter)
          distractionController = DistractionController(arbiter)
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
          assertTrue(voice.alertTriggered) // drowsiness genuinely spoke

          drowsinessController.onConnectionLost()

          // With the flag still set, this would be silently suppressed --
          // asserting it speaks proves setDrowsinessCriticalActive(false)
          // actually ran, not just that climateGateway.revertToBaseline() did.
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
          ) // suppressed, never spoke

          // distraction's own score recovers -- its controller reverts and calls stopAlert
          distractionController.onPayload(
              drowsinessPayload(TriggerPayload.STATE_CRITICAL, "vg-0003").copy(
                  distraction = DistractionInfo(
                      score = 0.1f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
                      handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = true, reason = "test",
                  )
              )
          )

          assertFalse(voice.stopCalled) // drowsiness's still-active alert must survive
      }
  }
  ```
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.AlertArbiterIntegrationTest"`
  Expected: with Tasks 10-13 already landed, this should actually PASS immediately (this task is a regression lock, not new functionality) — if it fails, that means one of Tasks 10-13's implementations has a real bug; stop and fix the root cause in the appropriate earlier task's file, do not patch around it here.
- [ ] **Step 3:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt
  git commit -m "Add cross-component regression test for the AlertArbiter flag-clear and ownership-tracking fixes"
  ```

---

## Task 15: Wire `DistractionController` into `VitalGuardMonitorService`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt`

**Interfaces:**
- Consumes: `DrowsinessController` (Task 11, changed constructor), `AlertArbiter` (Task 10), `DistractionController` (Task 13).
- Produces: the production wiring the whole feature runs through.

**Verified before writing this task:** the real, current `onCreate()` (lines 34-59) constructs `DrowsinessController(climateGateway, voiceGateway)` at line 50, then `TriggerPollClient` at lines 52-57 with `onPayload = { payload -> controller.onPayload(payload) }` / `onConnectionLost = { controller.onConnectionLost() }`. This is the ONLY production call site (confirmed via `grep -rn "DrowsinessController(" aaos-cockpit-app` — the only other match is the test file already updated in Task 11).

- [ ] **Step 1:** Modify `onCreate()`'s wiring block:
  ```kotlin
  val gatewayModeStore = PrefsGatewayModeStore(this)
  val climateGateway: ClimateActuatorGateway = when (gatewayModeStore.get()) {
      GatewayMode.REAL -> RealClimateActuatorGateway(this)
      GatewayMode.FAKE -> FakeClimateActuatorGateway()
  }
  val voiceGateway: VoiceAlertGateway = when (gatewayModeStore.get()) {
      GatewayMode.REAL -> RealVoiceAlertGateway(this)
      GatewayMode.FAKE -> FakeVoiceAlertGateway()
  }
  val alertArbiter = AlertArbiter(voiceGateway)
  val drowsinessController = DrowsinessController(climateGateway, alertArbiter)
  val distractionController = DistractionController(alertArbiter)

  pollClient = TriggerPollClient(
      fetcher = HttpTriggerFetcher(CONTAINER_NODE_BASE_URL),
      scope = serviceScope,
      onPayload = { payload ->
          drowsinessController.onPayload(payload)
          distractionController.onPayload(payload)
      },
      onConnectionLost = {
          drowsinessController.onConnectionLost()
          distractionController.onConnectionLost()
      },
  )
  pollClient.start()
  ```
- [ ] **Step 2:** No test file targets `VitalGuardMonitorService` directly today (confirmed: no `VitalGuardMonitorServiceTest.kt` exists in the repo) — verify by compiling, same fallback as Task 9's Step 3 if Gradle isn't runnable in this environment (visually re-read the edited block end-to-end for syntax correctness).
- [ ] **Step 3:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt
  git commit -m "Wire DistractionController into the production polling pipeline"
  ```

---

## Task 16: Fix the stale Global Constraint and update CLAUDE.md

**Files:**
- Modify: `docs/superpowers/plans/2026-07-28-cv-backend-remediation.md`
- Modify: `CLAUDE.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1:** In `docs/superpowers/plans/2026-07-28-cv-backend-remediation.md`'s Global Constraints section, find the line: "Distraction/yaw-based detection is explicitly out of scope for this plan (deferred by the user) — do not add a yaw signal, a new schema state, or any FSM change for it." Append a note directly after it (do not delete the original line — it was accurate for that plan's own scope; this is a superseded-by annotation, not a correction):
  ```markdown
  **Superseded:** this deferral was reversed by explicit user decision in
  `docs/superpowers/plans/2026-07-30-distraction-detection.md` — that plan
  adds a yaw signal, a new `distraction` schema object, and new Kotlin FSM
  components. This line is kept for historical accuracy about THIS plan's
  own original scope, not as a current restriction.
  ```
- [ ] **Step 2:** In `CLAUDE.md`, add a new subsection under (or near) the existing "Known Deviations from Proposal" section — same rigor/style as that section's existing entries (concrete numbers, judge-facing reasoning, explicit non-claims):
  ```markdown
  ### Distraction Detection (added, not in original proposal)

  Two additional signals were added to the DMS pipeline beyond the original
  drowsiness-only scope: **gaze/head-off-road** (head pitch AND yaw from the
  same MediaPipe Face Landmarker transformation matrix already used for
  drowsiness, combined with the existing debounced eye-closure signal to
  disambiguate "looking down at a phone" from "nodding off") and
  **hands-off-wheel** (a new MediaPipe **Hand Landmarker** model, tracking
  hand-landmark position against a fixed wheel region — a proxy for phone
  use, not true object detection, since MediaPipe has no phone/object
  detector and training one was not feasible in this timeframe).

  Delivered as a new `distraction` object alongside the existing drowsiness
  payload (`contracts/trigger.schema.json`), reacted to by a separate
  `DistractionController` in the Android app, arbitrated through a new
  `AlertArbiter` so a distraction reminder never overrides — or gets wrongly
  cut off by — an active drowsiness alert (drowsiness always takes
  priority). Distraction never touches HVAC — voice-only, and a lighter
  audio-focus request than drowsiness's exclusive one.

  Judge-facing reason: same build-vs-buy reasoning as the Face Landmarker
  migration — MediaPipe's Hand Landmarker is a pretrained, well-validated
  model used as-is, not a custom-trained classifier. The specific
  thresholds/weights (`PITCH_OFF_ROAD_THRESHOLD`, `YAW_OFF_ROAD_THRESHOLD`,
  `W_GAZE`/`W_HANDS`, `DistractionTriggerEmitter`'s sustain/cooldown) are
  reasoned starting points, not independently validated — same disclosed
  stance as the drowsiness formula's own weights. The wheel-region
  calibration is tied to the specific camera framing tested against and has
  not been verified on the real Container Node/Skycraft camera.
  ```
- [ ] **Step 3:** Commit:
  ```bash
  git add docs/superpowers/plans/2026-07-28-cv-backend-remediation.md CLAUDE.md
  git commit -m "Document the distraction-detection feature, fix the stale out-of-scope constraint"
  ```

---

## Task 17: Acceptance gate — run against all 5 real videos inside the real container

**Files:** none created/modified except the results doc below — this task runs the gates and records the outcome.

**Interfaces:** consumes everything from Tasks 1-9 (the Python side only — Kotlin has no equivalent real-hardware gate available in this environment).

- [ ] **Step 1:** Rebuild the image fresh:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:distraction-gate .
  ```
- [ ] **Step 2:** Re-run all 5 videos through the container:
  ```bash
  for name in normal drowsy distracted full-stream-2 full-stream-facemp4; do
    MSYS_NO_PATHCONV=1 docker run --rm \
      -v "$(pwd)/out/${name}.mp4:/data/${name}.mp4:ro" \
      -v "$(pwd)/out:/app/out" \
      vital-guard-dms:distraction-gate --video "/data/${name}.mp4" --host 0.0.0.0 --port 8765 \
      --out-csv "/app/out/evidence_${name}_distraction_gate.csv"
  done
  ```
- [ ] **Step 3: Gate 1 — yaw physical plausibility.** Reuse the exact gap-aware awk pattern already fixed for pitch (Task 7 Step 3 of the CV remediation plan — tracks row numbers, skips and logs any face-loss gap instead of comparing across it; the `yaw_deg` CSV column is at position 8, 1-indexed, per Task 7 of this plan's header row `ts,has_face,blink_score,head_pitch,score,state,signal,yaw_deg,hands_visibility,hands_on_wheel,distraction_score,distraction_state,distraction_signal`):
  ```bash
  for f in normal drowsy distracted full-stream-2 full-stream-facemp4; do
    echo "=== $f ==="
    awk -F',' '
      NR>1 && $8!="" {
        if (prev_val != "" && NR == prev_row + 1) {
          diff = $8 - prev_val
          if (diff < 0) diff = -diff
          if (diff > 90) print "JUMP at row " NR ": " prev_val " -> " $8
        } else if (prev_val != "" && NR != prev_row + 1) {
          print "SKIPPED (gap, rows " prev_row " -> " NR "): " prev_val " -> " $8
        }
        prev_val = $8
        prev_row = NR
      }
    ' "out/evidence_${f}_distraction_gate.csv"
  done
  ```
  Expected: zero `JUMP` lines on all 5 videos. Any `SKIPPED` line is informational only. Then visually cross-check yaw sign/direction on 2-3 frames of `distracted.mp4` (same procedure as Task 2 Step 7).
- [ ] **Step 4: Gate 2 — behavioral correctness per segment.** Segment boundaries per `CV_REMEDIATION_RESULTS.md`'s empirical findings for `full-stream-2.mp4`: Normal ~2-7.9s, "STAGE 2: DROWSY" ~8-11.8s, "STAGE 3: DISTRACTED" ~12-15.9s, closing reprise ~16.3-19.27s.

  **Negative checks (must never be `CRITICAL`) use `$12` (`distraction_state`), not `$13` (`distraction_signal`).** `distraction_signal` is `DistractionTriggerEmitter`'s edge-triggered output — it prints `"CRITICAL"` only on the single frame a episode *starts*, then `None` for every subsequent frame while that episode is still active (mirrors `TriggerEmitter`'s own `update()` shape). If a real `CRITICAL` episode began just before a restricted segment's boundary and stayed sustained across it, checking only `$13` would miss the overlap entirely, since the one edge-triggering frame could fall outside the scanned window while every frame *inside* the restricted window still correctly shows `distraction_state="CRITICAL"`. `distraction_state` (from `_state_for_score()`) reflects the current score-threshold state on every frame, so it's the correct column for "was this ever regarded as critical during this window," not just "did an episode start here." Positive checks (`distracted.mp4`, `full-stream-2.mp4`'s STAGE 3) still use `$13` — finding at least one edge-trigger event is exactly what "did it fire" means there.
  ```bash
  echo "=== distracted.mp4 max distraction_score (expect a CRITICAL signal somewhere) ==="
  awk -F',' 'NR>1 && $1+0>=0 {print $11}' out/evidence_distracted_distraction_gate.csv | sort -g | tail -1
  awk -F',' 'NR>1 && $13=="CRITICAL" {print}' out/evidence_distracted_distraction_gate.csv

  echo "=== full-stream-2.mp4 STAGE 3 (12-15.9s) -- expect CRITICAL ==="
  awk -F',' 'NR>1 && $1+0>=12.0 && $1+0<=15.9 {print $1","$11","$12","$13}' out/evidence_full-stream-2_distraction_gate.csv

  echo "=== full-stream-2.mp4 STAGE 2 DROWSY (8-11.8s) -- expect NO CRITICAL (state, not just signal) ==="
  awk -F',' 'NR>1 && $1+0>=8.0 && $1+0<=11.8 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_full-stream-2_distraction_gate.csv

  echo "=== drowsy.mp4 and full-stream-facemp4.mp4 -- expect NO CRITICAL anywhere (state, not just signal) ==="
  awk -F',' 'NR>1 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_drowsy_distraction_gate.csv
  awk -F',' 'NR>1 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_full-stream-facemp4_distraction_gate.csv

  echo "=== normal.mp4 -- expect NO CRITICAL anywhere (state, not just signal) ==="
  awk -F',' 'NR>1 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_normal_distraction_gate.csv
  ```
  If a positive case (`distracted.mp4`, `full-stream-2.mp4`'s STAGE 3) doesn't cross `CRITICAL` but the underlying signals are physically plausible (Gate 1 passed, yaw/pitch values look real), treat this as path-(b) progress per the design doc's framing — hand off threshold/weight tuning as follow-up work, do not reopen Tasks 1-9 to chase it. If a negative case falsely fires, that IS a real failure — investigate the specific frame's `yaw_deg`/`hands_on_wheel` values before concluding it's a threshold issue vs. a real logic bug. Note that these negative checks scanning `$12` across each entire file also cover the calibration-window false-positive risk fixed in Task 7 Step 3 (t<1.0s of every video) — no separate dedicated check for that window is needed.
- [ ] **Step 5: Latency.** Run `measure_latency.py` (Task 9) across all 5 videos, mounted read-only into the container:
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd)/out/normal.mp4:/data/normal.mp4:ro" \
    -v "$(pwd)/out/drowsy.mp4:/data/drowsy.mp4:ro" \
    -v "$(pwd)/out/distracted.mp4:/data/distracted.mp4:ro" \
    -v "$(pwd)/out/full-stream-2.mp4:/data/full-stream-2.mp4:ro" \
    -v "$(pwd)/out/full-stream-facemp4.mp4:/data/full-stream-facemp4.mp4:ro" \
    -v "$(pwd)/measure_latency.py:/app/measure_latency.py:ro" \
    --entrypoint python vital-guard-dms:distraction-gate /app/measure_latency.py \
    /data/normal.mp4 /data/drowsy.mp4 /data/distracted.mp4 /data/full-stream-2.mp4 /data/full-stream-facemp4.mp4
  ```
  Compare COMBINED p95 against 150ms. If exceeded, apply the downscale-then-re-run-Gate-1 contingency (same as the CV remediation plan's Decision 5) — downscale the frame before BOTH `mp.Image` constructions (Face and Hand Landmarker share the same input frame), then re-run Step 3 from scratch, not just re-measure latency.
- [ ] **Step 6:** Record the full outcome in `dms-ai-engine/DISTRACTION_DETECTION_RESULTS.md`, mirroring `CV_REMEDIATION_RESULTS.md`'s level of concrete detail (exact numbers, exact awk output, not vague summaries) — Gate 1 result per video, Gate 2 result per segment (including which path, (a) or (b), if applicable), latency numbers, any downscale decision and its Gate 1 re-check, and the wheel-region values actually used (from Task 3).
- [ ] **Step 7:** Run the full Python test suite one final time to confirm nothing regressed across all 17 tasks: `pytest dms-ai-engine -v`
- [ ] **Step 8:** Commit:
  ```bash
  git add dms-ai-engine/DISTRACTION_DETECTION_RESULTS.md
  git commit -m "Record distraction-detection acceptance-gate results"
  ```

---

## Self-Review Notes

- **Spec coverage:** Decision 1 (gaze-off-road, shared baseline, `is_gaze_off_road`) → Tasks 2, 4, 7. Decision 2 (hands-off-wheel, wheel region, tri-state visibility, `hands_on_wheel` semantics) → Tasks 1, 3, 7. Decision 3 (composite score/emitter, corrected weights) → Tasks 4, 5, 7. Decision 4 (schema) → Task 6. Decision 5 (Kotlin: `VoiceAlertGateway` fix, `AlertArbiter` with ownership tracking, `DrowsinessController`/`DistractionController` split, wiring) → Tasks 9-15. Decision 6 (stale Global Constraint) → Task 16. Acceptance gate → Task 17.
- **Real interfaces verified, not assumed:** GitNexus confirmed `head_pose.py`'s exact importers/callers (3 files, `extract_pitch_deg` has exactly 2 direct callers) before Task 2/7 were written. Direct reads confirmed `run_real_video()`'s exact current line range (152-256), `VoiceAlertGateway.triggerAlert()`'s real (parameterless) signature (caught a wrong assumption in an earlier design draft, fixed in the spec and reflected correctly in Task 9), `DrowsinessController.onConnectionLost()`'s exact delegation to `revertToBaseline()` (Task 11), and `VitalGuardMonitorService.kt`'s exact production wiring block (Task 15) — none of these were estimated.
- **Type/name consistency:** `DistractionInfo`'s `state` field only ever takes `NORMAL`/`WARNING`/`CRITICAL` (never `UNKNOWN`) across the schema (Task 6), `main.py` (Task 7), `TriggerPayload.kt` (Task 12), and every test payload constructed in Tasks 12-14 — checked consistently. `AlertSource`/`requestVoiceAlert`/`stopAlert`'s signatures match exactly between Task 10's definition and Tasks 11/13/14's call sites (no `message: String` parameter anywhere, per the corrected design).
- **No placeholders:** the one deliberately-marked "replace before committing" value is `WHEEL_REGION`'s four coordinates in Task 3 Step 6 — this is the one place, like Task 3's pitch-axis probe in the prior plan, that genuinely cannot be predetermined and requires the implementer's own empirical measurement inside the real container; every other task has concrete, final values.
