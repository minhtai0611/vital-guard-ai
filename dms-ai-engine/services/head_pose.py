"""
head_pose
---------
Head pitch estimation from MediaPipe Face Landmarker's
facial_transformation_matrixes -- a full learned 3D face model fit, not a
from-scratch solvePnP solve, which is why this doesn't suffer the
PnP flip-ambiguity that affected the old 6-point solvePnP approach (see
docs/superpowers/specs/2026-07-28-cv-backend-remediation-design.md).

The pitch axis below was determined empirically against real reference
video (dms-ai-engine/out/drowsy.mp4's visible head-droop segment,
~t=0.6-1.2s), not derived analytically -- Face Landmarker's canonical face
model convention isn't a convention this project chose, unlike the old
6-point model.

Empirical finding: the X component of rotation_matrix_to_euler_deg is
pitch. Evidence from the probe (dms-ai-engine/out/probe_pitch_axis.py, run
against drowsy.mp4 inside the real container):
  - t=0ms   (baseline, upright, frame verified visually):    x=-3.88  y= 1.01  z=-1.50
  - t=600ms (droop window start, eyes closing, slight droop): x=-4.97  y=-0.96  z= 0.68
  - t=1200ms (droop window end, visibly drooped, frame verified): x= 7.14  y=-5.41  z= 5.83
  - t=2200ms (sustained droop, deepest visible droop, frame verified): x=21.82  y=-11.91 z=12.07
x rises monotonically and by far the largest margin (~-4 deg to ~22 deg,
a ~26 deg swing) across the droop window and its sustained continuation,
tracking the visibly-increasing head-down posture confirmed by inspecting
the actual video frames at each of those timestamps. y and z also drift
during the same window (a real driver's head droop is not a pure clean
nod -- some coupling into the other axes is expected) but by a smaller
margin than x, and a combined-motion probe against distracted.mp4 (a
segment with a large yaw sweep from a head turn, y ranging roughly
+23 to -40 deg) showed x staying largely contained within a ~-9 to -3 deg
band -- i.e. pitch (x) is not grossly contaminated by a large yaw
excursion. Sign convention: x increases as the head visibly droops
further down, matching this module's "head down = positive" pitch
convention.

The reverse direction was quantified too: during drowsy.mp4's droop window
(t=0.6-2.2s), yaw (y) stayed within a -11.91 to -0.83 deg band while pitch
(x) swung from -4.97 to 21.82 -- i.e. yaw is not grossly contaminated by a
real head-droop motion either.
"""
import math
from typing import Tuple

import numpy as np


def rotation_matrix_to_euler_deg(matrix: np.ndarray) -> Tuple[float, float, float]:
    """matrix: a 4x4 (or already-3x3) transformation matrix from
    facial_transformation_matrixes. Returns (x, y, z) Euler angles in
    degrees via the standard rotation-matrix decomposition."""
    R = matrix[:3, :3]
    x = math.degrees(math.atan2(R[2, 1], R[2, 2]))
    y = math.degrees(math.atan2(-R[2, 0], math.sqrt(R[2, 1] ** 2 + R[2, 2] ** 2)))
    z = math.degrees(math.atan2(R[1, 0], R[0, 0]))
    return x, y, z


def extract_pitch_deg(transformation_matrix: np.ndarray) -> float:
    x, y, z = rotation_matrix_to_euler_deg(transformation_matrix)
    return x  # empirically determined to be pitch -- see module docstring


def extract_yaw_deg(transformation_matrix: np.ndarray) -> float:
    x, y, z = rotation_matrix_to_euler_deg(transformation_matrix)
    return y  # empirically confirmed to be yaw -- see module docstring
