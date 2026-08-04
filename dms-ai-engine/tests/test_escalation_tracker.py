import pytest

from services.escalation_tracker import EscalationTracker


def test_constructor_validates_length_mismatch():
    with pytest.raises(AssertionError):
        EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0])


def test_not_critical_returns_level_1_and_no_repeat():
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    level, repeat_due, level_changed = tracker.update(critical_active=False, now=0.0)
    assert level == 1
    assert repeat_due is False
    assert level_changed is False


def test_onset_tick_is_level_1_and_repeat_due_fires_immediately():
    """First tick of a new CRITICAL episode -- repeat_due=True is expected
    (it anchors _last_repeat_time; it does NOT cause an extra publish, since
    the emitter's own CRITICAL edge already publishes this same tick -- spec
    Section 2, 'Ghi chú thiết kế quan trọng')."""
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=0.0)
    assert level == 1
    assert repeat_due is True
    assert level_changed is False


def test_level_up_boundary_is_inclusive_at_exact_elapsed():
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    tracker.update(critical_active=True, now=0.0)
    level, _, level_changed = tracker.update(critical_active=True, now=8.0)
    assert level == 2, "elapsed==8.0 must already count as level 2 (>=, not >)"
    assert level_changed is True


def test_single_tick_jump_from_level_1_straight_to_level_3():
    """Simulates a large frame gap (e.g. a face-loss stretch) where elapsed
    jumps past both level-up boundaries in one update() call."""
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    tracker.update(critical_active=True, now=0.0)
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=20.0)
    assert level == 3
    assert level_changed is True
    assert repeat_due is True


def test_level_changed_updates_last_repeat_time_to_avoid_double_fire():
    """Regression for the bug found while tracing the spec's data flow
    (Section 4): if a level-change tick did NOT also update
    _last_repeat_time, the next repeat_due could fire only 1-2s later
    (anchored to the OLD level's last repeat), cutting off the utterance
    that was just spoken for the NEW level."""
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    tracker.update(critical_active=True, now=0.0)          # level 1, _last_repeat_time=0
    tracker.update(critical_active=True, now=8.0)           # level_changed -> 2, _last_repeat_time=8
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=13.0)  # 13-8=5>=interval[1]=5.0
    assert level == 2
    assert repeat_due is True
    assert level_changed is False
    # level changes to 3 here; _last_repeat_time must become 16, NOT stay at 13
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=16.0)
    assert level == 3
    assert level_changed is True
    assert repeat_due is True
    # without the fix, interval[2]=4.0 measured from the OLD anchor (13) would
    # make this next check fire at 17.0 (13+4); with the fix it must NOT fire
    # until 20.0 (16+4).
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=17.0)
    assert repeat_due is False, "must not double-fire 1s after the level-change announcement"
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=20.0)
    assert repeat_due is True


def test_continues_through_a_warning_dip_without_resetting():
    """critical_active is assumed to already be hysteresis-protected by the
    emitter (it only goes False at score<=exit_threshold, not on a WARNING
    dip) -- this test locks in that EscalationTracker trusts that input as-is
    and does not add its own reset logic on top."""
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    tracker.update(critical_active=True, now=0.0)
    tracker.update(critical_active=True, now=16.0)  # level 3
    level, _, _ = tracker.update(critical_active=True, now=22.0)  # still critical_active=True throughout
    assert level == 3, "a caller passing critical_active=True continuously must never see the level drop"


def test_recovered_resets_to_level_1_and_level_changed_fires_exactly_once():
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    tracker.update(critical_active=True, now=0.0)
    tracker.update(critical_active=True, now=16.0)  # level 3
    level, repeat_due, level_changed = tracker.update(critical_active=False, now=20.0)
    assert level == 1
    assert repeat_due is False
    assert level_changed is True
    level, repeat_due, level_changed = tracker.update(critical_active=False, now=21.0)
    assert level_changed is False, "must not repeat level_changed on every subsequent non-critical tick"


def test_reset_forces_level_1_regardless_of_critical_active():
    """reset() is for the face-loss path: critical_active on the underlying
    emitter is frozen (not updated) while has_face=False, so the tracker
    cannot detect a reset via critical_active alone -- main.py calls reset()
    explicitly instead (spec Section 2/5)."""
    tracker = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    tracker.update(critical_active=True, now=0.0)
    tracker.update(critical_active=True, now=16.0)  # level 3
    tracker.reset()
    level, repeat_due, level_changed = tracker.update(critical_active=True, now=16.1)
    assert level == 1, "reset() must force the next update() to start counting from level 1 again"
