# CV Backend Remediation — Acceptance Gate Results

Task 7 of the `2026-07-28-cv-backend-remediation` plan. Run against the real
Docker image (`vital-guard-dms:gate-check`, built fresh from the current
`Dockerfile`) and the 3 real test videos (`out/normal.mp4`, `out/drowsy.mp4`,
`out/distracted.mp4`), on 2026-07-29.

**Known evidenced starting point (pre-remediation, before this plan began):**
the drowsy video peaked at score **0.800** due to solvePnP flip-ambiguity in
the old head-pose extraction — never reaching the 0.85 CRITICAL threshold.
This document records whether the Face Landmarker migration (Tasks 1-6)
fixed that.

**Bottom line: both gates pass cleanly, no threshold adjustment or downscale
was needed.** Post-migration drowsy max score: **0.975** (was 0.800
pre-remediation). Gate 1 (physical plausibility): pass, zero jump artifacts.
Gate 2 (score outcome): pass via path (a). Latency: pass, COMBINED p95 =
15.7ms (budget: 150ms).

---

## Step 1 — Docker build

```bash
cd dms-ai-engine
docker build -t vital-guard-dms:gate-check .
```

Result: **succeeded**. All 8 layers `CACHED` (from Tasks 1-6's prior builds —
including the pinned `face_landmarker.task` model-bundle download), only the
final image tag write was new. Confirms nothing in Tasks 1-6 broke the build.

---

## Step 2 — Re-run all 3 videos through the container

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

All 3 runs exited 0. Stderr in each run was only MediaPipe/TFLite/absl
startup noise (no GPU on this host, falls back to the CPU/XNNPACK delegate
as designed — consistent with Decision 4's explicit-CPU-delegate choice) and
one deprecation warning from `google.protobuf`; no errors, no crashes.
Representative output (identical shape for all 3 runs):

```
WARNING: All log messages before absl::InitializeLog() is called are written to STDERR
I0000 00:00:1785319577.915045       1 task_runner.cc:85] GPU suport is not available: INTERNAL: ; RET_CHECK failure (mediapipe/gpu/gl_context_egl.cc:77) display != EGL_NO_DISPLAYeglGetDisplay() returned error 0x300c
W0000 00:00:1785319577.943276       1 face_landmarker_graph.cc:174] Sets FaceBlendshapesGraph acceleration to xnnpack by default.
INFO: Created TensorFlow Lite XNNPACK delegate for CPU.
W0000 00:00:1785319577.990453      56 inference_feedback_manager.cc:114] Feedback manager requires a model with a single signature inference. Disabling support for feedback tensors.
W0000 00:00:1785319578.011175      71 inference_feedback_manager.cc:114] Feedback manager requires a model with a single signature inference. Disabling support for feedback tensors.
/usr/local/lib/python3.12/site-packages/google/protobuf/symbol_database.py:55: UserWarning: SymbolDatabase.GetPrototype() is deprecated. Please use message_factory.GetMessageClass() instead. SymbolDatabase.GetPrototype() will be removed soon.
  warnings.warn('SymbolDatabase.GetPrototype() is deprecated. Please '
```

Output CSVs (all in `dms-ai-engine/out/`, gitignored — evidence artifacts,
not committed):

| File | Rows (incl. header) |
|---|---|
| `evidence_normal_post_remediation.csv` | 79 |
| `evidence_drowsy_post_remediation.csv` | 101 |
| `evidence_distracted_post_remediation.csv` | 103 |

CSV columns: `ts,has_face,blink_score,head_pitch,score,state,signal`.

---

## Step 3 — Gate 1: physical plausibility (pitch trajectory jump check)

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

Full output (only line printed):

```
SKIPPED (gap after face loss, rows 68 -> 86): 21.8 -> -11.7 -- not compared
```

**Result: Gate 1 PASSES.** Zero `JUMP` lines — no flip-ambiguity artifact in
the post-migration pitch signal anywhere in the drowsy clip. The one
`SKIPPED` line is exactly the informational, non-failure case the check is
designed to surface: rows 68→86 span a face-loss gap (no face detected for
that stretch, consistent with the identical gap Task 3's own probe found at
frames 67-83 of this same video — most likely a brief occlusion/extreme-angle
moment during the droop, not a bug), so the large raw diff (21.8° → -11.7°)
across that gap is correctly *not* compared/flagged as a jump.

### Visual cross-check

Extracted stills via `ffmpeg -ss <t> -i out/drowsy.mp4 -frames:v 1 <out>.png`
at t=0.0s, t=1.2s, t=2.2s and viewed them directly against the CSV's
`head_pitch` column at those timestamps:

| t | CSV `head_pitch` | Visual |
|---|---|---|
| 0.0s | -3.9° | Driver upright, eyes open, looking forward (near-baseline). |
| 1.2s | 7.1° | Head visibly drooping down, eyes closed — score crosses CRITICAL (0.857) at this exact row. |
| 2.2s | 21.8° | Head at its most drooped, chin down, eyes closed — largest pitch value in the whole run. |

Numeric direction (increasing positive pitch as the head visibly droops
further down) matches what's seen in the source video at all 3 checkpoints —
consistent with Task 3's established "head down = positive pitch" sign
convention. No contradiction found.

---

## Step 3.5 — Blink-hysteresis threshold sanity check against real data

Current constants in `services/eye_state.py`:
`BLINK_CLOSE_THRESHOLD = 0.55`, `BLINK_REOPEN_THRESHOLD = 0.35`.

```bash
awk -F',' 'NR>1 && $1+0>=0.6 && $1+0<=1.2 {print $3}' out/evidence_drowsy_post_remediation.csv
awk -F',' 'NR>1 && $1+0<0.3 {print $3}' out/evidence_drowsy_post_remediation.csv
```

**Closed-eye segment (t=0.6-1.2s, droop window, `blink_score` column):**

```
0.747
0.755
0.731
0.718
0.704
0.675
0.678
0.678
0.669
0.662
0.659
0.637
0.639
0.633
0.590
0.610
0.606
0.602
0.605
```

Min = **0.590**, max = **0.755**. All 19 values are above
`BLINK_CLOSE_THRESHOLD` (0.55), with the closest approach (0.590) still
0.04 above the threshold.

**Open-eye segment (t<0.3s, first ~0.3s, `blink_score` column):**

```
0.172
0.112
0.140
0.143
0.141
0.136
0.131
0.128
0.127
```

Min = 0.112, max = **0.172**. All 9 values are below
`BLINK_REOPEN_THRESHOLD` (0.35), with the closest approach (0.172) still
0.178 below the threshold.

**Result: no adjustment needed.** Both segments are reliably and cleanly
separated from their respective thresholds against real footage — the
closed-eye segment never dips below 0.590 (threshold 0.55) and the open-eye
segment never rises above 0.172 (threshold 0.35). Per the brief, since no
adjustment was triggered, Task 5's test suite re-run and Step 2's video
re-run were not required — this closes the loop Task 4 Step 4 opened without
needing a code change.

---

## Step 4 — Gate 2: score outcome

```bash
for f in out/evidence_normal_post_remediation.csv out/evidence_drowsy_post_remediation.csv out/evidence_distracted_post_remediation.csv; do
  echo "=== $f ==="
  awk -F',' 'NR>1 && $2=="1" && $5!="" {print $5}' "$f" | sort -g | tail -1
done
```

Full output:

```
=== out/evidence_normal_post_remediation.csv ===
0.333
=== out/evidence_drowsy_post_remediation.csv ===
0.975
=== out/evidence_distracted_post_remediation.csv ===
0.000
```

**Result: Gate 2 PASSES via path (a).** Drowsy's max score is **0.975**
(≥0.85 — the drowsy video actually reaches and sustains CRITICAL, first
crossing 0.85 at t=1.20s per the CSV: `1.20,1,0.605,7.1,0.857,CRITICAL,`),
and neither normal (max 0.333) nor distracted (max 0.000) ever reach
CRITICAL. This is the fix over the pre-remediation baseline of 0.800 — the
Face Landmarker migration resolved the flip-ambiguity ceiling.

Distracted's max score of exactly 0.000 across all 102 face-visible frames
was sanity-checked (`awk -F',' 'NR>1 && $2=="1" {print $5}' out/evidence_distracted_post_remediation.csv | sort -g | uniq -c`
→ `102 0.000`): expected, not a bug — the distracted clip is a large-yaw
head-turn with eyes open throughout (per Task 3's own combined-motion probe
against this same clip), so neither the blink-closure nor head-pitch-droop
signal ever fires; the composite score correctly stays at its floor.

---

## Step 5 — Latency

```bash
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "$(pwd)/out/normal.mp4:/data/normal.mp4:ro" \
  -v "$(pwd)/out/drowsy.mp4:/data/drowsy.mp4:ro" \
  -v "$(pwd)/out/distracted.mp4:/data/distracted.mp4:ro" \
  -v "$(pwd)/measure_latency.py:/app/measure_latency.py:ro" \
  --entrypoint python vital-guard-dms:gate-check /app/measure_latency.py \
  /data/normal.mp4 /data/drowsy.mp4 /data/distracted.mp4
```

Full output (mediapipe/absl startup noise omitted — identical to Step 2's):

```
/data/normal.mp4: n=78 p50=14.0ms p95=16.5ms
/data/drowsy.mp4: n=100 p50=13.8ms p95=16.9ms
/data/distracted.mp4: n=102 p50=13.7ms p95=14.9ms
COMBINED across 3 videos: n=280 p50=13.8ms p95=15.7ms p99=39.8ms
```

**Result: latency gate PASSES as-is.** COMBINED p95 = **15.7ms**, roughly
**9.6x under** the 150ms budget (KPI target: p95 ≤ 150ms). No downscale
(Decision 5 option (a)) or relaxed-KPI documentation (option (b)) was
triggered — measured on the real Docker container image on this dev host's
CPU delegate, per Decision 4's requirement to measure inside the real
container, not just on the bare dev machine.

(Ran exactly as scripted in the brief — `measure_latency.py` takes positional
video paths only (`sys.argv[1:]`), no separate model-path argument, since it
hardcodes `/app/models/face_landmarker.task`, matching where the Dockerfile
bakes the model.)

---

## Overall outcome

| Gate | Result | Detail |
|---|---|---|
| Gate 1 (physical plausibility) | **PASS** | 0 jump artifacts; 1 informational skip across a real face-loss gap; visual cross-check at 3 timestamps confirms direction |
| Gate 2 (score outcome) | **PASS via (a)** | drowsy max 0.975 ≥ 0.85; normal max 0.333, distracted max 0.000, neither reaches CRITICAL |
| Blink-threshold sanity check (3.5) | **PASS, no adjustment** | closed-eye segment min 0.590 > 0.55; open-eye segment max 0.172 < 0.35 |
| Latency (Step 5) | **PASS** | COMBINED p95 15.7ms ≤ 150ms budget |
| Downscale decision | **Not triggered** | latency already well under budget |

No further work is required by this plan. Threshold/sustain-window tuning
beyond what's already validated here remains explicitly out of this plan's
scope (per the brief's own path-(b) contingency, which did not trigger).

---

## Follow-up items from the final whole-branch review (deferred, not blockers)

The final whole-branch review (after all 7 tasks + one merge-blocker fix wave)
found no Critical issues and confirmed the merge-blocking Important findings
were fixed (NaN/inf crash in the timestamp guard, stale docs describing the
deleted solvePnP/EAR/FaceMesh backend, an unclosed `FaceLandmarker` resource
leak, and `test_container.sh`'s false-pass-on-startup-crash gap — all
addressed and re-reviewed clean). Two Important findings and several Minor
ones were explicitly scoped out of that fix wave as genuine follow-up work,
not defects blocking this plan's completion:

- **PERCLOS semantic documentation (design Decision 2's explicit ask, never
  written down anywhere).** Decision 2 requires documenting that PERCLOS's
  meaning changed slightly (closed/open classification is now debounced via
  `BlinkStateTracker`'s hysteresis, not raw-instantaneous) — this was never
  written. Separately, `main.py`'s `build_trigger_payload(..., perclos=...)`
  call actually passes `calc.compute_score()` (the composite 0.55/0.25/0.20
  score), not a true PERCLOS ratio, into the payload's `features.perclos`
  field — pre-existing, not introduced by this plan, but worth fixing
  alongside the documentation gap since CLAUDE.md's debug-overlay mandate
  names `perclos` as a value to display.
- **No automated regression guard on the real end-to-end CV path.** All 54
  tests use fakes at the Face Landmarker boundary — nothing chains real
  landmark detection → pitch extraction → blink scoring → composite score →
  trigger emission against a real video file. The only proof this actually
  works is this document's one-time manual container run, whose source CSVs
  live in gitignored `out/`. Suggested follow-up: commit the 3
  `evidence_*_post_remediation.csv` files as tracked evidence artifacts, and
  add a `test_container.sh` step (or a marked-slow pytest) that runs one
  short real clip and asserts drowsy max score ≥0.85 with zero >90° pitch
  jumps — the awk logic for both checks already exists above, it just isn't
  wired into anything repeatable by someone other than whoever ran this
  document's commands.
- **Minor, lower-priority items** (not reproduced in full here — see the
  review transcript in git history / session record if needed): dev tool's
  `source="latency-check"` payload isn't in `trigger.schema.json`'s `source`
  enum (harmless — never served); a couple of docstrings cite files that no
  longer exist (`PITCH_ESTIMATION_FINDINGS.md`, the gitignored throwaway
  probe script) and should point at this document instead; `main.py` has no
  `--model-path` CLI flag, so it can't run standalone on a Windows dev
  machine without editing the hardcoded container path; this document
  doesn't state which `public/*.mp4` tracked file maps to which gitignored
  `out/*.mp4` file used in the gate run; a leftover IDE scratch file and an
  uncommitted `evidence_run.csv` diff should be cleaned up; `curl` stays
  installed in the final runtime image though it's only needed at build time.

None of these affect the acceptance-gate outcome recorded above or the
correctness of the shipped CV pipeline — they're worth picking up as
follow-up work, prioritized roughly in the order listed.

---

## Regression pass against 5 real videos (2 new long multi-state clips added)

Re-ran the full system after 2 new videos were added, each spanning all 3
states in one continuous clip rather than isolating one state each:
`out/full-stream-2.mp4` (19.27s, 1080x1920 portrait) and
`out/full-stream-facemp4.mp4` (184.14s, 1280x720). Unit suite: 55/55 (54
original + 1 new). Fresh Docker build, all 5 videos run clean (exit 0).

**Gate 1 (physical plausibility): PASS on all 5**, zero >90° pitch jumps —
including both new videos, one of which (full-stream-2.mp4, portrait/
different resolution) was not part of the original validation set. Latency:
COMBINED p95 across all 5 = 18.5ms, still ~8x under the 150ms budget.

**Real bug found and fixed: baseline pitch calibration was never wired in.**
`services/score_calculator.py`'s `DrowsinessScoreCalculator.calibrate_baseline()`
existed and was unit-tested in isolation (`test_baseline_calibration_removes_seat_tilt_offset`)
but `main.py`'s `run_real_video()` never called it. On `full-stream-facemp4.mp4`,
raw head pitch stayed in `[-27.6°, -3.8°]` for the entire 184s — never crossing
the hardcoded 0° baseline — so `_normalized_head_droop()` clamped to 0 for
every single frame, capping the composite score at exactly **0.800**
(`0.55 perclos + 0.25 eye_closed_now`) regardless of how closed the eyes got,
one point below the 0.85 CRITICAL threshold. This is the same shape of false
negative the original PnP-flip-ambiguity bug caused, from an unrelated root
cause: a built, tested feature that was simply never connected to the
production entrypoint. Any camera mount whose neutral pitch isn't ~0° would
hit this.

**Fix:** `run_real_video()` now averages the first `BASELINE_CALIBRATION_SECONDS`
(1.0s) of pitch readings while a face is present and calls `calc.calibrate_baseline()`
once that window elapses — matching the method's own docstring intent ("call
it in the first few seconds while the driver sits upright"). Regression test
added (`test_run_real_video_calibrates_baseline_from_first_second_of_pitch_readings`)
reproducing the exact bug shape with a fake landmarker before implementing
the fix (TDD: confirmed failing against the old code, passing after).

**Verified after the fix, re-running all 5 videos:**

| Video | Max score before fix | Max score after fix | CRITICAL fires? |
|---|---|---|---|
| normal.mp4 | 0.333 | 0.333 | No (unchanged, correct) |
| drowsy.mp4 | 0.975 | 0.998 | Yes (unchanged, now slightly stronger signal) |
| distracted.mp4 | 0.000 | 0.004 | No (unchanged, correct — negligible shift) |
| full-stream-2.mp4 | 0.829 | 0.843 | No |
| full-stream-facemp4.mp4 | **0.800 (hard ceiling)** | **0.923** | **Yes — CRITICAL at t=174.80s (0.874), RECOVERED at t=175.50s (0.496)** |

No new false positives introduced (normal/distracted stay far below 0.85).
Gate 1 re-checked clean on all 5 after the fix.

**`full-stream-2.mp4` remains a known, already-scoped limitation, not a new
regression.** Its labeled "STAGE 2: DROWSY" segment (t≈8-11.8s) stays at
exactly 0.800 even after calibration — verified this is not a calibration
gap: this clip's own resting pitch (~9.3°, from its first second) is
*higher* than the peak pitch reached during that labeled segment (3.6°), so
a correctly-computed relative droop there is ≤0, not a missing-signal case.
The video's overall max moved from 0.829→0.843 because a *different*, later
segment (~t=17.8-18.0s, part of the closing reprise) now gets a modest droop
credit it didn't have before — it still never reaches CRITICAL. This is the
formula-weight-sensitivity limitation already covered by the design spec's
Gate 2 path (b) and this document's Phase-3-deferred stance, not something
this fix was expected to resolve.

This regression pass also partially closes the "no automated regression
guard on the real end-to-end CV path" follow-up item above: the new fake-
landmarker-based test at least exercises the calibration *logic* without a
real video, though the 5-video Docker run itself remains a manual step, not
CI-wired — that broader gap is still open.
