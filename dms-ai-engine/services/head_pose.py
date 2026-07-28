"""
head_pose
---------
Head pitch estimation via OpenCV solvePnP against a canonical 3D face model,
using 6 MediaPipe FaceMesh landmark correspondences — a standard, well-documented
technique (not a heuristic guess). Positive pitch = head drooping forward/down.
"""
import math
from typing import List, Tuple

import cv2
import numpy as np

# MediaPipe FaceMesh indices for: nose tip, chin, right eye outer corner,
# left eye outer corner, mouth right corner, mouth left corner.
MODEL_LANDMARK_INDICES = [1, 152, 33, 263, 61, 291]

# Canonical 3D face model (millimeters, arbitrary but internally consistent
# scale — solvePnP only needs consistent relative geometry, not real-world
# units, since we only read the rotation, not the translation).
MODEL_3D_POINTS = np.array([
    (0.0, 0.0, 0.0),        # nose tip
    (0.0, -63.6, -12.5),    # chin
    (-43.3, 32.7, -26.0),   # right eye outer corner
    (43.3, 32.7, -26.0),    # left eye outer corner
    (-28.9, -28.9, -24.1),  # mouth right corner
    (28.9, -28.9, -24.1),   # mouth left corner
], dtype=np.float64)


def estimate_pitch_deg(landmarks: List[Tuple[float, float]], frame_width: int, frame_height: int) -> float:
    """Estimate head pitch in degrees from 2D landmarks via solvePnP.

    NOTE: the camera intrinsics below (focal_length = frame_width, principal
    point at the frame center, zero lens distortion) are a standard pinhole
    approximation, not a real per-device camera calibration. This is a
    reasonable engineering default for this use case, but the absolute pitch
    value should be treated as approximate, not metrologically validated,
    until/unless calibrated against the actual deployment camera.

    NOTE: the Euler extraction below (`atan2(R[2,1], R[2,2])`) is deliberately
    sensitive to rotation about the model's local X axis (MODEL_3D_POINTS'
    ear-to-ear / interaural axis, since the eye corners sit at X=-43.3/+43.3)
    -- the axis a physical head nod actually rotates about -- and is blind to
    rotation about the local Y axis (vertical, turning the head left/right,
    i.e. yaw). An earlier version of this function used
    `atan2(-R[2,0], sqrt(R[0,0]**2 + R[1,0]**2))`, which is the mirror image:
    sensitive to Y (yaw), blind to X (pitch) -- i.e. it was silently returning
    yaw instead of pitch. See test_head_pose.py's
    test_pitch_is_insensitive_to_pure_yaw_rotation for the regression guard.
    """
    image_points = np.array(
        [landmarks[i] for i in MODEL_LANDMARK_INDICES], dtype=np.float64
    )
    focal_length = frame_width
    center = (frame_width / 2, frame_height / 2)
    camera_matrix = np.array([
        [focal_length, 0, center[0]],
        [0, focal_length, center[1]],
        [0, 0, 1],
    ], dtype=np.float64)
    dist_coeffs = np.zeros((4, 1))

    success, rotation_vec, _translation_vec = cv2.solvePnP(
        MODEL_3D_POINTS, image_points, camera_matrix, dist_coeffs,
        flags=cv2.SOLVEPNP_ITERATIVE,
    )
    if not success:
        return 0.0

    rotation_matrix, _ = cv2.Rodrigues(rotation_vec)
    pitch_rad = math.atan2(rotation_matrix[2, 1], rotation_matrix[2, 2])
    return math.degrees(pitch_rad)
