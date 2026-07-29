from services.eye_state import blink_score, BlinkStateTracker, BLINK_CLOSE_THRESHOLD, BLINK_REOPEN_THRESHOLD


def test_blink_score_averages_both_eyes():
    blendshapes = {"eyeBlinkLeft": 0.8, "eyeBlinkRight": 0.6, "jawOpen": 0.1}
    assert blink_score(blendshapes) == 0.7


def test_blink_score_missing_category_defaults_to_zero():
    """If Face Landmarker doesn't report a category for a frame (e.g. face
    partially out of view), treat it as eyes-open rather than crashing."""
    assert blink_score({"jawOpen": 0.1}) == 0.0


def test_blink_state_tracker_stays_open_below_close_threshold():
    tracker = BlinkStateTracker()
    assert tracker.update(BLINK_CLOSE_THRESHOLD - 0.05, now=0.0) is False


def test_blink_state_tracker_closes_above_close_threshold():
    tracker = BlinkStateTracker()
    assert tracker.update(BLINK_CLOSE_THRESHOLD + 0.05, now=0.0) is True


def test_blink_state_tracker_ignores_a_single_dip_between_the_two_thresholds():
    """This is the exact failure mode from the real drowsy-video finding:
    one noisy frame dipping between the close and reopen thresholds must
    NOT flip the state back to open -- only dropping below the LOWER
    reopen threshold should."""
    tracker = BlinkStateTracker()
    assert tracker.update(BLINK_CLOSE_THRESHOLD + 0.10, now=0.0) is True
    midpoint = (BLINK_CLOSE_THRESHOLD + BLINK_REOPEN_THRESHOLD) / 2
    assert tracker.update(midpoint, now=0.03) is True, "a dip that doesn't cross the reopen threshold must stay closed"
    assert tracker.update(BLINK_CLOSE_THRESHOLD + 0.10, now=0.07) is True


def test_blink_state_tracker_reopens_only_below_reopen_threshold():
    tracker = BlinkStateTracker()
    tracker.update(BLINK_CLOSE_THRESHOLD + 0.10, now=0.0)
    assert tracker.update(BLINK_REOPEN_THRESHOLD - 0.05, now=0.03) is False
