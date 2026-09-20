#!/usr/bin/env bash
# scripts/verify_debug_build.sh
# Verifies debug build configuration and outputs
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

echo "==> Verifying debug build configuration..."

# Build debug APK
./gradlew :app:assembleDebug --no-daemon --stacktrace

DEBUG_APK_DIR="$PROJECT_ROOT/app/build/outputs/apk/debug"

if [ ! -d "$DEBUG_APK_DIR" ]; then
    echo "✗ Debug APK directory does not exist: $DEBUG_APK_DIR" >&2
    exit 1
fi

APKS=($(find "$DEBUG_APK_DIR" -name "*.apk"))
if [ ${#APKS[@]} -eq 0 ]; then
    echo "✗ No debug APK produced in $DEBUG_APK_DIR" >&2
    exit 1
fi

echo "✓ Debug APK(s) created successfully:"
for apk in "${APKS[@]}"; do
    SIZE=$(ls -lh "$apk" | awk '{print $5}')
    echo "  - $(basename "$apk") ($SIZE)"
done

echo "==> Debug build verification passed!"
