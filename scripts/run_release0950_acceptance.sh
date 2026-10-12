#!/usr/bin/env bash
set -euo pipefail
acceptance_output=app/build/acceptance-0950
mkdir -p "$acceptance_output"
adb logcat -c
set +e
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.presentation.ui.setting.Release0950InstrumentedTest,dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsPerformanceInstrumentedTest --no-daemon
acceptance_status=$?
set -e
# Collect while the emulator is alive; the action tears it down after this script.
adb logcat -d > "$acceptance_output/logcat.txt"
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-performance.json > "$acceptance_output/performance.json" || true
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-editor.png > "$acceptance_output/editor-rtl-200-percent.png" || true
if [ "$acceptance_status" -eq 0 ]; then
    python3 - <<'CHECK'
import json
from pathlib import Path
folder = Path('app/build/acceptance-0950')
data = json.loads((folder / 'performance.json').read_text())
assert data['samples'] == 100 and data['representativeDevice'] is False
assert (folder / 'editor-rtl-200-percent.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
CHECK
fi
exit "$acceptance_status"
