# DMS Delivery / FSM Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Track B (`main.py`/`score_calculator.py`/`trigger_emitter.py`) the one
canonical DMS pipeline, deliver its triggers to the Android app over a local
HTTP network-pin (no cloud round-trip), and build a thin, testable Kotlin FSM +
Fake/Real gateway layer to consume it — per the approved design at
`docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md`.

**Architecture:** Python `trigger_emitter.py` gains explicit `RECOVERED`/`UNKNOWN`
signals alongside the existing single-shot `CRITICAL` fire; a new
`trigger_server.py` serves the latest signal over `GET /latest-trigger` on the
room-internal network. A new Kotlin `TriggerPollClient` polls that endpoint every
500ms and feeds payloads into a new `DrowsinessController` (latch-until-explicit-
signal FSM), which drives new `ClimateActuatorGateway`/`VoiceAlertGateway`
interfaces (Fake/Real, runtime-toggleable). The existing `BroadcastReceiver` path
stays in the codebase, untouched, as a dormant manual on-stage fallback only.

**Tech Stack:** Python 3 (opencv-python, mediapipe, numpy, `http.server` stdlib,
new: `jsonschema`), Kotlin 1.9.22 / AGP 8.2.2 (new: `kotlinx-serialization-json`,
`kotlinx-coroutines-android`, JUnit4 for tests).

## Global Constraints

- No cloud/external-host calls anywhere in the trigger path — the local HTTP
  server binds only to the room-internal network pin (spec Decision 3).
- `trigger_emitter.py`'s existing hysteresis (0.85 enter / 0.50 exit / 2.0s sustain
  / 10.0s cooldown) is not modified — only extended with new emit points on the
  existing edges (spec Decision 5).
- Kotlin FSM does not reimplement hysteresis/debounce/cooldown — it trusts any
  received `CRITICAL` payload as already-sustained (spec Decision 6).
- `GATEWAY_MODE` must be runtime-toggleable (not build-flavor-only) so Real→Fake
  can flip in seconds during a live demo (spec Decision 7).
- Existing `ClimateOverrideReceiver.kt`/`VoiceEmergencyAssistant.kt` VHAL/Audio
  logic is relocated behind the new gateway interfaces, not rewritten (spec
  Decision 7).
- Out of scope (do not touch): full docs/README authoring (deferred to the team's
  day-9 slot), `script-node/` Luau VHAL bridge, WebSocket upgrade, building a full
  CLAUDE.md-mandated visual debug overlay UI — this plan only exposes the
  `lastGatewayAction` state on `DrowsinessController` for such an overlay to read
  later; it does not build the overlay screen itself.
- `contracts/trigger.schema.json` field types (nested, per spec Decision 4):
  `timestampMs: long`, `source: string`, `score: float`, `confidence: float`,
  `state: "NORMAL"|"WARNING"|"CRITICAL"|"UNKNOWN"`,
  `features.perclos: float`, `features.eyeOpenProbability: float`,
  `features.headEulerAngleX: float`, `reason: string`, `correlationId: string`.

---

## File Structure

**Python (`dms-ai-engine/`):**
- Delete: `dms_detector.py` (Track A)
- Modify: `trigger_emitter.py` — add `TriggerEmitter` down-edge RECOVERED emit,
  add new `FacePresenceTracker` class for UNKNOWN emit
- Modify: `main.py` — nested-schema payload builder, wiring to `trigger_server`,
  real MediaPipe EAR + head-pose in the real-video branch
- Create: `trigger_server.py` — `LatestTriggerStore` + background HTTP server
- Create: `../contracts/trigger.schema.json` — the shared wire schema
- Modify: `requirements.txt` — add `jsonschema`
- Create: `test_trigger_server.py`, extend `test_dms.py`

**Kotlin (`aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/`):**
- Create: `TriggerPayload.kt` — wire-format data classes
- Create: `ClimateActuatorGateway.kt` — interface + `FakeClimateActuatorGateway` +
  `RealClimateActuatorGateway` (wraps existing VHAL logic)
- Create: `VoiceAlertGateway.kt` — interface + `FakeVoiceAlertGateway` +
  `RealVoiceAlertGateway` (wraps existing TTS/AudioManager logic)
- Create: `GatewayModeStore.kt` — runtime-toggleable Fake/Real flag
- Create: `GatewayModeReceiver.kt` — ADB-triggerable broadcast to flip it live
- Create: `DrowsinessController.kt` — the thin FSM
- Create: `TriggerPollClient.kt` — `TriggerFetcher` interface + `HttpTriggerFetcher`
  + polling loop with consecutive-failure tracking
- Modify: `VitalGuardMonitorService.kt` — construct gateways/controller/poll client,
  start/stop the poll loop
- Modify: `app/build.gradle` — new dependencies
- Create: test files under `app/src/test/java/com/vitalguard/ai/`

**Docs:**
- Modify: `README.md`, `CLAUDE.md`

---

## Task 1: Delete Track A, commit Track B as canonical

**Files:**
- Delete: `dms-ai-engine/dms_detector.py`
- Modify: `README.md` (the `python dms_detector.py` run instruction)

**Interfaces:** none (no code dependency on this task from later tasks besides the
repo being in a clean, single-track state).

- [ ] **Step 1:** Confirm no other reference exists before deleting:
  `git grep -n dms_detector` from the repo root. Expected: only the
  `README.md:32` `python dms_detector.py` line (already verified during design —
  re-verify here in case of drift).
- [ ] **Step 2:** Delete the file: `git rm dms-ai-engine/dms_detector.py`.
- [ ] **Step 3:** Update `README.md` line 32 from `python dms_detector.py` to
  `python main.py --mock` (matches the actually-runnable entry point today).
- [ ] **Step 4:** Stage and commit both the deletion and Track B's previously-
  untracked files together, so the repo's tracked state matches "Track B is
  canonical" as of one commit:
  ```bash
  git add dms-ai-engine/main.py dms-ai-engine/score_calculator.py \
          dms-ai-engine/trigger_emitter.py dms-ai-engine/test_dms.py \
          dms-ai-engine/dms_detector.py README.md
  git commit -m "Make Track B (main.py/score_calculator.py/trigger_emitter.py) canonical, delete Track A"
  ```
- [ ] **Step 5:** Verify: `git status --short` shows no untracked files remaining
  under `dms-ai-engine/` except `evidence_run.csv` (a generated artifact, not
  source — leave it untracked).

---

## Task 2: `contracts/trigger.schema.json` + schema validation test

**Files:**
- Create: `contracts/trigger.schema.json`
- Modify: `dms-ai-engine/requirements.txt` (add `jsonschema`)
- Create: `dms-ai-engine/test_schema.py`

**Interfaces:**
- Produces: the JSON Schema file at `../contracts/trigger.schema.json` (relative
  to `dms-ai-engine/`), loaded by later Python tasks (Task 6) to validate
  outgoing payloads, and used as the documented contract Kotlin's `TriggerPayload`
  (Task 10) must match field-for-field.

- [ ] **Step 1:** Add `jsonschema` to `dms-ai-engine/requirements.txt`:
  ```
  opencv-python
  mediapipe
  numpy
  requests
  jsonschema
  ```
- [ ] **Step 2:** Create `contracts/trigger.schema.json`:
  ```json
  {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "Vital-Guard AI Trigger Payload",
    "description": "Wire format for DMS trigger events, delivered as an HTTP JSON body over the local network-pin (GET /latest-trigger). Kept nested (not flattened) because the transport is HTTP JSON, not Android Intent extras — see docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md Decision 4.",
    "type": "object",
    "required": ["timestampMs", "source", "score", "confidence", "state", "features", "reason", "correlationId"],
    "properties": {
      "timestampMs": { "type": "integer", "description": "Epoch milliseconds." },
      "source": { "type": "string", "enum": ["container-python", "debug", "replay"] },
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
      "correlationId": { "type": "string" }
    }
  }
  ```
- [ ] **Step 3:** Write the failing test `dms-ai-engine/test_schema.py`:
  ```python
  import json
  from pathlib import Path

  import jsonschema
  import pytest

  SCHEMA_PATH = Path(__file__).parent.parent / "contracts" / "trigger.schema.json"


  @pytest.fixture
  def schema():
      return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


  def test_valid_critical_payload_passes(schema):
      payload = {
          "timestampMs": 1700000000000,
          "source": "container-python",
          "score": 0.91,
          "confidence": 1.0,
          "state": "CRITICAL",
          "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
          "reason": "sustained_high_score",
          "correlationId": "vg-critical-0001",
      }
      jsonschema.validate(payload, schema)


  def test_missing_features_field_fails(schema):
      payload = {
          "timestampMs": 1700000000000,
          "source": "container-python",
          "score": 0.91,
          "confidence": 1.0,
          "state": "CRITICAL",
          "reason": "sustained_high_score",
          "correlationId": "vg-critical-0001",
      }
      with pytest.raises(jsonschema.ValidationError):
          jsonschema.validate(payload, schema)


  def test_unknown_state_value_is_accepted(schema):
      payload = {
          "timestampMs": 1700000000000,
          "source": "container-python",
          "score": 0.2,
          "confidence": 1.0,
          "state": "UNKNOWN",
          "features": {"perclos": 0.0, "eyeOpenProbability": 0.0, "headEulerAngleX": 0.0},
          "reason": "lost_face",
          "correlationId": "vg-unknown-0001",
      }
      jsonschema.validate(payload, schema)


  def test_invalid_state_value_fails(schema):
      payload = {
          "timestampMs": 1700000000000,
          "source": "container-python",
          "score": 0.2,
          "confidence": 1.0,
          "state": "RECOVERED",
          "features": {"perclos": 0.0, "eyeOpenProbability": 0.0, "headEulerAngleX": 0.0},
          "reason": "recovered",
          "correlationId": "vg-x-0001",
      }
      with pytest.raises(jsonschema.ValidationError):
          jsonschema.validate(payload, schema)
  ```
- [ ] **Step 4:** Install the new dependency and run:
  `pip install jsonschema && pytest dms-ai-engine/test_schema.py -v`
  Expected: all 4 tests PASS (the schema file already exists from Step 2, so this
  confirms the schema is well-formed and enforces the right shape — including
  that `"RECOVERED"` is deliberately *not* a valid `state` value, since recovery
  is represented as `NORMAL`/`WARNING`, per spec Decision 5).
- [ ] **Step 5:** Commit:
  ```bash
  git add contracts/trigger.schema.json dms-ai-engine/requirements.txt dms-ai-engine/test_schema.py
  git commit -m "Add contracts/trigger.schema.json with validation tests"
  ```

---

## Task 3: Extend `TriggerEmitter` with an explicit RECOVERED signal

**Files:**
- Modify: `dms-ai-engine/trigger_emitter.py`
- Modify: `dms-ai-engine/test_dms.py`

**Interfaces:**
- Consumes: none new.
- Produces: `TriggerEmitter.update(score: float, now: float) -> Optional[str]`
  — **breaking change** from the current `-> bool` signature. Returns
  `"CRITICAL"` on the existing rising-edge fire, `"RECOVERED"` on the new
  falling-edge fire, or `None` otherwise. Task 6 (main.py rewire) depends on this
  exact return type.

- [ ] **Step 1:** Write the failing test in `test_dms.py` (append after the
  existing `TriggerEmitter` tests):
  ```python
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
  ```
- [ ] **Step 2:** Run to verify failures:
  `pytest dms-ai-engine/test_dms.py -v -k "critical_string or returns_none or recovered"`
  Expected: FAIL (current `update()` returns `bool`, and there is no RECOVERED
  concept yet).
- [ ] **Step 3:** Modify `trigger_emitter.py`:
  ```python
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
  ```
- [ ] **Step 4:** Run all `TriggerEmitter`/`update` tests:
  `pytest dms-ai-engine/test_dms.py -v`
  Expected: all PASS, including the previously-existing tests
  (`test_no_trigger_before_sustain_window_elapses`,
  `test_trigger_fires_once_after_sustain`, etc.) — those tests only ever asserted
  truthy/falsy (`is True`/`is False`); confirm they still pass unchanged against
  the new `Optional[str]` return (`"CRITICAL"` is truthy, `None` is falsy, but any
  test using strict `is True`/`is False` will now FAIL against a string return —
  fix any such assertions to compare against `"CRITICAL"`/`None` explicitly rather
  than loosen them).
- [ ] **Step 5:** Fix the pre-existing strict-boolean assertions found in Step 4
  (e.g. `test_trigger_fires_once_after_sustain` asserts
  `emitter.update(0.9, now=2.1) is True` — change to `== "CRITICAL"`; similarly
  for every `is False` assertion on `update()`, change to `is None`). Re-run:
  `pytest dms-ai-engine/test_dms.py -v` — expected: all PASS.
- [ ] **Step 6:** Commit:
  ```bash
  git add dms-ai-engine/trigger_emitter.py dms-ai-engine/test_dms.py
  git commit -m "Extend TriggerEmitter with explicit RECOVERED signal on the down-edge"
  ```

---

## Task 4: `FacePresenceTracker` for the UNKNOWN (lost-face) signal

**Files:**
- Modify: `dms-ai-engine/trigger_emitter.py` (add the new class in the same file)
- Modify: `dms-ai-engine/test_dms.py`

**Interfaces:**
- Produces: `FacePresenceTracker(sustain_seconds: float = 2.0)` with
  `.update(has_face: bool, now: float) -> Optional[str]`, returning `"UNKNOWN"`
  once when a face has been absent for `>= sustain_seconds`, `"PRESENT"` once when
  a face reappears after an `"UNKNOWN"` was fired, or `None` otherwise. Task 6
  depends on this exact API.

- [ ] **Step 1:** Write the failing tests, appended to `test_dms.py`:
  ```python
  from trigger_emitter import FacePresenceTracker


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
  ```
- [ ] **Step 2:** Run to verify failures:
  `pytest dms-ai-engine/test_dms.py -v -k face_presence`
  Expected: FAIL with `ImportError`/`AttributeError` (`FacePresenceTracker` does
  not exist yet).
- [ ] **Step 3:** Add the class to `trigger_emitter.py` (append after
  `TriggerEmitter`):
  ```python
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
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/test_dms.py -v`
  Expected: all tests (old and new) PASS.
- [ ] **Step 5:** Commit:
  ```bash
  git add dms-ai-engine/trigger_emitter.py dms-ai-engine/test_dms.py
  git commit -m "Add FacePresenceTracker for the UNKNOWN lost-face signal"
  ```

---

## Task 5: `trigger_server.py` — local HTTP network-pin server

**Files:**
- Create: `dms-ai-engine/trigger_server.py`
- Create: `dms-ai-engine/test_trigger_server.py`

**Interfaces:**
- Produces:
  - `LatestTriggerStore` with `.update_latest(payload: dict) -> None` and
    `.take_if_unserved() -> Optional[dict]` (returns the payload and marks it
    served, or `None` if nothing new).
  - `start_background_server(store: LatestTriggerStore, host: str = "0.0.0.0", port: int = 8765) -> http.server.HTTPServer`
    — starts a daemon thread serving `GET /latest-trigger` backed by `store`;
    returns the server object so callers/tests can call `.shutdown()`.
- Consumes: nothing from earlier tasks (pure new component); Task 6 will call
  both of these.

- [ ] **Step 1:** Write the failing test `dms-ai-engine/test_trigger_server.py`:
  ```python
  import json
  import urllib.request
  import urllib.error

  import pytest

  from trigger_server import LatestTriggerStore, start_background_server


  @pytest.fixture
  def running_server():
      store = LatestTriggerStore()
      server = start_background_server(store, host="127.0.0.1", port=0)
      port = server.server_address[1]
      yield store, f"http://127.0.0.1:{port}"
      server.shutdown()


  def _get(url):
      try:
          with urllib.request.urlopen(f"{url}/latest-trigger", timeout=2) as resp:
              return resp.status, json.loads(resp.read().decode("utf-8"))
      except urllib.error.HTTPError as e:
          return e.code, None


  def test_no_trigger_yet_returns_204(running_server):
      _store, base_url = running_server
      status, body = _get(base_url)
      assert status == 204
      assert body is None


  def test_new_trigger_is_served_once_as_200(running_server):
      store, base_url = running_server
      payload = {"state": "CRITICAL", "correlationId": "vg-0001"}
      store.update_latest(payload)

      status, body = _get(base_url)
      assert status == 200
      assert body == payload


  def test_same_trigger_is_not_served_twice(running_server):
      store, base_url = running_server
      store.update_latest({"state": "CRITICAL", "correlationId": "vg-0001"})
      _get(base_url)  # first poll consumes it

      status, body = _get(base_url)
      assert status == 204
      assert body is None


  def test_newer_trigger_overrides_unserved_older_one(running_server):
      store, base_url = running_server
      store.update_latest({"state": "CRITICAL", "correlationId": "vg-0001"})
      store.update_latest({"state": "RECOVERED", "correlationId": "vg-0002"})

      status, body = _get(base_url)
      assert status == 200
      assert body["correlationId"] == "vg-0002"
  ```
- [ ] **Step 2:** Run to verify failures:
  `pytest dms-ai-engine/test_trigger_server.py -v`
  Expected: FAIL with `ModuleNotFoundError: No module named 'trigger_server'`.
- [ ] **Step 3:** Create `dms-ai-engine/trigger_server.py`:
  ```python
  """
  trigger_server
  --------------
  Local HTTP server (Container Node side of the room-internal "network pin").
  Serves the single most recent trigger payload to the Skycraft App over
  GET /latest-trigger — 200 + JSON body if there's a payload not yet served,
  204 otherwise. No external host is ever contacted (see design doc Decision 3).
  """
  import json
  import threading
  from http.server import BaseHTTPRequestHandler, HTTPServer
  from typing import Optional


  class LatestTriggerStore:
      def __init__(self):
          self._lock = threading.Lock()
          self._latest: Optional[dict] = None
          self._served = False

      def update_latest(self, payload: dict) -> None:
          with self._lock:
              self._latest = payload
              self._served = False

      def take_if_unserved(self) -> Optional[dict]:
          with self._lock:
              if self._latest is not None and not self._served:
                  self._served = True
                  return self._latest
              return None


  def _make_handler(store: LatestTriggerStore):
      class Handler(BaseHTTPRequestHandler):
          def log_message(self, fmt, *args):
              pass  # tắt log HTTP mặc định gây nhiễu console demo

          def do_GET(self):
              if self.path != "/latest-trigger":
                  self.send_response(404)
                  self.end_headers()
                  return
              payload = store.take_if_unserved()
              if payload is None:
                  self.send_response(204)
                  self.end_headers()
                  return
              body = json.dumps(payload).encode("utf-8")
              self.send_response(200)
              self.send_header("Content-Type", "application/json")
              self.send_header("Content-Length", str(len(body)))
              self.end_headers()
              self.wfile.write(body)

      return Handler


  def start_background_server(store: LatestTriggerStore, host: str = "0.0.0.0",
                                port: int = 8765) -> HTTPServer:
      server = HTTPServer((host, port), _make_handler(store))
      thread = threading.Thread(target=server.serve_forever, daemon=True)
      thread.start()
      return server
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/test_trigger_server.py -v`
  Expected: all 4 tests PASS.
- [ ] **Step 5:** Commit:
  ```bash
  git add dms-ai-engine/trigger_server.py dms-ai-engine/test_trigger_server.py
  git commit -m "Add trigger_server.py: local HTTP network-pin server for Container Node"
  ```

---

## Task 6: Rewire `main.py` to the nested schema + `trigger_server` (mock mode)

**Files:**
- Modify: `dms-ai-engine/main.py`
- Modify: `dms-ai-engine/test_dms.py` (or a new `test_main.py` — using
  `test_main.py` to keep `test_dms.py` focused on the calculator/emitter units)

**Interfaces:**
- Consumes: `TriggerEmitter.update() -> Optional[str]` (Task 3),
  `FacePresenceTracker.update() -> Optional[str]` (Task 4),
  `LatestTriggerStore`/`start_background_server` (Task 5).
- Produces: `build_trigger_payload(state: str, score: float, confidence: float,
  perclos: float, eye_open_probability: float, head_euler_angle_x: float,
  reason: str, source: str, event_counter: int) -> dict` matching
  `contracts/trigger.schema.json` exactly — Tasks 7/8 (real CV) call this same
  function with real feature values instead of mock ones.

- [ ] **Step 1:** Write the failing test `dms-ai-engine/test_main.py`:
  ```python
  import jsonschema
  import json
  from pathlib import Path

  from main import build_trigger_payload

  SCHEMA_PATH = Path(__file__).parent.parent / "contracts" / "trigger.schema.json"


  def test_build_trigger_payload_matches_schema():
      schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
      payload = build_trigger_payload(
          state="CRITICAL", score=0.91, confidence=1.0,
          perclos=0.8, eye_open_probability=0.1, head_euler_angle_x=28.0,
          reason="sustained_high_score", source="mock-stream", event_counter=1,
      )
      jsonschema.validate(payload, schema)
      assert payload["state"] == "CRITICAL"
      assert payload["correlationId"] == "vg-mock-stream-0001"
      assert payload["features"]["perclos"] == 0.8


  def test_mock_run_produces_critical_then_recovered_then_serves_them(tmp_path, monkeypatch):
      """End-to-end smoke test of run_mock_stream against a real (ephemeral-port)
      trigger_server — confirms the whole mock pipeline wires together."""
      import main as main_module

      out_csv = tmp_path / "evidence_run.csv"
      served = []
      original_update_latest = main_module.LatestTriggerStore.update_latest

      def spy_update_latest(self, payload):
          served.append(payload)
          return original_update_latest(self, payload)

      monkeypatch.setattr(main_module.LatestTriggerStore, "update_latest", spy_update_latest)
      main_module.run_mock_stream(out_csv, host="127.0.0.1", port=0)

      states = [p["state"] for p in served]
      assert "CRITICAL" in states
      assert "NORMAL" in states or "WARNING" in states, "expected a recovery signal after the mock drowsy episode ends"
  ```
- [ ] **Step 2:** Run to verify failures:
  `pytest dms-ai-engine/test_main.py -v`
  Expected: FAIL — `build_trigger_payload` doesn't have this signature yet, and
  `run_mock_stream` doesn't accept `host`/`port`.
- [ ] **Step 3:** Rewrite the relevant parts of `main.py`:
  ```python
  import argparse
  import csv
  import time
  from pathlib import Path

  from score_calculator import DrowsinessScoreCalculator, FrameFeatures
  from trigger_emitter import TriggerEmitter, FacePresenceTracker
  from trigger_server import LatestTriggerStore, start_background_server

  TRIGGER_SCHEMA_VERSION = "1.0"


  def _state_for_score(score: float, enter_threshold: float = 0.85, exit_threshold: float = 0.50) -> str:
      if score >= enter_threshold:
          return "CRITICAL"
      if score > exit_threshold:
          return "WARNING"
      return "NORMAL"


  def build_trigger_payload(state: str, score: float, confidence: float,
                             perclos: float, eye_open_probability: float,
                             head_euler_angle_x: float, reason: str, source: str,
                             event_counter: int) -> dict:
      return {
          "timestampMs": int(time.time() * 1000),
          "source": source,
          "score": round(score, 3),
          "confidence": round(confidence, 3),
          "state": state,
          "features": {
              "perclos": round(perclos, 3),
              "eyeOpenProbability": round(eye_open_probability, 3),
              "headEulerAngleX": round(head_euler_angle_x, 3),
          },
          "reason": reason,
          "correlationId": f"vg-{source}-{event_counter:04d}",
      }


  def run_mock_stream(out_csv: Path, host: str = "0.0.0.0", port: int = 8765) -> None:
      """Không cần video/model thật — sinh chuỗi eye-state/head-pose giả để tự test
      toàn bộ pipeline (score calc + emitter + CSV + HTTP network-pin) trong lúc
      chờ mentor và trong lúc chưa có model eye-state thật."""
      store = LatestTriggerStore()
      server = start_background_server(store, host=host, port=port)
      try:
          calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
          emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                                    sustain_seconds=2.0, cooldown_seconds=10.0)
          face_tracker = FacePresenceTracker(sustain_seconds=2.0)
          event_counter = 0

          # Kịch bản: 3s tỉnh táo -> 4s buồn ngủ (nhắm mắt + gục đầu) -> 3s tỉnh táo lại
          scenario = (
              [FrameFeatures(0, False, 0)] * 30
              + [FrameFeatures(0, True, 28)] * 40
              + [FrameFeatures(0, False, 0)] * 30
          )

          with open(out_csv, "w", newline="", encoding="utf-8") as f:
              writer = csv.writer(f)
              writer.writerow(["ts", "eye_closed_now", "head_pitch", "score", "state", "signal"])
              t = 0.0
              for frame in scenario:
                  frame.timestamp = t
                  score = calc.add_frame(frame)
                  signal = emitter.update(score, now=t)
                  face_signal = face_tracker.update(has_face=True, now=t)  # mock stream: mặt luôn "thấy"

                  state = _state_for_score(score)
                  if signal in ("CRITICAL", "RECOVERED") or face_signal in ("UNKNOWN", "PRESENT"):
                      event_counter += 1
                      emitted_state = "UNKNOWN" if face_signal == "UNKNOWN" else state
                      reason = "lost_face" if face_signal == "UNKNOWN" else (
                          "sustained_high_score" if signal == "CRITICAL" else "recovered"
                      )
                      payload = build_trigger_payload(
                          state=emitted_state, score=score, confidence=1.0,
                          perclos=calc.compute_score(), eye_open_probability=(0.0 if frame.eye_closed else 1.0),
                          head_euler_angle_x=frame.head_pitch_deg, reason=reason,
                          source="mock-stream", event_counter=event_counter,
                      )
                      store.update_latest(payload)

                  writer.writerow([f"{t:.1f}", int(frame.eye_closed), frame.head_pitch_deg,
                                    f"{score:.3f}", state, signal or ""])
                  t += 0.1
                  time.sleep(0.02)

          print(f"Xong. CSV: {out_csv}. Số event đã emit: {event_counter}")
      finally:
          server.shutdown()


  def main():
      parser = argparse.ArgumentParser()
      parser.add_argument("--video", type=str, default=None, help="Đường dẫn video driver-facing")
      parser.add_argument("--mock", action="store_true", help="Chạy kịch bản giả, không cần video/model")
      parser.add_argument("--host", type=str, default="0.0.0.0", help="Địa chỉ bind server nội bộ room")
      parser.add_argument("--port", type=int, default=8765, help="Cổng server /latest-trigger")
      parser.add_argument("--out-csv", type=str, default="evidence_run.csv")
      args = parser.parse_args()

      if args.mock or not args.video:
          run_mock_stream(Path(args.out_csv), host=args.host, port=args.port)
          return

      # --- Nhánh video thật: xem Task 7/8 (MediaPipe FaceMesh EAR + head-pose) ---
      raise NotImplementedError(
          "Nhánh đọc video thật chưa nối eye-state/head-pose model. "
          "Dùng --mock để test toàn bộ pipeline trước; nối MediaPipe ở Task 7/8."
      )


  if __name__ == "__main__":
      main()
  ```
  (Removed `emit_trigger`/`requests`-based POST entirely — replaced by the
  `LatestTriggerStore` write; removed the old `json`/`sys` imports that are no
  longer used.)
- [ ] **Step 4:** Run: `pytest dms-ai-engine/test_main.py dms-ai-engine/test_dms.py -v`
  Expected: all PASS. (`--mock` still runs standalone via
  `python main.py --mock`, now against a background server instead of POSTing
  to a URL — manually verify once: `cd dms-ai-engine && python main.py --mock`,
  confirm it prints `Xong. CSV: evidence_run.csv. Số event đã emit: 2` — one
  CRITICAL + one RECOVERED — and exits cleanly.)
- [ ] **Step 5:** Commit:
  ```bash
  git add dms-ai-engine/main.py dms-ai-engine/test_main.py
  git commit -m "Rewire main.py to nested trigger schema + trigger_server network-pin"
  ```

---

## Task 7: Real MediaPipe FaceMesh EAR extraction

**Files:**
- Modify: `dms-ai-engine/main.py`
- Create: `dms-ai-engine/eye_state.py`
- Create: `dms-ai-engine/test_eye_state.py`

**Interfaces:**
- Produces: `compute_ear(landmarks: list[tuple[float, float]]) -> float` (pure
  geometry function, testable without a real camera/video) and
  `EAR_CLOSED_THRESHOLD`/`EAR_OPEN_THRESHOLD` constants used to derive
  `eye_closed: bool` and `eyeOpenProbability: float`.
- Consumes: nothing from earlier tasks — this is pure geometry over landmark
  coordinates, independent of the emitter/server work.

- [ ] **Step 1:** Write the failing test `dms-ai-engine/test_eye_state.py`
  (using synthetic landmark coordinates for a clearly-open vs. clearly-closed
  eye shape, in MediaPipe FaceMesh's normalized-image-space convention):
  ```python
  from eye_state import compute_ear

  # 6-point layout per eye, MediaPipe FaceMesh index order: [outer_corner, top_1,
  # top_2, inner_corner, bottom_2, bottom_1] — matches the classic
  # Soukupová & Čech EAR formula: (||top1-bottom1|| + ||top2-bottom2||) / (2*||outer-inner||)

  def test_open_eye_has_high_ear():
      # tall, open eye shape: vertical gap ~0.08, horizontal gap ~0.30
      landmarks = [
          (0.0, 0.15),   # outer corner
          (0.10, 0.05),  # top_1
          (0.20, 0.05),  # top_2
          (0.30, 0.15),  # inner corner
          (0.20, 0.25),  # bottom_2
          (0.10, 0.25),  # bottom_1
      ]
      assert compute_ear(landmarks) > 0.25


  def test_closed_eye_has_low_ear():
      # flat, closed eye shape: near-zero vertical gap, same horizontal gap
      landmarks = [
          (0.0, 0.15),
          (0.10, 0.14),
          (0.20, 0.14),
          (0.30, 0.15),
          (0.20, 0.16),
          (0.10, 0.16),
      ]
      assert compute_ear(landmarks) < 0.10


  def test_ear_requires_exactly_six_points():
      import pytest
      with pytest.raises(ValueError):
          compute_ear([(0.0, 0.0), (1.0, 1.0)])
  ```
- [ ] **Step 2:** Run to verify failures:
  `pytest dms-ai-engine/test_eye_state.py -v`
  Expected: FAIL with `ModuleNotFoundError`.
- [ ] **Step 3:** Create `dms-ai-engine/eye_state.py`:
  ```python
  """
  eye_state
  ---------
  Real eye-aspect-ratio (EAR) computation from MediaPipe FaceMesh landmarks —
  classic Soukupová & Čech (2016) formula. Pure geometry, no MediaPipe/OpenCV
  import here, so it's testable with plain synthetic coordinates.

  MediaPipe FaceMesh landmark indices used per eye (6-point EAR layout):
    Left eye  (viewer's left  / subject's right): [362, 385, 387, 263, 373, 380]
    Right eye (viewer's right / subject's left):  [33,  160, 158, 133, 153, 144]
  Order for each list: [outer_corner, top_1, top_2, inner_corner, bottom_2, bottom_1]
  """
  import math
  from typing import List, Tuple

  LEFT_EYE_INDICES = [362, 385, 387, 263, 373, 380]
  RIGHT_EYE_INDICES = [33, 160, 158, 133, 153, 144]

  EAR_CLOSED_THRESHOLD = 0.18
  EAR_OPEN_THRESHOLD = 0.28


  def _dist(a: Tuple[float, float], b: Tuple[float, float]) -> float:
      return math.hypot(a[0] - b[0], a[1] - b[1])


  def compute_ear(points: List[Tuple[float, float]]) -> float:
      """points: [outer_corner, top_1, top_2, inner_corner, bottom_2, bottom_1]."""
      if len(points) != 6:
          raise ValueError(f"compute_ear cần đúng 6 điểm, nhận được {len(points)}")
      outer, top1, top2, inner, bottom2, bottom1 = points
      vertical = _dist(top1, bottom1) + _dist(top2, bottom2)
      horizontal = 2.0 * _dist(outer, inner)
      if horizontal == 0:
          return 0.0
      return vertical / horizontal


  def eye_open_probability(ear: float) -> float:
      """Ánh xạ tuyến tính EAR -> [0,1], kẹp ở 2 đầu ngưỡng đã calibrate."""
      span = EAR_OPEN_THRESHOLD - EAR_CLOSED_THRESHOLD
      normalized = (ear - EAR_CLOSED_THRESHOLD) / span
      return max(0.0, min(1.0, normalized))


  def average_ear(landmarks: List[Tuple[float, float]]) -> float:
      """landmarks: full 468-point MediaPipe FaceMesh list (x, y) in normalized
      image coordinates; averages both eyes' EAR."""
      left = [landmarks[i] for i in LEFT_EYE_INDICES]
      right = [landmarks[i] for i in RIGHT_EYE_INDICES]
      return (compute_ear(left) + compute_ear(right)) / 2.0
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/test_eye_state.py -v`
  Expected: all 3 tests PASS.
- [ ] **Step 5:** Wire it into `main.py`'s real-video branch. Replace the
  `raise NotImplementedError(...)` block with:
  ```python
  def run_real_video(video_path: str, out_csv: Path, host: str, port: int) -> None:
      import cv2
      import mediapipe as mp
      from eye_state import average_ear, eye_open_probability, EAR_CLOSED_THRESHOLD
      from head_pose import estimate_pitch_deg  # Task 8

      store = LatestTriggerStore()
      server = start_background_server(store, host=host, port=port)
      face_mesh = mp.solutions.face_mesh.FaceMesh(
          max_num_faces=1, refine_landmarks=False,
          min_detection_confidence=0.5, min_tracking_confidence=0.5,
      )
      cap = cv2.VideoCapture(video_path)
      calc = DrowsinessScoreCalculator(window_seconds=2.0, sample_hz=10.0)
      emitter = TriggerEmitter(enter_threshold=0.85, exit_threshold=0.50,
                                sustain_seconds=2.0, cooldown_seconds=10.0)
      face_tracker = FacePresenceTracker(sustain_seconds=2.0)
      event_counter = 0
      t = 0.0
      fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
      frame_dt = 1.0 / fps

      try:
          with open(out_csv, "w", newline="", encoding="utf-8") as f:
              writer = csv.writer(f)
              writer.writerow(["ts", "has_face", "ear", "head_pitch", "score", "state", "signal"])
              while cap.isOpened():
                  ret, frame = cap.read()
                  if not ret:
                      break
                  rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                  results = face_mesh.process(rgb)
                  has_face = bool(results.multi_face_landmarks)

                  face_signal = face_tracker.update(has_face=has_face, now=t)
                  if face_signal == "UNKNOWN":
                      event_counter += 1
                      store.update_latest(build_trigger_payload(
                          state="UNKNOWN", score=0.0, confidence=0.0,
                          perclos=0.0, eye_open_probability=0.0, head_euler_angle_x=0.0,
                          reason="lost_face", source="container-python", event_counter=event_counter,
                      ))

                  if has_face:
                      h, w = frame.shape[:2]
                      landmarks = [(lm.x * w, lm.y * h) for lm in results.multi_face_landmarks[0].landmark]
                      ear = average_ear(landmarks)
                      pitch_deg = estimate_pitch_deg(landmarks, w, h)
                      eye_closed = ear < EAR_CLOSED_THRESHOLD
                      score = calc.add_frame(FrameFeatures(timestamp=t, eye_closed=eye_closed, head_pitch_deg=pitch_deg))
                      signal = emitter.update(score, now=t)
                      state = _state_for_score(score)
                      if signal in ("CRITICAL", "RECOVERED"):
                          event_counter += 1
                          store.update_latest(build_trigger_payload(
                              state=state, score=score, confidence=1.0,
                              perclos=calc.compute_score(), eye_open_probability=eye_open_probability(ear),
                              head_euler_angle_x=pitch_deg,
                              reason=("sustained_high_score" if signal == "CRITICAL" else "recovered"),
                              source="container-python", event_counter=event_counter,
                          ))
                      writer.writerow([f"{t:.2f}", 1, f"{ear:.3f}", f"{pitch_deg:.1f}", f"{score:.3f}", state, signal or ""])
                  else:
                      writer.writerow([f"{t:.2f}", 0, "", "", "", "", face_signal or ""])

                  t += frame_dt
      finally:
          cap.release()
          server.shutdown()
  ```
  And in `main()`, replace the real-video branch's body:
  ```python
      if args.mock or not args.video:
          run_mock_stream(Path(args.out_csv), host=args.host, port=args.port)
          return

      run_real_video(args.video, Path(args.out_csv), host=args.host, port=args.port)
  ```
- [ ] **Step 6:** This branch needs a real sample video to exercise end-to-end,
  which isn't available in this task's automated test — cover it with a unit
  test that stubs `cv2`/`mediapipe` calls instead (full integration is a manual
  Day-1/2 verification step per the design doc, not a unit test):
  ```python
  # dms-ai-engine/test_main.py — append
  def test_average_ear_and_state_agree_for_synthetic_closed_eyes():
      from eye_state import average_ear
      # Both eyes flat/closed shape, indices padded so LEFT/RIGHT_EYE_INDICES resolve.
      import eye_state
      landmarks = [(0.0, 0.0)] * 468
      closed_shape = [(0.0, 0.15), (0.10, 0.14), (0.20, 0.14), (0.30, 0.15), (0.20, 0.16), (0.10, 0.16)]
      for i, idx in enumerate(eye_state.LEFT_EYE_INDICES):
          landmarks[idx] = closed_shape[i]
      for i, idx in enumerate(eye_state.RIGHT_EYE_INDICES):
          landmarks[idx] = closed_shape[i]
      assert average_ear(landmarks) < eye_state.EAR_CLOSED_THRESHOLD
  ```
  Run: `pytest dms-ai-engine/test_main.py -v -k synthetic_closed_eyes`
  Expected: PASS.
- [ ] **Step 7:** Manual verification (not automated — requires a real sample
  video, per spec Decision 2's own acknowledgment that this needs a real
  sample): once a driver-facing sample clip is available, run
  `python main.py --video path/to/sample.mp4` and confirm `evidence_run.csv`
  shows plausible EAR/pitch values and at least one `CRITICAL` row for a
  clip containing a drowsy episode. Record the result as evidence per
  CLAUDE.md's "working code with evidence" principle — this is a Day 2/3-scale
  task in the team's separate day-by-day timeline, not blocking the rest of
  this plan.
- [ ] **Step 8:** Commit:
  ```bash
  git add dms-ai-engine/eye_state.py dms-ai-engine/test_eye_state.py dms-ai-engine/main.py dms-ai-engine/test_main.py
  git commit -m "Wire real MediaPipe FaceMesh EAR extraction into main.py's real-video branch"
  ```

---

## Task 8: Real head-pose (pitch) via solvePnP

**Files:**
- Create: `dms-ai-engine/head_pose.py`
- Create: `dms-ai-engine/test_head_pose.py`

**Interfaces:**
- Produces: `estimate_pitch_deg(landmarks: list[tuple[float, float]], frame_width: int, frame_height: int) -> float`
  — used by `run_real_video` in Task 7 (already referenced there via
  `from head_pose import estimate_pitch_deg`).
- Consumes: nothing from earlier tasks — pure geometry + OpenCV `solvePnP`.

- [ ] **Step 1:** Write the failing test `dms-ai-engine/test_head_pose.py`. This
  test can't easily assert an exact angle from synthetic 2D points without a
  real calibrated camera, so it asserts the *sign* and *relative ordering* of
  pitch for a clearly-upright vs. clearly-drooped synthetic landmark set built
  from the same 6-point 3D model the function uses:
  ```python
  from head_pose import estimate_pitch_deg, MODEL_LANDMARK_INDICES

  def _synthetic_landmarks(nose_y_offset: float, width: int, height: int):
      """Builds a full 468-point landmark list with just the 6 model points set
      to a plausible upright face, then shifts the nose tip down by
      nose_y_offset to simulate head droop (larger offset = more droop)."""
      landmarks = [(width / 2, height / 2)] * 468
      base = {
          1: (width * 0.50, height * 0.45),   # nose tip
          152: (width * 0.50, height * 0.75),  # chin
          33: (width * 0.35, height * 0.40),   # right eye outer corner
          263: (width * 0.65, height * 0.40),  # left eye outer corner
          61: (width * 0.40, height * 0.65),   # mouth right corner
          291: (width * 0.60, height * 0.65),  # mouth left corner
      }
      for idx, (x, y) in base.items():
          landmarks[idx] = (x, y + nose_y_offset if idx == 1 else y)
      return landmarks


  def test_model_uses_six_landmark_indices():
      assert len(MODEL_LANDMARK_INDICES) == 6


  def test_drooped_head_has_larger_pitch_than_upright_head():
      w, h = 640, 480
      upright = estimate_pitch_deg(_synthetic_landmarks(0, w, h), w, h)
      drooped = estimate_pitch_deg(_synthetic_landmarks(60, w, h), w, h)
      assert drooped > upright, "gục đầu (nose tip dịch xuống) phải cho pitch lớn hơn"
  ```
- [ ] **Step 2:** Run to verify failure:
  `pytest dms-ai-engine/test_head_pose.py -v`
  Expected: FAIL with `ModuleNotFoundError`.
- [ ] **Step 3:** Create `dms-ai-engine/head_pose.py`:
  ```python
  """
  head_pose
  ---------
  Head pitch estimation via OpenCV solvePnP against a canonical 3D face model,
  using 6 MediaPipe FaceMesh landmark correspondences — a standard, well-documented
  technique (not a heuristic guess). Positive pitch = head drooping forward/down.
  """
  import math
  from typing import List, Tuple

  import cv2
  import numpy as np

  # MediaPipe FaceMesh indices for: nose tip, chin, right eye outer corner,
  # left eye outer corner, mouth right corner, mouth left corner.
  MODEL_LANDMARK_INDICES = [1, 152, 33, 263, 61, 291]

  # Canonical 3D face model (millimeters, arbitrary but internally consistent
  # scale — solvePnP only needs consistent relative geometry, not real-world
  # units, since we only read the rotation, not the translation).
  MODEL_3D_POINTS = np.array([
      (0.0, 0.0, 0.0),        # nose tip
      (0.0, -63.6, -12.5),    # chin
      (-43.3, 32.7, -26.0),   # right eye outer corner
      (43.3, 32.7, -26.0),    # left eye outer corner
      (-28.9, -28.9, -24.1),  # mouth right corner
      (28.9, -28.9, -24.1),   # mouth left corner
  ], dtype=np.float64)


  def estimate_pitch_deg(landmarks: List[Tuple[float, float]], frame_width: int, frame_height: int) -> float:
      image_points = np.array(
          [landmarks[i] for i in MODEL_LANDMARK_INDICES], dtype=np.float64
      )
      focal_length = frame_width
      center = (frame_width / 2, frame_height / 2)
      camera_matrix = np.array([
          [focal_length, 0, center[0]],
          [0, focal_length, center[1]],
          [0, 0, 1],
      ], dtype=np.float64)
      dist_coeffs = np.zeros((4, 1))

      success, rotation_vec, _translation_vec = cv2.solvePnP(
          MODEL_3D_POINTS, image_points, camera_matrix, dist_coeffs,
          flags=cv2.SOLVEPNP_ITERATIVE,
      )
      if not success:
          return 0.0

      rotation_matrix, _ = cv2.Rodrigues(rotation_vec)
      sy = math.sqrt(rotation_matrix[0, 0] ** 2 + rotation_matrix[1, 0] ** 2)
      pitch_rad = math.atan2(-rotation_matrix[2, 0], sy)
      return math.degrees(pitch_rad)
  ```
- [ ] **Step 4:** Run: `pytest dms-ai-engine/test_head_pose.py -v`
  Expected: both tests PASS. If the sign convention comes out inverted (drooped
  < upright) once run against real OpenCV, flip the sign in
  `estimate_pitch_deg`'s return (`-math.degrees(pitch_rad)`) — the test as
  written pins down the intended direction, so let it tell you which way is
  correct rather than guessing.
- [ ] **Step 5:** Commit:
  ```bash
  git add dms-ai-engine/head_pose.py dms-ai-engine/test_head_pose.py
  git commit -m "Add real head-pose pitch estimation via solvePnP"
  ```

---

## Task 9: Kotlin Gradle dependencies

**Files:**
- Modify: `aaos-cockpit-app/app/build.gradle`
- Modify: `aaos-cockpit-app/build.gradle`

**Interfaces:** none (build config only) — every later Kotlin task depends on
this landing first.

- [ ] **Step 1:** Add the serialization plugin to the root `build.gradle`:
  ```groovy
  plugins {
      id("com.android.application") version "8.2.2" apply false
      id("org.jetbrains.kotlin.android") version "1.9.22" apply false
      id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
  }
  ```
- [ ] **Step 2:** Apply it and add dependencies in `app/build.gradle`:
  ```groovy
  plugins {
      id("com.android.application")
      id("org.jetbrains.kotlin.android")
      id("org.jetbrains.kotlin.plugin.serialization")
  }

  // ... existing android { } block unchanged ...

  dependencies {
      compileOnly files("${android.sdkDirectory}/platforms/android-${android.compileSdk}/optional/android.car.jar")
      implementation("androidx.car.app:app:1.4.0")

      implementation("androidx.core:core-ktx:1.12.0")
      implementation("androidx.appcompat:appcompat:1.6.1")

      implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
      implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

      testImplementation("junit:junit:4.13.2")
      testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
  }
  ```
- [ ] **Step 3:** Sync/build to confirm the new dependencies resolve:
  `cd aaos-cockpit-app && ./gradlew :app:testDebugUnitTest`
  Expected: build succeeds (0 tests yet, since none exist — "BUILD SUCCESSFUL"
  with no test failures reported).
- [ ] **Step 4:** Commit:
  ```bash
  git add aaos-cockpit-app/build.gradle aaos-cockpit-app/app/build.gradle
  git commit -m "Add kotlinx-serialization, kotlinx-coroutines, JUnit test dependencies"
  ```

---

## Task 10: `TriggerPayload.kt` — wire-format data classes

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPayloadTest.kt`

**Interfaces:**
- Produces: `@Serializable data class TriggerPayload(timestampMs: Long, source: String,
  score: Float, confidence: Float, state: String, features: TriggerFeatures,
  reason: String, correlationId: String)` and
  `@Serializable data class TriggerFeatures(perclos: Float, eyeOpenProbability: Float, headEulerAngleX: Float)`,
  plus `TriggerPayload.STATE_CRITICAL/STATE_NORMAL/STATE_WARNING/STATE_UNKNOWN`
  string constants. Used by Tasks 14 (`DrowsinessController`) and 15
  (`TriggerPollClient`).
- Consumes: nothing.

- [ ] **Step 1:** Write the failing test:
  ```kotlin
  package com.vitalguard.ai

  import kotlinx.serialization.json.Json
  import org.junit.Assert.assertEquals
  import org.junit.Test

  class TriggerPayloadTest {
      private val json = Json { ignoreUnknownKeys = true }

      @Test
      fun `parses a well-formed CRITICAL payload`() {
          val raw = """
              {
                "timestampMs": 1700000000000,
                "source": "container-python",
                "score": 0.91,
                "confidence": 1.0,
                "state": "CRITICAL",
                "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
                "reason": "sustained_high_score",
                "correlationId": "vg-critical-0001"
              }
          """.trimIndent()

          val payload = json.decodeFromString<TriggerPayload>(raw)

          assertEquals(TriggerPayload.STATE_CRITICAL, payload.state)
          assertEquals("vg-critical-0001", payload.correlationId)
          assertEquals(0.8f, payload.features.perclos)
      }
  }
  ```
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.TriggerPayloadTest"`
  Expected: FAIL — `TriggerPayload` doesn't exist yet.
- [ ] **Step 3:** Create `TriggerPayload.kt`:
  ```kotlin
  package com.vitalguard.ai

  import kotlinx.serialization.Serializable

  @Serializable
  data class TriggerFeatures(
      val perclos: Float,
      val eyeOpenProbability: Float,
      val headEulerAngleX: Float
  )

  @Serializable
  data class TriggerPayload(
      val timestampMs: Long,
      val source: String,
      val score: Float,
      val confidence: Float,
      val state: String,
      val features: TriggerFeatures,
      val reason: String,
      val correlationId: String
  ) {
      companion object {
          const val STATE_NORMAL = "NORMAL"
          const val STATE_WARNING = "WARNING"
          const val STATE_CRITICAL = "CRITICAL"
          const val STATE_UNKNOWN = "UNKNOWN"
      }
  }
  ```
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.TriggerPayloadTest"`
  Expected: PASS.
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPayload.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPayloadTest.kt
  git commit -m "Add TriggerPayload wire-format data classes"
  ```

---

## Task 11: Gateway interfaces + Fake implementations

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt`
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/FakeGatewaysTest.kt`

**Interfaces:**
- Produces:
  - `interface ClimateActuatorGateway { fun applyDrowsinessOverride(); fun revertToBaseline() }`
  - `interface VoiceAlertGateway { fun triggerAlert(); fun stopAlert() }`
  - `class FakeClimateActuatorGateway : ClimateActuatorGateway` with observable
    `var overrideApplied: Boolean`, `var revertCalled: Boolean`,
    `var throwOnApply: Boolean`, `var throwOnRevert: Boolean`.
  - `class FakeVoiceAlertGateway : VoiceAlertGateway` with the analogous
    `alertTriggered`/`stopCalled`/`throwOnTrigger`/`throwOnStop` fields.
  These Fakes are what Task 14's `DrowsinessControllerTest` asserts against.
- Consumes: nothing.

- [ ] **Step 1:** Write the failing test:
  ```kotlin
  package com.vitalguard.ai

  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class FakeGatewaysTest {
      @Test
      fun `fake climate gateway tracks apply and revert calls`() {
          val gateway = FakeClimateActuatorGateway()
          assertFalse(gateway.overrideApplied)

          gateway.applyDrowsinessOverride()
          assertTrue(gateway.overrideApplied)

          gateway.revertToBaseline()
          assertTrue(gateway.revertCalled)
      }

      @Test(expected = IllegalStateException::class)
      fun `fake climate gateway throws on apply when configured to`() {
          val gateway = FakeClimateActuatorGateway()
          gateway.throwOnApply = true
          gateway.applyDrowsinessOverride()
      }

      @Test
      fun `fake voice gateway tracks trigger and stop calls`() {
          val gateway = FakeVoiceAlertGateway()
          gateway.triggerAlert()
          gateway.stopAlert()
          assertTrue(gateway.alertTriggered)
          assertTrue(gateway.stopCalled)
      }
  }
  ```
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.FakeGatewaysTest"`
  Expected: FAIL — classes don't exist.
- [ ] **Step 3:** Create `ClimateActuatorGateway.kt`:
  ```kotlin
  package com.vitalguard.ai

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
- [ ] **Step 4:** Create `VoiceAlertGateway.kt`:
  ```kotlin
  package com.vitalguard.ai

  interface VoiceAlertGateway {
      fun triggerAlert()
      fun stopAlert()
  }

  class FakeVoiceAlertGateway : VoiceAlertGateway {
      var alertTriggered: Boolean = false
      var stopCalled: Boolean = false
      var throwOnTrigger: Boolean = false
      var throwOnStop: Boolean = false

      override fun triggerAlert() {
          if (throwOnTrigger) throw IllegalStateException("simulated voice gateway failure")
          alertTriggered = true
      }

      override fun stopAlert() {
          if (throwOnStop) throw IllegalStateException("simulated voice gateway stop failure")
          stopCalled = true
      }
  }
  ```
- [ ] **Step 5:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.FakeGatewaysTest"`
  Expected: all 3 PASS.
- [ ] **Step 6:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/FakeGatewaysTest.kt
  git commit -m "Add ClimateActuatorGateway/VoiceAlertGateway interfaces with Fake implementations"
  ```

---

## Task 12: `GatewayModeStore` + `GatewayModeReceiver` — runtime Fake/Real toggle

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/GatewayModeStore.kt`
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/GatewayModeReceiver.kt`
- Modify: `aaos-cockpit-app/app/src/main/AndroidManifest.xml`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/GatewayModeStoreTest.kt`

**Interfaces:**
- Produces: `enum class GatewayMode { FAKE, REAL }`,
  `object GatewayModeStore { fun get(context: Context): GatewayMode; fun set(context: Context, mode: GatewayMode) }`
  — Task 16 reads this when constructing gateways.

- [ ] **Step 1:** Write the failing test (uses `androidx.test` Context — since
  `SharedPreferences` needs a real/Robolectric Context, and this project has no
  Robolectric configured, use `androidx.test:core` + `ApplicationProvider` is an
  *instrumented*-style dependency; to keep this a plain JVM unit test, back
  `GatewayModeStore` with a simple injectable in-memory map by default and a
  real `SharedPreferences`-backed variant used only by production code — test
  the pure logic without Android framework calls):
  ```kotlin
  package com.vitalguard.ai

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class GatewayModeStoreTest {
      @Test
      fun `defaults to FAKE when nothing has been set`() {
          val store = InMemoryGatewayModeStore()
          assertEquals(GatewayMode.FAKE, store.get())
      }

      @Test
      fun `set then get round-trips`() {
          val store = InMemoryGatewayModeStore()
          store.set(GatewayMode.REAL)
          assertEquals(GatewayMode.REAL, store.get())
      }
  }
  ```
- [ ] **Step 2:** Run to verify failure:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.GatewayModeStoreTest"`
  Expected: FAIL — `GatewayMode`/`InMemoryGatewayModeStore` don't exist.
- [ ] **Step 3:** Create `GatewayModeStore.kt` with a small interface so
  production (`SharedPreferences`) and tests (`InMemoryGatewayModeStore`) share
  one contract:
  ```kotlin
  package com.vitalguard.ai

  import android.content.Context

  enum class GatewayMode { FAKE, REAL }

  interface GatewayModeStore {
      fun get(): GatewayMode
      fun set(mode: GatewayMode)
  }

  class InMemoryGatewayModeStore(initial: GatewayMode = GatewayMode.FAKE) : GatewayModeStore {
      @Volatile private var current: GatewayMode = initial
      override fun get(): GatewayMode = current
      override fun set(mode: GatewayMode) {
          current = mode
      }
  }

  /** Production store: persists across process restarts via SharedPreferences,
   * so a live GATEWAY_MODE=REAL demo survives an app/service restart. */
  class PrefsGatewayModeStore(private val context: Context) : GatewayModeStore {
      private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

      override fun get(): GatewayMode {
          val raw = prefs.getString(KEY_MODE, GatewayMode.FAKE.name) ?: GatewayMode.FAKE.name
          return runCatching { GatewayMode.valueOf(raw) }.getOrDefault(GatewayMode.FAKE)
      }

      override fun set(mode: GatewayMode) {
          prefs.edit().putString(KEY_MODE, mode.name).apply()
      }

      companion object {
          private const val PREFS_NAME = "vital_guard_gateway_mode"
          private const val KEY_MODE = "mode"
      }
  }
  ```
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.GatewayModeStoreTest"`
  Expected: both PASS (they exercise `InMemoryGatewayModeStore` only —
  `PrefsGatewayModeStore` needs a real `Context` and is verified manually via
  ADB in Step 6, not unit-tested).
- [ ] **Step 5:** Create `GatewayModeReceiver.kt` — an ADB-triggerable broadcast
  so the mode can flip in seconds during a live demo:
  ```kotlin
  package com.vitalguard.ai

  import android.content.BroadcastReceiver
  import android.content.Context
  import android.content.Intent
  import android.util.Log

  class GatewayModeReceiver : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
          if (intent.action != ACTION_SET_GATEWAY_MODE) return
          val requested = intent.getStringExtra(EXTRA_MODE) ?: return
          val mode = runCatching { GatewayMode.valueOf(requested) }.getOrNull() ?: run {
              Log.w("VitalGuardGatewayMode", "Ignoring invalid GATEWAY_MODE value: $requested")
              return
          }
          PrefsGatewayModeStore(context).set(mode)
          Log.w("VitalGuardGatewayMode", "GATEWAY_MODE switched to $mode")
      }

      companion object {
          const val ACTION_SET_GATEWAY_MODE = "com.vitalguard.ai.SET_GATEWAY_MODE"
          const val EXTRA_MODE = "mode"
      }
  }
  ```
- [ ] **Step 6:** Register it in `AndroidManifest.xml` (add inside
  `<application>`, alongside the existing `BootCompletedReceiver`):
  ```xml
  <receiver
      android:name=".GatewayModeReceiver"
      android:exported="true">
      <intent-filter>
          <action android:name="com.vitalguard.ai.SET_GATEWAY_MODE" />
      </intent-filter>
  </receiver>
  ```
  Manual verification once installed: `adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode REAL`
  followed by `adb logcat -s VitalGuardGatewayMode` should show
  `GATEWAY_MODE switched to REAL`.
- [ ] **Step 7:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/GatewayModeStore.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/GatewayModeReceiver.kt \
          aaos-cockpit-app/app/src/main/AndroidManifest.xml \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/GatewayModeStoreTest.kt
  git commit -m "Add runtime-toggleable GATEWAY_MODE store + ADB-triggerable receiver"
  ```

---

## Task 13: `Real*` gateways — relocate existing VHAL/TTS logic behind the interfaces

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt`
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateOverrideReceiver.kt`
  (trim to the dormant-fallback role only)

**Interfaces:**
- Produces: `class RealClimateActuatorGateway(context: Context) : ClimateActuatorGateway`,
  `class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway` — Task 16
  constructs these when `GatewayModeStore.get() == GatewayMode.REAL`.
- Consumes: existing VHAL logic currently in `ClimateOverrideReceiver.kt`'s
  `overrideVehicleClimate`/`forEachSupportedArea` (lines 34-92) and the existing
  `VoiceEmergencyAssistant` class as-is (no changes needed there — it already
  exposes exactly `executeVoiceIntervention()`/`releaseFocus()`).

No new automated test in this task — `Real*` gateways make live VHAL/AudioManager
calls that cannot run in a plain JUnit test without Robolectric/instrumentation
(consistent with the existing codebase's total absence of instrumented tests).
Verification is manual, via the existing `adb shell am broadcast` smoke test
already documented in `README.md`, now re-pointed at the new gateway.

- [ ] **Step 1:** Move `overrideVehicleClimate`'s body and
  `forEachSupportedArea` verbatim into a new `RealClimateActuatorGateway`,
  appended to `ClimateActuatorGateway.kt`:
  ```kotlin
  // ClimateActuatorGateway.kt — append below FakeClimateActuatorGateway

  class RealClimateActuatorGateway(private val context: Context) : ClimateActuatorGateway {
      private val TAG = "VitalGuardClimate"

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

      override fun revertToBaseline() {
          try {
              val car = Car.createCar(context)
              val carPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_AC_ON) { area ->
                  carPropertyManager.setBooleanProperty(VehiclePropertyIds.HVAC_AC_ON, area, false)
              }
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_FAN_SPEED) { area ->
                  carPropertyManager.setIntProperty(VehiclePropertyIds.HVAC_FAN_SPEED, area, BASELINE_FAN_SPEED)
              }
              forEachSupportedArea(carPropertyManager, VehiclePropertyIds.HVAC_TEMPERATURE_SET) { area ->
                  carPropertyManager.setFloatProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, area, BASELINE_TEMPERATURE_C)
              }
              Log.d(TAG, "Climate reverted to baseline: AC=OFF, Fan=$BASELINE_FAN_SPEED, Temp=${BASELINE_TEMPERATURE_C}C")
          } catch (t: Throwable) {
              Log.e(TAG, "Failed to revert VHAL climate to baseline: ${t.message}")
              throw t
          }
      }

      private fun forEachSupportedArea(
          carPropertyManager: CarPropertyManager,
          propertyId: Int,
          action: (Int) -> Unit
      ) {
          val areaIds = carPropertyManager.getCarPropertyConfig(propertyId)?.areaIds
              ?: intArrayOf(FALLBACK_AREA_ID)
          for (area in areaIds) {
              try {
                  action(area)
              } catch (t: Throwable) {
                  Log.w(TAG, "Failed to set property 0x${propertyId.toString(16)} for area 0x${area.toString(16)}: ${t.message}")
              }
          }
      }
  }
  ```
  Add the needed imports at the top of `ClimateActuatorGateway.kt`:
  ```kotlin
  import android.car.Car
  import android.car.VehiclePropertyIds
  import android.car.hardware.property.CarPropertyManager
  import android.util.Log
  ```
  Note the behavior change from the original: `applyDrowsinessOverride`/
  `revertToBaseline` now **rethrow** after logging (`throw t`), rather than
  swallowing the exception — this is required so `DrowsinessController` (Task
  14) can actually observe and react to a gateway failure per spec Decision 6;
  swallowing it here would make the controller's crash-safety logic untestable
  and pointless.
- [ ] **Step 2:** Append `RealVoiceAlertGateway` to `VoiceAlertGateway.kt`:
  ```kotlin
  // VoiceAlertGateway.kt — append below FakeVoiceAlertGateway

  class RealVoiceAlertGateway(context: Context) : VoiceAlertGateway {
      private val assistant = VoiceEmergencyAssistant(context)

      override fun triggerAlert() {
          assistant.executeVoiceIntervention()
      }

      override fun stopAlert() {
          assistant.releaseFocus()
      }
  }
  ```
  Add `import android.content.Context` at the top of `VoiceAlertGateway.kt`.
- [ ] **Step 3:** Trim `ClimateOverrideReceiver.kt` to its dormant-fallback role
  only — it no longer needs its own VHAL logic (that moved to
  `RealClimateActuatorGateway`), but per spec Decision 3 it stays registered and
  functional as a manual on-stage safety net. Replace its body to delegate to
  the same gateway class (reusing the logic rather than duplicating it):
  ```kotlin
  package com.vitalguard.ai

  import android.content.BroadcastReceiver
  import android.content.Context
  import android.content.Intent
  import android.util.Log

  /**
   * Dormant manual on-stage fallback only (see design doc Decision 3) — not
   * wired to any automated sender. A person can still trigger this by hand via
   * `adb shell am broadcast -a com.vitalguard.ai.TRIGGER_ALERT` if the automated
   * HTTP network-pin pipeline fails live. The automated path is
   * DrowsinessController -> ClimateActuatorGateway/VoiceAlertGateway (Task 14/16).
   */
  class ClimateOverrideReceiver(
      private val voiceAssistant: VoiceEmergencyAssistant? = null
  ) : BroadcastReceiver() {
      private val TAG = "VitalGuardClimate"

      companion object {
          const val ACTION_TRIGGER_ALERT = "com.vitalguard.ai.TRIGGER_ALERT"
      }

      override fun onReceive(context: Context, intent: Intent) {
          if (intent.action != ACTION_TRIGGER_ALERT) return
          val score = intent.getFloatExtra("drowsiness_score", 0f)
          Log.w(TAG, "Manual fallback TRIGGER_ALERT received. Score: $score")
          try {
              RealClimateActuatorGateway(context).applyDrowsinessOverride()
          } catch (t: Throwable) {
              // RealClimateActuatorGateway rethrows (needed for DrowsinessController's
              // crash-safety, Task 14) — but THIS path is the dormant manual on-stage
              // fallback, invoked only when everything else has already failed, so it
              // must not itself crash the service. Restores the crash-safety the
              // original onReceive had (see the 2026-07-24 NoSuchMethodError fix this
              // class's history documents).
              Log.e(TAG, "Manual fallback climate override failed: ${t.message}")
          }
          voiceAssistant?.executeVoiceIntervention()
      }
  }
  ```
- [ ] **Step 4:** Build to confirm it compiles (no unit test exercises live VHAL
  code, so a successful build is the verification here):
  `./gradlew :app:compileDebugKotlin`
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Manual verification, per the existing README smoke test
  (unchanged mechanism, now backed by the relocated gateway logic): install the
  APK, then `adb shell am broadcast -a com.vitalguard.ai.TRIGGER_ALERT --ef drowsiness_score 0.95`
  and `adb logcat -s VitalGuardClimate VitalGuardVoice` — confirm the same
  climate/voice behavior as before this task (this is a refactor, not a
  behavior change, for the manual-fallback path).
- [ ] **Step 6:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateActuatorGateway.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VoiceAlertGateway.kt \
          aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/ClimateOverrideReceiver.kt
  git commit -m "Relocate VHAL/TTS logic into Real*Gateway implementations"
  ```

---

## Task 14: `DrowsinessController.kt` — the thin FSM

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt`

**Interfaces:**
- Consumes: `ClimateActuatorGateway`/`VoiceAlertGateway` (Task 11),
  `TriggerPayload` (Task 10).
- Produces:
  - `class DrowsinessController(climateGateway: ClimateActuatorGateway, voiceGateway: VoiceAlertGateway)`
  - `fun onPayload(payload: TriggerPayload)`
  - `fun onConnectionLost()`
  - `val lastGatewayAction: GatewayActionStatus` (read-only property)
  - `enum class GatewayActionStatus { NONE, OVERRIDE_APPLIED, OVERRIDE_FAILED, REVERTED, REVERT_FAILED }`
  Task 16 wires `TriggerPollClient`'s callbacks to `onPayload`/`onConnectionLost`.

- [ ] **Step 1:** Write the failing tests — this is the plan's most important
  test file; it implements all 6 cases from the spec's Testing section:
  ```kotlin
  package com.vitalguard.ai

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test

  class DrowsinessControllerTest {
      private lateinit var climate: FakeClimateActuatorGateway
      private lateinit var voice: FakeVoiceAlertGateway
      private lateinit var controller: DrowsinessController

      @Before
      fun setUp() {
          climate = FakeClimateActuatorGateway()
          voice = FakeVoiceAlertGateway()
          controller = DrowsinessController(climate, voice)
      }

      private fun payload(state: String, correlationId: String) = TriggerPayload(
          timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
          state = state,
          features = TriggerFeatures(perclos = 0.8f, eyeOpenProbability = 0.1f, headEulerAngleX = 28.0f),
          reason = "test", correlationId = correlationId,
      )

      @Test
      fun `normal operation below threshold never calls gateways`() {
          controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0001"))

          assertFalse(climate.overrideApplied)
          assertFalse(voice.alertTriggered)
      }

      @Test
      fun `idempotency - repeated CRITICAL with same correlationId fires gateways only once`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

          assertTrue(climate.overrideApplied)
          climate.overrideApplied = false // reset the flag to prove no SECOND call happened
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          assertFalse(climate.overrideApplied)
      }

      @Test
      fun `explicit RECOVERED reverts to safe baseline immediately`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onPayload(payload(TriggerPayload.STATE_NORMAL, "vg-0002"))

          assertTrue(climate.revertCalled)
          assertTrue(voice.stopCalled)
      }

      @Test
      fun `explicit UNKNOWN (lost-face) reverts to safe baseline immediately`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onPayload(payload(TriggerPayload.STATE_UNKNOWN, "vg-0002"))

          assertTrue(climate.revertCalled)
          assertTrue(voice.stopCalled)
      }

      @Test
      fun `connection-lost reverts to safe baseline without an explicit payload`() {
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          controller.onConnectionLost()

          assertTrue(climate.revertCalled)
          assertTrue(voice.stopCalled)
      }

      @Test
      fun `gateway throwing on apply is caught, does not crash, does not retry`() {
          climate.throwOnApply = true

          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))

          assertEquals(DrowsinessController.GatewayActionStatus.OVERRIDE_FAILED, controller.lastGatewayAction)
          // no crash reaching this line is itself part of what's being verified;
          // and a second identical payload must not trigger a retry of the same call:
          climate.throwOnApply = false
          controller.onPayload(payload(TriggerPayload.STATE_CRITICAL, "vg-0001"))
          assertFalse(climate.overrideApplied)
      }
  }
  ```
- [ ] **Step 2:** Run to verify failures:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DrowsinessControllerTest"`
  Expected: FAIL — `DrowsinessController` doesn't exist.
- [ ] **Step 3:** Create `DrowsinessController.kt`:
  ```kotlin
  package com.vitalguard.ai

  import android.util.Log

  /**
   * Thin FSM — trusts trigger_emitter.py's hysteresis/sustain/cooldown (any
   * CRITICAL payload received here already represents a sustained state); this
   * class only owns latch-until-explicit-signal, idempotency, connection-loss
   * fallback, and gateway crash-safety. See design doc Decision 6.
   */
  class DrowsinessController(
      private val climateGateway: ClimateActuatorGateway,
      private val voiceGateway: VoiceAlertGateway
  ) {
      enum class GatewayActionStatus { NONE, OVERRIDE_APPLIED, OVERRIDE_FAILED, REVERTED, REVERT_FAILED }

      private val TAG = "VitalGuardController"

      var lastGatewayAction: GatewayActionStatus = GatewayActionStatus.NONE
          private set

      private var latched = false
      private var lastCorrelationId: String? = null

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
              voiceGateway.triggerAlert()
              lastGatewayAction = GatewayActionStatus.OVERRIDE_APPLIED
          } catch (t: Throwable) {
              Log.e(TAG, "Gateway failure applying drowsiness override: ${t.message}")
              lastGatewayAction = GatewayActionStatus.OVERRIDE_FAILED
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
              voiceGateway.stopAlert()
              lastGatewayAction = GatewayActionStatus.REVERTED
          } catch (t: Throwable) {
              Log.e(TAG, "Gateway failure reverting to baseline: ${t.message}")
              lastGatewayAction = GatewayActionStatus.REVERT_FAILED
          }
      }
  }
  ```
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.DrowsinessControllerTest"`
  Expected: all 6 tests PASS. If `connection-lost reverts...` fails because
  `latched` was never set (test forgot the priming `onPayload(CRITICAL)` call) —
  it's already included above; if any other test fails, check that
  `lastCorrelationId` comparison isn't accidentally blocking the *second*
  distinct payload in a test (each non-idempotency test uses a different
  `correlationId` per call — confirm before debugging further).
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/DrowsinessController.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/DrowsinessControllerTest.kt
  git commit -m "Add DrowsinessController thin FSM with 6-case test suite"
  ```

---

## Task 15: `TriggerPollClient` — HTTP polling with consecutive-failure tracking

**Files:**
- Create: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPollClient.kt`
- Create: `aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt`

**Interfaces:**
- Consumes: `TriggerPayload` (Task 10).
- Produces:
  - `sealed class FetchResult { data class Success(val payload: TriggerPayload) : FetchResult(); object NoNewTrigger : FetchResult(); object Failure : FetchResult() }`
  - `interface TriggerFetcher { suspend fun fetchLatest(): FetchResult }`
  - `class HttpTriggerFetcher(baseUrl: String) : TriggerFetcher`
  - `class TriggerPollClient(fetcher: TriggerFetcher, scope: CoroutineScope, onPayload: (TriggerPayload) -> Unit, onConnectionLost: () -> Unit, pollIntervalMs: Long = 500L, failureThreshold: Int = 3)`
    with `fun start()` / `fun stop()`. Task 16 wires its `onPayload`/
    `onConnectionLost` callbacks straight to `DrowsinessController`'s
    `onPayload`/`onConnectionLost` (Task 14).

- [ ] **Step 1:** Write the failing tests, using a scripted `FakeTriggerFetcher`
  (no real network) and `kotlinx-coroutines-test`'s `runTest`/virtual time so
  the 500ms interval doesn't make the test suite slow:
  ```kotlin
  package com.vitalguard.ai

  import kotlinx.coroutines.test.StandardTestDispatcher
  import kotlinx.coroutines.test.TestScope
  import kotlinx.coroutines.test.advanceTimeBy
  import kotlinx.coroutines.test.runTest
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Test

  private class FakeTriggerFetcher(private val script: MutableList<FetchResult>) : TriggerFetcher {
      var callCount = 0
      override suspend fun fetchLatest(): FetchResult {
          callCount++
          return if (script.isNotEmpty()) script.removeAt(0) else FetchResult.NoNewTrigger
      }
  }

  private fun samplePayload(id: String) = TriggerPayload(
      timestampMs = 0L, source = "test", score = 0.9f, confidence = 1.0f,
      state = TriggerPayload.STATE_CRITICAL,
      features = TriggerFeatures(0.8f, 0.1f, 28.0f), reason = "test", correlationId = id,
  )

  class TriggerPollClientTest {
      @Test
      fun `delivers a successful payload to onPayload`() = runTest {
          val fetcher = FakeTriggerFetcher(mutableListOf(FetchResult.Success(samplePayload("vg-0001"))))
          var received: TriggerPayload? = null
          val client = TriggerPollClient(
              fetcher = fetcher, scope = this,
              onPayload = { received = it }, onConnectionLost = {},
              pollIntervalMs = 10L,
          )
          client.start()
          advanceTimeBy(15)
          client.stop()

          assertEquals("vg-0001", received?.correlationId)
      }

      @Test
      fun `three consecutive failures trigger onConnectionLost exactly once`() = runTest {
          val fetcher = FakeTriggerFetcher(mutableListOf(FetchResult.Failure, FetchResult.Failure, FetchResult.Failure))
          var lostCount = 0
          val client = TriggerPollClient(
              fetcher = fetcher, scope = this,
              onPayload = {}, onConnectionLost = { lostCount++ },
              pollIntervalMs = 10L, failureThreshold = 3,
          )
          client.start()
          advanceTimeBy(35)
          client.stop()

          assertEquals(1, lostCount)
      }

      @Test
      fun `a successful poll resets the consecutive-failure count`() = runTest {
          val fetcher = FakeTriggerFetcher(mutableListOf(
              FetchResult.Failure, FetchResult.Failure,
              FetchResult.Success(samplePayload("vg-0002")),
              FetchResult.Failure, FetchResult.Failure,
          ))
          var lostCount = 0
          val client = TriggerPollClient(
              fetcher = fetcher, scope = this,
              onPayload = {}, onConnectionLost = { lostCount++ },
              pollIntervalMs = 10L, failureThreshold = 3,
          )
          client.start()
          advanceTimeBy(55)
          client.stop()

          assertEquals(0, lostCount) // never reached 3 CONSECUTIVE failures
      }

      @Test
      fun `NoNewTrigger is not treated as a failure`() = runTest {
          val fetcher = FakeTriggerFetcher(mutableListOf(FetchResult.NoNewTrigger, FetchResult.NoNewTrigger))
          var lostCount = 0
          var received: TriggerPayload? = null
          val client = TriggerPollClient(
              fetcher = fetcher, scope = this,
              onPayload = { received = it }, onConnectionLost = { lostCount++ },
              pollIntervalMs = 10L,
          )
          client.start()
          advanceTimeBy(25)
          client.stop()

          assertEquals(0, lostCount)
          assertNull(received)
      }
  }
  ```
- [ ] **Step 2:** Run to verify failures:
  `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.TriggerPollClientTest"`
  Expected: FAIL — none of these types exist yet.
- [ ] **Step 3:** Create `TriggerPollClient.kt`:
  ```kotlin
  package com.vitalguard.ai

  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.launch
  import kotlinx.serialization.json.Json
  import java.io.BufferedReader
  import java.io.InputStreamReader
  import java.net.HttpURLConnection
  import java.net.URL

  sealed class FetchResult {
      data class Success(val payload: TriggerPayload) : FetchResult()
      object NoNewTrigger : FetchResult()
      object Failure : FetchResult()
  }

  interface TriggerFetcher {
      suspend fun fetchLatest(): FetchResult
  }

  /** Real fetcher: GET {baseUrl}/latest-trigger over the room-internal network-pin
   * only — see design doc Decision 3. 2s connect+read timeout. */
  class HttpTriggerFetcher(private val baseUrl: String) : TriggerFetcher {
      private val json = Json { ignoreUnknownKeys = true }

      override suspend fun fetchLatest(): FetchResult {
          return try {
              val connection = URL("$baseUrl/latest-trigger").openConnection() as HttpURLConnection
              connection.connectTimeout = 2000
              connection.readTimeout = 2000
              connection.requestMethod = "GET"
              when (connection.responseCode) {
                  200 -> {
                      val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
                      FetchResult.Success(json.decodeFromString<TriggerPayload>(body))
                  }
                  204 -> FetchResult.NoNewTrigger
                  else -> FetchResult.Failure
              }
          } catch (_: Exception) {
              FetchResult.Failure
          }
      }
  }

  class TriggerPollClient(
      private val fetcher: TriggerFetcher,
      private val scope: CoroutineScope,
      private val onPayload: (TriggerPayload) -> Unit,
      private val onConnectionLost: () -> Unit,
      private val pollIntervalMs: Long = 500L,
      private val failureThreshold: Int = 3
  ) {
      private var job: Job? = null
      private var consecutiveFailures = 0

      fun start() {
          job = scope.launch {
              while (true) {
                  when (val result = fetcher.fetchLatest()) {
                      is FetchResult.Success -> {
                          consecutiveFailures = 0
                          onPayload(result.payload)
                      }
                      FetchResult.NoNewTrigger -> {
                          consecutiveFailures = 0
                      }
                      FetchResult.Failure -> {
                          consecutiveFailures++
                          if (consecutiveFailures == failureThreshold) {
                              onConnectionLost()
                          }
                      }
                  }
                  delay(pollIntervalMs)
              }
          }
      }

      fun stop() {
          job?.cancel()
          job = null
      }
  }
  ```
- [ ] **Step 4:** Run: `./gradlew :app:testDebugUnitTest --tests "com.vitalguard.ai.TriggerPollClientTest"`
  Expected: all 4 tests PASS. (If `runTest`'s virtual-clock timing is flaky
  because `advanceTimeBy` and the internal `delay` don't align exactly on poll
  boundaries, adjust the test's `advanceTimeBy` values to fall clearly *after*
  the Nth expected poll rather than exactly on it — e.g. `advanceTimeBy(35)` for
  3 polls at a 10ms interval leaves a safety margin.)
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/TriggerPollClient.kt \
          aaos-cockpit-app/app/src/test/java/com/vitalguard/ai/TriggerPollClientTest.kt
  git commit -m "Add TriggerPollClient with consecutive-failure connection-loss detection"
  ```

---

## Task 16: Wire everything into `VitalGuardMonitorService`

**Files:**
- Modify: `aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt`

**Interfaces:**
- Consumes: `GatewayModeStore`/`PrefsGatewayModeStore` (Task 12),
  `FakeClimateActuatorGateway`/`RealClimateActuatorGateway`,
  `FakeVoiceAlertGateway`/`RealVoiceAlertGateway` (Tasks 11/13),
  `DrowsinessController` (Task 14), `TriggerPollClient`/`HttpTriggerFetcher`
  (Task 15).
- Produces: nothing further downstream — this is the final integration point.

No new unit test in this task (a `Service` requires instrumentation to test
meaningfully, consistent with the rest of this codebase); verify via the manual
Day-1 task already specified in the design doc (Step 3 below).

- [ ] **Step 1:** Decide the Container Node's address. Add a constant for now
  (a build-time-configurable value is out of scope for this plan — flag it as a
  follow-up, not a blocker): `private const val CONTAINER_NODE_BASE_URL = "http://192.168.49.2:8765"`
  — replace the placeholder IP with whatever the room-internal network pin
  actually assigns once verified (this is exactly the Day-1 verification task
  in the design doc).
- [ ] **Step 2:** Rewrite `VitalGuardMonitorService.kt`:
  ```kotlin
  package com.vitalguard.ai

  import android.app.NotificationChannel
  import android.app.NotificationManager
  import android.app.Service
  import android.content.Intent
  import android.content.IntentFilter
  import android.os.Build
  import android.os.IBinder
  import androidx.core.app.NotificationCompat
  import androidx.core.content.ContextCompat
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.cancel

  /**
   * Foreground service hosting the automated trigger pipeline: TriggerPollClient
   * -> DrowsinessController -> Climate/Voice gateways. Also keeps
   * ClimateOverrideReceiver registered as the dormant manual on-stage fallback
   * (see design doc Decision 3) — unrelated to the automated path below.
   */
  class VitalGuardMonitorService : Service() {

      private val voiceAssistant by lazy { VoiceEmergencyAssistant(this) }
      private val climateOverrideReceiver by lazy { ClimateOverrideReceiver(voiceAssistant) }
      private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      private lateinit var pollClient: TriggerPollClient

      override fun onCreate() {
          super.onCreate()
          startForeground(NOTIFICATION_ID, buildNotification())

          val filter = IntentFilter(ClimateOverrideReceiver.ACTION_TRIGGER_ALERT)
          ContextCompat.registerReceiver(this, climateOverrideReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

          val gatewayModeStore = PrefsGatewayModeStore(this)
          val climateGateway: ClimateActuatorGateway = when (gatewayModeStore.get()) {
              GatewayMode.REAL -> RealClimateActuatorGateway(this)
              GatewayMode.FAKE -> FakeClimateActuatorGateway()
          }
          val voiceGateway: VoiceAlertGateway = when (gatewayModeStore.get()) {
              GatewayMode.REAL -> RealVoiceAlertGateway(this)
              GatewayMode.FAKE -> FakeVoiceAlertGateway()
          }
          val controller = DrowsinessController(climateGateway, voiceGateway)

          pollClient = TriggerPollClient(
              fetcher = HttpTriggerFetcher(CONTAINER_NODE_BASE_URL),
              scope = serviceScope,
              onPayload = { payload -> controller.onPayload(payload) },
              onConnectionLost = { controller.onConnectionLost() },
          )
          pollClient.start()
      }

      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

      override fun onDestroy() {
          pollClient.stop()
          serviceScope.cancel()
          unregisterReceiver(climateOverrideReceiver)
          voiceAssistant.releaseFocus()
          super.onDestroy()
      }

      override fun onBind(intent: Intent?): IBinder? = null

      private fun buildNotification(): android.app.Notification {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              val channel = NotificationChannel(
                  CHANNEL_ID,
                  "Vital-Guard Monitoring",
                  NotificationManager.IMPORTANCE_LOW
              )
              getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
          }
          return NotificationCompat.Builder(this, CHANNEL_ID)
              .setContentTitle("Vital-Guard AI")
              .setContentText("Monitoring driver alertness")
              .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
              .setOngoing(true)
              .build()
      }

      companion object {
          private const val NOTIFICATION_ID = 1
          private const val CHANNEL_ID = "vital_guard_monitor"
          private const val CONTAINER_NODE_BASE_URL = "http://192.168.49.2:8765"
      }
  }
  ```
- [ ] **Step 3:** Build: `./gradlew :app:assembleDebug`
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 4:** Manual end-to-end verification (this is the Day-1 task from
  the design doc — do this before considering the network-pin path "done"):
  1. On the Container Node (or a dev machine standing in for it during initial
     testing), run `cd dms-ai-engine && python main.py --mock --host 0.0.0.0 --port 8765`.
  2. Confirm `CONTAINER_NODE_BASE_URL` in `VitalGuardMonitorService.kt` matches
     the Container Node's actual address on the room-internal network pin (or,
     for a same-machine smoke test first, point it at
     `http://10.0.2.2:8765` from an emulator, or the dev machine's LAN IP from
     a real device).
  3. Install and launch the app; `adb logcat -s VitalGuardController` should
     show the controller reacting once the mock scenario's CRITICAL fires
     around t≈2.1s into the mock run, and again on RECOVERED shortly after.
  4. Confirm no crash and no repeated firing while the mock script re-runs.
- [ ] **Step 5:** Commit:
  ```bash
  git add aaos-cockpit-app/app/src/main/java/com/vitalguard/ai/VitalGuardMonitorService.kt
  git commit -m "Wire TriggerPollClient -> DrowsinessController -> gateways into VitalGuardMonitorService"
  ```

---

## Task 17: Documentation fixes (spec Decision 8)

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:** none — pure documentation, no code dependency.

- [ ] **Step 1:** Update `README.md`'s `dms-ai-engine/` module description (the
  section describing delivery mechanism, currently claiming "via CarSky's
  shell/ADB bridge") and its smoke-test section, to describe the actual local
  HTTP network-pin:
  ```markdown
  ### `dms-ai-engine/`
  Python + OpenCV + MediaPipe Face Mesh drowsiness detector. Computes an EAR-based
  drowsiness score over a sliding window and, on a sustained threshold crossing,
  serves a trigger event over a local HTTP endpoint (`trigger_server.py`,
  `GET /latest-trigger`) on the room-internal network pin — no external/cloud
  call is made anywhere in this path.

  Setup:
  ```bash
  cd dms-ai-engine
  pip install -r requirements.txt
  python main.py --mock
  ```

  ### `aaos-cockpit-app/`
  Kotlin Android Automotive app. Polls the Container Node's `/latest-trigger`
  endpoint (`TriggerPollClient`) and drives `DrowsinessController`, which:
  - Overrides `HVAC_AC_ON` / `HVAC_FAN_SPEED` / `HVAC_TEMPERATURE_SET` via
    `ClimateActuatorGateway` (VHAL/`CarPropertyManager`).
  - Takes exclusive audio focus, mutes media, and speaks a safety alert via
    `VoiceAlertGateway` (TTS).

  `GATEWAY_MODE` (Fake/Real) is runtime-toggleable:
  `adb shell am broadcast -a com.vitalguard.ai.SET_GATEWAY_MODE --es mode REAL`

  Open in Android Studio (Koala+), with an Automotive (1024p landscape) API 33
  virtual device.

  ## Manual on-stage fallback (if the automated pipeline fails live)

  `ClimateOverrideReceiver` stays registered as a dormant, human-operated safety
  net — not wired to any automated sender:
  ```bash
  adb shell am broadcast -a com.vitalguard.ai.TRIGGER_ALERT --ef drowsiness_score 0.95
  adb logcat -s VitalGuardClimate VitalGuardVoice
  ```
  ```
  (Replace the corresponding existing sections in `README.md` with this text —
  keep the repo-layout tree and everything else unchanged.)
- [ ] **Step 2:** In `CLAUDE.md`, update the "Trigger Delivery" section (under
  "Architectural Decisions Already Locked In" and the dedicated "Trigger
  Delivery" subsection) to state: the local HTTP network-pin
  (`trigger_server.py` + `TriggerPollClient`) is the implemented mechanism; the
  CarSky control-plane Shell-Exec approach was considered and explicitly
  rejected for violating the no-cloud-round-trip principle (cite
  `docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md`
  Decision 3); `ClimateOverrideReceiver`'s `BroadcastReceiver` is intentionally
  kept as a dormant, human-operated fallback only.
- [ ] **Step 3:** In `CLAUDE.md`'s "Data Contract" section, replace the
  `contracts/trigger.schema.json` example with the actual nested shape from
  Task 2 (including `state` now allowing `"UNKNOWN"`).
- [ ] **Step 4:** In `CLAUDE.md`'s "Implementation Status" section, replace the
  claims about `DrowsinessController.kt`/`VehicleGateway.kt` already existing
  with an accurate list: `DrowsinessController.kt`, `ClimateActuatorGateway.kt`,
  `VoiceAlertGateway.kt`, `TriggerPollClient.kt`, `GatewayModeStore.kt` — built
  via this plan, tests passing (list the actual test file names from Tasks
  10-15).
- [ ] **Step 5:** Add the "Known Deviations from Proposal" note to CLAUDE.md's
  "Reference Basis" section:
  ```markdown
  ## Known Deviations from Proposal

  The submitted proposal names MobileNetV3-Small (INT8-quantized) as the eye-state
  backbone. The implemented pipeline uses MediaPipe FaceMesh (a pre-trained,
  well-validated landmark model) with geometric EAR/head-pose extraction instead
  of a self-trained classifier. If asked by judges:

  > "Chúng tôi dùng MediaPipe FaceMesh (model landmark đã được kiểm chứng) thay vì
  > tự train MobileNetV3 — PERCLOS/EAR và ngưỡng 0.85 sustained giữ nguyên như
  > proposal, chỉ thay phần trích xuất landmark."
  ```
- [ ] **Step 6:** Commit:
  ```bash
  git add README.md CLAUDE.md
  git commit -m "Update README/CLAUDE.md to match the implemented delivery architecture"
  ```

---

## Self-Review Notes

- **Spec coverage:** Decision 1 → Task 1. Decision 2 → Tasks 7-8. Decision 3 →
  Tasks 5, 15, 16. Decision 4 → Task 2. Decision 5 → Tasks 3-4. Decision 6 →
  Task 14. Decision 7 → Tasks 11-13. Decision 8 → Task 17. All 8 decisions have
  at least one task producing a testable deliverable.
- **Sequencing:** Python schema/emitter/server work (Tasks 2-6) lands before any
  Kotlin task consumes the schema shape (Task 10 onward) — Kotlin's
  `TriggerPayload` is hand-matched to the schema file from Task 2, not
  generated from it, so this ordering matters for correctness, not just
  convenience. Tasks 9-14 (Gradle deps, payload parsing, gateways, FSM) have no
  dependency on the real HTTP transport and were sequenced to be testable
  against Fakes before Task 15 (real poll client) and Task 16 (wiring) land.
  Tasks 7-8 (real MediaPipe CV) are independent of the Kotlin track entirely and
  could run in parallel with it if two people are working simultaneously.
- **Type consistency check:** `TriggerEmitter.update()` return type
  (`Optional[str]`, Task 3) is consumed correctly in Task 6's `main.py` rewrite
  (`signal in ("CRITICAL", "RECOVERED")`). `FacePresenceTracker.update()`
  return type is consumed the same way. Kotlin's `TriggerPayload.state` is a
  plain `String` compared against `TriggerPayload.STATE_*` constants
  consistently across Tasks 10, 14, and 15's test payloads.
- **No placeholders:** every step above contains complete, runnable code —
  verified by re-reading each task's Step 3-equivalent implementation block.
