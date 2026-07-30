"""
hand_tracker
------------
Hand-presence signal from MediaPipe's Hand Landmarker Tasks API, used as a
proxy for "hands off wheel" -- MediaPipe has no phone/object detector, so
this tracks hand LANDMARK POSITION relative to a fixed wheel region, not
true phone detection (see design doc's Scope framing).

WHEEL_REGION is a normalized (0-1) bounding box calibrated against the
current stock-footage test clip's camera framing. It was derived empirically
by running MediaPipe's Hand Landmarker against every 15th frame of
`dms-ai-engine/out/normal.mp4` (2.6s clip, both hands on the wheel
throughout) inside the built container image and recording the real
per-frame hand bounding boxes (see this task's commit/report for the full
probe output). The two observed hand clusters were:
  - right hand: x=[0.588,0.640] y=[0.775,0.883]  (min/max across samples)
  - left hand:  x=[0.298,0.389] y=[0.819,0.967]  (min/max across samples)
Combined observed range: x=[0.298,0.640] y=[0.775,0.967]. WHEEL_REGION widens
that by a 0.08 safety margin in every direction (a hand adjusting grip is
still "on the wheel," not just the exact landmark centroid), clamped to
[0, 1]:
  x_min = 0.298 - 0.08 = 0.22 (rounded)
  x_max = 0.640 + 0.08 = 0.72
  y_min = 0.775 - 0.08 = 0.70 (rounded)
  y_max = 0.967 + 0.08 = 1.047 -> clamped to 1.0

It is NOT verified against the real Container Node/Skycraft camera's actual
mounting angle, and may need recalibration before being trusted on that
hardware (design doc Known Limitations).
"""
from typing import List

import mediapipe as mp
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core.base_options import BaseOptions

WHEEL_REGION = {
    "x_min": 0.22,
    "x_max": 0.72,
    "y_min": 0.70,
    "y_max": 1.00,
}


def build_video_mode_hand_landmarker(model_path: str) -> vision.HandLandmarker:
    options = vision.HandLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=model_path, delegate=BaseOptions.Delegate.CPU),
        running_mode=vision.RunningMode.VIDEO,
        num_hands=2,
    )
    return vision.HandLandmarker.create_from_options(options)


def classify_hands_visibility(num_hands_detected: int) -> str:
    if num_hands_detected >= 2:
        return "FULL"
    if num_hands_detected == 1:
        return "PARTIAL"
    return "UNKNOWN"


def _in_wheel_region(hand, region) -> bool:
    cx = sum(lm.x for lm in hand) / len(hand)
    cy = sum(lm.y for lm in hand) / len(hand)
    return region["x_min"] <= cx <= region["x_max"] and region["y_min"] <= cy <= region["y_max"]


def hands_on_wheel(hand_landmark_lists: List[list], region: dict) -> bool:
    """True only when ALL currently-visible hands are inside `region` --
    a single visible hand outside the region (e.g. holding a phone while
    the other hand is out of frame) must classify as False, never
    optimistically True. Call only when hand_landmark_lists is non-empty
    (i.e. hands_visibility != "UNKNOWN") -- the return value is a
    don't-care otherwise."""
    if not hand_landmark_lists:
        return False
    return all(_in_wheel_region(hand, region) for hand in hand_landmark_lists)
