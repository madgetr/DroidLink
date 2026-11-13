"""Background readers for the phone's sensors via Termux:API.

`termux-sensor` takes ~3s to start, so instead of calling it per request we keep
one streaming process alive and cache the latest reading of each sensor.
"""

import asyncio
import json
import logging
import os
import signal
import time

log = logging.getLogger("sensors")

# Friendly name -> exact Android sensor name (from `termux-sensor -l` on the S23)
SENSORS = {
    "accelerometer": "lsm6dso LSM6DSO Accelerometer Non-wakeup",
    "gyroscope": "lsm6dso LSM6DSO Gyroscope Non-wakeup",
    "magnetometer": "AK09918 Magnetometer",
    "gravity": "gravity  Non-wakeup",
    "linear_acceleration": "linear_acceleration",
    "rotation_vector": "Rotation Vector  Non-wakeup",
    "orientation": "Orientation Sensor",
    "light": "STK33911 Light  Non-wakeup",
    "pressure": "lps22hh Pressure Sensor Non-wakeup",
    "proximity": "STK33911 Proximity  Wakeup",
    "step_counter": "step_counter  Non-wakeup",
}
NAME_TO_KEY = {v: k for k, v in SENSORS.items()}

SENSOR_INTERVAL_MS = 200
BATTERY_INTERVAL_S = 30


def _format(key, values):
    """Turn raw value arrays into labelled readings with units."""
    v = values
    if key in ("accelerometer", "gravity", "linear_acceleration") and len(v) >= 3:
        return {"x": v[0], "y": v[1], "z": v[2], "unit": "m/s^2"}
    if key == "gyroscope" and len(v) >= 3:
        return {"x": v[0], "y": v[1], "z": v[2], "unit": "rad/s"}
    if key == "magnetometer" and len(v) >= 3:
        return {"x": v[0], "y": v[1], "z": v[2], "unit": "uT"}
    if key == "orientation" and len(v) >= 3:
        return {"azimuth": v[0], "pitch": v[1], "roll": v[2], "unit": "deg"}
    if key == "rotation_vector" and len(v) >= 4:
        return {"x": v[0], "y": v[1], "z": v[2], "w": v[3]}
    if key == "light" and v:
        return {"value": v[0], "unit": "lux"}
    if key == "pressure" and v:
        return {"value": v[0], "unit": "hPa"}
    if key == "proximity" and v:
        return {"value": v[0], "unit": "cm"}
    if key == "step_counter" and v:
        return {"value": int(v[0]), "unit": "steps"}
    return {"values": v}


class SensorHub:
    def __init__(self):
        self.readings = {}   # key -> {"ts": float, **formatted}
        self.battery = None
        self.battery_ts = None
        self.error = None
        self._proc = None
        self._tasks = []

    async def start(self):
        self._tasks = [
            asyncio.create_task(self._sensor_loop()),
            asyncio.create_task(self._battery_loop()),
        ]

    async def stop(self):
        for t in self._tasks:
            t.cancel()
        # termux-sensor is a bash wrapper around a termux-api child, so kill the
        # whole process group, not just the wrapper.
        if self._proc and self._proc.returncode is None:
            try:
                os.killpg(self._proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        # Release the sensor listeners held by the Termux:API service.
        # This can hang, so never let it block shutdown.
        p = await asyncio.create_subprocess_exec(
            "termux-sensor", "-c", start_new_session=True,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        try:
            await asyncio.wait_for(p.wait(), timeout=3)
        except asyncio.TimeoutError:
            os.killpg(p.pid, signal.SIGKILL)

    async def _sensor_loop(self):
        names = ",".join(SENSORS.values())
        decoder = json.JSONDecoder()
        while True:
            try:
                self._proc = await asyncio.create_subprocess_exec(
                    "termux-sensor", "-s", names, "-d", str(SENSOR_INTERVAL_MS),
                    start_new_session=True,  # own process group, see stop()
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                buf = ""
                while True:
                    chunk = await self._proc.stdout.read(4096)
                    if not chunk:
                        break
                    buf += chunk.decode(errors="replace")
                    # Output is a stream of pretty-printed JSON objects; peel them off.
                    while True:
                        buf = buf.lstrip()
                        if not buf:
                            break
                        try:
                            obj, end = decoder.raw_decode(buf)
                        except json.JSONDecodeError:
                            break  # incomplete object, wait for more data
                        buf = buf[end:]
                        self._ingest(obj)
                err = (await self._proc.stderr.read()).decode(errors="replace").strip()
                self.error = f"termux-sensor exited: {err or self._proc.returncode}"
                log.warning(self.error)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # keep the loop alive no matter what
                self.error = repr(e)
                log.exception("sensor loop error")
            await asyncio.sleep(2)

    def _ingest(self, obj):
        now = time.time()
        for name, data in obj.items():
            key = NAME_TO_KEY.get(name)
            if key and isinstance(data, dict) and "values" in data:
                self.readings[key] = {"ts": now, **_format(key, data["values"])}
        self.error = None

    async def _battery_loop(self):
        while True:
            try:
                p = await asyncio.create_subprocess_exec(
                    "termux-battery-status",
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
                out, _ = await asyncio.wait_for(p.communicate(), timeout=15)
                self.battery = json.loads(out)
                self.battery_ts = time.time()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("battery read failed")
            await asyncio.sleep(BATTERY_INTERVAL_S)

    def snapshot(self):
        now = time.time()
        return {
            "ts": now,
            "sensors": {
                k: {**r, "age_s": round(now - r["ts"], 2)} for k, r in self.readings.items()
            },
            "battery": self.battery,
            "error": self.error,
        }
