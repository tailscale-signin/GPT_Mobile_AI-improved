#!/usr/bin/env bash
# Stable local/CI validation entry point. Usage: bash scripts/validate.sh [all|resources|test|lint|build]
set -euo pipefail

TASK="${1:-all}"
GRADLE=(./gradlew --no-daemon --stacktrace)

run_resources() {
    python3 scripts/check_android_resources.py
}

case "$TASK" in
    all)
        run_resources
        "${GRADLE[@]}" :app:testDebugUnitTest :app:lintDebug assembleDebug
        ;;
    resources)
        run_resources
        ;;
    test)
        "${GRADLE[@]}" :app:testDebugUnitTest
        ;;
    lint)
        "${GRADLE[@]}" :app:lintDebug
        ;;
    build)
        "${GRADLE[@]}" assembleDebug
        ;;
    *)
        echo "Unknown validation target: $TASK" >&2
        echo "Usage: bash scripts/validate.sh [all|resources|test|lint|build]" >&2
        exit 2
        ;;
esac
