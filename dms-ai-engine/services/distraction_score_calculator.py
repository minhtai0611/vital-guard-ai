"""
distraction_score_calculator
-----------------------------
Composite Distraction Score [0..1] from a sliding window of
DistractionFrameFeatures. Two independent sub-signals:
  score = W_GAZE * gaze_off_road_ratio + W_HANDS * hands_off_wheel_ratio

W_GAZE=0.80/W_HANDS=0.20 -- a fresh, reasoned starting point, NOT copied
from drowsiness's 0.55/0.25/0.20 weights and NOT scientifically validated.
Gaze is the more direct, more dangerous signal (looking away from the road
while still holding the wheel is worse than briefly one-handing the wheel
while still watching the road) and it's the only sub-signal with real
verifiable ground truth right now (distracted.mp4 has hands on the wheel
throughout). An earlier 0.65/0.35 draft made the only real positive-case
video's max reachable score 0.65 -- strictly below any sane enter
threshold -- a hard arithmetic ceiling, not a tunable-later gap. With
0.80/0.20, gaze alone at ratio=1.0 reaches 0.80, clearing
DistractionTriggerEmitter's default enter_threshold=0.70 with margin.
Hands-off-wheel alone, even at ratio=1.0, only contributes 0.20 -- a
supporting/amplifying signal in this iteration, not independently
sufficient (mirrors how drowsiness's own head_droop_norm term alone can't
reach CRITICAL either). Revisit once real footage combining sustained
hands-off-wheel WITHOUT gaze-off-road exists to test against.
"""
from dataclasses import dataclass
from collections import deque

W_GAZE = 0.80
W_HANDS = 0.20


def is_gaze_off_road(head_off_road: bool, eye_closed: bool) -> bool:
    """A drooped/turned head with closed eyes is drowsiness, not
    distraction; a drooped/turned head with open eyes (looking down at a
    phone, or turned to talk) is distraction, not drowsiness. `eye_closed`
    must be the already-debounced BlinkStateTracker output, not a fresh
    raw blink_score threshold check -- reusing a second raw check would
    reintroduce the single-frame-noise problem hysteresis was built to
    solve."""
    return head_off_road and not eye_closed


@dataclass
class DistractionFrameFeatures:
    timestamp: float
    gaze_off_road: bool      # = is_gaze_off_road(head_off_road, eye_closed)
    hands_visibility: str    # "FULL"|"PARTIAL"|"UNKNOWN"
    hands_on_wheel: bool     # meaningful only when hands_visibility != "UNKNOWN"


class DistractionScoreCalculator:
    def __init__(self, window_seconds: float = 2.0, sample_hz: float = 10.0):
        self.max_samples = max(1, int(window_seconds * sample_hz))
        self.window: deque = deque(maxlen=self.max_samples)

    def add_frame(self, frame: DistractionFrameFeatures) -> float:
        self.window.append(frame)
        return self.compute_score()

    def compute_score(self) -> float:
        if not self.window:
            return 0.0
        gaze_off_road_ratio = sum(1 for f in self.window if f.gaze_off_road) / len(self.window)
        countable = [f for f in self.window if f.hands_visibility != "UNKNOWN"]
        hands_off_wheel_ratio = (
            sum(1 for f in countable if not f.hands_on_wheel) / len(countable)
            if countable else 0.0  # no countable frames -> neutral, not fabricated
        )
        score = W_GAZE * gaze_off_road_ratio + W_HANDS * hands_off_wheel_ratio
        return max(0.0, min(1.0, score))
