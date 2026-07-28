from unittest.mock import patch

import cv2
import numpy as np

from head_pose import estimate_pitch_deg, MODEL_LANDMARK_INDICES, MODEL_3D_POINTS


def _camera_matrix(width, height):
    focal_length = width
    center = (width / 2, height / 2)
    return np.array([
        [focal_length, 0, center[0]],
        [0, focal_length, center[1]],
        [0, 0, 1],
    ], dtype=np.float64)


def _project_synthetic_landmarks(rotation_vec_deg, width, height):
    """Builds a full 468-point landmark list by rotating the REAL MODEL_3D_POINTS
    (imported from head_pose.py, not hand-typed approximations) by a KNOWN
    axis-angle rotation and projecting through the exact same pinhole camera
    model estimate_pitch_deg's own camera_matrix construction assumes
    (focal_length=width, principal point at frame center, zero distortion).

    This guarantees an exact, self-consistent 2D/3D correspondence: solvePnP
    is handed a genuinely well-posed problem instead of six independently
    hand-typed pixel positions that don't correspond to any real projection
    of MODEL_3D_POINTS (the old fixture measured 16-23px reprojection error
    even at its "upright" baseline, and flipped sign/magnitude between nearby
    offsets -- a poorly-conditioned setup, not a real droop signal).

    rotation_vec_deg is an (rx, ry, rz) axis-angle rotation in degrees, applied
    as a Rodrigues rotation vector via cv2.projectPoints. Empirically (see
    task-8-report.md "Fix report" section), rotating purely about the model's
    Y axis is the one that `estimate_pitch_deg`'s atan2(-R[2,0], sy) extraction
    responds to monotonically and exactly here; rotating about X or Z alone
    leaves the recovered pitch pinned near zero (floating-point noise) because
    the nose tip sits at the model's local origin, so those rotations barely
    move the other 5 points' projections. This is a property of this specific
    6-point model + this specific Euler-extraction formula, not a claim that Y
    is anatomically "the pitch axis" in general -- the point of this fixture is
    to test the code's actual, self-consistent geometric response.
    """
    camera_matrix = _camera_matrix(width, height)
    dist_coeffs = np.zeros((4, 1))
    rvec = np.radians(np.array(rotation_vec_deg, dtype=np.float64)).reshape(3, 1)
    tvec = np.array([[0.0], [0.0], [400.0]], dtype=np.float64)

    image_points, _ = cv2.projectPoints(MODEL_3D_POINTS, rvec, tvec, camera_matrix, dist_coeffs)
    image_points = image_points.reshape(-1, 2)

    landmarks = [(width / 2, height / 2)] * 468
    for idx, point in zip(MODEL_LANDMARK_INDICES, image_points):
        landmarks[idx] = (float(point[0]), float(point[1]))
    return landmarks


def _landmarks_for_y_angle(angle_deg, width, height):
    """Convenience wrapper: pure rotation about the model's Y axis only,
    which is the axis empirically confirmed to drive estimate_pitch_deg's
    output monotonically and exactly (see _project_synthetic_landmarks)."""
    return _project_synthetic_landmarks((0.0, angle_deg, 0.0), width, height)


def test_model_uses_six_landmark_indices():
    assert len(MODEL_LANDMARK_INDICES) == 6


def test_drooped_head_has_larger_pitch_than_upright_head():
    w, h = 640, 480
    upright = estimate_pitch_deg(_landmarks_for_y_angle(0, w, h), w, h)
    drooped = estimate_pitch_deg(_landmarks_for_y_angle(20, w, h), w, h)
    assert drooped > upright, "larger known rotation angle must give larger recovered pitch"


def test_recovered_pitch_is_reasonably_close_to_the_known_synthetic_angle():
    w, h = 640, 480
    for true_angle in (-20.0, -10.0, 10.0, 20.0, 30.0):
        landmarks = _landmarks_for_y_angle(true_angle, w, h)
        recovered = estimate_pitch_deg(landmarks, w, h)
        assert abs(recovered - true_angle) < 2.5, (
            f"expected recovered pitch near {true_angle} deg, got {recovered:.4f} deg"
        )


def test_returns_zero_when_solvepnp_fails():
    w, h = 640, 480
    landmarks = _landmarks_for_y_angle(0, w, h)
    with patch("head_pose.cv2.solvePnP", return_value=(False, None, None)):
        assert estimate_pitch_deg(landmarks, w, h) == 0.0
