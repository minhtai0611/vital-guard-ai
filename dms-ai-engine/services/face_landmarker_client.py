"""
face_landmarker_client
-----------------------
Shared FaceLandmarker construction (explicit CPU delegate, VIDEO running
mode -- both required, never left to a default: see design doc Decisions
1/4) and a monotonic-timestamp guard for CAP_PROP_POS_MSEC, which can
occasionally be non-increasing between reads for some codecs/containers --
VIDEO mode throws immediately (not a warning) on a non-monotonic timestamp.
"""
from typing import Optional

from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core.base_options import BaseOptions


def build_video_mode_landmarker(model_path: str) -> vision.FaceLandmarker:
    options = vision.FaceLandmarkerOptions(
        base_options=BaseOptions(
            model_asset_path=model_path,
            delegate=BaseOptions.Delegate.CPU,
        ),
        running_mode=vision.RunningMode.VIDEO,
        num_faces=1,
        output_face_blendshapes=True,
        output_facial_transformation_matrixes=True,
        min_face_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    return vision.FaceLandmarker.create_from_options(options)


class MonotonicTimestamp:
    def __init__(self):
        self._last: Optional[int] = None

    def next(self, raw_ms: float) -> int:
        candidate = int(raw_ms)
        if self._last is not None and candidate <= self._last:
            candidate = self._last + 1
        self._last = candidate
        return candidate
