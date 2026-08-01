class EscalationTracker:
    """
    Tính escalation_level (1/2/3) dựa trên thời gian LIÊN TỤC ở CRITICAL đã qua
    hysteresis (driven bởi critical_active property của TriggerEmitter/
    DistractionTriggerEmitter -- KHÔNG phải raw score > threshold từng frame).
    """

    def __init__(self, level_up_seconds: list[float], repeat_interval_seconds: list[float]):
        assert len(repeat_interval_seconds) == len(level_up_seconds) + 1
        self._level_up_seconds = level_up_seconds
        self._repeat_interval_seconds = repeat_interval_seconds
        self._critical_since: float | None = None
        self._last_repeat_time: float | None = None
        self._last_level = 1

    def update(self, critical_active: bool, now: float) -> tuple[int, bool, bool]:
        """Trả về (level, repeat_due, level_changed)."""
        if not critical_active:
            self._critical_since = None
            self._last_repeat_time = None
            level_changed = self._last_level != 1
            self._last_level = 1
            return 1, False, level_changed

        if self._critical_since is None:
            self._critical_since = now
            self._last_repeat_time = None  # chưa lặp lần nào trong episode này

        elapsed = now - self._critical_since
        level = 1 + sum(1 for t in self._level_up_seconds if elapsed >= t)

        level_changed = level != self._last_level
        self._last_level = level

        interval = self._repeat_interval_seconds[level - 1]
        # level_changed cũng tự tính là "vừa lặp" -- nếu không, _last_repeat_time
        # cũ (neo theo interval của level TRƯỚC) có thể khiến lần repeat_due kế
        # tiếp rơi chỉ 1-2s sau khi vừa nói câu ở level MỚI, cắt ngang utterance
        # đó giữa chừng.
        repeat_due = level_changed or self._last_repeat_time is None or (now - self._last_repeat_time) >= interval
        if repeat_due:
            self._last_repeat_time = now
        return level, repeat_due, level_changed

    def reset(self) -> None:
        """Gọi khi UNKNOWN (mất mặt) bắn ra -- force về level 1 bất kể
        critical_active hiện tại (TriggerEmitter không cập nhật trong lúc mất
        mặt, nên không thể tự phản ánh qua critical_active)."""
        self._critical_since = None
        self._last_repeat_time = None
        self._last_level = 1
