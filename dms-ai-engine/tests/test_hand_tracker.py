from services.hand_tracker import classify_hands_visibility, hands_on_wheel, WHEEL_REGION


def test_classify_hands_visibility_full_when_two_hands():
    assert classify_hands_visibility(2) == "FULL"


def test_classify_hands_visibility_partial_when_one_hand():
    assert classify_hands_visibility(1) == "PARTIAL"


def test_classify_hands_visibility_unknown_when_no_hands():
    assert classify_hands_visibility(0) == "UNKNOWN"


def _hand_at(cx, cy):
    """A minimal fake hand landmark list: one point at the given normalized
    center, matching the .x/.y attribute interface hands_on_wheel() reads."""
    import types
    return [types.SimpleNamespace(x=cx, y=cy)]


def test_hands_on_wheel_true_only_when_all_visible_hands_in_wheel_region():
    wheel_cx = (WHEEL_REGION["x_min"] + WHEEL_REGION["x_max"]) / 2
    wheel_cy = (WHEEL_REGION["y_min"] + WHEEL_REGION["y_max"]) / 2
    both_hands_on_wheel = [_hand_at(wheel_cx, wheel_cy), _hand_at(wheel_cx, wheel_cy)]
    assert hands_on_wheel(both_hands_on_wheel, WHEEL_REGION) is True


def test_hands_on_wheel_false_when_partial_visibility_and_visible_hand_is_off_wheel():
    """The dangerous case Feature B exists to catch: one hand visible, and
    that one hand is NOT on the wheel (e.g. holding a phone) -- must not be
    optimistically classified as safe just because a hand is visible."""
    off_wheel_hand = [_hand_at(0.02, 0.02)]  # top-left corner, nowhere near the wheel region
    assert hands_on_wheel(off_wheel_hand, WHEEL_REGION) is False


def test_hands_on_wheel_false_when_any_visible_hand_is_outside_the_region():
    wheel_cx = (WHEEL_REGION["x_min"] + WHEEL_REGION["x_max"]) / 2
    wheel_cy = (WHEEL_REGION["y_min"] + WHEEL_REGION["y_max"]) / 2
    one_on_one_off = [_hand_at(wheel_cx, wheel_cy), _hand_at(0.02, 0.02)]
    assert hands_on_wheel(one_on_one_off, WHEEL_REGION) is False
