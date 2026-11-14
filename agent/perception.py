"""What the droid sees: one photo -> a fixed set of answers the code can act on.

Every answer is constrained to a fixed set of choices (via a JSON schema), so a
small model can't produce something the mission code doesn't understand. The
model never sees mission history here, so it looks with fresh eyes each time.
"""

from dataclasses import dataclass, field

from agent import llm

WHERE = ["none", "left", "center", "right"]
DISTANCE = ["none", "near", "medium", "far"]
SPOT = ["far left", "left", "center", "right", "far right"]   # across the frame, for the radar
SPOT_OFFSET = {"far left": -0.38, "left": -0.2, "center": 0.0, "right": 0.2, "far right": 0.38}
DISTANCE_CM = {"near": 70, "medium": 170, "far": 350}

PROMPT = """\
This photo is from a small robot's camera, low to the ground. Answer about THIS photo only.
- scene: one short sentence describing what is visible.
- person_where / person_distance: is a person (or part of one, like legs or feet) \
visible? Where in the frame (left, center, right), and how far (near = within about one \
meter or filling much of the frame, medium, far)? Use "none" for both if no person.
- pet_kind / pet_where / pet_distance: same for a dog, cat or other animal. pet_kind \
is e.g. "dog" or "cat", or "" if none.
- floor_ahead_clear: true only if the floor straight ahead is visible and free of \
obstacles for about half a meter.
- interesting: a short phrase naming the most interesting thing in view, or "".
- things: up to 4 real objects you can clearly see (furniture, doors, toys, people, pets...), each with a short name (1-3 words, e.g. "couch", "red ball"), where it is across the frame, and how far."""

SCHEMA = {
    "type": "object",
    "properties": {
        "scene": {"type": "string"},
        "person_where": {"type": "string", "enum": WHERE},
        "person_distance": {"type": "string", "enum": DISTANCE},
        "pet_kind": {"type": "string"},
        "pet_where": {"type": "string", "enum": WHERE},
        "pet_distance": {"type": "string", "enum": DISTANCE},
        "floor_ahead_clear": {"type": "boolean"},
        "interesting": {"type": "string"},
        "things": {"type": "array", "maxItems": 4, "items": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "maxLength": 30},
                "where": {"type": "string", "enum": SPOT},
                "distance": {"type": "string", "enum": DISTANCE[1:]},
            },
            "required": ["name", "where", "distance"], "additionalProperties": False}},
    },
    "required": ["scene", "person_where", "person_distance", "pet_kind",
                 "pet_where", "pet_distance", "floor_ahead_clear", "interesting", "things"],
    "additionalProperties": False,
}


@dataclass
class Perception:
    scene: str
    person_where: str
    person_distance: str
    pet_kind: str
    pet_where: str
    pet_distance: str
    floor_ahead_clear: bool
    interesting: str
    things: list = field(default_factory=list)   # [{name, where, distance}] for the radar
    heading: float | None = None
    seconds: float = 0.0

    @property
    def person(self) -> bool:
        return self.person_where != "none"

    @property
    def pet(self) -> bool:
        return self.pet_where != "none"

    def summary(self) -> str:
        parts = [self.scene.strip()]
        if self.person:
            parts.append(f"A person is visible ({self.person_where}, {self.person_distance}).")
        if self.pet:
            parts.append(f"A {self.pet_kind or 'pet'} is visible ({self.pet_where}, {self.pet_distance}).")
        parts.append("The floor ahead is clear." if self.floor_ahead_clear else "The floor ahead is blocked.")
        return " ".join(parts)


FLOOR_SCHEMA = {"type": "object", "properties": {"clear": {"type": "boolean"}},
                "required": ["clear"], "additionalProperties": False}


def floor_clear(jpeg: bytes) -> bool:
    """Quick yes/no (~1 s): is the floor straight ahead clear to drive on?"""
    r = llm.chat([{"role": "user", "content": [llm.image_part(jpeg), {"type": "text", "text": (
        "This photo is from a small robot's camera, low to the ground. Is the floor straight "
        "ahead clear of obstacles for about half a meter? Answer clear: true or false.")}]}],
        schema=FLOOR_SCHEMA, max_tokens=12, temperature=0)
    return bool(r["clear"])


# With the object detector doing people, pets and common objects, the model only
# has to describe the scene and judge the floor: far fewer tokens, so much faster.
SLIM_PROMPT = """\
This photo is from a small robot's camera, low to the ground. Answer about THIS photo only.
- scene: one short sentence describing what is visible.
- floor_ahead_clear: true only if the floor straight ahead is visible and free of \
obstacles for about half a meter.
- interesting: a short phrase naming the most interesting thing in view, or "".
- things: up to 3 notable objects that are NOT people, pets or everyday household items \
(e.g. "hockey net", "weight bench", "toy castle"), with where each is and how far."""

SLIM_SCHEMA = {
    "type": "object",
    "properties": {
        "scene": {"type": "string"},
        "floor_ahead_clear": {"type": "boolean"},
        "interesting": {"type": "string"},
        "things": {**SCHEMA["properties"]["things"], "maxItems": 3},
    },
    "required": ["scene", "floor_ahead_clear", "interesting", "things"],
    "additionalProperties": False,
}


def _where(cx: float) -> str:
    return "left" if cx < 0.36 else "right" if cx > 0.64 else "center"


def _distance(box) -> str:
    return "near" if box.w >= 0.35 or box.y2 >= 0.97 else "medium" if box.w >= 0.12 else "far"


# (maxLength limits and the "things" list each slow the constrained output a lot:
#  ~1.1 s without them vs ~4 s with. Hence things only on request.)
QUICK_SCHEMA = {
    "type": "object",
    "properties": {k: SLIM_SCHEMA["properties"][k] for k in ("scene", "floor_ahead_clear", "interesting")},
    "required": ["scene", "floor_ahead_clear", "interesting"],
    "additionalProperties": False,
}
QUICK_PROMPT = SLIM_PROMPT.split("- things")[0].rstrip()


def perceive(jpeg: bytes, dets: list | None = None, things: bool = False) -> dict:
    """Describe the photo. With [dets] from the object detector, people and pets
    come from those and the model does less: ~1 s, or ~4 s with [things] (unusual
    objects for the radar, which the detector doesn't know)."""
    if dets is None:
        return llm.chat(
            [{"role": "user", "content": [llm.image_part(jpeg), {"type": "text", "text": PROMPT}]}],
            schema=SCHEMA, max_tokens=400, temperature=0.2)
    r = llm.chat(
        [{"role": "user", "content": [llm.image_part(jpeg), {"type": "text",
                                                            "text": SLIM_PROMPT if things else QUICK_PROMPT}]}],
        schema=SLIM_SCHEMA if things else QUICK_SCHEMA, max_tokens=260 if things else 120,
        temperature=0.2)
    r.setdefault("things", [])
    person = next((d for d in dets if d.label == "person" and d.conf >= 0.45), None)
    pet = next((d for d in dets if d.label in ("dog", "cat", "bird") and d.conf >= 0.4), None)
    r.update(
        person_where=_where(person.box.cx) if person else "none",
        person_distance=_distance(person.box) if person else "none",
        pet_kind=pet.label if pet else "",
        pet_where=_where(pet.box.cx) if pet else "none",
        pet_distance=_distance(pet.box) if pet else "none",
    )
    return r
