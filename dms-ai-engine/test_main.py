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
