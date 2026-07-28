from eye_state import compute_ear

# 6-point layout per eye, MediaPipe FaceMesh index order: [outer_corner, top_1,
# top_2, inner_corner, bottom_2, bottom_1] — matches the classic
# Soukupová & Čech EAR formula: (||top1-bottom1|| + ||top2-bottom2||) / (2*||outer-inner||)


def test_open_eye_has_high_ear():
    # tall, open eye shape: vertical gap ~0.08, horizontal gap ~0.30
    landmarks = [
        (0.0, 0.15),   # outer corner
        (0.10, 0.05),  # top_1
        (0.20, 0.05),  # top_2
        (0.30, 0.15),  # inner corner
        (0.20, 0.25),  # bottom_2
        (0.10, 0.25),  # bottom_1
    ]
    assert compute_ear(landmarks) > 0.25


def test_closed_eye_has_low_ear():
    # flat, closed eye shape: near-zero vertical gap, same horizontal gap
    landmarks = [
        (0.0, 0.15),
        (0.10, 0.14),
        (0.20, 0.14),
        (0.30, 0.15),
        (0.20, 0.16),
        (0.10, 0.16),
    ]
    assert compute_ear(landmarks) < 0.10


def test_ear_requires_exactly_six_points():
    import pytest
    with pytest.raises(ValueError):
        compute_ear([(0.0, 0.0), (1.0, 1.0)])
