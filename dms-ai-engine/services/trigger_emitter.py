"""
TriggerEmitter
--------------
Quyết định KHI NÀO phát ra 1 Trigger từ chuỗi Drowsiness Score liên tục.
Đây là phần "trí tuệ" quan trọng nhất để chống false-positive/flicker —
BGK sẽ hỏi xoáy vào đây, không phải vào con số accuracy của model.

Quy tắc (đúng runbook mục 07):
  - Enter threshold 0.85, phải SUSTAIN liên tục >= sustain_seconds mới fire.
  - Không fire lặp lại khi vẫn đang ở trên ngưỡng (chỉ fire 1 lần / episode).
  - Chỉ "mở khoá" để fire lần tiếp theo khi score rơi xuống <= exit_threshold
    (hysteresis 2 ngưỡng, không phải 1 ngưỡng đơn — tránh dao động quanh 0.85).
  - cooldown_seconds là lớp bảo vệ bổ sung phòng khi logic sustain có bug.

FacePresenceTracker
-------------------
Phát hiện mất mặt kéo dài (camera che, driver ra khỏi khung hình) để phát
UNKNOWN — tách riêng khỏi TriggerEmitter vì đây là tín hiệu về SỰ HIỆN DIỆN
của khuôn mặt, không phải về giá trị score.
"""
from typing import Optional


class TriggerEmitter:
    def __init__(self, enter_threshold: float = 0.85, exit_threshold: float = 0.50,
                 sustain_seconds: float = 2.0, cooldown_seconds: float = 10.0):
        assert exit_threshold < enter_threshold, "exit phải thấp hơn enter (hysteresis)"
        self.enter_threshold = enter_threshold
        self.exit_threshold = exit_threshold
        self.sustain_seconds = sustain_seconds
        self.cooldown_seconds = cooldown_seconds

        self._above_since: Optional[float] = None
        self._last_emit_time: float = float("-inf")
        self._armed = True  # False sau khi đã fire, tới khi score rơi dưới exit_threshold
        self._critical_active = False  # True từ lúc fire CRITICAL tới lúc fire RECOVERED

    @property
    def critical_active(self) -> bool:
        return self._critical_active

    def update(self, score: float, now: float) -> Optional[str]:
        """Gọi mỗi khi có score mới. Trả 'CRITICAL'/'RECOVERED' đúng 1 lần mỗi
        cạnh tương ứng, hoặc None nếu không có gì cần emit."""
        if score >= self.enter_threshold:
            if self._above_since is None:
                self._above_since = now
            sustained = (now - self._above_since) >= self.sustain_seconds
            cooldown_ok = (now - self._last_emit_time) >= self.cooldown_seconds
            if sustained and cooldown_ok and self._armed:
                self._armed = False
                self._last_emit_time = now
                self._critical_active = True
                return "CRITICAL"
        else:
            self._above_since = None
            if score <= self.exit_threshold:
                self._armed = True
                if self._critical_active:
                    self._critical_active = False
                    return "RECOVERED"
        return None


class FacePresenceTracker:
    """Phát hiện mất mặt kéo dài (camera che, driver ra khỏi khung hình) để phát
    UNKNOWN — tách riêng khỏi TriggerEmitter vì đây là tín hiệu về SỰ HIỆN DIỆN
    của khuôn mặt, không phải về giá trị score."""

    def __init__(self, sustain_seconds: float = 2.0):
        self.sustain_seconds = sustain_seconds
        self._absent_since: Optional[float] = None
        self._unknown_active = False

    def update(self, has_face: bool, now: float) -> Optional[str]:
        if not has_face:
            if self._absent_since is None:
                self._absent_since = now
            sustained = (now - self._absent_since) >= self.sustain_seconds
            if sustained and not self._unknown_active:
                self._unknown_active = True
                return "UNKNOWN"
        else:
            self._absent_since = None
            if self._unknown_active:
                self._unknown_active = False
                return "PRESENT"
        return None
