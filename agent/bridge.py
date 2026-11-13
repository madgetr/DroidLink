"""HTTP client for the droid bridge API running on the phone."""

import json
import os
import urllib.request

DROID_URL = os.environ.get("DROID_URL", "http://192.168.2.250:8000")


class Snapshot:
    def __init__(self, jpeg: bytes, headers):
        self.jpeg = jpeg
        self.ts = float(headers.get("X-Capture-Ts", 0))
        self.camera = int(headers.get("X-Camera", 0))
        self.azimuth = _maybe_float(headers.get("X-Azimuth"))
        self.pitch = _maybe_float(headers.get("X-Pitch"))
        self.roll = _maybe_float(headers.get("X-Roll"))


def _maybe_float(v):
    return float(v) if v not in (None, "") else None


class DroidBridge:
    def __init__(self, base_url: str = DROID_URL, timeout: float = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _get(self, path: str):
        with urllib.request.urlopen(self.base_url + path, timeout=self.timeout) as r:
            return r.read(), r.headers

    def health(self) -> dict:
        body, _ = self._get("/health")
        return json.loads(body)

    def sensors(self) -> dict:
        body, _ = self._get("/sensors")
        return json.loads(body)

    def snapshot(self, camera: int = 2, width: int = 1280) -> Snapshot:
        body, headers = self._get(f"/camera/snapshot?camera={camera}&width={width}")
        return Snapshot(body, headers)
