import math

import numpy as np

from services.head_pose import rotation_matrix_to_euler_deg, extract_pitch_deg, extract_yaw_deg


def _rotation_matrix_x(angle_deg):
    """Pure rotation about the X axis, as a 4x4 homogeneous matrix (matching
    facial_transformation_matrixes' shape)."""
    a = math.radians(angle_deg)
    m = np.eye(4)
    m[1, 1] = math.cos(a)
    m[1, 2] = -math.sin(a)
    m[2, 1] = math.sin(a)
    m[2, 2] = math.cos(a)
    return m


def _rotation_matrix_y(angle_deg):
    a = math.radians(angle_deg)
    m = np.eye(4)
    m[0, 0] = math.cos(a)
    m[0, 2] = math.sin(a)
    m[2, 0] = -math.sin(a)
    m[2, 2] = math.cos(a)
    return m


def test_pure_x_rotation_recovers_known_angle():
    for angle in (-30.0, -10.0, 10.0, 30.0):
        x, y, z = rotation_matrix_to_euler_deg(_rotation_matrix_x(angle))
        assert abs(x - angle) < 0.01, f"expected x~{angle}, got {x}"
        assert abs(y) < 0.01 and abs(z) < 0.01, f"pure X rotation leaked into y/z: y={y} z={z}"


def test_pure_y_rotation_recovers_known_angle():
    for angle in (-30.0, -10.0, 10.0, 30.0):
        x, y, z = rotation_matrix_to_euler_deg(_rotation_matrix_y(angle))
        assert abs(y - angle) < 0.01, f"expected y~{angle}, got {y}"
        assert abs(x) < 0.01 and abs(z) < 0.01, f"pure Y rotation leaked into x/z: x={x} z={z}"


def test_extract_pitch_deg_increases_with_the_chosen_axis_rotation():
    """Locks in the Task 3 empirical finding: whichever axis was chosen as
    pitch must increase monotonically with that axis's rotation angle. If
    the chosen axis was X, this duplicates test_pure_x_rotation's rotation
    builder; substitute _rotation_matrix_y (or a z-builder) here if the
    investigation found pitch on a different axis."""
    angles = [-20.0, -10.0, 0.0, 10.0, 20.0, 30.0]
    pitches = [extract_pitch_deg(_rotation_matrix_x(a)) for a in angles]
    for earlier, later in zip(pitches, pitches[1:]):
        assert later > earlier, f"pitch must be strictly increasing, got {pitches}"


def test_extract_pitch_deg_is_insensitive_to_the_other_axes():
    """Regression guard mirroring Task 8's yaw-blindness test -- whichever
    axis ISN'T pitch must not move the extracted value much."""
    for angle in (-30.0, -15.0, 15.0, 30.0):
        pitch = extract_pitch_deg(_rotation_matrix_y(angle))
        assert abs(pitch) < 2.5, f"non-pitch axis rotation of {angle} deg leaked into pitch: got {pitch}"


def test_combined_rotation_does_not_corrupt_pitch_extraction():
    """Single-axis tests above cannot detect a wrong Euler composition-order
    convention -- with only one non-zero angle, any consistent decomposition
    recovers it correctly regardless of composition order. This test
    combines two axes at once (matching how a real head actually moves --
    nod + turn together, not in isolation) under the SAME composition order
    this module's own rotation_matrix_to_euler_deg assumes (R = Rz*Ry*Rx;
    with the Z angle held at 0, that reduces to R = Ry @ Rx -- Ry applied
    to the result of Rx, i.e. Ry's matrix LEFT-multiplies Rx's matrix), so
    it validates internal self-consistency of our chosen convention. With
    this exact order, both angles recover exactly (verified numerically:
    x=20.0, y=15.0, z=0.0 to 1e-10 precision -- these are noiseless
    analytic rotations, not real sensor data, so an exact match is the
    correct bar, not a loose tolerance). It does NOT prove
    facial_transformation_matrixes uses the same convention -- that can
    only be checked empirically against real video (Task 3 Step 3's
    combined-motion probe), which is a separate, real-data-dependent check
    this synthetic test cannot replace."""
    pitch_angle, other_angle = 20.0, 15.0
    combined = _rotation_matrix_y(other_angle) @ _rotation_matrix_x(pitch_angle)
    x, y, z = rotation_matrix_to_euler_deg(combined)
    assert abs(x - pitch_angle) < 0.01, f"expected pitch axis ~{pitch_angle}, got {x}"
    assert abs(y - other_angle) < 0.01, f"expected other axis ~{other_angle}, got {y}"
    assert abs(z) < 0.01, f"expected no leakage into the third (unrotated) axis, got {z}"


def test_extract_yaw_deg_increases_with_the_chosen_axis_rotation():
    angles = [-20.0, -10.0, 0.0, 10.0, 20.0, 30.0]
    yaws = [extract_yaw_deg(_rotation_matrix_y(a)) for a in angles]
    for earlier, later in zip(yaws, yaws[1:]):
        assert later > earlier, f"yaw must be strictly increasing, got {yaws}"


def test_extract_yaw_deg_is_insensitive_to_pitch_rotation():
    """Mirrors test_extract_pitch_deg_is_insensitive_to_the_other_axes exactly
    (same synthetic builder, same 2.5deg tolerance) -- a regression guard on
    extract_yaw_deg() specifically, in case a future change accidentally
    reads the wrong axis, even though the underlying single-axis math is
    already covered by test_pure_x_rotation_recovers_known_angle."""
    for angle in (-30.0, -15.0, 15.0, 30.0):
        yaw = extract_yaw_deg(_rotation_matrix_x(angle))
        assert abs(yaw) < 2.5, f"non-yaw axis rotation of {angle} deg leaked into yaw: got {yaw}"
