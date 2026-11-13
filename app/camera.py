"""Still snapshots via `termux-camera-photo` (~3.4s per shot, full-res JPEG).

The phone's camera can't be opened directly from Termux, so each shot goes
through the Termux:API app. We then downscale with Pillow for fast transfer.
"""

import asyncio
import io
import os
import time

from PIL import Image, ImageOps

# Camera ids on the S23 (from `termux-camera-info`)
CAMERAS = {0: "back main", 1: "front", 2: "back ultrawide", 3: "front (alt)"}

SNAP_PATH = os.path.expanduser("~/.droid/snap.jpg")
_lock = asyncio.Lock()  # the camera can only take one shot at a time


async def take_snapshot(camera: int = 0, width: int = 1280, quality: int = 80) -> tuple[bytes, float]:
    """Capture a photo and return (jpeg_bytes, capture_timestamp)."""
    async with _lock:
        if os.path.exists(SNAP_PATH):
            os.remove(SNAP_PATH)
        ts = time.time()
        proc = await asyncio.create_subprocess_exec(
            "termux-camera-photo", "-c", str(camera), SNAP_PATH,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        out, err = await asyncio.wait_for(proc.communicate(), timeout=20)
        if not os.path.exists(SNAP_PATH) or os.path.getsize(SNAP_PATH) == 0:
            raise RuntimeError((out + err).decode(errors="replace").strip() or "capture failed")
        # Resize off the event loop so sensor streaming keeps running
        data = await asyncio.to_thread(_resize, SNAP_PATH, width, quality)
        return data, ts


def _resize(path: str, width: int, quality: int) -> bytes:
    with Image.open(path) as img:
        # Decode the 12MP JPEG at a reduced scale (1/2, 1/4, 1/8) that is still
        # >= the target size on both sides - ~2-4x faster than a full decode.
        img.draft("RGB", (width, width))
        img = ImageOps.exif_transpose(img)  # apply the camera's rotation flag
        if img.width > width:
            img = img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)
        buf = io.BytesIO()
        img.convert("RGB").save(buf, "JPEG", quality=quality)
        return buf.getvalue()
