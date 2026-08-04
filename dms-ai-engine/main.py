"""
DMS service entry point.
Chạy: python3 main.py --mock
Cần: pip install -r requirements.txt

Việc của Phát trong vài ngày đầu (theo đúng mục 07 runbook, "Ngày đầu của AI"):
  [x] Đọc được 1 video, in FPS/timestamp                      <- run_real_video() dưới đây
  [x] Overlay mắt/head-pose qua MediaPipe Face Landmarker (Tasks API: blendshapes cho
      blink score + facial transformation matrix cho head pitch — không còn là
      FaceMesh/EAR nữa)
  [x] Xuất CSV: ts, perclos, headPitch, score, state
  [ ] Đảm bảo video "buồn ngủ" phát Trigger đúng 1 lần

Phần eye-state/head-pose thật ĐÃ được cắm vào run_real_video() — MediaPipe Face
Landmarker (services/face_landmarker_client.py), blink score + hysteresis
(services/eye_state.py), head pitch từ transformation matrix (services/head_pose.py).
Chế độ --mock vẫn giữ lại để test nhanh toàn bộ pipeline JSON/HTTP mà không cần
video/model thật.

Trigger delivery: không còn POST tới --trigger-url nữa — Container Node giờ
serve payload mới nhất qua LatestTriggerStore + trigger_server (local HTTP
network-pin, GET /latest-trigger), App bên Skycraft tự poll (xem
contracts/trigger.schema.json + docs/superpowers/specs/... Decision 3/4).
"""
import argparse
import csv
import math
import signal
import time
from pathlib import Path

from services.score_calculator import DrowsinessScoreCalculator, FrameFeatures
from services.trigger_emitter import TriggerEmitter, FacePresenceTracker
from services.trigger_server import LatestTriggerStore, start_background_server

TRIGGER_SCHEMA_VERSION = "1.0"

# DrowsinessScoreCalculator.calibrate_baseline() existed and was unit-tested
# in isolation but was never called from run_real_video() -- on any camera
# mount whose "neutral" head pitch isn't ~0deg, head-droop was silently
# clamped to 0 for the entire video, capping the composite score at 0.80
# (0.55 perclos + 0.25 eye_closed_now), one point below the 0.85 CRITICAL
# threshold, regardless of eye closure. Confirmed on a real 3-minute driver
# video whose pitch stayed in [-27.6, -3.8]deg throughout. 1.0s is long
# enough to average out per-frame noise (~30 samples at 30fps) while staying
# short enough not to eat into a short clip's droop-detection window.
BASELINE_CALIBRATION_SECONDS = 1.0

PITCH_OFF_ROAD_THRESHOLD = 20.0
YAW_OFF_ROAD_THRESHOLD = 30.0

# A container's main process runs as PID 1 — Linux does NOT apply the default
# terminate-on-SIGTERM disposition to PID 1 unless a handler is registered.
# Confirmed empirically: without this, `docker stop` had to fall back to
# SIGKILL after the full grace period (exit code 137) instead of an immediate
# clean exit. Both run_mock_stream and run_real_video check this flag every
# iteration so a long-running container stops promptly.
_shutdown_requested = False


def _handle_shutdown_signal(signum, frame):
    global _shutdown_requested
    _shutdown_requested = True


signal.signal(signal.SIGTERM, _handle_shutdown_signal)
signal.signal(signal.SIGINT, _handle_shutdown_signal)


def _state_for_score(score: float, enter_threshold: float = 0.85, exit_threshold: float = 0.50) -> str:
    if score >= enter_threshold:
        return "CRITICAL"
    if score > exit_threshold:
        return "WARNING"
    return "NORMAL"


def build_trigger_payload(state: str, score: float, confidence: float,
                           perclos: float, eye_open_probability: float,
                           head_euler_angle_x: float, reason: str, source: str,
                           event_counter: int, distraction_score: float, distraction_state: str,
                           yaw_deg: float, pitch_deg: float, hands_visibility: str,
                           hands_on_wheel_flag: bool, distraction_reason: str,
                           escalation_level: int, distraction_escalation_level: int) -> dict:
    return {
        "timestampMs": int(time.time() * 1000),
        "source": source,
        "score": round(score, 3),
        "confidence": round(confidence, 3),
        "state": state,
        "escalationLevel": escalation_level,
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
            "escalationLevel": distraction_escalation_level,
            "yawDeg": round(yaw_deg, 3),
            "pitchDeg": round(pitch_deg, 3),
            "handsVisibility": hands_visibility,
            "handsOnWheel": hands_on_wheel_flag,
            "reason": distraction_reason,
        },
    }


def run_mock_stream(out_csv: Path, host: str = "0.0.0.0", port: int = 8765) -> None:
    """Không cần video/model thật — sinh chuỗi eye-state/head-pose giả để tự test
    toàn bộ pipeline (score calc + emitter + CSV + HTTP network-pin) trong lúc
    chờ mentor và trong lúc chưa có model eye-state thật."""
    store = LatestTriggerStore()
    server = start_background_server(store, host=host, port=port)
    try:
        calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
        emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                                  sustain_seconds=2.0, cooldown_seconds=10.0)
        face_tracker = FacePresenceTracker(sustain_seconds=2.0)
        event_counter = 0

        # Kịch bản: 3s tỉnh táo -> 4s buồn ngủ (nhắm mắt + gục đầu) -> 3s tỉnh táo lại
        scenario = (
            [FrameFeatures(0, False, 0)] * 30
            + [FrameFeatures(0, True, 28)] * 40
            + [FrameFeatures(0, False, 0)] * 30
        )

        with open(out_csv, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["ts", "eye_closed_now", "head_pitch", "score", "state", "signal"])
            t = 0.0
            for frame in scenario:
                if _shutdown_requested:
                    break
                frame.timestamp = t
                score = calc.add_frame(frame)
                signal = emitter.update(score, now=t)
                face_signal = face_tracker.update(has_face=True, now=t)  # mock stream: mặt luôn "thấy"

                state = _state_for_score(score)
                if signal in ("CRITICAL", "RECOVERED") or face_signal in ("UNKNOWN", "PRESENT"):
                    event_counter += 1
                    emitted_state = "UNKNOWN" if face_signal == "UNKNOWN" else state
                    reason = "lost_face" if face_signal == "UNKNOWN" else (
                        "sustained_high_score" if signal == "CRITICAL" else "recovered"
                    )
                    payload = build_trigger_payload(
                        state=emitted_state, score=score, confidence=1.0,
                        perclos=calc.compute_score(), eye_open_probability=(0.0 if frame.eye_closed else 1.0),
                        head_euler_angle_x=frame.head_pitch_deg, reason=reason,
                        source="mock-stream", event_counter=event_counter,
                        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                        hands_visibility="UNKNOWN", hands_on_wheel_flag=False,
                        distraction_reason="mock_stream_no_distraction_signal",
                        escalation_level=1, distraction_escalation_level=1,
                    )
                    store.update_latest(payload)

                writer.writerow([f"{t:.1f}", int(frame.eye_closed), frame.head_pitch_deg,
                                  f"{score:.3f}", state, signal or ""])
                t += 0.1
                time.sleep(0.02)

        print(f"Xong. CSV: {out_csv}. Số event đã emit: {event_counter}")
    finally:
        server.shutdown()


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
    from services.escalation_tracker import EscalationTracker

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
    drowsy_escalation = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    distraction_escalation = EscalationTracker(level_up_seconds=[6.0, 12.0], repeat_interval_seconds=[7.0, 5.0, 3.0])
    event_counter = 0
    t = 0.0
    calibration_pitch_samples = []
    baseline_calibrated = False
    fps = cap.get(cv2.CAP_PROP_FPS)
    # `fps or 30.0` only catches falsy values (0/None) — a negative number or
    # NaN is truthy in Python and would silently corrupt the timestamp
    # progression (frame_dt going negative or NaN), breaking TriggerEmitter's
    # sustain-window timing. Validate the value itself, not just its truthiness.
    if not math.isfinite(fps) or fps <= 0:
        fps = 30.0
    frame_dt = 1.0 / fps

    try:
        with open(out_csv, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal",
                              "yaw_deg", "hands_visibility", "hands_on_wheel", "distraction_score",
                              "distraction_state", "distraction_signal", "escalation_level",
                              "distraction_escalation_level"])
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
                    drowsy_escalation.reset()
                    distraction_escalation.reset()
                    event_counter += 1
                    store.update_latest(build_trigger_payload(
                        state="UNKNOWN", score=0.0, confidence=0.0,
                        perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                        reason="lost_face", source="container-python", event_counter=event_counter,
                        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                        distraction_reason="lost_face",
                        escalation_level=1, distraction_escalation_level=1,
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

                    drowsy_level, drowsy_repeat_due, drowsy_level_changed = drowsy_escalation.update(
                        emitter.critical_active, now=t)
                    distraction_level, distraction_repeat_due, distraction_level_changed = distraction_escalation.update(
                        distraction_emitter.critical_active, now=t)

                    if (signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED")
                            or drowsy_repeat_due or distraction_repeat_due
                            or drowsy_level_changed or distraction_level_changed):
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
                            escalation_level=drowsy_level, distraction_escalation_level=distraction_level,
                        ))
                    writer.writerow([f"{t:.2f}", 1, f"{score_blink:.3f}", f"{pitch_deg:.1f}", f"{score:.3f}", state, signal or "",
                                      f"{yaw_deg:.1f}", hands_visibility, int(on_wheel),
                                      f"{distraction_score:.3f}", distraction_state, distraction_signal or "",
                                      drowsy_level, distraction_level])
                else:
                    writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or "",
                                      "", hands_visibility, int(on_wheel), "", "", "",
                                      "", ""])

                t += frame_dt
    finally:
        cap.release()
        # FaceLandmarker owns a native MediaPipe graph/TFLite interpreter/thread
        # pool -- never closing it leaks those resources for the life of the
        # process. Only matters in-process here (run_real_video() is invoked
        # once per container run today), but measure_latency.py calls this same
        # construction once per video in a loop, where the leak is cumulative.
        landmarker.close()
        hand_landmarker.close()
        server.shutdown()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=str, default=None, help="Đường dẫn video driver-facing")
    parser.add_argument("--mock", action="store_true", help="Chạy kịch bản giả, không cần video/model")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Địa chỉ bind server nội bộ room")
    parser.add_argument("--port", type=int, default=8765, help="Cổng server /latest-trigger")
    parser.add_argument("--out-csv", type=str, default="evidence_run.csv")
    args = parser.parse_args()

    if args.mock or not args.video:
        run_mock_stream(Path(args.out_csv), host=args.host, port=args.port)
        return

    run_real_video(args.video, Path(args.out_csv), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
