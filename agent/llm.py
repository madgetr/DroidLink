"""Minimal client for llama-server's OpenAI-compatible chat endpoint."""

import base64
import json
import os
import urllib.request

LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8080")


def image_part(jpeg: bytes) -> dict:
    return {"type": "image_url",
            "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}}


def chat(messages: list[dict], schema: dict | None = None, max_tokens: int = 300,
         temperature: float = 0.3):
    """Send a chat request. With a JSON schema, the reply is forced to match it
    (grammar-constrained sampling) and returned parsed; otherwise returns text."""
    for attempt in range(2):
        body = {"messages": messages, "max_tokens": max_tokens, "temperature": temperature}
        if schema:
            body["response_format"] = {"type": "json_schema",
                                       "json_schema": {"name": "reply", "schema": schema}}
        req = urllib.request.Request(LLM_URL + "/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=300) as r:
            text = json.load(r)["choices"][0]["message"]["content"]
        if not schema:
            return text
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            # Usually the reply ran out of tokens mid-JSON. Retry once with more room.
            max_tokens, temperature = max_tokens * 2, 0.0
    raise ValueError(f"model reply wasn't valid JSON: {text[:200]}")


def health() -> str:
    with urllib.request.urlopen(LLM_URL + "/health", timeout=5) as r:
        return json.load(r).get("status", "?")
