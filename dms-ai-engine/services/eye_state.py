"""
eye_state
---------
Eye-closure signal from MediaPipe Face Landmarker's face_blendshapes
(eyeBlinkLeft/eyeBlinkRight, continuous [0,1]) instead of a hand-computed
eye-aspect-ratio from raw landmark geometry. Pure data in, no
MediaPipe/OpenCV import here -- testable with plain dicts.

BLINK_CLOSE_THRESHOLD/BLINK_REOPEN_THRESHOLD form a two-threshold
hysteresis at the RAW SIGNAL layer (not just the composite-score layer,
where the only hysteresis previously lived -- trigger_emitter.py's
TriggerEmitter). This targets a specific, evidenced failure: a real drowsy
video's score climbed to 0.800 then dropped sharply the instant one frame's
raw signal crossed a single instantaneous threshold, right before the 0.85
CRITICAL threshold would have been reached (see
PITCH_ESTIMATION_FINDINGS.md). Values between the two thresholds are
deliberately "sticky" in whichever state was last entered.
"""
from typing import Dict

# Blendshape scores range [0, 1], where 1 = eyes fully closed.
# BLINK_CLOSE_THRESHOLD = 0.55: enter "closed" when eyes >55% shut (above midpoint);
# BLINK_REOPEN_THRESHOLD = 0.35: exit "closed" only when eyes <35% shut (below midpoint).
# The 0.2 gap (0.55 - 0.35) provides hysteresis to prevent state flicker from noise
# in the blendshape signal (e.g., a single noisy frame dipping between the thresholds).
# This starting point is centered slightly above the blendshape [0,1] midpoint (0.5)
# and will be empirically validated against real blink video data in Task 7.
BLINK_CLOSE_THRESHOLD = 0.55
BLINK_REOPEN_THRESHOLD = 0.35


def blink_score(blendshapes: Dict[str, float]) -> float:
    left = blendshapes.get("eyeBlinkLeft", 0.0)
    right = blendshapes.get("eyeBlinkRight", 0.0)
    return (left + right) / 2.0


class BlinkStateTracker:
    def __init__(self):
        self._closed = False

    def update(self, score: float, now: float) -> bool:
        if not self._closed and score >= BLINK_CLOSE_THRESHOLD:
            self._closed = True
        elif self._closed and score <= BLINK_REOPEN_THRESHOLD:
            self._closed = False
        return self._closed
