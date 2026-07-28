"""
DMS service entry point.
Chạy: python3 main.py --mock
Cần: pip install -r requirements.txt

Việc của Phát trong vài ngày đầu (theo đúng mục 07 runbook, "Ngày đầu của AI"):
  [ ] Đọc được 1 video, in FPS/timestamp                      <- khung sẵn dưới đây
  [ ] Overlay mắt/head-pose (hoặc dùng model MediaPipe FaceMesh có sẵn)
  [ ] Xuất CSV: ts, perclos, headPitch, score, state
  [ ] Đảm bảo video "buồn ngủ" phát Trigger đúng 1 lần

Phần eye-state/head-pose thật (MediaPipe FaceMesh) chưa cắm ở đây — để bạn tự
nối vì cần video mẫu thật để tune ngưỡng eye-aspect-ratio/pitch. Khung dưới
chạy được ngay ở chế độ --mock để tự test toàn bộ pipeline JSON/HTTP trước.

Trigger delivery: không còn POST tới --trigger-url nữa — Container Node giờ
serve payload mới nhất qua LatestTriggerStore + trigger_server (local HTTP
network-pin, GET /latest-trigger), App bên Skycraft tự poll (xem
contracts/trigger.schema.json + docs/superpowers/specs/... Decision 3/4).
"""
import argparse
import csv
import time
from pathlib import Path

from score_calculator import DrowsinessScoreCalculator, FrameFeatures
from trigger_emitter import TriggerEmitter, FacePresenceTracker
from trigger_server import LatestTriggerStore, start_background_server

TRIGGER_SCHEMA_VERSION = "1.0"


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

    # --- Nhánh video thật: xem Task 7/8 (MediaPipe FaceMesh EAR + head-pose) ---
    raise NotImplementedError(
        "Nhánh đọc video thật chưa nối eye-state/head-pose model. "
        "Dùng --mock để test toàn bộ pipeline trước; nối MediaPipe ở Task 7/8."
    )


if __name__ == "__main__":
    main()
