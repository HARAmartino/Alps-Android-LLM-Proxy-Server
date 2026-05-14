#!/usr/bin/env bash
# check_apk_size.sh
#
# Builds the release APK (unsigned is fine for size checks) and fails if the
# per-ABI APK exceeds the 25 MB target.
#
# Usage:
#   ./check_apk_size.sh [--max-mb <N>]
#
# Environment:
#   MAX_APK_SIZE_MB  Override the threshold (default: 25)
#
# The script checks every *-release-unsigned.apk (or *-release.apk) produced
# by the ABI-split build.  All files must be under the threshold.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
MAX_MB="${MAX_APK_SIZE_MB:-25}"

# Allow CLI override: --max-mb <N>
while [[ $# -gt 0 ]]; do
    case "$1" in
        --max-mb)
            MAX_MB="$2"; shift 2 ;;
        *)
            echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

MAX_BYTES=$(( MAX_MB * 1024 * 1024 ))

echo "=== Building release APKs (unsigned) ==="
cd "$REPO_ROOT"
./gradlew assembleRelease --no-daemon --console=plain

echo ""
echo "=== Checking APK sizes (limit: ${MAX_MB} MB = ${MAX_BYTES} bytes) ==="

APK_DIR="$REPO_ROOT/app/build/outputs/apk/release"
if [[ ! -d "$APK_DIR" ]]; then
    echo "ERROR: APK output directory not found: $APK_DIR" >&2
    exit 1
fi

# Collect all release APKs (split or universal)
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 1 -name "*.apk" | sort)

if [[ ${#APKS[@]} -eq 0 ]]; then
    echo "ERROR: No APK files found in $APK_DIR" >&2
    exit 1
fi

FAILED=0
for apk in "${APKS[@]}"; do
    size=$(wc -c < "$apk")
    size_mb=$(awk "BEGIN {printf \"%.2f\", $size / 1048576}")
    name=$(basename "$apk")
    if (( size > MAX_BYTES )); then
        echo "  FAIL  $name  ${size_mb} MB  >  ${MAX_MB} MB limit"
        FAILED=1
    else
        echo "  OK    $name  ${size_mb} MB"
    fi
done

echo ""
if (( FAILED )); then
    echo "One or more APKs exceed the ${MAX_MB} MB size limit. Reduce dependencies or tighten R8 rules." >&2
    exit 1
else
    echo "All APKs are within the ${MAX_MB} MB limit."
fi
