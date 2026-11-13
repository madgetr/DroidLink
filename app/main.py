"""Droid bridge API - runs on the Termux phone mounted on the droid."""

import platform
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query, Response

from app.camera import CAMERAS, take_snapshot
from app.sensors import SENSORS, SensorHub

START_TIME = time.time()
hub = SensorHub()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await hub.start()
    yield
    await hub.stop()


app = FastAPI(title="Droid Bridge", version="0.2.0", lifespan=lifespan)


@app.get("/")
def root():
    return {"name": "droid-bridge", "status": "ok"}


@app.get("/health")
def health():
    return {
        "status": "ok",
        "uptime_s": round(time.time() - START_TIME, 1),
        "python": platform.python_version(),
        "machine": platform.machine(),
    }


@app.get(
    "/camera/snapshot",
    response_class=Response,
    responses={200: {"content": {"image/jpeg": {}}}},
)
async def camera_snapshot(
    camera: int = Query(0, description="0=back main, 2=back ultrawide, 1/3=front"),
    width: int = Query(1280, ge=160, le=4080, description="Output width in pixels"),
    quality: int = Query(80, ge=10, le=95, description="JPEG quality"),
):
    """Take a photo (~3.5s). Heading/tilt at capture time are in the X-* headers."""
    if camera not in CAMERAS:
        raise HTTPException(404, f"unknown camera; choose from {CAMERAS}")
    orient = hub.readings.get("orientation", {})  # pose just before the shot
    try:
        jpeg, ts = await take_snapshot(camera, width, quality)
    except Exception as e:
        raise HTTPException(503, f"camera error: {e}")
    headers = {"X-Capture-Ts": f"{ts:.3f}", "X-Camera": str(camera)}
    for k in ("azimuth", "pitch", "roll"):
        if k in orient:
            headers[f"X-{k.capitalize()}"] = f"{orient[k]:.1f}"
    return Response(jpeg, media_type="image/jpeg", headers=headers)


@app.get("/sensors")
def sensors():
    """Latest reading of every sensor, plus battery status."""
    return hub.snapshot()


@app.get("/sensors/{name}")
def sensor(name: str):
    """Latest reading of one sensor, e.g. /sensors/orientation."""
    if name == "battery":
        return {"ts": hub.battery_ts, **(hub.battery or {})}
    if name not in SENSORS:
        raise HTTPException(404, f"unknown sensor; choose from {sorted(SENSORS) + ['battery']}")
    reading = hub.snapshot()["sensors"].get(name)
    if reading is None:
        raise HTTPException(503, "no reading yet")
    return reading
