"""Precise target location: where exactly is "the dog" in the frame?

Qwen3-VL can ground objects as bounding boxes in 0-1000 coordinates. That's
far more useful for steering than "left/center/right": the box's horizontal
centre tells us how many degrees to turn, and its size/bottom edge tells us
roughly how close we are. ~1 s per call on the laptop GPU.
"""

from dataclasses import dataclass

from agent import llm

SCHEMA = {
    "type": "object",
    "properties": {
        "found": {"type": "boolean"},
        "bbox_2d": {"type": "array", "items": {"type": "integer", "minimum": 0, "maximum": 1000},
                    "minItems": 4, "maxItems": 4},
    },
    "required": ["found", "bbox_2d"],
    "additionalProperties": False,
}


@dataclass
class Box:
    """A bounding box in fractions of the image (0..1)."""
    x1: float
    y1: float
    x2: float
    y2: float

    @property
    def cx(self) -> float:          # horizontal centre: 0 = left edge, 1 = right edge
        return (self.x1 + self.x2) / 2

    @property
    def w(self) -> float:
        return self.x2 - self.x1

    @property
    def h(self) -> float:
        return self.y2 - self.y1

    @property
    def area(self) -> float:
        return self.w * self.h

    def near(self, target: str) -> bool:
        """Close enough to count as 'there'. Uses width, not height: from the
        droid's low camera a standing person fills the frame's height even from
        2 m away. People are reached sooner (don't run into feet)."""
        if any(w in target for w in ("person", "human", "man", "woman", "kid", "child", "me")):
            return self.w >= 0.40
        return self.w >= 0.6 or self.y2 >= 0.95

    def iou(self, other: "Box") -> float:
        """Overlap with another box (0 = none, 1 = identical)."""
        ix = max(0.0, min(self.x2, other.x2) - max(self.x1, other.x1))
        iy = max(0.0, min(self.y2, other.y2) - max(self.y1, other.y1))
        inter = ix * iy
        return inter / (self.area + other.area - inter + 1e-9)

    def __str__(self) -> str:
        return f"at x={self.cx:.2f} (w={self.w:.2f}, h={self.h:.2f}, bottom={self.y2:.2f})"


PATH_SCHEMA = {
    "type": "object",
    "properties": {**SCHEMA["properties"], "path_clear": {"type": "boolean"}},
    "required": SCHEMA["required"] + ["path_clear"],
    "additionalProperties": False,
}


def locate_with_path(jpeg: bytes, target: str) -> tuple[Box | None, bool]:
    """Like locate(), plus: is the floor between the robot and the target clear?
    One call, so checking the path while driving costs almost nothing extra."""
    r = llm.chat([{"role": "user", "content": [
        llm.image_part(jpeg),
        {"type": "text", "text": (
            f"This photo is from a small robot's camera, low to the ground. Locate the {target} "
            "in it: a real one, not a picture, poster, toy or screen. If there are several, pick "
            "the nearest. Give its bounding box as [x1, y1, x2, y2] with coordinates from 0 to "
            f"1000 relative to the image size (found=false and [0,0,0,0] if no real {target} is "
            f"visible). path_clear: true only if the robot could drive straight toward the "
            f"{target} without hitting furniture, walls or other obstacles on the way.")},
    ]}], schema=PATH_SCHEMA, max_tokens=70, temperature=0)
    x1, y1, x2, y2 = r["bbox_2d"]
    if not r["found"] or x2 <= x1 or y2 <= y1:
        return None, bool(r["path_clear"])
    return Box(x1 / 1000, y1 / 1000, x2 / 1000, y2 / 1000), bool(r["path_clear"])


def locate(jpeg: bytes, target: str) -> Box | None:
    """Find [target] in the photo; None if it isn't visible."""
    r = llm.chat([{"role": "user", "content": [
        llm.image_part(jpeg),
        {"type": "text", "text": (
            f"Locate the {target} in this image: a real one, not a picture, poster, toy or "
            "screen showing one. If there are several, pick the nearest. If it is visible, give "
            "its bounding box as [x1, y1, x2, y2] with coordinates from 0 to 1000 relative to "
            f"the image size. If no real {target} is visible, set found to false and bbox_2d to "
            "[0,0,0,0].")},
    ]}], schema=SCHEMA, max_tokens=60, temperature=0)
    x1, y1, x2, y2 = r["bbox_2d"]
    if not r["found"] or x2 <= x1 or y2 <= y1:
        return None
    return Box(x1 / 1000, y1 / 1000, x2 / 1000, y2 / 1000)
