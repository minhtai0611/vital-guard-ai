"""
eye_state
---------
Real eye-aspect-ratio (EAR) computation from MediaPipe FaceMesh landmarks —
classic Soukupová & Čech (2016) formula. Pure geometry, no MediaPipe/OpenCV
import here, so it's testable with plain synthetic coordinates.

MediaPipe FaceMesh landmark indices used per eye (6-point EAR layout):
  Left eye  (viewer's left  / subject's right): [362, 385, 387, 263, 373, 380]
  Right eye (viewer's right / subject's left):  [33,  160, 158, 133, 153, 144]
Order for each list: [outer_corner, top_1, top_2, inner_corner, bottom_2, bottom_1]
"""
import math
from typing import List, Tuple

LEFT_EYE_INDICES = [362, 385, 387, 263, 373, 380]
RIGHT_EYE_INDICES = [33, 160, 158, 133, 153, 144]

EAR_CLOSED_THRESHOLD = 0.18
EAR_OPEN_THRESHOLD = 0.28


def _dist(a: Tuple[float, float], b: Tuple[float, float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def compute_ear(points: List[Tuple[float, float]]) -> float:
    """points: [outer_corner, top_1, top_2, inner_corner, bottom_2, bottom_1]."""
    if len(points) != 6:
        raise ValueError(f"compute_ear cần đúng 6 điểm, nhận được {len(points)}")
    outer, top1, top2, inner, bottom2, bottom1 = points
    vertical = _dist(top1, bottom1) + _dist(top2, bottom2)
    horizontal = 2.0 * _dist(outer, inner)
    if horizontal == 0:
        return 0.0
    return vertical / horizontal


def eye_open_probability(ear: float) -> float:
    """Ánh xạ tuyến tính EAR -> [0,1], kẹp ở 2 đầu ngưỡng đã calibrate."""
    span = EAR_OPEN_THRESHOLD - EAR_CLOSED_THRESHOLD
    normalized = (ear - EAR_CLOSED_THRESHOLD) / span
    return max(0.0, min(1.0, normalized))


def average_ear(landmarks: List[Tuple[float, float]]) -> float:
    """landmarks: full 468-point MediaPipe FaceMesh list (x, y) in normalized
    image coordinates; averages both eyes' EAR."""
    left = [landmarks[i] for i in LEFT_EYE_INDICES]
    right = [landmarks[i] for i in RIGHT_EYE_INDICES]
    return (compute_ear(left) + compute_ear(right)) / 2.0
