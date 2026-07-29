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
