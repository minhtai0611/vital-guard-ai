"""
Chạy: pytest test_dms.py -v
Không cần Android/CarSky/video thật — đây là bằng chứng "test evidence"
đầu tiên có thể bỏ vào evidence/ ngay hôm nay.
"""
from services.score_calculator import DrowsinessScoreCalculator, FrameFeatures
from services.trigger_emitter import TriggerEmitter, FacePresenceTracker


# ---------- DrowsinessScoreCalculator ----------

def test_all_eyes_open_no_droop_gives_zero_score():
    calc = DrowsinessScoreCalculator(window_seconds=2, sample_hz=10)
    for i in range(20):
        calc.add_frame(FrameFeatures(timestamp=i * 0.1, eye_closed=False, head_pitch_deg=0))
    assert calc.compute_score() == 0.0


def test_sustained_closed_eyes_and_droop_gives_high_score():
    calc = DrowsinessScoreCalculator(window_seconds=2, sample_hz=10)
    for i in range(20):
        calc.add_frame(FrameFeatures(timestamp=i * 0.1, eye_closed=True, head_pitch_deg=30))
    assert calc.compute_score() > 0.85


def test_single_normal_blink_does_not_spike_perclos():
    calc = DrowsinessScoreCalculator(window_seconds=2, sample_hz=10)
    # 19 frame mắt mở, 1 frame nhắm (1 cái chớp mắt bình thường trong cửa sổ 2s)
    for i in range(19):
        calc.add_frame(FrameFeatures(timestamp=i * 0.1, eye_closed=False, head_pitch_deg=0))
    score = calc.add_frame(FrameFeatures(timestamp=1.9, eye_closed=True, head_pitch_deg=0))
    assert score < 0.85, "một cái chớp mắt bình thường không được gây score cao"


def test_baseline_calibration_removes_seat_tilt_offset():
    calc = DrowsinessScoreCalculator(window_seconds=2, sample_hz=10)
    calc.calibrate_baseline(pitch_deg=10.0)  # ghế nghiêng sẵn 10 độ khi ngồi thẳng
    for i in range(20):
        calc.add_frame(FrameFeatures(timestamp=i * 0.1, eye_closed=False, head_pitch_deg=10.0))
    # đầu vẫn ở đúng vị trí "thẳng" sau khi trừ baseline -> droop phải bằng 0
    assert calc.compute_score() == 0.0


# ---------- TriggerEmitter ----------

def test_no_trigger_before_sustain_window_elapses():
    emitter = TriggerEmitter(sustain_seconds=2.0, cooldown_seconds=10.0)
    assert emitter.update(0.9, now=0.0) is None
    assert emitter.update(0.9, now=1.0) is None


def test_trigger_fires_once_after_sustain():
    emitter = TriggerEmitter(sustain_seconds=2.0, cooldown_seconds=10.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.0)
    assert emitter.update(0.9, now=2.1) == "CRITICAL"


def test_no_duplicate_trigger_while_still_above_threshold():
    emitter = TriggerEmitter(sustain_seconds=2.0, cooldown_seconds=10.0)
    emitter.update(0.9, now=0.0)
    fired_first = emitter.update(0.9, now=2.1)
    fired_again = emitter.update(0.9, now=3.0)
    assert fired_first == "CRITICAL"
    assert fired_again is None, "không được trigger lặp khi vẫn đang ở episode cũ"


def test_trigger_rearms_after_dropping_below_exit_threshold():
    # cooldown_seconds đặt nhỏ hơn khoảng cách 2 lần fire trong test này để tách riêng
    # 2 cơ chế: re-arm (do hysteresis) và cooldown (lớp bảo vệ bổ sung) không che lấp nhau
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                              sustain_seconds=2.0, cooldown_seconds=3.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=2.1)          # fire lần 1
    emitter.update(0.3, now=5.0)          # rơi dưới exit -> re-arm (driver tỉnh táo lại)
    assert emitter.update(0.9, now=5.1) is None    # chưa sustain đủ lại
    assert emitter.update(0.9, now=7.2) == "CRITICAL"    # sustain đủ (>=2s) và qua cooldown (>=3s kể từ 2.1) -> fire lần 2


def test_hysteresis_prevents_flicker_around_0_85():
    """Score dao động 0.84 <-> 0.86 liên tục quanh ngưỡng -> KHÔNG được bắn trigger
    vì chưa từng sustain >=2s liên tục ở trên 0.85."""
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50, sustain_seconds=2.0)
    t = 0.0
    fired_any = False
    for i in range(10):
        score = 0.86 if i % 2 == 0 else 0.84  # không bao giờ rơi xuống exit_threshold=0.50
        fired_any = fired_any or emitter.update(score, now=t)
        t += 0.3
    assert fired_any is None


def test_critical_active_property_reflects_internal_state():
    emitter = TriggerEmitter(sustain_seconds=2.0, cooldown_seconds=10.0)
    assert emitter.critical_active is False
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=2.1)
    assert emitter.critical_active is True
    emitter.update(0.3, now=5.0)
    assert emitter.critical_active is False


def test_critical_active_does_not_flap_on_warning_zone_oscillation():
    """critical_active must only clear at score<=exit_threshold (0.50) --
    NOT on any dip into the WARNING zone (0.50-0.85). EscalationTracker
    (Task 1) depends on this to avoid resetting escalation on a brief
    improvement."""
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                              sustain_seconds=2.0, cooldown_seconds=10.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=2.1)
    assert emitter.critical_active is True
    t = 2.1
    for i in range(10):
        score = 0.80 if i % 2 == 0 else 0.60  # oscillates in the WARNING zone, never <=0.50
        emitter.update(score, now=t)
        assert emitter.critical_active is True, f"must stay True at t={t}, score={score}"
        t += 0.3


def test_short_dip_below_enter_but_above_exit_resets_sustain_timer():
    """Case gần giống thực tế: score vượt 0.85 được 1.5s rồi tụt xuống 0.7 (chưa chạm exit)
    rồi lại vượt 0.85 — timer sustain phải reset lại từ đầu, không cộng dồn."""
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50, sustain_seconds=2.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.5)
    emitter.update(0.7, now=1.6)   # tụt xuống nhưng vẫn trên exit -> above_since reset về None
    assert emitter.update(0.9, now=1.7) is None    # mới bắt đầu sustain lại
    assert emitter.update(0.9, now=3.8) == "CRITICAL"    # đủ 2.1s kể từ 1.7 -> fire


def test_update_returns_critical_string_on_fire():
    emitter = TriggerEmitter(sustain_seconds=2.0, cooldown_seconds=10.0)
    emitter.update(0.9, now=0.0)
    result = emitter.update(0.9, now=2.1)
    assert result == "CRITICAL"


def test_update_returns_none_when_not_firing():
    emitter = TriggerEmitter(sustain_seconds=2.0, cooldown_seconds=10.0)
    assert emitter.update(0.9, now=0.0) is None


def test_recovered_fires_once_on_down_edge_after_critical():
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                              sustain_seconds=2.0, cooldown_seconds=10.0)
    emitter.update(0.9, now=0.0)
    assert emitter.update(0.9, now=2.1) == "CRITICAL"
    # score drops to/below exit_threshold -> RECOVERED fires exactly once
    assert emitter.update(0.3, now=5.0) == "RECOVERED"
    assert emitter.update(0.3, now=5.1) is None, "must not repeat RECOVERED every call"


def test_recovered_does_not_fire_without_a_prior_critical():
    """Dropping below exit_threshold when no CRITICAL ever fired (e.g. driver was
    never drowsy) must not emit a spurious RECOVERED."""
    emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                              sustain_seconds=2.0, cooldown_seconds=10.0)
    assert emitter.update(0.3, now=0.0) is None
    assert emitter.update(0.2, now=1.0) is None


# ---------- FacePresenceTracker ----------

def test_face_presence_no_signal_while_face_is_visible():
    tracker = FacePresenceTracker(sustain_seconds=2.0)
    assert tracker.update(has_face=True, now=0.0) is None
    assert tracker.update(has_face=True, now=1.0) is None


def test_face_presence_unknown_fires_once_after_sustained_loss():
    tracker = FacePresenceTracker(sustain_seconds=2.0)
    assert tracker.update(has_face=False, now=0.0) is None
    assert tracker.update(has_face=False, now=1.0) is None
    assert tracker.update(has_face=False, now=2.1) == "UNKNOWN"
    assert tracker.update(has_face=False, now=3.0) is None, "must not repeat UNKNOWN every call"


def test_face_presence_present_fires_once_on_face_returning():
    tracker = FacePresenceTracker(sustain_seconds=2.0)
    tracker.update(has_face=False, now=0.0)
    assert tracker.update(has_face=False, now=2.1) == "UNKNOWN"
    assert tracker.update(has_face=True, now=3.0) == "PRESENT"
    assert tracker.update(has_face=True, now=3.1) is None, "must not repeat PRESENT every call"


def test_face_presence_brief_loss_under_sustain_window_emits_nothing():
    tracker = FacePresenceTracker(sustain_seconds=2.0)
    tracker.update(has_face=False, now=0.0)
    assert tracker.update(has_face=True, now=1.0) is None, "face came back before sustain elapsed"
