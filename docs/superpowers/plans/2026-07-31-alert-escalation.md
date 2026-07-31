# Alert Escalation (Drowsiness & Distraction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add voice-repeat + climate-escalation (drowsiness only) when a driver stays CRITICAL continuously without recovering, replacing the current "alert once, then silent" behavior.

**Architecture:** A new, standalone `EscalationTracker` class (Python) computes a 1-3 `escalation_level` per source (drowsiness, distraction) from a new read-only `critical_active` property already exposed by the existing emitters — no changes to the emitters' own sustain/cooldown logic. The level travels to the app inside the existing trigger payload; Kotlin owns all real actuation values (temperature, TTS copy) and re-acts on every CRITICAL payload it receives (no more "fire once, latch" — Python is now the sole timing authority, deciding when a repeat/level-change is meaningful).

**Tech Stack:** Python 3.12 (`dms-ai-engine/`), pytest; Kotlin (`aaos-cockpit-app/`), JUnit4 (no Gradle wrapper exists in this repo/environment — Kotlin changes are hand-traced against the real source, not compiler-verified, exactly as done for every prior Kotlin task in this project).

**Spec:** `docs/superpowers/specs/2026-07-31-alert-escalation-design.md` — read it before starting; this plan implements it section by section and cites exact section numbers throughout.

## Global Constraints

- Do NOT modify `TriggerEmitter.update()` / `DistractionTriggerEmitter.update()`'s existing sustain/cooldown/hysteresis logic — only add a new read-only `critical_active` property to each (spec Section 2).
- `EscalationTracker` lives in its own new file, is driven by `critical_active` (already hysteresis-protected), never by raw score — this is why it doesn't flap or reset on a WARNING dip (spec Section 2, verified against `trigger_emitter.py:36,46-49,53-57`).
- Any branch of `main.py`/`measure_latency.py` that runs when `has_face=False` (the CSV `else` branch, the `face_signal == "UNKNOWN"` branch) must use LITERAL `1`/`""` for the two new escalation fields — never read `drowsy_level`/`distraction_level`, which carry a stale value from a prior tick in that branch (spec Section 2, "Bắt buộc dùng literal").
- Python never knows real actuation values (°C, TTS copy) — it only computes/sends `escalation_level: int` (1-3). Kotlin owns all level→action mapping (spec Section 3).
- `repeat_interval_seconds` (Python) and TTS copy length (Kotlin) are coupled — a shorter interval than the spoken utterance cuts the utterance off via `QUEUE_FLUSH`. The values in this plan already account for the estimated (not yet device-measured) utterance durations (spec Section 3, "Ràng buộc thời lượng TTS").
- No mute/disable/snooze UI — explicitly out of scope (spec header, "Ngoài phạm vi").
- No changes to `AlertArbiter`'s suppression/handoff decision logic (drowsiness always wins, stop-outgoing-before-handoff) — only thread a new `level: Int` parameter through it.
- Any file whose function signature changes (`build_trigger_payload`, `applyDrowsinessOverride`, `triggerAlert`, `triggerDistractionReminder`, `requestVoiceAlert`, `TriggerPayload`/`DistractionInfo` constructors) must have EVERY existing call site updated in the same task that changes the signature — this plan lists every call site found by reading the actual current files; if implementing a task finds an additional call site not listed here, update it too and note it in the task's completion report (spec Section 3, "Rủi ro ripple-effect đã biết trước").
- This branch's `DrowsinessController.handleCritical()`/`revertToBaseline()` and its constructor are ALSO being changed by a separate, independently-planned spec (`docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md`, Gap #2). If that work has already landed on this branch by the time you implement Task 10/11 here, do NOT blindly overwrite `handleCritical()`/`revertToBaseline()` with this plan's version — read the current file first and merge: this plan's `level`-based logic must coexist with that spec's `alertPreferencesStore`/`isParked` checks in the same two methods (spec Section 7a).

---

## Task 1: `EscalationTracker` (pure Python logic)

**Files:**
- Create: `dms-ai-engine/services/escalation_tracker.py`
- Test: `dms-ai-engine/tests/test_escalation_tracker.py`

**Interfaces:**
- Produces: `EscalationTracker(level_up_seconds: list[float], repeat_interval_seconds: list[float])`, method `update(critical_active: bool, now: float) -> tuple[int, bool, bool]` returning `(level, repeat_due, level_changed)`, method `reset() -> None`. Both `level_up_seconds` and `repeat_interval_seconds` are plain lists of floats (no other type). `len(repeat_interval_seconds) == len(level_up_seconds) + 1` is enforced by an `assert` in `__init__`.

- [ ] **Step 1: Write the failing tests**

Create `dms-ai-engine/tests/test_escalation_tracker.py`:

```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd dms-ai-engine && python -m pytest tests/test_escalation_tracker.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'services.escalation_tracker'`

- [ ] **Step 3: Write the implementation**

Create `dms-ai-engine/services/escalation_tracker.py`:

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd dms-ai-engine && python -m pytest tests/test_escalation_tracker.py -v`
Expected: 9 passed

- [ ] **Step 5: Commit**

```bash
git add dms-ai-engine/services/escalation_tracker.py dms-ai-engine/tests/test_escalation_tracker.py
git commit -m "Add EscalationTracker: 1-3 level computed from continuous CRITICAL time"
```

---

## Task 2: `critical_active` property + anti-flap tests on both emitters

**Files:**
- Modify: `dms-ai-engine/services/trigger_emitter.py:24-58` (class `TriggerEmitter`)
- Modify: `dms-ai-engine/services/distraction_trigger_emitter.py:19-51` (class `DistractionTriggerEmitter`)
- Test: `dms-ai-engine/tests/test_dms.py` (append after the existing `TriggerEmitter` tests)
- Test: `dms-ai-engine/tests/test_distraction_trigger_emitter.py` (append)

**Interfaces:**
- Consumes: nothing new.
- Produces: `TriggerEmitter.critical_active` (bool, read-only property, reflects the existing private `self._critical_active`). Same for `DistractionTriggerEmitter.critical_active`. Task 4 consumes both.

- [ ] **Step 1: Write the failing tests**

Append to `dms-ai-engine/tests/test_dms.py` (after `test_hysteresis_prevents_flicker_around_0_85`, i.e. after line 91):

```python
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
```

Append to `dms-ai-engine/tests/test_distraction_trigger_emitter.py` (end of file, after `test_recovered_does_not_fire_without_a_prior_critical`):

```python
def test_critical_active_property_reflects_internal_state():
    emitter = DistractionTriggerEmitter(sustain_seconds=1.5, cooldown_seconds=5.0)
    assert emitter.critical_active is False
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.6)
    assert emitter.critical_active is True
    emitter.update(0.3, now=3.0)
    assert emitter.critical_active is False


def test_critical_active_does_not_flap_on_warning_zone_oscillation():
    emitter = DistractionTriggerEmitter(enter_threshold=0.70, exit_threshold=0.40,
                                         sustain_seconds=1.5, cooldown_seconds=5.0)
    emitter.update(0.9, now=0.0)
    emitter.update(0.9, now=1.6)
    assert emitter.critical_active is True
    t = 1.6
    for i in range(10):
        score = 0.65 if i % 2 == 0 else 0.45  # oscillates in the WARNING zone, never <=0.40
        emitter.update(score, now=t)
        assert emitter.critical_active is True, f"must stay True at t={t}, score={score}"
        t += 0.3
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd dms-ai-engine && python -m pytest tests/test_dms.py::test_critical_active_property_reflects_internal_state tests/test_distraction_trigger_emitter.py::test_critical_active_property_reflects_internal_state -v`
Expected: FAIL with `AttributeError: 'TriggerEmitter' object has no attribute 'critical_active'`

- [ ] **Step 3: Add the property to both classes**

In `dms-ai-engine/services/trigger_emitter.py`, inside `class TriggerEmitter` (after `__init__`, i.e. after line 36 `self._critical_active = False`), add:

```python

    @property
    def critical_active(self) -> bool:
        return self._critical_active
```

In `dms-ai-engine/services/distraction_trigger_emitter.py`, inside `class DistractionTriggerEmitter` (after `__init__`, i.e. after line 31 `self._critical_active = False`), add:

```python

    @property
    def critical_active(self) -> bool:
        return self._critical_active
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd dms-ai-engine && python -m pytest tests/test_dms.py tests/test_distraction_trigger_emitter.py -v`
Expected: all pass (existing tests unaffected, 4 new tests pass)

- [ ] **Step 5: Commit**

```bash
git add dms-ai-engine/services/trigger_emitter.py dms-ai-engine/services/distraction_trigger_emitter.py dms-ai-engine/tests/test_dms.py dms-ai-engine/tests/test_distraction_trigger_emitter.py
git commit -m "Add read-only critical_active property to both trigger emitters"
```

---

## Task 3: `contracts/trigger.schema.json`

**Files:**
- Modify: `contracts/trigger.schema.json`

**Interfaces:**
- Produces: schema now requires `escalationLevel` (top-level, integer 1-3) and `distraction.escalationLevel` (nested, integer 1-3). Task 4's `test_build_trigger_payload_matches_schema` and Task 6's Kotlin `TriggerPayload` both consume this contract.

- [ ] **Step 1: Edit the schema**

The current file is exactly:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Vital-Guard AI Trigger Payload",
  "description": "Wire format for DMS trigger events, delivered as an HTTP JSON body over the local network-pin (GET /latest-trigger). Kept nested (not flattened) because the transport is HTTP JSON, not Android Intent extras — see docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md Decision 4.",
  "type": "object",
  "required": ["timestampMs", "source", "score", "confidence", "state", "features", "reason", "correlationId", "distraction"],
  "properties": {
    "timestampMs": { "type": "integer", "description": "Epoch milliseconds." },
    "source": { "type": "string", "enum": ["container-python", "debug", "replay", "mock-stream"] },
    "score": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
    "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
    "state": { "type": "string", "enum": ["NORMAL", "WARNING", "CRITICAL", "UNKNOWN"] },
    "features": {
      "type": "object",
      "required": ["perclos", "eyeOpenProbability", "headEulerAngleX"],
      "properties": {
        "perclos": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "eyeOpenProbability": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "headEulerAngleX": { "type": "number" }
      }
    },
    "reason": { "type": "string" },
    "correlationId": { "type": "string" },
    "distraction": {
      "type": "object",
      "required": ["score", "state", "yawDeg", "pitchDeg", "handsVisibility", "handsOnWheel", "reason"],
      "properties": {
        "score": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "state": { "type": "string", "enum": ["NORMAL", "WARNING", "CRITICAL"] },
        "yawDeg": { "type": "number" },
        "pitchDeg": { "type": "number" },
        "handsVisibility": { "type": "string", "enum": ["FULL", "PARTIAL", "UNKNOWN"] },
        "handsOnWheel": { "type": "boolean" },
        "reason": { "type": "string" }
      }
    }
  }
}
```

Replace it with:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Vital-Guard AI Trigger Payload",
  "description": "Wire format for DMS trigger events, delivered as an HTTP JSON body over the local network-pin (GET /latest-trigger). Kept nested (not flattened) because the transport is HTTP JSON, not Android Intent extras — see docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md Decision 4. escalationLevel is top-level (mirrors state) while distraction.escalationLevel is nested (mirrors distraction.state) — intentional mirror of the existing state/distraction.state split, not an inconsistency (see docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 2).",
  "type": "object",
  "required": ["timestampMs", "source", "score", "confidence", "state", "escalationLevel", "features", "reason", "correlationId", "distraction"],
  "properties": {
    "timestampMs": { "type": "integer", "description": "Epoch milliseconds." },
    "source": { "type": "string", "enum": ["container-python", "debug", "replay", "mock-stream"] },
    "score": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
    "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
    "state": { "type": "string", "enum": ["NORMAL", "WARNING", "CRITICAL", "UNKNOWN"] },
    "escalationLevel": { "type": "integer", "minimum": 1, "maximum": 3 },
    "features": {
      "type": "object",
      "required": ["perclos", "eyeOpenProbability", "headEulerAngleX"],
      "properties": {
        "perclos": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "eyeOpenProbability": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "headEulerAngleX": { "type": "number" }
      }
    },
    "reason": { "type": "string" },
    "correlationId": { "type": "string" },
    "distraction": {
      "type": "object",
      "required": ["score", "state", "escalationLevel", "yawDeg", "pitchDeg", "handsVisibility", "handsOnWheel", "reason"],
      "properties": {
        "score": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "state": { "type": "string", "enum": ["NORMAL", "WARNING", "CRITICAL"] },
        "escalationLevel": { "type": "integer", "minimum": 1, "maximum": 3 },
        "yawDeg": { "type": "number" },
        "pitchDeg": { "type": "number" },
        "handsVisibility": { "type": "string", "enum": ["FULL", "PARTIAL", "UNKNOWN"] },
        "handsOnWheel": { "type": "boolean" },
        "reason": { "type": "string" }
      }
    }
  }
}
```

- [ ] **Step 2: Verify it's valid JSON**

Run: `python -c "import json; json.load(open('contracts/trigger.schema.json'))" && echo OK`
Expected: `OK`

(No test asserts against this file in isolation yet — Task 4 Step 2 will run the existing `test_build_trigger_payload_matches_schema` against this new schema and it will fail until Task 4's Python producer code adds the two fields to the payload. That is expected and handled there.)

- [ ] **Step 3: Commit**

```bash
git add contracts/trigger.schema.json
git commit -m "Add escalationLevel (top-level and distraction.escalationLevel) to trigger schema"
```

---

## Task 4: Wire `EscalationTracker` into `main.py`

**Files:**
- Modify: `dms-ai-engine/main.py:78-106` (`build_trigger_payload`), `:109-166` (`run_mock_stream`), `:169-341` (`run_real_video`)
- Modify: `dms-ai-engine/tests/test_main.py`

**Interfaces:**
- Consumes: `EscalationTracker` (Task 1), `TriggerEmitter.critical_active`/`DistractionTriggerEmitter.critical_active` (Task 2), updated schema (Task 3).
- Produces: `build_trigger_payload(..., escalation_level: int, distraction_escalation_level: int)` — 2 new REQUIRED keyword params, added to the returned dict as `"escalationLevel"` (top-level) and `"distraction"["escalationLevel"]`. CSV header gains 2 columns: `"escalation_level"`, `"distraction_escalation_level"`.

- [ ] **Step 1: Update `build_trigger_payload`'s signature and body**

In `dms-ai-engine/main.py`, replace the function at lines 78-106:

```python
def build_trigger_payload(state: str, score: float, confidence: float,
                           perclos: float, eye_open_probability: float,
                           head_euler_angle_x: float, reason: str, source: str,
                           event_counter: int, distraction_score: float, distraction_state: str,
                           yaw_deg: float, pitch_deg: float, hands_visibility: str,
                           hands_on_wheel_flag: bool, distraction_reason: str,
                           escalation_level: int, distraction_escalation_level: int) -> dict:
    return {
        "timestampMs": int(time.time() * 1000),
        "source": source,
        "score": round(score, 3),
        "confidence": round(confidence, 3),
        "state": state,
        "escalationLevel": escalation_level,
        "features": {
            "perclos": round(perclos, 3),
            "eyeOpenProbability": round(eye_open_probability, 3),
            "headEulerAngleX": round(head_euler_angle_x, 3),
        },
        "reason": reason,
        "correlationId": f"vg-{source}-{event_counter:04d}",
        "distraction": {
            "score": round(distraction_score, 3),
            "state": distraction_state,
            "escalationLevel": distraction_escalation_level,
            "yawDeg": round(yaw_deg, 3),
            "pitchDeg": round(pitch_deg, 3),
            "handsVisibility": hands_visibility,
            "handsOnWheel": hands_on_wheel_flag,
            "reason": distraction_reason,
        },
    }
```

- [ ] **Step 2: Update the `run_mock_stream` call site (does not use escalation — pass literal 1s)**

In `dms-ai-engine/main.py`, in `run_mock_stream` (around line 148-156), the existing call:

```python
                    payload = build_trigger_payload(
                        state=emitted_state, score=score, confidence=1.0,
                        perclos=calc.compute_score(), eye_open_probability=(0.0 if frame.eye_closed else 1.0),
                        head_euler_angle_x=frame.head_pitch_deg, reason=reason,
                        source="mock-stream", event_counter=event_counter,
                        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                        hands_visibility="UNKNOWN", hands_on_wheel_flag=False,
                        distraction_reason="mock_stream_no_distraction_signal",
                    )
```

becomes:

```python
                    payload = build_trigger_payload(
                        state=emitted_state, score=score, confidence=1.0,
                        perclos=calc.compute_score(), eye_open_probability=(0.0 if frame.eye_closed else 1.0),
                        head_euler_angle_x=frame.head_pitch_deg, reason=reason,
                        source="mock-stream", event_counter=event_counter,
                        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                        hands_visibility="UNKNOWN", hands_on_wheel_flag=False,
                        distraction_reason="mock_stream_no_distraction_signal",
                        escalation_level=1, distraction_escalation_level=1,
                    )
```

- [ ] **Step 3: Wire escalation into `run_real_video`**

In `dms-ai-engine/main.py`, in the local imports at the top of `run_real_video` (after line 183 `from services.distraction_trigger_emitter import DistractionTriggerEmitter`), add:

```python
    from services.escalation_tracker import EscalationTracker
```

After line 207 (`distraction_emitter = DistractionTriggerEmitter(...)`), add:

```python
    drowsy_escalation = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    distraction_escalation = EscalationTracker(level_up_seconds=[6.0, 12.0], repeat_interval_seconds=[7.0, 5.0, 3.0])
```

Replace the CSV header at lines 224-226:

```python
            writer.writerow(["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal",
                              "yaw_deg", "hands_visibility", "hands_on_wheel", "distraction_score",
                              "distraction_state", "distraction_signal"])
```

with:

```python
            writer.writerow(["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal",
                              "yaw_deg", "hands_visibility", "hands_on_wheel", "distraction_score",
                              "distraction_state", "distraction_signal", "escalation_level",
                              "distraction_escalation_level"])
```

Replace the `face_signal == "UNKNOWN"` branch at lines 244-254:

```python
                face_signal = face_tracker.update(has_face=has_face, now=t)
                if face_signal == "UNKNOWN":
                    event_counter += 1
                    store.update_latest(build_trigger_payload(
                        state="UNKNOWN", score=0.0, confidence=0.0,
                        perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                        reason="lost_face", source="container-python", event_counter=event_counter,
                        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                        distraction_reason="lost_face",
                    ))
```

with:

```python
                face_signal = face_tracker.update(has_face=has_face, now=t)
                if face_signal == "UNKNOWN":
                    drowsy_escalation.reset()
                    distraction_escalation.reset()
                    event_counter += 1
                    store.update_latest(build_trigger_payload(
                        state="UNKNOWN", score=0.0, confidence=0.0,
                        perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                        reason="lost_face", source="container-python", event_counter=event_counter,
                        distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                        distraction_reason="lost_face",
                        escalation_level=1, distraction_escalation_level=1,
                    ))
```

Replace lines 310-329 (the main publish-gate `if` and both `writer.writerow(...)` calls):

```python
                    if signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED"):
                        event_counter += 1
                        store.update_latest(build_trigger_payload(
                            state=state, score=score, confidence=1.0,
                            perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                            head_euler_angle_x=pitch_deg,
                            reason=("sustained_high_score" if signal == "CRITICAL" else "recovered") if signal else "unchanged",
                            source="container-python", event_counter=event_counter,
                            distraction_score=distraction_score, distraction_state=distraction_state,
                            yaw_deg=yaw_deg, pitch_deg=baseline_corrected_pitch,
                            hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                            distraction_reason=("gaze_off_road_or_hands_off_wheel" if distraction_signal == "CRITICAL"
                                                 else "recovered" if distraction_signal == "RECOVERED" else "unchanged"),
                        ))
                    writer.writerow([f"{t:.2f}", 1, f"{score_blink:.3f}", f"{pitch_deg:.1f}", f"{score:.3f}", state, signal or "",
                                      f"{yaw_deg:.1f}", hands_visibility, int(on_wheel),
                                      f"{distraction_score:.3f}", distraction_state, distraction_signal or ""])
                else:
                    writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or "",
                                      "", hands_visibility, int(on_wheel), "", "", ""])
```

with:

```python
                    drowsy_level, drowsy_repeat_due, drowsy_level_changed = drowsy_escalation.update(
                        emitter.critical_active, now=t)
                    distraction_level, distraction_repeat_due, distraction_level_changed = distraction_escalation.update(
                        distraction_emitter.critical_active, now=t)

                    if (signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED")
                            or drowsy_repeat_due or distraction_repeat_due
                            or drowsy_level_changed or distraction_level_changed):
                        event_counter += 1
                        store.update_latest(build_trigger_payload(
                            state=state, score=score, confidence=1.0,
                            perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                            head_euler_angle_x=pitch_deg,
                            reason=("sustained_high_score" if signal == "CRITICAL" else "recovered") if signal else "unchanged",
                            source="container-python", event_counter=event_counter,
                            distraction_score=distraction_score, distraction_state=distraction_state,
                            yaw_deg=yaw_deg, pitch_deg=baseline_corrected_pitch,
                            hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                            distraction_reason=("gaze_off_road_or_hands_off_wheel" if distraction_signal == "CRITICAL"
                                                 else "recovered" if distraction_signal == "RECOVERED" else "unchanged"),
                            escalation_level=drowsy_level, distraction_escalation_level=distraction_level,
                        ))
                    writer.writerow([f"{t:.2f}", 1, f"{score_blink:.3f}", f"{pitch_deg:.1f}", f"{score:.3f}", state, signal or "",
                                      f"{yaw_deg:.1f}", hands_visibility, int(on_wheel),
                                      f"{distraction_score:.3f}", distraction_state, distraction_signal or "",
                                      drowsy_level, distraction_level])
                else:
                    writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or "",
                                      "", hands_visibility, int(on_wheel), "", "", "",
                                      "", ""])
```

- [ ] **Step 4: Fix the existing tests that this signature/CSV change breaks**

In `dms-ai-engine/tests/test_main.py`:

1. `test_build_trigger_payload_matches_schema` (currently lines 239-253) — add the 2 new kwargs so it still calls the real signature, and this test will now also validate the new schema fields for free via `jsonschema.validate`:

```python
def test_build_trigger_payload_matches_schema():
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    payload = build_trigger_payload(
        state="CRITICAL", score=0.91, confidence=1.0,
        perclos=0.8, eye_open_probability=0.1, head_euler_angle_x=28.0,
        reason="sustained_high_score", source="mock-stream", event_counter=1,
        distraction_score=0.75, distraction_state="CRITICAL", yaw_deg=35.0, pitch_deg=5.0,
        hands_visibility="PARTIAL", hands_on_wheel_flag=False, distraction_reason="gaze_off_road",
        escalation_level=2, distraction_escalation_level=1,
    )
    jsonschema.validate(payload, schema)
    assert payload["state"] == "CRITICAL"
    assert payload["correlationId"] == "vg-mock-stream-0001"
    assert payload["features"]["perclos"] == 0.8
    assert payload["distraction"]["state"] == "CRITICAL"
    assert payload["distraction"]["handsOnWheel"] is False
    assert payload["escalationLevel"] == 2
    assert payload["distraction"]["escalationLevel"] == 1
```

2. `test_run_real_video_processes_has_face_frames_end_to_end` (currently lines 371-419) — update the exact header assertion and row-unpacking to account for 2 new columns:

```python
    assert header.split(",") == ["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal",
                                  "yaw_deg", "hands_visibility", "hands_on_wheel", "distraction_score",
                                  "distraction_state", "distraction_signal", "escalation_level",
                                  "distraction_escalation_level"]
    assert len(data_rows) == len(frames)

    for row in data_rows:
        (ts, has_face, blink_score_col, head_pitch, score, state, signal,
         yaw_deg, hands_visibility, on_wheel, distraction_score, distraction_state,
         distraction_signal, escalation_level, distraction_escalation_level) = row.split(",")
        assert has_face == "1"
        assert escalation_level == "1", "no CRITICAL episode occurs in this test -- level must stay 1"
        assert distraction_escalation_level == "1"
```

(Only the two blocks shown above change in this test; the rest of the test body is unchanged.)

3. `test_run_real_video_emits_unknown_after_sustained_lost_face` (currently lines 333-368) — the spec (Section 6) calls for an exact-count assertion instead of `any(...)`, to actually verify the face-loss path does not flood. Replace the final assertion:

```python
    assert any(p["state"] == "UNKNOWN" and p["reason"] == "lost_face" for p in served), (
        "sustained lost-face (3s of no-face frames) must emit an UNKNOWN/lost_face payload"
    )
```

with:

```python
    unknown_payloads = [p for p in served if p["state"] == "UNKNOWN" and p["reason"] == "lost_face"]
    assert len(unknown_payloads) == 1, (
        f"sustained lost-face (3s of no-face frames) must emit exactly ONE UNKNOWN/lost_face payload "
        f"(FacePresenceTracker is edge-only, see test_dms.py:143-148), got {len(unknown_payloads)}"
    )
    assert unknown_payloads[0]["escalationLevel"] == 1
    assert unknown_payloads[0]["distraction"]["escalationLevel"] == 1
```

- [ ] **Step 5: Write a new integration test for escalation over time**

Append to `dms-ai-engine/tests/test_main.py`:

```python
def test_run_real_video_escalates_and_repeats_while_critical_does_not_recover(tmp_path, monkeypatch):
    """End-to-end proof that a sustained drowsy episode (eyes closed, head
    drooped, never recovering) produces MORE than one CRITICAL-state payload
    over time, with escalationLevel increasing -- the exact behavior this
    plan adds.

    Uses `_fake_landmarker_with_time_varying_pitch` (constant pitch for the
    first second, then a sustained droop), NOT
    `_fake_landmarker_with_face_detected`'s constant pitch -- a truly
    constant pitch throughout would be entirely cancelled out by
    calibrate_baseline() (baseline_pitch_deg converges to that same
    constant), capping the score at ~0.80 and never reaching the 0.85
    CRITICAL threshold. This is the exact ceiling bug documented in
    CV_REMEDIATION_RESULTS.md -- reproducing it here by accident would make
    this new test silently never exercise escalation at all."""
    import cv2
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    fps = 30.0
    calibration_frames = int(1.0 * fps)  # matches BASELINE_CALIBRATION_SECONDS
    # 30s of continuous frames -- long enough to observe level 1 -> 2 (+8s)
    # -> 3 (+16s) and at least one post-level-3 repeat (interval[2]=4.0s),
    # measured from whenever CRITICAL first fires (a few seconds in, once
    # sustain_seconds=2.0 elapses past the calibration window).
    frames = [frame] * int(30.0 * fps)
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture(frames, fps=fps))
    monkeypatch.setattr(
        "services.face_landmarker_client.build_video_mode_landmarker",
        lambda model_path: _fake_landmarker_with_time_varying_pitch(
            calibration_frames=calibration_frames,
            calibration_pitch_deg=0.0,
            drooped_pitch_deg=30.0,
            blink_left=0.9, blink_right=0.9,
        ),
    )
    monkeypatch.setattr(
        "services.hand_tracker.build_video_mode_hand_landmarker",
        lambda model_path: _fake_hand_landmarker_with_hands_at(),
    )

    served = []
    original_update_latest = main_module.LatestTriggerStore.update_latest

    def spy_update_latest(self, payload):
        served.append(payload)
        return original_update_latest(self, payload)

    monkeypatch.setattr(main_module.LatestTriggerStore, "update_latest", spy_update_latest)

    main_module.run_real_video("does-not-matter.mp4", tmp_path / "out.csv", host="127.0.0.1", port=0)

    critical_payloads = [p for p in served if p["state"] == "CRITICAL"]
    assert len(critical_payloads) > 1, (
        "a 30s continuous CRITICAL episode must produce more than the original "
        "edge payload -- escalation repeats/level-changes must also publish"
    )
    levels_seen = sorted(set(p["escalationLevel"] for p in critical_payloads))
    assert levels_seen == [1, 2, 3], f"expected to observe all 3 levels over 30s, got {levels_seen}"
```

- [ ] **Step 6: Run the full Python test suite**

Run: `cd dms-ai-engine && python -m pytest -v`
Expected: all tests pass, 0 failures

- [ ] **Step 7: Commit**

```bash
git add dms-ai-engine/main.py dms-ai-engine/tests/test_main.py
git commit -m "Wire EscalationTracker into run_real_video's publish gate and CSV logging"
```

---

## Task 5: Mirror escalation into `measure_latency.py`

**Files:**
- Modify: `dms-ai-engine/measure_latency.py`

**Interfaces:**
- Consumes: `EscalationTracker` (Task 1), `TriggerEmitter.critical_active`/`DistractionTriggerEmitter.critical_active` (Task 2), `build_trigger_payload`'s new signature (Task 4).

This file exists specifically to mirror `run_real_video()`'s FULL per-frame body for latency measurement (see its own module docstring, and the project convention established when the distraction path was first mirrored here) — it must not fall out of sync with `main.py`'s new escalation logic.

- [ ] **Step 1: Add the import and tracker construction**

In `dms-ai-engine/measure_latency.py`, change the import block (currently lines 34-37):

```python
from main import (
    build_trigger_payload, _state_for_score,
    BASELINE_CALIBRATION_SECONDS, PITCH_OFF_ROAD_THRESHOLD, YAW_OFF_ROAD_THRESHOLD,
)
```

to:

```python
from services.escalation_tracker import EscalationTracker
from main import (
    build_trigger_payload, _state_for_score,
    BASELINE_CALIBRATION_SECONDS, PITCH_OFF_ROAD_THRESHOLD, YAW_OFF_ROAD_THRESHOLD,
)
```

After line 52 (`distraction_emitter = DistractionTriggerEmitter(...)`), add:

```python
    drowsy_escalation = EscalationTracker(level_up_seconds=[8.0, 16.0], repeat_interval_seconds=[10.0, 5.0, 4.0])
    distraction_escalation = EscalationTracker(level_up_seconds=[6.0, 12.0], repeat_interval_seconds=[7.0, 5.0, 3.0])
```

- [ ] **Step 2: Update the UNKNOWN branch (lines 94-103)**

Replace:

```python
            face_signal = face_tracker.update(has_face=has_face, now=t)
            if face_signal == "UNKNOWN":
                event_counter += 1
                store.update_latest(build_trigger_payload(
                    state="UNKNOWN", score=0.0, confidence=0.0,
                    perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                    reason="lost_face", source="latency-check", event_counter=event_counter,
                    distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                    hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                    distraction_reason="lost_face",
                ))
```

with:

```python
            face_signal = face_tracker.update(has_face=has_face, now=t)
            if face_signal == "UNKNOWN":
                drowsy_escalation.reset()
                distraction_escalation.reset()
                event_counter += 1
                store.update_latest(build_trigger_payload(
                    state="UNKNOWN", score=0.0, confidence=0.0,
                    perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                    reason="lost_face", source="latency-check", event_counter=event_counter,
                    distraction_score=0.0, distraction_state="NORMAL", yaw_deg=0.0, pitch_deg=0.0,
                    hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                    distraction_reason="lost_face",
                    escalation_level=1, distraction_escalation_level=1,
                ))
```

- [ ] **Step 3: Update the main publish-gate branch (lines 147-160)**

Replace:

```python
                if signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED"):
                    event_counter += 1
                    store.update_latest(build_trigger_payload(
                        state=state, score=score, confidence=1.0,
                        perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                        head_euler_angle_x=pitch_deg,
                        reason=("sustained_high_score" if signal == "CRITICAL" else "recovered") if signal else "unchanged",
                        source="latency-check", event_counter=event_counter,
                        distraction_score=distraction_score, distraction_state=distraction_state,
                        yaw_deg=yaw_deg, pitch_deg=baseline_corrected_pitch,
                        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                        distraction_reason=("gaze_off_road_or_hands_off_wheel" if distraction_signal == "CRITICAL"
                                             else "recovered" if distraction_signal == "RECOVERED" else "unchanged"),
                    ))
```

with:

```python
                drowsy_level, drowsy_repeat_due, drowsy_level_changed = drowsy_escalation.update(
                    emitter.critical_active, now=t)
                distraction_level, distraction_repeat_due, distraction_level_changed = distraction_escalation.update(
                    distraction_emitter.critical_active, now=t)

                if (signal in ("CRITICAL", "RECOVERED") or distraction_signal in ("CRITICAL", "RECOVERED")
                        or drowsy_repeat_due or distraction_repeat_due
                        or drowsy_level_changed or distraction_level_changed):
                    event_counter += 1
                    store.update_latest(build_trigger_payload(
                        state=state, score=score, confidence=1.0,
                        perclos=calc.compute_score(), eye_open_probability=(1.0 - score_blink),
                        head_euler_angle_x=pitch_deg,
                        reason=("sustained_high_score" if signal == "CRITICAL" else "recovered") if signal else "unchanged",
                        source="latency-check", event_counter=event_counter,
                        distraction_score=distraction_score, distraction_state=distraction_state,
                        yaw_deg=yaw_deg, pitch_deg=baseline_corrected_pitch,
                        hands_visibility=hands_visibility, hands_on_wheel_flag=on_wheel,
                        distraction_reason=("gaze_off_road_or_hands_off_wheel" if distraction_signal == "CRITICAL"
                                             else "recovered" if distraction_signal == "RECOVERED" else "unchanged"),
                        escalation_level=drowsy_level, distraction_escalation_level=distraction_level,
                    ))
```

- [ ] **Step 4: Verify the module still imports and runs its own smoke path**

Run: `cd dms-ai-engine && python -c "import measure_latency; print('OK')"`
Expected: `OK` (import succeeds; no syntax/reference errors)

Run the full suite once more to make sure nothing else broke: `cd dms-ai-engine && python -m pytest -v`
Expected: all pass

- [ ] **Step 5: Commit**

```bash
git add dms-ai-engine/measure_latency.py
git commit -m "Mirror EscalationTracker wiring into measure_latency.py"
```

---

## Task 6: `TriggerPayload.kt` — add `escalationLevel` fields, fix every call site

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt:23-32`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt:20-29`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt:24-32,44-51,59-66,69-76`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPayloadTest.kt:13-28,39-50`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt:19-27`

**Interfaces:**
- Produces: `TriggerPayload.escalationLevel: Int` (new, no default — every construction site must supply it), `DistractionInfo.escalationLevel: Int` (new, no default). Task 10/11 consume `payload.escalationLevel`/`payload.distraction.escalationLevel`.

No Gradle wrapper exists in this repo/environment (confirmed repeatedly across every prior Kotlin task in this project) — every step below is verified by grep + hand-reading the resulting file, not by a compiler. Grep for `TriggerPayload(` and `DistractionInfo(` across the whole `aaos-cockpit-app` tree before considering this task done, in case a call site exists beyond the 6 files listed above.

- [ ] **Step 1: Add the fields to `TriggerPayload.kt`**

Replace the current `DistractionInfo` and `TriggerPayload` data classes:

```kotlin
@Serializable
data class DistractionInfo(
    val score: Float,
    val state: String,
    val yawDeg: Float,
    val pitchDeg: Float,
    val handsVisibility: String,
    val handsOnWheel: Boolean,
    val reason: String
) {
    companion object {
        const val VISIBILITY_FULL = "FULL"
        const val VISIBILITY_PARTIAL = "PARTIAL"
        const val VISIBILITY_UNKNOWN = "UNKNOWN"
    }
}

@Serializable
data class TriggerPayload(
    val timestampMs: Long,
    val source: String,
    val score: Float,
    val confidence: Float,
    val state: String,
    val features: TriggerFeatures,
    val reason: String,
    val correlationId: String,
    val distraction: DistractionInfo
) {
```

with:

```kotlin
@Serializable
data class DistractionInfo(
    val score: Float,
    val state: String,
    val escalationLevel: Int,
    val yawDeg: Float,
    val pitchDeg: Float,
    val handsVisibility: String,
    val handsOnWheel: Boolean,
    val reason: String
) {
    companion object {
        const val VISIBILITY_FULL = "FULL"
        const val VISIBILITY_PARTIAL = "PARTIAL"
        const val VISIBILITY_UNKNOWN = "UNKNOWN"
    }
}

@Serializable
data class TriggerPayload(
    val timestampMs: Long,
    val source: String,
    val score: Float,
    val confidence: Float,
    val state: String,
    val escalationLevel: Int,
    val features: TriggerFeatures,
    val reason: String,
    val correlationId: String,
    val distraction: DistractionInfo
) {
```

(The `companion object { const val STATE_... }` block below is unchanged.)

- [ ] **Step 2: Fix `DrowsinessControllerTest.kt`'s `payload()` helper**

Replace (current lines 23-32):

```kotlin
    private fun payload(state: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
        state = state,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test"
        ),
    )
```

with:

```kotlin
    private fun payload(state: String, correlationId: String, escalationLevel: Int = 1) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
        state = state, escalationLevel = escalationLevel,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test"
        ),
    )
```

(Adding `escalationLevel` as a defaulted parameter on the test helper — not on `TriggerPayload` itself — keeps every existing call in this file compiling unchanged while giving Task 10's new tests a way to pass a specific level.)

- [ ] **Step 3: Fix `DistractionControllerTest.kt`'s `payload()` helper**

Replace (current lines 20-29):

```kotlin
    private fun payload(distractionState: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.1f, confidence = 1.0f,
        state = TriggerPayload.STATE_NORMAL,
        features = TriggerFeatures(perclos = 0.0f, eyeOpenProbability = 1.0f, headEulerAngleX = 0.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.9f, state = distractionState, yawDeg = 45.0f, pitchDeg = 5.0f,
            handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
        ),
    )
```

with:

```kotlin
    private fun payload(distractionState: String, correlationId: String, escalationLevel: Int = 1) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.1f, confidence = 1.0f,
        state = TriggerPayload.STATE_NORMAL, escalationLevel = 1,
        features = TriggerFeatures(perclos = 0.0f, eyeOpenProbability = 1.0f, headEulerAngleX = 0.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.9f, state = distractionState, escalationLevel = escalationLevel, yawDeg = 45.0f, pitchDeg = 5.0f,
            handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
        ),
    )
```

- [ ] **Step 4: Fix `AlertArbiterIntegrationTest.kt`'s 4 constructor call sites**

Replace `drowsinessPayload()` (current lines 24-32):

```kotlin
    private fun drowsinessPayload(state: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f, state = state,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test",
        ),
    )
```

with:

```kotlin
    private fun drowsinessPayload(state: String, correlationId: String) = TriggerPayload(
        timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f, state = state, escalationLevel = 1,
        features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
        reason = "test", correlationId = correlationId,
        distraction = DistractionInfo(
            score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
            handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test",
        ),
    )
```

Then, in the same file, each of the 3 inline `DistractionInfo(...)` constructions inside `.copy(distraction = ...)` calls (current lines 46-49, 61-64, 71-74) needs `escalationLevel = 1` added. All 3 currently look like one of these two shapes:

```kotlin
                    distraction = DistractionInfo(
                        score = 0.9f, state = TriggerPayload.STATE_CRITICAL, yawDeg = 45.0f, pitchDeg = 5.0f,
                        handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                    )
```
```kotlin
                    distraction = DistractionInfo(
                        score = 0.1f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
                        handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = true, reason = "test",
                    )
```

Add `escalationLevel = 1,` right after the `state = ...,` argument in each of the 3 occurrences, e.g. the first becomes:

```kotlin
                    distraction = DistractionInfo(
                        score = 0.9f, state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1, yawDeg = 45.0f, pitchDeg = 5.0f,
                        handsVisibility = DistractionInfo.VISIBILITY_FULL, handsOnWheel = false, reason = "test",
                    )
```

(Apply the identical one-argument insertion to the other 2 occurrences, keeping every other argument and value unchanged.)

- [ ] **Step 5: Fix `TriggerPayloadTest.kt`'s 2 raw JSON fixtures**

Replace the first raw string (current lines 13-28):

```kotlin
        val raw = """
            {
              "timestampMs": 1700000000000,
              "source": "container-python",
              "score": 0.91,
              "confidence": 1.0,
              "state": "CRITICAL",
              "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
              "reason": "sustained_high_score",
              "correlationId": "vg-critical-0001",
              "distraction": {
                "score": 0.0, "state": "NORMAL", "yawDeg": 0.0, "pitchDeg": 0.0,
                "handsVisibility": "FULL", "handsOnWheel": true, "reason": "none"
              }
            }
        """.trimIndent()
```

with:

```kotlin
        val raw = """
            {
              "timestampMs": 1700000000000,
              "source": "container-python",
              "score": 0.91,
              "confidence": 1.0,
              "state": "CRITICAL",
              "escalationLevel": 1,
              "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
              "reason": "sustained_high_score",
              "correlationId": "vg-critical-0001",
              "distraction": {
                "score": 0.0, "state": "NORMAL", "escalationLevel": 1, "yawDeg": 0.0, "pitchDeg": 0.0,
                "handsVisibility": "FULL", "handsOnWheel": true, "reason": "none"
              }
            }
        """.trimIndent()
```

Replace the second raw string (current lines 39-50):

```kotlin
        val raw = """
            {
              "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
              "state": "NORMAL",
              "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
              "reason": "test", "correlationId": "vg-test-0001",
              "distraction": {
                "score": 0.9, "state": "CRITICAL", "yawDeg": 45.0, "pitchDeg": 5.0,
                "handsVisibility": "FULL", "handsOnWheel": true, "reason": "gaze_off_road"
              }
            }
        """.trimIndent()
```

with:

```kotlin
        val raw = """
            {
              "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
              "state": "NORMAL", "escalationLevel": 1,
              "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
              "reason": "test", "correlationId": "vg-test-0001",
              "distraction": {
                "score": 0.9, "state": "CRITICAL", "escalationLevel": 2, "yawDeg": 45.0, "pitchDeg": 5.0,
                "handsVisibility": "FULL", "handsOnWheel": true, "reason": "gaze_off_road"
              }
            }
        """.trimIndent()
```

And add one assertion to the same test (`` `deserializes the distraction object correctly` ``) confirming the new field actually deserializes, right after the existing `assertTrue(payload.distraction.handsOnWheel)` line:

```kotlin
        assertEquals(2, payload.distraction.escalationLevel)
```

- [ ] **Step 6: Fix `TriggerPollClientTest.kt`'s `samplePayload()` helper**

Replace (current lines 19-27):

```kotlin
private fun samplePayload(id: String) = TriggerPayload(
    timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
    state = TriggerPayload.STATE_CRITICAL,
    features = TriggerFeatures(0.8f, 0.1f, 28.0f), reason = "test", correlationId = id,
    distraction = DistractionInfo(
        score = 0.0f, state = TriggerPayload.STATE_NORMAL, yawDeg = 0.0f, pitchDeg = 0.0f,
        handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test"
    ),
)
```

with:

```kotlin
private fun samplePayload(id: String) = TriggerPayload(
    timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
    state = TriggerPayload.STATE_CRITICAL, escalationLevel = 1,
    features = TriggerFeatures(0.8f, 0.1f, 28.0f), reason = "test", correlationId = id,
    distraction = DistractionInfo(
        score = 0.0f, state = TriggerPayload.STATE_NORMAL, escalationLevel = 1, yawDeg = 0.0f, pitchDeg = 0.0f,
        handsVisibility = DistractionInfo.VISIBILITY_UNKNOWN, handsOnWheel = false, reason = "test"
    ),
)
```

- [ ] **Step 7: Grep for any other call site**

Run: `grep -rn "TriggerPayload(\|DistractionInfo(" aaos-cockpit-app/app/src/`
Expected: only the 6 files touched above (plus `TriggerPayload.kt`'s own class definition) appear. If any other file appears, apply the same one-argument insertion pattern to it and note it in the commit message.

- [ ] **Step 8: Hand-trace verification (no Gradle wrapper available)**

Re-read the full diff of every file touched in this task and confirm: every `TriggerPayload(` call now has an `escalationLevel = ` argument, every `DistractionInfo(` call now has an `escalationLevel = ` argument, and no other argument or assertion in any of these test files was altered beyond what's specified above.

- [ ] **Step 9: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterIntegrationTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPayloadTest.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt
git commit -m "Add escalationLevel to TriggerPayload/DistractionInfo, fix every call site"
```

---

## Task 7: Thread `level: Int` through `VoiceAlertGateway` and `AlertArbiter`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertArbiter.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterTest.kt`

**Interfaces:**
- Consumes: nothing new from earlier tasks (Task 9 will later make `RealVoiceAlertGateway` actually use the level; this task only threads the parameter through the interface/Fake/Arbiter).
- Produces: `VoiceAlertGateway.triggerAlert(level: Int)`, `VoiceAlertGateway.triggerDistractionReminder(level: Int)`, `AlertArbiter.requestVoiceAlert(source: AlertSource, level: Int)`. Task 9, 10, 11 consume these.

- [ ] **Step 1: Update `VoiceAlertGateway.kt`'s interface and both implementations**

Replace the whole file's interface/Fake block:

```kotlin
interface VoiceAlertGateway {
    fun triggerAlert()
    fun triggerDistractionReminder()
    fun stopAlert()
}

class FakeVoiceAlertGateway : VoiceAlertGateway {
    var alertTriggered: Boolean = false
    var distractionReminderTriggered: Boolean = false
    var stopCalled: Boolean = false
    var throwOnTrigger: Boolean = false
    var throwOnStop: Boolean = false

    /** Records call order (e.g. "stopAlert", "triggerAlert") so handoff-ordering tests can assert on it. */
    val callLog: MutableList<String> = mutableListOf()

    override fun triggerAlert() {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        alertTriggered = true
        callLog.add("triggerAlert")
    }

    override fun triggerDistractionReminder() {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        distractionReminderTriggered = true
        callLog.add("triggerDistractionReminder")
    }

    override fun stopAlert() {
        if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
        stopCalled = true
        callLog.add("stopAlert")
    }
}

/** Real implementation — wraps the existing VoiceEmergencyAssistant unchanged. */
class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway {
    private val assistant = VoiceEmergencyAssistant(context)

    override fun triggerAlert() {
        assistant.executeVoiceIntervention()
    }

    override fun triggerDistractionReminder() {
        assistant.executeDistractionReminder()
    }

    override fun stopAlert() {
        assistant.releaseFocus()
    }
}
```

with:

```kotlin
interface VoiceAlertGateway {
    fun triggerAlert(level: Int)
    fun triggerDistractionReminder(level: Int)
    fun stopAlert()
}

class FakeVoiceAlertGateway : VoiceAlertGateway {
    var alertTriggered: Boolean = false
    var distractionReminderTriggered: Boolean = false
    var stopCalled: Boolean = false
    var throwOnTrigger: Boolean = false
    var throwOnStop: Boolean = false
    var lastAlertLevel: Int? = null
    var lastDistractionReminderLevel: Int? = null

    /** Records call order (e.g. "stopAlert", "triggerAlert") so handoff-ordering tests can assert on it. */
    val callLog: MutableList<String> = mutableListOf()

    override fun triggerAlert(level: Int) {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        alertTriggered = true
        lastAlertLevel = level
        callLog.add("triggerAlert")
    }

    override fun triggerDistractionReminder(level: Int) {
        if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
        distractionReminderTriggered = true
        lastDistractionReminderLevel = level
        callLog.add("triggerDistractionReminder")
    }

    override fun stopAlert() {
        if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
        stopCalled = true
        callLog.add("stopAlert")
    }
}

/** Real implementation — wraps the existing VoiceEmergencyAssistant unchanged. */
class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway {
    private val assistant = VoiceEmergencyAssistant(context)

    override fun triggerAlert(level: Int) {
        assistant.executeVoiceIntervention(level)
    }

    override fun triggerDistractionReminder(level: Int) {
        assistant.executeDistractionReminder(level)
    }

    override fun stopAlert() {
        assistant.releaseFocus()
    }
}
```

- [ ] **Step 2: Update `AlertArbiter.kt`**

Replace:

```kotlin
    fun requestVoiceAlert(source: AlertSource) {
        if (source == AlertSource.DISTRACTION && drowsinessCriticalActive) {
            Log.i(TAG, "Suppressed distraction alert -- drowsiness CRITICAL has priority")
            return
        }
        val outgoingOwner = activeSpeaker
        if (outgoingOwner != null && outgoingOwner != source) {
            Log.i(TAG, "Handing off active speaker from $outgoingOwner to $source -- stopping outgoing owner first")
            voiceAlertGateway.stopAlert()
        }
        activeSpeaker = source
        when (source) {
            AlertSource.DROWSINESS -> voiceAlertGateway.triggerAlert()
            AlertSource.DISTRACTION -> voiceAlertGateway.triggerDistractionReminder()
        }
    }
```

with:

```kotlin
    fun requestVoiceAlert(source: AlertSource, level: Int) {
        if (source == AlertSource.DISTRACTION && drowsinessCriticalActive) {
            Log.i(TAG, "Suppressed distraction alert -- drowsiness CRITICAL has priority")
            return
        }
        val outgoingOwner = activeSpeaker
        if (outgoingOwner != null && outgoingOwner != source) {
            Log.i(TAG, "Handing off active speaker from $outgoingOwner to $source -- stopping outgoing owner first")
            voiceAlertGateway.stopAlert()
        }
        activeSpeaker = source
        when (source) {
            AlertSource.DROWSINESS -> voiceAlertGateway.triggerAlert(level)
            AlertSource.DISTRACTION -> voiceAlertGateway.triggerDistractionReminder(level)
        }
    }
```

(`stopAlert(source: AlertSource)` below is unchanged — it never called `triggerAlert`/`triggerDistractionReminder`.)

- [ ] **Step 3: Fix `AlertArbiterTest.kt`'s call sites**

Every `arbiter.requestVoiceAlert(AlertSource.DROWSINESS)` / `arbiter.requestVoiceAlert(AlertSource.DISTRACTION)` call in this file (current lines 22-23, 31, 39-40, 49, 59, 65) needs a `level` argument. Since the test doesn't care about specific level values (it's testing arbitration, not escalation), use `1` for all of them. Concretely, replace every occurrence of:

```kotlin
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS)
```
with:
```kotlin
        arbiter.requestVoiceAlert(AlertSource.DROWSINESS, 1)
```
and every occurrence of:
```kotlin
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION)
```
with:
```kotlin
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 1)
```

(Do not guess the count — run `grep -n "requestVoiceAlert(AlertSource" aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterTest.kt` first, apply the substitution to every line it lists, then re-run the same grep and confirm zero lines remain without a second argument.)

- [ ] **Step 4: Add a new test proving `level` actually reaches the gateway**

Append to `AlertArbiterTest.kt`:

```kotlin
    @Test
    fun `requestVoiceAlert passes the level through to the gateway unchanged`() {
        arbiter.requestVoiceAlert(AlertSource.DISTRACTION, 3)

        assertEquals(3, voice.lastDistractionReminderLevel)
    }
```

- [ ] **Step 5: Hand-trace verification and commit**

Re-read the full diff of `AlertArbiter.kt`, `VoiceAlertGateway.kt`, and `AlertArbiterTest.kt` to confirm every call site compiles conceptually (correct argument count/order) — no Gradle wrapper exists in this environment to compile-check.

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/AlertArbiter.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/AlertArbiterTest.kt
git commit -m "Thread escalation level through VoiceAlertGateway and AlertArbiter"
```

---

## Task 8: `ClimateActuatorGateway.kt` — level-to-temperature mapping

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt`

**Interfaces:**
- Produces: `ClimateActuatorGateway.applyDrowsinessOverride(level: Int)` (was no-arg). `BridgeClimateActuatorGateway` also implements this interface and must be updated even though this feature doesn't otherwise touch it. Task 10 consumes this.

- [ ] **Step 1: Update the interface, Fake, Real, and Bridge implementations**

Replace the interface + `FakeClimateActuatorGateway`:

```kotlin
interface ClimateActuatorGateway {
    fun applyDrowsinessOverride()
    fun revertToBaseline()
}

class FakeClimateActuatorGateway : ClimateActuatorGateway {
    var overrideApplied: Boolean = false
    var revertCalled: Boolean = false
    var throwOnApply: Boolean = false
    var throwOnRevert: Boolean = false

    override fun applyDrowsinessOverride() {
        if (throwOnApply) throw IllegalStateException("simulated climate gateway failure")
        overrideApplied = true
    }

    override fun revertToBaseline() {
        if (throwOnRevert) throw IllegalStateException("simulated climate gateway revert failure")
        revertCalled = true
    }
}
```

with:

```kotlin
interface ClimateActuatorGateway {
    fun applyDrowsinessOverride(level: Int)
    fun revertToBaseline()
}

class FakeClimateActuatorGateway : ClimateActuatorGateway {
    var overrideApplied: Boolean = false
    var revertCalled: Boolean = false
    var throwOnApply: Boolean = false
    var throwOnRevert: Boolean = false
    var lastAppliedLevel: Int? = null

    override fun applyDrowsinessOverride(level: Int) {
        if (throwOnApply) throw IllegalStateException("simulated climate gateway failure")
        overrideApplied = true
        lastAppliedLevel = level
    }

    override fun revertToBaseline() {
        if (throwOnRevert) throw IllegalStateException("simulated climate gateway revert failure")
        revertCalled = true
    }
}
```

Replace `RealClimateActuatorGateway`'s companion object and `applyDrowsinessOverride`:

```kotlin
    companion object {
        private const val FALLBACK_AREA_ID = 1
        private const val FALLBACK_FAN_SPEED = 7
        private const val COLD_TEMPERATURE_C = 20.0f
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f
    }

    override fun applyDrowsinessOverride() {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                @Suppress("DEPRECATION")
                val maxFanSpeed = (config?.getMaxValue(area) as? Int) ?: FALLBACK_FAN_SPEED
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, maxFanSpeed)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, COLD_TEMPERATURE_C)
            }
            Log.d(TAG, "Climate override applied: AC=ON, Fan=max, Temp=${COLD_TEMPERATURE_C}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply VHAL climate override: ${t.message}")
            throw t
        }
    }
```

with:

```kotlin
    companion object {
        private const val FALLBACK_AREA_ID = 1
        private const val FALLBACK_FAN_SPEED = 7
        private const val BASELINE_FAN_SPEED = 2
        private const val BASELINE_TEMPERATURE_C = 25.0f

        // Level->temperature mapping is Kotlin-owned -- Python only ever sends
        // an escalationLevel int, never a real actuation value (see design doc
        // docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3).
        // Level 1 == the pre-escalation baseline behavior (was COLD_TEMPERATURE_C).
        private val TEMPERATURE_C_BY_LEVEL = mapOf(1 to 20.0f, 2 to 17.0f, 3 to 16.0f)
        private const val FALLBACK_TEMPERATURE_C = 20.0f // used if level is somehow outside 1-3

        private fun temperatureCFor(level: Int): Float = TEMPERATURE_C_BY_LEVEL[level] ?: FALLBACK_TEMPERATURE_C
    }

    override fun applyDrowsinessOverride(level: Int) {
        try {
            val car = Car.createCar(context)
            val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            val targetTemperatureC = temperatureCFor(level)

            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, true)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_FAN_SPEED)
                @Suppress("DEPRECATION")
                val maxFanSpeed = (config?.getMaxValue(area) as? Int) ?: FALLBACK_FAN_SPEED
                carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, maxFanSpeed)
            }
            forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                val config = carPropertyManager.getCarPropertyConfig(VehiclePropertyIds.HVAC_TEMPERATURE_SET)
                @Suppress("DEPRECATION")
                val minTemperatureC = (config?.getMinValue(area) as? Float)
                val clampedTemperatureC = if (minTemperatureC != null && targetTemperatureC < minTemperatureC) {
                    Log.w(TAG, "Level $level target ${targetTemperatureC}C below config min ${minTemperatureC}C for area $area -- clamping")
                    minTemperatureC
                } else {
                    targetTemperatureC
                }
                carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, clampedTemperatureC)
            }
            Log.d(TAG, "Climate override applied at level $level: AC=ON, Fan=max, Temp=${targetTemperatureC}C")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply VHAL climate override at level $level: ${t.message}")
            throw t
        }
    }
```

(`revertToBaseline()` below this is unchanged — it always reverts to the fixed `BASELINE_FAN_SPEED`/`BASELINE_TEMPERATURE_C`, independent of `level`, per spec Section 2's "Nhiệt độ per-level KHÔNG sống ở Python" and the existing `revertToBaseline()` contract.)

Replace `BridgeClimateActuatorGateway`'s method signature (it still just fires a broadcast, no logic change needed beyond matching the interface):

```kotlin
class BridgeClimateActuatorGateway(private val context: Context) : ClimateActuatorGateway {
    override fun applyDrowsinessOverride() {
        context.sendBroadcast(Intent("com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"))
    }

    override fun revertToBaseline() {
        context.sendBroadcast(Intent("com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"))
    }
}
```

with:

```kotlin
class BridgeClimateActuatorGateway(private val context: Context) : ClimateActuatorGateway {
    override fun applyDrowsinessOverride(level: Int) {
        context.sendBroadcast(Intent("com.vitalguard.ai.bridge.APPLY_HVAC_OVERRIDE"))
    }

    override fun revertToBaseline() {
        context.sendBroadcast(Intent("com.vitalguard.ai.bridge.REVERT_HVAC_BASELINE"))
    }
}
```

- [ ] **Step 2: Grep for any other caller of `applyDrowsinessOverride`**

Run: `grep -rn "applyDrowsinessOverride" aaos-cockpit-app/app/src/`
Expected at this point: `ClimateActuatorGateway.kt`'s 3 implementations (now `level: Int`), plus two callers that still pass no argument and will not compile until later tasks fix them — `DrowsinessController.kt` (fixed in Task 10) and `ClimateOverrideReceiver.kt:28` (fixed in Task 9 Step 3, which runs after this task in the plan's order — do not fix it here, Task 9 also fixes this same file's `executeVoiceIntervention()` call in the same edit). Confirm you see exactly these two not-yet-fixed callers and no others; if a third appears, it needs the same treatment and should be called out in this task's commit message.

- [ ] **Step 3: Commit**

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt
git commit -m "Add level-to-temperature mapping to ClimateActuatorGateway"
```

---

## Task 9: `VoiceEmergencyAssistant.kt` — level-to-message mapping + utterance-cutoff logging

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt`

**Interfaces:**
- Produces: `VoiceEmergencyAssistant.executeVoiceIntervention(level: Int)`, `VoiceEmergencyAssistant.executeDistractionReminder(level: Int)`. Consumed by `RealVoiceAlertGateway` (already wired in Task 7).

This task also adds the `UtteranceProgressListener` safety net decided in spec Section 3 ("Ràng buộc thời lượng TTS", item 4) — pure logging, no control-flow change.

- [ ] **Step 1: Replace the file's message-speaking logic**

Replace `executeVoiceIntervention`/`speakAlert`:

```kotlin
    fun executeVoiceIntervention() {
        // 1. Cấu hình Audio Attributes với độ ưu tiên cao nhất (ASSISTANT / EMERGENCY)
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // 2. Thiết lập yêu cầu cướp tiêu điểm âm thanh độc quyền (Mute tất cả nhạc của xe)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Audio focus state changed to: $focusChange")
            }
            .build()

        // 3. Gửi yêu cầu lên hệ thống âm thanh buồng lái
        val result = audioManager.requestAudioFocus(focusRequest!!)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "🔇 Audio Focus Obtained! Vehicle Media Muted.")
            speakAlert()
        } else {
            Log.e(TAG, "❌ Audio Focus Request Denied.")
        }
    }

    private fun speakAlert() {
        val alertText = "Warning! Drowsiness detected! Climate safety mode engaged. Please stay awake. Shall I guide you to the nearest rest stop?"
        tts?.speak(alertText, TextToSpeech.QUEUE_FLUSH, null, "EMERGENCY_ALERT")
        Log.i(TAG, "🗣️ Speaking Alert: '$alertText'")
    }

    fun executeDistractionReminder() {
        // Lighter than executeVoiceIntervention()'s _EXCLUSIVE request -- a brief
        // distraction reminder shouldn't seize/mute all cabin audio the way a
        // sustained drowsiness alert does. Placeholder wording/focus behavior,
        // pending Tài's sign-off (design doc Decision 5) -- functional default,
        // not a final UX decision.
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val reminderFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Distraction reminder audio focus state changed to: $focusChange")
            }
            .build()

        val result = audioManager.requestAudioFocus(reminderFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = reminderFocusRequest
            val reminderText = "Please keep your eyes on the road and both hands on the wheel."
            tts?.speak(reminderText, TextToSpeech.QUEUE_FLUSH, null, "DISTRACTION_REMINDER")
            Log.i(TAG, "🗣️ Speaking distraction reminder: '$reminderText'")
        } else {
            Log.e(TAG, "❌ Distraction reminder audio focus request denied.")
        }
    }
```

with:

```kotlin
    fun executeVoiceIntervention(level: Int) {
        // 1. Cấu hình Audio Attributes với độ ưu tiên cao nhất (ASSISTANT / EMERGENCY)
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // 2. Thiết lập yêu cầu cướp tiêu điểm âm thanh độc quyền (Mute tất cả nhạc của xe)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Audio focus state changed to: $focusChange")
            }
            .build()

        // 3. Gửi yêu cầu lên hệ thống âm thanh buồng lái
        val result = audioManager.requestAudioFocus(focusRequest!!)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "🔇 Audio Focus Obtained! Vehicle Media Muted.")
            speakAlert(level)
        } else {
            Log.e(TAG, "❌ Audio Focus Request Denied.")
        }
    }

    // Level->copy mapping is Kotlin-owned -- Python only sends an escalationLevel
    // int (see design doc docs/superpowers/specs/2026-07-31-alert-escalation-design.md
    // Section 3). repeat_interval_seconds on the Python side was tuned to exceed
    // each of these utterances' estimated spoken duration with margin -- if this
    // copy changes, re-measure with TextToSpeech and re-tune the Python-side
    // interval constants, do not assume the margin still holds.
    private fun drowsinessAlertTextFor(level: Int): String = when (level) {
        1 -> "Warning! Drowsiness detected! Climate safety mode engaged. Please stay awake. Shall I guide you to the nearest rest stop?"
        2 -> "You're still drowsy. Please pull over now."
        else -> "Pull over immediately. Not safe to continue."
    }

    private fun speakAlert(level: Int) {
        val alertText = drowsinessAlertTextFor(level)
        tts?.speak(alertText, TextToSpeech.QUEUE_FLUSH, null, "EMERGENCY_ALERT")
        Log.i(TAG, "🗣️ Speaking Alert (level $level): '$alertText'")
    }

    private fun distractionReminderTextFor(level: Int): String = when (level) {
        1 -> "Please keep your eyes on the road and both hands on the wheel."
        2 -> "Eyes on the road, please. This is important."
        else -> "Eyes on the road now!"
    }

    fun executeDistractionReminder(level: Int) {
        // Lighter than executeVoiceIntervention()'s _EXCLUSIVE request -- a brief
        // distraction reminder shouldn't seize/mute all cabin audio the way a
        // sustained drowsiness alert does. Placeholder wording/focus behavior,
        // pending Tài's sign-off (design doc Decision 5) -- functional default,
        // not a final UX decision.
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val reminderFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(playbackAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                Log.d(TAG, "Distraction reminder audio focus state changed to: $focusChange")
            }
            .build()

        val result = audioManager.requestAudioFocus(reminderFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = reminderFocusRequest
            val reminderText = distractionReminderTextFor(level)
            tts?.speak(reminderText, TextToSpeech.QUEUE_FLUSH, null, "DISTRACTION_REMINDER")
            Log.i(TAG, "🗣️ Speaking distraction reminder (level $level): '$reminderText'")
        } else {
            Log.e(TAG, "❌ Distraction reminder audio focus request denied.")
        }
    }
```

- [ ] **Step 2: Register an `UtteranceProgressListener` for cut-off detection**

In `init { tts = TextToSpeech(context, this) }`, the `onInit` callback currently only sets the language. Replace:

```kotlin
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US // Sử dụng tiếng Anh chuyên nghiệp theo proposal
        }
    }
```

with:

```kotlin
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US // Sử dụng tiếng Anh chuyên nghiệp theo proposal
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (interrupted) {
                        // Pure observability -- does not change timing/control flow. Lets
                        // rehearsal on the real demo device (adb logcat) catch a
                        // repeat_interval_seconds/utterance-length mismatch before the
                        // live show, per design doc Section 3 "Ràng buộc thời lượng TTS".
                        Log.w(TAG, "Utterance cut off before completion: $utteranceId")
                    }
                }
            })
        }
    }
```

Add the missing import at the top of the file (alongside the existing `android.speech.tts.TextToSpeech` import):

```kotlin
import android.speech.tts.UtteranceProgressListener
```

- [ ] **Step 3: Fix `ClimateOverrideReceiver.kt`'s direct calls (found by grep, not previously known)**

`ClimateOverrideReceiver.kt:28-29` is the dormant manual on-stage fallback (ADB-triggered only, per its own class doc) — it calls both `RealClimateActuatorGateway(context).applyDrowsinessOverride()` and `voiceAssistant?.executeVoiceIntervention()` directly, bypassing the escalation pipeline entirely. Both signatures just changed (Task 8, Task 9 Step 1) — this file would fail to compile without a fix, and this call site was not in this plan's original Global Constraints list. Since this is a one-shot manual fallback with no escalation state of its own, use level 1 (the original, pre-escalation baseline behavior).

Replace (current lines 24-30):

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_ALERT) return
        val score = intent.getFloatExtra("drowsiness_score", 0f)
        Log.w(TAG, "Manual fallback TRIGGER_ALERT received. Score: $score")
        RealClimateActuatorGateway(context).applyDrowsinessOverride()
        voiceAssistant?.executeVoiceIntervention()
    }
```

with:

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_ALERT) return
        val score = intent.getFloatExtra("drowsiness_score", 0f)
        Log.w(TAG, "Manual fallback TRIGGER_ALERT received. Score: $score")
        // Manual one-shot fallback -- no escalation state of its own, always level 1
        // (the original pre-escalation baseline behavior).
        RealClimateActuatorGateway(context).applyDrowsinessOverride(1)
        voiceAssistant?.executeVoiceIntervention(1)
    }
```

- [ ] **Step 4: Hand-trace verification and commit**

Re-read the full `VoiceEmergencyAssistant.kt` and `ClimateOverrideReceiver.kt` to confirm: both `executeVoiceIntervention`/`executeDistractionReminder` now take `level: Int` and call the corresponding `...TextFor(level)` helper; the `UtteranceProgressListener` is registered once in `onInit`; `ClimateOverrideReceiver.kt`'s two call sites now pass `1`; no other method in either file was altered. Also grep once more to be sure no other caller exists: `grep -rn "executeVoiceIntervention\|executeDistractionReminder" aaos-cockpit-app/app/src/` — expected matches are only `VoiceEmergencyAssistant.kt`'s own definitions, `RealVoiceAlertGateway` (Task 7), and `ClimateOverrideReceiver.kt` (this step).

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceEmergencyAssistant.kt \
        aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateOverrideReceiver.kt
git commit -m "Add level-to-message mapping and utterance-cutoff logging to VoiceEmergencyAssistant"
```

---

## Task 10: `DrowsinessController.kt` — escalation-aware `handleCritical`/`revertToBaseline`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt`

**Interfaces:**
- Consumes: `payload.escalationLevel` (Task 6), `ClimateActuatorGateway.applyDrowsinessOverride(level: Int)` (Task 8), `AlertArbiter.requestVoiceAlert(source, level: Int)` (Task 7).
- Produces: no new public interface — this is the top of the chain built by Tasks 6-9.

**Before touching this file:** re-read Global Constraints' note about `docs/superpowers/specs/2026-07-31-alert-preferences-parked-suppression-design.md` — if that spec's `alertPreferencesStore`/`isParked` changes have already landed in this file, merge by hand rather than pasting the block below verbatim.

- [ ] **Step 1: Write the 6 new failing tests**

Append to `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt` (before the closing `}` of the class):

```kotlin
    @Test
    fun `duplicate correlationId does not call the gateway or arbiter again`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))
        assertTrue(climate.overrideApplied)
        climate.overrideApplied = false
        voice.alertTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))

        assertFalse(climate.overrideApplied)
        assertFalse(voice.alertTriggered)
    }

    @Test
    fun `consecutive CRITICAL with the same level does not re-apply climate but still fires voice every payload`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 2))
        assertEquals(2, climate.lastAppliedLevel)
        climate.overrideApplied = false // reset to prove no SECOND climate call happened
        voice.alertTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 2))

        assertFalse(climate.overrideApplied)
        assertTrue(voice.alertTriggered)
        assertEquals(2, voice.lastAlertLevel)
    }

    @Test
    fun `CRITICAL with an increased level re-applies climate at the new level`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))
        assertEquals(1, climate.lastAppliedLevel)

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 2))

        assertTrue(climate.overrideApplied)
        assertEquals(2, climate.lastAppliedLevel)
    }

    @Test
    fun `climate failure sets OVERRIDE_FAILED and retries the same level on the next payload`() {
        climate.throwOnApply = true

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))

        assertEquals(DrowsinessController.GatewayActionStatus.OVERRIDE_FAILED, controller.lastGatewayAction)
        assertFalse(climate.overrideApplied)

        climate.throwOnApply = false
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 1))

        assertTrue(climate.overrideApplied)
        assertEquals(1, climate.lastAppliedLevel)
    }

    @Test
    fun `UNKNOWN reverts to baseline and clears the last applied climate level`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 3))

        controller.onPayload(payload(TriggerPayload.STATE_UNKNOWN, "vg-0002"))

        assertTrue(climate.revertCalled)
        assertTrue(voice.stopCalled)
    }

    @Test
    fun `after UNKNOWN a fresh CRITICAL at level 1 re-applies the override from scratch`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 3))
        controller.onPayload(payload(TriggerPayload.STATE_UNKNOWN, "vg-0002"))
        climate.overrideApplied = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003", escalationLevel = 1))

        assertTrue(climate.overrideApplied)
        assertEquals(1, climate.lastAppliedLevel)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run (hand-trace, since no Gradle wrapper exists — read the test file and confirm each new test references `climate.lastAppliedLevel`/`voice.lastAlertLevel`, which don't exist on the current `DrowsinessController`/gateways until Step 3 runs): the compile would fail on `handleCritical()` still being no-arg and on `applyDrowsinessOverride()`/`requestVoiceAlert()` still being called without a `level` argument.

- [ ] **Step 3: Rewrite `handleCritical`/`revertToBaseline`/`onPayload`**

Replace:

```kotlin
    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return // duplicate delivery of an already-processed payload — idempotency
        }
        lastCorrelationId = payload.correlationId

        when (payload.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical()
            else -> handleNonCritical() // NORMAL, WARNING, UNKNOWN all revert to baseline
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost (3 consecutive poll failures) — reverting to safe baseline")
        revertToBaseline()
    }

    private fun handleCritical() {
        if (latched) return // already applied for the current episode
        latched = true
        try {
            climateGateway.applyDrowsinessOverride()
            alertArbiter.setDrowsinessCriticalActive(true)
            alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS)
            lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure applying drowsiness override: ${t.message}")
            lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
            // no retry: GATEWAY_MODE is the intended recovery path for a broken Real gateway
        }
    }

    private fun handleNonCritical() {
        if (!latched) return // nothing to revert — never fabricate an action from missing prior state
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        try {
            climateGateway.revertToBaseline()
            alertArbiter.setDrowsinessCriticalActive(false)
            alertArbiter.stopAlert(AlertSource.DROWSINESS)
            lastGatewayAction = GatewayActionStatus.REVERTED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        }
    }
```

with:

```kotlin
    private var lastAppliedClimateLevel: Int? = null // null = no override currently applied

    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return // duplicate delivery of an already-processed payload — idempotency
        }
        lastCorrelationId = payload.correlationId

        when (payload.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical(payload.escalationLevel)
            else -> handleNonCritical() // NORMAL, WARNING, UNKNOWN all revert to baseline
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost (3 consecutive poll failures) — reverting to safe baseline")
        revertToBaseline()
    }

    // Every CRITICAL payload from here on is meaningful (original edge, a
    // repeat_due tick, or a level_changed tick -- Python is the sole timing
    // authority, see docs/superpowers/specs/2026-07-31-alert-escalation-design.md
    // Section 2/3) -- so this no longer early-returns on `latched`. Climate is
    // only re-applied when the level actually changes; voice fires every time.
    private fun handleCritical(level: Int) {
        latched = true
        if (lastAppliedClimateLevel != level) {
            try {
                climateGateway.applyDrowsinessOverride(level)
                lastAppliedClimateLevel = level
                alertArbiter.setDrowsinessCriticalActive(true)
                lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
                DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
            } catch (t: Throwable) {
                Log.e(TAG, "Gateway failure applying drowsiness override at level $level: ${t.message}")
                lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
                DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
                // lastAppliedClimateLevel is NOT set here (this line only runs if the
                // try block above threw before reaching it) -- the next payload at the
                // same level will retry naturally, it is not treated as "already applied".
            }
        } else {
            alertArbiter.setDrowsinessCriticalActive(true)
        }
        try {
            alertArbiter.requestVoiceAlert(AlertSource.DROWSINESS, level)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure requesting drowsiness voice alert at level $level: ${t.message}")
        }
    }

    private fun handleNonCritical() {
        if (!latched) return // nothing to revert — never fabricate an action from missing prior state
        revertToBaseline()
    }

    private fun revertToBaseline() {
        latched = false
        lastAppliedClimateLevel = null
        try {
            climateGateway.revertToBaseline()
            alertArbiter.setDrowsinessCriticalActive(false)
            alertArbiter.stopAlert(AlertSource.DROWSINESS)
            lastGatewayAction = GatewayActionStatus.REVERTED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
            lastGatewayAction = GatewayActionStatus.REVERT_FAILED
            DebugOverlayState.instance.updateGatewayAction(lastGatewayAction.name)
        }
    }
```

(Note: `alertArbiter.setDrowsinessCriticalActive(true)` is called on both branches of the `if (lastAppliedClimateLevel != level)` check — it must run every time `handleCritical` runs, independent of whether climate was re-applied, since it is priority bookkeeping for `AlertArbiter`, not a climate side-effect. This mirrors the existing top-level KDoc's already-stated invariant that this flag's correctness does not depend on any other gateway call succeeding.)

- [ ] **Step 4: Update the class-level KDoc**

The current KDoc (lines 5-16) documents the OR-gate/instantaneous-state finding from the whole-branch review of the prior plan. Append one sentence to it (do not remove the existing text) noting the new behavior:

```kotlin
/**
 * Thin FSM — owns latch-until-explicit-signal, idempotency, connection-loss
 * fallback, and gateway crash-safety. Trusts `payload.state` directly and
 * does not debounce it itself: `payload.state` is `_state_for_score()`'s
 * instantaneous threshold read, not a sustained value. Sustain/cooldown
 * lives entirely in the emitters' *publish gate* (trigger_emitter.py),
 * which now fires on either the drowsiness OR the distraction emitter's
 * edge (main.py's `run_real_video()`: `signal in (...) or
 * distraction_signal in (...)`) -- so a delivered CRITICAL can arrive on
 * a distraction-only publish edge without drowsiness's own emitter having
 * sustained it. See design doc Decision 6.
 *
 * As of the alert-escalation feature, `handleCritical()` also no longer
 * latches into a no-op after the first call: every CRITICAL payload's
 * `escalationLevel` is trusted directly too, for the same reason --
 * Python's EscalationTracker (services/escalation_tracker.py) is the sole
 * timing authority deciding when a repeat/level-change publish is
 * meaningful, this class only reacts to it (see
 * docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3).
 */
```

- [ ] **Step 5: Run tests (hand-trace) and commit**

Re-read the full resulting `DrowsinessController.kt` and `DrowsinessControllerTest.kt`, trace each of the 6 new tests plus all 5 pre-existing tests line-by-line against the new implementation to confirm they pass (no Gradle wrapper available to compile/run).

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt
git commit -m "Make DrowsinessController escalation-aware: re-act on every CRITICAL payload"
```

---

## Task 11: `DistractionController.kt` — same pattern, no climate

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt`
- Modify: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt`

**Interfaces:**
- Consumes: `payload.distraction.escalationLevel` (Task 6), `AlertArbiter.requestVoiceAlert(source, level: Int)` (Task 7).

**Before touching this file:** same merge caution as Task 10 applies here too, per Global Constraints.

- [ ] **Step 1: Write the 3 new failing tests**

Append to `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt` (before the closing `}` of the class):

```kotlin
    @Test
    fun `duplicate correlationId does not call the gateway again`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
        voice.distractionReminderTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

        assertFalse(voice.distractionReminderTriggered)
    }

    @Test
    fun `consecutive CRITICAL fires the voice reminder every payload with the current level`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 1))
        assertEquals(1, voice.lastDistractionReminderLevel)
        voice.distractionReminderTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0002", escalationLevel = 2))

        assertTrue(voice.distractionReminderTriggered)
        assertEquals(2, voice.lastDistractionReminderLevel)
    }

    @Test
    fun `NORMAL after CRITICAL reverts, and a fresh CRITICAL fires again`() {
        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001", escalationLevel = 3))
        controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0002"))
        assertTrue(voice.stopCalled)
        voice.distractionReminderTriggered = false

        controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0003", escalationLevel = 1))

        assertTrue(voice.distractionReminderTriggered)
        assertEquals(1, voice.lastDistractionReminderLevel)
    }
```

- [ ] **Step 2: Run tests to verify they fail (hand-trace)**

Confirm by reading the current file that `escalationLevel` is not yet a parameter of `payload()` and `voice.lastDistractionReminderLevel` does not yet exist until Task 6/7 land (they already did in this plan's order — the failure here would be that `DistractionController.handleCritical()` is still no-arg and never reads `payload.distraction.escalationLevel`).

- [ ] **Step 3: Rewrite `handleCritical`/`onPayload`**

Replace:

```kotlin
    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return
        }
        lastCorrelationId = payload.correlationId

        when (payload.distraction.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical()
            else -> handleNonCritical()
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost -- reverting distraction reminder to baseline")
        revertToBaseline()
    }

    private fun handleCritical() {
        if (latched) return
        latched = true
        try {
            alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure requesting distraction reminder: ${t.message}")
        }
    }
```

with:

```kotlin
    fun onPayload(payload: TriggerPayload) {
        if (payload.correlationId == lastCorrelationId) {
            return
        }
        lastCorrelationId = payload.correlationId

        when (payload.distraction.state) {
            TriggerPayload.STATE_CRITICAL -> handleCritical(payload.distraction.escalationLevel)
            else -> handleNonCritical()
        }
    }

    fun onConnectionLost() {
        Log.w(TAG, "Connection lost -- reverting distraction reminder to baseline")
        revertToBaseline()
    }

    // No latch-and-return: every CRITICAL payload is meaningful (original edge,
    // repeat_due, or level_changed) -- same reasoning as DrowsinessController,
    // see docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3.
    // There is no climate channel here, so unlike DrowsinessController there is
    // no per-level dedup needed -- voice simply fires every time.
    private fun handleCritical(level: Int) {
        latched = true
        try {
            alertArbiter.requestVoiceAlert(AlertSource.DISTRACTION, level)
        } catch (t: Throwable) {
            Log.e(TAG, "Gateway failure requesting distraction reminder at level $level: ${t.message}")
        }
    }
```

(`handleNonCritical()`/`revertToBaseline()` below are unchanged.)

- [ ] **Step 4: Update the class-level KDoc**

Append one sentence to the existing KDoc (lines 5-11), same spirit as Task 10 Step 4:

```kotlin
/**
 * Independent FSM for distraction, arbitrated through the same
 * [AlertArbiter] as [DrowsinessController] but tracking its own latch
 * state entirely separately -- drowsiness and distraction are
 * physiologically independent and must never be merged into one state
 * enum (see design doc Decision 5).
 *
 * As of the alert-escalation feature, `handleCritical()` re-acts on every
 * CRITICAL payload's `distraction.escalationLevel` rather than latching
 * into a no-op after the first call -- see
 * docs/superpowers/specs/2026-07-31-alert-escalation-design.md Section 3.
 */
```

- [ ] **Step 5: Run tests (hand-trace) and commit**

Re-read the full resulting file and trace all 6 pre-existing tests plus the 3 new ones against the new implementation.

```bash
git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DistractionController.kt \
        aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DistractionControllerTest.kt
git commit -m "Make DistractionController escalation-aware: re-act on every CRITICAL payload"
```

---

## Task 12: Full-suite verification and documentation

**Files:**
- Modify: `CLAUDE.md` (repo root — gitignored/untracked per earlier session notes; if it is genuinely absent from git tracking, edit the local file anyway, matching the precedent set by the prior distraction-detection plan's own Task 16)

**Interfaces:** none — this task only verifies and documents what Tasks 1-11 built.

- [ ] **Step 1: Run the full Python test suite**

Run: `cd dms-ai-engine && python -m pytest -v`
Expected: all tests pass, 0 failures, 0 errors.

- [ ] **Step 2: Hand-trace the full Kotlin diff one more time end-to-end**

Re-read every Kotlin file touched across Tasks 6-11 together (not per-task in isolation) and trace one full scenario by hand: a `TriggerPayload` with `state=CRITICAL, escalationLevel=1` arriving at `VitalGuardMonitorService`'s `onPayload` fan-out, through `DrowsinessController.handleCritical(1)`, to `AlertArbiter.requestVoiceAlert(DROWSINESS, 1)`, to `VoiceAlertGateway.triggerAlert(1)`, to `VoiceEmergencyAssistant.executeVoiceIntervention(1)` — confirm every signature matches at every hop. Repeat for the distraction path and for a level-3 climate call reaching `ClimateActuatorGateway.applyDrowsinessOverride(3)`.

Also re-grep once across the whole `aaos-cockpit-app` tree to catch anything this plan's per-task greps might have missed:

```bash
grep -rn "applyDrowsinessOverride\|triggerAlert\|triggerDistractionReminder\|requestVoiceAlert" aaos-cockpit-app/app/src/
```

Confirm every call site now passes a `level`/`Int` argument.

- [ ] **Step 3: Measure real TTS duration on the actual demo device (manual, not a coded step — do not skip)**

Per spec Section 6, this is required before the feature can be considered done, the same way `PrefsAlertPreferencesStore`/VHAL clamp logic in the sibling spec are verified by hand on the real VM rather than by a unit test. On the Skycraft AAOS VM (or the closest available real device with the actual TTS voice engine that will run at the demo), call `TextToSpeech.speak()` with each of the 6 final copy strings from `VoiceEmergencyAssistant.kt` (3 drowsiness levels + 3 distraction levels) and time each one (e.g. via the `UtteranceProgressListener.onStart`/`onDone` callbacks added in Task 9, logged with a timestamp). Compare each measured duration against its corresponding `repeat_interval_seconds` entry in `main.py`'s `drowsy_escalation`/`distraction_escalation` construction (Task 4 Step 3). If any measured duration exceeds its interval minus ~1s of margin, increase that interval in `main.py` (not the copy) and re-commit. Record the measured numbers somewhere durable (a comment in `VoiceEmergencyAssistant.kt` next to the copy, following the existing convention of documenting real measurements in code comments elsewhere in this codebase, e.g. `main.py`'s `BASELINE_CALIBRATION_SECONDS` comment).

- [ ] **Step 4: Add a "Known Deviations" / feature note to `CLAUDE.md`**

Add a new subsection under the existing "Known Deviations from Proposal" section (matching that section's established style), documenting:
- The 3-level voice-repeat + climate-escalation mechanism, the `EscalationTracker` class, and that its exact timing constants (`level_up_seconds`, `repeat_interval_seconds`) are reasoned starting points requiring re-measurement against real TTS on the demo device, not validated numbers — same disclosed-limitation style as the drowsiness score's own weights.
- That Gap #2 (mute/disable UI, parked-state suppression) is explicitly out of scope for this feature and tracked separately.

- [ ] **Step 5: Final commit**

```bash
git add CLAUDE.md
git commit -m "Document the alert-escalation feature's disclosed limitations in CLAUDE.md"
```
