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


def test_monotonic_timestamp_falls_back_instead_of_raising_on_nan():
    """int(float('nan')) raises ValueError. A NaN raw_ms is a real risk, not a
    theoretical one -- main.py/measure_latency.py feed this straight from a
    live cap.get(cv2.CAP_PROP_POS_MSEC) read, which some codecs/containers can
    report as NaN. This must fall back to _last + 1 instead of crashing the
    whole VIDEO-mode inference loop."""
    m = MonotonicTimestamp()
    assert m.next(100.0) == 100
    assert m.next(float("nan")) == 101, "NaN must fall back to _last + 1, not raise"
    assert m.next(102.0) == 102, "a later genuine reading resumes passing through normally"


def test_monotonic_timestamp_falls_back_instead_of_raising_on_positive_infinity():
    """int(float('inf')) raises OverflowError. Also covers the case where the
    very first reading ever seen is non-finite -- _last is still None, so the
    fallback must be 0, not an attempt to add 1 to None."""
    m = MonotonicTimestamp()
    assert m.next(float("inf")) == 0, "first-ever call with +inf must fall back to 0, not raise"

    m2 = MonotonicTimestamp()
    assert m2.next(50.0) == 50
    assert m2.next(float("inf")) == 51, "+inf after a real reading must fall back to _last + 1"
    assert m2.next(60.0) == 60, "a later genuine reading resumes passing through normally"
