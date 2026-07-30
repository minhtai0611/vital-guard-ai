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
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# face_landmarker.task is fetched at BUILD time only, never at runtime --
# preserves the no-cloud-round-trip-in-the-runtime-path principle. Baked
# into the image so a running container never needs network access to
# function. Pinned to the numbered path (float16/1/), NOT float16/latest/ --
# `latest` is a moving alias, so a future rebuild (e.g. during final on-stage
# demo prep, possibly weeks after this line was written) could silently bake
# in a different model artifact with no build failure and no signal anything
# changed. Verified 2026-07-29: this exact numbered URL returns HTTP 200,
# Content-Length 3758596 bytes (~3.58 MiB), identical ETag/MD5
# (b0e7274907a1644404fef66b28dd6d85 / sOcnSQehZEQE/vZrKN1thQ==) to what
# `latest` resolved to on the same day -- i.e. version 1 IS the artifact
# `latest` currently points at. Placed before `COPY services/`/`COPY main.py`
# so Docker's layer cache keeps this (large, slow) download cached across
# rebuilds that only touch Python source. If MediaPipe ever publishes a
# face_landmarker v2, this URL will keep resolving to v1 (reproducible,
# no silent drift) -- bumping to a newer version is then a deliberate,
# reviewed change to this line, not something that happens for free.
RUN mkdir -p /app/models && \
    curl -fL -o /app/models/face_landmarker.task \
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

# hand_landmarker.task -- same rationale as face_landmarker.task above: baked
# in at BUILD time only (never fetched at runtime), pinned to the numbered
# path (float16/1/), NOT float16/latest/, for the same reproducibility
# reason. Added for distraction detection (hands-off-wheel), consumed by
# hand_tracker.py. Verified 2026-07-30: this exact numbered URL returns HTTP
# 200, Content-Length 7819105 bytes (~7.46 MiB), identical ETag/MD5
# (15318430ea3851670fe9914116a9cfad / FTGEMOo4UWcP6ZFBFqnPrQ==) to what
# `latest` resolved to on the same day -- i.e. version 1 IS the artifact
# `latest` currently points at.
RUN curl -fL -o /app/models/hand_landmarker.task \
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

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
