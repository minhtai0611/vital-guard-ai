# Distraction Detection — Acceptance Gate Results

Task 17 of the `2026-07-30-distraction-detection` plan. Run against the real
Docker image (`vital-guard-dms:distraction-gate`, built fresh from the
current `Dockerfile`) and all 5 real test videos (`out/normal.mp4`,
`out/drowsy.mp4`, `out/distracted.mp4`, `out/full-stream-2.mp4`,
`out/full-stream-facemp4.mp4`), on 2026-07-30.

**Bottom line: Gate 1 passes cleanly on all 5 videos. Gate 2's negative
checks pass on 4/5 videos as literally specified, and the 5th
(`full-stream-facemp4.mp4`) was investigated in depth and found to be a
correct detection of real off-road content in that video, not a logic bug
(see Step 4). Both of Gate 2's positive checks (`distracted.mp4`,
`full-stream-2.mp4`'s STAGE 3) take path (b) — real, physically plausible
signal that doesn't cross the CRITICAL threshold — for two distinct,
concretely diagnosed reasons. Latency: pass, COMBINED p95 = 54.2ms (budget:
150ms), no downscale needed.** No code changes were made by this task; per
the brief, all path-(b) and investigated findings are recorded here as
follow-up work, not reopened against Tasks 1-9.

---

## Step 1 — Docker build

```bash
cd dms-ai-engine
docker build -t vital-guard-dms:distraction-gate .
```

Result: **succeeded** (image present, `docker images` confirms
`vital-guard-dms:distraction-gate`, built from the current `Dockerfile`
including Task 1's baked-in `hand_landmarker.task`).

---

## Step 2 — Re-run all 5 videos through the container

```bash
cd dms-ai-engine
for name in normal drowsy distracted full-stream-2 full-stream-facemp4; do
  MSYS_NO_PATHCONV=1 docker run --rm \
    -v "$(pwd)/out/${name}.mp4:/data/${name}.mp4:ro" \
    -v "$(pwd)/out:/app/out" \
    vital-guard-dms:distraction-gate --video "/data/${name}.mp4" --host 0.0.0.0 --port 8765 \
    --out-csv "/app/out/evidence_${name}_distraction_gate.csv"
done
```

All 5 runs exited 0, no errors, no crashes (only MediaPipe/absl/protobuf
startup noise, identical shape to `CV_REMEDIATION_RESULTS.md`'s Step 2).

Output CSVs (all in `dms-ai-engine/out/`, gitignored — evidence artifacts,
not committed):

| File | Rows (incl. header) | Source video duration |
|---|---|---|
| `evidence_normal_distraction_gate.csv` | 79 | 2.6s |
| `evidence_drowsy_distraction_gate.csv` | 101 | 3.33s |
| `evidence_distracted_distraction_gate.csv` | 103 | 3.37s |
| `evidence_full-stream-2_distraction_gate.csv` | 579 | 19.27s |
| `evidence_full-stream-facemp4_distraction_gate.csv` | 5481 | 184.14s |

CSV columns: `ts,has_face,blink_score,head_pitch,score,state,signal,yaw_deg,
hands_visibility,hands_on_wheel,distraction_score,distraction_state,
distraction_signal`.

---

## Step 3 — Gate 1: yaw physical plausibility (jump check)

Same gap-aware awk pattern used for pitch in `CV_REMEDIATION_RESULTS.md`
(Task 7 Step 3 of the CV remediation plan), applied to `yaw_deg` (column 8):

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

Full output:

```
=== normal ===
=== drowsy ===
SKIPPED (gap, rows 68 -> 86): -11.9 -> 15.5
=== distracted ===
=== full-stream-2 ===
SKIPPED (gap, rows 31 -> 33): 13.9 -> 18.1
SKIPPED (gap, rows 489 -> 491): 31.3 -> 16.7
=== full-stream-facemp4 ===
```

**Result: Gate 1 PASSES on all 5 videos.** Zero `JUMP` lines anywhere. The
3 `SKIPPED` lines are exactly the informational, non-failure case the check
is designed to surface — face-loss gaps (drowsy's gap at rows 68→86 is the
same face-loss stretch `CV_REMEDIATION_RESULTS.md` already found for pitch
on this same video), correctly not compared/flagged as jumps.

### Visual cross-check

Already performed and permanently recorded in `services/head_pose.py`'s
module docstring during Task 2 (this is the identical procedure Step 3
asks for — re-running it would just reproduce the same numbers against the
same static video):

> Yaw sign convention was visually cross-checked against `distracted.mp4`'s
> frames: t=0.07s y=+23.89 (near-frontal, oriented screen-right), t=0.80s
> y=-40.69 (clear profile turn, oriented screen-left), t=1.30s y=+1.79
> (back to frontal) — sign is consistent: positive yaw = screen-right
> orientation, negative yaw = screen-left.

This task independently re-confirmed the underlying data is still
consistent: `evidence_distracted_distraction_gate.csv` shows
`0.07,...,23.9,...` and `0.80,...,-40.7,...`, matching the docstring's
recorded values (within float-formatting rounding). No contradiction found.

---

## Step 4 — Gate 2: behavioral correctness per segment

Segment boundaries per `CV_REMEDIATION_RESULTS.md`'s empirical findings for
`full-stream-2.mp4`: Normal ~2-7.9s, "STAGE 2: DROWSY" ~8-11.8s, "STAGE 3:
DISTRACTED" ~12-15.9s, closing reprise ~16.3-19.27s.

### 4a. Positive check — `distracted.mp4` (expect a `CRITICAL` signal somewhere)

```bash
awk -F',' 'NR>1 && $1+0>=0 {print $11}' out/evidence_distracted_distraction_gate.csv | sort -g | tail -1
awk -F',' 'NR>1 && $13=="CRITICAL" {print}' out/evidence_distracted_distraction_gate.csv
```

Output: max `distraction_score` = **0.000**. No `CRITICAL` rows at all.

**Investigated (does not cross CRITICAL — path (b), concrete root cause
found, not a logic bug):** `distracted.mp4` is only 3.37s long. Its only
real off-road excursion is exactly the head-turn already documented above
(yaw swinging from ~+24° at t=0.07s to -40.7° at t=0.80s, back to ~+2° by
t=1.30s) — entirely inside `t < BASELINE_CALIBRATION_SECONDS` (1.0s,
`main.py:47`). `main.py:281` deliberately skips feeding
`distraction_calc` during that window (the same guard that fixes the
drowsiness score's own calibration-window false-positive class — see
comment at `main.py:282-293`), so `gaze_off_road` is never computed for
this excursion at all. Confirmed via direct scan of the whole file:
`max abs(yaw_deg) for t>=1.0 is 34.2°, occurring only at the single row
t=1.00` (the very first post-calibration sample, an artifact of the turn's
tail end), and every row after that stays within `|yaw| <= ~5°` for the
remaining 2.3s of the clip — i.e. there is no second off-road event later
in the video to detect. Gate 1 (above) confirms the yaw signal itself is
real and physically plausible, not corrupted. **Root cause: this specific
test video's only distraction content happens to fall entirely inside the
calibration window design.** Follow-up (not a Tasks 1-9 reopen): either
record a replacement positive-case clip whose head-turn starts after t=1.0s,
or shorten `BASELINE_CALIBRATION_SECONDS` for the distraction path
specifically — deferred, not a blocker.

### 4b. Positive check — `full-stream-2.mp4` STAGE 3 (12-15.9s, expect `CRITICAL`)

```bash
awk -F',' 'NR>1 && $1+0>=12.0 && $1+0<=15.9 {print $1","$11","$12","$13}' out/evidence_full-stream-2_distraction_gate.csv
```

Output: `distraction_score` stays at 0.040 (12.00-12.47s) then 0.000
(12.50-15.90s); `distraction_state` = `NORMAL` for all 118 rows in the
window; no `CRITICAL` anywhere.

**Investigated (does not cross CRITICAL — path (b), concrete root cause
found, not a logic bug):**

```bash
awk -F',' 'NR>1 && $1+0>=12.0 && $1+0<=15.9 && $8!="" {v=$8; if(v<0)v=-v; if(v>max)max=v} END{print max}' out/evidence_full-stream-2_distraction_gate.csv   # max |yaw|
awk -F',' 'NR>1 && $1+0>=12.0 && $1+0<=15.9 && $4!="" {v=$4; if(v<0)v=-v; if(v>max)max=v} END{print max}' out/evidence_full-stream-2_distraction_gate.csv   # max |pitch|
awk -F',' 'NR>1 && $1+0>=12.0 && $1+0<=15.9 {print $9}' out/evidence_full-stream-2_distraction_gate.csv | sort | uniq -c   # hands_visibility
```

Results: max `|yaw_deg|` in the window = **28.5°** (below
`YAW_OFF_ROAD_THRESHOLD=30.0`), max `|head_pitch|` = **7.2°** (well below
`PITCH_OFF_ROAD_THRESHOLD=20.0`), and `hands_visibility` = `UNKNOWN` for
**all 118/118 rows** — the hand tracker never detects a hand at all during
this segment.

Visually confirmed the segment is genuine distraction content, not a
mislabeled window: extracted `out/full-stream-2.mp4` frames at t=12.3s and
t=13.0s (`ffmpeg -ss <t> -i out/full-stream-2.mp4 -frames:v 1 -update 1
<out>.png`). The t=13.0s frame shows the driver's own on-screen caption
reading **"STAGE 3: DISTRACTED"** with his gaze visibly averted/turned to
the side — real content, correctly labeled by the video itself. The
detector's underlying yaw signal (up to 28.5°) is directionally consistent
with that visible turn — Gate 1 already confirms it isn't corrupted — it
simply falls just short of the 30° threshold, and the driver's hands
(visible on the wheel in the same frame) are never picked up by the hand
tracker in this segment, so the 0.20-weight hands sub-score also
contributes nothing. **Root cause: a real, visually-confirmed off-road
event whose magnitude sits just under the current yaw threshold, combined
with a hand-tracking miss specific to this stock-footage clip's framing.**
Follow-up (not a Tasks 1-9 reopen): candidate for `YAW_OFF_ROAD_THRESHOLD`
tuning and/or investigating why `hand_tracker` never registers a detection
in this segment (possibly this clip's hand position/framing differs from
`normal.mp4`'s, which is what `WHEEL_REGION` was calibrated against — see
Step 6) — deferred, not a blocker.

### 4c. Negative check — `full-stream-2.mp4` STAGE 2 DROWSY (8-11.8s, expect NO `CRITICAL`)

```bash
awk -F',' 'NR>1 && $1+0>=8.0 && $1+0<=11.8 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_full-stream-2_distraction_gate.csv
```

Output: **empty.** PASS — zero unexpected `CRITICAL` rows in the
drowsy-only segment.

### 4d. Negative check — `drowsy.mp4` (expect NO `CRITICAL` anywhere)

```bash
awk -F',' 'NR>1 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_drowsy_distraction_gate.csv
```

Output: **empty.** PASS — zero unexpected `CRITICAL` rows across all 100
data rows.

### 4e. Negative check — `full-stream-facemp4.mp4` (expect NO `CRITICAL` anywhere)

```bash
awk -F',' 'NR>1 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_full-stream-facemp4_distraction_gate.csv
```

Output: **45 rows** flagged `CRITICAL`, spanning **t=5.65s to t=7.12s**
(~1.47s sustained), all with `hands_visibility=UNKNOWN` and `yaw_deg`
ranging from -47.8° to -19.1°.

**Investigated per the brief's explicit instruction ("if a negative case
falsely fires, that IS a real failure — investigate the specific frame's
yaw_deg/hands_on_wheel values before concluding it's a threshold issue vs.
a real logic bug"):**

```bash
awk -F',' 'NR>1 && $1+0>=5.6 && $1+0<=7.2 {print $1","$3","$4","$8","$9","$10","$11","$12}' out/evidence_full-stream-facemp4_distraction_gate.csv
```

Sample rows (full window in the raw CSV):

```
5.65,0.162,-21.9,-47.4,UNKNOWN,0,0.720,CRITICAL
6.01,0.183,-22.3,-45.8,UNKNOWN,0,0.800,CRITICAL
6.75,0.162,-20.3,-48.6,UNKNOWN,0,0.800,CRITICAL
7.12,0.342,-12.8,-19.1,UNKNOWN,0,0.720,CRITICAL
```

`blink_score` stays in the 0.13-0.52 range throughout (well below the
`BLINK_CLOSE_THRESHOLD=0.55` used elsewhere in this codebase) — i.e. the
eyes are open, not closed, ruling out this being a drowsiness episode
mislabeled as distraction. Extracted and visually compared two frames from
`out/full-stream-facemp4.mp4` (`ffmpeg -ss <t> -i out/full-stream-facemp4.mp4
-frames:v 1 -update 1 <out>.png`):

| t | CSV `yaw_deg` | Visual |
|---|---|---|
| 1.0s (baseline, outside the flagged window) | ~+1° | Driver frontal, facing the camera. |
| 6.2s (inside the flagged window) | -46.7° | Driver's head turned into a **clear profile view**, looking away to the side, eyes visibly open. |

**Conclusion: this is a correct detection of a real, visually-confirmed
gaze-off-road event that genuinely occurs in this specific video file — not
a tracking glitch and not a logic bug.** `full-stream-facemp4.mp4` is a
184-second raw take (over 9x longer than `full-stream-2.mp4`'s scripted
19.27s demo sequence) and evidently contains footage of the actor turning
his head away from the camera for ~1.5s partway through, unrelated to the
drowsiness-only content this file was previously used to regression-test
in `CV_REMEDIATION_RESULTS.md`. The brief's literal expectation ("expect NO
CRITICAL anywhere") for this file was an untested assumption carried
forward from the drowsiness-only plan and did not hold once a second signal
(gaze) was added — the detector is doing exactly what it's designed to do.
**No code change is warranted.** Recorded here so future users of this
file as a pure negative/regression fixture know it contains a genuine
off-road segment at t≈5.65-7.12s.

### 4f. Negative check — `normal.mp4` (expect NO `CRITICAL` anywhere)

```bash
awk -F',' 'NR>1 && $12=="CRITICAL" {print "UNEXPECTED CRITICAL: " $0}' out/evidence_normal_distraction_gate.csv
```

Output: **empty.** PASS — zero unexpected `CRITICAL` rows across all 78
data rows.

### 4g. Production signal check — `distraction_signal` (column 13) across all 5 evidence CSVs

```bash
for f in normal drowsy distracted full-stream-2 full-stream-facemp4; do
  echo "=== $f ==="
  awk -F',' 'NR>1 && $13!="" {print}' out/evidence_${f}_distraction_gate.csv | wc -l
done
```

Output: **0 non-empty rows in every single one of the 5 CSVs.** All of Gate
2's checks above (4a-4f) read `distraction_state` (column 12,
score-thresholded NORMAL/WARNING/CRITICAL) — but the actual production
signal is `distraction_signal` (column 13), the one
`DistractionTriggerEmitter` edge-triggers on and the one that gates
`build_trigger_payload()` in `main.py` (i.e. the only column that would
really drive an alert in the Android app). That column never fires once
across the entire acceptance corpus, including during
`full-stream-facemp4.mp4`'s genuine, visually-confirmed head-turn (4e).

**Root cause for the closest near-miss, `full-stream-facemp4.mp4`:**

```bash
awk -F',' 'NR>1 && $1+0>=5.0 && $1+0<=7.5 {print $1","$11","$12","$13}' out/evidence_full-stream-facemp4_distraction_gate.csv
```

`distraction_score` (column 11) is `>=0.70` — `DistractionTriggerEmitter`'s
`enter_threshold` (`services/distraction_trigger_emitter.py:21`) — from
**t=5.65s to t=7.12s inclusive**, a sustained duration of exactly **1.47s**.
`DistractionTriggerEmitter`'s `sustain_seconds=1.5`
(`services/distraction_trigger_emitter.py:21`) requires the score to stay
above threshold for 1.5s before it latches and emits a signal. 1.47s is
**0.03s short** of that bar, so `_above_since` never accumulates enough
duration and `sustained` (line 37) never evaluates `True` — the emitter
correctly declines to fire, exactly as designed. This is a genuine
near-miss on the sustain window for this specific real-world clip's
excursion length, **not a logic bug** in the emitter.

**Conclusion: not a Tasks 1-9 reopen.** The emitter is behaving exactly per
its own hysteresis/sustain contract (the same kind of debounce mandated for
the drowsiness FSM); the corpus simply doesn't contain an off-road episode
that clears 1.5s. Follow-up (deferred, not a blocker): either shorten
`sustain_seconds` for the distraction path, or record/capture a positive
clip whose off-road excursion comfortably exceeds 1.5s, so the production
`distraction_signal` path itself (not just the score/state columns) gets
exercised at least once before relying on it for a live demo.

### Gate 2 summary

| Video / segment | Check type | Result | Path |
|---|---|---|---|
| `distracted.mp4` | positive | does not cross CRITICAL | (b) — real off-road excursion entirely inside the 1.0s calibration window; no second event later in the 3.37s clip |
| `full-stream-2.mp4` STAGE 3 (12-15.9s) | positive | does not cross CRITICAL | (b) — max yaw 28.5° (just under 30° threshold), hands never detected (UNKNOWN throughout); visually confirmed real off-road content via the video's own "STAGE 3: DISTRACTED" caption |
| `full-stream-2.mp4` STAGE 2 (8-11.8s) | negative | PASS | n/a |
| `drowsy.mp4` (whole file) | negative | PASS | n/a |
| `full-stream-facemp4.mp4` (whole file) | negative (as specified) | 45 CRITICAL rows, t=5.65-7.12s | investigated: correct detection of a real, visually-confirmed off-road event in this file, not a bug — brief's "expect none" assumption for this specific file did not hold |
| `normal.mp4` (whole file) | negative | PASS | n/a |

The calibration-window false-positive check (t<1.0s of every video) was
covered by the whole-file scans in 4d/4e/4f and by 4a's own investigation —
no separate dedicated check was needed, per the brief.

---

## Step 5 — Latency

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

Full output (mediapipe/absl/protobuf startup noise omitted):

```
/data/normal.mp4: n=78 p50=50.6ms p95=72.2ms
/data/drowsy.mp4: n=100 p50=63.8ms p95=67.4ms
/data/distracted.mp4: n=102 p50=64.1ms p95=70.5ms
/data/full-stream-2.mp4: n=578 p50=38.0ms p95=55.4ms
/data/full-stream-facemp4.mp4: n=5480 p50=35.2ms p95=50.2ms
COMBINED across 5 videos: n=6338 p50=35.5ms p95=54.2ms p99=66.0ms
```

**Result: latency gate PASSES as-is.** COMBINED p95 = **54.2ms**, roughly
**2.8x under** the 150ms budget (KPI target: p95 ≤ 150ms). Per-video p95
ranges from 50.2ms to 72.2ms — all individually under budget too. No
downscale contingency (main.py's Face/Hand Landmarker shared-frame
downscale path per the CV remediation plan's Decision 5) was triggered, so
no Gate 1 re-check was required. Measured inside the real Docker container
image on this dev host's CPU delegate (no GPU — `GPU support is not
available` in the startup log, falls back to XNNPACK as designed), per the
same requirement `CV_REMEDIATION_RESULTS.md` followed. The per-video p95
values here (~50-72ms) are noticeably higher than
`CV_REMEDIATION_RESULTS.md`'s drowsiness-only figures (~15-17ms) because
this path now runs Hand Landmarker inference in addition to Face
Landmarker on every frame — still comfortably within budget.

---

## Step 6 — Wheel-region values actually used (Task 3)

From `services/hand_tracker.py`:

```python
WHEEL_REGION = {
    "x_min": 0.22,
    "x_max": 0.72,
    "y_min": 0.70,
    "y_max": 1.00,
}
```

Derived empirically against `normal.mp4`'s real per-frame hand bounding
boxes (right hand x=[0.588,0.640] y=[0.775,0.883]; left hand x=[0.298,0.389]
y=[0.819,0.967]), widened by a 0.08 margin in every direction, clamped to
[0,1]. Per its own module docstring, this calibration is tied to
`normal.mp4`'s specific camera framing and has **not** been verified
against the real Container Node/Skycraft camera or against the other 4
videos' framing — consistent with this task's own finding in 4b that
`full-stream-2.mp4`'s STAGE 3 segment never registers a hand detection at
all, which may be a symptom of this same calibration mismatch.

No downscale decision was made (Step 5 latency already passed without
one), so there is no downscale-triggered Gate 1 re-check to report.

---

## Step 7 — Full test suite re-run

```bash
pytest dms-ai-engine -v
```

Result: **83 passed, 1 warning (pre-existing `google.protobuf` deprecation
notice, unrelated to this plan), 0 failed, 0 errors**, in 16.96s. Confirms
nothing regressed across all 17 tasks of this plan.

---

## Overall outcome

| Gate | Result | Detail |
|---|---|---|
| Gate 1 (yaw physical plausibility) | **PASS** (5/5 videos) | 0 jump artifacts anywhere; 3 informational skips across real face-loss gaps; yaw sign/direction cross-check (from Task 2) reconfirmed consistent |
| Gate 2 (behavioral correctness) | **PASS on 4/5 negative checks as specified; 5th investigated and found correct, not a bug; both positive checks take path (b) for concretely diagnosed reasons** | see per-segment table in Step 4 |
| Latency (Step 5) | **PASS** | COMBINED p95 54.2ms ≤ 150ms budget (2.8x margin) |
| Downscale decision | **Not triggered** | latency already well under budget |
| Full test suite (Step 7) | **PASS** | 83/83 passed, 0 regressions |

No code changes were made by this task. Four concrete, non-blocking
follow-up items were identified for future work (none reopen Tasks 1-9):

1. `distracted.mp4`'s only off-road event falls entirely inside the 1.0s
   baseline-calibration window — record a replacement/additional positive
   clip whose head-turn starts after t=1.0s, or reconsider the calibration
   window's length for the distraction path.
2. `full-stream-2.mp4`'s STAGE 3 segment peaks at 28.5° yaw (just under the
   30° threshold) with hands never detected at all — candidate for
   `YAW_OFF_ROAD_THRESHOLD` tuning and/or `WHEEL_REGION`/hand-tracking
   recalibration against this clip's specific framing.
3. `full-stream-facemp4.mp4` is not a pure negative/regression fixture for
   distraction — it contains a genuine, visually-confirmed off-road head
   turn at t≈5.65-7.12s. Anyone reusing this file for a future "expect no
   distraction CRITICAL" regression check should account for this window.
4. The production `distraction_signal` (the column `DistractionTriggerEmitter`
   actually edge-triggers on, and what gates `build_trigger_payload()` in
   `main.py`) is empty across every row of all 5 evidence CSVs — the
   end-to-end distraction alert never fires once in the acceptance corpus.
   Closest near-miss: `full-stream-facemp4.mp4`'s t=5.65-7.12s CRITICAL
   episode sustains `distraction_score>=0.70` for exactly 1.47s, 0.03s short
   of `DistractionTriggerEmitter`'s `sustain_seconds=1.5`. Candidate for
   `sustain_seconds` tuning and/or capturing a positive clip with a
   comfortably-longer off-road excursion so the production signal path gets
   exercised at least once.
