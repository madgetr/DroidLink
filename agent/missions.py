"""The droid's body, voice, and mission menu.

Missions are plain code controllers: the model perceives and talks, the code
decides how to move. That keeps a small local model from wandering off.

Most missions take a *target* ("dog", "person", "red ball"): the droid locates
it precisely (bounding box), turns until it's centred, and approaches it.
"""

import queue
import random
import re
import threading
import time
from pathlib import Path

from agent import llm
from agent.bridge import DroidBridge, Unavailable
from agent.perception import Perception, floor_clear, perceive
from agent.tools import DroidTools
from agent.tracking import Tracker, decode
from agent.vision import Box, locate, locate_with_path

MOODS = ["happy", "curious", "excited", "sad", "alert", "greeting"]

PERSONA = """\
You are {name}, a small astromech droid that a parent and their kid built together at \
a droid workshop. You are brave, curious, loyal and a little cheeky. Like the astromech \
droids in Star Wars, you talk in beeps, whistles and chirps. Keep everything kid-friendly, \
and keep it short."""

# Body language that can go with a spoken line (small moves only: no spinning off
# mid-conversation). "none" is fine most of the time.
REACTIONS = ["none", "glance", "look_around", "double_take", "shake", "nod", "wiggle", "surprised", "sad"]
REACTION_HINT = ("move: optional body language to go with it: nod (yes/agree), shake (no), "
                 "wiggle (happy/excited), double_take or surprised (surprise), sad, glance or "
                 "look_around (curious), or none.")

SAY_SCHEMA = {
    "type": "object",
    "properties": {
        "mood": {"type": "string", "enum": MOODS},
        "beeps": {"type": "string"},
        "translation": {"type": "string"},
        "move": {"type": "string", "enum": REACTIONS},
    },
    "required": ["mood", "beeps", "translation", "move"],
    "additionalProperties": False,
}

SYLLABLES = ["bweep", "dwoo", "tweet", "brrrp", "zwee", "doot", "bleep", "wooo", "trill",
             "boop", "fweet", "dweedle", "beep", "vrrr", "tik-tik", "whirr", "bzzt", "woop"]


def beeps_hint() -> str:
    """Fresh example syllables each call, so the model doesn't parrot one sound."""
    return ("the droid sounds you make, written out. Invent new ones by mixing syllables "
            "like " + ", ".join(random.sample(SYLLABLES, 6)) + ".")


DRIVE_CM = 40          # longest single drive while things are experimental
HFOV = 90.0            # ultrawide's horizontal field of view in portrait (measured ~90, degrees)
CENTER_TOL = 0.08      # target counts as centred within this fraction of the frame

# Heard any of these? Stop right away, whatever the model thinks.
STOP_WORDS = re.compile(r"\b(stop|halt|freeze|quiet|shush|sleep|power down|go to sleep)\b", re.I)

_ART = r"(?:the |my |a |an |our |your )?"
_TARGET = r"(?P<t>[a-z][a-z ]*?)"
_END = r"(?:\s+(?:please|now|for me|buddy))*\s*$"

# Common spoken commands, matched directly (reliable and instant). Anything else
# is left to the model to interpret. Each gives (mission, target).
COMMANDS = [
    (re.compile(r"\b(dance|boogie|bust a move)\b", re.I), ("gesture", "dance")),
    (re.compile(r"\b(spin|twirl)\b", re.I), ("gesture", "happy_spin")),
    (re.compile(r"\b(shake your head|say no)\b", re.I), ("gesture", "shake")),
    (re.compile(r"\b(nod|say yes)\b", re.I), ("gesture", "nod")),
    (re.compile(r"\b(wiggle|shimmy)\b", re.I), ("gesture", "wiggle")),
    (re.compile(r"\bfollow (me|us)\b", re.I), ("follow_target", "person")),
    (re.compile(r"\b(come here|come to me|come over|over here)\b", re.I), ("approach_target", "person")),
    (re.compile(r"\blook around\b", re.I), ("look_around", None)),
    (re.compile(r"\b(go )?explor(e|ing)\b", re.I), ("explore", None)),
    (re.compile(r"\b(say (hi|hello)|greet)\b", re.I), ("greet_human", "person")),
    (re.compile(rf"\bfollow {_ART}{_TARGET}{_END}", re.I), ("follow_target", None)),
    (re.compile(rf"\b(?:go to|drive to|roll to|go over to|go see) {_ART}{_TARGET}{_END}", re.I),
     ("approach_target", None)),
    (re.compile(rf"\b(?:find|look for|search for|where's|where is|where are|show me|go find) "
                rf"{_ART}{_TARGET}{_END}", re.I), ("find_target", None)),
]


def command_in(text: str) -> tuple[str, str | None] | None:
    """('mission', 'target') for a recognised command, ('stop', None), or None."""
    if STOP_WORDS.search(text):
        return "stop", None
    if re.search(r"\b(and|then|after that)\b", text, re.I):
        return None          # several steps: let the model turn it into a custom mission
    for pattern, (mission, target) in COMMANDS:
        m = pattern.search(text)
        if m:
            if target is None and "t" in m.groupdict():
                target = m.group("t").strip()
                if target in ("me", "us"):
                    target = "person"
            return mission, target
    return None


class Interrupted(Exception):
    """Someone called the droid by name mid-mission; the main loop handles it."""


class Droid:
    """Senses, body and voice, shared by all missions."""

    def __init__(self, name: str, bridge: DroidBridge, run_dir: Path,
                 movement: str = "auto", camera: str = "ultrawide", voice: str = "both",
                 listening: bool = True):
        self.name = name
        self.bridge = bridge
        self.tools = DroidTools(bridge, run_dir, movement=movement, image_width=640)
        self.camera = {"ultrawide": 2, "main": 0}[camera]
        self.voice = voice      # "beeps", "both" (beeps then English), or "english"
        self.persona = PERSONA.format(name=name)
        self.last: Perception | None = None
        self.torch_on = False   # the flashlight mode itself is a setting in the phone app
        self.goal = ""          # what we're trying to do right now, keeps chatter on topic
        self.memory: dict[str, float] = {}   # thing -> gyro yaw where we last faced it
        # Conversation
        self.listening = listening           # use the phone's microphone at all
        self.dialogue: list[str] = []        # recent lines, "Human: ..." / "HDO: ..."
        self.requested: str | None = None    # mission (or "stop") a human asked for
        self.requested_target: str | None = None
        self.speaking_until = 0.0            # don't listen while our own audio plays
        self._in_conversation = False
        self.calls: queue.Queue[str] = queue.Queue()   # "HDO, ..." heard in the background
        if listening:
            self._start_wake_word()
        self.personality = True              # random head glances while active
        threading.Thread(target=self._fidget, daemon=True).start()

    # --- personality

    def post(self, who: str, text: str) -> None:
        """Show a line in the browser control panel's log (best effort)."""
        try:
            self.bridge.log(who, text)
        except Exception:
            pass

    def manual(self) -> bool:
        """Is someone driving from the control panel right now?"""
        try:
            return bool(self.bridge.agent_status().get("manual"))
        except Exception:
            return False

    def gesture(self, name: str) -> str:
        """Perform a canned move (best effort: never breaks a mission)."""
        try:
            r = self.bridge.gesture(name)
            print(f"   [move: {name} -> {r.get('result')}]")
            return f"did a {name.replace('_', ' ')}"
        except Unavailable as e:
            if "called" in str(e) or "override" in str(e):
                raise Interrupted() from None
            return f"couldn't do a {name}: {e}"
        except Exception as e:
            return f"couldn't do a {name}: {e}"

    def _fidget(self) -> None:
        """Every so often glance around with the head, like a curious droid. Head
        moves don't disturb the camera (it's on the body), so this is safe even
        mid-mission; skipped while talking or listening."""
        while True:
            time.sleep(random.uniform(8, 20))
            if not self.personality or self._in_conversation or time.time() < self.speaking_until + 2:
                continue
            try:
                if self.bridge.droid_status().get("ready"):
                    self.bridge.gesture(random.choice(["glance"] * 4 + ["look_around", "double_take"]))
            except Exception:
                pass

    # --- wake word: "HDO, follow me" works any time

    def _start_wake_word(self) -> None:
        try:
            self.bridge.wake(True, [self.name])
        except Exception as e:
            print(f"   [wake word unavailable: {e}]")
            return
        threading.Thread(target=self._watch_calls, daemon=True).start()

    def _watch_calls(self) -> None:
        since = self.bridge.voice_events(0)["next"]       # ignore anything from before we started
        while True:
            try:
                ev = self.bridge.voice_events(since)
                since = ev["next"]
                for item in ev["items"]:
                    print(f"\n   [heard my name: {item['text']!r}]")
                    self.calls.put(item["command"])
            except Exception:
                pass
            time.sleep(0.4)

    def stop_listening(self) -> None:
        try:
            self.bridge.wake(False)
        except Exception:
            pass

    def check_calls(self) -> None:
        """Called between mission steps: stop what we're doing if someone called us."""
        if not self.calls.empty() and not self._in_conversation:
            raise Interrupted()

    def answer_call(self, command: str) -> None:
        """Respond to "HDO" (just the name) or "HDO, <something>"."""
        if command:
            self.dialogue = (self.dialogue + [f"Human: {self.name}, {command}"])[-10:]
            self.converse(command)
        else:
            # The phone already answered ("What's up?") the instant it heard us;
            # now listen for what they want.
            heard = self.listen(7)
            if heard:
                self.converse(heard)

    def request(self, mission: str, target: str | None) -> None:
        self.requested, self.requested_target = mission, target
        print(f"   [human asked for: {mission}{f' ({target})' if target else ''}]")

    # --- senses

    def snap(self):
        """Take a photo (and notice if the phone switched its headlamp)."""
        self.check_calls()
        snap = self.bridge.snapshot(camera=self.camera, width=640)
        self.tools.snap_count += 1
        (self.tools.run_dir / f"{self.tools.snap_count:03d}.jpg").write_bytes(snap.jpeg)
        if snap.torch != self.torch_on:      # the phone switched its light (auto mode)
            self.torch_on = snap.torch
            self.chirp("alert" if snap.torch else "happy")
            print(f"   [headlamp {'on' if snap.torch else 'off'}]")
        return snap

    def look(self) -> Perception:
        """Photo + general description (people, pets, floor, interesting things)."""
        t = time.time()
        snap = self.snap()
        p = Perception(**perceive(snap.jpeg), heading=snap.azimuth, seconds=time.time() - t)
        heading = f"{p.heading:.0f}" if p.heading is not None else "?"
        print(f"   [look @ {heading} deg, {p.seconds:.1f}s] {p.summary()}")
        self.last = p
        return p

    def find(self, target: str) -> Box | None:
        """Photo + precise location of [target] in it (None if not visible)."""
        t = time.time()
        snap = self.snap()
        self.show(None, "", f"looking for the {target}...", "yellow")
        box = locate(snap.jpeg, target)
        print(f"   [find {target}: {box or 'not in view'}, {time.time() - t:.1f}s]")
        if box:
            self.show(box, target, f"found the {target} (model)", "yellow")
        else:
            self.show(None, "", f"no {target} in view", "red")
        return box

    def check(self, question: str) -> str:
        """Look and answer a question about the scene ("is the dog asleep?")."""
        jpeg = self.snap().jpeg
        r = llm.chat([{"role": "user", "content": [llm.image_part(jpeg), {"type": "text", "text": (
            f"This photo is from a small robot's camera. Question: {question} "
            "Answer in one short sentence, based only on what is visible.")}]}],
            schema={"type": "object", "properties": {"answer": {"type": "string"}},
                    "required": ["answer"], "additionalProperties": False},
            max_tokens=60, temperature=0.1)
        print(f"   [check: {question} -> {r['answer']}]")
        return r["answer"]

    def remember(self, thing: str) -> None:
        """Note which way we're facing now, as where [thing] is."""
        try:
            yaw = self.bridge.yaw()
        except Exception:
            yaw = None
        if yaw is not None:
            self.memory[thing] = yaw
            print(f"   [memory: {thing} is at yaw {yaw:.0f}]")

    def face(self, thing: str) -> str:
        """Turn back toward where we last saw [thing]."""
        key = next((k for k in self.memory if k in thing or thing in k), None)
        if key is None:
            return f"I don't remember where the {thing} is"
        try:
            now = self.bridge.yaw()
        except Exception:
            now = None
        if now is None:
            return "no gyro heading available"
        delta = (self.memory[key] - now + 540) % 360 - 180
        if abs(delta) > 3:
            self.turn(delta)
        return f"turned back toward the {key} (where I saw it earlier)"

    def show(self, box, label: str, status: str, color: str = "green") -> None:
        """Draw what we're doing on the phone's live stream (best effort, never blocks for long)."""
        try:
            self.bridge.overlay(box, label, status, color)
        except Exception:
            pass

    # --- body

    def turn(self, degrees: float, quick: bool = False) -> None:
        """Turn in place. quick=True skips the fine correction (fine for scanning)."""
        self.check_calls()
        if self.motors_connected():
            try:
                r = self.bridge.turn(degrees, tolerance=12 if quick else 5, attempts=1 if quick else 5)
            except Unavailable as e:
                if "called" in str(e) or "override" in str(e):
                    raise Interrupted() from None
                raise
            print(f"   [turn {degrees:+.0f}{' quick' if quick else ''}] turned {r['turned']:+.0f} "
                  f"({r['result']})")
        else:
            print(f"   [turn {degrees:+.0f}] " + self.tools.run("turn", {"degrees": degrees})[0]["text"])

    def velocity(self, left: float, right: float, ttl_ms: int = 400) -> dict:
        """Stream wheel speeds; someone calling our name interrupts the mission."""
        try:
            return self.bridge.velocity(left, right, ttl_ms)
        except Unavailable as e:
            if "called" in str(e) or "override" in str(e):
                raise Interrupted() from None
            raise

    def motors_connected(self) -> bool:
        if self.tools.movement not in ("auto", "motors"):
            return False
        try:
            return bool(self.bridge.control_status().get("connected"))
        except Exception:
            return False

    def frame(self):
        """Photo as both JPEG (for the model) and an image array (for the tracker)."""
        jpeg = self.snap().jpeg
        return jpeg, decode(jpeg)

    def drive(self, cm: float, p: Perception) -> bool:
        """Drive forward, but only if the latest look says the way is clear."""
        self.check_calls()
        if cm > 0 and not p.floor_ahead_clear:
            print("   [drive refused: floor ahead not clear]")
            return False
        cm = max(-DRIVE_CM, min(DRIVE_CM, cm))
        print(f"   [drive {cm:+.0f}cm] " + self.tools.run("drive", {"distance_cm": cm})[0]["text"])
        return True

    def has_motors(self) -> bool:
        if self.tools.movement == "sim":
            return True
        try:
            return bool(self.bridge.control_status().get("connected"))
        except Exception:
            return False

    def wiggle(self) -> None:
        """A little happy dance (only with real or simulated motors)."""
        if self.has_motors():
            for d in (15, -30, 15):
                self.turn(d)

    # --- voice

    def chirp(self, mood: str) -> float:
        """Play a chirp on the phone; returns its length in seconds."""
        try:
            return self.bridge.chirp(mood).get("ms", 0) / 1000
        except Exception as e:
            print(f"   [chirp failed: {e}]")
            return 0.0

    def utter(self, beeps: str, translation: str, mood: str, speak: bool = True,
              listen: bool = True) -> None:
        """Make a sound. With speak=True the English is said out loud (for talking
        to people and real news); otherwise it's beeps only, English on screen.
        If a spoken line was a question, listen for the answer."""
        # Small models sometimes leave markdown debris (``` or *) and emoji in the
        # text; the voice would read those out loud.
        clean = lambda s: " ".join(re.sub(r"[`*]|[^\x00-⿿]", "", s).split())
        beeps, translation = clean(beeps), clean(translation)
        speak = speak and self.voice in ("both", "english")
        if speak and self.voice == "english":
            print(f"\n{self.name}: \"{translation}\"\n")
        else:
            print(f"\n{self.name}: *{beeps}*  [{translation}]{'' if speak else '  (beeped)'}\n")
        if speak:
            self.dialogue = (self.dialogue + [f"{self.name}: {translation}"])[-10:]
        self.post(self.name, translation if speak else f"*{beeps}*  ({translation})")
        seconds = 0.0
        if self.voice != "english" or not speak:
            seconds += self.chirp(mood)   # the phone queues audio, so speech plays after the chirp
        if speak:
            try:
                seconds += self.bridge.speak(translation).get("ms", 0) / 1000
            except Unavailable as e:
                print(f"   [speak failed: {e}]")
        self.speaking_until = time.time() + seconds + 0.3
        if listen and speak and translation.rstrip().endswith("?") and not self._in_conversation:
            heard = self.listen(7)
            if heard:
                self.converse(heard)

    def say(self, situation: str, speak: bool = False, mood: str = "curious") -> None:
        """React in character. Spoken lines are written by the model, kept on topic
        by the current goal. Unspoken reactions are just a chirp: no model call,
        no delay (thinking out loud is noise anyway)."""
        if not speak:
            print(f"   [{mood} chirp: {situation}]")
            self.chirp(mood)
            return
        goal = f"Your current goal: {self.goal}.\n" if self.goal else ""
        r = llm.chat([
            {"role": "system", "content": self.persona},
            {"role": "user", "content": (
                f"{goal}Situation: {situation}\n\nReact in character with one short line about "
                "this situation only; don't bring up unrelated things. "
                f"beeps: {beeps_hint()} translation: what it means in English, at most "
                "12 words. mood: how you feel. " + REACTION_HINT)},
        ], schema=SAY_SCHEMA, max_tokens=120, temperature=0.8)
        self.reply(r, speak=speak)

    def reply(self, r: dict, speak: bool = True) -> None:
        """Say a model-written line, with the body language it picked (if any)."""
        move = r.get("move", "none")
        if move != "none" and move == getattr(self, "_last_move", None):
            move = "glance"                 # small models overuse a favourite; keep it varied
        self._last_move = move
        if move and move != "none":
            try:
                self.gesture(move)          # head moves run alongside the speech
            except Interrupted:
                pass                        # someone called us mid-move; the main loop handles it
        self.utter(r["beeps"], r["translation"], r["mood"], speak=speak)

    # --- ears

    def listen(self, seconds: float, cue: bool = True) -> str:
        """Wait until we've finished talking, then listen for one utterance."""
        if not self.listening:
            return ""
        time.sleep(max(0.0, self.speaking_until - time.time()))
        try:
            r = self.bridge.listen(seconds, cue=cue)
        except Exception as e:
            print(f"   [listen failed: {e}]")
            return ""
        if r.get("error"):
            print(f"   [listen: {r['error']}]")
            if "language pack" in r["error"]:
                print("   [download the offline English speech pack on the phone to talk to me]")
                self.listening = False        # don't keep chirping at people for nothing
        text = r.get("text", "").strip()
        print(f"   [heard: {text!r}]" if text else "   [heard nothing]")
        if text:
            self.post("You", f"(said) {text}")
        if text:
            self.dialogue = (self.dialogue + [f"Human: {text}"])[-10:]
        return text

    def converse(self, heard: str, max_rounds: int = 3) -> None:
        """Answer a human in character. If they asked for something on the mission
        menu (or to stop), remember it in self.requested for the main loop."""
        cmd = command_in(heard)
        if cmd:
            self.request(*cmd)
        self._in_conversation = True
        try:
            for _ in range(max_rounds):
                options = ["none", "stop"] + list(MISSIONS)
                menu = "\n".join(f"- {name}: {MISSIONS[name][1]}" for name in MISSIONS)
                scene = self.last.summary() if self.last else "(nothing yet)"
                r = llm.chat([
                    {"role": "system", "content": self.persona},
                    {"role": "user", "content": (
                        f"WHAT YOU SEE: {scene}\n\nCONVERSATION SO FAR:\n" + "\n".join(self.dialogue) +
                        f"\n\nThe human just said: \"{heard}\"\n\n"
                        f"THINGS YOU CAN DO:\n{menu}\n\n"
                        "Reply in character with one short line. If the human asked you to do one "
                        "of the things above, set action to it and target to what it's about (e.g. "
                        "'dog', 'person', 'red ball'); if they asked you to stop, be quiet or go to "
                        "sleep, set action to stop; otherwise set action to none and target to ''. "
                        "You may ask a short follow-up question. "
                        f"beeps: {beeps_hint()} translation: what you say in English, at most 20 "
                        "words. mood: how you feel. " + REACTION_HINT)},
                ], schema={**SAY_SCHEMA,
                           "properties": {**SAY_SCHEMA["properties"],
                                          "action": {"type": "string", "enum": options},
                                          "target": {"type": "string"}},
                           "required": SAY_SCHEMA["required"] + ["action", "target"]},
                    max_tokens=180, temperature=0.7)
                if not self.requested and r["action"] != "none":
                    self.request(r["action"], r["target"].strip() or None)
                if r["action"] != "none":
                    r["move"] = "none"              # the requested mission will do the moving
                self.reply(r)
                if self.requested or not r["translation"].rstrip().endswith("?"):
                    return
                heard = self.listen(7)
                if not heard:
                    return
                cmd = command_in(heard)
                if cmd:
                    self.request(*cmd)
        finally:
            self._in_conversation = False


# ------------------------------------------------------------------ targeting
# The model is only asked "where is it?" when we need to (re)acquire the target.
# In between, code does the work: gyro turns to aim, and a CV tracker steering
# the wheels continuously while driving.

TRACK_POWER = 0.55       # forward power while tracking
STEER_GAIN = 0.8         # wheel speed difference per unit of off-centre (-0.5..0.5)
WHEEL_MIN = 0.45         # below this a wheel just strains (the phone adds weight)


def _track_width(box: Box) -> int:
    """Small (distant) targets need more pixels to track reliably."""
    return 320 if box.w >= 0.25 else 480 if box.w >= 0.12 else 640


def _wheel(v: float) -> float:
    """A wheel either stops or gets enough power to actually turn."""
    if abs(v) < 0.15:
        return 0.0
    if abs(v) < WHEEL_MIN:
        return WHEEL_MIN if v > 0 else -WHEEL_MIN
    return max(-1.0, min(1.0, v))
MODEL_CHECK_S = 3.0      # the model confirms the target this often (fixes tracker drift)
MAX_STEER = 0.2          # gentle steering while driving: keeps the view steady for the tracker
SPIN_OFFSET = 0.25       # further off-centre than this: stop and turn toward it in pulses
SPIN_PULSE_MS = 150


def _search(d: Droid, target: str, step: float = 60, max_turns: int = 6) -> Box | None:
    """Turn in place until [target] is in view, or a full circle is done.
    Scanning turns don't need to be precise, so they're quick ones."""
    for i in range(max_turns + 1):
        box = d.find(target)
        if box:
            return box
        if i < max_turns:
            d.turn(step, quick=True)
    return None


def _aim(d: Droid, target: str, box: Box) -> Box | None:
    """Face the target: one precise gyro turn computed from where it is in the
    frame, then one look to confirm (and a single touch-up if needed)."""
    for _ in range(2):
        offset = box.cx - 0.5                 # -0.5 (far left) .. +0.5 (far right)
        if abs(offset) <= CENTER_TOL:
            return box
        d.turn(offset * HFOV)
        box = d.find(target)
        if box is None:
            return None
    return box


def _drive_tracking(d: Droid, target: str, box: Box, jpeg: bytes, frame, follow: bool,
                    max_s: float) -> str:
    """Tracking mode: steer toward the target by how far off-centre it is, and
    drive forward until close. If it's lost, stop and scan to reacquire it.
    The phone stops the motors itself if our updates stop (dead-man switch),
    and on a bump or tilt."""
    t_end = time.time() + max_s
    reached = False
    try:
        while time.time() < t_end:
            result = _track(d, target, box, jpeg, follow, t_end, reached)
            if result == "reached":
                return f"reached the {target}"
            if result.startswith("arrived"):
                reached = True
            if result in ("lost", "arrived, then lost"):
                d.bridge.stop()
                d.show(None, "", f"lost the {target}, scanning...", "red")
                print(f"   [track: lost the {target}, scanning to reacquire]")
                box = _search(d, target, step=45, max_turns=7)
                if box is None:
                    return (f"followed the {target} until it left" if reached
                            else f"lost the {target}")
                jpeg = d.snap().jpeg
                continue
            return result                       # bump/tilt/timeout
        return f"{'followed' if follow else 'got closer to'} the {target}"
    finally:
        d.bridge.stop()
        d.show(None, "", "", "clear")


def _track(d: Droid, target: str, box: Box, jpeg: bytes, follow: bool, t_end: float,
           reached: bool) -> str:
    """One tracking run, until the target is reached, lost, or time is up."""
    width = _track_width(box)
    tracker = Tracker(decode(jpeg, width), box)
    last_check = time.time()
    last_print = 0.0
    while time.time() < t_end:
        d.check_calls()
        jpeg = d.snap().jpeg
        b = tracker.update(decode(jpeg, width))
        if b is None:
            return "arrived, then lost" if reached else "lost"
        if time.time() - last_check > MODEL_CHECK_S:
            # Every few seconds the model confirms the target (and fixes tracker drift)
            d.bridge.velocity(0, 0, 300)
            m = locate(jpeg, target)
            last_check = time.time()
            if m is None:
                return "arrived, then lost" if reached else "lost"
            b = m
            width = _track_width(m)
            tracker = Tracker(decode(jpeg, width), m)
        offset = b.cx - 0.5                     # -0.5 (far left) .. +0.5 (far right)
        if b.near(target):
            if not follow:
                return "reached"
            if not reached:
                reached = True
                d.chirp("happy")
            fwd = 0.0                           # close enough: wait for it to move
        else:
            fwd = TRACK_POWER
        if abs(offset) > SPIN_OFFSET:
            # Way off to one side: stop and turn toward it in short pulses, so the
            # view doesn't jump further than the tracker can follow.
            spin = WHEEL_MIN if offset > 0 else -WHEEL_MIN
            left, right = spin, -spin
            r = d.velocity(left, right, ttl_ms=SPIN_PULSE_MS)
        else:
            # Roughly ahead: drive and steer gently in proportion to the offset.
            turn = max(-MAX_STEER, min(MAX_STEER, STEER_GAIN * offset))
            left, right = _wheel(fwd + turn), _wheel(fwd - turn)
            r = d.velocity(left, right, ttl_ms=400)
        if r.get("ended") in ("bump", "tilt"):
            return f"stopped on a {r['ended']} on the way to the {target}"
        d.show(b, target, f"{'following' if follow else 'going to'} {target}  off-centre "
                          f"{offset:+.2f}  wheels {left:+.2f}/{right:+.2f}"
                          f"{'  (close)' if b.near(target) else ''}", "green")
        if time.time() - last_print > 1.0:
            last_print = time.time()
            print(f"   [track {target}: {b}  wheels {left:+.2f}/{right:+.2f}]")
    return f"{'followed' if follow else 'got closer to'} the {target}"


def find_target(d: Droid, target: str, announce: bool = True) -> str:
    """Look around for the target and turn to face it."""
    if announce:
        d.goal = f"find the {target}"
    box = _search(d, target)
    if box is None:
        d.gesture("shake")
        if announce:
            d.say(f"You looked all the way around but couldn't find the {target}.", speak=True)
        return f"couldn't find the {target} anywhere around"
    d.chirp("excited")
    _aim(d, target, box)
    d.remember(target)
    d.gesture("double_take")
    if announce:
        d.say(f"You found the {target} and you're now facing it.", speak=True)
    return f"found the {target} and turned to face it"


def approach_target(d: Droid, target: str, follow: bool = False, max_s: float = 20,
                    announce: bool = True) -> str:
    """Find the target, face it, then drive to it (and keep following) with tracking."""
    if announce:
        d.goal = f"{'follow' if follow else 'go to'} the {target}"
    box = _search(d, target)
    if box is None:
        if announce:
            d.say(f"You looked all around but couldn't find the {target}.", speak=True)
        return f"couldn't find the {target} anywhere around"
    box = _aim(d, target, box) or box
    d.remember(target)
    jpeg, frame = d.frame()
    found, path_clear = locate_with_path(jpeg, target)
    box = found or box
    if not follow and not box.near(target) and not path_clear:
        if announce:
            d.say(f"Something is blocking the way to the {target}.", speak=True)
        return f"path to the {target} was blocked"
    if not d.motors_connected():
        return f"facing the {target} (no motors to drive closer)"
    result = _drive_tracking(d, target, box, jpeg, frame, follow, max_s)
    if result.startswith("reached"):
        d.remember(target)
        d.gesture("wiggle")
    elif result.startswith("lost"):
        d.gesture("sad")
        if announce:
            d.say(f"You rolled right up to the {target}.", speak=True)
    return result


# ------------------------------------------------------------------ missions
# Each mission takes the droid and a target (or None) and returns a one-line
# summary of what happened, for the droid's memory.

def find_mission(d: Droid, target: str | None) -> str:
    return find_target(d, target or "person")


def approach_mission(d: Droid, target: str | None) -> str:
    return approach_target(d, target or "person")


def follow_mission(d: Droid, target: str | None) -> str:
    return approach_target(d, target or "person", follow=True, max_s=60)


def greet_human(d: Droid, target: str | None) -> str:
    result = approach_target(d, target or "person")
    if not result.startswith("reached"):
        return result + "; couldn't greet anyone"
    d.gesture("dance")
    d.say("You rolled up to a human. Greet them warmly!", speak=True)
    return "greeted a human"


def check_on_pets(d: Droid, target: str | None) -> str:
    return find_target(d, target or "dog or cat")


def explore(d: Droid, target: str | None) -> str:
    """Wander around: drive where it's clear, turn where it isn't."""
    d.goal = "explore the room"
    seen: list[str] = []
    for _ in range(6):
        p = d.look()
        if p.person:
            return "while exploring, found a human"
        if p.pet:
            return f"while exploring, found a {p.pet_kind or 'pet'}"
        if p.interesting and p.interesting not in seen:
            seen.append(p.interesting)
        if not d.drive(DRIVE_CM, p):
            d.turn(random.choice([60, 90, -60, -90]))
    if seen:
        d.say("While exploring you saw: " + ", ".join(seen[-3:]) + ".")
    return "explored and saw: " + ", ".join(seen[-4:]) if seen else "explored but found nothing new"


def look_around(d: Droid, target: str | None) -> str:
    """Stay put, look around in a full circle, and comment on the room."""
    d.goal = "look around the room"
    things = []
    for i in range(4):
        p = d.look()
        if p.interesting:
            things.append(p.interesting)
        if i < 3:
            d.turn(90)
    summary = ", ".join(things) or "nothing special"
    d.say(f"You looked around the room and saw: {summary}.", speak=True)
    return "looked around and saw: " + summary


def custom_mission(d: Droid, target: str | None) -> str:
    from agent.planner import run_goal
    return run_goal(d, target)


def gesture_mission(d: Droid, target: str | None) -> str:
    return d.gesture(target or "dance")


# name -> (function, description for the model)
MISSIONS = {
    "gesture": (gesture_mission, "Do a fun move. target: dance, happy_spin, wiggle, nod, shake, "
                "look_around, double_take, sad or surprised."),
    "custom_mission": (custom_mission, "Anything else, especially requests with several steps "
                       "(e.g. 'check if the dog is asleep and come tell me'). Put the whole "
                       "request in target."),
    "find_target": (find_mission, "Find something (a dog, a person, a ball...) and turn to face it."),
    "approach_target": (approach_mission, "Find something and roll right up to it."),
    "follow_target": (follow_mission, "Find something (usually a person) and keep following it."),
    "greet_human": (greet_human, "Go to a human and greet them with a happy dance."),
    "check_on_pets": (check_on_pets, "Look for the dog or cat and face it."),
    "explore": (explore, "Roll around the room to discover new things."),
    "look_around": (look_around, "Stay put, look all around, and describe the room."),
}
