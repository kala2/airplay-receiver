#!/bin/bash
# Checks the build prerequisites and installs the exact Android SDK packages this project needs.
# Versions come from app/build.gradle.kts (ndkVersion, cmake version, compileSdk), so they live in
# one place. Safe to re-run. Used by CI too (--ci exports ANDROID_NDK_ROOT/HOME to $GITHUB_ENV).
#
# Usage: scripts/setup-sdk.sh [--ci]
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE_FILE=app/build.gradle.kts
NDK_VERSION=$(sed -n 's/^ *ndkVersion *= *"\(.*\)".*/\1/p' "$GRADLE_FILE")
CMAKE_VERSION=$(sed -n '/externalNativeBuild/,/}/s/^ *version *= *"\(.*\)".*/\1/p' "$GRADLE_FILE" | head -1)
COMPILE_SDK=$(sed -n 's/^ *compileSdk *= *\([0-9]*\).*/\1/p' "$GRADLE_FILE")
BUILD_TOOLS="${COMPILE_SDK}.0.0"
ok() { printf '  ✓ %s\n' "$*"; }
fail() { printf '  ✗ %s\n' "$*" >&2; exit 1; }

echo "Checking prerequisites…"
java_version=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
[ "$java_version" = 17 ] && ok "JDK 17" || fail "JDK 17 required (found: ${java_version:-none}). See docs/BUILDING.md."
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] && ok "Android SDK command-line tools ($ANDROID_HOME)" \
    || fail "sdkmanager not found at $SDKMANAGER — install the Android command-line tools (docs/BUILDING.md)"
for t in perl make; do command -v $t >/dev/null && ok "$t" || fail "$t is required"; done
command -v clang >/dev/null && ok "clang (for scripts/test-native.sh)" || echo "  ! clang not found: scripts/test-native.sh won't run"

echo "Installing pinned SDK packages: NDK $NDK_VERSION, CMake $CMAKE_VERSION, android-$COMPILE_SDK, build-tools ${BUILD_TOOLS}…"
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
"$SDKMANAGER" --install "platform-tools" "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" \
    "cmake;$CMAKE_VERSION" "ndk;$NDK_VERSION" > /dev/null
NDK_DIR="$ANDROID_HOME/ndk/$NDK_VERSION"
[ -d "$NDK_DIR" ] && ok "NDK $NDK_VERSION" || fail "NDK install failed"

if [ "${1:-}" = "--ci" ] && [ -n "${GITHUB_ENV:-}" ]; then
    # Runner images preset these to their own default NDK; point them at the pinned one.
    {
        echo "ANDROID_NDK_ROOT=$NDK_DIR"; echo "ANDROID_NDK_HOME=$NDK_DIR"
        echo "NDK_VERSION=$NDK_VERSION"; echo "BUILD_TOOLS=$BUILD_TOOLS"
    } >> "$GITHUB_ENV"
else
    echo
    echo "Done. Next: scripts/fetch-deps.sh && scripts/build-openssl.sh && ./gradlew assembleDebug"
    echo "(If ANDROID_NDK_ROOT is set elsewhere in your shell, point it at $NDK_DIR.)"
fi
