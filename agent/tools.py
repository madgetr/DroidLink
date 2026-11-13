"""Droid tools, kept provider-neutral so a llama.cpp agent can reuse them.

Each tool returns a list of parts: {"type": "text", "text": ...} or
{"type": "image", "jpeg": bytes}. Each agent backend converts these into its
own message format.

Movement isn't wired to the droid yet, so turn/drive run in "manual" mode:
the agent asks a human to move the droid, then checks the compass.
"""

import time
from pathlib import Path

from agent.bridge import DroidBridge

CAMERAS = {"ultrawide": 2, "main": 0}

TOOL_SPECS = [
    {
        "name": "look",
        "description": (
            "Take a photo from the droid's camera (takes ~4s). Returns the image plus the "
            "compass heading and tilt at the moment of capture. The ultrawide camera sees "
            "more of the room; the main camera has more detail at a distance."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "camera": {"type": "string", "enum": list(CAMERAS), "description": "Default: ultrawide"},
            },
            "required": [],
            "additionalProperties": False,
        },
    },
    {
        "name": "read_sensors",
        "description": (
            "Read the droid's sensors: compass heading/pitch/roll (deg), linear acceleration "
            "(m/s^2, gravity removed), ambient light (lux), proximity (cm), and battery."
        ),
        "input_schema": {"type": "object", "properties": {}, "required": [], "additionalProperties": False},
    },
    {
        "name": "turn",
        "description": (
            "Rotate the droid in place. Positive degrees turn right (clockwise seen from above), "
            "negative turn left. Returns the new compass heading."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"degrees": {"type": "number", "minimum": -180, "maximum": 180}},
            "required": ["degrees"],
            "additionalProperties": False,
        },
    },
    {
        "name": "drive",
        "description": (
            "Drive straight. Positive cm goes forward, negative backs up. Only drive when "
            "the path ahead looks clear in a recent photo."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"distance_cm": {"type": "number", "minimum": -100, "maximum": 200}},
            "required": ["distance_cm"],
            "additionalProperties": False,
        },
    },
]


def _text(s: str) -> list[dict]:
    return [{"type": "text", "text": s}]


class DroidTools:
    def __init__(self, bridge: DroidBridge, run_dir: Path, movement: str = "manual",
                 image_width: int = 1280):
        self.bridge = bridge
        self.run_dir = run_dir
        self.movement = movement
        self.image_width = image_width
        self.snap_count = 0
        run_dir.mkdir(parents=True, exist_ok=True)

    def run(self, name: str, args: dict) -> list[dict]:
        handler = getattr(self, f"tool_{name}", None)
        if handler is None:
            raise ValueError(f"unknown tool {name!r}")
        return handler(**args)

    # --- tools ---------------------------------------------------------

    def tool_look(self, camera: str = "ultrawide") -> list[dict]:
        snap = self.bridge.snapshot(camera=CAMERAS[camera], width=self.image_width)
        self.snap_count += 1
        path = self.run_dir / f"{self.snap_count:03d}_{camera}.jpg"
        path.write_bytes(snap.jpeg)
        print(f"   [saved {path}]")
        pose = (
            f"heading {snap.azimuth:.0f}°, pitch {snap.pitch:.0f}°, roll {snap.roll:.0f}°"
            if snap.azimuth is not None else "pose unavailable"
        )
        return [{"type": "text", "text": f"{camera} camera photo; {pose}"},
                {"type": "image", "jpeg": snap.jpeg}]

    def tool_read_sensors(self) -> list[dict]:
        d = self.bridge.sensors()
        s, b = d.get("sensors", {}), d.get("battery") or {}
        o, la = s.get("orientation", {}), s.get("linear_acceleration", {})
        lines = []
        if o:
            lines.append(f"heading {o['azimuth']:.0f}°, pitch {o['pitch']:.0f}°, roll {o['roll']:.0f}°")
        if la:
            lines.append(f"linear accel x={la['x']:.2f} y={la['y']:.2f} z={la['z']:.2f} m/s^2")
        if "light" in s:
            lines.append(f"light {s['light']['value']:.0f} lux")
        if "proximity" in s:
            lines.append(f"proximity {s['proximity']['value']:.0f} cm")
        if b:
            lines.append(f"battery {b.get('percentage')}% ({b.get('status', '').lower()})")
        return _text("\n".join(lines) or "no sensor data")

    def tool_turn(self, degrees: float) -> list[dict]:
        before = self._heading()
        if self.movement == "manual":
            side = "RIGHT" if degrees > 0 else "LEFT"
            input(f"\n>>> Please turn the droid {side} {abs(degrees):.0f}° and press Enter... ")
        else:
            raise NotImplementedError("BLE movement not wired up yet")
        time.sleep(0.5)  # let the compass settle
        after = self._heading()
        if before is None or after is None:
            return _text("turn done; heading unavailable")
        actual = (after - before + 540) % 360 - 180
        return _text(f"turned {actual:+.0f}° (requested {degrees:+.0f}°); heading now {after:.0f}°")

    def tool_drive(self, distance_cm: float) -> list[dict]:
        if self.movement == "manual":
            way = "FORWARD" if distance_cm > 0 else "BACKWARD"
            input(f"\n>>> Please move the droid {way} {abs(distance_cm):.0f} cm and press Enter... ")
        else:
            raise NotImplementedError("BLE movement not wired up yet")
        return _text(f"drove {distance_cm:+.0f} cm")

    def _heading(self):
        o = self.bridge.sensors().get("sensors", {}).get("orientation")
        return o["azimuth"] if o else None
