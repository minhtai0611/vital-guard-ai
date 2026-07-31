from services.distraction_score_calculator import (
    is_gaze_off_road, DistractionFrameFeatures, DistractionScoreCalculator,
)


def test_is_gaze_off_road_treats_head_down_with_eyes_open_as_distraction():
    assert is_gaze_off_road(head_off_road=True, eye_closed=False) is True


def test_is_gaze_off_road_treats_head_down_with_eyes_closed_as_not_distraction():
    assert is_gaze_off_road(head_off_road=True, eye_closed=True) is False


def test_is_gaze_off_road_false_when_head_is_not_off_road_regardless_of_eyes():
    assert is_gaze_off_road(head_off_road=False, eye_closed=False) is False
    assert is_gaze_off_road(head_off_road=False, eye_closed=True) is False


def test_all_normal_gives_zero_score():
    calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
    for i in range(20):
        calc.add_frame(DistractionFrameFeatures(
            timestamp=i * 0.1, gaze_off_road=False,
            hands_visibility="FULL", hands_on_wheel=True,
        ))
    assert calc.compute_score() == 0.0


def test_gaze_alone_at_full_ratio_clears_the_enter_threshold():
    """Locks in the exact arithmetic this design's weights depend on: gaze
    off-road for the entire window, hands always on wheel (matching
    distracted.mp4's real ground truth), must still produce a score that
    would clear DistractionTriggerEmitter's default enter_threshold=0.70 --
    an earlier weight split (0.65/0.35) made this mathematically
    impossible; this test exists so that regression can never silently
    return."""
    calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
    score = 0.0
    for i in range(20):
        score = calc.add_frame(DistractionFrameFeatures(
            timestamp=i * 0.1, gaze_off_road=True,
            hands_visibility="FULL", hands_on_wheel=True,
        ))
    assert score >= 0.70, f"gaze alone at ratio=1.0 must clear 0.70, got {score}"


def test_sustained_off_road_and_hands_off_wheel_gives_max_score():
    calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
    score = 0.0
    for i in range(20):
        score = calc.add_frame(DistractionFrameFeatures(
            timestamp=i * 0.1, gaze_off_road=True,
            hands_visibility="FULL", hands_on_wheel=False,
        ))
    assert score == 1.0


def test_unknown_visibility_frames_are_excluded_not_treated_as_off_wheel():
    """Mirrors FacePresenceTracker's principle: a missing detection must
    never be fabricated into evidence of the worst case. All frames report
    hands_visibility="UNKNOWN" (hands_on_wheel is a don't-care in that
    state) and gaze is never off-road -- score must stay exactly 0.0, not
    get inflated by treating the unknown hand state as "off wheel"."""
    calc = DistractionScoreCalculator(window_seconds=2, sample_hz=10)
    score = 0.0
    for i in range(20):
        score = calc.add_frame(DistractionFrameFeatures(
            timestamp=i * 0.1, gaze_off_road=False,
            hands_visibility="UNKNOWN", hands_on_wheel=False,
        ))
    assert score == 0.0
