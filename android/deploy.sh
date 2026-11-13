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
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT CAMERA POST_NOTIFICATIONS; do
    "$ADB" -s "$DEV" shell pm grant $PKG android.permission.$p || true
done

echo "== restart"
"$ADB" -s "$DEV" shell am force-stop $PKG
"$ADB" -s "$DEV" shell am start -n $PKG/.MainActivity >/dev/null
for i in $(seq 1 60); do
    if curl -sf "http://$PHONE_IP:8765/health" >/dev/null; then
        curl -s "http://$PHONE_IP:8765/health"; echo; exit 0
    fi
    sleep 0.5
done
echo "DroidLink did not come up"; exit 1
