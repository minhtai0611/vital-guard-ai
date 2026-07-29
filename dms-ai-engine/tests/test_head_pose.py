from unittest.mock import patch

import cv2
import numpy as np
import pytest

from services.head_pose import estimate_pitch_deg, MODEL_LANDMARK_INDICES, MODEL_3D_POINTS


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
    of MODEL_3D_POINTS (the round-1 fixture measured 16-23px reprojection
    error even at its "upright" baseline, and flipped sign/magnitude between
    nearby offsets -- a poorly-conditioned setup, not a real droop signal).

    rotation_vec_deg is an (rx, ry, rz) axis-angle rotation in degrees, applied
    as a Rodrigues rotation vector via cv2.projectPoints.

    Axis choice history (see task-8-report.md for the full derivation):
    `estimate_pitch_deg` was originally implemented as
    `atan2(-R[2,0], sqrt(R[0,0]**2 + R[1,0]**2))`, which is only sensitive to
    rotation about the model's local Y axis (MODEL_3D_POINTS' vertical,
    eyes-above-chin axis) -- i.e. it measured YAW (turning left/right), not
    pitch (nodding), because it was algebraically blind to X-axis rotation
    (the real ear-to-ear axis a physical head nod rotates about: eyes are at
    X=-43.3/+43.3, so X is the interaural axis). That bug was found via this
    same fixture during a second review round and fixed by changing the
    extraction to `atan2(R[2,1], R[2,2])`, which is exactly the mirror image:
    sensitive to X-axis rotation, blind to Y-axis rotation. Tests below rotate
    about X (see `_landmarks_for_x_angle`) to match physical head pitch, and
    explicitly assert blindness to pure yaw (Y-axis rotation) as a regression
    guard against re-introducing the yaw/pitch swap.
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


def _landmarks_for_x_angle(angle_deg, width, height):
    """Convenience wrapper: pure rotation about the model's local X axis
    (the ear-to-ear / interaural axis) only -- the axis a physical head nod
    rotates about, and the axis the corrected estimate_pitch_deg formula
    (atan2(R[2,1], R[2,2])) is sensitive to."""
    return _project_synthetic_landmarks((angle_deg, 0.0, 0.0), width, height)


def _landmarks_for_y_angle(angle_deg, width, height):
    """Convenience wrapper: pure rotation about the model's local Y axis
    (vertical, eyes-above-chin axis) only -- physically a head turn (yaw),
    which the corrected formula must now be blind to."""
    return _project_synthetic_landmarks((0.0, angle_deg, 0.0), width, height)


def test_model_uses_six_landmark_indices():
    assert len(MODEL_LANDMARK_INDICES) == 6


def test_drooped_head_has_larger_pitch_than_upright_head():
    w, h = 640, 480
    upright = estimate_pitch_deg(_landmarks_for_x_angle(0, w, h), w, h)
    drooped = estimate_pitch_deg(_landmarks_for_x_angle(20, w, h), w, h)
    assert drooped > upright, "larger known head-nod rotation angle must give larger recovered pitch"


def test_recovered_pitch_is_reasonably_close_to_the_known_synthetic_angle():
    w, h = 640, 480
    for true_angle in (-20.0, -10.0, 10.0, 20.0, 30.0):
        landmarks = _landmarks_for_x_angle(true_angle, w, h)
        recovered = estimate_pitch_deg(landmarks, w, h)
        assert abs(recovered - true_angle) < 2.5, (
            f"expected recovered pitch near {true_angle} deg, got {recovered:.4f} deg"
        )


@pytest.mark.parametrize("true_angle", list(range(-30, 31, 2)))
def test_pitch_sweep_recovers_known_x_axis_angle_across_wide_range(true_angle):
    """Committed version of the manual sweep script used to validate this fix
    (round 1's version of this sweep was left as an uncommitted scratch file,
    flagged as a completeness gap by review -- this parametrized test is the
    fix for that gap). Covers -30..30 degrees in 2-degree steps (31 cases) on
    the X axis, which is exact-recoverable because the fixture projects
    through the identical camera model estimate_pitch_deg inverts, so the
    round trip has no real-world noise -- tolerance is tight (0.01 deg) to
    catch any regression in either the fixture or the extraction formula."""
    w, h = 640, 480
    landmarks = _landmarks_for_x_angle(true_angle, w, h)
    recovered = estimate_pitch_deg(landmarks, w, h)
    assert abs(recovered - true_angle) < 0.01, (
        f"expected recovered pitch within 0.01 deg of {true_angle}, got {recovered:.6f}"
    )


def test_pitch_sweep_is_monotonically_increasing_across_x_axis_rotation():
    """Explicit monotonic-ordering check across a wide sweep, reproducing the
    reviewer's 'sweep nearby values, don't just check two points' methodology
    that originally caught the round-1 bug -- applied here to the corrected
    X-axis formula as a standing regression test."""
    w, h = 640, 480
    angles = list(range(-30, 31, 5))
    pitches = [estimate_pitch_deg(_landmarks_for_x_angle(a, w, h), w, h) for a in angles]
    for earlier, later in zip(pitches, pitches[1:]):
        assert later > earlier, f"pitch sequence must be strictly increasing, got {pitches}"


def test_pitch_is_insensitive_to_pure_yaw_rotation():
    """Regression guard for the exact bug the second review round found:
    estimate_pitch_deg's original formula (atan2(-R[2,0], sy)) was actually
    measuring yaw (rotation about the model's local Y axis), not pitch. This
    test asserts the corrected formula stays near zero under pure yaw, so a
    future accidental revert back to the old formula would fail loudly here
    instead of only showing up in a real accuracy analysis."""
    w, h = 640, 480
    for yaw_angle in (-30.0, -15.0, 15.0, 30.0):
        landmarks = _landmarks_for_y_angle(yaw_angle, w, h)
        recovered = estimate_pitch_deg(landmarks, w, h)
        assert abs(recovered) < 2.5, (
            f"pure yaw of {yaw_angle} deg should not move recovered pitch, got {recovered:.4f} deg"
        )


def test_returns_zero_when_solvepnp_fails():
    w, h = 640, 480
    landmarks = _landmarks_for_x_angle(0, w, h)
    with patch("services.head_pose.cv2.solvePnP", return_value=(False, None, None)):
        assert estimate_pitch_deg(landmarks, w, h) == 0.0
