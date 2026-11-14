# DroidLink

Turn an Android phone into the brain of a Disney **Droid Depot R-series astromech**.

The phone rides on the droid. It talks to the droid over Bluetooth LE and supplies the camera, sensors, a voice and ears. A laptop runs a local vision model and acts as the droid's mind: it picks missions, tracks people and pets, answers when called by name, and does little dances.

```
 ┌────────────── laptop ──────────────┐        Wi-Fi         ┌──────── phone (DroidLink app) ────────┐   BLE   ┌───────┐
 │ agent.brain  (missions, planner)   │  ◄── HTTP :8765 ──►  │ camera · gyro · torch · TTS · mic     │ ◄─────► │ droid │
 │ llama-server (Qwen3-VL-4B, local)  │                      │ motion control · control panel (web)  │         └───────┘
 │ OpenCV CSRT tracker                │                      └───────────────────────────────────────┘
 └────────────────────────────────────┘
```

## What it can do

- **Drive accurately:** gyro-guided turns with easing (within about ±5°), timed drives, continuous velocity with a dead-man switch, and bump/tilt guards.
- **See:** fast JPEG snapshots and an MJPEG live stream with the tracking box drawn on it. The flashlight switches on automatically when the scene gets dark.
- **Talk:** synthesized droid chirps for moods, plus spoken English in a robotized voice. You choose the voice, pitch and speed in the app.
- **Listen:** a wake word. Say its name and it stops what it's doing and answers "What's up?". It also understands commands like *"HDO, follow me"*, *"look around"* or *"find the dog"*.
- **Personality:** random head glances, plus canned moves (`dance`, `wiggle`, `nod`, `happy_spin`, `sad`, `surprised`, `shake`, `double_take`, …) that the agent uses as body language.
- **Missions:** explore, look around, find or approach or follow a target, greet a human and check on the pets. It can also take open-ended goals such as *"check if the dog is asleep and come tell me"*, handled by a small planner over deterministic skills.
- **Control panel:** open `http://<phone-ip>:8765/` in a browser to watch the stream, take manual control (D-pad, WASD or arrow keys, Space to stop), trigger moves, chat with the agent or make the droid speak.

## Repository layout

| Path | What |
|---|---|
| `android/` | **DroidLink**: a Kotlin Android app with no external libraries. A foreground service runs a small HTTP server on port 8765 that exposes BLE, motion, camera, sensors, sound and voice. |
| `android/deploy.sh` | Builds, installs over adb (wireless debugging), grants permissions, restarts and health-checks the app. |
| `agent/` | The laptop side, in Python. |
| `agent/bridge.py` | HTTP client for the phone API. |
| `agent/brain.py` | Main loop: picks a mission, runs it and repeats. |
| `agent/missions.py` | Persona, missions, target search, tracking and steering. |
| `agent/planner.py` | Open-ended goals → steps over skills (look, find, go_to, follow, say, ask, gesture, …). |
| `agent/vision.py`, `perception.py`, `tracking.py` | Model-based detection (boxes on a 0–1000 grid), scene summaries and the OpenCV tracker. |
| `agent/llm.py`, `start-llm.ps1` | Client and launcher for a local `llama-server` with JSON-schema-constrained output. |

## Setup

### 1. The phone app

You need Android 13 or newer (minSdk 33). Build with Android Studio's JDK, or run:

```bash
cd android
./deploy.sh            # assembleDebug → adb install → grant permissions → start → health check
```

Open **DroidLink** on the phone, set the droid's name and voice, and tap **Start**. Then check `http://<phone-ip>:8765/health`.

> After a reinstall, tap **Stop** and then **Start** once on the phone's screen. Android only lets a foreground service use the microphone if it was started from the visible app.

Connecting takes the droid away from its handheld remote, so the app connects only on demand (or at start, if **auto-connect** is on). Closing `agent.brain` hands the droid back to the remote.

### 2. The local model

Install [llama.cpp](https://github.com/ggml-org/llama.cpp) and download **Qwen3-VL-4B-Instruct** (GGUF plus its mmproj file). Edit the paths in `agent/start-llm.ps1`, then run:

```powershell
agent\start-llm.ps1        # llama-server on :8080
```

### 3. The agent

```powershell
python -m venv venv
venv\Scripts\pip install -r agent\requirements.txt
$env:DROID_URL = "http://<phone-ip>:8765"
venv\Scripts\python -m agent.brain
```

Useful options:

```
--goal "find the dog and tell me if it's asleep"   # open-ended mission first
--mission follow_target --target person            # force one mission
--missions 10                                      # how many to run
--movement auto|manual|motors|sim                  # manual = you push it by hand
--voice beeps|both|english
--no-listen                                        # don't use the microphone
```

## HTTP API (phone)

All routes use GET with query parameters. A route returns `503` when it can't act right now, for example when no droid is connected.

| Area | Routes |
|---|---|
| Panel | `/` · `/agent/status` · `/agent/say` · `/agent/log` |
| Droid | `/droid/connect` · `/droid/disconnect` · `/droid/status` · `/droid/forget` |
| Motion | `/control/turn?degrees=` · `/control/drive?cm=` · `/control/velocity?left=&right=&ttl_ms=` · `/control/wheels` · `/control/stop` · `/control/gesture?name=` · `/control/head*` |
| Camera | `/camera/snapshot?camera=&width=` · `/camera/stream` · `/camera/torch?mode=on\|off\|auto` · `/camera/overlay` |
| Sensors | `/sensors` · `/sensors/orientation` · `/health` · `/settings` |
| Sound | `/sound/chirp?mood=` · `/sound/speak?text=` · `/sound/voices` |
| Voice | `/voice/listen` · `/voice/wake?enable=` · `/voice/events?since=` |
| Raw BLE | `/ble/*` |

## Notes and limits

- The droid falls asleep when nothing is connected to it, so power-cycle it if it can't be found. Android also allows only about 5 BLE scans every 30 s, which is why the app connects directly to the saved address first.
- The compass is useless next to the motor magnets, so turns use the gyro's game rotation vector.
- A 4B vision model is quick and free but its grounding isn't always reliable. Steering, tracking and turning are handled by deterministic code, and the model is only asked *what* and *where*.
- The head motor can't lift a phone, so mount the phone on the body.

Built by a parent and kid after a trip to Droid Depot. Not affiliated with Disney or Lucasfilm.
