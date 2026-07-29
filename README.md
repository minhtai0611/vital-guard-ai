# 🛡️ Vital-Guard AI

Selective Cross-Vertical MVP for Automotive Hackathon 2026 — an on-device (edge) DMS AI engine
that detects driver drowsiness and triggers a multi-sensory intervention (cabin climate override
+ voice assistant) inside an Android Automotive OS (AAOS) cockpit app.

Full architecture, data flow, setup, packaging, and demo script live in
[`VITAL_GUARD_AI_E2E_GUIDE.md`](./VITAL_GUARD_AI_E2E_GUIDE.md).

## Repo layout

```
vital-guard-ai/
├── .gitignore
├── README.md
├── VITAL_GUARD_AI_E2E_GUIDE.md   <-- Full E2E guide (setup, architecture, demo script)
├── dms-ai-engine/                <-- Python DMS AI engine (drowsiness detection)
└── aaos-cockpit-app/             <-- Android Studio project (AAOS cockpit app, Kotlin)
```

## Modules

### `dms-ai-engine/`
Python + OpenCV + MediaPipe Face Landmarker (Tasks API, VIDEO running mode) drowsiness
detector. Computes a composite drowsiness score from the landmarker's face blendshapes
(blink score) and facial transformation matrix (head pitch) over a sliding window, and once
it crosses the threshold, serves a Trigger payload over a local HTTP/WebSocket network-pin
endpoint (`GET /latest-trigger`, conforming to `contracts/trigger.schema.json`) that the
AAOS app connects to/polls directly — not a CarSky shell/ADB broadcast.

Setup:
```bash
cd dms-ai-engine
pip install -r requirements.txt
python main.py --mock
```

### `aaos-cockpit-app/`
Kotlin Android Automotive app. Listens for `com.vitalguard.ai.TRIGGER_ALERT`, then:
- Overrides `HVAC_AC_ON` / `HVAC_FAN_SPEED` / `HVAC_TEMPERATURE_SET` via `CarPropertyManager` (VHAL).
- Takes exclusive audio focus, mutes media, and speaks a safety alert via TTS.

Open in Android Studio (Koala+), with an Automotive (1024p landscape) API 33 virtual device.

## Smoke test (no camera required)

```bash
adb shell am broadcast -a com.vitalguard.ai.TRIGGER_ALERT --ef drowsiness_score 0.95
adb logcat -s VitalGuardClimate VitalGuardVoice
```
