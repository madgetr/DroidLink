"""Claude-powered droid agent: look -> think -> act, until the mission is done.

Usage (from C:\\Users\\trevo\\bot):
    venv\\Scripts\\python -m agent.claude_agent "Look around and describe the room"
"""

import argparse
import base64
import sys
import time
from pathlib import Path

import anthropic

from agent.bridge import DroidBridge
from agent.tools import TOOL_SPECS, DroidTools

MODEL = "claude-opus-5-5"

SYSTEM_PROMPT = """\
You are the brain of a small astromech droid that a parent and child built together. \
An Android phone is mounted on the droid; it gives you a camera and sensors. You act \
in a loop: look, think, then act with your tools, one step at a time.

How the body works:
- `look` takes a photo (about 4 seconds) and reports the compass heading at that moment. \
Headings are degrees clockwise from north, so you can track which direction you saw what.
- `turn` rotates in place; `drive` moves straight. Movement may be imprecise, so check \
the returned heading and look again after moving rather than assuming.
- The camera sits on the droid's body, low to the ground.

Safety comes first. Never drive toward stairs, drops, table edges, pets, or people's feet, \
and don't drive when you haven't looked recently or aren't sure the path is clear. If you \
are stuck or unsure, stop and say so.

Keep each message short: what you see, what you'll do next and why. When the mission is \
complete (or can't be completed), end with a brief summary of what you found."""


def to_claude_content(parts: list[dict]) -> list[dict]:
    """Convert provider-neutral tool parts into Claude content blocks."""
    out = []
    for p in parts:
        if p["type"] == "text":
            out.append({"type": "text", "text": p["text"]})
        elif p["type"] == "image":
            out.append({"type": "image", "source": {
                "type": "base64", "media_type": "image/jpeg",
                "data": base64.standard_b64encode(p["jpeg"]).decode()}})
    return out


def run(mission: str, max_steps: int, effort: str) -> None:
    client = anthropic.Anthropic()
    bridge = DroidBridge()
    print(f"Bridge: {bridge.base_url} -> {bridge.health()['status']}")

    run_dir = Path(__file__).parent / "runs" / time.strftime("%Y%m%d-%H%M%S")
    tools = DroidTools(bridge, run_dir)
    messages = [{"role": "user", "content": mission}]

    for step in range(1, max_steps + 1):
        response = client.beta.messages.create(
            model=MODEL,
            max_tokens=16000,
            system=SYSTEM_PROMPT,
            tools=TOOL_SPECS,
            messages=messages,
            thinking={"type": "adaptive", "display": "summarized"},
            output_config={"effort": effort},
            cache_control={"type": "ephemeral"},  # history is resent each step
            # If Opus declines on a safety classifier, retry on a fallback model
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
        # Keep the full content (incl. thinking blocks) so the history stays valid
        messages.append({"role": "assistant", "content": response.content})

        for block in response.content:
            if block.type == "thinking" and block.thinking:
                print(f"\n  (thinking) {block.thinking.strip()}")
            elif block.type == "text" and block.text.strip():
                print(f"\nDroid: {block.text.strip()}")

        u = response.usage
        print(f"   [step {step}: in={u.input_tokens} cached={u.cache_read_input_tokens} "
              f"out={u.output_tokens}]")

        if response.stop_reason == "refusal":
            print("\nThe model declined this request.")
            return
        if response.stop_reason != "tool_use":
            return  # end_turn: mission finished

        # Run every requested tool, then send all results back in one message
        results = []
        for block in response.content:
            if block.type != "tool_use":
                continue
            print(f"\n-> {block.name}({block.input})")
            try:
                content = to_claude_content(tools.run(block.name, block.input))
                results.append({"type": "tool_result", "tool_use_id": block.id, "content": content})
            except Exception as e:
                print(f"   [tool error: {e}]")
                results.append({"type": "tool_result", "tool_use_id": block.id,
                                "content": f"Error: {e}", "is_error": True})
        messages.append({"role": "user", "content": results})

    print(f"\nStopped after {max_steps} steps.")


def main():
    ap = argparse.ArgumentParser(description="Run the droid agent on a mission.")
    ap.add_argument("mission", nargs="?", default="Look around and tell me what you see.")
    ap.add_argument("--max-steps", type=int, default=15)
    ap.add_argument("--effort", default="medium", choices=["low", "medium", "high", "xhigh", "max"])
    args = ap.parse_args()
    try:
        run(args.mission, args.max_steps, args.effort)
    except anthropic.AuthenticationError:
        sys.exit("No valid Anthropic credentials. Set ANTHROPIC_API_KEY first.")
    except KeyboardInterrupt:
        print("\nAborted.")


if __name__ == "__main__":
    main()
