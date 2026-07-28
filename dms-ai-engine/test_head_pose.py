from head_pose import estimate_pitch_deg, MODEL_LANDMARK_INDICES


def _synthetic_landmarks(nose_y_offset: float, width: int, height: int):
    """Builds a full 468-point landmark list with just the 6 model points set
    to a plausible upright face, then shifts the nose tip down by
    nose_y_offset to simulate head droop (larger offset = more droop)."""
    landmarks = [(width / 2, height / 2)] * 468
    base = {
        1: (width * 0.50, height * 0.45),   # nose tip
        152: (width * 0.50, height * 0.75),  # chin
        33: (width * 0.35, height * 0.40),   # right eye outer corner
        263: (width * 0.65, height * 0.40),  # left eye outer corner
        61: (width * 0.40, height * 0.65),   # mouth right corner
        291: (width * 0.60, height * 0.65),  # mouth left corner
    }
    for idx, (x, y) in base.items():
        landmarks[idx] = (x, y + nose_y_offset if idx == 1 else y)
    return landmarks


def test_model_uses_six_landmark_indices():
    assert len(MODEL_LANDMARK_INDICES) == 6


def test_drooped_head_has_larger_pitch_than_upright_head():
    w, h = 640, 480
    upright = estimate_pitch_deg(_synthetic_landmarks(0, w, h), w, h)
    drooped = estimate_pitch_deg(_synthetic_landmarks(60, w, h), w, h)
    assert drooped > upright, "gục đầu (nose tip dịch xuống) phải cho pitch lớn hơn"
