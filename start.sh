#!/data/data/com.termux/files/usr/bin/bash
# Start everything the droid bridge needs: wake lock, sshd, and the FastAPI app.
# Safe to run repeatedly - anything already running is left alone.

BOT_DIR="$HOME/bot"
STATE_DIR="$HOME/.droid"     # outside ~/bot, because sync wipes ~/bot
PORT="${PORT:-8000}"
mkdir -p "$STATE_DIR"

# Keep Android from sleeping the CPU / killing Termux
termux-wake-lock

# SSH server (port 8022)
if ! pgrep -x sshd >/dev/null; then
    sshd && echo "sshd started"
else
    echo "sshd already running"
fi

# FastAPI app
if [ -f "$STATE_DIR/api.pid" ] && kill -0 "$(cat "$STATE_DIR/api.pid")" 2>/dev/null; then
    echo "API already running (pid $(cat "$STATE_DIR/api.pid"))"
else
    cd "$BOT_DIR" || exit 1
    nohup python -m uvicorn app.main:app --host 0.0.0.0 --port "$PORT" \
        >> "$STATE_DIR/api.log" 2>&1 &
    echo $! > "$STATE_DIR/api.pid"
    echo "API started on port $PORT (pid $!), log: $STATE_DIR/api.log"
fi
