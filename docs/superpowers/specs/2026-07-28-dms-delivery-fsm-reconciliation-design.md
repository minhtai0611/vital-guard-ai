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

## Decisions

### 1. Track B becomes canonical; Track A is deleted

`main.py` / `score_calculator.py` / `trigger_emitter.py` / `test_dms.py` become the
one DMS implementation going forward. `dms_detector.py` is deleted.

**Rationale:** Track B already has the correct scoring formula and the state-machine
hardening (hysteresis/sustain/cooldown) that CLAUDE.md mandates, with 10 passing
tests. Track A's only advantage — a working delivery mechanism — is ported into
Track B (see Decision 3) rather than kept as a second file. Maintaining two
divergent, unconnected implementations for the remaining 13 days is pure risk.

**Verified before deletion:** grepped the full repo for `dms_detector` — the only
reference is a `python dms_detector.py` run command in `README.md`. No code imports
it. Safe to delete; the README snippet is updated to the `main.py` invocation as
part of the doc fix in Decision 6.

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
sequence. Digital Cockpit's own review principle is evidence over theory; this is
where the evidence has to come from.

### 3. Trigger delivery: CarSky Shell-Exec API — an *unverified* mechanism, not a proven one

Track A's `send_trigger()` is **not** a raw local `adb` binary call. It is an HTTP
POST (Python `requests`) to a CarSky control-plane API —
`{GATEWAY_URL}/api/v1/vms/{ROOM_ID}/{NODE_KEY}/shell` — whose body specifies a shell
command (`am broadcast -a com.vitalguard.ai.TRIGGER_ALERT ...`); the CarSky platform
executes that command on the target Skycraft VM. Renamed in all docs/code as
**"CarSky Shell-Exec API delivery"** — calling it "ADB broadcast" was misleading
about what actually has to be network-reachable.

This is ported into `trigger_emitter.py` as `emit_via_carsky_shell_api()`, built
from the flat payload fields (Decision 4).

**Risk — confirmed unverified (2026-07-28):** this HTTP call has only ever been
exercised from a dev laptop with normal internet access during Round 1. It has
**never** been run from inside an actual CarSky Container Node — the real demo
context. Container Node egress policy and whether `HEADERS`'s auth is
session/IP-scoped are both unknown.

**Mandatory Day-1 task:** deploy `emit_via_carsky_shell_api()` inside a real
Container Node in the CarSky blueprint and verify the HTTP POST succeeds end-to-end
(200 response *and* the broadcast observed via `logcat` on the Skycraft VM) before
building anything further on top of this path.

**Documented contingency, not deleted:** if the Container Node cannot reach
`GATEWAY_URL` (egress blocked, auth scoped to the dev laptop's session/IP),
CLAUDE.md's original HTTP/WebSocket "network pin" design (Container Node serves a
local endpoint, App polls/subscribes directly) is the fallback — it is already fully
specified in CLAUDE.md and is not being deleted from the document, only
superseded-by-default pending Day-1 verification.

### 4. `contracts/trigger.schema.json`: flat schema, matching Intent-extra type mapping

New file, created from scratch (none exists today). **Flattening is a hard technical
constraint, not a shortcut**: Android Intent extras (`--es`/`--ef`/`--ei`/`--el`) have
no nested-object support, so CLAUDE.md's original `features: {perclos,
eyeOpenProbability, headEulerAngleX}` nested shape cannot be sent as broadcast
extras at all. This must be stated explicitly in the schema file's own
documentation/comments so it reads as a deliberate constraint, not sloppiness.

Exact field → type → extra-flag mapping (verified per-field, not assumed uniform):

| Field | Type | Extra flag |
|---|---|---|
| `timestampMs` | long | `--el` |
| `source` | string | `--es` |
| `score` | float | `--ef` |
| `confidence` | float | `--ef` |
| `state` | string | `--es` |
| `perclos` | float | `--ef` |
| `eyeOpenProbability` | float | `--ef` |
| `headEulerAngleX` | float | `--ef` |
| `reason` | string | `--es` |
| `correlationId` | string | `--es` |

Both `trigger_emitter.py` (Python) and the Kotlin `BroadcastReceiver` parsing logic
must conform to this exact mapping.

### 5. Kotlin FSM: thin scope — idempotency + fallback + crash-safety, not full hysteresis

`DrowsinessController.kt` (does not exist today — built from scratch) does **not**
re-implement hysteresis/debounce/cooldown. `trigger_emitter.py` already guarantees
that any broadcast received represents a sustained, hysteresis-gated state — Kotlin
trusts that guarantee rather than duplicating it.

What Kotlin owns:
- **Idempotency:** dedupe by `correlationId` — a repeated/duplicate broadcast of the
  same state does not re-fire the gateways a second time.
- **Fallback:** a heartbeat timeout — if no broadcast arrives within N seconds of the
  last CRITICAL state, revert to a safe baseline (AC off, fan 2, temp 25°C, stop
  voice alert) rather than holding CRITICAL indefinitely or fabricating a new alert
  from missing data.
- **Crash-safety:** if a gateway call throws (Real gateway VHAL/AudioManager
  failure), the controller catches it, logs it, and continues running — it does not
  crash the app.

**Rationale:** with zero existing Kotlin FSM footprint and 13 days left, building the
full 5-property hardening (debounce/hysteresis/cooldown/idempotency/fallback)
independently on the Kotlin side would mean designing, building, and testing from
scratch a second copy of logic that already works and is unit-tested in Python. The
thin scope closes the one real gap — a single bad/duplicate delivery still directly
double-firing HVAC/voice, or a dead trigger stream leaving the cabin stuck in
CRITICAL — without duplicating proven logic.

### 6. Fake/Real gateway split: built now

`ClimateActuatorGateway` / `VoiceAlertGateway` interfaces, each with `Fake*`/`Real*`
implementations. `Real*` wraps the *existing* `ClimateOverrideReceiver` /
`VoiceEmergencyAssistant` VHAL/AudioManager+TTS logic behind the interface — that
logic is not rewritten, only relocated. `GATEWAY_MODE` is a **runtime-toggleable**
flag (not build-flavor-only), so Real→Fake can flip in seconds if something breaks
on stage.

**Rationale:** this is what makes `DrowsinessControllerTest.kt` possible at all — FSM
tests assert against a `Fake`, not a live VHAL call — and it's what CLAUDE.md's
Go/No-Go criteria require as a demo-safety fallback.

### 7. Documentation: minimal fixes now, full docs deferred to day 9 (already scheduled)

In scope now (fixing stale/broken state, not new authoring):
- `README.md`'s `python dms_detector.py` run instruction → updated to the `main.py`
  invocation (Decision 1's orphan-check fix).
- `CLAUDE.md`'s "Implementation Status" section corrected to stop claiming
  `DrowsinessController.kt`/gateway files/tests exist under names that were never
  built.
- `CLAUDE.md`'s "Trigger Delivery" section updated: CarSky Shell-Exec API delivery
  as the default path (Decision 3), network-pin kept as documented contingency.
- `CLAUDE.md`'s schema description updated to the flat shape (Decision 4).
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
                                     (hysteresis 0.85/0.50, 2s sustain, 10s cooldown)
                                            │  emit_via_carsky_shell_api()
                                            ▼
                    HTTP POST {GATEWAY_URL}/api/v1/vms/{ROOM_ID}/{NODE_KEY}/shell
                       body: am broadcast -a com.vitalguard.ai.TRIGGER_ALERT
                             --el timestampMs --es source --ef score --ef confidence
                             --es state --ef perclos --ef eyeOpenProbability
                             --ef headEulerAngleX --es reason --es correlationId
                    [UNVERIFIED from inside a Container Node — Day-1 test required;
                     falls back to CLAUDE.md's network-pin design if unreachable]
                                            ▼
                    VitalGuardMonitorService (foreground, dynamic receiver — unchanged)
                                            ▼
                              DrowsinessController.kt (thin FSM — new)
                    idempotency (dedupe by correlationId) + heartbeat fallback
                                    + gateway-exception crash-safety
                                    ├─ ClimateActuatorGateway (Fake/Real, GATEWAY_MODE — new)
                                    └─ VoiceAlertGateway (Fake/Real, GATEWAY_MODE — new)
```

## Error handling / edge cases

- **Shell-Exec API delivery failing** (Container Node unreachable, non-200 response,
  request exception) → `trigger_emitter.py` logs the exact result (status code or
  exception) and continues its own state machine; it does not crash the pipeline on
  a delivery failure.
- **No broadcast ever reaching Kotlin** (lost face, dropped frames, network-pin
  contingency not yet built, Shell-Exec API down) → the heartbeat timeout is the
  single source of truth for "unknown/safe" — there is no separate no-signal code
  path to keep in sync with it.
- **Duplicate/replayed broadcasts** (e.g. a flaky HTTP retry re-sending the same
  command) → `correlationId` dedupe absorbs them without a second HVAC/voice trigger.
- **Gateway call throws** (Real VHAL/AudioManager failure) → caught and logged by
  `DrowsinessController.kt`; the controller keeps running rather than crashing the
  app.

## Testing

**Python** (`dms-ai-engine/`):
- Existing 10 tests in `test_dms.py` stay as-is.
- New tests for the real MediaPipe EAR/head-pose path (fixture video or synthetic
  landmark input asserting expected EAR/head-pose values).
- New tests for `emit_via_carsky_shell_api()`: mock `requests.post`, assert the exact
  command string and extras constructed from a given `TriggerPayload`, per the
  Decision 4 type-mapping table (in particular, `timestampMs` must appear as `--el`,
  not `--ef`).

**Kotlin** (`aaos-cockpit-app/`), `DrowsinessControllerTest.kt` against
`FakeClimateActuatorGateway`/`FakeVoiceAlertGateway`:
1. Normal operation — score/state below threshold → gateways never called.
2. Idempotency — repeated CRITICAL broadcast with the same `correlationId` → gateway
   called exactly once.
3. Fallback — broadcast stream stops → controller reverts to safe state and instructs
   gateways to revert, without a new incoming trigger.
4. Gateway failure — `Fake*Gateway` throws → controller catches it, logs it, does not
   crash, and does **not** retry the same call — it stays in its current tracked
   state and will act again on the next state transition or next broadcast. No
   retry loop, to avoid masking a persistently broken Real gateway behind silent
   repeated attempts; a broken Real gateway is exactly what `GATEWAY_MODE` exists to
   let the team fall back away from within seconds.

## Out of scope for this design

- Full 5-property Kotlin FSM hardening (Decision 5) — explicitly deferred; Python
  already owns hysteresis/debounce/cooldown.
- `script-node/` Luau VHAL Bridge Service (CLAUDE.md's "Option C") — untouched by
  this design; HVAC writes continue going directly from
  `ClimateActuatorGateway.Real` to `CarPropertyManager`.
- Full docs/README authoring — deferred to day 9 per the existing timeline.
- Committing Track B's files is included in this design's implementation (it must
  be committed once reconciled), but broader git hygiene/branching strategy is out
  of scope here.
