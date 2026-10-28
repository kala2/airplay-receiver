#!/bin/bash
# semantic-release verifyRelease: refuse versions that can't map to an Android versionCode
# (versionCode = MAJOR·10000 + MINOR·100 + PATCH, see app/build.gradle.kts).
set -euo pipefail
v="$1"
[[ "$v" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "not MAJOR.MINOR.PATCH: $v" >&2; exit 1; }
(( BASH_REMATCH[2] < 100 && BASH_REMATCH[3] < 100 )) || { echo "minor/patch must be < 100: $v" >&2; exit 1; }
echo "✓ version $v → versionCode $(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3] ))"
