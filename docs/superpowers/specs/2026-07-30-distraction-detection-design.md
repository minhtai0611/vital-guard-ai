# Distraction Detection — Design

**Date:** 2026-07-30
**Author:** Phát (CV/DMS pipeline)
**Status:** Approved by Phát — ready for implementation planning

## Background

This project's CV backend already computes head pitch (`services/head_pose.py`'s
`extract_pitch_deg()`, from MediaPipe Face Landmarker's
`facial_transformation_matrixes`) and eye-closure (`services/eye_state.py`'s
`blink_score()`/`BlinkStateTracker`) to detect drowsiness. Distraction
detection was explicitly deferred ("Thôi để sau") when the CV backend
remediation plan was scoped, to ship the Face Landmarker migration first.
This design returns to it now that the migration is complete and its own
acceptance gate passed (see `dms-ai-engine/CV_REMEDIATION_RESULTS.md`).

**Test videos:** this design's testing plan uses the same 5 real videos
already established earlier in the same working session's regression pass
(not newly introduced here): `dms-ai-engine/out/normal.mp4`, `drowsy.mp4`,
`distracted.mp4` (3 short single-state clips), and `full-stream-2.mp4`,
`full-stream-facemp4.mp4` (2 long multi-state clips added later, used to
find and fix the baseline-pitch-calibration bug recorded in
`CV_REMEDIATION_RESULTS.md`'s "Regression pass against 5 real videos"
section). All 5 are confirmed present in `dms-ai-engine/out/` as of this
design.

## Scope framing — two features, deliberately narrowed from the original ask

"Mất tập trung" (distraction) was narrowed from the original ask through
explicit pushback and user decision, given MediaPipe Face Landmarker only
sees the face/head, not hands or objects:

- **Feature A — Gaze/head-off-road.** Uses head pitch AND yaw (both from the
  transformation matrix already computed for drowsiness) plus eye state.
  Cheap: no new model, no new Docker bake-in, negligible latency impact.
- **Feature B — Hands-off-wheel.** Originally asked as "phone in hand," but
  MediaPipe has no phone/object detector, and training a custom one is not
  feasible in a 2-day hackathon window with no labeled phone dataset.
  Narrowed to **hand presence in a defined wheel region**, using MediaPipe's
  pretrained **Hand Landmarker** (same build-vs-buy reasoning as Face
  Landmarker) — a real, buildable proxy for "at least one hand not on the
  wheel," not true phone detection.

Both features are built together in this plan (explicit user decision after
being shown the cost/risk asymmetry between them — Feature B requires a new
model, a new Docker bake-in, a second per-frame inference call, and a new
latency measurement, none of which Feature A needs).

**Camera framing confirmed:** extracted frames from `normal.mp4` and
`distracted.mp4` (both stock-footage-style clips) show a full upper-body/
dashboard-level shot with both hands visible on the wheel — not a tight face
crop. This makes Feature B's wheel-region heuristic viable against the
current test footage. It does **not** confirm the real Container Node/
Skycraft camera will share this framing — see Known Limitations.

## Decisions

### 1. Feature A — gaze-off-road signal (pitch + yaw, with a drowsiness exclusion)

**Both axes, not yaw alone.** An earlier draft of this design used yaw only,
reasoning it wouldn't overlap with drowsiness's pitch-based head-droop
signal — this was wrong: it misses "looking down at a phone" (a pitch bias,
not a yaw bias). Correct signal:

```python
head_off_road = abs(pitch_deg) > PITCH_OFF_ROAD_THRESHOLD or abs(yaw_deg) > YAW_OFF_ROAD_THRESHOLD
```

`PITCH_OFF_ROAD_THRESHOLD`/`YAW_OFF_ROAD_THRESHOLD` are separate constants
from drowsiness's `max_droop_deg=25.0` — "head down enough to imply a phone"
and "head down enough to imply nodding off" are not assumed to be the same
angle; each is tuned independently against real data during the acceptance
gate (mirroring how `BLINK_CLOSE_THRESHOLD`/`BLINK_REOPEN_THRESHOLD` were
picked as a reasoned starting point and later checked against real CSV
data, not hand-waved). Reasoned starting values, not final:
`YAW_OFF_ROAD_THRESHOLD = 30.0` (clearly past a normal mirror-check glance
—typically well under 20° — while comfortably under `distracted.mp4`'s
~40-60° full head-turn extreme); `PITCH_OFF_ROAD_THRESHOLD = 20.0` (close
to but under `drowsy.mp4`'s ~22-25° droop peak — this pitch threshold is
**not** expected to cleanly separate distraction from drowsiness on its
own, since a phone-glance droop and a nodding-off droop can reach similar
angles; that separation is deliberately delegated to the `is_gaze_off_road()`
eyes-open/closed exclusion below, not to this threshold).

**Axis extraction reuses existing code, no new probe needed.**
`services/head_pose.py`'s own docstring already empirically confirmed Y is
yaw (combined-motion probe against `distracted.mp4`: y ranging ~+23° to
-40° during a head turn, Task 3 of the CV remediation plan). Add
`extract_yaw_deg(transformation_matrix) -> float` returning `y` from the
existing `rotation_matrix_to_euler_deg()` — no new empirical investigation,
just exposing an already-computed, already-validated value. `extract_pitch_deg()`
is reused as-is, not reimplemented.

**Shared baseline pitch — do not reproduce the just-fixed calibration bug.**
`main.py`'s `run_real_video()` was recently fixed to call
`DrowsinessScoreCalculator.calibrate_baseline()` from the first
`BASELINE_CALIBRATION_SECONDS` (1.0s) of pitch readings, because an
uncalibrated 0° baseline silently capped the drowsiness score on any camera
whose neutral pitch isn't ~0° (see `CV_REMEDIATION_RESULTS.md`). Feature A's
pitch-based off-road check **must use the same calibrated baseline**, not a
second independent one — `run_real_video()` computes the baseline once and
passes it to both the drowsiness and distraction pitch calculations. Yaw has
no equivalent baseline concept (left/right symmetry is assumed neutral at
0°) — only pitch needs baseline correction.

**Drowsiness exclusion — the core disambiguation, as its own tested function.**
A drooped head with closed eyes is drowsiness, not distraction; a drooped
head with open eyes (looking down at a phone) is distraction, not
drowsiness. This is resolved with a dedicated, independently-testable
function — not buried as inline logic in `main.py` — living in
`services/distraction_score_calculator.py` (co-located with its only
caller; kept as a standalone top-level function specifically so it gets its
own named tests instead of being folded into a generic calculator test):

```python
def is_gaze_off_road(head_off_road: bool, eye_closed: bool) -> bool:
    return head_off_road and not eye_closed
```

`eye_closed` here is the **already-debounced** `BlinkStateTracker.update()`
output computed once per frame in `run_real_video()` — not a fresh raw
`blink_score < BLINK_CLOSE_THRESHOLD` check. Reusing a second raw-threshold
check would reintroduce the exact single-frame-noise problem
`BlinkStateTracker`'s hysteresis was built to solve.

Required tests (directly on `is_gaze_off_road()`, not indirectly through the
calculator):
- `test_is_gaze_off_road_treats_head_down_with_eyes_open_as_distraction`
- `test_is_gaze_off_road_treats_head_down_with_eyes_closed_as_not_distraction`

**Additional rigor for the yaw axis** (mirroring Task 3's own methodology,
since this is the same class of risk that produced the original PnP
flip-ambiguity bug and the later composition-order pre-flight catch):
- Visual cross-check of yaw's sign convention on 2-3 frames of
  `distracted.mp4` (does positive/negative y match the visible turn
  direction?).
- A real-video empirical check that yaw is not badly contaminated by pitch
  motion — `head_pose.py`'s docstring already reports this qualitatively
  ("y and z also drift... by a smaller margin than x") but has no
  quantitative threshold; add one by inspecting `drowsy.mp4`'s droop
  segment's y values the same way pitch's insensitivity-to-yaw was
  quantified (`~-9 to -3 deg band`).
- A unit test symmetric to the existing pitch-insensitivity test:
  `test_extract_yaw_deg_is_insensitive_to_pitch_rotation` (using
  `_rotation_matrix_x` synthetic rotations). Note: the existing
  `test_combined_rotation_does_not_corrupt_pitch_extraction` already
  asserts both x and y recover correctly in a combined synthetic rotation
  (lines 85-86 of `test_head_pose.py`) — this covers the synthetic/
  analytic case; the real-video check above is the separate, real-data-
  dependent check the synthetic test cannot replace (same distinction the
  existing docstring already draws for pitch).

### 2. Feature B — hands-off-wheel signal

**New client:** `services/hand_tracker.py`, mirroring
`face_landmarker_client.py`'s pattern — `build_video_mode_hand_landmarker(model_path)`
using `mediapipe.tasks.python.vision.HandLandmarker`, explicit
`BaseOptions.Delegate.CPU`, `RunningMode.VIDEO`, `num_hands=2` (explicit,
not left to a default).

**Model bundle:** `hand_landmarker.task` baked into the Docker image at
build time (never fetched at runtime), version-pinned URL, verified via
ETag/byte-size the same way Task 1 verified `face_landmarker.task`.

**Wheel region:** a fixed bounding box in normalized frame coordinates
(calibrated against the current stock-footage camera framing). This is an
explicit, documented calibration assumption tied to one specific camera
angle — **must be re-verified against the real Container Node/Skycraft
camera's actual framing before being trusted**, not assumed to transfer
(see Known Limitations).

**Visibility is tri-state, not boolean — never fabricate "off wheel" from a
missing detection.** Mirrors `FacePresenceTracker`'s own principle (a
missing signal is not evidence of the worst case): `hands_visibility: FULL|PARTIAL|UNKNOWN`
based on how many hands `HandLandmarker` detected that frame (2/1/0). No
separate debounce class is needed for this classification itself — it's
computed fresh each frame, and the sliding-window ratio computation (below)
already provides the needed smoothing by excluding `UNKNOWN` frames from
its denominator.

**`hands_on_wheel` semantics — the dangerous case must not be optimistically
cleared.** `hands_on_wheel: bool` is `True` only when **all** currently-
visible hands are within the wheel region. If visibility is `PARTIAL` (one
hand visible) and that one visible hand is inside the region, `hands_on_wheel=True`
— documented explicitly as "known partial information, bounded optimism,"
not hidden. If the one visible hand is outside the region, `hands_on_wheel=False`
— this is the one-hand-on-wheel-one-hand-on-phone case, the single most
dangerous scenario Feature B exists to catch; it must not be misclassified
as safe just because *a* hand is visible and on the wheel. When
`hands_visibility=UNKNOWN`, `hands_on_wheel`'s value is a don't-care (the
frame is excluded from the ratio regardless) — set to `False` by convention
for clean serialization, never read for a decision.

Required tests in `services/hand_tracker.py`'s test file:
- `test_hands_on_wheel_true_only_when_all_visible_hands_in_wheel_region`
- `test_hands_on_wheel_false_when_partial_visibility_and_visible_hand_is_off_wheel`
  (locks in the dangerous case explicitly, so a future "optimize to True if
  ≥1 visible hand is on wheel" change would fail a named test, not just
  silently regress)

**Latency and Gate 1 impact:** a second per-frame model inference is a real
cost, not assumed to fit in the current margin (~15-20ms of a 150ms
budget) — must be measured on the real container after implementation, with
the same downscale-then-re-run-Gate-1 contingency already established for
the drowsiness migration (Decision 5 of the CV remediation design) if the
budget is exceeded.

### 3. Composite distraction score and trigger — separate from drowsiness's

`services/distraction_score_calculator.py` (new file, mirrors
`score_calculator.py`'s shape but is a distinct class — the two composite
scores are independent, not a shared calculator with extra fields):

```python
@dataclass
class DistractionFrameFeatures:
    timestamp: float
    gaze_off_road: bool      # = is_gaze_off_road(head_off_road, eye_closed)
    hands_visibility: str    # "FULL"|"PARTIAL"|"UNKNOWN"
    hands_on_wheel: bool     # meaningful only when hands_visibility != "UNKNOWN"

class DistractionScoreCalculator:
    def __init__(self, window_seconds: float = 2.0, sample_hz: float = 10.0):
        ...

    def compute_score(self) -> float:
        gaze_off_road_ratio = <fraction of window with gaze_off_road=True>
        countable = [f for f in window if f.hands_visibility != "UNKNOWN"]
        hands_off_wheel_ratio = (
            sum(1 for f in countable if not f.hands_on_wheel) / len(countable)
            if countable else 0.0  # no countable frames -> neutral, not fabricated
        )
        return W_GAZE * gaze_off_road_ratio + W_HANDS * hands_off_wheel_ratio
```

`W_GAZE=0.65`, `W_HANDS=0.35` — a fresh, reasoned starting point (gaze is
the more direct signal, hands is supporting), **explicitly not copied from**
drowsiness's `0.55/0.25/0.20` weights, and explicitly unvalidated — same
documented stance the project already takes on those numbers. Revisited
against real footage at the acceptance gate, same as `BLINK_CLOSE_THRESHOLD`
was.

`services/distraction_trigger_emitter.py` (new file, **not** a reuse of
`TriggerEmitter` — distraction's sustain/cooldown reasoning is genuinely
different from drowsiness's):

```python
class DistractionTriggerEmitter:
    def __init__(self, enter_threshold: float = 0.70, exit_threshold: float = 0.40,
                 sustain_seconds: float = 1.5, cooldown_seconds: float = 5.0):
        ...
```

Same two-threshold hysteresis shape as `TriggerEmitter`, different reasoned
numbers: shorter `sustain_seconds` (1.5s vs. 2.0s — a phone glance or a
hand off the wheel is dangerous on a shorter timescale than the drowsiness
FSM's own drowsiness-confirmation window), shorter `cooldown_seconds` (5.0s
vs. 10.0s — distraction events plausibly recur more often and shouldn't be
suppressed as long). All four numbers are starting points, explicitly
unvalidated, revisited at the acceptance gate.

Required tests in `services/distraction_trigger_emitter.py`'s test file:
mirror all 8 of `TriggerEmitter`'s existing test cases (debounce, hysteresis,
no-duplicate-while-above, rearm-after-exit, critical/recovered string
returns, none-when-not-firing) against `DistractionTriggerEmitter`'s own
numbers.

**State mapping:** `distraction.state` uses the same `NORMAL|WARNING|CRITICAL`
pattern as `_state_for_score()`. Only `CRITICAL` triggers a gateway action
(voice reminder); `WARNING` is overlay-visible only, no action — mirrors how
drowsiness's own `WARNING` state works.

### 4. Schema — additive, not breaking

`contracts/trigger.schema.json` gets one new required top-level sibling to
`features` (not nested inside it — distraction is an independent status +
its own features, not one more feature of the drowsiness state):

```json
"distraction": {
  "score": 0.0,
  "state": "NORMAL|WARNING|CRITICAL",
  "yawDeg": 0.0,
  "pitchDeg": 0.0,
  "handsVisibility": "FULL|PARTIAL|UNKNOWN",
  "handsOnWheel": true,
  "reason": "string"
}
```

`pitchDeg` here is the same baseline-corrected pitch used for the off-road
check (Decision 1), not a second raw reading. Added to `required` alongside
the existing fields — no backward-compatibility shim, since both the
Python producer and Kotlin consumer are updated together in this plan.

**Transport confirmed unchanged:** the current transport is HTTP JSON
(`GET /latest-trigger`), not Android Intent extras/ADB broadcast — the
existing schema doc's own note ("Kept nested... because the transport is
HTTP JSON, not Android Intent extras") already covers this; the nested
`distraction` object needs no flattening. (A flattening concern was raised
during design as a precautionary note only, not a transport change
proposal — confirmed with the author.)

### 5. Kotlin — a separate controller, arbitrated through a shared voice gateway

**Two independent FSM instances, not one merged enum.** Drowsiness and
distraction are physiologically independent — a driver can be drowsy while
looking straight ahead, or distracted while fully alert — so forcing them
into one mutually-exclusive state enum would either hide a real simultaneous
case or need an artificial priority rule at the *state* level. Instead:
`DrowsinessController` (existing, modified) and a new `DistractionController`
each independently track their own `payload.state` / `payload.distraction.state`,
each with their own `latched` flag and `lastCorrelationId` — no shared
correlationId namespace needed; each controller's own idempotency comes
from its latch state, not from correlationId matching, so two instances
safely reading the same payload's single top-level `correlationId` is not a
conflict (a namespace-per-concern scheme was considered and dropped as
unnecessary surface area).

```kotlin
enum class AlertSource { DROWSINESS, DISTRACTION }

class AlertArbiter(private val voiceAlertGateway: VoiceAlertGateway) {
    private var drowsinessCriticalActive = false
    private var activeSpeaker: AlertSource? = null  // who is ACTUALLY sounding right now

    fun setDrowsinessCriticalActive(active: Boolean) { drowsinessCriticalActive = active }

    fun requestVoiceAlert(source: AlertSource, message: String) {
        if (source == AlertSource.DISTRACTION && drowsinessCriticalActive) {
            Log.i(TAG, "Suppressed distraction alert -- drowsiness CRITICAL has priority")
            return
        }
        activeSpeaker = source
        voiceAlertGateway.triggerAlert(message)
    }

    fun stopAlert(source: AlertSource) {
        if (activeSpeaker != source) {
            // A suppressed source was never actually sounding -- its stop request
            // must not cut off whichever source IS legitimately active.
            Log.i(TAG, "Ignored stopAlert from $source -- does not own the active alert (owner: $activeSpeaker)")
            return
        }
        activeSpeaker = null
        voiceAlertGateway.stopAlert()
    }
}
```

**Ownership tracking is load-bearing, not incidental.** An earlier draft had
`stopAlert(source)` call `voiceAlertGateway.stopAlert()` unconditionally,
ignoring `source` entirely. Concrete failure this caused: drowsiness fires
`CRITICAL` and is genuinely speaking (not suppressed); distraction also
fires `CRITICAL` at the same time and is correctly suppressed (never
actually spoke); distraction's score then drops below its exit threshold,
and `DistractionController` calls `alertArbiter.stopAlert(DISTRACTION)` as
part of its normal revert path — with the unconditional version, this
silently cuts off the drowsiness alert that IS actively sounding, at
exactly the moment it matters most. Tracking `activeSpeaker` (set only when
a request actually reaches the real gateway, i.e. was not suppressed) fixes
this: a `stopAlert()` call from a source that never owned the active alert
is a no-op.

Required tests, added specifically because the two arbitration tests below
Decision 5's original test list do not exercise `stopAlert()`'s cross-source
behavior at all (this is exactly the gap that let the bug above go
unnoticed):
- `test_stopAlert_from_suppressed_source_does_not_stop_active_alert_from_other_source`
  — drowsiness requests and speaks; distraction requests while drowsiness
  is active and is suppressed; distraction calls `stopAlert`; assert the
  underlying gateway's `stopAlert()` was never invoked.
- `test_stopAlert_from_owning_source_stops_its_own_alert_normally` — the
  ordinary case, unaffected by the fix.

**Why an arbiter is needed at all:** the real problem is not an
`AudioFocus`-level OS race (the same app re-requesting its own focus doesn't
conflict at the OS level) — it's that two independently-triggered TTS
messages could be spoken over each other, unintelligibly, if both
controllers call the voice gateway around the same time. `AlertArbiter`
centralizes the one decision that matters: drowsiness always wins.

**Priority is a fixed 2-source comparison, not a generic `priority: Int`.**
An earlier draft used a generic priority-number parameter; simplified to a
direct enum comparison since there are exactly two sources with a fixed,
hardcoded precedence (drowsiness > distraction) and no third source is
planned. Revisit only if a real third alert source is added later.

**Suppressed alerts are dropped, not retried.** If a distraction `CRITICAL`
edge is suppressed because drowsiness is active, and drowsiness later clears
while distraction is still active, the distraction reminder for that
episode is **not** retried — accepted tradeoff for this iteration (the
driver already heard the higher-severity drowsiness alert). A retry/resume
mechanism would need `DistractionController` to track "suppressed, pending
resume" and `AlertArbiter` to notify it when the blocking condition clears —
real added complexity, deferred until real usage shows it's needed.

**Real cost, not free — this touches already-shipped code.**
`DrowsinessController`'s constructor changes from taking `VoiceAlertGateway`
directly to taking `AlertArbiter` (its `handleCritical()`/`revertToBaseline()`
now call `alertArbiter.requestVoiceAlert(DROWSINESS, ...)` /
`alertArbiter.setDrowsinessCriticalActive(...)` /
`alertArbiter.stopAlert(DROWSINESS)` instead of calling `voiceGateway`
directly). This is a real, non-additive change to a component that already
shipped through its own SDD review — not purely new surface area, and must
be planned as a modification task with its own regression check (existing
`DrowsinessControllerTest.kt` cases must still pass unchanged).

`DistractionController` mirrors `DrowsinessController`'s shape (idempotency
via `latched` + `lastCorrelationId`, `onConnectionLost()` fallback that
reverts/stops on a dropped connection) but only depends on `AlertArbiter` —
no `ClimateActuatorGateway` (confirmed: distraction never touches HVAC).

**Wiring:** wherever `TriggerPollClient`'s success/failure callbacks
currently invoke `drowsinessController.onPayload(payload)` /
`onConnectionLost()`, they now also invoke the equivalent
`distractionController` calls — both controllers process every payload
independently.

Required Kotlin tests:
- `DistractionControllerTest.kt` — mirrors `DrowsinessControllerTest`'s 6
  mandatory cases (normal/brief-spike/sustained/recovery/gateway-failure/
  lost-signal), substituting `payload.distraction.state`.
- `AlertArbiterTest.kt` — at minimum: (1) drowsiness `CRITICAL` +
  distraction `CRITICAL` simultaneously → only drowsiness's message is
  spoken, distraction's is logged as suppressed; (2) distraction `CRITICAL`
  alone (no drowsiness active) → spoken normally, not blocked; (3)
  `test_stopAlert_from_suppressed_source_does_not_stop_active_alert_from_other_source`;
  (4) `test_stopAlert_from_owning_source_stops_its_own_alert_normally` (see
  the ownership-tracking note above — (3)/(4) exist specifically because
  the original (1)/(2) pair never exercised `stopAlert()` and would not
  have caught the cross-source-stop bug found during design review).
- Existing `DrowsinessControllerTest.kt`: **assertions unchanged,
  construction updated to the new signature** — the constructor changes
  from taking `VoiceAlertGateway` to `AlertArbiter`, so every test's
  setup/instantiation must pass a fake `AlertArbiter` instead; this is not
  "zero lines touched," it's "the expected behavior each test asserts
  stays identical, only the wiring to build the object under test changes."

**Final UX policy needs Tài's sign-off.** The exact suppress-vs-queue
behavior and the drop-vs-retry decision above are proposed defaults, not
unilaterally final — per `CLAUDE.md`'s ownership split, Tài owns AAOS app
integration/FSM Controller/Climate/Voice. This design specifies the
contract (schema shape, arbiter hook points) Tài's implementation must
satisfy; the precise voice UX behavior should be confirmed with him before
the Kotlin side ships.

### 6. Global Constraints affected by this plan (from the prior CV remediation plan)

The CV backend remediation plan's Global Constraints section states:
"Distraction/yaw-based detection is explicitly out of scope for this plan
(deferred by the user) — do not add a yaw signal, a new schema state, or
any FSM change for it." This design directly reverses that deferral by
user decision. The implementation plan for this design must include an
explicit task to update that older plan's text (or note its supersession)
so the repo doesn't carry two contradictory statements about distraction
scope — the same class of staleness Task 7 Step 6.5 of that plan already
had to fix once for the MobileNetV3 backbone claim.

## File Structure

- Create: `dms-ai-engine/services/hand_tracker.py` — `HandLandmarker` client
  (mirrors `face_landmarker_client.py`) + wheel-region/`hands_on_wheel`
  logic + tests.
- Modify: `dms-ai-engine/services/head_pose.py` — add `extract_yaw_deg()`.
- Modify: `dms-ai-engine/tests/test_head_pose.py` — yaw tests (insensitivity
  to pitch; the existing combined-rotation test already covers the
  synthetic combined case for both axes).
- Create: `dms-ai-engine/services/distraction_score_calculator.py` —
  `is_gaze_off_road()`, `DistractionFrameFeatures`, `DistractionScoreCalculator`.
- Create: `dms-ai-engine/services/distraction_trigger_emitter.py` —
  `DistractionTriggerEmitter`.
- Create matching test files for both new services above (see Decisions 1-3
  for the specific required test names).
- Modify: `dms-ai-engine/main.py` — wire hand tracking + distraction score/
  emitter into `run_real_video()`'s per-frame loop; share the calibrated
  baseline pitch between drowsiness and distraction; extend
  `build_trigger_payload()` with the `distraction` object.
- Modify: `dms-ai-engine/tests/test_main.py` — fake `HandLandmarker`, extend
  the has-face end-to-end test to cover the `distraction` payload fields.
- Modify: `dms-ai-engine/measure_latency.py` — **explicitly** call the Hand
  Landmarker and mirror the distraction trigger-emitter path, the same way
  it already mirrors the drowsiness trigger path (Task 6 of the CV
  remediation plan was caught missing exactly this kind of completeness
  once already — do not repeat it for distraction).
- Modify: `dms-ai-engine/Dockerfile` — bake in `hand_landmarker.task`,
  version-pinned, byte-size/ETag verified.
- Modify: `contracts/trigger.schema.json` — add the `distraction` object
  (Decision 4).
- Modify: `aaos-cockpit-app/app/.../TriggerPayload.kt` — add
  `DistractionInfo`/`distraction` field.
- Create: `aaos-cockpit-app/app/.../AlertArbiter.kt`.
- Modify: `aaos-cockpit-app/app/.../DrowsinessController.kt` — route voice
  calls through `AlertArbiter` (Decision 5).
- Create: `aaos-cockpit-app/app/.../DistractionController.kt`.
- Create matching Kotlin test files (`AlertArbiterTest.kt`,
  `DistractionControllerTest.kt`); re-verify `DrowsinessControllerTest.kt`
  passes unmodified against the new constructor.
- Modify: whichever file wires `TriggerPollClient` callbacks to
  `DrowsinessController` — fan out to `DistractionController` too.
- Modify: `docs/superpowers/plans/2026-07-28-cv-backend-remediation.md` —
  update/annotate the now-superseded Global Constraint on distraction/yaw
  scope (Decision 6).
- Modify: `CLAUDE.md` — document the new feature (backbone reasoning, new
  schema fields, new Global Constraints), same rigor as the existing
  "Known Deviations from Proposal" section.

## Acceptance gate (mirrors the CV remediation plan's two-part gate structure)

- **Gate 1 (physical plausibility):** zero yaw jumps >90° between truly-
  consecutive rows across all 5 real videos, using the **gap-aware** awk
  pattern already fixed for pitch (Task 7 Step 3 of the CV remediation
  plan — tracks row numbers, skips and logs any face-loss gap instead of
  comparing across it; do not reuse the original naive adjacent-row
  version). Visual cross-check of yaw sign/direction on 2-3 frames of
  `distracted.mp4`.
- **Gate 2 (behavioral correctness on real footage):** distraction fires
  `CRITICAL` during `distracted.mp4`'s head-turn; distraction does **not**
  fire during `drowsy.mp4`'s and `full-stream-facemp4.mp4`'s real drowsy
  segments (the reverse of the synthetic confound tests, checked against
  real footage, not just fakes). Neither Gate result is treated as final —
  same path-(a)/path-(b) framing as the CV remediation plan: physically
  plausible-but-under-threshold is progress, not failure, and routes to a
  documented threshold-tuning follow-up rather than reopening this plan's
  tasks.
- **Latency:** COMBINED p95 (drowsiness + distraction, both models, across
  all 5 videos) measured inside the real container, ≤150ms budget, with
  the downscale-then-re-Gate-1 contingency if exceeded.

## Out of Scope

- True phone-object detection (would need a custom-trained object detector
  and a labeled dataset — not feasible in this timeframe; hands-off-wheel
  via Hand Landmarker is the accepted proxy, see Scope framing).
- Independent gaze-direction estimation (eye-gaze vector distinct from head
  pose) — MediaPipe Face Landmarker doesn't provide this; head pose is used
  as the gaze proxy, a known, undeclared-as-scientifically-validated
  approximation (consistent with this project's existing stance on
  unvalidated thresholds/weights).
- Verifying the wheel-region calibration against the real Container Node/
  Skycraft camera's actual mounting angle — this design's wheel-region box
  is only confirmed against the current stock-footage test clips.
- Final voice-alert UX policy (exact suppress/queue wording, drop-vs-retry)
  — proposed defaults here, pending Tài's confirmation (Decision 5).

## Known Limitations (carried forward honestly, not resolved by this design)

- The wheel-region bounding box is camera-framing-specific. If the real
  demo camera's angle differs meaningfully from the stock-footage clips
  used here, Feature B's hands_on_wheel signal may need recalibration
  before it's trustworthy — this design does not claim it transfers.
- `PITCH_OFF_ROAD_THRESHOLD`/`YAW_OFF_ROAD_THRESHOLD` and the
  `DistractionScoreCalculator`/`DistractionTriggerEmitter` constants are
  all reasoned starting points, not scientifically validated — same
  disclosed status as the existing drowsiness formula's weights.
- Head pose as a gaze proxy will miss distraction that doesn't involve
  head movement (e.g., eyes-only glance at a phone in a fixed mount without
  turning the head) — not solvable with the current signal set.
- **No active preemption if drowsiness turns `CRITICAL` while a distraction
  alert is already mid-speech.** `AlertArbiter` only decides at the moment
  `requestVoiceAlert()` is called — it does not proactively interrupt an
  already-playing distraction message the instant drowsiness becomes
  active. `requestVoiceAlert(DROWSINESS, ...)` will still be called and
  will still update `activeSpeaker`/call the real gateway, so the *arbiter's
  own bookkeeping* stays correct either way — but whether the driver
  actually hears a clean handoff (distraction message cut off cleanly, not
  garbled underneath drowsiness's) depends on the real `VoiceAlertGateway`
  implementation's use of `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, which is
  expected to preempt on its own at the platform level. This is a
  platform-behavior assumption, not something this design verifies or
  implements itself — flagged here explicitly rather than left to look
  handled.
