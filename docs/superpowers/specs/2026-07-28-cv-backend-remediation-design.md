# CV Backend Remediation (Face Landmarker migration) — Design

**Date:** 2026-07-28
**Author:** Phát (CV/DMS pipeline)
**Status:** Approved by Phát — ready for implementation planning
## Background

Real-video testing (results below were originally logged in
`dms-ai-engine/PITCH_ESTIMATION_FINDINGS.md`, since removed from the working
tree — the key evidence is restated here so this spec stands on its own)
found that the 6-point
`solvePnP`-based head-pose estimation from Task 8 suffers from **PnP
flip-ambiguity** on real footage: `solvePnP` finds a geometrically low-error
fit (2.8–6.2px reprojection error) that is nonetheless the ~180°-flipped twin
of the true pose (confirmed via rotation-matrix sign pattern: `R[1,1]` and
`R[2,2]` both consistently near -1). This is a known PnP phenomenon for
near-frontal, near-planar landmark configurations combined with an
*approximated* camera matrix (no real calibration). It caused a false
negative — a real "drowsy" test video peaked at score 0.800, never crossing
the 0.85 CRITICAL threshold — and, separately, exposed a missing-`abs()`
red herring in `score_calculator.py` that was investigated and ruled out as
the real cause (see the findings doc for the full trail).

This design replaces the CV backend for both head-pose and eye-closure with
MediaPipe's Face Landmarker Tasks API, which fits a full learned 3D face
model (478 landmarks) rather than solving PnP from 6 sparse points — the
flip-ambiguity failure mode should not apply to a model-fit transform the
same way it applies to an underdetermined sparse-point PnP solve.

## Scope framing — this is a CV backend swap, not a two-formula patch

It touches most of `eye_state.py`, `head_pose.py`, and `main.py`'s
`run_real_video()` real-video path, and the existing Task 7/8 test suites
(written against the old `mp.solutions.face_mesh`/6-point-PnP API surface)
will need to be rewritten against the new API, not just patched. Time-boxed
at half a day, but budget it as a rewrite.

## Decisions

### 1. Head-pose: MediaPipe Face Landmarker `facial_transformation_matrixes`

Replace `head_pose.py`'s 6-point `solvePnP` with
`FaceLandmarkerOptions(output_facial_transformation_matrixes=True)`. Extract
yaw/pitch/roll from the returned 4×4 transform's **rotation submatrix**
(`matrix[:3, :3]`, top-left 3×3 — the 4×4 also carries translation, easy to
extract wrong if not careful).

**The Task 8 extraction formula (`atan2(R[2,1], R[2,2])`) must NOT be reused
as-is.** It was derived specifically for the custom 6-point 3D model's own
axis convention; MediaPipe's canonical face model may use a different
convention (handedness, axis order). Re-run the same empirical methodology
Task 8 used: probe pure rotations about each axis independently, confirm by
hand-derivation (or against known synthetic rotations) which extraction
gives a value that increases monotonically with real head-droop before
trusting it as "pitch."

**Running mode — must be set explicitly, not left at a default:**
- File-based video (current `--video` path): `RunningMode.VIDEO`, fed a
  strictly monotonically increasing `timestamp_ms`. Read
  `cap.get(cv2.CAP_PROP_POS_MSEC)` (reflects the video's real decoded
  timestamp, more accurate than accumulating a fixed `frame_dt`) — but guard
  it: some codecs/containers (B-frames, variable frame rate) can return a
  timestamp that isn't strictly greater than the previous read, and
  `VIDEO` mode **throws an exception immediately** (not a warning) on a
  non-monotonic timestamp. If the new reading isn't greater than the last
  one used, bump it by a small epsilon (or fall back to the accumulated
  `frame_dt` estimate for that one frame) rather than passing it through
  raw. Do not assume the current 3 test videos are representative of every
  video this will ever see, including Phase 0's own upcoming footage.
- Live camera (not implemented yet, future work): `RunningMode.LIVE_STREAM`
  + async callback — noted here as the future direction, not built now.

Using `IMAGE` mode (simpler API, easy to reach for by accident) would lose
frame-to-frame tracking continuity — exactly the temporal-continuity benefit
that reduces jitter/noise, which is the robustness property this migration
is chasing. Do not use `IMAGE` mode for either the file or live-camera path.

### 2. Eye-closure: `face_blendshapes` (`eyeBlinkLeft`/`eyeBlinkRight`)

Replace hand-computed EAR in `eye_state.py` with
`FaceLandmarkerOptions(output_face_blendshapes=True)`, reading the
`eyeBlinkLeft`/`eyeBlinkRight` blendshape scores — continuous [0,1], not a
binary EAR-threshold classification.

**Add hysteresis at the raw-signal layer**, not only at the composite-score
layer where the only hysteresis currently lives (`trigger_emitter.py`'s
`TriggerEmitter`, which operates on the composite score, not raw eye state).
Two thresholds: enter "closed" above a higher blendshape value, exit
"closed" only below a lower one. This directly targets the exact failure
mode found in the drowsy video — a single frame where EAR crossed
`EAR_CLOSED_THRESHOLD` (0.18) instantly zeroed the 0.25-weighted
`eye_closed_now` term right before the CRITICAL threshold would have been
crossed. Note this changes what PERCLOS numerically represents slightly
(the closed/open classification is now debounced, not raw-instantaneous) —
a standard, accepted practice in PERCLOS literature, but worth documenting
as an explicit change from before.

### 3. Model bundle: baked into the Docker image at build time

`face_landmarker.task` must be fetched via `RUN curl/wget ...` inside the
Dockerfile (build time), never fetched by Python code at container runtime —
preserves the no-cloud-round-trip-in-the-safety-critical-path principle
already locked in for this project.

### 4. Explicit CPU delegate + real-container verification (including architecture)

`BaseOptions(delegate=BaseOptions.Delegate.CPU)` must be set explicitly when
constructing `FaceLandmarkerOptions` — MediaPipe Tasks may otherwise default
toward a GPU delegate on a dev machine that has one, then fail silently or
crash in the headless container (no GPU/OpenGL/EGL), repeating the exact
"works on my machine" failure class this project has already hit twice
(the `libGL.so.1`/`libxcb.so.1` import failures, the PID-1 SIGTERM issue).

**CPU delegate alone doesn't cover CPU architecture risk.** Does the
pip-installed `mediapipe` wheel actually support the Container Node's real
CPU architecture (x86_64 vs arm64 — the Skycraft VM showed a `trout_arm64`
prompt earlier in this project, but the Container Node is not guaranteed to
share that architecture)? Per this project's own "self-test with evidence,
escalate only after" principle: don't ask CarSky/the mentor about this
speculatively. Build the Docker image and let a real build-time failure
(`pip install mediapipe` failing to find a matching wheel) be the concrete
evidence, if it happens — that's exactly the kind of specific, evidenced
question this project's escalation path calls for. If the target
architecture is knowable ahead of time, build with `docker buildx build
--platform ...` set explicitly rather than defaulting to the dev machine's
native architecture.

### 5. Latency: measured, not assumed — pinned to CLAUDE.md's actual KPI number

CLAUDE.md's KPI table states **p95 ≤150ms detection latency**. Measure the
**whole pipeline** (frame read → inference → score computed) — not just the
`detect_for_video()` call in isolation — since that's the latency that
actually affects the driver. Take p95 across **all 4 videos** (the original
3 plus the drowsy one), not a single short clip, for a statistically
meaningful sample.

If p95 exceeds 150ms, two documented options — pick and record the reasoning,
don't leave it ambiguous under time pressure:
- (a) Downscale the input frame resolution before feeding it to Face
  Landmarker. **If chosen, re-run the entire Gate 1 physical-plausibility
  check (Decision 6 below) after downscaling** — lower resolution can degrade
  landmark accuracy at difficult angles/lighting, not just speed. Do not
  treat downscaling as a pure performance change that skips re-validation.
- (b) Accept a relaxed KPI, with the reasoning written into CLAUDE.md's KPI
  table (not silently changed).
(`num_faces` is already 1, the minimum — not an available lever.)

### 6. Acceptance gate — two ways to pass, both measurable

**Gate 1 — physical plausibility (measurable, not a judgment call):**
- Zero frame-to-frame jumps greater than **90°** anywhere in the drowsy
  video's full pitch/yaw trajectory (a real flip jumps ~180°; natural head
  motion at 30fps essentially never exceeds a few degrees between
  consecutive frames — 90° cleanly separates the two). Assert this
  programmatically over the whole trajectory, not by inspection.
- Visual cross-check on at least 2–3 source frames (e.g. the same frame 36
  used during diagnosis): the sign/direction in the numbers matches the
  direction visibly seen in the video (head down = positive, matching the
  existing `head_pitch_deg` docstring convention).

**Gate 2 — score outcome (either satisfies "Phase 1 is fixed"):**
- (a) The drowsy video's score exceeds 0.85 AND the normal/distracted videos
  still don't false-positive AND p95 latency ≤150ms (or a documented,
  reasoned exception per Decision 5) — **or**
- (b) The drowsy video doesn't exceed 0.85, but Gate 1 passes (pose/eye-
  closure are now physically plausible) — treat this as genuine progress,
  not a Phase 1 failure, and move to Phase 3 to tune the 0.85 threshold /
  sustain window against real data instead of re-touching Phase 1. This
  distinction matters: without it, a threshold/sustain-window tuning problem
  could be mistaken for "the CV backend swap didn't work" and trigger an
  unproductive loop back into Phase 1.

**Container verification is part of the gate, not optional:** Face Landmarker
must be confirmed to initialize and run successfully inside the actual Docker
container (reusing `test_container.sh`'s pattern) — on a build targeting the
Container Node's real architecture per Decision 4 — before Phase 1 is
considered done. Passing only on the dev machine does not satisfy this gate.

## Phase 2 — expanded clip set (external dependency, not a coding task)

Phát will record/collect additional footage (multiple people, glasses,
low light, off-axis camera angle) before Phase 2 starts — this is manual
data collection outside this session's scope. Phase 2 itself is: re-run the
Phase-1-fixed pipeline against that expanded set once it exists. New failure
modes surfacing here are expected and normal, not evidence Phase 1 was
wrong.

## Phase 3 — targeted fixes from Phase 2 data (decision table)

| Phase 2 finding | Response |
|---|---|
| Face detection drops at extreme camera angles | Consider a lower `min_face_detection_confidence`, OR document a known camera-angle limitation for the demo — don't try to solve every angle |
| Blendshape/eye signal noisy in low light | Document "well-lit cabin" as a known assumption — don't chase it with more modeling unless time remains |
| 0.85 threshold / 0.55-0.25-0.20 weights misaligned after the signal swap | Retune using the logged CSV data as an empirical fit — explicitly documented as "not scientifically validated," matching CLAUDE.md's existing stance on these numbers |
| Glasses break blendshape accuracy | Document as a known limitation — do not attempt a fix in this 2-day window |

## Phase 4 — stretch (only if AAOS/Container/App/FSM integration stays on schedule)

EMA smoothing on the composite score; add a yawn signal (`jawOpen`
blendshape — near-free once blendshapes are already computed); use Face
Landmarker's per-frame confidence to filter low-quality frames out of scoring
instead of feeding them in; a custom-trained classifier is explicitly
deprioritized to a post-competition research direction (correctly reasoned —
collecting and labeling training data cannot happen inside a 2-day window).

## Known external-validity limits (carried forward from the findings doc, not resolved by this design)

All 3 current test videos are single-actor, close-up, well-lit, near-frontal,
smooth "stock footage"-style motion — not representative of a real DMS camera
(typically mounted off-axis on the steering column/dash). No evidence yet on:
multiple faces, glasses, low light/IR night vision, off-angle cameras,
partial occlusion (a hand covering the mouth mid-yawn), or the fast/blurry
motion of a real microsleep head-drop (which smooth stock footage cannot
exercise). MediaPipe's blendshape training-data representativeness for real
cabin conditions is unverified. This design does not claim to resolve these —
they're exactly what Phase 2's expanded clip set is for.
