"""Local droid agent using a vision model served by llama.cpp (llama-server).

Small local models describe photos well but lose the big picture (e.g. turning in
circles forever, or parroting earlier notes instead of looking). So each step is two
calls: describe the photo with no history (fresh eyes), then decide from that text plus
the history. And the code, not the model, tracks progress through three phases and
only offers the actions that make sense in each one:

  scan     - look around this spot: turn / drive / done
  choose   - full 360 seen: pick a heading to explore (go_toward) / done
  approach - facing that heading with a fresh photo: drive / go_toward / done

After driving, the droid is at a new spot and scans again. After MAX_SPOTS spots,
only "done" is allowed, so it gives up instead of wandering forever. The allowed
actions are enforced with llama.cpp's grammar-constrained JSON output.

Start the model server first (agent/start-llm.ps1), then from C:\\Users\\trevo\\bot:
    venv\\Scripts\\python -m agent.local_agent "Find the door"
"""

import argparse
import base64
import json
import os
import time
import urllib.request
from pathlib import Path

from agent.bridge import DroidBridge
from agent.tools import DroidTools

LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8080")
MAX_DRIVE_CM = 50   # safety cap per drive while things are experimental
MAX_SPOTS = 3       # give up after scanning this many spots
HALF_FOV = 50       # degrees each photo covers either side of its heading (ultrawide)

SYSTEM_PROMPT = """\
You are the brain of a small astromech droid. A phone on the droid gives you a camera \
and a compass. Each step you get the mission, what you have seen so far, and a fresh \
photo taken low to the ground at a compass heading (degrees clockwise from north).

Actions (you will only be offered the ones that make sense right now):
- "turn": rotate in place. amount = degrees, positive = right, negative = left.
- "drive": move straight ahead. amount = centimeters forward (max 50). Only if the \
photo shows the path ahead is clear.
- "go_toward": face a compass heading you saw earlier, to explore that way. \
amount = the compass heading (0-359). Pick the most promising or most open direction.
- "done": the mission is complete, or you have explored enough and should give up. \
Put your answer for the human in final_answer.

Rules:
- Describe only what you actually see in the photo. If unsure, say so.
- Never drive toward stairs, drops, table edges, pets or people.
- Keep observation and reasoning to one or two short sentences each."""

PHASE_ACTIONS = {
    "scan": ["turn", "drive", "done"],
    "choose": ["go_toward", "done"],
    "approach": ["drive", "go_toward", "done"],
    "give_up": ["done"],
}

PHASE_HINTS = {
    "scan": "You are looking around this spot. Turn about 90 degrees to see more, "
            "drive if you see where to go, or finish if the mission is complete.",
    "choose": "You have now looked all the way around this spot. Do NOT keep looking. "
              "Pick the most promising direction from what you saw (go_toward), "
              "or finish (done) if the mission is complete or hopeless.",
    "approach": "You turned to explore this direction. If the path ahead is clear, drive. "
                "If it is blocked, pick a different heading or finish.",
    "give_up": f"You have explored {MAX_SPOTS} spots. You must finish now: say what you "
               "found, or explain why you could not complete the mission.",
}


def decision_schema(actions: list[str]) -> dict:
    return {
        "type": "object",
        "properties": {
            "reasoning": {"type": "string"},
            "action": {"type": "string", "enum": actions},
            "amount": {"type": "number"},
            "final_answer": {"type": "string"},
        },
        "required": ["reasoning", "action", "amount", "final_answer"],
        "additionalProperties": False,
    }


def coverage(headings: list[float]) -> float:
    """Fraction of the full circle covered by photos at these headings."""
    seen = set()
    for h in headings:
        for d in range(-HALF_FOV, HALF_FOV + 1, 5):
            seen.add(int((h + d) % 360) // 5)
    return len(seen) / 72


def angle_diff(target: float, current: float) -> float:
    """Signed shortest turn from current to target heading, in degrees."""
    return (target - current + 540) % 360 - 180


DESCRIBE_PROMPT = """\
This photo was taken by a small robot's camera, low to the ground. The robot's mission \
is: "{mission}"

Answer about THIS photo only:
- scene: in two short sentences, what is actually visible (objects, furniture, people, \
doors, openings).
- target_visible: true if the thing the mission is looking for is visible in this photo.
- target_where: if visible, where in the frame (left / center / right, near / far); \
otherwise an empty string.
- floor_ahead_clear: true only if the floor straight ahead is clearly visible and free \
of obstacles."""

LOOK_SCHEMA = {
    "type": "object",
    "properties": {
        "scene": {"type": "string"},
        "target_visible": {"type": "boolean"},
        "target_where": {"type": "string"},
        "floor_ahead_clear": {"type": "boolean"},
    },
    "required": ["scene", "target_visible", "target_where", "floor_ahead_clear"],
    "additionalProperties": False,
}


def _chat(body: dict) -> str:
    req = urllib.request.Request(LLM_URL + "/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)["choices"][0]["message"]["content"]


def describe(jpeg: bytes, mission: str) -> tuple[dict, float]:
    """Stage 1: look at the photo with NO history (so the model can't copy old notes),
    but with the mission, so it flags the target when it sees it."""
    t = time.time()
    text = _chat({
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {
                "url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}},
            {"type": "text", "text": DESCRIBE_PROMPT.format(mission=mission)},
        ]}],
        "temperature": 0.2,
        "max_tokens": 200,
        "response_format": {"type": "json_schema", "json_schema": {
            "name": "look", "schema": LOOK_SCHEMA}},
    })
    return json.loads(text), time.time() - t


def summarize_look(look: dict) -> str:
    s = " ".join(look["scene"].split())
    if look["target_visible"]:
        s += f" TARGET VISIBLE ({look['target_where'] or 'in view'})."
    s += " Floor ahead clear." if look["floor_ahead_clear"] else " Floor ahead NOT clear."
    return s


def decide(prompt: str, actions: list[str]) -> tuple[dict, float]:
    """Stage 2: choose an action from the text description + history (no image)."""
    t = time.time()
    text = _chat({
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": prompt}],
        "temperature": 0.3,
        "max_tokens": 300,
        "response_format": {"type": "json_schema", "json_schema": {
            "name": "decision", "schema": decision_schema(actions)}},
    })
    return json.loads(text), time.time() - t


class Explorer:
    def __init__(self, mission: str, tools: DroidTools, camera: str):
        self.mission = mission
        self.tools = tools
        self.camera = camera
        self.spot = 1
        self.phase = "scan"
        self.here: list[tuple[float, str]] = []   # (heading, observation) at this spot
        self.earlier: list[str] = []              # summaries of previous spots

    def prompt(self, pose: str, seeing: str, target_visible: bool = False) -> str:
        lines = [f"MISSION: {self.mission}", ""]
        if self.earlier:
            lines += ["EARLIER SPOTS:"] + self.earlier + [""]
        lines.append(f"AT THIS SPOT (spot {self.spot} of max {MAX_SPOTS}) YOU HAVE SEEN:")
        lines += [f"- heading {h:.0f}: {obs}" for h, obs in self.here] or ["- (nothing yet)"]
        hint = PHASE_HINTS[self.phase]
        if target_visible:
            hint = ("What the mission is looking for is visible RIGHT NOW. Unless the mission "
                    "asks you to reach it, finish (done) and say where it is.")
        lines += ["", f"WHAT YOU SEE RIGHT NOW ({pose}): {seeing}", "",
                  f"NOW: {hint}",
                  f"Allowed actions: {', '.join(PHASE_ACTIONS[self.phase])}."]
        return "\n".join(lines)

    def step(self, n: int) -> bool:
        """Run one look-decide-act step. Returns True when the mission is over."""
        print(f"\n=== step {n} | spot {self.spot} | phase {self.phase} ===")
        t = time.time()
        snap = self.tools.bridge.snapshot(camera={"ultrawide": 2, "main": 0}[self.camera], width=640)
        self.tools.snap_count += 1
        (self.tools.run_dir / f"{self.tools.snap_count:03d}_{self.camera}.jpg").write_bytes(snap.jpeg)
        heading = snap.azimuth or 0.0
        pose = f"{self.camera} camera, heading {heading:.0f}"
        print(f"look:   {pose}  ({time.time() - t:.1f}s)")

        look, see_s = describe(snap.jpeg, self.mission)
        seeing = summarize_look(look)
        print(f"sees:   {seeing}  ({see_s:.1f}s)")
        actions = PHASE_ACTIONS[self.phase]
        if look["target_visible"] and "done" not in actions:
            actions = actions + ["done"]
        d, think_s = decide(self.prompt(pose, seeing, look["target_visible"]), actions)
        print(f"thinks: {d['reasoning']}")
        print(f"action: {d['action']} {d['amount']:g}  ({think_s:.1f}s)")
        self.here.append((heading, seeing))

        if d["action"] == "done":
            answer = d["final_answer"].strip()
            if len(answer) < 20:   # small models sometimes just write "done"
                answer = (f"Found it at heading {heading:.0f}° ({look['target_where'] or 'in view'}). "
                          f"{look['scene']}") if look["target_visible"] else d["reasoning"]
            print(f"\nDroid: {answer}")
            return True

        if d["action"] == "turn":
            print("result:", self.tools.run("turn", {"degrees": max(-180, min(180, d["amount"]))})[0]["text"])
        elif d["action"] == "go_toward":
            delta = angle_diff(d["amount"] % 360, heading)
            print("result:", self.tools.run("turn", {"degrees": delta})[0]["text"])
            self.phase = "approach"
            return False
        elif d["action"] == "drive":
            cm = max(-MAX_DRIVE_CM, min(MAX_DRIVE_CM, d["amount"]))
            print("result:", self.tools.run("drive", {"distance_cm": cm})[0]["text"])
            self.new_spot(f"drove {cm:+.0f} cm toward heading {heading:.0f}")
            return False

        # Still scanning: move on once the whole circle has been seen
        cov = coverage([h for h, _ in self.here])
        print(f"        (seen {cov:.0%} of the circle at this spot)")
        if self.phase == "scan" and cov >= 0.95:
            self.phase = "give_up" if self.spot >= MAX_SPOTS else "choose"
        return False

    def new_spot(self, how: str):
        seen = "; ".join(f"{h:.0f}: {o}" for h, o in self.here)
        self.earlier.append(f"- spot {self.spot}: saw [{seen}], then {how}")
        self.earlier = self.earlier[-3:]   # keep the prompt small
        self.spot += 1
        self.here = []
        self.phase = "scan"


def run(mission: str, max_steps: int, camera: str) -> None:
    bridge = DroidBridge()
    print(f"Bridge: {bridge.base_url} -> {bridge.health()['status']}")
    with urllib.request.urlopen(LLM_URL + "/health", timeout=5) as r:
        print(f"LLM:    {LLM_URL} -> {json.load(r).get('status')}")

    run_dir = Path(__file__).parent / "runs" / time.strftime("%Y%m%d-%H%M%S-local")
    explorer = Explorer(mission, DroidTools(bridge, run_dir), camera)
    for n in range(1, max_steps + 1):
        if explorer.step(n):
            return
    print(f"\nStopped after {max_steps} steps.")


def main():
    ap = argparse.ArgumentParser(description="Run the local (llama.cpp) droid agent.")
    ap.add_argument("mission", nargs="?", default="Look around and tell me what you see.")
    ap.add_argument("--max-steps", type=int, default=25)
    ap.add_argument("--camera", default="ultrawide", choices=["ultrawide", "main"])
    args = ap.parse_args()
    try:
        run(args.mission, args.max_steps, args.camera)
    except KeyboardInterrupt:
        print("\nAborted.")


if __name__ == "__main__":
    main()
