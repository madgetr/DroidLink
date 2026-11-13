"""Fast frame-to-frame tracking with classic computer vision (no model).

The vision model finds the target once (~1.5 s). After that, OpenCV's CSRT
tracker follows its box in each new frame in a few tens of milliseconds, so
the droid can steer continuously instead of stopping to look.
"""

import cv2
import numpy as np

from agent.vision import Box


TRACK_WIDTH = 320   # tracking doesn't need full resolution, and it's ~4x faster


def decode(jpeg: bytes, width: int = TRACK_WIDTH) -> np.ndarray:
    """JPEG -> image for the tracker. Boxes are fractions, so size doesn't matter.
    Use a bigger width for small (distant) targets so they keep enough detail."""
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if img.shape[1] > width:
        img = cv2.resize(img, (width, round(img.shape[0] * width / img.shape[1])),
                         interpolation=cv2.INTER_AREA)
    return img


class Tracker:
    def __init__(self, frame: np.ndarray, box: Box):
        h, w = frame.shape[:2]
        rect = (int(box.x1 * w), int(box.y1 * h), max(4, int(box.w * w)), max(4, int(box.h * h)))
        self._t = cv2.TrackerCSRT.create()
        self._t.init(frame, rect)

    def update(self, frame: np.ndarray) -> Box | None:
        """The target's box in this frame, or None if the tracker lost it."""
        ok, (x, y, bw, bh) = self._t.update(frame)
        if not ok or bw <= 0 or bh <= 0:
            return None
        h, w = frame.shape[:2]
        return Box(max(0.0, x / w), max(0.0, y / h), min(1.0, (x + bw) / w), min(1.0, (y + bh) / h))
