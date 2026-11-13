"""HTTP client for the droid bridge API running on the phone."""

import json
import os
import urllib.error
import urllib.request

# DroidLink app on the phone. (The old Termux FastAPI server is on port 8000.)
DROID_URL = os.environ.get("DROID_URL", "http://192.168.2.250:8765")


class Unavailable(Exception):
    """The bridge can't do this right now (HTTP 503), e.g. no droid connected."""


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
        try:
            with urllib.request.urlopen(self.base_url + path, timeout=self.timeout) as r:
                return r.read(), r.headers
        except urllib.error.HTTPError as e:
            if e.code == 503:
                raise Unavailable(json.loads(e.read()).get("detail", "unavailable")) from None
            raise

    def health(self) -> dict:
        body, _ = self._get("/health")
        return json.loads(body)

    def sensors(self) -> dict:
        body, _ = self._get("/sensors")
        return json.loads(body)

    def snapshot(self, camera: int = 2, width: int = 1280) -> Snapshot:
        body, headers = self._get(f"/camera/snapshot?camera={camera}&width={width}")
        return Snapshot(body, headers)

    # --- motion (DroidLink only). Raise Unavailable while no droid is connected.

    def control_status(self) -> dict:
        body, _ = self._get("/control/status")
        return json.loads(body)

    def turn(self, degrees: float) -> dict:
        body, _ = self._get(f"/control/turn?degrees={degrees:.1f}")
        return json.loads(body)

    def drive(self, cm: float) -> dict:
        body, _ = self._get(f"/control/drive?cm={cm:.1f}")
        return json.loads(body)

    def stop(self) -> dict:
        body, _ = self._get("/control/stop")
        return json.loads(body)
