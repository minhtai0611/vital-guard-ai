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
                           event_counter: int) -> dict:
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
        landmarker.close()
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
        # FaceLandmarker owns a native MediaPipe graph/TFLite interpreter/thread
        # pool -- never closing it leaks those resources for the life of the
        # process. Only matters in-process here (run_real_video() is invoked
        # once per container run today), but measure_latency.py calls this same
        # construction once per video in a loop, where the leak is cumulative.
        landmarker.close()
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
