from services.face_landmarker_client import MonotonicTimestamp


def test_monotonic_timestamp_passes_through_increasing_values():
    m = MonotonicTimestamp()
    assert m.next(0.0) == 0
    assert m.next(33.0) == 33
    assert m.next(67.0) == 67


def test_monotonic_timestamp_bumps_a_non_increasing_reading():
    """Some codecs/containers (B-frames, variable frame rate) can report a
    CAP_PROP_POS_MSEC value that doesn't strictly increase between reads.
    FaceLandmarker's VIDEO mode throws an exception immediately (not a
    warning) if fed a non-increasing timestamp -- this must never happen."""
    m = MonotonicTimestamp()
    assert m.next(100.0) == 100
    assert m.next(100.0) == 101, "equal reading must still advance"
    assert m.next(50.0) == 102, "a reading that went backwards must still advance"
    assert m.next(200.0) == 200, "a later genuine reading resumes passing through normally"


def test_monotonic_timestamp_first_call_accepts_zero():
    m = MonotonicTimestamp()
    assert m.next(0.0) == 0
