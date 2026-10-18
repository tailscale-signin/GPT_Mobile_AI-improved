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
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.ConversationGlassInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/conversation-glass-instrumentation.txt"
conversation_glass_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedSettingsInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/combined-settings-instrumentation.txt"
combined_settings_status=${PIPESTATUS[0]}
# The suite requests both 320 dp and 600 dp layouts; the device must accommodate the wider one.
timeout 30s adb shell wm size 1200x2560
settings_home_display_status=$?
if [ "$settings_home_display_status" -ne 0 ]; then
  printf 'Settings Home 600 dp viewport setup failed (exit %s).\n' "$settings_home_display_status" >&2
fi
settings_home_status=125
if [ "$settings_home_display_status" -eq 0 ]; then
  timeout 240s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsHomeInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/settings-home-instrumentation.txt"
  settings_home_status=${PIPESTATUS[0]}
fi
timeout 30s adb shell wm size 640x1280
settings_home_restore_status=$?
if [ "$settings_home_restore_status" -ne 0 ]; then
  printf 'Settings Home 320 dp viewport restore failed (exit %s).\n' "$settings_home_restore_status" >&2
fi
# The workflow disables animations. This suite measures an intermediate navigation frame.
navigation_setup_status=0
for navigation_scale in animator_duration_scale window_animation_scale transition_animation_scale; do
  timeout 30s adb shell settings put global "$navigation_scale" 1
  navigation_scale_status=$?
  if [ "$navigation_scale_status" -ne 0 ]; then
    navigation_setup_status=$navigation_scale_status
    printf 'Navigation animation setup failed for %s (exit %s).\n' "$navigation_scale" "$navigation_scale_status" >&2
  fi
done
navigation_motion_status=125
if [ "$navigation_setup_status" -eq 0 ]; then
  timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.navigation.NavigationMotionInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/navigation-motion-instrumentation.txt"
  navigation_motion_status=${PIPESTATUS[0]}
fi
navigation_restore_status=0
for navigation_scale in animator_duration_scale window_animation_scale transition_animation_scale; do
  timeout 30s adb shell settings put global "$navigation_scale" 0
  navigation_scale_status=$?
  if [ "$navigation_scale_status" -ne 0 ]; then
    navigation_restore_status=$navigation_scale_status
    printf 'Navigation animation restore failed for %s (exit %s).\n' "$navigation_scale" "$navigation_scale_status" >&2
  fi
done
set -e
adb logcat -d > "$acceptance_output/logcat.txt"
settings_home_artifact_status=0
timeout 30s adb exec-out run-as dev.melo.gptmobile.improved tar -cf - -C files/ui-review settings-home > "$acceptance_output/settings-home.tar" || settings_home_artifact_status=$?
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
for conversation_theme in light dark; do
  for conversation_state in opacity sharp blur; do
    adb exec-out run-as dev.melo.gptmobile.improved cat "files/conversation-glass-$conversation_state-$conversation_theme.png" > "$acceptance_output/conversation-glass-$conversation_state-$conversation_theme.png"
  done
done
if [ "$settings_home_display_status" -ne 0 ]; then exit "$settings_home_display_status"; fi
if [ "$settings_home_restore_status" -ne 0 ]; then exit "$settings_home_restore_status"; fi
if [ "$navigation_setup_status" -ne 0 ]; then exit "$navigation_setup_status"; fi
if [ "$navigation_restore_status" -ne 0 ]; then exit "$navigation_restore_status"; fi
if [ "$combined_settings_status" -ne 0 ]; then exit "$combined_settings_status"; fi
if [ "$settings_home_status" -ne 0 ]; then exit "$settings_home_status"; fi
if [ "$navigation_motion_status" -ne 0 ]; then exit "$navigation_motion_status"; fi
if [ "$settings_home_artifact_status" -ne 0 ]; then exit "$settings_home_artifact_status"; fi
if [ "$conversation_glass_status" -ne 0 ]; then exit "$conversation_glass_status"; fi
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
import tarfile
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
for test in ('conversationGlassKeepsRequestedOpacityOverFadedMessages', 'conversationGlassFiltersDetailInBothControls', 'conversationGlassDoesNotRevealMessagesBeforeEntryRestores'):
    check_instrumentation(folder / 'conversation-glass-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.ConversationGlassInstrumentedTest', test)
for test in ('combinedTabStagesBothAxesAndRemovesDelegationCard', 'cancelKeepsCommittedMembershipAndPreventsEmptySave', 'membershipRowStagesChangesWithoutSavingImmediately', 'saveableDraftRestoresTheTwoAxesTogether', 'unsupportedFutureControlsStayVisibleButCannotBeEdited'):
    check_instrumentation(folder / 'combined-settings-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedSettingsInstrumentedTest', test)
for test in ('oneToFiveItemsAtCompactWidth', 'oneToFiveItemsAtWideWidth', 'oneToFiveItemsAtLargeFontAndRtl', 'homeScreenNamesItsDestinationEnforcesFiveAndAllowsZero'):
    check_instrumentation(folder / 'settings-home-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsHomeInstrumentedTest', test)
check_instrumentation(folder / 'navigation-motion-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.navigation.NavigationMotionInstrumentedTest', 'backUsesDirectionalMotionAndCompletesOnThePreviousScreen')
for test in ('shortHoldDoesNotOpenMenuAndOneSecondHoldPreservesAction', 'movingToScrollCancelsTheHold'):
    check_instrumentation(folder / 'bubble-actions-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.BubbleActionMenuInstrumentedTest', test)
for name in ('picker-light-200-percent.png', 'picker-dark-rtl-200-percent.png', 'glass-off.png', 'glass-40dp.png', 'glass-stacked.png', 'chat-glass-controls.png'):
    assert (folder / name).read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
for theme in ('light', 'dark'):
    for state in ('opacity', 'sharp', 'blur'):
        assert (folder / f'conversation-glass-{state}-{theme}.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
with tarfile.open(folder / 'settings-home.tar') as captures:
    for layout in ('compact', 'wide', 'large-rtl'):
        for theme in ('light', 'dark'):
            for count in range(1, 6):
                name = f'settings-home/{layout}-{theme}-{count}.png'
                member = captures.getmember(name)
                assert member.isfile(), name
                with captures.extractfile(member) as image:
                    assert image.read(8) == b'\x89PNG\r\n\x1a\n', name
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
