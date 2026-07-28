"""
DMS service entry point.
Chạy: python3 main.py --video path/to/driver_sample.mp4 --trigger-url http://localhost:8765/trigger
Cần: pip install opencv-python mediapipe requests --break-system-packages

Việc của Phát trong vài ngày đầu (theo đúng mục 07 runbook, "Ngày đầu của AI"):
  [ ] Đọc được 1 video, in FPS/timestamp                      <- khung sẵn dưới đây
  [ ] Overlay mắt/head-pose (hoặc dùng model MediaPipe FaceMesh có sẵn)
  [ ] Xuất CSV: ts, perclos, headPitch, score, state
  [ ] Đảm bảo video "buồn ngủ" phát Trigger đúng 1 lần

Phần eye-state/head-pose thật (MediaPipe FaceMesh) chưa cắm ở đây — để bạn tự
nối vì cần video mẫu thật để tune ngưỡng eye-aspect-ratio/pitch. Khung dưới
chạy được ngay ở chế độ --mock để tự test toàn bộ pipeline JSON/HTTP trước.
"""
import argparse
import csv
import json
import time
import sys
from pathlib import Path

from score_calculator import DrowsinessScoreCalculator, FrameFeatures
from trigger_emitter import TriggerEmitter

TRIGGER_SCHEMA_VERSION = "1.0"


def build_trigger_payload(event_id: str, score: float, sustained_ms: int, source: str) -> dict:
    return {
        "version": TRIGGER_SCHEMA_VERSION,
        "eventId": event_id,
        "type": "DROWSINESS_TRIGGER",
        "score": round(score, 3),
        "sustainedMs": sustained_ms,
        "source": source,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }


def emit_trigger(payload: dict, trigger_url: str | None) -> None:
    print(f"[TRIGGER] {json.dumps(payload, ensure_ascii=False)}")
    if trigger_url:
        import requests  # import trễ để --mock không cần cài requests
        try:
            requests.post(trigger_url, json=payload, timeout=2)
        except Exception as e:
            print(f"[WARN] Không gửi được trigger tới {trigger_url}: {e}", file=sys.stderr)


def run_mock_stream(out_csv: Path, trigger_url: str | None) -> None:
    """Không cần video/model thật — sinh chuỗi eye-state/head-pose giả để tự test
    toàn bộ pipeline (score calc + emitter + CSV + HTTP) trong lúc chờ mentor
    và trong lúc chưa có model eye-state thật."""
    calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                              sustain_seconds=2.0, cooldown_seconds=10.0)
    event_counter = 0

    # Kịch bản: 3s tỉnh táo -> 4s buồn ngủ (nhắm mắt + gục đầu) -> 3s tỉnh táo lại
    scenario = (
        [FrameFeatures(0, False, 0)] * 30
        + [FrameFeatures(0, True, 28)] * 40
        + [FrameFeatures(0, False, 0)] * 30
    )

    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["ts", "perclos_window", "eye_closed_now", "head_pitch", "score", "trigger_fired"])
        t = 0.0
        for frame in scenario:
            frame.timestamp = t
            score = calc.add_frame(frame)
            fired = emitter.update(score, now=t)
            if fired:
                event_counter += 1
                payload = build_trigger_payload(
                    event_id=f"vg-mock-{event_counter:03d}",
                    score=score, sustained_ms=2000, source="mock-stream",
                )
                emit_trigger(payload, trigger_url)
            writer.writerow([f"{t:.1f}", "", int(frame.eye_closed), frame.head_pitch_deg,
                              f"{score:.3f}", int(fired)])
            t += 0.1
            time.sleep(0.02)  # giả lập realtime nhẹ, bỏ nếu muốn chạy hết tốc lực

    print(f"Xong. CSV: {out_csv}. Số trigger đã phát: {event_counter}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=str, default=None, help="Đường dẫn video driver-facing")
    parser.add_argument("--mock", action="store_true", help="Chạy kịch bản giả, không cần video/model")
    parser.add_argument("--trigger-url", type=str, default=None,
                         help="POST JSON trigger tới đây, vd http://localhost:8765/trigger")
    parser.add_argument("--out-csv", type=str, default="evidence_run.csv")
    args = parser.parse_args()

    if args.mock or not args.video:
        run_mock_stream(Path(args.out_csv), args.trigger_url)
        return

    # --- Nhánh video thật: cắm MediaPipe FaceMesh + eye-aspect-ratio ở đây ---
    raise NotImplementedError(
        "Nhánh đọc video thật chưa nối eye-state/head-pose model. "
        "Dùng --mock để test toàn bộ pipeline trước; nối OpenCV/MediaPipe khi có video mẫu."
    )


if __name__ == "__main__":
    main()
