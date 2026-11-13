#!/data/data/com.termux/files/usr/bin/bash
# Stop the FastAPI app (leaves sshd and the wake lock alone so you keep access).

PID_FILE="$HOME/.droid/api.pid"
if [ -f "$PID_FILE" ] && kill "$(cat "$PID_FILE")" 2>/dev/null; then
    PID="$(cat "$PID_FILE")"
    # Give it 5s to shut down cleanly, then force it
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$PID" 2>/dev/null || break
        sleep 0.5
    done
    kill -9 "$PID" 2>/dev/null && echo "API force-killed" || echo "API stopped"
else
    echo "API not running"
fi
rm -f "$PID_FILE"

# Clean up any stray uvicorn or sensor streams (only one sensor listener can be active)
# Patterns are anchored to the start of the command line so they can't match
# an unrelated shell whose arguments merely mention these names.
pkill -9 -f "^python -m uvicorn app.main:app" 2>/dev/null
pkill -9 -f "^/data/data/com.termux/files/usr/bin/bash /data/data/com.termux/files/usr/bin/termux-sensor" 2>/dev/null
pkill -9 -f "^/data/data/com.termux/files/usr/libexec/termux-api Sensor" 2>/dev/null
true
