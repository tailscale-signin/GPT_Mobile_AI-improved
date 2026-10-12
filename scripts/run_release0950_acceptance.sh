#!/usr/bin/env bash
set -euo pipefail
acceptance_output=app/build/acceptance-0950
mkdir -p "$acceptance_output"
# Constrain the display itself so dialog windows are also tested at 320 dp.
adb shell wm size 640x1280
adb shell wm density 320
adb install -r app/build/outputs/apk/debug/*x86_64*.apk
adb install -r app/build/outputs/apk/androidTest/debug/*.apk
adb shell pm list instrumentation > "$acceptance_output/instrumentations.txt"
adb logcat -c
# Gradle's connected-test cleanup uninstalls the app. Run explicit named cases
# directly, validate their actual protocol packets, then collect private files.
set +e
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.setting.Release0950InstrumentedTest#planEditorAppliesStepsAtCompactWidthLargeFontAndRtl dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/editor-instrumentation.txt"
editor_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsPerformanceInstrumentedTest#catalogParseAndStatisticsOverheadProduceMeasuredEvidence dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/performance-instrumentation.txt"
performance_status=${PIPESTATUS[0]}
set -e
adb logcat -d > "$acceptance_output/logcat.txt"
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-performance.json > "$acceptance_output/performance.json" || true
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-editor.png > "$acceptance_output/editor-rtl-200-percent.png" || true
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-plan-dialog.png > "$acceptance_output/plan-dialog-rtl-200-percent.png" || true
if [ "$editor_status" -ne 0 ]; then exit "$editor_status"; fi
if [ "$performance_status" -ne 0 ]; then exit "$performance_status"; fi
python3 - <<'CHECK'
import json
import sys
from pathlib import Path
sys.path.insert(0, 'scripts')
from check_native_memory_smoke import check_instrumentation
folder = Path('app/build/acceptance-0950')
check_instrumentation(folder / 'editor-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.Release0950InstrumentedTest', 'planEditorAppliesStepsAtCompactWidthLargeFontAndRtl')
check_instrumentation(folder / 'performance-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsPerformanceInstrumentedTest', 'catalogParseAndStatisticsOverheadProduceMeasuredEvidence')
data = json.loads((folder / 'performance.json').read_text())
assert data['samples'] == 100 and data['representativeDevice'] is False
assert data['screenWidthDp'] == 320 and data['densityDpi'] == 320
for name in ('editor-rtl-200-percent.png', 'plan-dialog-rtl-200-percent.png'):
    assert (folder / name).read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
CHECK
