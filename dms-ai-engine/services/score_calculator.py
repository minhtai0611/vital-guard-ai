"""
DrowsinessScoreCalculator
-------------------------
Tính composite Drowsiness Score [0..1] từ một sliding window các frame feature
(eye_closed, head_pitch_deg). Không phụ thuộc Android/CarSky — test được 100%
offline trên laptop trong lúc chờ mentor trả lời.

Công thức (đúng như runbook mục 07):
    score = 0.55 * perclos_window + 0.25 * eye_closed_now + 0.20 * head_droop_norm
"""
from dataclasses import dataclass
from collections import deque


@dataclass
class FrameFeatures:
    timestamp: float        # giây, mốc thời gian của frame trong video
    eye_closed: bool        # output của BlinkStateTracker (blendshape blink score +
                             # hysteresis, services/eye_state.py) — không phải
                             # MobileNetV3-Small
    head_pitch_deg: float   # góc pitch đầu, dương = đầu gục xuống


class DrowsinessScoreCalculator:
    def __init__(self, window_seconds: float = 20.0, sample_hz: float = 10.0,
                 max_droop_deg: float = 25.0):
        """
        window_seconds: độ dài cửa sổ PERCLOS (mặc định 20s theo runbook).
        sample_hz: tần suất frame được đưa vào (không cần full 30fps gốc,
                   10-15fps là đủ cho PERCLOS/head-pose).
        max_droop_deg: góc gục đầu coi là droop tối đa (chuẩn hoá về 1.0).
        """
        self.window_seconds = window_seconds
        self.max_samples = max(1, int(window_seconds * sample_hz))
        self.window: deque[FrameFeatures] = deque(maxlen=self.max_samples)
        self.max_droop_deg = max_droop_deg
        self.baseline_pitch_deg = 0.0  # có thể calibrate lúc khởi động phiên lái

    def calibrate_baseline(self, pitch_deg: float) -> None:
        """Gọi vài giây đầu khi driver ngồi thẳng, để trừ độ nghiêng ghế/camera."""
        self.baseline_pitch_deg = pitch_deg

    def add_frame(self, frame: FrameFeatures) -> float:
        self.window.append(frame)
        return self.compute_score()

    def compute_score(self) -> float:
        if not self.window:
            return 0.0
        perclos = sum(1 for f in self.window if f.eye_closed) / len(self.window)
        eye_closed_now = 1.0 if self.window[-1].eye_closed else 0.0
        head_droop_norm = self._normalized_head_droop()
        score = 0.55 * perclos + 0.25 * eye_closed_now + 0.20 * head_droop_norm
        return max(0.0, min(1.0, score))

    def _normalized_head_droop(self) -> float:
        latest_pitch = self.window[-1].head_pitch_deg - self.baseline_pitch_deg
        return max(0.0, min(1.0, latest_pitch / self.max_droop_deg))
