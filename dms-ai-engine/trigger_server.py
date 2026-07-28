"""
trigger_server
--------------
Local HTTP server (Container Node side of the room-internal "network pin").
Serves the single most recent trigger payload to the Skycraft App over
GET /latest-trigger — 200 + JSON body if there's a payload not yet served,
204 otherwise. No external host is ever contacted (see design doc Decision 3).
"""
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Optional


class LatestTriggerStore:
    def __init__(self):
        self._lock = threading.Lock()
        self._latest: Optional[dict] = None
        self._served = False

    def update_latest(self, payload: dict) -> None:
        with self._lock:
            self._latest = payload
            self._served = False

    def take_if_unserved(self) -> Optional[dict]:
        with self._lock:
            if self._latest is not None and not self._served:
                self._served = True
                return self._latest
            return None


def _make_handler(store: LatestTriggerStore):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass  # tắt log HTTP mặc định gây nhiễu console demo

        def do_GET(self):
            if self.path != "/latest-trigger":
                self.send_response(404)
                self.end_headers()
                return
            payload = store.take_if_unserved()
            if payload is None:
                self.send_response(204)
                self.end_headers()
                return
            body = json.dumps(payload).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return Handler


def start_background_server(store: LatestTriggerStore, host: str = "0.0.0.0",
                              port: int = 8765) -> HTTPServer:
    server = HTTPServer((host, port), _make_handler(store))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server
