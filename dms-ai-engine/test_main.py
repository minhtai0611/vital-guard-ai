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
