#!/bin/bash
# One line of resource usage for the running app, for leak checks across connect/disconnect cycles:
# total PSS, Java heap, native heap, thread count, open file descriptors.
# Usage: scripts/soak-stats.sh [label]
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib-device.sh
require_device
PKG=dev.personal.airplayrx

PID=$("$ADB" -s "$SERIAL" shell pidof "$PKG" | tr -d '\r')
[ -z "$PID" ] && { echo "app not running"; exit 1; }
MEM=$("$ADB" -s "$SERIAL" shell dumpsys meminfo "$PKG" | tr -d '\r')
pss=$(echo "$MEM" | awk '/TOTAL PSS:/ {print $3; exit}')
java=$(echo "$MEM" | awk '/^ *Java Heap:/ {print $3; exit}')
native=$(echo "$MEM" | awk '/^ *Native Heap:/ {print $3; exit}')
threads=$("$ADB" -s "$SERIAL" shell cat /proc/"$PID"/status | awk '/^Threads:/ {print $2}' | tr -d '\r')
# run-as works for debuggable builds only; release builds report "n/a".
fds=$("$ADB" -s "$SERIAL" shell run-as "$PKG" ls /proc/"$PID"/fd 2>/dev/null | wc -l | tr -d ' ' || true)
[ "${fds:-0}" -gt 1 ] 2>/dev/null || fds="n/a"
printf '%s pid=%s pss=%sK java=%sK native=%sK threads=%s fds=%s\n' \
    "${1:-$(date +%H:%M:%S)}" "$PID" "$pss" "$java" "$native" "$threads" "$fds"
