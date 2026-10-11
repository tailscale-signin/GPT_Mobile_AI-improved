#!/usr/bin/env bash
# Stable local/CI validation entry point. Use `provenance` to require a qualified QNN dispatch build.
set -euo pipefail

TASK="${1:-all}"
GRADLE=(./gradlew --no-daemon --stacktrace)

run_resources() {
    python3 scripts/check_android_resources.py
    python3 scripts/generate_app_icon.py --check
    python3 scripts/check_qnn_provenance.py
}

case "$TASK" in
    all)
        run_resources
        "${GRADLE[@]}" :app:testDebugUnitTest :app:lintDebug assembleDebug
        python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
        python3 scripts/check_no_telemetry.py app/build/outputs/apk/debug/*.apk
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
        python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
        python3 scripts/check_no_telemetry.py app/build/outputs/apk/debug/*.apk
        ;;
    provenance)
        python3 scripts/check_qnn_provenance.py
        ;;
    *)
        echo "Unknown validation target: $TASK" >&2
        echo "Usage: bash scripts/validate.sh [all|resources|test|lint|build|provenance]" >&2
        exit 2
        ;;
esac
