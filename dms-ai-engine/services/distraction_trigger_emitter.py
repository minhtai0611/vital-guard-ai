"""
DistractionTriggerEmitter
--------------------------
Same two-threshold hysteresis shape as TriggerEmitter (services/trigger_emitter.py),
but a SEPARATE class with separate reasoning -- distraction's sustain/cooldown
timing is genuinely different from drowsiness's, not a copy:
  - sustain_seconds=1.5 (vs drowsiness's 2.0): a phone glance or a hand off
    the wheel is dangerous on a shorter timescale than the drowsiness FSM's
    own drowsiness-confirmation window.
  - cooldown_seconds=5.0 (vs drowsiness's 10.0): distraction events plausibly
    recur more often and shouldn't be suppressed as long.
All four defaults are reasoned starting points, explicitly unvalidated,
revisited at the acceptance gate (same disclosed status as TriggerEmitter's
own numbers).
"""
from typing import Optional


class DistractionTriggerEmitter:
    def __init__(self, enter_threshold: float = 0.70, exit_threshold: float = 0.40,
                 sustain_seconds: float = 1.5, cooldown_seconds: float = 5.0):
        assert exit_threshold < enter_threshold, "exit phải thấp hơn enter (hysteresis)"
        self.enter_threshold = enter_threshold
        self.exit_threshold = exit_threshold
        self.sustain_seconds = sustain_seconds
        self.cooldown_seconds = cooldown_seconds

        self._above_since: Optional[float] = None
        self._last_emit_time: float = float("-inf")
        self._armed = True
        self._critical_active = False

    def update(self, score: float, now: float) -> Optional[str]:
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
