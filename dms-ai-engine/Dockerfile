FROM python:3.12-slim

# opencv-contrib-python (pulled in transitively by mediapipe) needs these
# system libs even in headless/no-GUI use — confirmed empirically: without
# them, `import cv2` inside this image raises
# "ImportError: libxcb.so.1: cannot open shared object file" (also reproduced
# with libGL.so.1 before libgl1 was added). No GUI code (cv2.imshow etc.)
# exists anywhere in this repo — this is purely to satisfy cv2's native
# shared-library dependencies, not for any actual display use.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender1 \
    libxcb1 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY services/ ./services/
COPY main.py ./

# The room-internal network-pin endpoint (GET /latest-trigger) — see
# docs/superpowers/specs/2026-07-28-dms-delivery-fsm-reconciliation-design.md
# Decision 3. No other port is ever opened; no outbound call is ever made.
EXPOSE 8765

# Default to --mock so `docker run` works out of the box with no extra setup —
# real video needs a file mounted into the container, e.g.:
#   docker run -v /path/to/sample.mp4:/data/sample.mp4 <image> --video /data/sample.mp4
ENTRYPOINT ["python", "main.py"]
CMD ["--mock", "--host", "0.0.0.0", "--port", "8765"]
