#!/bin/bash
# Build → install → launch → follow logcat for the AirPlayRx tag.
#
# Usage: scripts/deploy.sh [debug|release] [--no-logs|--no-launch]
#   --no-launch: install only; the app restarts its receiver service by itself after the update
#   (MY_PACKAGE_REPLACED) without opening the screen, so the TV's input is not switched.
#   ADB_SERIAL=<serial> chooses the device (default: see scripts/lib-device.sh).
set -euo pipefail

cd "$(dirname "$0")/.."

. scripts/lib-device.sh
# JAVA_HOME: use the environment's; else the JDK location suggested in docs/BUILDING.md, if present.
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/Android/jdk-17/Contents/Home" ]; then
    export JAVA_HOME="$HOME/Android/jdk-17/Contents/Home"
fi
export ANDROID_HOME

VARIANT="${1:-debug}"
FOLLOW_LOGS=1
LAUNCH=1
[ "${2:-}" = "--no-logs" ] && FOLLOW_LOGS=0
[ "${2:-}" = "--no-launch" ] && { FOLLOW_LOGS=0; LAUNCH=0; }

PKG="dev.personal.airplayrx"
require_device
echo "→ Device: $SERIAL"

case "$VARIANT" in
    debug)   TASK=assembleDebug;   APK=app/build/outputs/apk/debug/app-debug.apk ;;
    release) TASK=assembleRelease; APK=app/build/outputs/apk/release/app-release.apk ;;
    *) echo "Unknown variant: $VARIANT (use debug or release)" >&2; exit 1 ;;
esac

echo "→ Building ($TASK)…"
./gradlew "$TASK" --console=plain -q

echo "→ Installing ${APK}…"
"$ADB" -s "$SERIAL" install -r "$APK"

"$ADB" -s "$SERIAL" logcat -c
if [ "$LAUNCH" = 1 ]; then
    echo "→ Launching…"
    "$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
fi

if [ "$FOLLOW_LOGS" = 1 ]; then
    echo "→ Logs (Ctrl+C to stop):"
    exec "$ADB" -s "$SERIAL" logcat -s AirPlayRx:V AndroidRuntime:E DEBUG:V
else
    sleep 3
    "$ADB" -s "$SERIAL" logcat -d -s AirPlayRx:V AndroidRuntime:E DEBUG:V
fi
