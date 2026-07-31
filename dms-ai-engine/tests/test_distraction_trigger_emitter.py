from services.distraction_trigger_emitter import DistractionTriggerEmitter


def test_no_trigger_before_sustain_window_elapses():
    emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
    assert emitter.update(0.9, now=0.0) is None
    assert emitter.update(0.9, now=1.0) is None


def test_trigger_fires_once_after_sustain():
    emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.0)
    assert emitter.update(0.9, now=1.6) == "CRITICAL"


def test_no_duplicate_trigger_while_still_above_threshold():
    emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
    emitter.update(0.9, now=0.0)
    fired_first = emitter.update(0.9, now=1.6)
    fired_again = emitter.update(0.9, now=2.5)
    assert fired_first == "CRITICAL"
    assert fired_again is None, "không được trigger lặp khi vẫn đang ở episode cũ"


def test_trigger_rearms_after_dropping_below_exit_threshold():
    # cooldown_seconds đặt nhỏ hơn khoảng cách 2 lần fire trong test này để tách riêng
    # 2 cơ chế: re-arm (do hysteresis) và cooldown (lớp bảo vệ bổ sung) không che lấp nhau
    emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                         sustain_seconds=1.5, cooldown_seconds=2.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.6)           # fire lần 1
    emitter.update(0.3, now=3.0)           # rơi dưới exit -> re-arm
    assert emitter.update(0.9, now=3.1) is None    # chưa sustain đủ lại
    assert emitter.update(0.9, now=4.7) == "CRITICAL"    # sustain đủ (>=1.5s) và qua cooldown (>=2.0s kể từ 1.6) -> fire lần 2


def test_hysteresis_prevents_flicker_around_0_70():
    """Score dao động 0.71 <-> 0.69 liên tục quanh ngưỡng -> KHÔNG được bắn trigger
    vì chưa từng sustain >=1.5s liên tục ở trên 0.70."""
    emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40, sustain_seconds=1.5)
    t = 0.0
    fired_any = False
    for i in range(10):
        score = 0.71 if i % 2 == 0 else 0.69  # không bao giờ rơi xuống exit_threshold=0.40
        fired_any = fired_any or emitter.update(score, now=t)
        t += 0.3
    assert fired_any is None or fired_any is False


def test_short_dip_below_enter_but_above_exit_resets_sustain_timer():
    emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40, sustain_seconds=1.5)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.0)
    emitter.update(0.5, now=1.1)   # tụt xuống nhưng vẫn trên exit -> above_since reset về None
    assert emitter.update(0.9, now=1.2) is None    # mới bắt đầu sustain lại
    assert emitter.update(0.9, now=2.8) == "CRITICAL"    # đủ 1.6s kể từ 1.2 -> fire


def test_update_returns_critical_string_on_fire():
    emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
    emitter.update(0.9, now=0.0)
    result = emitter.update(0.9, now=1.6)
    assert result == "CRITICAL"


def test_update_returns_none_when_not_firing():
    emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
    assert emitter.update(0.9, now=0.0) is None


def test_recovered_fires_once_on_down_edge_after_critical():
    emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                         sustain_seconds=1.5, cooldown_seconds=5.0)
    emitter.update(0.9, now=0.0)
    assert emitter.update(0.9, now=1.6) == "CRITICAL"
    assert emitter.update(0.3, now=3.0) == "RECOVERED"
    assert emitter.update(0.3, now=3.1) is None, "must not repeat RECOVERED every call"


def test_recovered_does_not_fire_without_a_prior_critical():
    emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                         sustain_seconds=1.5, cooldown_seconds=5.0)
    assert emitter.update(0.3, now=0.0) is None
    assert emitter.update(0.2, now=1.0) is None
