#!/bin/bash
# Builds a static libcrypto for each Android ABI with the NDK, once, and caches the result:
#   third_party/openssl-android/include/            (headers, shared by all ABIs)
#   third_party/openssl-android/lib/<abi>/libcrypto.a
#   third_party/openssl-android/BUILD_INFO           (OpenSSL version + NDK + API + ABIs)
# Skips the build when BUILD_INFO already matches. Use --force to rebuild.
#
# The build runs on a temporary copy of the sources, for two reasons: third_party/openssl is never
# modified, and OpenSSL's makefiles break on the space in this project's folder name
# ("airplay receiver"). A symlink does not help because Configure resolves it with realpath().
# Only libcrypto is built: UxPlay does not use libssl (TLS).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)

# ABIs: same setting as Gradle's abiFilters — env AIRPLAYRX_ABIS, else airplayrx.abis from
# ~/.gradle/gradle.properties (per developer), else from the project's gradle.properties.
read_abis() {
    [ -n "${AIRPLAYRX_ABIS:-}" ] && { echo "$AIRPLAYRX_ABIS"; return; }
    local f v
    for f in "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties" "$ROOT/gradle.properties"; do
        v=$(sed -n 's/^[[:space:]]*airplayrx\.abis[[:space:]]*=[[:space:]]*//p' "$f" 2>/dev/null | tail -1)
        [ -n "$v" ] && { echo "$v"; return; }
    done
    echo "armeabi-v7a,arm64-v8a"
}
IFS=',' read -r -a ABIS <<< "$(read_abis | tr -d '[:space:]')"
API=31  # keep in sync with minSdk in app/build.gradle.kts
# Dev Mac: dual-core i5 with 4 threads; more only adds memory pressure. CI may override.
JOBS="${OPENSSL_JOBS:-4}"

: "${ANDROID_HOME:=$HOME/Android/Sdk}"
: "${ANDROID_NDK_ROOT:=$ANDROID_HOME/ndk/30.0.16248370}"
export ANDROID_NDK_ROOT
# The NDK ships one prebuilt toolchain per host: darwin-x86_64 (the dev Mac) or linux-x86_64 (CI).
case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;
    Linux)  NDK_HOST=linux-x86_64 ;;
    *) echo "unsupported build host $(uname -s)" >&2; exit 1 ;;
esac
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST/bin:$PATH"

SRC="$ROOT/third_party/openssl"
OUT="$ROOT/third_party/openssl-android"
VERSION=$(cat "$SRC/VERSION")
NDK_VER=$(basename "$ANDROID_NDK_ROOT")
INFO="openssl=$VERSION ndk=$NDK_VER api=$API abis=${ABIS[*]}"

if [ "${1:-}" != "--force" ] && [ -f "$OUT/BUILD_INFO" ] && [ "$(cat "$OUT/BUILD_INFO")" = "$INFO" ]; then
    echo "✓ OpenSSL already built ($INFO)"
    exit 0
fi

openssl_target() {
    case "$1" in
        armeabi-v7a) echo android-arm ;;
        arm64-v8a)   echo android-arm64 ;;
        x86)         echo android-x86 ;;
        x86_64)      echo android-x86_64 ;;
        *) echo "unknown ABI $1" >&2; exit 1 ;;
    esac
}

# Features UxPlay never touches; leaving them out shortens the build.
CONFIG_OPTS=(
    no-shared no-tests no-apps no-docs no-ui-console no-module no-engine no-dso
    no-comp no-dtls no-ssl3 no-quic no-srtp no-sctp
)

rm -rf "$OUT"
mkdir -p "$OUT/include" "$OUT/lib"

for abi in "${ABIS[@]}"; do
    BUILD=$(mktemp -d)/openssl
    cp -R "$SRC" "$BUILD"
    echo "→ Configuring OpenSSL $VERSION for $abi (API $API) in ${BUILD}…"
    (cd "$BUILD" && perl ./Configure "$(openssl_target "$abi")" -D__ANDROID_API__=$API \
        "${CONFIG_OPTS[@]}" -O2 -fPIC >/dev/null)

    echo "→ Building libcrypto for $abi with -j$JOBS (this is the slow part)…"
    start=$(date +%s)
    make -C "$BUILD" -j$JOBS build_generated >/dev/null
    make -C "$BUILD" -j$JOBS libcrypto.a >/dev/null
    echo "  built in $(( ($(date +%s) - start) / 60 )) min"

    mkdir -p "$OUT/lib/$abi"
    cp "$BUILD/libcrypto.a" "$OUT/lib/$abi/"
    # The build copy's include/ holds source headers plus generated ones (configuration.h, …).
    cp -R "$BUILD/include/openssl" "$OUT/include/"
    rm -rf "$(dirname "$BUILD")"
done

echo "$INFO" > "$OUT/BUILD_INFO"
echo "✓ OpenSSL ready: $INFO"
ls -la "$OUT"/lib/*/
