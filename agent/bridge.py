"""HTTP client for the droid bridge API running on the phone."""

import json
import os
import urllib.error
import urllib.parse
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
        self.torch = headers.get("X-Torch") == "on"       # flashlight was on for this shot
        self.yaw = _maybe_float(headers.get("X-Yaw"))     # gyro heading when it was taken


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

    def yaw(self) -> float | None:
        """Gyro heading in degrees (continuous; good for 'turn back to where X was')."""
        body, _ = self._get("/sensors/orientation")
        return json.loads(body).get("yaw")

    def snapshot(self, camera: int = 2, width: int = 1280) -> Snapshot:
        body, headers = self._get(f"/camera/snapshot?camera={camera}&width={width}")
        return Snapshot(body, headers)

    # --- motion (DroidLink only). Raise Unavailable while no droid is connected.

    def control_status(self) -> dict:
        body, _ = self._get("/control/status")
        return json.loads(body)

    def turn(self, degrees: float, tolerance: float = 5, attempts: int = 5) -> dict:
        """Gyro-guided turn. Quick turns: bigger tolerance, fewer correction attempts."""
        body, _ = self._get(f"/control/turn?degrees={degrees:.1f}&tolerance={tolerance:.0f}&attempts={attempts}")
        return json.loads(body)

    def velocity(self, left: float, right: float, ttl_ms: int = 400) -> dict:
        """Continuous driving: set wheel speeds (-1..1). The phone stops the motors by
        itself if no update arrives within ttl_ms (dead-man switch)."""
        body, _ = self._get(f"/control/velocity?left={left:.3f}&right={right:.3f}&ttl_ms={ttl_ms}")
        return json.loads(body)

    def drive(self, cm: float) -> dict:
        body, _ = self._get(f"/control/drive?cm={cm:.1f}")
        return json.loads(body)

    def droid_connect(self) -> dict:
        """Connect the phone to the droid (finds it if needed). Takes the droid away
        from its handheld remote until droid_disconnect()."""
        body, _ = self._get("/droid/connect")
        return json.loads(body)

    def droid_status(self) -> dict:
        body, _ = self._get("/droid/status")
        return json.loads(body)

    def droid_disconnect(self) -> dict:
        """Release the droid so its handheld remote works again."""
        body, _ = self._get("/droid/disconnect")
        return json.loads(body)

    def gesture(self, name: str) -> dict:
        """A canned move: glance, look_around, shake, double_take (head only, run in the
        background), or wiggle, nod, happy_spin, dance, sad, surprised (whole body)."""
        body, _ = self._get(f"/control/gesture?name={name}")
        return json.loads(body)

    def stop(self) -> dict:
        body, _ = self._get("/control/stop")
        return json.loads(body)

    def torch(self, mode: str) -> dict:
        """Set the saved flashlight mode: "on", "off" or "auto" (on when it's dark)."""
        body, _ = self._get(f"/camera/torch?mode={mode}")
        return json.loads(body)

    def light_off(self) -> dict:
        """Switch the flashlight off now, without changing the saved mode."""
        body, _ = self._get("/camera/torch?light=off")
        return json.loads(body)

    def listen(self, seconds: float = 6, cue: bool = True) -> dict:
        """Chirp (if cue) and listen for one utterance. {"text": "...", ...}; "" if silence."""
        body, _ = self._get(f"/voice/listen?seconds={seconds:.1f}&cue={'1' if cue else '0'}")
        return json.loads(body)

    def wake(self, enable: bool, names: list[str] | None = None) -> dict:
        """Turn background listening for the droid's name on or off."""
        q = f"/voice/wake?enable={'1' if enable else '0'}"
        if names:
            q += "&names=" + urllib.parse.quote(",".join(names))
        body, _ = self._get(q)
        return json.loads(body)

    def voice_events(self, since: int) -> dict:
        """Times the droid heard its name: {"next": n, "items": [{"text", "command"}, ...]}"""
        body, _ = self._get(f"/voice/events?since={since}")
        return json.loads(body)

    def overlay(self, box=None, label: str = "", status: str = "", color: str = "green") -> None:
        """Draw what the brain is tracking on the live /camera/stream (box = vision.Box)."""
        q = {"label": label, "status": status, "color": color}
        if box is not None:
            q.update(x1=f"{box.x1:.3f}", y1=f"{box.y1:.3f}", x2=f"{box.x2:.3f}", y2=f"{box.y2:.3f}")
        self._get("/camera/overlay?" + urllib.parse.urlencode(q))

    def log(self, who: str, text: str) -> None:
        """Add a line to the control panel's conversation log."""
        self._get("/agent/log?" + urllib.parse.urlencode({"who": who, "add": text}))

    def agent_status(self) -> dict:
        """{manual: someone is driving from the control panel, droid: connected, ...}"""
        body, _ = self._get("/agent/status")
        return json.loads(body)

    def settings(self) -> dict:
        """The droid's saved settings (name, voice) from the phone app."""
        body, _ = self._get("/settings")
        return json.loads(body)

    # --- radar: what the droid has seen and where (confidence fades on the phone)

    def memory(self) -> dict:
        """{pose, travel_cm, trail, items: [{thing, kind, relative_deg, distance_cm,
        age_s, confidence, uncertainty_cm, ...}]} (most recent first)."""
        body, _ = self._get("/memory")
        return json.loads(body)

    def memory_see(self, thing: str, offset: float | None = None, bearing: float | None = None,
                   distance_cm: float | None = None, confidence: float = 0.9,
                   hfov: float = 90, detector: bool = False) -> dict:
        """Put [thing] on the radar: [offset] across the current view (-0.5 left .. 0.5
        right) or an absolute gyro [bearing], and how far away if known."""
        q = {"thing": thing, "confidence": f"{confidence:.2f}", "hfov": f"{hfov:.0f}"}
        if detector:
            q["detector"] = "1"
        if bearing is not None:
            q["bearing"] = f"{bearing:.1f}"
        else:
            q["offset"] = f"{offset or 0:.3f}"
        if distance_cm is not None:
            q["distance"] = f"{distance_cm:.0f}"
        body, _ = self._get("/memory/see?" + urllib.parse.urlencode(q))
        return json.loads(body)

    def memory_missed(self, yaw: float, seen_ids: list[int], hfov: float = 90) -> dict:
        """The detector looked around heading [yaw] and saw only [seen_ids]: things it
        knows that should have been in view lose confidence, and vanish if gone."""
        body, _ = self._get("/memory/missed?" + urllib.parse.urlencode(
            {"yaw": f"{yaw:.1f}", "hfov": f"{hfov:.0f}", "seen": ",".join(map(str, seen_ids))}))
        return json.loads(body)

    def memory_doubt(self, thing: str, factor: float = 0.4, id: int | None = None) -> dict:
        """Looked where [thing] should be and it wasn't there: trust that memory less."""
        q = {"thing": thing, "factor": f"{factor:.2f}"}
        if id is not None:
            q["id"] = str(id)
        body, _ = self._get("/memory/doubt?" + urllib.parse.urlencode(q))
        return json.loads(body)

    # --- voice (DroidLink 0.3+)

    def chirp(self, mood: str) -> dict:
        body, _ = self._get(f"/sound/chirp?mood={mood}")
        return json.loads(body)

    def speak(self, text: str) -> dict:
        body, _ = self._get("/sound/speak?text=" + urllib.parse.quote(text))
        return json.loads(body)
