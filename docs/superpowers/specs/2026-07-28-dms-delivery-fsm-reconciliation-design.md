# DMS Pipeline / Trigger Delivery / Kotlin FSM Reconciliation — Design

**Date:** 2026-07-28
**Author:** Phát (CV/DMS pipeline), reconciling with Tài's (AAOS app) side
**Status:** Approved by Phát — pending review by Tài / mentor Phương Anh before implementation
**Deadline context:** 13 days remain until Round 2 submission (10/08/2026)

## Background

`CLAUDE.md`'s documented architecture (Container Node Python DMS → HTTP/WebSocket
"network pin" → Kotlin FSM with `DrowsinessController.kt` and Fake/Real gateways) is
largely **aspirational**. Deep-research into the actual repo state (2026-07-28) found:

- Two disconnected, competing Python implementations in `dms-ai-engine/`:
  - **Track A** (`dms_detector.py`, committed 2026-07-24): OpenCV+MediaPipe capture
    loop, but `calculate_ear()` is a bare `pass` stub with a hardcoded
    `ear = 0.15 if face else 0.3` placeholder. Delivers triggers via an HTTP POST to a
    CarSky control-plane API that runs `am broadcast` on the target VM. This is the
    only mechanism actually wired end-to-end to the Kotlin app today (via the README's
    manual smoke-test instructions).
  - **Track B** (`main.py` / `score_calculator.py` / `trigger_emitter.py` /
    `test_dms.py`, uncommitted): correct 0.55/0.25/0.20 scoring formula, real
    hysteresis (0.85 up / 0.50 down) + 2s sustain + 10s cooldown, 10 passing unit
    tests — but the real-video branch raises `NotImplementedError` (only `--mock`
    works), and it POSTs a custom JSON payload to a `--trigger-url` that matches
    neither Track A's broadcast extra nor CLAUDE.md's documented
    `contracts/trigger.schema.json` (which does not exist anywhere in the repo).
  - Critically, `TriggerEmitter.update()` only ever returns `True` **once**, on the
    rising edge into CRITICAL (verified by reading `trigger_emitter.py` and
    `main.py` directly) — there is no periodic "still CRITICAL" signal and no
    recovery signal at all today, on either track.
- No `contracts/trigger.schema.json`, no `script-node/` Luau files, no `docs/`
  directory, and **none** of the Kotlin files CLAUDE.md's "Implementation Status"
  claims exist (`DrowsinessController.kt`, `ClimateActuatorGateway.kt`,
  `VoiceAlertGateway.kt`, `DrowsinessControllerTest.kt` — zero matches anywhere).
- What actually exists on the Kotlin side (`aaos-cockpit-app/`) is a foreground
  `Service` with a dynamically-registered `BroadcastReceiver`
  (`ClimateOverrideReceiver.kt`, `VoiceEmergencyAssistant.kt`) calling
  `CarPropertyManager`/`AudioManager`/`TextToSpeech` directly — no FSM, no gateway
  abstraction, no tests.
- `CLAUDE.md` itself has never been committed (explicitly gitignored by the team) —
  it is a local planning document, not a team-agreed, checked-in spec.

This design reconciles the two Python tracks, defines the real trigger delivery
mechanism and payload schema, and scopes a minimal but real Kotlin FSM + gateway
layer — all sized to what's achievable in the 13 days remaining, per CLAUDE.md's own
principle: *working code with evidence over polishing unvalidated theory.*

**Revision note:** an earlier draft of this design proposed reusing Track A's
CarSky-control-plane HTTP delivery mechanism (relabeled "CarSky Shell-Exec API
delivery") as the default trigger path. Review caught that this is a genuine cloud
round-trip in the safety-critical path — it calls an external CarSky host outside
the room's internal network, directly contradicting CLAUDE.md's own non-negotiable
principle ("no cloud round-trip... must not depend on external connectivity") and
adding unpredictable external-hop latency on top of the ≤150ms detection-latency KPI.
That mechanism has been dropped entirely from this design (see Decision 3) in favor
of CLAUDE.md's originally-documented local HTTP network-pin.

## Decisions

### 1. Track B becomes canonical; Track A is deleted

`main.py` / `score_calculator.py` / `trigger_emitter.py` / `test_dms.py` become the
one DMS implementation going forward. `dms_detector.py` is deleted outright — no part
of it (capture loop, EAR stub, or its CarSky-control-plane delivery call) is reused;
the new delivery mechanism is built fresh in Decision 3.

**Rationale:** Track B already has the correct scoring formula and the state-machine
hardening (hysteresis/sustain/cooldown) that CLAUDE.md mandates, with 10 passing
tests. Maintaining two divergent, unconnected implementations for the remaining 13
days is pure risk, and Track A's stubbed EAR/delivery code isn't worth carrying
forward.

**Verified before deletion:** grepped the full repo for `dms_detector` — the only
reference is a `python dms_detector.py` run command in `README.md`. No code imports
it. Safe to delete; the README snippet is updated to the `main.py` invocation as
part of the doc fix in Decision 8.

### 2. Real CV inference: full MediaPipe FaceMesh pipeline, not mock-only

`main.py`'s real-video branch (currently `raise NotImplementedError`) gets wired to
real MediaPipe FaceMesh landmark extraction:
- Real EAR (eye aspect ratio, left/right average) from FaceMesh landmarks feeds
  `perclos` / `eye_closed_now` in `score_calculator.py`.
- Landmark-based head-pose estimation feeds `headEulerAngleX`.
- `--mock` mode is kept as-is for CI/offline testing of the scoring/emitter logic
  independent of camera input.

**Rationale:** this is the single largest functional gap in the whole project — both
prior tracks fake the actual CV inference (a stubbed placeholder or a
`NotImplementedError`). Without it there is no real demo, only a scripted mock
sequence.

### 3. Trigger delivery: local HTTP network-pin — Container Node server, Kotlin poll client. No cloud round-trip, no CarSky control-plane call, anywhere.

CLAUDE.md's own non-negotiable principle: *"the entire pipeline runs on-device/edge,
with no cloud round-trip in the safety-critical path... no network hop leaves the
environment."* Any mechanism that calls an external CarSky control-plane host (as
Track A's `send_trigger()` did) violates this outright and puts unpredictable
external-hop latency into the ≤150ms detection-latency KPI. That mechanism is
dropped completely — not kept as a fallback, not relabeled, removed from the design.

**Chosen architecture — exactly CLAUDE.md's original documented design:**

- **Container Node (Python):** a new lightweight local HTTP server —
  `dms-ai-engine/trigger_server.py` — runs alongside the scoring/emitter loop (in the
  same process, background thread) and exposes `GET /latest-trigger`, returning the
  most recent trigger payload (see Decision 4/5 for shape and signal types) as a
  JSON body, or `204 No Content` if nothing new since the last successful poll. It
  listens only on the room-internal network (the network pin added directly between
  the Container Node and the Skycraft VM, self-service, no BTC approval needed —
  same mechanism CLAUDE.md already sanctions for this link). No external host is
  ever contacted.
- **Skycraft App (Kotlin):** a new HTTP poll client inside the existing
  `VitalGuardMonitorService` foreground service. It polls `GET /latest-trigger`
  every **500 ms**, with a **2 s** per-request timeout, and feeds any new payload
  directly into `DrowsinessController.kt`. Three consecutive failed/timed-out polls
  (worst case ≈6 s to detect) means the connection is considered lost — the FSM
  reverts to a safe baseline (Decision 6). This is a real, observable technical
  signal (poll failure), not a guessed heartbeat number.
- **The existing `BroadcastReceiver` (`ClimateOverrideReceiver.kt`) is left in place,
  untouched, but demoted to a dormant, human-operated on-stage safety net** — per
  CLAUDE.md's own existing "Demo fallback" tier (`adb shell cmd car_service
  inject-vhal-event`), a person can still manually run `adb shell am broadcast -a
  com.vitalguard.ai.TRIGGER_ALERT` if the entire automated pipeline fails live. It is
  **not** wired to any automated Python sender going forward — no code invests in
  building that link.

**Latency-budget note:** the ≤150ms KPI is the per-frame CV detection/inference
budget, not the end-to-end onset-to-cockpit-response time — that end-to-end time is
already dominated by `trigger_emitter.py`'s 2s sustain window, so ~500ms of added
polling latency is proportionally small (total ≈2.5s onset-to-response), consistent
with the demo script's minute-scale timeline.

**Day-1 task:** build and verify this local HTTP network-pin end-to-end — Container
Node serves `/latest-trigger` on the room-internal network; the App's new poll
client successfully fetches and parses a synthetic trigger payload and feeds it into
`DrowsinessController.kt` — before building anything further (FSM hardening,
gateways) on top of it.

### 4. `contracts/trigger.schema.json`: nested JSON, delivered as an HTTP response body

Since the production path is now HTTP JSON (not Android `Intent` broadcast extras),
the earlier flattening requirement no longer applies — HTTP JSON supports nested
objects natively, so the schema reverts to CLAUDE.md's original nested shape:

```json
{
  "timestampMs": 0,
  "source": "container-python|debug|replay",
  "score": 0.0,
  "confidence": 0.0,
  "state": "NORMAL|WARNING|CRITICAL|UNKNOWN",
  "features": {
    "perclos": 0.0,
    "eyeOpenProbability": 0.0,
    "headEulerAngleX": 0.0
  },
  "reason": "string",
  "correlationId": "string"
}
```

The `state` enum gains `UNKNOWN` (Decision 5) — CLAUDE.md's own FSM-hardening section
already uses the language "fall back to an unknown/safe state," so this formalizes
an existing intent rather than introducing a new concept.

**Superseded note:** an earlier draft of this decision flattened the schema to match
Android Intent-extra type limits (`--es`/`--ef`/`--el`), because the default delivery
path was assumed to be an `am broadcast` command. That assumption no longer holds
(Decision 3) — no code in this design constructs a shell command or Intent-extra
payload, so the flat-schema requirement and its associated shell-escaping concern
(`shlex.quote()`) are both moot and removed.

### 5. Emission model: add explicit RECOVERED and UNKNOWN signals, not just single-shot CRITICAL

`TriggerEmitter.update()` today only returns `True` once, on the rising edge into
CRITICAL (verified in `trigger_emitter.py:31-46`) — there is no signal at all for
recovery or for a lost/undetectable face. Relying on a guessed timeout to infer
either condition on the Kotlin side would revert HVAC/voice to baseline **while a
driver is still critically drowsy**, which is the opposite of the safety intent. So
the emission model itself is extended:

- **RECOVERED:** emit once on the down-edge, mirroring the existing up-edge emit —
  when score drops to `≤ exit_threshold` (the same point where `_armed` currently
  becomes `True` silently), emit a payload with `state="WARNING"` or `"NORMAL"`
  (whichever the score justifies) and a fresh `correlationId`.
- **UNKNOWN (lost face):** when MediaPipe reports no face for a sustained period
  (a new, separate threshold — proposed default: same 2.0s `sustain_seconds` used
  for CRITICAL, to keep one mental model for "how long is long enough to act on"),
  emit once with `state="UNKNOWN"`, `reason="lost_face"`.

This gives the Kotlin FSM (Decision 6) explicit signals to act on for the *normal*
recovery/lost-face paths, and confines connection-loss detection to the poll client's
own consecutive-failure count (Decision 3) — two distinct, independently-observable
failure modes (data-quality vs. connectivity), each with its own signal, rather than
one overloaded timeout guess.

### 6. Kotlin FSM: thin scope — latch-until-explicit-signal, not hysteresis duplication

`DrowsinessController.kt` (does not exist today — built from scratch) does **not**
re-implement hysteresis/debounce/cooldown; `trigger_emitter.py` already guarantees
any CRITICAL payload represents a sustained, hysteresis-gated state.

What Kotlin owns:
- **Latch-until-explicit-signal:** on receiving CRITICAL, fire the gateways and hold
  that state until an explicit `RECOVERED` or `UNKNOWN` payload arrives (Decision 5)
  — not a timeout guess.
- **Connection-loss fallback:** 3 consecutive poll failures (Decision 3) → revert to
  a safe baseline (AC off, fan 2, temp 25°C, stop voice alert), distinct in the debug
  overlay/logs from an explicit `UNKNOWN` (lost-face) revert, even though both lead
  to the same gateway action.
- **Idempotency:** dedupe by `correlationId` — a repeated/duplicate delivery of the
  same payload (e.g. a retried poll) does not re-fire the gateways a second time.
- **Crash-safety:** if a gateway call throws (Real gateway VHAL/AudioManager
  failure), the controller catches it, logs it, does **not** crash, and does **not**
  retry the same call — it stays in its current tracked state and acts again only on
  the next state transition or next payload. No retry loop, to avoid masking a
  persistently broken Real gateway behind silent repeated attempts; a broken Real
  gateway is exactly what `GATEWAY_MODE` exists to let the team fall back away from
  within seconds.
- **Debug-overlay visibility on gateway failure:** a caught gateway exception must
  update the overlay-visible "last gateway action" field to something like `"last
  action: FAILED (see log)"` — CLAUDE.md's mandatory debug overlay already requires
  showing the last gateway action, and a silent logcat-only failure means nobody
  watching the demo knows `GATEWAY_MODE` needs to be flipped to Fake.

**Rationale:** with zero existing Kotlin FSM footprint and 13 days left, building the
full 5-property hardening independently on the Kotlin side would mean duplicating
logic that already works and is unit-tested in Python. The thin scope closes the
real gaps — duplicate delivery double-firing HVAC/voice, a dead connection leaving
the cabin stuck in CRITICAL, a silent gateway failure going unnoticed — without
duplicating proven logic.

### 7. Fake/Real gateway split: built now

`ClimateActuatorGateway` / `VoiceAlertGateway` interfaces, each with `Fake*`/`Real*`
implementations. `Real*` wraps the *existing* `ClimateOverrideReceiver` /
`VoiceEmergencyAssistant` VHAL/AudioManager+TTS logic behind the interface — that
logic is not rewritten, only relocated. `GATEWAY_MODE` is a **runtime-toggleable**
flag (not build-flavor-only), so Real→Fake can flip in seconds if something breaks
on stage.

**Rationale:** this is what makes `DrowsinessControllerTest.kt` possible at all — FSM
tests assert against a `Fake`, not a live VHAL call — and it's what CLAUDE.md's
Go/No-Go criteria require as a demo-safety fallback.

### 8. Documentation: minimal fixes now, full docs deferred to day 9 (already scheduled)

In scope now (fixing stale/broken state, not new authoring):
- `README.md`'s `python dms_detector.py` run instruction → updated to the `main.py`
  invocation (Decision 1's orphan-check fix).
- `CLAUDE.md`'s "Implementation Status" section corrected to stop claiming
  `DrowsinessController.kt`/gateway files/tests exist under names that were never
  built.
- `CLAUDE.md`'s "Trigger Delivery" section updated to describe the local HTTP
  network-pin exactly as implemented (Decision 3), and to explicitly state that the
  CarSky control-plane delivery mechanism was considered and rejected for violating
  the no-cloud-round-trip principle — not silently dropped.
- `CLAUDE.md`'s schema description updated to the nested shape (Decision 4), with
  the `UNKNOWN` state value added.
- A note that `ClimateOverrideReceiver.kt`'s `BroadcastReceiver` is intentionally
  kept as a dormant, human-operated on-stage fallback only (Decision 3) — so nobody
  mistakes it for a still-live automated path or invests further engineering effort
  wiring it up.
- A "Known Deviations from Proposal" note added to CLAUDE.md's Reference Basis
  section, with a prepared judge-facing answer for the MediaPipe-vs-MobileNetV3
  deviation:
  > *"Chúng tôi dùng MediaPipe FaceMesh (model landmark đã được kiểm chứng) thay vì
  > tự train MobileNetV3 — PERCLOS/EAR và ngưỡng 0.85 sustained giữ nguyên như
  > proposal, chỉ thay phần trích xuất landmark."*

Deferred to the already-scheduled day 9 (07/08) slot: full `docs/` authoring
(technical-deep-dive, implementation-plan, runbook), and the full README rewrite.

## Architecture (resulting data flow)

```
[Camera/video] → MediaPipe FaceMesh → score_calculator.py (real EAR/PERCLOS/head-pose)
                                            │
                                     trigger_emitter.py
                                     (hysteresis 0.85/0.50, 2s sustain, 10s cooldown;
                                      + RECOVERED on down-edge; + UNKNOWN on lost-face)
                                            │  updates in-memory "latest trigger" state
                                            ▼
                     trigger_server.py — local HTTP server (Container Node, new)
                        GET /latest-trigger → nested JSON per contracts/trigger.schema.json
                                               (200) or 204 if nothing new
                        [room-internal network pin only — no external host, ever]
                                            ▼
                    VitalGuardMonitorService — new HTTP poll client (Kotlin, new)
                        polls every 500ms, 2s timeout/request
                        3 consecutive failures → connection-lost → safe baseline
                                            ▼
                              DrowsinessController.kt (thin FSM — new)
                    latch CRITICAL until explicit RECOVERED/UNKNOWN/connection-lost
                          + idempotency (correlationId) + gateway-exception
                            crash-safety, surfaced in the debug overlay
                                    ├─ ClimateActuatorGateway (Fake/Real, GATEWAY_MODE — new)
                                    └─ VoiceAlertGateway (Fake/Real, GATEWAY_MODE — new)

[Dormant, unchanged: ClimateOverrideReceiver.kt's BroadcastReceiver still exists and
 still responds to a manually-typed `adb shell am broadcast -a
 com.vitalguard.ai.TRIGGER_ALERT`, kept only as a human-operated on-stage safety net
 per CLAUDE.md's existing "Demo fallback" tier — not wired to any automated sender.]
```

## Error handling / edge cases

- **HTTP server unreachable / connection lost** (Container Node down, network pin
  drops) → detected client-side via 3 consecutive failed/timed-out polls (2s
  timeout each, ≈6s worst case) — a real technical signal, not a guessed heartbeat.
  FSM reverts to safe baseline.
- **Lost face** (camera/MediaPipe stops detecting a face for a sustained period) →
  explicit `UNKNOWN` signal from Python (Decision 5), distinct in cause from a dead
  connection even though the FSM outcome (revert to safe baseline) is the same —
  the debug overlay/logs show which one actually happened.
- **Recovery** → explicit `RECOVERED` signal on the down-edge; FSM reverts to safe
  baseline immediately on receipt, without waiting on any timeout.
- **Duplicate/replayed payloads** (e.g. a retried poll returning the same trigger
  twice) → `correlationId` dedupe absorbs them without a second HVAC/voice trigger.
- **Gateway call throws** (Real VHAL/AudioManager failure) → caught and logged by
  `DrowsinessController.kt`, surfaced in the debug overlay's "last gateway action"
  field as `FAILED`; the controller keeps running rather than crashing the app, and
  does not retry.

## Testing

**Python** (`dms-ai-engine/`):
- Existing 10 tests in `test_dms.py` stay as-is.
- New tests for the real MediaPipe EAR/head-pose path (fixture video or synthetic
  landmark input asserting expected EAR/head-pose values).
- New tests for `TriggerEmitter`: emits `RECOVERED` exactly once on the down-edge
  (mirroring the existing up-edge test), emits `UNKNOWN` after a sustained lost-face
  period, does not double-emit either.
- New tests for `trigger_server.py`: serves the correct latest payload on `GET
  /latest-trigger`, returns `204` when nothing new, matches the nested schema
  exactly (including the `UNKNOWN` state value).

**Kotlin** (`aaos-cockpit-app/`), `DrowsinessControllerTest.kt` against
`FakeClimateActuatorGateway`/`FakeVoiceAlertGateway`:
1. Normal operation — score/state below threshold → gateways never called.
2. Idempotency — repeated CRITICAL payload with the same `correlationId` → gateway
   called exactly once.
3. Explicit RECOVERED — reverts to safe baseline immediately on receipt.
4. Explicit UNKNOWN (lost-face) — reverts to safe baseline immediately on receipt.
5. Connection-lost (3 simulated consecutive poll failures) — reverts to safe
   baseline without ever receiving an explicit RECOVERED/UNKNOWN payload.
6. Gateway failure — `Fake*Gateway` throws → controller catches it, logs it, does
   not crash, does not retry, and the debug-overlay-visible last-action field shows
   `FAILED`.

New poll-client tests: parses a valid nested JSON payload correctly; treats a `204`
or malformed response as "no new trigger" (not a poll failure); correctly counts
consecutive failures and resets the count on any successful poll.

## Out of scope for this design

- Full 5-property Kotlin FSM hardening (Decision 6) — explicitly deferred; Python
  already owns hysteresis/debounce/cooldown.
- WebSocket upgrade — simple HTTP polling was chosen over a persistent WebSocket
  connection for 13-day pragmatism (no connection-lifecycle/reconnect logic to build
  and debug under time pressure). Worth revisiting post-deadline, not now.
- `script-node/` Luau VHAL Bridge Service (CLAUDE.md's "Option C") — untouched by
  this design; HVAC writes continue going directly from
  `ClimateActuatorGateway.Real` to `CarPropertyManager`.
- Full docs/README authoring — deferred to day 9 per the existing timeline.
- Committing Track B's files is included in this design's implementation (it must
  be committed once reconciled), but broader git hygiene/branching strategy is out
  of scope here.
- The CarSky control-plane Shell-Exec mechanism is dropped entirely, including as an
  automated fallback — the only remaining fallback tier is the pre-existing, manual,
  human-typed `adb shell am broadcast` safety net, which requires no new code.
