import types

import jsonschema
import json
import numpy as np
import pytest
from pathlib import Path

from main import build_trigger_payload

SCHEMA_PATH = Path(__file__).parent.parent / "contracts" / "trigger.schema.json"


class _FakeVideoCapture:
    """Stands in for cv2.VideoCapture in tests — real video files/mediapipe are
    not needed to characterize run_real_video()'s handling of file-open failure,
    bad FPS metadata, and sustained lost-face, which is what these tests target.
    `frames`: list of numpy arrays (or empty, to simulate a video with 0 frames).
    """

    def __init__(self, frames, fps=30.0, opened=True):
        self._frames = list(frames)
        self._index = 0
        self._fps = fps
        self._opened = opened

    def isOpened(self):
        return self._opened

    def get(self, prop_id):
        return self._fps

    def read(self):
        if self._index >= len(self._frames):
            return False, None
        frame = self._frames[self._index]
        self._index += 1
        return True, frame

    def release(self):
        self._opened = False


def _fake_mediapipe_with_no_face_ever():
    """Builds a fake `mediapipe` solutions.face_mesh.FaceMesh whose .process()
    always reports no face detected — used to test the sustained-lost-face path
    without needing a real face in a real video."""
    no_face_result = types.SimpleNamespace(multi_face_landmarks=None)

    class _FakeFaceMesh:
        def __init__(self, **kwargs):
            pass

        def process(self, rgb):
            return no_face_result

    return types.SimpleNamespace(face_mesh=types.SimpleNamespace(FaceMesh=_FakeFaceMesh))


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


def test_run_real_video_raises_clear_error_when_video_cannot_be_opened(tmp_path, monkeypatch):
    """Characterizes a real risk flagged before this test existed: an unopenable
    video file (bad codec, wrong path) previously left cap.isOpened() False from
    the start, so the while-loop never ran and the function returned silently
    with an empty (headers-only) CSV and zero events — no error, no signal that
    anything went wrong. That's exactly the kind of silent failure this test
    closes off: run_real_video() must now raise a clear error instead."""
    import cv2
    import mediapipe
    import main as main_module

    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture([], opened=False))
    monkeypatch.setattr(mediapipe, "solutions", _fake_mediapipe_with_no_face_ever(), raising=False)

    with pytest.raises(RuntimeError, match="[Cc]ould not open"):
        main_module.run_real_video("does-not-matter.mp4", tmp_path / "out.csv", host="127.0.0.1", port=0)


@pytest.mark.parametrize("bad_fps", [0, -1, float("nan")])
def test_run_real_video_falls_back_to_default_fps_when_reported_fps_is_invalid(tmp_path, monkeypatch, bad_fps):
    """cap.get(cv2.CAP_PROP_FPS) can return 0, a negative number, or NaN for some
    codecs/containers. The original `fps = cap.get(...) or 30.0` fallback only
    catches falsy values (0/None) — both -1 and float('nan') are truthy in
    Python, so they would have passed straight through, corrupting the
    timestamp progression (`frame_dt = 1.0/fps` goes negative or NaN, breaking
    TriggerEmitter's sustain-window timing). This test forces the fallback to
    actually validate the value, not just its truthiness."""
    import cv2
    import mediapipe
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture([frame, frame], fps=bad_fps))
    monkeypatch.setattr(mediapipe, "solutions", _fake_mediapipe_with_no_face_ever(), raising=False)

    out_csv = tmp_path / "out.csv"
    main_module.run_real_video("does-not-matter.mp4", out_csv, host="127.0.0.1", port=0)

    rows = out_csv.read_text(encoding="utf-8").strip().splitlines()[1:]
    timestamps = [float(row.split(",")[0]) for row in rows]
    assert timestamps == sorted(timestamps), "timestamps must be non-decreasing even with bad FPS metadata"
    assert all(ts >= 0 for ts in timestamps), "a NaN/negative fps must not produce negative/NaN timestamps"


def test_run_real_video_emits_unknown_after_sustained_lost_face(tmp_path, monkeypatch):
    """End-to-end (mocked cv2/mediapipe) confirmation that the lost-face path —
    never exercised before, since no real video was ever run through this
    function — actually reaches LatestTriggerStore. 30 frames at the default
    30fps fallback covers 1.0s; sustain_seconds is 2.0s, so this alone must NOT
    fire; the test only asserts on what SHOULD happen once enough frames pass."""
    import cv2
    import mediapipe
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    # 90 frames at fps=30 => 3.0s of video, comfortably past the 2.0s sustain window.
    frames = [frame] * 90
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture(frames, fps=30.0))
    monkeypatch.setattr(mediapipe, "solutions", _fake_mediapipe_with_no_face_ever(), raising=False)

    served = []
    original_update_latest = main_module.LatestTriggerStore.update_latest

    def spy_update_latest(self, payload):
        served.append(payload)
        return original_update_latest(self, payload)

    monkeypatch.setattr(main_module.LatestTriggerStore, "update_latest", spy_update_latest)

    main_module.run_real_video("does-not-matter.mp4", tmp_path / "out.csv", host="127.0.0.1", port=0)

    assert any(p["state"] == "UNKNOWN" and p["reason"] == "lost_face" for p in served), (
        "sustained lost-face (3s of no-face frames) must emit an UNKNOWN/lost_face payload"
    )


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
