#!/usr/bin/env bash
# Container-level smoke tests for the dms-ai-engine Docker image.
#
# These check things pytest cannot: does the image actually build with the
# system libs cv2/mediapipe need, does the HTTP network-pin endpoint work
# through Docker's real port mapping (not just in-process), and does the
# container shut down cleanly on `docker stop` (a container's main process
# runs as PID 1, and Linux does NOT apply the default terminate-on-SIGTERM
# behavior to PID 1 unless the process installs a handler — confirmed by
# reproducing exit code 137/SIGKILL-after-grace-period before main.py's
# signal.signal(SIGTERM, ...) fix landed).
#
# Run from dms-ai-engine/:  bash test_container.sh
#
# Deliberately not using `set -e`: a `VAR=$(cmd)` assignment under `set -e`
# has inconsistent behavior across bash/environments (reproduced: it aborted
# this script immediately after a successful `STATUS=$(curl ...)` capture,
# before the following `if` check even ran) — explicit `if ! cmd; then exit 1;
# fi` after each step is more predictable and gives clearer failure messages.
cd "$(dirname "$0")"

IMAGE=vital-guard-dms:smoketest
CONTAINER=vital-guard-dms-smoketest
TEST_VIDEO=.smoketest_video.mp4
PORT=18765

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -f "$TEST_VIDEO"
}
trap cleanup EXIT

echo "[1/4] Building image..."
if ! docker build -t "$IMAGE" . >/dev/null; then
  echo "FAIL: docker build failed"
  exit 1
fi

echo "[2/4] Running --mock (default CMD), expecting a clean exit 0 with 2 events..."
MOCK_LOG=$(docker run --rm "$IMAGE" 2>&1)
echo "$MOCK_LOG" | grep -q "Số event đã emit: 2"
if [ "$?" -ne 0 ]; then
  echo "FAIL: --mock did not report the expected 2 events. Output:"
  echo "$MOCK_LOG"
  exit 1
fi
echo "OK: --mock ran cleanly inside the container (2 events, matches dev-machine behavior)"

echo "[3/4] Testing /latest-trigger reachability through Docker's port mapping..."
docker run -d --name "$CONTAINER" -p "$PORT:8765" --entrypoint python "$IMAGE" -c "
from trigger_server import LatestTriggerStore, start_background_server
import time
store = LatestTriggerStore()
start_background_server(store, host='0.0.0.0', port=8765)
store.update_latest({'state': 'CRITICAL', 'correlationId': 'smoketest-0001'})
time.sleep(15)
" >/dev/null
sleep 2
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/latest-trigger")
if [ "$STATUS" != "200" ]; then
  echo "FAIL: expected HTTP 200 from /latest-trigger via host->container port mapping, got $STATUS"
  exit 1
fi
STATUS2=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${PORT}/latest-trigger")
if [ "$STATUS2" != "204" ]; then
  echo "FAIL: expected HTTP 204 on the second poll (already served), got $STATUS2"
  exit 1
fi
echo "OK: /latest-trigger reachable from host, serves the 200-then-204 semantics correctly"
docker rm -f "$CONTAINER" >/dev/null

echo "[4/4] Testing graceful shutdown (docker stop against the real main.py entrypoint)..."
python -c "
import cv2, numpy as np
fourcc = cv2.VideoWriter_fourcc(*'mp4v')
out = cv2.VideoWriter('$TEST_VIDEO', fourcc, 30.0, (320, 240))
rng = np.random.default_rng(1)
for _ in range(4000):
    out.write(rng.integers(0, 255, (240, 320, 3), dtype=np.uint8))
out.release()
"
docker run -d --name "$CONTAINER" \
  -v "$(pwd)/${TEST_VIDEO}:/data/sample.mp4:ro" \
  "$IMAGE" --video /data/sample.mp4 --host 0.0.0.0 --port 8765 >/dev/null
sleep 1
START=$(date +%s)
docker stop -t 5 "$CONTAINER" >/dev/null
ELAPSED=$(( $(date +%s) - START ))
EXIT_CODE=$(docker inspect "$CONTAINER" --format "{{.State.ExitCode}}")
if [ "$EXIT_CODE" = "137" ] || [ "$ELAPSED" -ge 5 ]; then
  echo "FAIL: container did not shut down gracefully (elapsed=${ELAPSED}s, exit=${EXIT_CODE}) — did main.py's SIGTERM handler regress?"
  exit 1
fi
echo "OK: shut down gracefully in ${ELAPSED}s (exit code ${EXIT_CODE}, not 137/SIGKILL)"

echo ""
echo "All container smoke tests passed."
