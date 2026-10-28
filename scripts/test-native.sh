#!/bin/bash
# Runs host-side (macOS) unit tests for pure native logic, with ASan + UBSan.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CPP=app/src/main/cpp
SHIM=$CPP/shim
FLAGS=(-Wall -Wextra -Werror -fsanitize=address,undefined)

clang "${FLAGS[@]}" -I"$SHIM" \
    app/src/test/cpp/dns_sd_shim_test.c "$SHIM/dns_sd_shim.c" -o "$OUT/dns_sd_shim_test"
"$OUT/dns_sd_shim_test"

# stub/ provides a printf-based <android/log.h>.
clang++ -std=c++17 "${FLAGS[@]}" -Iapp/src/test/cpp/stub -I"$CPP" \
    app/src/test/cpp/video_stats_test.cpp "$CPP/video_stats.cpp" -o "$OUT/video_stats_test"
"$OUT/video_stats_test"

echo "✓ native host tests passed"
