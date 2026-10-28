#!/bin/bash
# Shared helpers for scripts that talk to the TV over adb. Source it: . scripts/lib-device.sh
#
# Device choice: $ADB_SERIAL if set; otherwise the connected device. Wireless debugging changes its
# port after every reboot, but adb also discovers the device over mDNS under a stable name
# (adb-<serial>-<id>._adb-tls-connect._tcp), so that entry is preferred when present.

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
[ -n "$ADB" ] || { echo "adb not found: install platform-tools (see docs/BUILDING.md)" >&2; exit 1; }

pick_device() {
    if [ -n "${ADB_SERIAL:-}" ]; then echo "$ADB_SERIAL"; return; fi
    local devices
    devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
    local mdns
    mdns=$(echo "$devices" | grep '_adb-tls-connect\._tcp$' | head -1 || true)
    if [ -n "$mdns" ]; then echo "$mdns"; return; fi
    echo "$devices" | head -1
}

require_device() {
    SERIAL=$(pick_device)
    if [ -z "$SERIAL" ]; then
        echo "No device connected. On the TV enable Developer options → Wireless debugging, then:" >&2
        echo "  $ADB pair <TV-IP>:<pairing-port> <code>   (once)" >&2
        echo "  $ADB connect <TV-IP>:<port shown on the TV>" >&2
        echo "Or set ADB_SERIAL=<serial> to choose a device. See docs/INSTALL.md." >&2
        exit 1
    fi
}
