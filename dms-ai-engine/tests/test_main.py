import math
import types

import jsonschema
import json
import numpy as np
import pytest
from pathlib import Path

from main import build_trigger_payload

SCHEMA_PATH = Path(__file__).parent.parent.parent / "contracts" / "trigger.schema.json"


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
        import cv2
        # run_real_video() now reads two distinct props: CAP_PROP_FPS (once,
        # for frame_dt) and CAP_PROP_POS_MSEC (every frame, fed through
        # MonotonicTimestamp -- which does int(raw_ms) and blows up on NaN).
        # A fake that returned self._fps for every prop_id used to be harmless
        # because nothing read POS_MSEC; now it would leak a bad/NaN fps value
        # into the timestamp guard on every frame. Return a real synthetic
        # position instead so the two are no longer conflated.
        if prop_id == cv2.CAP_PROP_FPS:
            return self._fps
        if prop_id == cv2.CAP_PROP_POS_MSEC:
            return self._index * 33.33
        return 0

    def read(self):
        if self._index >= len(self._frames):
            return False, None
        frame = self._frames[self._index]
        self._index += 1
        return True, frame

    def release(self):
        self._opened = False


def _fake_landmarker_with_no_face_ever():
    """Fakes build_video_mode_landmarker's return value: a FaceLandmarker
    whose detect_for_video() always reports no face -- used to test the
    sustained-lost-face path without a real face in a real video."""
    no_face_result = types.SimpleNamespace(face_blendshapes=[], facial_transformation_matrixes=[])

    class _FakeLandmarker:
        def detect_for_video(self, mp_image, timestamp_ms):
            return no_face_result

        def close(self):
            """Real FaceLandmarker.close() releases its native graph/TFLite
            interpreter/thread pool -- run_real_video()/measure() now call this
            unconditionally in their finally block, so the fake must support it
            too or every test using this fake would raise AttributeError."""
            pass

    return _FakeLandmarker()


def _fake_landmarker_with_face_detected(blink_left=0.9, blink_right=0.85, pitch_deg=10.0):
    """Fakes build_video_mode_landmarker's return value for the has_face=True
    path: a FaceLandmarker whose detect_for_video() reports one detected
    face, with a category-like list for face_blendshapes[0] (matching the
    real mediapipe Category type's .category_name/.score interface used at
    main.py's `c.category_name`/`c.score`) and a 4x4 rotation matrix for
    facial_transformation_matrixes[0] (consumed by extract_pitch_deg, which
    reads matrix[:3, :3] and returns the X Euler angle as pitch).

    `pitch_deg` controls the resulting head_euler_angle_x by building a pure
    X-axis rotation matrix -- rotation_matrix_to_euler_deg(R) for such a
    matrix returns (pitch_deg, 0, 0) via atan2(sin, cos) on the R[2,1]/R[2,2]
    and R[1,0]/R[0,0] terms.
    """
    categories = [
        types.SimpleNamespace(category_name="eyeBlinkLeft", score=blink_left),
        types.SimpleNamespace(category_name="eyeBlinkRight", score=blink_right),
    ]
    angle = math.radians(pitch_deg)
    rotation_matrix = np.array([
        [1.0, 0.0, 0.0, 0.0],
        [0.0, math.cos(angle), -math.sin(angle), 0.0],
        [0.0, math.sin(angle), math.cos(angle), 0.0],
        [0.0, 0.0, 0.0, 1.0],
    ])
    face_result = types.SimpleNamespace(
        face_blendshapes=[categories],
        facial_transformation_matrixes=[rotation_matrix],
    )

    class _FakeLandmarker:
        def detect_for_video(self, mp_image, timestamp_ms):
            return face_result

        def close(self):
            """See _fake_landmarker_with_no_face_ever's close() for why this
            exists: run_real_video()/measure() now call landmarker.close()
            unconditionally in their finally block."""
            pass

    return _FakeLandmarker()


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
    import main as main_module

    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture([], opened=False))
    monkeypatch.setattr(
        "services.face_landmarker_client.build_video_mode_landmarker",
        lambda model_path: _fake_landmarker_with_no_face_ever(),
    )

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
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture([frame, frame], fps=bad_fps))
    monkeypatch.setattr(
        "services.face_landmarker_client.build_video_mode_landmarker",
        lambda model_path: _fake_landmarker_with_no_face_ever(),
    )

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
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    # 90 frames at fps=30 => 3.0s of video, comfortably past the 2.0s sustain window.
    frames = [frame] * 90
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture(frames, fps=30.0))
    monkeypatch.setattr(
        "services.face_landmarker_client.build_video_mode_landmarker",
        lambda model_path: _fake_landmarker_with_no_face_ever(),
    )

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


def test_run_real_video_processes_has_face_frames_end_to_end(tmp_path, monkeypatch):
    """Exercises the has_face=True glue code added by the Face Landmarker
    rewire (main.py's `blendshapes = {c.category_name: c.score for c in
    result.face_blendshapes[0]}` and `pitch_deg =
    extract_pitch_deg(result.facial_transformation_matrixes[0])`) end-to-end
    through run_real_video(). Every other run_real_video test in this file
    uses _fake_landmarker_with_no_face_ever, so none of them ever touch this
    code path -- a regression in the attribute names, list indexing, or dict
    construction here would not be caught by any existing test."""
    import cv2
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    # A handful of frames is enough to prove the has_face branch runs without
    # error and writes sane CSV rows; sustained-trigger behavior is already
    # covered by the mock-stream and lost-face tests.
    frames = [frame] * 5
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture(frames, fps=30.0))
    monkeypatch.setattr(
        "services.face_landmarker_client.build_video_mode_landmarker",
        lambda model_path: _fake_landmarker_with_face_detected(blink_left=0.9, blink_right=0.85, pitch_deg=12.0),
    )

    out_csv = tmp_path / "out.csv"
    main_module.run_real_video("does-not-matter.mp4", out_csv, host="127.0.0.1", port=0)

    rows = out_csv.read_text(encoding="utf-8").strip().splitlines()
    header, data_rows = rows[0], rows[1:]
    assert header.split(",") == ["ts", "has_face", "blink_score", "head_pitch", "score", "state", "signal"]
    assert len(data_rows) == len(frames)

    for row in data_rows:
        ts, has_face, blink_score_col, head_pitch, score, state, signal = row.split(",")
        assert has_face == "1"
        # Non-empty and numeric -- confirms blink_score()/extract_pitch_deg()
        # actually ran on the fake's category/matrix data rather than the
        # has_face branch silently short-circuiting.
        assert blink_score_col != ""
        assert head_pitch != ""
        assert float(blink_score_col) > 0.6  # (0.9 + 0.85) / 2 == 0.875, well above BLINK_CLOSE_THRESHOLD
        assert float(head_pitch) == pytest.approx(12.0, abs=0.5)


def test_sigterm_handler_sets_shutdown_flag():
    """Confirmed via a real `docker stop` against this exact container image:
    a plain Python script running as PID 1 in a container does NOT get the
    default terminate-on-SIGTERM behavior — Linux only applies the default
    disposition to non-PID-1 processes. Without an explicit handler, `docker
    stop` had to fall back to SIGKILL after the full grace period (measured:
    exit code 137, ~6s instead of an immediate clean exit). This test locks in
    the fix: an explicit handler that flips a checked-every-iteration flag."""
    import main as main_module

    main_module._shutdown_requested = False
    try:
        main_module._handle_shutdown_signal(15, None)  # 15 == signal.SIGTERM
        assert main_module._shutdown_requested is True
    finally:
        main_module._shutdown_requested = False


def test_run_mock_stream_stops_early_when_shutdown_requested(tmp_path):
    import main as main_module

    main_module._shutdown_requested = True
    try:
        out_csv = tmp_path / "out.csv"
        main_module.run_mock_stream(out_csv, host="127.0.0.1", port=0)
        rows = out_csv.read_text(encoding="utf-8").strip().splitlines()[1:]
        assert len(rows) == 0, "no frame should be processed once shutdown was already requested"
    finally:
        main_module._shutdown_requested = False


def test_run_real_video_stops_early_when_shutdown_requested(tmp_path, monkeypatch):
    import cv2
    import main as main_module

    frame = np.zeros((64, 64, 3), dtype=np.uint8)
    monkeypatch.setattr(cv2, "VideoCapture", lambda path: _FakeVideoCapture([frame] * 90, fps=30.0))
    monkeypatch.setattr(
        "services.face_landmarker_client.build_video_mode_landmarker",
        lambda model_path: _fake_landmarker_with_no_face_ever(),
    )

    main_module._shutdown_requested = True
    try:
        out_csv = tmp_path / "out.csv"
        main_module.run_real_video("does-not-matter.mp4", out_csv, host="127.0.0.1", port=0)
        rows = out_csv.read_text(encoding="utf-8").strip().splitlines()[1:]
        assert len(rows) == 0, "no frame should be processed once shutdown was already requested"
    finally:
        main_module._shutdown_requested = False


def test_blink_score_and_state_agree_for_a_high_blink_score():
    from services.eye_state import blink_score
    blendshapes = {"eyeBlinkLeft": 0.9, "eyeBlinkRight": 0.85}
    assert blink_score(blendshapes) > 0.6  # matches BLINK_CLOSE_THRESHOLD's intent
