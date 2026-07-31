"""
Measures end-to-end per-frame latency (frame read -> Face Landmarker
inference -> Hand Landmarker inference -> eye/pose/hand extraction ->
drowsiness score -> distraction score -> emitter/face-tracker updates ->
trigger-store write on a firing frame from either emitter) -- the FULL
per-frame body run_real_video() executes, not a subset. Run inside the real
Container-Node-equivalent Docker container, not just the dev machine (see
design doc Decision 4 -- CPU delegate doesn't cover CPU-architecture
mismatches, and dev-machine timing isn't representative of the real
deployment target regardless).

Usage: python measure_latency.py video1.mp4 video2.mp4 ...
"""
import math
import sys
import time

import cv2
import mediapipe as mp

from services.face_landmarker_client import build_video_mode_landmarker, MonotonicTimestamp
from services.head_pose import extract_pitch_deg, extract_yaw_deg
from services.eye_state import blink_score, BlinkStateTracker
from services.hand_tracker import (
    build_video_mode_hand_landmarker, classify_hands_visibility, hands_on_wheel, WHEEL_REGION,
)
from services.score_calculator import DrowsinessScoreCalculator, FrameFeatures
from services.trigger_emitter import TriggerEmitter, FacePresenceTracker
from services.distraction_score_calculator import (
    is_gaze_off_road, DistractionFrameFeatures, DistractionScoreCalculator,
)
from services.distraction_trigger_emitter import DistractionTriggerEmitter
from services.trigger_server import LatestTriggerStore
from main import (
    build_trigger_payload, _state_for_score,
    BASELINE_CALIBRATION_SECONDS, PITCH_OFF_ROAD_THRESHOLD, YAW_OFF_ROAD_THRESHOLD,
)


def measure(video_path: str, model_path: str,
            hand_model_path: str = "/app/models/hand_landmarker.task") -> list:
    landmarker = build_video_mode_landmarker(model_path)
    hand_landmarker = build_video_mode_hand_landmarker(hand_model_path)
    timestamp_guard = MonotonicTimestamp()
    blink_tracker = BlinkStateTracker()
    calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                              sustain_seconds=2.0, cooldown_seconds=10.0)
    face_tracker = FacePresenceTracker(sustain_seconds=2.0)
    distraction_calc = DistractionScoreCalculator(window_seconds=2.0, sample_hz=10.0)
    distraction_emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                                      sustain_seconds=1.5, cooldown_seconds=5.0)
    store = LatestTriggerStore()
    event_counter = 0
    calibration_pitch_samples = []
    baseline_calibrated = False
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        landmarker.close()
        hand_landmarker.close()
        raise RuntimeError(
            f"Could not open video file: {video_path} "
            "(bad path, or a codec/container OpenCV's build doesn't support)"
        )
    latencies_ms = []
    t = 0.0
    fps = cap.get(cv2.CAP_PROP_FPS)
    # `fps or 30.0` only catches falsy values (0/None) — a negative number or
    # NaN is truthy in Python and would silently corrupt the timestamp
    # progression (frame_dt going negative or NaN), breaking TriggerEmitter's
    # sustain-window timing. Validate the value itself, not just its truthiness.
    if not math.isfinite(fps) or fps <= 0:
        fps = 30.0
    frame_dt = 1.0 / fps

    try:
        while cap.isOpened():
            start = time.perf_counter()
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
                    reason="lost_face", source="latency-check", event_counter=event_counter,
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
                    # Do NOT feed distraction_calc during the ~1s calibration
                    # window -- see main.py's run_real_video() for the full
                    # rationale (same false-ceiling bug class as drowsiness's
                    # own score). Skip scoring entirely rather than score on a
                    # known-wrong baseline.
                    head_off_road = abs(baseline_corrected_pitch) > PITCH_OFF_ROAD_THRESHOLD or abs(yaw_deg) > YAW_OFF_ROAD_THRESHOLD
                    gaze_off_road = is_gaze_off_road(head_off_road, eye_closed)
                    distraction_score = distraction_calc.add_frame(DistractionFrameFeatures(
                        timestamp=t, gaze_off_road=gaze_off_road,
                        hands_visibility=hands_visibility, hands_on_wheel=on_wheel,
                    ))
                    distraction_signal = distraction_emitter.update(distraction_score, now=t)
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
                        source="latency-check", event_counter=event_counter,
                        distraction_score=distraction_score, distraction_state=distraction_state,
                        yaw_deg=yaw_deg, pitch_deg=baseline_corrected_pitch,
                        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                        distraction_reason=("gaze_off_road_or_hands_off_wheel" if distraction_signal == "CRITICAL"
                                             else "recovered" if distraction_signal == "RECOVERED" else "unchanged"),
                    ))
            latencies_ms.append((time.perf_counter() - start) * 1000.0)
            t += frame_dt
    finally:
        cap.release()
        # measure() is called once per video from the CLI loop below -- without
        # this, a multi-video run leaks one FaceLandmarker/HandLandmarker
        # (native MediaPipe graph/TFLite interpreter/thread pool) per video
        # for the rest of the process's lifetime.
        landmarker.close()
        hand_landmarker.close()

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
