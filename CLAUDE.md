# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project: Vital-Guard AI

A drowsiness detection & response system for Android Automotive OS (AAOS), built for **FPT Automotive Hackathon 2026**, **Digital Cockpit** track (assigned by the organizers/BTC — not the team's original track choice). Team: **Vital-Guard Team** — **Phát** (Computer Vision/DMS pipeline), **Tài** (AAOS app integration/FSM Controller/Climate/Voice), mentor **Trần Minh Tuệ**.

**Problem being solved:** microsleep while driving — existing DMS only issues passive audio alerts, not strong enough to wake a drowsy driver.

**Solution — Selective Cross-Vertical MVP, 3 modules:**
1. **Driver Intelligence Platform** ("Eyes") — an AI vision engine reads driver-facing video → computes a **Drowsiness Score** (PERCLOS + blink duration + head-pose, MobileNetV3-Small INT8-quantized) → fires a **Trigger** when score ≥ 0.85 sustained.
2. **Climate Control (VHAL)** — on Trigger, overrides HVAC: AC ON, fan speed **8**, temperature **20°C** (from baseline 25°C/fan 2) — a thermal/tactile alertness cue.
3. **Voice-Controlled Assistant** — seizes `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, mutes media, plays a TTS alert + suggests the nearest rest stop.

**Core architectural principle (MUST NOT be violated):** the entire pipeline runs **on-device/edge, with no cloud round-trip** in the safety-critical path. All communication happens over **localhost / the emulator bridge network** inside the CarSky room — no network hop leaves the environment. Reason: latency must stay well under a second, and the on-stage Final in Hanoi must not depend on external connectivity.

---

## Project Structure

```
vital-guard/
├── contracts/
│   └── trigger.schema.json   # Shared data contract — Container Node ↔ App (Python + Kotlin both conform to this)
├── dms-container/            # Python — runs inside a CarSky Container Node (NOT inside the Android app)
│   ├── score_calculator.py   # PERCLOS + blink + head-pose fusion → Drowsiness Score
│   ├── trigger_emitter.py    # Emits a Trigger when score ≥ 0.85 sustained, serves it over a local network endpoint
│   ├── main.py                # Pipeline entry point
│   ├── test_dms.py            # Unit tests (10 tests, currently passing)
│   └── Dockerfile
├── android-app/               # Kotlin — app running on the Skycraft AAOS VM
│   ├── DrowsinessController.kt   # Adaptive Response Controller (FSM: Normal→Warning→Critical)
│   ├── ClimateActuatorGateway.kt  # Interface + Fake/Real — HVAC only (VHAL/CarPropertyManager)
│   ├── VoiceAlertGateway.kt       # Interface + Fake/Real — Audio Focus/TTS only
│   └── DrowsinessControllerTest.kt
├── script-node/                # Luau — runs inside the CarSky Script Node (VehicleServer)
│   └── vhal_hvac.luau          # Standard HVAC property backend only — NOT a trigger bridge (see decision below)
├── docs/
│   ├── vital-guard-technical-deep-dive.md
│   ├── vital-guard-implementation-plan.md
│   └── runbook.html
└── CLAUDE.md
```

> **Important:** this is a **proposed** structure based on the architecture already agreed on. When actual coding starts, reconcile with the real repo layout if it differs — but do NOT change the responsibility split (DMS lives in the Container Node, never ported to Kotlin; FSM/Adaptive Controller lives in the Android app, never pushed into the Script Node; Climate and Voice are separate gateways, never merged into one).

---

## Tech Stack

### DMS Pipeline (`dms-container/`)
- **Language:** Python — kept as-is, **do NOT port to Kotlin/JNI**. Reason: the Container Node accepts any OCI image, while the Script Node's Luau sandbox is unsuitable for heavy inference.
- **Model:** MobileNetV3-Small, INT8-quantized — targets edge efficiency.
- **Metric:** PERCLOS (standard P80 criterion) + blink duration + head-droop, fused into a composite score in [0,1].
- **Output:** a Trigger packet when the score is ≥ 0.85 sustained over a sliding window. Served over a lightweight local HTTP/WebSocket endpoint (see "Trigger Delivery" below) — **not** pushed through a custom VHAL property.
- **Deployment:** package as a Dockerfile → Container Node in the CarSky blueprint. Video input (replay or real camera) goes through a **Container "Driver Video Replay"** node — the team can add this themselves, **no BTC approval required**.

### Android App (`android-app/`)
- **Language:** Kotlin
- **Platform:** Android Automotive OS (AAOS), running on the **Skycraft node** (a VHAL client only — it does not generate vehicle signals itself)
- **APIs:** `CarPropertyManager` (VHAL read/write via AIDL IPC), `CarAudioManager` (Audio Focus), Android `TextToSpeech`
- **Pattern:** two separate gateway interfaces, each with its own Fake/Real pair — **do not merge them into one `VehicleGateway`**:
  - `ClimateActuatorGateway` (`FakeClimateActuatorGateway` / `RealClimateActuatorGateway`) — HVAC only, via `CarPropertyManager`.
  - `VoiceAlertGateway` (`FakeVoiceAlertGateway` / `RealVoiceAlertGateway`) — Audio Focus/TTS only, via `CarAudioManager`.
  - Reason: climate (VHAL) and voice (audio policy) fail independently and need independent fallbacks — merging them makes one failure drag down the other.
- **Switching Fake ↔ Real:** must go through a runtime feature flag (`GATEWAY_MODE=FAKE|REAL` via build flavor/DI/config/debug-menu toggle) — **never** a manual code edit to swap. Must be able to fall back to Fake within seconds if Real breaks during the demo.
- Always develop and test FSM logic against Fake gateways first; switch to Real only during the final integration phase.

### Script Node (`script-node/`)
- **Language:** Luau (CarSky's Lua sandbox)
- **Role:** acts as the **VehicleServer** backend for standard HVAC properties (`HVAC_AC_ON`/`HVAC_FAN_SPEED`/`HVAC_TEMPERATURE_SET`) that the App writes via `CarPropertyManager`. **It is NOT a bridge for the drowsiness trigger** — see "Trigger Delivery" below.
- **Do NOT run AI inference here** — the sandbox is not suited for heavy workloads.

### Trigger Delivery — on-device Kotlin pipeline (current, supersedes the network-pin decision below)
As of `docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md`, the Android app's drowsiness trigger no longer depends on the Python Container Node at runtime. `MediaPipeReplayDetectionSource` runs MediaPipe Face Landmarker directly on-device (Kotlin), porting the same PERCLOS/head-pose/sustain/escalation math dms-ai-engine's `services/*.py` already had tested (`eye_state.py`, `head_pose.py`, `score_calculator.py`, `trigger_emitter.py`, `escalation_tracker.py` → `com.vitalguard.ai.drowsiness`). `TriggerPollClient`'s HTTP-polling path to the Container Node has been deleted.

The original network-pin architecture described below is **kept for historical context and because `dms-ai-engine/` still exists in the repo** (untouched, still buildable) — but it is no longer what the shipped Android app depends on. If a future need re-introduces a Container Node in the loop (e.g. distraction detection, which is NOT yet ported to Kotlin — see root CLAUDE.md's Distraction Detection section), revisit this decision explicitly rather than assuming it still applies.

**Original decision (superseded for drowsiness, still describes the Container Node's own standalone capability):** the drowsiness trigger could go from the Container Node straight to the Android app over a **network pin added directly between the Container Node and the Skycraft VM** (self-service, same mechanism as adding the video-replay container — no BTC approval needed). The Container Node serves the trigger over a lightweight local HTTP/WebSocket endpoint conforming to `contracts/trigger.schema.json`. **This bypasses the Script Node and VHAL entirely for the trigger signal** — VHAL/Script Node is used only for the HVAC actuation direction (a standard AOSP property, not a custom one).

---

## System Architecture (Runtime Data Flow)

```
[Container Node: Driver Video Replay / real camera]
        │  video stream
        ▼
[Container Node: DMS Pipeline — Python/PyTorch]
   score_calculator.py → Drowsiness Score (PERCLOS+blink+head-pose)
        │  Trigger when score ≥ 0.85 sustained
        ▼
[Network pin — Container Node → Skycraft VM, added directly, self-service]
   Lightweight HTTP/WebSocket server on Container; App connects/polls directly
   (conforms to contracts/trigger.schema.json — bypasses Script Node/VHAL entirely for this signal)
        ▼
[Android App on the Skycraft VM]
   [Drowsiness Trigger] → [Adaptive Response Controller (FSM: Normal→Warning→Critical)]
      ├─ → VoiceAlertGateway → CarAudioService (mute/duck) → AI Voice Playback (TTS)
      └─ → ClimateActuatorGateway → CarPropertyManager → AIDL IPC → Vehicle HAL
              (via Script Node as VehicleServer — HVAC: AC ON, fan=8, temp=20°C,
               a STANDARD AOSP property, not custom — only needs write permission, see below)
        │
        ▼
[Screen widget (judges watch via browser)] / [Signal Watch widget — verify property, NOT a substitute for real code]
```

**Technical Communication Boundaries (on-device IPC):** every arrow in the diagram above is **local IPC / local network within the room** — no path ever leaves the device/room. This is the standard answer if a judge asks about the security/latency architecture.

---

## Vehicle Property Reference (VHAL)

| Property | Data type (fixed AOSP standard) | Target value | Notes |
|---|---|---|---|
| `HVAC_AC_ON` | boolean | `true` on Trigger | `0x15200505` |
| `HVAC_FAN_SPEED` | int | `8` on Trigger (baseline: `2`) | |
| `HVAC_TEMPERATURE_SET` | float | `20°C` on Trigger (baseline: `25°C`) | |

**Self-resolve, don't ask first:** `areaId`, min/max/step, and read/write access are **not guessed or asked about upfront** — query them at runtime with `CarPropertyManager.getCarPropertyConfig(propId)` (`getAreaIds()`, `getMinValue()`, `getMaxValue()`). Code must **clamp the target values (fan=8, temp=20°C) to whatever range the config actually returns**, logging when a clamp happens — never hardcode assuming the proposal's numbers fit every possible config.

**Mandatory rule:** if `setProperty()` is rejected or a property returns `null`/errors when tested via ADB/Signal Watch → try it, log the exact result (exception type, or silent no-op), and only **then** escalate to the mentor/infra team with that concrete evidence. Do not ask before testing.

---

## HVAC Permission Risk — Agreed Solution (in priority order)

The Skycraft VM grants **root ADB access** (`trout_arm64:/ $` prompt) — use this to resolve the signature-level HVAC-write permission risk:

1. **Option A (preferred):** `adb root && adb remount` → push the APK to `/system/priv-app/` → create a `privapp-permissions-*.xml` allowlisting the exact permission (`CONTROL_CAR_CLIMATE`...) → reboot → verify with `adb shell dumpsys package <pkg> | grep CONTROL_CAR_CLIMATE`.
2. **Option B (fallback, faster but riskier):** set `ro.control_privapp_permissions=disable` in `build.prop`.
3. **Option C (safety-net architecture — run in parallel, not a replacement for A/B):** isolate the HVAC-write logic into a small, dedicated **VHAL Bridge Service** priv-app. The main app talks to the bridge via an internal Broadcast Intent (no special permission needed). Benefit: if the priv-app breaks during the demo, only one small service needs fixing.
4. **Demo fallback (if A/B/C all fail):** use `adb shell cmd car_service inject-vhal-event` to prove the signal path in front of judges — explain this as a third-party-APK permission limitation on AAOS, not a design flaw.

---

## Data Contract (`contracts/trigger.schema.json`)

Both the Python pipeline and the Kotlin app must conform to this schema for the trigger payload — locked before either side codes independently:

```json
{
  "timestampMs": 0,
  "source": "container-python|debug|replay",
  "score": 0.0,
  "confidence": 0.0,
  "state": "NORMAL|WARNING|CRITICAL",
  "features": {
    "perclos": 0.0,
    "eyeOpenProbability": 0.0,
    "headEulerAngleX": 0.0
  },
  "reason": "string",
  "correlationId": "string"
}
```

---

## State Machine Hardening (mandatory, not optional)

`DrowsinessController.kt`'s FSM must never be just "score high → Critical immediately." It must implement:

- **Debounce:** only change state if the bad signal persists long enough (not one bad frame → red alert).
- **Hysteresis:** the up-threshold and down-threshold differ, to prevent state flicker.
- **Cooldown:** don't fire an alert on every single frame.
- **Idempotency:** staying in the same state must not re-trigger the gateway repeatedly.
- **Fallback:** if the Container Node connection drops or no trigger arrives (lost face, dropped frames), fall back to an "unknown"/safe state — never fabricate an alert from missing data.

**Mandatory unit test cases in `DrowsinessControllerTest.kt`:** normal score (no gateway call) · brief score spike (not immediately Critical) · sustained high score (transitions to Critical) · score drop (recovers with hysteresis) · gateway failure (app doesn't crash) · lost/no trigger signal (doesn't fire a false alert).

---

## Debug Overlay (mandatory, not a nice-to-have)

Without an overlay, the team can't tell whether a problem is the model, the state machine, or the gateway. The overlay (in-app UI or log) must show: `perclos` · `eyeOpenProbability` · `headEulerAngleX` · current state · remaining cooldown · trigger frame rate/frequency · whether a trigger is currently being received from the Container · the last gateway action taken. This must exist **before** any threshold tuning — never tune blind.

---

## Implementation Status (already BUILT — don't propose rebuilding these)

- **DMS pipeline (Python):** `score_calculator.py`, `trigger_emitter.py`, `test_dms.py`, `main.py` — **10 unit tests, run and passing**. Check the code before assuming "scoring logic doesn't exist yet."
- **Kotlin skeleton:** `DrowsinessController.kt`, `VehicleGateway.kt` (Fake/Real pattern), `DrowsinessControllerTest.kt` — already scaffolded.
- **Docs:** `vital-guard-technical-deep-dive.md` (architecture, data flow, timing budget, IPC security, test cases), `vital-guard-implementation-plan.md` (13-day roadmap to 10/08), an annotated runbook HTML.
- **Guideline analysis:** confirmed `Connected_Car.html` is a separate data-science track (CSV/dataset submission), **out of scope** for Digital Cockpit — do not build that.

---

## Known Deviations from Proposal

- **Eye-state/head-pose backbone is MediaPipe Face Landmarker (blendshapes +
  facial transformation matrices), not "MobileNetV3-Small INT8-quantized"**
  as the Tech Stack section above still names it, **and not plain FaceMesh +
  hand-rolled EAR/solvePnP either.** The pipeline now uses Face Landmarker's
  `face_blendshapes` (`eyeBlinkLeft`/`eyeBlinkRight`) for the eye-closure
  signal and `facial_transformation_matrixes` for head-pose pitch
  extraction, replacing the earlier solvePnP-based extraction that had a
  documented flip-ambiguity bug capping the drowsy test video's score at
  0.800 — just under the 0.85 CRITICAL threshold. Post-migration, the same
  drowsy video reaches 0.975; see `dms-ai-engine/CV_REMEDIATION_RESULTS.md`
  for the full acceptance-gate evidence (Docker-container run against 3 real
  test videos, physical-plausibility check, blink-threshold sanity check,
  latency measurement).
  Judge-facing reason: Face Landmarker is a pretrained, well-validated model
  used as-is (not a custom-trained classifier) — a deliberate build-vs-buy
  choice, not a claim that the team scientifically validated the specific
  0.85 threshold or the blink-hysteresis constants themselves (see
  "Reference Basis" below for what is/isn't independently validated).
  Note: this is the **second** undocumented deviation on this exact point —
  an earlier, separate plan's Task 17 (scoped to a teammate) was meant to
  record a prior switch away from MobileNetV3 to plain FaceMesh + hand-rolled
  EAR/solvePnP, but per that plan's own ledger it was never actually
  executed, so this section is the first place either deviation has been
  written down.

### Distraction Detection (added, not in original proposal)

Two additional signals were added to the DMS pipeline beyond the original
drowsiness-only scope: **gaze/head-off-road** (head pitch AND yaw from the
same MediaPipe Face Landmarker transformation matrix already used for
drowsiness, combined with the existing debounced eye-closure signal to
disambiguate "looking down at a phone" from "nodding off") and
**hands-off-wheel** (a new MediaPipe **Hand Landmarker** model, tracking
hand-landmark position against a fixed wheel region — a proxy for phone
use, not true object detection, since MediaPipe has no phone/object
detector and training one was not feasible in this timeframe).

Delivered as a new `distraction` object alongside the existing drowsiness
payload (`contracts/trigger.schema.json`), reacted to by a separate
`DistractionController` in the Android app, arbitrated through a new
`AlertArbiter` so a distraction reminder never overrides — or gets wrongly
cut off by — an active drowsiness alert (drowsiness always takes
priority). Distraction never touches HVAC — voice-only, and a lighter
audio-focus request than drowsiness's exclusive one.

Judge-facing reason: same build-vs-buy reasoning as the Face Landmarker
migration — MediaPipe's Hand Landmarker is a pretrained, well-validated
model used as-is, not a custom-trained classifier. The specific
thresholds/weights (`PITCH_OFF_ROAD_THRESHOLD`, `YAW_OFF_ROAD_THRESHOLD`,
`W_GAZE`/`W_HANDS`, `DistractionTriggerEmitter`'s sustain/cooldown) are
reasoned starting points, not independently validated — same disclosed
stance as the drowsiness formula's own weights. The wheel-region
calibration is tied to the specific camera framing tested against and has
not been verified on the real Container Node/Skycraft camera.

---

## Architectural Decisions Already Locked In (do not re-litigate unless new info comes from the mentor)

- The DMS pipeline runs in a **Container Node** (Python), never ported to Kotlin, never run in the Script Node.
- The Adaptive Response Controller (FSM) lives in the **Android app** (Kotlin), never pushed to Script Node/Luau.
- The drowsiness trigger is computed **on-device in Kotlin** (`com.vitalguard.ai.drowsiness`, see `docs/superpowers/specs/2026-08-08-drowsiness-kotlin-port-design.md`) — the Python Container Node is no longer a runtime dependency for drowsiness. HVAC actuation is still never a custom VHAL property pushed by the Script Node (that part of the original decision is unchanged).
- Distraction detection (hands-off-wheel, gaze-off-road) is **not yet ported** to Kotlin — it still requires the Python Container Node's `hand_tracker.py`/`distraction_score_calculator.py` if/when that feature needs to run; `MediaPipeReplayDetectionSource` currently emits `NO_DISTRACTION` unconditionally.
- Gateways are **split**: `ClimateActuatorGateway` (HVAC) and `VoiceAlertGateway` (audio/TTS) are separate interfaces, never merged into one `VehicleGateway`.
- Develop and test logic against Fake gateways first; Real gateways are only used in the final integration phase, swapped via a runtime feature flag, never a manual code edit.
- Video input (replay or real camera) is the team's own choice, and the team can add the node to the blueprint themselves, no approval needed.
- The CAN Gateway (C++) was **actively dropped in Round 1** (memory-leak risk; CAN bus signals aren't decisive for drowsiness — it shows up in the face/head, not vehicle dynamics). **Do not rebuild it.**
- The scoring formula weights (0.55/0.25/0.20) and other timing parameters are an **unvalidated baseline** — only the 0.85 threshold and the general PERCLOS-based fusion concept have real scientific grounding (see "Reference Basis" below). Do not claim the specific weights are "scientifically validated" in any submission material.
- Risk-attribution analytics (SHAP-style, post-trip coaching report) are **out of the real-time path** — computed offline after the trip, never inside the real-time inference loop, since that would break the latency budget.
- **Default to self-deciding and self-testing over asking the mentor.** Anything resolvable via a runtime API call (`getCarPropertyConfig()`), a local test (write + check Signal Watch), or a decision already implied by the proposal (e.g. `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` + forced ducking) should be decided and tried immediately — not asked about first. Escalate to the mentor only after testing, and only with concrete evidence (an error log, a screenshot, a specific rejected write) — never as a speculative question asked before any code has been tried.

---

## KPI / Testing Targets

| KPI | Target |
|---|---|
| Detection latency (p95) | ≤ 150 ms |
| Audio focus & mute | ≤ 100 ms |
| 20-minute load test | 0 crashes |
| VHAL commands accepted | 100% |

---

## Out of Scope (already declared to the judging panel)

- No road-obstacle detection algorithm (road-facing camera AI).
- No payment or driver account-management system.
- No complex 3D graphical interface on the AAOS screen.
- No ASR/NLU core rebuilt from scratch — use the existing Starter Pack engine.
- Nothing touching `Connected_Car.html` / the Driver Intelligence Platform's CSV-dataset submission under Connected Car Services — different track, already confirmed with BTC.
- No rebuilding of components already provided in the starter pack — it earns no extra points and only costs time.

---

## Security & IPC — Rules That Must Not Be Broken

1. **On-device only:** all communication between Container Node ↔ App (network pin) and App ↔ Script Node (VHAL) must go over the localhost/emulator bridge network inside the room. No external network calls in the safety-critical path.
2. **Verify, don't assume:** never assume a VHAL property's `areaId`/min/max — query them via `getCarPropertyConfig()` and clamp target values to what's actually returned. A rejected write or null read means: log the exact result, then decide whether to escalate — don't guess a workaround.
3. **No speculative escalation:** don't message the mentor/BTC about something before it's been tried in code. Every mentor question must come with a concrete test result attached.
4. **Scope priv-app permissions tightly:** the VHAL Bridge Service (Option C) should contain only the HVAC-write logic — don't extend its privileges to any other part of the app.

---

## Reference Basis (use when answering judges' technical questions)

- NHTSA (2022) — Traffic Safety Facts: Drowsy Driving
- Euro NCAP (2023) — Vision Zero & Direct Driver Monitoring Systems, Roadmap 2026
- EU General Safety Regulation (2019/2144) + Commission Delegated Regulation (EU) 2021/1341 (DDAW), (EU) 2023/2590 (ADDW)
- WHO (2023) — Global Status Report on Road Safety 2023
- ISO 15005 (2017) — Road vehicles — Ergonomics of transport information and control systems
- Gwak, Shino, Ueda & Kamata (2019) — effects of cabin temperature on arousal level/thermal comfort — the basis for using temperature as an anti-drowsiness actuator
- Android Developers (2025) — AAOS: Audio Focus, IPC & VHAL AIDL Guidelines

**Note:** the sources above validate the **concepts** (PERCLOS, the climate-alertness link, the on-device IPC pattern) — they do NOT validate the specific numbers (0.55/0.25/0.20, the 0.85 threshold) as the team's own research findings. When answering judges, keep this distinction clear.

---

## Demo Script (stage storyboard — basis for the seed/contingency scenario)

| Time | What happens |
|---|---|
| Min 01–05 | Two parallel feeds: driver-facing video + the AAOS cockpit UI in a normal state (25°C, fan level 2), with MP3 music playing |
| Min 05–12 | On the video feed, the driver's head droops and eyes close (microsleep). The console shows the on-device AI log sending a `TRIGGER_ALERT` packet |
| Min 12–20 | The MP3 music cuts out abruptly, the cabin climate switches to safety mode (elevated airflow, AC ON, fan 8, temp 20°C), and simultaneously the speakers play: a drowsiness warning + a suggestion for the nearest rest stop |

This is the reference script for preparing a **contingency seed scenario** — if a live component fails during the demo, fall back to a simulated video/log following this exact timeline.

---

## Project Development Principles

1. **Prioritize working code with real evidence over untested theory:** with 13 days left until the Round 2 deadline (10/08), prioritize code that runs + has real evidence (logs, passing tests, video) over polishing unvalidated theory.
2. **Don't port code between languages unless necessary:** DMS stays in Python inside the Container Node; FSM stays in Kotlin inside the app. Only port when there's a clear technical reason (e.g., measured latency exceeds budget).
3. **Self-decide and self-test before asking:** anything resolvable via a runtime API call, a local write-then-verify test, or a decision the proposal already implies should be decided and tried immediately. Only escalate to BTC/mentor after testing, with concrete evidence (log, screenshot, exact rejection) attached — mentor response time is unpredictable and shouldn't gate the 13-day timeline.
4. **Test before integrating for real:** FSM/scoring logic must have passing unit tests against Fake gateways before switching to Real gateways/the real VM.
5. **README required at submission time:** must cover the architecture (Container Node → App via network pin, App → Script Node for HVAC), the list of Vehicle Properties used, and build/install instructions for CarSky.
6. **Be transparent about scientific limitations:** don't claim specific weights/scoring are "validated" without a basis — see "Reference Basis" above.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **vital-guard-ai** (849 symbols, 1676 relationships, 31 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/vital-guard-ai/context` | Codebase overview, check index freshness |
| `gitnexus://repo/vital-guard-ai/clusters` | All functional areas |
| `gitnexus://repo/vital-guard-ai/processes` | All execution flows |
| `gitnexus://repo/vital-guard-ai/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
