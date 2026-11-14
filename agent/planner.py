"""Open-ended missions: the model plans and sequences skills, code does the driving.

A small local model is bad at low-level control (steering, timing, distances)
but decent at "what should I do next?" when the choices are simple, named
skills with plain-language results. So:

  1. the model writes a short plan for the goal,
  2. each step it picks ONE skill + argument (JSON-constrained, can't be invalid),
  3. code runs the skill and reports back in plain words,
  4. repeat until it calls "done" or runs out of steps.

Skills are the reliable building blocks from missions.py: find, go_to, follow,
face (from memory), turn, drive, look, check, say, ask, wait.
"""

import random
import re
import time

from agent import llm
from agent.missions import Droid, Interrupted, approach_target, find_target, beeps_hint

# name -> (what it does, what the argument is)
SKILLS = {
    "look": ("Take a photo and describe what is straight in front of you.", "nothing"),
    "scan": ("Look all the way around (4 directions) and describe what's in each one. Use this "
             "for 'look around', counting things, or when you don't know where something is.",
             "nothing"),
    "check": ("Look and answer a question about what you see, e.g. 'is the dog asleep?'.",
              "the question"),
    "find": ("Turn around until you see something, then face it.", "the thing, e.g. 'dog'"),
    "go_to": ("Find something and drive right up to it.", "the thing, e.g. 'couch'"),
    "follow": ("Follow something (usually a person) for about 30 seconds.", "the thing"),
    "face": ("Turn toward something on your radar (where you saw it before). Quicker than "
             "find, if the memory is recent.", "the thing"),
    "turn": ("Turn in place. Positive = right, negative = left.", "degrees, e.g. '90'"),
    "drive": ("Drive straight forward, if the way is clear. Stops by itself if you get stuck.",
              "centimeters, e.g. '40'"),
    "back_up": ("Reverse straight back, e.g. when stuck or too close to something.",
                "centimeters, e.g. '20'"),
    "say": ("Say something out loud to the humans. Only say things you've actually seen or "
            "confirmed with your other skills.", "what to say"),
    "ask": ("Ask the humans a question out loud and hear their answer.", "the question"),
    "wait": ("Wait a few seconds.", "seconds, e.g. '5'"),
    "gesture": ("Do a fun move to show how you feel.",
                "one of: dance, happy_spin, wiggle, nod, shake, look_around, sad, surprised"),
    "done": ("The mission is finished, or impossible. The argument is what you say to the "
             "human: the actual answer or result, e.g. 'Yes, the dog is fast asleep!'",
             "your answer for the human"),
}

EXAMPLE = """\
EXAMPLE
Goal: check if the dog is asleep and come tell me
Plan: 1. find person (remember where they are) 2. find dog 3. check if it is asleep \
4. go back to the person 5. tell them 6. done
Steps:
  find "person" -> found the person and turned to face them
  find "dog" -> found the dog and turned to face it
  check "is the dog asleep?" -> yes, the dog is lying down with its eyes closed
  go_to "person" -> reached the person
  say "The dog is fast asleep!" -> said it
  done "The dog is asleep and I told my human."
"""

MAX_STEPS = 15
MOVES = {"turn", "drive", "back_up", "face", "find", "go_to", "follow", "scan", "gesture"}   # skills that change the view


class Mission:
    def __init__(self, d: Droid, goal: str):
        self.d = d
        self.goal = goal
        self.plan = ""
        self.history: list[tuple[str, str, str]] = []   # (skill, arg, result)

    # --------------------------------------------------------------- thinking

    def make_plan(self) -> str:
        r = llm.chat([
            {"role": "system", "content": self._system()},
            {"role": "user", "content": (
                f"Goal: {self.goal}\n\nWrite a short numbered plan (at most 6 steps) using only "
                "your skills. Keep it simple. Don't write the final answer yet: you'll find it "
                "out by doing the steps.")},
        ], schema={"type": "object", "properties": {"plan": {"type": "string", "maxLength": 400}},
                   "required": ["plan"], "additionalProperties": False},
            max_tokens=250, temperature=0.3)
        return " ".join(r["plan"].split())

    def next_step(self) -> dict:
        steps = "\n".join(f'  {s} "{a}" -> {r}' for s, a, r in self.history[-8:]) or "  (none yet)"
        memory = self.d.radar_summary()
        scene = self.d.last.summary() if self.d.last else "(haven't looked yet)"
        left = MAX_STEPS - len(self.history)
        prompt = (
            f"Goal: {self.goal}\nPlan: {self.plan}\n\nSteps so far:\n{steps}\n\n"
            f"What you saw last: {scene}\nYour radar (where things are, from what you've "
            f"seen; old memories are less reliable, and 'face' only works on trustworthy "
            f"ones):\n{memory}\n"
            f"Steps left: {left}\n\n"
            "Choose the ONE next skill, following your plan. Don't repeat a step that already "
            "gave you an answer. If the steps so far already answer the goal, use done now "
            "(after saying the answer if the goal asks you to tell someone). "
            "thought: one short sentence. arg: the skill's argument ('' if none).")
        return llm.chat([
            {"role": "system", "content": self._system()},
            {"role": "user", "content": prompt},
        ], schema={
            "type": "object",
            "properties": {
                "thought": {"type": "string", "maxLength": 160},
                "skill": {"type": "string", "enum": list(SKILLS)},
                "arg": {"type": "string", "maxLength": 120},
            },
            "required": ["thought", "skill", "arg"],
            "additionalProperties": False,
        }, max_tokens=200, temperature=0.2)

    def _system(self) -> str:
        skills = "\n".join(f"- {name} <{arg}>: {desc}" for name, (desc, arg) in SKILLS.items())
        return (f"{self.d.persona}\n\nYou carry out missions step by step using ONLY these "
                f"skills:\n{skills}\n\n{EXAMPLE}")

    # ---------------------------------------------------------------- running

    def run(self) -> str:
        d = self.d
        d.goal = self.goal
        print(f"\n   [mission goal] {self.goal}")
        self.plan = self.make_plan()
        print(f"   [plan] {self.plan}")
        d.chirp("happy")
        refused = 0
        for n in range(1, MAX_STEPS + 1):
            step = self.next_step()
            skill, arg = step["skill"], step["arg"].strip().strip('"')
            print(f"\n   [step {n}] {step['thought']}\n   -> {skill} \"{arg}\"")
            if skill == "done":
                if not arg or len(arg) < 4:
                    return self.conclude()
                # Say the model's own answer as-is: faster, and true to what it found
                d.utter(random.choice(["bweep!", "dwoo-dweet!", "beep-boop!"]), arg, "happy",
                        listen=False)
                return arg
            # Small models dither (asking the same question over and over). Allow a
            # step twice at most; after that, make it decide with what it knows.
            # (Only since we last moved: looking again after turning is a new look.)
            since_move = self.history
            for i in range(len(self.history) - 1, -1, -1):
                if self.history[i][0] in MOVES:
                    since_move = self.history[i + 1:]
                    break
            earlier = [r for s, a, r in since_move if s == skill and _same(a, arg)]
            if len(earlier) >= 2 and skill not in ("turn", "drive", "wait"):
                refused += 1
                if refused >= 2:
                    return self.conclude()
                result = (f"(not done: you already did this twice and got: "
                          + "; ".join(earlier[-2:]) + ". Use what you know and move on, or use done.)")
                print(f"   <- {result}")
                self.history.append((skill, arg, result))
                continue
            try:
                result = self.execute(skill, arg)
            except Interrupted:
                raise
            except Exception as e:                   # a broken step shouldn't end the mission
                result = f"that didn't work ({e})"
            print(f"   <- {result}")
            self.history.append((skill, arg, result))
        return self.conclude()

    def conclude(self) -> str:
        """Out of steps or going in circles: decide on an answer from what we have."""
        steps = "\n".join(f'  {s} "{a}" -> {r}' for s, a, r in self.history[-10:])
        r = llm.chat([
            {"role": "system", "content": self.d.persona},
            {"role": "user", "content": (
                f"Goal: {self.goal}\n\nWhat you did:\n{steps}\n\nTime to stop. In one or two short "
                "sentences, give your best answer for the goal based on what you found, or say "
                "honestly what you couldn't do.")},
        ], schema={"type": "object", "properties": {"answer": {"type": "string", "maxLength": 200}},
                   "required": ["answer"], "additionalProperties": False},
            max_tokens=120, temperature=0.2)
        print(f"   [concluding] {r['answer']}")
        self.d.utter(random.choice(["bweep!", "dwoo-dweet.", "beep-boop."]), r["answer"], "curious",
                     listen=False)
        return r["answer"]

    def execute(self, skill: str, arg: str) -> str:
        d = self.d
        if skill == "look":
            return d.look().summary()
        if skill == "scan":
            seen = []
            for i in range(4):
                p = d.look(things=True)
                seen.append(f"direction {i + 1} ({i * 90} deg right of start): {p.summary()}")
                if i < 3:
                    d.turn(90, quick=True)
            d.turn(90, quick=True)                # back to where we started
            return " | ".join(seen)
        if skill == "check":
            return d.check(arg or "what do you see?")
        if skill == "find":
            result = find_target(d, arg or "person", announce=False)
            return result
        if skill == "go_to":
            return approach_target(d, arg or "person", announce=False)
        if skill == "follow":
            return approach_target(d, arg or "person", follow=True, max_s=30, announce=False)
        if skill == "face":
            return d.face(arg)
        if skill == "turn":
            deg = _number(arg, 90)
            d.turn(max(-180, min(180, deg)))
            return f"turned {deg:+.0f} degrees"
        if skill == "drive":
            cm = _number(arg, 40)
            p = d.look()
            before = len(d.obstacles())
            if not d.drive(cm, p):
                return "didn't drive: the way ahead isn't clear"
            if len(d.obstacles()) > before:
                return (f"got stuck on something while driving (marked as an obstacle on your "
                        f"radar), backed up and went around it")
            return f"drove about {cm:.0f} cm"
        if skill == "back_up":
            return d.back_up(_number(arg, 20))
        if skill == "say":
            d.utter(random.choice(["bweep!", "dwoo-weet!", "beep-boop!"]), arg, "happy", listen=False)
            return "said it"
        if skill == "ask":
            question = arg if arg.endswith("?") else arg + "?"
            d.utter(random.choice(["bweep?", "dwoo?", "tweet?"]), question, "curious", listen=False)
            heard = d.listen(8)
            return f'the human answered: "{heard}"' if heard else "nobody answered"
        if skill == "gesture":
            name = re.sub(r"[^a-z_]", "", arg.lower().replace(" ", "_")) or "wiggle"
            return d.gesture(name)
        if skill == "wait":
            s = max(1, min(30, _number(arg, 5)))
            time.sleep(s)
            return f"waited {s:.0f} seconds"
        return f"unknown skill {skill}"


def _same(a: str, b: str) -> bool:
    """Same argument, ignoring case, punctuation and spacing."""
    norm = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
    return norm(a) == norm(b)


def _number(text: str, default: float) -> float:
    m = re.search(r"-?\d+(\.\d+)?", text or "")
    return float(m.group()) if m else default


def run_goal(d: Droid, goal: str | None) -> str:
    if not goal:
        return "no goal given"
    return Mission(d, goal).run()
