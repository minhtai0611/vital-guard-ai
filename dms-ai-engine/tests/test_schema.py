import json
from pathlib import Path

import jsonschema
import pytest

SCHEMA_PATH = Path(__file__).parent.parent.parent / "contracts" / "trigger.schema.json"


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
        "escalationLevel": 1,
        "features": {"perclos": 0.8, "eyeOpenProbability": 0.1, "headEulerAngleX": 28.0},
        "reason": "sustained_high_score",
        "correlationId": "vg-critical-0001",
        "distraction": {
            "score": 0.5, "state": "NORMAL", "escalationLevel": 1, "yawDeg": 10.0, "pitchDeg": 5.0,
            "handsVisibility": "FULL", "handsOnWheel": True, "reason": "normal_gaze",
        },
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
        "escalationLevel": 1,
        "features": {"perclos": 0.0, "eyeOpenProbability": 0.0, "headEulerAngleX": 0.0},
        "reason": "lost_face",
        "correlationId": "vg-unknown-0001",
        "distraction": {
            "score": 0.0, "state": "NORMAL", "escalationLevel": 1, "yawDeg": 0.0, "pitchDeg": 0.0,
            "handsVisibility": "UNKNOWN", "handsOnWheel": True, "reason": "unknown_gaze",
        },
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


def test_valid_payload_with_distraction_object_passes(schema):
    payload = {
        "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
        "state": "NORMAL", "escalationLevel": 1,
        "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
        "reason": "test", "correlationId": "vg-test-0001",
        "distraction": {
            "score": 0.9, "state": "CRITICAL", "escalationLevel": 2, "yawDeg": 45.0, "pitchDeg": 5.0,
            "handsVisibility": "FULL", "handsOnWheel": True, "reason": "gaze_off_road",
        },
    }
    jsonschema.validate(payload, schema)


def test_missing_distraction_object_fails(schema):
    payload = {
        "timestampMs": 0, "source": "container-python", "score": 0.1, "confidence": 1.0,
        "state": "NORMAL",
        "features": {"perclos": 0.0, "eyeOpenProbability": 1.0, "headEulerAngleX": 0.0},
        "reason": "test", "correlationId": "vg-test-0001",
    }
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(payload, schema)
