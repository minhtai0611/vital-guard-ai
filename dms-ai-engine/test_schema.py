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
