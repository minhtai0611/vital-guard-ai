# CV Backend Remediation (Face Landmarker Migration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 6-point-`solvePnP` head-pose estimator and hand-computed
EAR eye-closure signal (which suffer real, evidenced failures on real video —
PnP flip-ambiguity, single-frame score collapse) with MediaPipe Face
Landmarker's `facial_transformation_matrixes` and `face_blendshapes`, per
`docs/superpowers/specs/2026-07-28-cv-backend-remediation-design.md`.

**Architecture:** A new shared `services/face_landmarker_client.py` wraps
`FaceLandmarker` construction (explicit CPU delegate, `VIDEO` running mode,
model bundle baked into the Docker image at build time). `services/head_pose.py`
is rewritten around a rotation-matrix → Euler-angle utility whose pitch-axis
assignment is determined empirically against real reference video (the exact
axis cannot be derived analytically the way the old 6-point solvePnP's was,
because Face Landmarker's canonical face model convention isn't ours to
choose). `services/eye_state.py` is rewritten around blendshape scores plus a
new raw-signal-level hysteresis tracker. `main.py`'s `run_real_video()` is
rewired to the new client, with a monotonic-timestamp guard around
`CAP_PROP_POS_MSEC`. All Task 7/8-era tests that exercised the old API are
replaced, not patched.

**Tech Stack:** Python 3, `mediapipe` (Tasks API — `mediapipe.tasks.python.vision.FaceLandmarker`),
`opencv-contrib-python` (transitive via mediapipe), Docker.

## Global Constraints

- The `face_landmarker.task` model bundle is fetched via `RUN curl`/`wget` in
  the Dockerfile at **build time** — never fetched by Python at container
  runtime (preserves the no-cloud-round-trip-in-the-runtime-path principle
  already locked in for this project).
- `BaseOptions(delegate=BaseOptions.Delegate.CPU)` must be set explicitly
  everywhere a `FaceLandmarker` is constructed — never rely on the default.
- `RunningMode.VIDEO` for the file-based `--video` path, with a strictly
  monotonically increasing `timestamp_ms`. `RunningMode.IMAGE` must not be
  used (loses frame-to-frame tracking continuity — the exact robustness
  property this migration exists to gain). `RunningMode.LIVE_STREAM` is
  future work (real camera), not built in this plan.
- Gate 1 (physical plausibility): zero frame-to-frame jumps greater than
  **90°** anywhere in the drowsy video's full pitch trajectory, plus a visual
  cross-check against at least 2-3 source frames.
- Gate 2 (score outcome), either satisfies "done": (a) drowsy video score
  exceeds 0.85 AND normal/distracted don't false-positive AND p95 latency
  (frame-read → inference → score-computed, across all 4 videos) ≤150ms
  (CLAUDE.md's KPI) — or (b) Gate 1 passes but 0.85 isn't reached; treat as
  genuine progress, hand off to threshold/sustain-window tuning (out of this
  plan's scope), not a reason to redo this plan's tasks.
- If p95 latency exceeds 150ms and input-resolution downscaling is applied
  as a fix, Gate 1 must be re-run after downscaling — downscaling can degrade
  landmark accuracy, not just speed.
- Every gate must be verified inside the real Docker container (reusing
  `test_container.sh`'s pattern), built targeting the Container Node's real
  CPU architecture if known (`docker buildx build --platform ...`) — passing
  only on the Windows dev machine does not satisfy any gate. If `pip install
  mediapipe` fails at build time due to an architecture mismatch, that
  failure *is* the escalation evidence — don't ask CarSky/the mentor
  speculatively beforehand.
- Distraction/yaw-based detection is explicitly out of scope for this plan
  (deferred by the user) — do not add a yaw signal, a new schema state, or
  any FSM change for it.
- `contracts/trigger.schema.json`'s shape is unchanged by this plan — same
  `headEulerAngleX`/`eyeOpenProbability` fields, just computed differently.

---

## File Structure

- Create: `dms-ai-engine/services/face_landmarker_client.py` — shared
  `FaceLandmarker` construction (CPU delegate, VIDEO mode) + monotonic
  timestamp guard.
- Rewrite: `dms-ai-engine/services/head_pose.py` — rotation-matrix → Euler
  utility + empirically-determined pitch extraction. Old 6-point-solvePnP
  code and `MODEL_LANDMARK_INDICES`/`MODEL_3D_POINTS` constants removed.
- Rewrite: `dms-ai-engine/services/eye_state.py` — blendshape-based blink
  score + new `BlinkStateTracker` (raw-signal hysteresis). Old EAR geometry
  code and `LEFT_EYE_INDICES`/`RIGHT_EYE_INDICES` constants removed.
- Modify: `dms-ai-engine/main.py` — `run_real_video()` rewired to the new
  client/head_pose/eye_state APIs; monotonic `CAP_PROP_POS_MSEC` guard.
- Modify: `dms-ai-engine/requirements.txt` — mediapipe version verified to
  support `output_facial_transformation_matrixes`/`output_face_blendshapes`
  on both Windows dev and Linux container.
- Modify: `dms-ai-engine/Dockerfile` — bake in `face_landmarker.task` at
  build time.
- Replace: `dms-ai-engine/tests/test_head_pose.py`,
  `dms-ai-engine/tests/test_eye_state.py` — new API surface, same rigor
  (regression tests for the pitch axis, same as Task 8's methodology).
- Modify: `dms-ai-engine/tests/test_main.py` — replace `_FakeVideoCapture`'s
  companion mock (`_fake_mediapipe_with_no_face_ever`, which simulates the
  old `mp.solutions.face_mesh` surface) with a fake matching
  `FaceLandmarker.detect_for_video()`'s return shape; update the 4 tests that
  use it plus the 1 `average_ear`-based test (11 test functions total
  affected across the 3 files, confirmed via GitNexus call-graph — not an
  estimate).
- Create: `dms-ai-engine/out/probe_pitch_axis.py` (throwaway investigation
  script, not committed) — used once in Task 3 to empirically determine the
  pitch axis; its *finding* gets committed as code + regression tests, the
  script itself does not need to survive.
- Create: `dms-ai-engine/measure_latency.py` — committed, reusable latency
  measurement script for Task 7's gate.

---

## Task 1: Pin mediapipe version for the Tasks API, bake the model bundle into the Docker image

**Files:**
- Modify: `dms-ai-engine/requirements.txt`
- Modify: `dms-ai-engine/Dockerfile`

**Interfaces:** none (build/dependency config only) — every later task depends
on this landing first.

- [ ] **Step 1:** Verify the currently-pinned `mediapipe==0.10.14` actually
  exposes the Tasks API surface needed
  (`mediapipe.tasks.python.vision.FaceLandmarker`,
  `FaceLandmarkerOptions(output_facial_transformation_matrixes=True,
  output_face_blendshapes=True)`) on the Windows dev machine:
  ```bash
  cd dms-ai-engine
  python -c "
  from mediapipe.tasks.python import vision
  from mediapipe.tasks.python.core import base_options as bo
  opts = vision.FaceLandmarkerOptions(
      base_options=bo.BaseOptions(model_asset_path='dummy.task'),
      running_mode=vision.RunningMode.VIDEO,
      output_face_blendshapes=True,
      output_facial_transformation_matrixes=True,
  )
  print('FaceLandmarkerOptions constructed OK:', opts.output_face_blendshapes, opts.output_facial_transformation_matrixes)
  "
  ```
  Expected: prints `True True` (the `model_asset_path='dummy.task'` doesn't
  need to exist yet for just constructing the options object — only
  `create_from_options` would need a real file). If this raises
  `AttributeError`/`ImportError`, the pinned version doesn't support the
  Tasks API surface needed; try the next-newest 0.10.x release and re-check
  (do not jump to 1.0.0 — already confirmed to drop the legacy `solutions`
  API entirely from the Windows wheel, and nothing in this plan needs
  `solutions` anymore, so 1.0.0 becomes viable **only if** this exact
  Options-construction check also passes on 1.0.0 on Windows — verify before
  assuming either way).
- [ ] **Step 2:** Repeat Step 1's exact check inside a throwaway Linux
  container using the currently-pinned version, to confirm it's not a
  Windows-only capability:
  ```bash
  docker run --rm python:3.12-slim bash -c "pip install --quiet mediapipe==0.10.14 2>&1 | tail -3 && python -c \"
  from mediapipe.tasks.python import vision
  from mediapipe.tasks.python.core import base_options as bo
  opts = vision.FaceLandmarkerOptions(base_options=bo.BaseOptions(model_asset_path='dummy.task'), running_mode=vision.RunningMode.VIDEO, output_face_blendshapes=True, output_facial_transformation_matrixes=True)
  print('OK', opts.output_face_blendshapes, opts.output_facial_transformation_matrixes)
  \""
  ```
- [ ] **Step 3:** Once a working version is confirmed on both platforms,
  update the comment block in `requirements.txt` to record which version was
  verified for the Tasks API surface (keep the existing comments about why
  `opencv-python` isn't listed separately and why 1.0.0/0.10.35 dropped
  `solutions` — still relevant context for anyone reading this file later;
  add a new paragraph noting the Tasks API verification).
- [ ] **Step 4:** Download the `face_landmarker.task` model bundle once
  locally to inspect its size and confirm a stable download URL exists
  (the official MediaPipe model is published at a Google-hosted storage
  URL — check the current official docs for the exact URL rather than
  guessing one; do not hardcode a URL without having verified it resolves).
  Record the file size in a Dockerfile comment (affects image size).
- [ ] **Step 5:** Add the model bundle download to `Dockerfile`, as a
  build-time step (never a runtime fetch):
  ```dockerfile
  # face_landmarker.task is fetched at BUILD time only, never at runtime --
  # preserves the no-cloud-round-trip-in-the-runtime-path principle. Baked
  # into the image so a running container never needs network access to
  # function. Verify the URL below is still current before relying on it.
  RUN mkdir -p /app/models && \
      curl -fL -o /app/models/face_landmarker.task \
      "<the URL verified in Step 4>"
  ```
  Place this after the `apt-get`/system-libs step and before `COPY
  services/`, so Docker's layer cache keeps the (large, slow) download
  cached across rebuilds that only touch Python source.
- [ ] **Step 6:** Rebuild the image and confirm the model file is present
  and non-empty inside the container:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:facelandmarker-check .
  docker run --rm --entrypoint ls vital-guard-dms:facelandmarker-check -la /app/models/
  ```
  Expected: `face_landmarker.task` listed with a non-zero size matching
  Step 4's recorded size.
- [ ] **Step 7:** Commit:
  ```bash
  git add dms-ai-engine/requirements.txt dms-ai-engine/Dockerfile
  git commit -m "Verify mediapipe Tasks API support, bake face_landmarker.task into the image at build time"
  ```

---

## Task 2: `services/face_landmarker_client.py` — shared construction + monotonic timestamp guard

**Files:**
- Create: `dms-ai-engine/services/face_landmarker_client.py`
- Create: `dms-ai-engine/tests/test_face_landmarker_client.py`

**Interfaces:**
- Consumes: the model bundle path from Task 1 (`/app/models/face_landmarker.task`
  in the container; pass as a parameter, don't hardcode the path inside this
  module — the caller supplies it).
- Produces:
  - `build_video_mode_landmarker(model_path: str) -> FaceLandmarker` — the
    one place `FaceLandmarkerOptions`/`BaseOptions` get constructed, with
    `delegate=BaseOptions.Delegate.CPU` and `running_mode=RunningMode.VIDEO`
    always set, never left to a default.
  - `class MonotonicTimestamp` — `next(self, raw_ms: float) -> int`, used by
    Task 5 (`main.py`) to guard `CAP_PROP_POS_MSEC` readings before passing
    them to `detect_for_video()`. Tracks the last value returned; if
    `raw_ms` isn't strictly greater than the last returned value, returns
    `last + 1` instead of the raw (non-monotonic) reading.

- [ ] **Step 1:** Write the failing test for `MonotonicTimestamp` (this part
  is pure logic, fully unit-testable without a real model file or video):
  ```python
  # dms-ai-engine/tests/test_face_landmarker_client.py
  from services.face_landmarker_client import MonotonicTimestamp


  def test_monotonic_timestamp_passes_through_increasing_values():
      m = MonotonicTimestamp()
      assert m.next(0.0) == 0
      assert m.next(33.0) == 33
      assert m.next(67.0) == 67


  def test_monotonic_timestamp_bumps_a_non_increasing_reading():
      """Some codecs/containers (B-frames, variable frame rate) can report a
      CAP_PROP_POS_MSEC value that doesn't strictly increase between reads.
      FaceLandmarker's VIDEO mode throws an exception immediately (not a
      warning) if fed a non-increasing timestamp -- this must never happen."""
      m = MonotonicTimestamp()
      assert m.next(100.0) == 100
      assert m.next(100.0) == 101, "equal reading must still advance"
      assert m.next(50.0) == 102, "a reading that went backwards must still advance"
      assert m.next(200.0) == 200, "a later genuine reading resumes passing through normally"


  def test_monotonic_timestamp_first_call_accepts_zero():
      m = MonotonicTimestamp()
      assert m.next(0.0) == 0
  ```
- [ ] **Step 2:** Run to verify failure:
  `pytest dms-ai-engine/tests/test_face_landmarker_client.py -v`
  Expected: FAIL — module doesn't exist.
- [ ] **Step 3:** Create `dms-ai-engine/services/face_landmarker_client.py`:
  ```python
  """
  face_landmarker_client
  -----------------------
  Shared FaceLandmarker construction (explicit CPU delegate, VIDEO running
  mode -- both required, never left to a default: see design doc Decisions
  1/4) and a monotonic-timestamp guard for CAP_PROP_POS_MSEC, which can
  occasionally be non-increasing between reads for some codecs/containers --
  VIDEO mode throws immediately (not a warning) on a non-monotonic timestamp.
  """
  from typing import Optional

  from mediapipe.tasks.python import vision
  from mediapipe.tasks.python.core.base_options import BaseOptions


  def build_video_mode_landmarker(model_path: str) -> vision.FaceLandmarker:
      options = vision.FaceLandmarkerOptions(
          base_options=BaseOptions(
              model_asset_path=model_path,
              delegate=BaseOptions.Delegate.CPU,
          ),
          running_mode=vision.RunningMode.VIDEO,
          num_faces=1,
          output_face_blendshapes=True,
          output_facial_transformation_matrixes=True,
          min_face_detection_confidence=0.5,
          min_tracking_confidence=0.5,
      )
      return vision.FaceLandmarker.create_from_options(options)


  class MonotonicTimestamp:
      def __init__(self):
          self._last: Optional[int] = None

      def next(self, raw_ms: float) -> int:
          candidate = int(raw_ms)
          if self._last is not None and candidate <= self._last:
              candidate = self._last + 1
          self._last = candidate
          return candidate
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/tests/test_face_landmarker_client.py -v`
  Expected: all 3 tests PASS.
- [ ] **Step 5:** Manual smoke check that `build_video_mode_landmarker`
  actually constructs against the real model file baked in by Task 1 (this
  needs the real container, since the model file lives there):
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:facelandmarker-check .
  docker run --rm --entrypoint python vital-guard-dms:facelandmarker-check -c "
  from services.face_landmarker_client import build_video_mode_landmarker
  lm = build_video_mode_landmarker('/app/models/face_landmarker.task')
  print('constructed OK:', lm)
  "
  ```
  Expected: prints `constructed OK: <FaceLandmarker object>` with no
  exception. If this raises an error mentioning GPU/EGL/OpenGL, the CPU
  delegate isn't taking effect — re-check Step 3's `BaseOptions` construction
  before proceeding; do not treat a GPU-related crash here as acceptable.
- [ ] **Step 6:** Commit:
  ```bash
  git add dms-ai-engine/services/face_landmarker_client.py dms-ai-engine/tests/test_face_landmarker_client.py
  git commit -m "Add shared FaceLandmarker client: explicit CPU delegate, VIDEO mode, monotonic timestamp guard"
  ```

---

## Task 3: `services/head_pose.py` — empirically determine the pitch axis, then implement + lock in with regression tests

**Files:**
- Create (throwaway, not committed): `dms-ai-engine/out/probe_pitch_axis.py`
- Rewrite: `dms-ai-engine/services/head_pose.py`
- Replace: `dms-ai-engine/tests/test_head_pose.py`

**Interfaces:**
- Consumes: `build_video_mode_landmarker` (Task 2), the 3 original videos +
  `drowsy.mp4` already present in `dms-ai-engine/out/` from prior real-video
  testing (or `dms-ai-engine/public/`, re-copy if `out/` was cleaned).
- Produces: `rotation_matrix_to_euler_deg(matrix: np.ndarray) -> tuple[float, float, float]`
  (generic, all 3 angles, pure math) and
  `extract_pitch_deg(transformation_matrix: np.ndarray) -> float` (picks the
  one component empirically determined below to be pitch). Consumed by
  Task 5 (`main.py`).

**Why this task can't just transcribe a formula (read before starting):**
Task 8's `atan2(R[2,1], R[2,2])` formula was derived for a 6-point 3D model
*we chose ourselves* (`MODEL_3D_POINTS`), so we knew exactly which axis was
interaural (pitch) vs vertical (yaw) by construction. Face Landmarker's
`facial_transformation_matrixes` comes from a canonical face model internal
to MediaPipe — its axis convention isn't ours to assume, and unlike
`solvePnP`, we can't feed it synthetic landmarks to test in isolation
(`detect_for_video` takes an image, runs its own internal face
detection+landmark model, then produces the matrix — there's no synthetic-
landmarks entry point). The pitch axis must be found empirically against
real video with a known, unambiguous head motion, mirroring Task 8's own
"sweep and correlate against ground truth" methodology, adapted to the tools
actually available here.

**A second, easy-to-miss assumption baked into the Euler-extraction formula
itself (not just "which axis is pitch"):** the three `atan2` expressions
below assume `facial_transformation_matrixes`' rotation submatrix decomposes
under one *specific* composition order (the classic
`R = Rz(z) · Ry(y) · Rx(x)` Tait-Bryan convention this formula is built on).
This is a second, independent assumption from "which axis is pitch" — and
critically, **pure single-axis rotation tests (Step 5's
`test_pure_x_rotation_recovers_known_angle`/`test_pure_y_rotation_...`)
cannot detect a wrong composition order**, because with only one non-zero
Euler angle there's no coupling between axes for a wrong decomposition order
to leak through — any consistent decomposition recovers a pure single-axis
rotation correctly regardless of composition-order convention. A real
driver's head moves on multiple axes at once (nod + slight tilt + turn
together), which is exactly the condition that would expose a wrong
composition order as cross-axis leakage. Step 3 below adds an explicit check
against *combined*, natural head motion in real video (not just the
isolated clean-nod segment), and Step 5 adds a synthetic combined-rotation
unit test — together these catch what the single-axis tests structurally
cannot.

- [ ] **Step 1:** Write the throwaway probe script
  `dms-ai-engine/out/probe_pitch_axis.py` (do not commit this file — it's a
  one-time investigation tool, its *finding* becomes the committed code in
  Step 3+):
  ```python
  import sys
  sys.path.insert(0, "/app")
  import cv2
  import numpy as np
  import math
  from services.face_landmarker_client import build_video_mode_landmarker, MonotonicTimestamp
  import mediapipe as mp

  landmarker = build_video_mode_landmarker("/app/models/face_landmarker.task")
  cap = cv2.VideoCapture(sys.argv[1])
  ts = MonotonicTimestamp()
  frame_idx = 0

  def euler_from_matrix(m):
      R = m[:3, :3]
      x = math.degrees(math.atan2(R[2, 1], R[2, 2]))
      y = math.degrees(math.atan2(-R[2, 0], math.sqrt(R[2, 1]**2 + R[2, 2]**2)))
      z = math.degrees(math.atan2(R[1, 0], R[0, 0]))
      return x, y, z

  while cap.isOpened():
      ret, frame = cap.read()
      if not ret:
          break
      rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
      mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
      raw_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
      result = landmarker.detect_for_video(mp_image, ts.next(raw_ms))
      if result.facial_transformation_matrixes:
          matrix = np.array(result.facial_transformation_matrixes[0])
          x, y, z = euler_from_matrix(matrix)
          print(f"frame={frame_idx} t={raw_ms:.0f}ms x={x:.2f} y={y:.2f} z={z:.2f}")
      frame_idx += 1
  cap.release()
  ```
- [ ] **Step 2:** Run this against `drowsy.mp4` (the video with the clearest,
  most sustained visible head-droop from prior testing) inside the real
  container, printing all 3 Euler components per frame:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:pitchprobe .
  docker run --rm \
    -v "$(pwd)/out/drowsy.mp4:/data/drowsy.mp4:ro" \
    -v "$(pwd)/out/probe_pitch_axis.py:/app/probe_pitch_axis.py:ro" \
    --entrypoint python vital-guard-dms:pitchprobe /app/probe_pitch_axis.py /data/drowsy.mp4
  ```
- [ ] **Step 3:** Inspect the printed x/y/z trajectories against the known
  timeline from the earlier `evidence_drowsy.csv` (head visibly drooping
  starting around t≈0.6s, peaking around t≈1.2s per the original findings).
  Identify which of x/y/z **increases substantially and monotonically-ish**
  during that visible droop window while the other two stay comparatively
  flat — that component is pitch. Also spot-check 2-3 individual frames
  against the actual video image (open the frame at that timestamp) to
  confirm the sign matches "head down = positive," per the existing
  `head_pitch_deg` docstring convention. Record which axis (x/y/z from the
  probe) was chosen and why, in a comment in the real `head_pose.py` (Step 4)
  — do not leave this decision undocumented.

  **Also run the same probe against `normal.mp4` or `distracted.mp4`** (not
  just the isolated drowsy droop) and inspect a segment where the head is
  visibly doing more than one thing at once (a slight turn while also
  tilting, not a clean isolated nod) — per this task's preamble, an isolated
  clean-nod segment cannot expose a wrong Euler composition-order
  convention, only combined/coupled motion can. Confirm the chosen pitch
  component still tracks visually-apparent nodding without visible
  contamination from the simultaneous other motion in that segment. If the
  chosen axis looks contaminated (jumps or trends that don't match the
  visible nod alone), the composition-order assumption in Step 4's formula
  is suspect — this is a real finding to document, not something to paper
  over by cherry-picking a cleaner segment.
- [ ] **Step 4:** Rewrite `dms-ai-engine/services/head_pose.py` using the
  axis chosen in Step 3 (below uses `x` as the placeholder for whichever
  component Step 3 actually identifies — substitute the real finding, do not
  leave `x` if the investigation found a different component):
  ```python
  """
  head_pose
  ---------
  Head pitch estimation from MediaPipe Face Landmarker's
  facial_transformation_matrixes -- a full learned 3D face model fit, not a
  from-scratch solvePnP solve, which is why this doesn't suffer the
  PnP flip-ambiguity that affected the old 6-point solvePnP approach (see
  docs/superpowers/specs/2026-07-28-cv-backend-remediation-design.md).

  The pitch axis below was determined empirically against real reference
  video (dms-ai-engine/out/drowsy.mp4's visible head-droop segment,
  ~t=0.6-1.2s), not derived analytically -- Face Landmarker's canonical face
  model convention isn't a convention this project chose, unlike the old
  6-point model. [Fill in: which axis (x/y/z from the probe) was chosen,
  and the specific evidence -- e.g. "the x component tracked the visible
  droop, rising from ~2 deg to ~24 deg across the droop window while y/z
  stayed within +/-3 deg of baseline."]
  """
  import math
  from typing import Tuple

  import numpy as np


  def rotation_matrix_to_euler_deg(matrix: np.ndarray) -> Tuple[float, float, float]:
      """matrix: a 4x4 (or already-3x3) transformation matrix from
      facial_transformation_matrixes. Returns (x, y, z) Euler angles in
      degrees via the standard rotation-matrix decomposition."""
      R = matrix[:3, :3]
      x = math.degrees(math.atan2(R[2, 1], R[2, 2]))
      y = math.degrees(math.atan2(-R[2, 0], math.sqrt(R[2, 1] ** 2 + R[2, 2] ** 2)))
      z = math.degrees(math.atan2(R[1, 0], R[0, 0]))
      return x, y, z


  def extract_pitch_deg(transformation_matrix: np.ndarray) -> float:
      x, y, z = rotation_matrix_to_euler_deg(transformation_matrix)
      return x  # <-- substitute the Step 3 finding if it wasn't x
  ```
- [ ] **Step 5:** Replace `dms-ai-engine/tests/test_head_pose.py` entirely.
  Since `extract_pitch_deg` takes a rotation matrix directly (not an image),
  it's fully unit-testable with hand-constructed matrices — same rigor as
  Task 8, adapted to take a matrix input instead of 2D landmarks:
  ```python
  import math

  import numpy as np

  from services.head_pose import rotation_matrix_to_euler_deg, extract_pitch_deg


  def _rotation_matrix_x(angle_deg):
      """Pure rotation about the X axis, as a 4x4 homogeneous matrix (matching
      facial_transformation_matrixes' shape)."""
      a = math.radians(angle_deg)
      m = np.eye(4)
      m[1, 1] = math.cos(a)
      m[1, 2] = -math.sin(a)
      m[2, 1] = math.sin(a)
      m[2, 2] = math.cos(a)
      return m


  def _rotation_matrix_y(angle_deg):
      a = math.radians(angle_deg)
      m = np.eye(4)
      m[0, 0] = math.cos(a)
      m[0, 2] = math.sin(a)
      m[2, 0] = -math.sin(a)
      m[2, 2] = math.cos(a)
      return m


  def test_pure_x_rotation_recovers_known_angle():
      for angle in (-30.0, -10.0, 10.0, 30.0):
          x, y, z = rotation_matrix_to_euler_deg(_rotation_matrix_x(angle))
          assert abs(x - angle) < 0.01, f"expected x~{angle}, got {x}"
          assert abs(y) < 0.01 and abs(z) < 0.01, f"pure X rotation leaked into y/z: y={y} z={z}"


  def test_pure_y_rotation_recovers_known_angle():
      for angle in (-30.0, -10.0, 10.0, 30.0):
          x, y, z = rotation_matrix_to_euler_deg(_rotation_matrix_y(angle))
          assert abs(y - angle) < 0.01, f"expected y~{angle}, got {y}"
          assert abs(x) < 0.01 and abs(z) < 0.01, f"pure Y rotation leaked into x/z: x={x} z={z}"


  def test_extract_pitch_deg_increases_with_the_chosen_axis_rotation():
      """Locks in the Task 3 empirical finding: whichever axis was chosen as
      pitch must increase monotonically with that axis's rotation angle. If
      the chosen axis was X, this duplicates test_pure_x_rotation's rotation
      builder; substitute _rotation_matrix_y (or a z-builder) here if the
      investigation found pitch on a different axis."""
      angles = [-20.0, -10.0, 0.0, 10.0, 20.0, 30.0]
      pitches = [extract_pitch_deg(_rotation_matrix_x(a)) for a in angles]
      for earlier, later in zip(pitches, pitches[1:]):
          assert later > earlier, f"pitch must be strictly increasing, got {pitches}"


  def test_extract_pitch_deg_is_insensitive_to_the_other_axes():
      """Regression guard mirroring Task 8's yaw-blindness test -- whichever
      axis ISN'T pitch must not move the extracted value much."""
      for angle in (-30.0, -15.0, 15.0, 30.0):
          pitch = extract_pitch_deg(_rotation_matrix_y(angle))
          assert abs(pitch) < 2.5, f"non-pitch axis rotation of {angle} deg leaked into pitch: got {pitch}"


  def test_combined_rotation_does_not_corrupt_pitch_extraction():
      """Single-axis tests above cannot detect a wrong Euler composition-order
      convention -- with only one non-zero angle, any consistent decomposition
      recovers it correctly regardless of composition order. This test
      combines two axes at once (matching how a real head actually moves --
      nod + turn together, not in isolation) under the SAME composition order
      this module's own rotation_matrix_to_euler_deg assumes (R = Rz*Ry*Rx),
      so it validates internal self-consistency of our chosen convention.
      It does NOT prove facial_transformation_matrixes uses the same
      convention -- that can only be checked empirically against real video
      (Task 3 Step 3's combined-motion probe), which is a separate,
      real-data-dependent check this synthetic test cannot replace."""
      pitch_angle, other_angle = 20.0, 15.0
      combined = _rotation_matrix_x(pitch_angle) @ _rotation_matrix_y(other_angle)
      pitch_alone = extract_pitch_deg(_rotation_matrix_x(pitch_angle))
      pitch_combined = extract_pitch_deg(combined)
      assert abs(pitch_combined - pitch_alone) < 5.0, (
          f"combining a second axis of rotation shifted extracted pitch from "
          f"{pitch_alone:.2f} to {pitch_combined:.2f} -- more than the expected "
          f"small coupling error for this composition order"
      )
  ```
  (If Step 3's finding is `y` or `z` instead of `x`, swap which builder
  function plays the "pitch axis" vs "other axis" role throughout this file
  — do not leave a mismatch between the finding and the tests. The `@`
  matrix-multiplication order in `test_combined_rotation_...` encodes the
  same `Rz*Ry*Rx`-style composition assumed in `rotation_matrix_to_euler_deg`
  — swap the multiplication order too if the chosen axis/convention changes
  which matrix should be applied first.)
- [ ] **Step 6:** Run: `pytest dms-ai-engine/tests/test_head_pose.py -v`
  Expected: all tests PASS. These test pure math (rotation matrices you
  construct by hand), so they run fine on Windows without needing the real
  model file or a container.
- [ ] **Step 7:** Delete the throwaway probe script (or leave it un-committed
  under `out/`, which is already outside version control):
  `rm dms-ai-engine/out/probe_pitch_axis.py` (optional — harmless to leave
  since `out/` isn't tracked, but remove if it's confusing to find later).
- [ ] **Step 8:** Commit:
  ```bash
  git add dms-ai-engine/services/head_pose.py dms-ai-engine/tests/test_head_pose.py
  git commit -m "Replace solvePnP head-pose with Face Landmarker transformation-matrix extraction (axis determined empirically against real video)"
  ```

---

## Task 4: `services/eye_state.py` — blendshape blink score + raw-signal hysteresis

**Files:**
- Rewrite: `dms-ai-engine/services/eye_state.py`
- Replace: `dms-ai-engine/tests/test_eye_state.py`

**Interfaces:**
- Consumes: nothing from earlier tasks — this operates on blendshape
  category lists, which is pure data (a list of `(name, score)` pairs or
  equivalent), no MediaPipe/OpenCV import needed, matching the old file's
  "pure geometry, testable without a camera" property.
- Produces:
  - `blink_score(blendshapes: dict[str, float]) -> float` — averages
    `eyeBlinkLeft`/`eyeBlinkRight` scores (both already [0,1] from Face
    Landmarker).
  - `class BlinkStateTracker` — `update(self, score: float, now: float) -> bool`,
    the raw-signal-level hysteresis (two thresholds: enter "closed" above a
    higher value, exit only below a lower one) that Task 5 uses instead of a
    per-frame instantaneous threshold. This directly targets the exact
    failure mode found in `PITCH_ESTIMATION_FINDINGS.md`'s drowsy-video
    analysis: a single frame where the raw signal crossed the old
    instantaneous EAR threshold instantly zeroed the `eye_closed_now` score
    term right before the CRITICAL threshold would have been crossed.

- [ ] **Step 1:** Write the failing tests:
  ```python
  # dms-ai-engine/tests/test_eye_state.py
  from services.eye_state import blink_score, BlinkStateTracker, BLINK_CLOSE_THRESHOLD, BLINK_REOPEN_THRESHOLD


  def test_blink_score_averages_both_eyes():
      blendshapes = {"eyeBlinkLeft": 0.8, "eyeBlinkRight": 0.6, "jawOpen": 0.1}
      assert blink_score(blendshapes) == 0.7


  def test_blink_score_missing_category_defaults_to_zero():
      """If Face Landmarker doesn't report a category for a frame (e.g. face
      partially out of view), treat it as eyes-open rather than crashing."""
      assert blink_score({"jawOpen": 0.1}) == 0.0


  def test_blink_state_tracker_stays_open_below_close_threshold():
      tracker = BlinkStateTracker()
      assert tracker.update(BLINK_CLOSE_THRESHOLD - 0.05, now=0.0) is False


  def test_blink_state_tracker_closes_above_close_threshold():
      tracker = BlinkStateTracker()
      assert tracker.update(BLINK_CLOSE_THRESHOLD + 0.05, now=0.0) is True


  def test_blink_state_tracker_ignores_a_single_dip_between_the_two_thresholds():
      """This is the exact failure mode from the real drowsy-video finding:
      one noisy frame dipping between the close and reopen thresholds must
      NOT flip the state back to open -- only dropping below the LOWER
      reopen threshold should."""
      tracker = BlinkStateTracker()
      assert tracker.update(BLINK_CLOSE_THRESHOLD + 0.10, now=0.0) is True
      midpoint = (BLINK_CLOSE_THRESHOLD + BLINK_REOPEN_THRESHOLD) / 2
      assert tracker.update(midpoint, now=0.03) is True, "a dip that doesn't cross the reopen threshold must stay closed"
      assert tracker.update(BLINK_CLOSE_THRESHOLD + 0.10, now=0.07) is True


  def test_blink_state_tracker_reopens_only_below_reopen_threshold():
      tracker = BlinkStateTracker()
      tracker.update(BLINK_CLOSE_THRESHOLD + 0.10, now=0.0)
      assert tracker.update(BLINK_REOPEN_THRESHOLD - 0.05, now=0.03) is False
  ```
- [ ] **Step 2:** Run to verify failure:
  `pytest dms-ai-engine/tests/test_eye_state.py -v`
  Expected: FAIL — module doesn't have these names yet.
- [ ] **Step 3:** Rewrite `dms-ai-engine/services/eye_state.py`:
  ```python
  """
  eye_state
  ---------
  Eye-closure signal from MediaPipe Face Landmarker's face_blendshapes
  (eyeBlinkLeft/eyeBlinkRight, continuous [0,1]) instead of a hand-computed
  eye-aspect-ratio from raw landmark geometry. Pure data in, no
  MediaPipe/OpenCV import here -- testable with plain dicts.

  BLINK_CLOSE_THRESHOLD/BLINK_REOPEN_THRESHOLD form a two-threshold
  hysteresis at the RAW SIGNAL layer (not just the composite-score layer,
  where the only hysteresis previously lived -- trigger_emitter.py's
  TriggerEmitter). This targets a specific, evidenced failure: a real drowsy
  video's score climbed to 0.800 then dropped sharply the instant one frame's
  raw signal crossed a single instantaneous threshold, right before the 0.85
  CRITICAL threshold would have been reached (see
  PITCH_ESTIMATION_FINDINGS.md). Values between the two thresholds are
  deliberately "sticky" in whichever state was last entered.
  """
  from typing import Dict

  BLINK_CLOSE_THRESHOLD = 0.6
  BLINK_REOPEN_THRESHOLD = 0.4


  def blink_score(blendshapes: Dict[str, float]) -> float:
      left = blendshapes.get("eyeBlinkLeft", 0.0)
      right = blendshapes.get("eyeBlinkRight", 0.0)
      return (left + right) / 2.0


  class BlinkStateTracker:
      def __init__(self):
          self._closed = False

      def update(self, score: float, now: float) -> bool:
          if not self._closed and score >= BLINK_CLOSE_THRESHOLD:
              self._closed = True
          elif self._closed and score <= BLINK_REOPEN_THRESHOLD:
              self._closed = False
          return self._closed
  ```
  (`now` is accepted for interface consistency with `TriggerEmitter`/
  `FacePresenceTracker`'s `update(value, now)` shape, but this tracker is
  purely value-driven, not time-driven — it doesn't currently use `now`
  internally. Keep the parameter rather than dropping it, since a future
  time-based extension — e.g. requiring the close threshold to be held for
  a minimum duration — would need it, and changing the call signature later
  would touch every call site again.)
- [ ] **Step 4:** Pick actual `BLINK_CLOSE_THRESHOLD`/`BLINK_REOPEN_THRESHOLD`
  values with real justification, not the placeholders above chosen for
  test-writing convenience. Blendshape scores and EAR are different scales
  (blendshape is a learned [0,1] confidence, not a geometric ratio) — do not
  assume the old `EAR_CLOSED_THRESHOLD=0.18`-style numeric intuition
  transfers. This step just needs *a* reasoned starting point — e.g. 0.5/0.3
  as a symmetric-ish gap around blendshape midpoint — document the reasoning
  inline as a comment. **This is genuinely revisited, not just hoped to be:
  Task 7 Step 3.5 explicitly inspects real blink-score CSV data against
  these two numbers and requires a fix-and-rerun if they look wrong** —
  don't treat "defer to later" as equivalent to "will definitely get
  checked"; the checking step is named explicitly below.
- [ ] **Step 5:** Run: `pytest dms-ai-engine/tests/test_eye_state.py -v`
  Expected: all 6 tests PASS.
- [ ] **Step 6:** Commit:
  ```bash
  git add dms-ai-engine/services/eye_state.py dms-ai-engine/tests/test_eye_state.py
  git commit -m "Replace EAR-based eye-closure with Face Landmarker blendshapes + raw-signal hysteresis"
  ```

---

## Task 5: Rewire `main.py`'s `run_real_video()` to the new client/head_pose/eye_state APIs

**Files:**
- Modify: `dms-ai-engine/main.py`
- Modify: `dms-ai-engine/tests/test_main.py`

**Interfaces:**
- Consumes: `build_video_mode_landmarker`/`MonotonicTimestamp` (Task 2),
  `extract_pitch_deg` (Task 3), `blink_score`/`BlinkStateTracker` (Task 4).
- Produces: `run_real_video(video_path, out_csv, host, port, model_path="/app/models/face_landmarker.task")`
  — same external signature as before plus one new `model_path` parameter
  (give it a sensible container-path default so existing callers/tests that
  don't care about the model path don't all need updating, but allow
  override for tests that supply a fake).

**Verified before writing this task (not assumed from memory):**
`FacePresenceTracker.update()` (`services/trigger_emitter.py`, unchanged by
this plan) is edge-triggered via its own `_unknown_active` guard flag — it
returns `"UNKNOWN"` exactly once when sustained absence is first detected
(`sustained and not self._unknown_active`), then `None` on every subsequent
call while `has_face` stays `False`, only returning `"PRESENT"` once when a
face reappears. So `if face_signal == "UNKNOWN":` below fires once per
lost-face episode, not once per frame during the whole absence — confirmed
by reading the actual current source, not carried over from an earlier
session's memory of a different plan's Task 4.

- [ ] **Step 1:** Rewrite `run_real_video()` in `main.py`:
  ```python
  def run_real_video(video_path: str, out_csv: Path, host: str, port: int,
                      model_path: str = "/app/models/face_landmarker.task") -> None:
      import cv2
      import mediapipe as mp
      from services.face_landmarker_client import build_video_mode_landmarker, MonotonicTimestamp
      from services.head_pose import extract_pitch_deg
      from services.eye_state import blink_score, BlinkStateTracker

      store = LatestTriggerStore()
      server = start_background_server(store, host=host, port=port)
      landmarker = build_video_mode_landmarker(model_path)
      timestamp_guard = MonotonicTimestamp()
      blink_tracker = BlinkStateTracker()

      cap = cv2.VideoCapture(video_path)
      if not cap.isOpened():
          server.shutdown()
          raise RuntimeError(
              f"Could not open video file: {video_path} "
              "(bad path, or a codec/container OpenCV's build doesn't support)"
          )
      calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
      emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                                sustain_seconds=2.0, cooldown_seconds=10.0)
      face_tracker = FacePresenceTracker(sustain_seconds=2.0)
      event_counter = 0
      t = 0.0
      fps = cap.get(cv2.CAP_PROP_FPS)
      if not math.isfinite(fps) or fps <= 0:
          fps = 30.0
      frame_dt = 1.0 / fps

      try:
          with open(out_csv, "w", newline="", encoding="utf-8") as f:
              writer = csv.writer(f)
              writer.writerow(["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal"])
              while cap.isOpened():
                  if _shutdown_requested:
                      break
                  ret, frame = cap.read()
                  if not ret:
                      break
                  rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                  mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                  raw_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
                  result = landmarker.detect_for_video(mp_image, timestamp_guard.next(raw_ms))
                  has_face = bool(result.face_blendshapes)

                  face_signal = face_tracker.update(has_face=has_face, now=t)
                  if face_signal == "UNKNOWN":
                      event_counter += 1
                      store.update_latest(build_trigger_payload(
                          state="UNKNOWN", score=0.0, confidence=0.0,
                          perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                          reason="lost_face", source="container-python", event_counter=event_counter,
                      ))

                  if has_face:
                      blendshapes = {c.category_name: c.score for c in result.face_blendshapes[0]}
                      score_blink = blink_score(blendshapes)
                      eye_closed = blink_tracker.update(score_blink, now=t)
                      pitch_deg = extract_pitch_deg(result.facial_transformation_matrixes[0])
                      score = calc.add_frame(FrameFeatures(timestamp=t, eye_closed=eye_closed, head_pitch_deg=pitch_deg))
                      signal = emitter.update(score, now=t)
                      state = _state_for_score(score)
                      if signal in ("CRITICAL", "RECOVERED"):
                          event_counter += 1
                          store.update_latest(build_trigger_payload(
                              state=state, score=score, confidence=1.0,
                              perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                              head_euler_angle_x=pitch_deg,
                              reason=("sustained_high_score" if signal == "CRITICAL" else "recovered"),
                              source="container-python", event_counter=event_counter,
                          ))
                      writer.writerow([f"{t:.2f}", 1, f"{score_blink:.3f}", f"{pitch_deg:.1f}", f"{score:.3f}", state, signal or ""])
                  else:
                      writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or ""])

                  t += frame_dt
      finally:
          cap.release()
          server.shutdown()
  ```
  Note: `eye_open_probability` is now simply `1.0 - score_blink` inline
  (blendshape score is already a continuous [0,1] "closed-ness," so the old
  separate `eye_open_probability()` normalization function — which mapped a
  raw EAR value through calibrated open/closed thresholds — is no longer
  needed; delete it from `eye_state.py` if Task 4 hadn't already omitted it).
  `has_face` is now derived from `result.face_blendshapes` truthiness rather
  than `result.face_landmarks`, since blendshapes are what this function
  actually consumes — but confirm during Task 5's testing that
  `face_blendshapes` is empty/falsy exactly when no face is detected (same
  as `face_landmarks` would be); if that assumption is wrong, switch to
  checking `result.face_landmarks` instead and note why in a comment.
- [ ] **Step 2:** Update `dms-ai-engine/tests/test_main.py`'s mock
  infrastructure to match the new API surface. Replace
  `_fake_mediapipe_with_no_face_ever` (which faked
  `mp.solutions.face_mesh.FaceMesh().process()`) with a fake matching
  `FaceLandmarker.detect_for_video()`'s return shape:
  ```python
  import types


  def _fake_landmarker_with_no_face_ever():
      """Fakes build_video_mode_landmarker's return value: a FaceLandmarker
      whose detect_for_video() always reports no face -- used to test the
      sustained-lost-face path without a real face in a real video."""
      no_face_result = types.SimpleNamespace(face_blendshapes=[], facial_transformation_matrixes=[])

      class _FakeLandmarker:
          def detect_for_video(self, mp_image, timestamp_ms):
              return no_face_result

      return _FakeLandmarker()
  ```
  Update the 4 tests that previously did
  `monkeypatch.setattr(mediapipe, "solutions", _fake_mediapipe_with_no_face_ever(), raising=False)`
  to instead patch `build_video_mode_landmarker` itself:
  ```python
  monkeypatch.setattr(
      "services.face_landmarker_client.build_video_mode_landmarker",
      lambda model_path: _fake_landmarker_with_no_face_ever(),
  )
  ```
  This affects (rename/verify each still makes sense against the new code,
  don't just find-and-replace blindly — confirmed via GitNexus these are the
  exact 4 functions that call `run_real_video` with the old mock):
  `test_run_real_video_raises_clear_error_when_video_cannot_be_opened`,
  `test_run_real_video_falls_back_to_default_fps_when_reported_fps_is_invalid`,
  `test_run_real_video_emits_unknown_after_sustained_lost_face`,
  `test_run_real_video_stops_early_when_shutdown_requested`.
- [ ] **Step 3:** Replace `test_average_ear_and_state_agree_for_synthetic_closed_eyes`
  (which tested the now-deleted `average_ear`) with an equivalent for the
  new blink-score path:
  ```python
  def test_blink_score_and_state_agree_for_a_high_blink_score():
      from services.eye_state import blink_score
      blendshapes = {"eyeBlinkLeft": 0.9, "eyeBlinkRight": 0.85}
      assert blink_score(blendshapes) > 0.6  # matches BLINK_CLOSE_THRESHOLD's intent
  ```
- [ ] **Step 4:** Run the full suite:
  `pytest dms-ai-engine -v`
  Expected: all tests pass, including the 4 updated `run_real_video` tests
  and the replaced blink-score test. Fix any mock-shape mismatch the tests
  surface (e.g. if `face_blendshapes`/`facial_transformation_matrixes`'
  real-world truthiness semantics differ slightly from the fake's empty-list
  assumption).
- [ ] **Step 5:** Commit:
  ```bash
  git add dms-ai-engine/main.py dms-ai-engine/tests/test_main.py
  git commit -m "Rewire run_real_video() to Face Landmarker client, head_pose, and eye_state"
  ```

---

## Task 6: Latency measurement script

**Files:**
- Create: `dms-ai-engine/measure_latency.py`

**Interfaces:**
- Consumes: `run_real_video`'s per-frame processing logic (Task 5) —
  duplicates the per-frame body under timing instrumentation rather than
  importing `run_real_video` directly, since that function doesn't expose a
  per-frame hook to time; keep this script's frame-processing logic in sync
  with `main.py`'s by construction (call the same `services.*` functions,
  not reimplemented math).
- Produces: a CLI script printing p50/p95/p99 end-to-end
  (frame-read → inference → score-computed) latency in milliseconds, run
  across one or more videos.

- [ ] **Step 1:** Create `dms-ai-engine/measure_latency.py`. This mirrors
  `run_real_video()`'s **entire** per-frame body, not a subset — an earlier
  draft of this script only timed through `calc.add_frame(...)`, omitting
  `face_tracker.update()`, `emitter.update()`, and — critically — the
  `build_trigger_payload()`/`store.update_latest()` path that only runs on
  a `CRITICAL`/`RECOVERED`/`UNKNOWN` frame. That's exactly the frame where
  latency matters most (the system reacting to a real trigger), and it's
  also the one with extra cost (payload construction, a lock-guarded store
  write) that a subset measurement would silently miss, making the reported
  p95 an optimistic lower bound rather than the real worst case. Include a
  real `LatestTriggerStore` (no HTTP server needed for this measurement —
  just the store object, since the lock/write is what's being timed) so the
  trigger-path cost is genuinely exercised, not skipped:
  ```python
  """
  Measures end-to-end per-frame latency (frame read -> Face Landmarker
  inference -> eye/pose extraction -> score -> emitter/face-tracker update ->
  trigger-store write on a firing frame) -- the FULL per-frame body
  run_real_video() executes, not a subset. Run inside the real
  Container-Node-equivalent Docker container, not just the dev machine (see
  design doc Decision 4 -- CPU delegate doesn't cover CPU-architecture
  mismatches, and dev-machine timing isn't representative of the real
  deployment target regardless).

  Usage: python measure_latency.py video1.mp4 video2.mp4 ...
  """
  import sys
  import time

  import cv2
  import mediapipe as mp

  from services.face_landmarker_client import build_video_mode_landmarker, MonotonicTimestamp
  from services.head_pose import extract_pitch_deg
  from services.eye_state import blink_score, BlinkStateTracker
  from services.score_calculator import DrowsinessScoreCalculator, FrameFeatures
  from services.trigger_emitter import TriggerEmitter, FacePresenceTracker
  from services.trigger_server import LatestTriggerStore
  from main import build_trigger_payload, _state_for_score


  def measure(video_path: str, model_path: str) -> list:
      landmarker = build_video_mode_landmarker(model_path)
      timestamp_guard = MonotonicTimestamp()
      blink_tracker = BlinkStateTracker()
      calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
      emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                                sustain_seconds=2.0, cooldown_seconds=10.0)
      face_tracker = FacePresenceTracker(sustain_seconds=2.0)
      store = LatestTriggerStore()
      event_counter = 0
      cap = cv2.VideoCapture(video_path)
      latencies_ms = []
      t = 0.0
      fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
      frame_dt = 1.0 / fps

      while cap.isOpened():
          start = time.perf_counter()
          ret, frame = cap.read()
          if not ret:
              break
          rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
          mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
          raw_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
          result = landmarker.detect_for_video(mp_image, timestamp_guard.next(raw_ms))
          has_face = bool(result.face_blendshapes)

          face_signal = face_tracker.update(has_face=has_face, now=t)
          if face_signal == "UNKNOWN":
              event_counter += 1
              store.update_latest(build_trigger_payload(
                  state="UNKNOWN", score=0.0, confidence=0.0,
                  perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                  reason="lost_face", source="latency-check", event_counter=event_counter,
              ))

          if has_face:
              blendshapes = {c.category_name: c.score for c in result.face_blendshapes[0]}
              score_blink = blink_score(blendshapes)
              eye_closed = blink_tracker.update(score_blink, now=t)
              pitch_deg = extract_pitch_deg(result.facial_transformation_matrixes[0])
              score = calc.add_frame(FrameFeatures(timestamp=t, eye_closed=eye_closed, head_pitch_deg=pitch_deg))
              signal = emitter.update(score, now=t)
              state = _state_for_score(score)
              if signal in ("CRITICAL", "RECOVERED"):
                  event_counter += 1
                  store.update_latest(build_trigger_payload(
                      state=state, score=score, confidence=1.0,
                      perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                      head_euler_angle_x=pitch_deg,
                      reason=("sustained_high_score" if signal == "CRITICAL" else "recovered"),
                      source="latency-check", event_counter=event_counter,
                  ))
          latencies_ms.append((time.perf_counter() - start) * 1000.0)
          t += frame_dt

      cap.release()
      return latencies_ms


  def percentile(values, p):
      s = sorted(values)
      idx = min(len(s) - 1, int(len(s) * p / 100.0))
      return s[idx]


  if __name__ == "__main__":
      all_latencies = []
      for video_path in sys.argv[1:]:
          lat = measure(video_path, "/app/models/face_landmarker.task")
          print(f"{video_path}: n={len(lat)} p50={percentile(lat, 50):.1f}ms p95={percentile(lat, 95):.1f}ms")
          all_latencies.extend(lat)
      print(f"COMBINED across {len(sys.argv[1:])} videos: n={len(all_latencies)} "
            f"p50={percentile(all_latencies, 50):.1f}ms p95={percentile(all_latencies, 95):.1f}ms "
            f"p99={percentile(all_latencies, 99):.1f}ms")
  ```
- [ ] **Step 2:** No automated test for this script — it's a measurement
  tool, and Task 7's acceptance-gate run is its real exercise. Manually
  confirm it runs without crashing against one video before Task 7:
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:latency-check .
  docker run --rm -v "$(pwd)/out/normal.mp4:/data/normal.mp4:ro" \
    --entrypoint python vital-guard-dms:latency-check /app/measure_latency.py /data/normal.mp4
  ```
  (This requires `measure_latency.py` to also be `COPY`'d into the image —
  add it to the `Dockerfile`'s `COPY` step now, or mount it in like this
  smoke-check does; decide based on whether the team wants it available
  inside every built image going forward. Recommendation: mount it in for
  ad-hoc runs rather than bake it into the production image, since it's a
  developer tool, not part of the running service — don't add a permanent
  `COPY measure_latency.py` line to the Dockerfile.)
- [ ] **Step 3:** Commit:
  ```bash
  git add dms-ai-engine/measure_latency.py
  git commit -m "Add end-to-end latency measurement script for the Gate 2 KPI check"
  ```

---

## Task 7: Acceptance gate — run against the real videos inside the real container

**Files:** none created/modified — this task runs the gates from the Global
Constraints and records the outcome. If Gate 1 or Gate 2 fails outright
(not the "(b) partial progress" case), fixes belong back in Task 3/4/5, not
here.

**Interfaces:** consumes everything from Tasks 1-6.

- [ ] **Step 1:** Rebuild the image fresh (confirm nothing Task 1-6 changed
  breaks the build):
  ```bash
  cd dms-ai-engine
  docker build -t vital-guard-dms:gate-check .
  ```
  If building for a specific known Container Node architecture, use
  `docker buildx build --platform <target> -t vital-guard-dms:gate-check .`
  instead — if this step itself fails (e.g. `pip install mediapipe` can't
  find a matching wheel), that build failure **is** the escalation evidence
  per the Global Constraints; capture the exact error text before escalating.
- [ ] **Step 2:** Re-run all 3 original videos + drowsy.mp4 through the
  container (reuses the same pattern already used for the pre-remediation
  baseline):
  ```bash
  cd dms-ai-engine
  for name in normal drowsy distracted; do
    MSYS_NO_PATHCONV=1 docker run --rm \
      -v "$(pwd)/out/${name}.mp4:/data/${name}.mp4:ro" \
      -v "$(pwd)/out:/app/out" \
      vital-guard-dms:gate-check --video "/data/${name}.mp4" --host 0.0.0.0 --port 8765 \
      --out-csv "/app/out/evidence_${name}_post_remediation.csv"
  done
  ```
- [ ] **Step 3: Gate 1 — physical plausibility.** Programmatically check the
  drowsy video's pitch trajectory for jumps (write a short one-off check, not
  a permanent test file, since this is a one-time acceptance measurement
  against a specific real clip, not a regression guard on code).
  **Only compare truly-adjacent rows (consecutive line numbers), not "the
  previous row that happened to have a pitch value"** — if the face is lost
  for a few frames (plausible exactly during a drowsy/microsleep moment, the
  head drooping out of frame) and reappears at a genuinely different angle,
  comparing across that gap can produce a large, legitimate diff that isn't
  a flip artifact at all; the check below tracks the previous row's own line
  number and skips the comparison whenever a gap is detected, rather than
  silently comparing across it:
  ```bash
  awk -F',' '
    NR>1 && $4!="" {
      if (prev_val != "" && NR == prev_row + 1) {
        diff = $4 - prev_val
        if (diff < 0) diff = -diff
        if (diff > 90) print "JUMP at row " NR ": " prev_val " -> " $4
      } else if (prev_val != "" && NR != prev_row + 1) {
        print "SKIPPED (gap after face loss, rows " prev_row " -> " NR "): " prev_val " -> " $4 " -- not compared"
      }
      prev_val = $4
      prev_row = NR
    }
  ' out/evidence_drowsy_post_remediation.csv
  ```
  Expected: no `JUMP` lines (any `SKIPPED` lines are informational, not
  failures — they mark exactly the gap-after-face-loss case this check is
  designed to not misjudge). If any `JUMP` line prints, Gate 1 fails — this
  is a real regression to investigate in Task 3's extraction logic, not
  something to relax the threshold to hide.
  Then visually cross-check 2-3 frames (same procedure as Task 3 Step 3):
  confirm the numeric pitch direction matches what's visibly seen in the
  source video at those timestamps.
- [ ] **Step 3.5: Sanity-check the blink-hysteresis thresholds against real
  data** (closes the loop Task 4 Step 4 opened — this is the step that
  actually inspects `BLINK_CLOSE_THRESHOLD`/`BLINK_REOPEN_THRESHOLD` against
  real footage, not just a hope that it happens somewhere). Extract the
  `blink_score` column from `evidence_drowsy_post_remediation.csv` at
  timestamps visibly showing closed eyes (from the original manual review
  around the drowsy video's t≈0.6-1.2s droop segment) and at timestamps
  showing clearly open eyes (e.g. the first ~0.3s):
  ```bash
  awk -F',' 'NR>1 && $1+0>=0.6 && $1+0<=1.2 {print $3}' out/evidence_drowsy_post_remediation.csv
  awk -F',' 'NR>1 && $1+0<0.3 {print $3}' out/evidence_drowsy_post_remediation.csv
  ```
  If the closed-eye segment's values aren't reliably above
  `BLINK_CLOSE_THRESHOLD` (0.6) or the open-eye segment's values aren't
  reliably below `BLINK_REOPEN_THRESHOLD` (0.4), adjust the two constants in
  `eye_state.py` with the reasoning updated in its inline comment, re-run
  Task 5's test suite (the hysteresis logic tests use the constants
  symbolically, so they still pass after a threshold change — only the
  absolute numbers move), and re-run Step 2's video pass before continuing
  to Gate 2.
- [ ] **Step 4: Gate 2 — score outcome.** Inspect
  `evidence_drowsy_post_remediation.csv`'s max score and
  `evidence_normal_post_remediation.csv`/`evidence_distracted_post_remediation.csv`'s
  max scores (same `awk` pattern used during the original diagnosis):
  ```bash
  for f in out/evidence_normal_post_remediation.csv out/evidence_drowsy_post_remediation.csv out/evidence_distracted_post_remediation.csv; do
    echo "=== $f ==="
    awk -F',' 'NR>1 && $2=="1" && $5!="" {print $5}' "$f" | sort -g | tail -1
  done
  ```
  - If drowsy's max ≥0.85 AND normal/distracted never reach CRITICAL: Gate 2
    passes via (a) — proceed to Step 5 (latency).
  - If drowsy's max <0.85 but Gate 1 (Step 3) passed clean: Gate 2 passes via
    (b) — record this outcome plainly (don't force a false "(a) passed"), and
    hand off threshold/sustain-window tuning as follow-up work outside this
    plan's scope, per the Global Constraints.
  - If Gate 1 failed (Step 3) AND drowsy doesn't reach 0.85: neither gate
    passes — this is a real failure, return to Task 3 (most likely) or
    Task 4 before re-running this task.
- [ ] **Step 5: Latency.** Run `measure_latency.py` across all 4 videos
  inside the same container:
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd)/out/normal.mp4:/data/normal.mp4:ro" \
    -v "$(pwd)/out/drowsy.mp4:/data/drowsy.mp4:ro" \
    -v "$(pwd)/out/distracted.mp4:/data/distracted.mp4:ro" \
    -v "$(pwd)/measure_latency.py:/app/measure_latency.py:ro" \
    --entrypoint python vital-guard-dms:gate-check /app/measure_latency.py \
    /data/normal.mp4 /data/drowsy.mp4 /data/distracted.mp4
  ```
  Compare the printed COMBINED p95 against 150ms.
  - If ≤150ms: latency gate passes as-is.
  - If >150ms: apply Decision 5's option (a) (downscale input resolution
    before `cv2.cvtColor`/`mp.Image` construction in both `run_real_video`
    and `measure_latency.py`) or (b) (document a relaxed KPI with reasoning
    in CLAUDE.md's KPI table). **If (a) is chosen, re-run Step 3 (Gate 1)
    after downscaling** — do not skip this re-check.
- [ ] **Step 6:** Record the full outcome (Gate 1 result, which Gate 2 path
  was satisfied, latency numbers, any downscale decision and its Gate 1
  re-check, and the Step 3.5 blink-threshold sanity check's outcome) in a
  short new findings note — `dms-ai-engine/CV_REMEDIATION_RESULTS.md` —
  mirroring the level of concrete detail `PITCH_ESTIMATION_FINDINGS.md` had
  (exact numbers, not vague summaries), since that's the standard this
  project has held itself to throughout.
- [ ] **Step 6.5:** Update `CLAUDE.md` — it currently still names
  "MobileNetV3-Small INT8-quantized" as the eye-state backbone (the
  documented "Known Deviations from Proposal" note about switching to
  MediaPipe was itself part of a *different* plan's Task 17, which was
  scoped to a teammate and, per that plan's own ledger, was never actually
  executed — so CLAUDE.md is stale on this point regardless of this plan's
  changes, and this migration is now a *second* deviation on top of an
  undocumented first one). Update CLAUDE.md's "Known Deviations from
  Proposal" section (add it if the section doesn't exist yet) to state
  plainly that the eye-state/head-pose backbone is MediaPipe Face Landmarker
  (blendshapes + facial transformation matrices), not MobileNetV3 and not
  plain FaceMesh + hand-rolled EAR/solvePnP either — with a one-line
  judge-facing reason consistent with the project's existing stance (a
  pretrained, well-validated model used as-is, not a custom-trained
  classifier, so no claim of "scientific validation" for the specific
  thresholds is being made).
- [ ] **Step 7:** Commit:
  ```bash
  git add dms-ai-engine/CV_REMEDIATION_RESULTS.md CLAUDE.md
  git commit -m "Record CV backend remediation acceptance-gate results, update CLAUDE.md's stale backbone description"
  ```

---

## Self-Review Notes

- **Spec coverage:** Decision 1 (Face Landmarker transformation matrix) →
  Task 3. Decision 2 (blendshapes + hysteresis) → Task 4. Decision 3 (model
  bundle baked at build time) → Task 1. Decision 4 (CPU delegate + real-
  container/architecture verification) → Task 2 (delegate) + Task 7 (build
  verification). Decision 5 (latency, pinned to 150ms, downscale-then-
  re-gate) → Task 6 (script) + Task 7 (execution + re-gate rule). Decision 6
  (two-part acceptance gate) → Task 7. `RunningMode.VIDEO` +
  `CAP_PROP_POS_MSEC` monotonic guard → Task 2. Rotation-submatrix
  extraction + empirical axis determination → Task 3.
- **Sequencing:** Task 1 (deps/model bundle) and Task 2 (client wrapper)
  must land before Task 3's empirical probe can run (needs a working
  `FaceLandmarker` instance). Task 3 (head-pose) and Task 4 (eye-state) have
  no dependency on each other and could run in parallel if two people are
  available. Task 5 depends on both 3 and 4. Task 6 depends on 5 (calls the
  same service functions). Task 7 depends on everything.
- **Type consistency check:** `extract_pitch_deg(transformation_matrix:
  np.ndarray) -> float` (Task 3) is called consistently in Task 5's
  `run_real_video` and Task 6's `measure_latency.py` with the same
  `result.facial_transformation_matrixes[0]` argument shape.
  `blink_score(blendshapes: Dict[str, float])` (Task 4) is fed a dict built
  the same way (`{c.category_name: c.score for c in result.face_blendshapes[0]}`)
  in both Task 5 and Task 6. `BlinkStateTracker.update(score, now)` and
  `MonotonicTimestamp.next(raw_ms)` signatures match their Task 4/Task 2
  definitions everywhere they're called.
- **No placeholders:** Task 3 is the one place this plan cannot hand over a
  fixed formula (the pitch axis is a genuine unknown until empirically
  probed) — this is disclosed explicitly in the task's own preamble, with a
  concrete, runnable procedure (not a vague "figure it out") and a clear
  instruction to substitute the real finding everywhere a placeholder axis
  choice appears, rather than leaving it inconsistent.
- **Revision (post-review) fixes:**
  1. Task 3's Euler-extraction formula silently assumed a specific
     composition-order convention (`Rz*Ry*Rx`) in addition to "which axis is
     pitch" — a second, independent assumption single-axis tests structurally
     cannot catch (no cross-axis coupling with only one non-zero angle). Added
     a combined-motion empirical check to Step 3 and a synthetic
     combined-rotation self-consistency test to Step 5.
  2. Task 4 Step 4 referenced a nonexistent "Task 8" (this plan only has 7
     tasks) and implied the blink thresholds would get revisited without any
     step actually doing so. Fixed the reference and added Task 7 Step 3.5,
     which explicitly inspects real blink-score CSV data against the two
     thresholds and requires a fix-and-rerun if they don't hold up.
  3. `measure_latency.py`'s first draft only timed through
     `calc.add_frame(...)`, omitting `face_tracker.update()`,
     `emitter.update()`, and the `build_trigger_payload`/
     `store.update_latest` path that only runs on a firing frame — exactly
     the frame where latency matters most and where the omitted cost (lock-
     guarded store write) actually lives. Rewritten to mirror
     `run_real_video()`'s full per-frame body.
  4. Task 7's Gate 1 jump-check compared "the previous row with a pitch
     value," which would misjudge a legitimate large angle change across a
     face-loss gap (plausible exactly during a drowsy/microsleep moment) as
     a flip artifact. Rewritten to only compare truly-consecutive CSV rows,
     explicitly skipping (and logging, not silently ignoring) any gap.
  5. CLAUDE.md still names MobileNetV3 as the backbone — the "Known
     Deviations" update was scoped to a different plan's Task 17, which was
     assigned to a teammate and, per that plan's own ledger, was never
     executed. Added Task 7 Step 6.5 to fix this, since this migration is a
     second undocumented deviation stacking on an already-undocumented first
     one.
  6. A prior review round flagged a possible idempotency gap in Task 5's
     `if face_signal == "UNKNOWN":` branch (could it fire every frame during
     a prolonged face-loss period, not just once?) but the fix for it never
     landed in the plan text. Resolved by reading the actual current
     `FacePresenceTracker.update()` source (not relying on memory of an
     earlier session/plan): it already guards with an `_unknown_active` flag
     and is edge-triggered by construction, so the existing code was correct
     — added an explicit "Verified before writing this task" note to Task 5
     citing the exact mechanism, so this isn't an unstated assumption anymore.
