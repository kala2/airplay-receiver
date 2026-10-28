#!/bin/bash
# Builds the signed release APK for <version> (passed to Gradle, nothing written), verifies it was
# signed with the expected certificate, and puts the release assets into dist/:
#   airplay-receiver-<v>.apk, -mapping.zip (R8), -native-symbols.zip (unstripped .so), SHA256SUMS
# Needs AIRPLAYRX_SIGNING (path to signing.properties) and EXPECTED_CERT_SHA256 in the environment.
# Run by semantic-release (prepare step) before the tag/release exist: a failure here means no
# release is published.
set -euo pipefail
cd "$(dirname "$0")/../.."
v="$1"
: "${EXPECTED_CERT_SHA256:?EXPECTED_CERT_SHA256 not set}"
: "${AIRPLAYRX_SIGNING:?AIRPLAYRX_SIGNING not set}"
[[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "bad version $v" >&2; exit 1; }
code=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3] ))

./gradlew --no-daemon assembleRelease -PversionName="$v" -PversionCode="$code"

apk=app/build/outputs/apk/release/app-release.apk
apksigner="$ANDROID_HOME/build-tools/${BUILD_TOOLS:-34.0.0}/apksigner"
"$apksigner" verify "$apk"
actual=$("$apksigner" verify --print-certs "$apk" | awk '/certificate SHA-256 digest/ {print $NF; exit}')
[ "$actual" = "$EXPECTED_CERT_SHA256" ] || { echo "unexpected signing certificate: $actual" >&2; exit 1; }

rm -rf dist && mkdir dist
out="$(pwd)/dist"
cp "$apk" "$out/airplay-receiver-$v.apk"
(cd app/build/outputs/mapping/release && zip -q "$out/airplay-receiver-$v-mapping.zip" mapping.txt)
syms=$(find app/build/intermediates -type d -path '*merged_native_libs/release*' -name lib | head -1)
(cd "$syms" && zip -qr "$out/airplay-receiver-$v-native-symbols.zip" .)
(cd "$out" && shasum -a 256 ./*.apk ./*.zip > SHA256SUMS)
echo "✓ release assets in dist/:"; ls -l dist
