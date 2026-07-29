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
Python + OpenCV + MediaPipe Face Mesh drowsiness detector. Computes an EAR-based drowsiness
score over a sliding window and, once it crosses the threshold, sends a `TRIGGER_ALERT`
broadcast to the AAOS device (via CarSky's shell/ADB bridge in the demo environment).

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
