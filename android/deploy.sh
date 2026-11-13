#!/usr/bin/env bash
# Build DroidLink, install it on the phone over wireless adb, and restart it.
# Usage (Git Bash):  android/deploy.sh
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
ADB="${ADB:-/c/Users/trevo/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PHONE_IP="${PHONE_IP:-192.168.2.250}"
PKG=dev.droidlink

echo "== build"
./gradlew assembleDebug -q --console=plain

echo "== connect"
# Reuse an existing connection; otherwise find the wireless-debugging port
# (it changes after reboots) via mDNS.
DEV=$("$ADB" devices | awk -v ip="$PHONE_IP" 'index($1, ip":") && $2 == "device" {print $1; exit}')
if [ -z "$DEV" ]; then
    PORT=$("$ADB" mdns services | awk -v ip="$PHONE_IP" '/_adb-tls-connect/ && index($3, ip":") {split($3,a,":"); print a[2]; exit}')
    [ -n "$PORT" ] || { echo "Phone not found. Is Wireless debugging on?"; exit 1; }
    DEV="$PHONE_IP:$PORT"
    "$ADB" connect "$DEV" >/dev/null
fi

echo "== install on $DEV"
"$ADB" -s "$DEV" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do
    "$ADB" -s "$DEV" shell pm grant $PKG android.permission.$p || true
done

echo "== restart"
"$ADB" -s "$DEV" logcat -b crash -c
"$ADB" -s "$DEV" shell am force-stop $PKG
"$ADB" -s "$DEV" shell am start -n $PKG/.MainActivity >/dev/null
up=0
for i in $(seq 1 60); do
    if curl -sf "http://$PHONE_IP:8765/health" >/dev/null; then up=1; break; fi
    sleep 0.5
done
sleep 2   # give the screen a moment to crash, if it's going to

# The service can be healthy while the UI crashes, so check the crash log too.
CRASH=$("$ADB" -s "$DEV" logcat -d -b crash 2>/dev/null | grep -A 12 "Process: $PKG" || true)
if [ -n "$CRASH" ]; then
    echo "!! DroidLink crashed:"; echo "$CRASH" | grep -E "Exception|Caused by|at dev\.droidlink" | head -8
    exit 1
fi
[ $up = 1 ] || { echo "DroidLink did not come up"; exit 1; }
curl -s "http://$PHONE_IP:8765/health"; echo
# Android only grants background microphone access to a service started while
# the app is on screen. After a deploy to a locked phone, voice won't work until
# someone taps Stop then Start in the app.
echo "note: for voice, tap Stop/Start in DroidLink on the phone (mic needs a start from the screen)"
