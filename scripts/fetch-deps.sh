#!/bin/bash
# Downloads the pinned release archives of UxPlay, libplist and OpenSSL into third_party/.
# Each archive is SHA-256 checked; a dependency whose VERSION file already matches is skipped.
#
# Usage: scripts/fetch-deps.sh [--force]
# To bump a version: change it below, set its SHA-256 and run with --force (see CONTRIBUTING.md).
set -euo pipefail

cd "$(dirname "$0")/.."
TP=third_party
FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

# name | version | url | sha256
DEPS=(
"UxPlay|v1.73.7|https://github.com/FDH2/UxPlay/archive/refs/tags/v1.73.7.tar.gz|65feb8732de666a7161a9e562aa603a7f5fe0ebb890c2a30c5e1a9c85c76309f"
"libplist|2.8.0|https://github.com/libimobiledevice/libplist/releases/download/2.8.0/libplist-2.8.0.tar.bz2|b1f59f7634c58b2481325a23ff4e3bf51574a42d868cbe466d2b39b04550752a"
"openssl|3.5.9|https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz|603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
)

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TP"
for entry in "${DEPS[@]}"; do
    IFS='|' read -r name version url sha <<<"$entry"
    dest="$TP/$name"

    if [ $FORCE = 0 ] && [ -f "$dest/VERSION" ] && [ "$(cat "$dest/VERSION")" = "$version" ]; then
        echo "✓ $name $version already present"
        continue
    fi

    echo "↓ $name $version"
    archive="$TMP/$(basename "$url")"
    curl -fL --retry 3 -sS -o "$archive" "$url"
    echo "$sha  $archive" | shasum -a 256 -c --quiet || { echo "✗ checksum mismatch for $name" >&2; exit 1; }

    rm -rf "$dest" "$TMP/x" && mkdir -p "$TMP/x"
    tar -xf "$archive" -C "$TMP/x"
    # Each archive contains exactly one top-level folder; move it into place under a stable name.
    mv "$TMP/x"/*/ "$dest"
    echo "$version" > "$dest/VERSION"
    echo "✓ $name $version → $dest"
done
