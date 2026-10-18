#!/usr/bin/env bash
set -euo pipefail
acceptance_output=app/build/acceptance-0950
mkdir -p "$acceptance_output"
# Constrain the display itself so dialog windows are also tested at 320 dp.
adb shell wm size 640x1280
adb shell wm density 320
adb shell settings put system font_scale 2.0
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
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.memory.MemoryDurabilityInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/memory-durability-instrumentation.txt"
memory_status=${PIPESTATUS[0]}
timeout 240s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.home.PlatformPickerInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/picker-instrumentation.txt"
picker_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.BubbleActionMenuInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/bubble-actions-instrumentation.txt"
bubble_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/glass-instrumentation.txt"
glass_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.ComposerControlsInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/composer-controls-instrumentation.txt"
composer_status=${PIPESTATUS[0]}
set -e
adb logcat -d > "$acceptance_output/logcat.txt"
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-performance.json > "$acceptance_output/performance.json" || true
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-editor.png > "$acceptance_output/editor-rtl-200-percent.png" || true
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-plan-dialog.png > "$acceptance_output/plan-dialog-rtl-200-percent.png" || true
for picker_theme in light dark-rtl; do
  adb exec-out run-as dev.melo.gptmobile.improved cat "files/picker-$picker_theme-200-percent.png" > "$acceptance_output/picker-$picker_theme-200-percent.png"
done
for glass_state in off 40dp stacked; do
  adb exec-out run-as dev.melo.gptmobile.improved cat "files/glass-$glass_state.png" > "$acceptance_output/glass-$glass_state.png"
done
adb exec-out run-as dev.melo.gptmobile.improved cat files/chat-glass-controls.png > "$acceptance_output/chat-glass-controls.png"
if [ "$composer_status" -ne 0 ]; then exit "$composer_status"; fi
if [ "$glass_status" -ne 0 ]; then exit "$glass_status"; fi
if [ "$bubble_status" -ne 0 ]; then exit "$bubble_status"; fi
if [ "$picker_status" -ne 0 ]; then exit "$picker_status"; fi
if [ "$editor_status" -ne 0 ]; then exit "$editor_status"; fi
if [ "$performance_status" -ne 0 ]; then exit "$performance_status"; fi
if [ "$memory_status" -ne 0 ]; then exit "$memory_status"; fi
python3 - <<'CHECK'
import json
import sys
from pathlib import Path
sys.path.insert(0, 'scripts')
from check_native_memory_smoke import check_instrumentation
folder = Path('app/build/acceptance-0950')
check_instrumentation(folder / 'editor-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.Release0950InstrumentedTest', 'planEditorAppliesStepsAtCompactWidthLargeFontAndRtl')
check_instrumentation(folder / 'performance-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsPerformanceInstrumentedTest', 'catalogParseAndStatisticsOverheadProduceMeasuredEvidence')
for test in ('encryptedCutoverPreservesIdentityAndRejectsStaleWrites', 'portableRestoreUsesNewKeyAndCannotResurrectDeletedFacts'):
    check_instrumentation(folder / 'memory-durability-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.memory.MemoryDurabilityInstrumentedTest', test)
for test in ('compactLightSelectionAndAccessibleFavorite', 'compactDarkRtlSelectionAndAccessibleFavorite'):
    check_instrumentation(folder / 'picker-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.home.PlatformPickerInstrumentedTest', test)
for test in ('dialogBlurSwitchActuallyFiltersTheBackground', 'stackedDialogSamplesItsVisibleParentInsteadOfOnlyTheActivity', 'settingsBackdropAndTypographyChangesKeepNavigationContentComposed', 'openingSettingsWhileHomeBlurStillDrawsDoesNotReplayLiveHomeLayers'):
    check_instrumentation(folder / 'glass-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropInstrumentedTest', test)
for test in ('typingFollowUpKeepsStopAndSendIndependent', 'glassComposerKeepsSendStopAndLatestActionsIndependent'):
    check_instrumentation(folder / 'composer-controls-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.ComposerControlsInstrumentedTest', test)
for test in ('shortHoldDoesNotOpenMenuAndOneSecondHoldPreservesAction', 'movingToScrollCancelsTheHold'):
    check_instrumentation(folder / 'bubble-actions-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.BubbleActionMenuInstrumentedTest', test)
for name in ('picker-light-200-percent.png', 'picker-dark-rtl-200-percent.png', 'glass-off.png', 'glass-40dp.png', 'glass-stacked.png', 'chat-glass-controls.png'):
    assert (folder / name).read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
data = json.loads((folder / 'performance.json').read_text())
assert data['samples'] == 100 and data['representativeDevice'] is False
assert data['qualification'] == 'measurement_only'
assert data['acknowledgedStarts'] + data['unacknowledgedStarts'] == 100
assert len(data['writeAttemptNanos']) == len(data['acceptedStarts']) == 100
if data['unacknowledgedStarts'] or data['recordedInProgress']:
    print('Recording gaps observed; performance remains unqualified:', data['unacknowledgedStarts'], data['recordedInProgress'])
assert data['screenWidthDp'] == 320 and data['densityDpi'] == 320
assert data['fontScale'] == 2.0
for name in ('editor-rtl-200-percent.png', 'plan-dialog-rtl-200-percent.png'):
    assert (folder / name).read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
CHECK
