"""DMS AI Engine — drowsiness detection over a live camera feed.

Computes an EAR-based drowsiness score (sliding-window smoothed) and, once it
crosses `threshold`, sends a TRIGGER_ALERT to the AAOS device via the CarSky
shell bridge. See VITAL_GUARD_AI_E2E_GUIDE.md Phần III.1 for the full flow.
"""
import os
import time

import cv2
import mediapipe as mp
import requests

# CarSky node connection — override via environment variables, do not commit real keys.
GATEWAY_URL = os.environ.get("CARSKY_GATEWAY_URL", "https://hackathon-2.carsky.io")
ROOM_ID = os.environ.get("CARSKY_ROOM_ID", "")
NODE_KEY = os.environ.get("CARSKY_NODE_KEY", "ivi-android-node-key")
API_KEY = os.environ.get("CARSKY_API_KEY", "")

HEADERS = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json",
}


class DrowsinessDetector:
    def __init__(self, threshold=0.85, window_size=15):
        self.threshold = threshold
        self.window_size = window_size
        self.history = []
        self.mp_face_mesh = mp.solutions.face_mesh
        self.face_mesh = self.mp_face_mesh.FaceMesh(max_num_faces=1, refine_landmarks=True)

    def calculate_ear(self, landmarks):
        # TODO: compute Eye Aspect Ratio from MediaPipe eye landmarks (left/right eye indices).
        pass

    def check_drowsiness(self, ear, head_pose):
        # EAR closer to 0 (eyes closing) -> drowsiness score closer to 1.
        score = 1.0 - ear
        self.history.append(score)
        if len(self.history) > self.window_size:
            self.history.pop(0)

        avg_score = sum(self.history) / len(self.history)
        return avg_score

    def send_trigger(self, score):
        url = f"{GATEWAY_URL}/api/v1/vms/{ROOM_ID}/{NODE_KEY}/shell"
        payload = {
            "command": f"am broadcast -a com.vitalguard.ai.TRIGGER_ALERT --ef drowsiness_score {score}"
        }
        try:
            response = requests.post(url, headers=HEADERS, json=payload, timeout=5)
            if response.status_code == 200:
                print(f"[DMS AI] Trigger successfully sent! Score: {score}")
            else:
                print(f"[DMS AI] Trigger request failed: HTTP {response.status_code}")
        except requests.RequestException as e:
            print(f"[DMS AI] Connection error: {e}")

    def run(self):
        cap = cv2.VideoCapture(0)
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break

            rgb_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            results = self.face_mesh.process(rgb_frame)

            # TODO: replace with real EAR computed from results.multi_face_landmarks.
            ear = 0.15 if results.multi_face_landmarks else 0.3
            score = self.check_drowsiness(ear, None)

            if score >= self.threshold:
                self.send_trigger(score)
                time.sleep(2)

        cap.release()


if __name__ == "__main__":
    detector = DrowsinessDetector()
    detector.run()
