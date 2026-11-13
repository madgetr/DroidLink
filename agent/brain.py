"""The droid's main loop: look around, pick a mission from the menu, run it, repeat.

Start the model server first (agent/start-llm.ps1), then from C:\\Users\\trevo\\bot:
    venv\\Scripts\\python -m agent.brain
Options: --missions N, --mission NAME (force one), --movement auto|manual|sim,
         --voice beeps|both|english
Press Ctrl+C to stop.
"""

import argparse
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from agent import llm
from agent.bridge import DroidBridge
from agent.missions import MISSIONS, MOODS, Droid, Interrupted, beeps_hint
from agent.perception import Perception


CONNECTED_DROID = [False]   # did this run take over the droid? (release it on exit)


@dataclass
class Memory:
    mission: str
    outcome: str
    when: float


def allowed_missions(history: list[Memory], p: Perception) -> list[str]:
    """Rules the model can't override, to keep the droid from getting stuck in loops."""
    allowed = [m for m in MISSIONS if m != "custom_mission"]   # those need a goal from a human

    def drop(name):
        if name in allowed and len(allowed) > 1:
            allowed.remove(name)

    if len(history) >= 2 and history[-1].mission == history[-2].mission:
        drop(history[-1].mission)                     # no 3 in a row
    if any(h.mission == "greet_human" for h in history[-4:]):
        drop("greet_human")                           # don't pester people
    if any(h.mission == "follow_target" for h in history[-3:]):
        drop("follow_target")                         # following is for when someone asks
    if not p.person and any("couldn't find the person" in h.outcome for h in history[-2:]):
        for m in ("approach_target", "greet_human"):
            drop(m)                                   # nobody's around right now
    if not p.pet and any("couldn't find the dog or cat" in h.outcome for h in history[-3:]):
        drop("check_on_pets")
    return allowed


def pick_mission(d: Droid, p: Perception, history: list[Memory]) -> dict:
    allowed = allowed_missions(history, p)
    menu = "\n".join(f"- {name}: {MISSIONS[name][1]}" for name in allowed)
    recent = "\n".join(f"- {h.mission}: {h.outcome}" for h in history[-5:]) or "- (just woke up)"
    prompt = (
        f"WHAT YOU SEE RIGHT NOW: {p.summary()}\n\n"
        f"WHAT YOU DID RECENTLY:\n{recent}\n\n"
        f"MISSIONS YOU CAN DO NEXT:\n{menu}\n\n"
        "Pick the mission that makes the most sense now, and avoid repeating yourself. "
        "target: what the mission is about (e.g. 'person', 'dog', 'ball'), or '' if none. "
        "reason: one short sentence. "
        f"beeps: to announce it, {beeps_hint()} translation: what that means in "
        "English, at most 10 words. mood: how you feel about it."
    )
    schema = {
        "type": "object",
        "properties": {
            "mission": {"type": "string", "enum": allowed},
            "target": {"type": "string"},
            "reason": {"type": "string"},
            "beeps": {"type": "string"},
            "translation": {"type": "string"},
            "mood": {"type": "string", "enum": MOODS},
        },
        "required": ["mission", "target", "reason", "beeps", "translation", "mood"],
        "additionalProperties": False,
    }
    return llm.chat([{"role": "system", "content": d.persona},
                     {"role": "user", "content": prompt}],
                    schema=schema, max_tokens=150, temperature=0.7)


def run(args) -> None:
    bridge = DroidBridge()
    print(f"Bridge: {bridge.base_url} -> {bridge.health()['status']}")
    print(f"LLM:    {llm.LLM_URL} -> {llm.health()}")
    run_dir = Path(__file__).parent / "runs" / time.strftime("%Y%m%d-%H%M%S-brain")
    if args.movement in ("auto", "motors"):
        try:
            bridge.droid_connect()
            CONNECTED_DROID[0] = True
            print("Droid:  connected (its handheld remote is paused until this exits)")
        except Exception as e:
            if args.movement == "motors":
                raise
            print(f"Droid:  not connected ({e}); you'll be asked to move it by hand")
    name = args.name or bridge.settings().get("name") or "Droid"   # named in the phone app
    print(f"Name:   {name}")
    d = Droid(name, bridge, run_dir, movement=args.movement, voice=args.voice,
              listening=not args.no_listen)
    print(f"Movement: {args.movement}{' (motors connected)' if d.has_motors() else ''}  |  photos: {run_dir}")
    if d.listening:
        print(f"Call me by name any time: \"{name}, follow me\", \"{name}, look around\", "
              f"\"{name}, stop\"... I also listen after I ask a question.")

    d.chirp("excited")
    history: list[Memory] = []
    n = 0
    try:
        while n < args.missions:
            # Someone called us by name? Answer first (may request a mission or stop).
            while not d.calls.empty() and d.requested != "stop":
                d.answer_call(d.calls.get())
            if d.requested == "stop":
                d.say("Your human told you to stop. Say goodnight.", speak=True)
                print("\n[stopping: asked to]")
                break
            n += 1
            print(f"\n{'=' * 20} mission {n} {'=' * 20}")
            try:
                outcome, mission = run_one(d, args, history)
            except Interrupted:
                print("   [interrupted: someone called my name]")
                n -= 1          # doesn't count; answer them and carry on
                continue
            history.append(Memory(mission, outcome, time.time()))
    finally:
        d.stop_listening()

    print("\nMission log:")
    for h in history:
        print(f"  - {h.mission}: {h.outcome}")


def run_one(d: Droid, args, history: list[Memory]) -> tuple[str, str]:
    """Pick (or take the requested) mission and run it. Returns (outcome, mission)."""
    if d.requested:
        choice = {"mission": d.requested, "target": d.requested_target, "reason": "a human asked me to"}
        d.requested = d.requested_target = None
        d.chirp("happy")                     # "on it!"
    elif args.goal:
        choice = {"mission": "custom_mission", "target": args.goal, "reason": "given a goal"}
        args.goal = None                     # just once; then back to choosing for itself
    elif args.mission:
        choice = {"mission": args.mission, "target": args.target, "reason": "requested"}
    else:
        p = d.look()
        choice = pick_mission(d, p, history)
    target = (choice.get("target") or "").strip() or None
    print(f"\n>>> {choice['mission'].upper()}{f' [{target}]' if target else ''}: {choice['reason']}")
    if "beeps" in choice:
        # Announcing our own plan is internal monologue: beep it, don't say it.
        d.utter(choice["beeps"], choice["translation"], choice["mood"], speak=False)

    func = MISSIONS[choice["mission"]][0]
    try:
        outcome = func(d, target)
    except Interrupted:
        d.bridge.stop()     # don't leave the motors running while we chat
        raise
    except Exception as e:  # keep the droid going if one mission breaks
        outcome = f"mission failed: {e}"
        print(f"   [error] {e}")
    print(f"<<< {choice['mission']}: {outcome}")
    return outcome, choice["mission"]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="Let the droid choose and run its own missions.")
    ap.add_argument("--name", help="override the droid's name (default: the name set in the phone app)")
    ap.add_argument("--missions", type=int, default=5, help="how many missions to run")
    ap.add_argument("--mission", choices=list(MISSIONS), help="run this mission instead of choosing")
    ap.add_argument("--target", help="what the --mission is about, e.g. 'dog'")
    ap.add_argument("--goal", help="an open-ended mission to do first, e.g. "
                                    "\"check if the dog is asleep and come tell me\"")
    ap.add_argument("--movement", default="auto", choices=["auto", "manual", "motors", "sim"])
    ap.add_argument("--voice", default="both", choices=["beeps", "both", "english"],
                    help="beeps only, beeps then spoken English (default), or English only")
    ap.add_argument("--no-listen", action="store_true", help="don't use the microphone")
    args = ap.parse_args()
    try:
        run(args)
    except KeyboardInterrupt:
        print("\nStopping.")
    finally:
        # Leave the droid safe: motors stopped, flashlight off (saved mode unchanged),
        # and handed back to its handheld remote.
        b = DroidBridge()
        cleanups = [b.stop, b.light_off]
        if CONNECTED_DROID[0]:
            cleanups.append(b.droid_disconnect)
        for cleanup in cleanups:
            try:
                cleanup()
            except Exception:
                pass


if __name__ == "__main__":
    main()
