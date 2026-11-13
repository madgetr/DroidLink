"""What the droid sees: one photo -> a fixed set of answers the code can act on.

Every answer is constrained to a fixed set of choices (via a JSON schema), so a
small model can't produce something the mission code doesn't understand. The
model never sees mission history here, so it looks with fresh eyes each time.
"""

from dataclasses import dataclass

from agent import llm

WHERE = ["none", "left", "center", "right"]
DISTANCE = ["none", "near", "medium", "far"]

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
- interesting: a short phrase naming the most interesting thing in view, or ""."""

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
    },
    "required": ["scene", "person_where", "person_distance", "pet_kind",
                 "pet_where", "pet_distance", "floor_ahead_clear", "interesting"],
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


def perceive(jpeg: bytes) -> dict:
    return llm.chat(
        [{"role": "user", "content": [llm.image_part(jpeg), {"type": "text", "text": PROMPT}]}],
        schema=SCHEMA, max_tokens=250, temperature=0.2)
