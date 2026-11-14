#!/usr/bin/env bash
set -euo pipefail
acceptance_output=app/build/acceptance-0950
mkdir -p "$acceptance_output"
# Preserve the original status while retaining evidence even when preparation
# fails before any instrumentation starts. Never clear away boot/setup ANRs.
capture_acceptance_diagnostics() {
  acceptance_exit=$?
  trap - EXIT
  set +e
  timeout 10s adb logcat -d > "$acceptance_output/exit-logcat.txt"
  timeout 10s adb shell dumpsys window -a > "$acceptance_output/window-state.txt"
  timeout 10s adb shell dumpsys activity processes > "$acceptance_output/process-state.txt"
  timeout 10s adb shell dumpsys activity lastanr > "$acceptance_output/last-anr.txt"
  exit "$acceptance_exit"
}
trap capture_acceptance_diagnostics EXIT
timeout 10s adb logcat -d > "$acceptance_output/boot-logcat.txt"
application_apk=$(python3 scripts/select_android_test_apk.py app/build/intermediates/apk/debug --application-id dev.melo.gptmobile.improved --abi x86_64)
instrumentation_apk=$(python3 scripts/select_android_test_apk.py app/build/intermediates/apk/androidTest/debug --application-id dev.melo.gptmobile.improved.test)
# AGP marks IDE-targeted APKs test-only; both installs are confined to this emulator.
adb install -r -t "$application_apk"
adb install -r -t "$instrumentation_apk"
python3 scripts/prepare_emulator_ui.py --output "$acceptance_output/emulator-ui.json"
# Constrain the display with the lightweight test HOME in the foreground.
# Keep the stock launcher's QuickStep service available to SystemUI's taskbar.
adb shell wm size 640x1280
adb shell wm density 320
adb shell settings put system font_scale 2.0
python3 scripts/prepare_emulator_ui.py --settle-display --output "$acceptance_output/emulator-display.json"
timeout 10s adb logcat -d > "$acceptance_output/setup-logcat.txt"
adb shell pm list instrumentation > "$acceptance_output/instrumentations.txt"
# Gradle's connected-test cleanup uninstalls the app. Run explicit named cases
# directly, validate their actual protocol packets, then collect private files.
set +e
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.setting.Release0950InstrumentedTest#planEditorAppliesStepsAtCompactWidthLargeFontAndRtl dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/editor-instrumentation.txt"
editor_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsPerformanceInstrumentedTest#catalogParseAndStatisticsOverheadProduceMeasuredEvidence dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/performance-instrumentation.txt"
performance_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.memory.MemoryDurabilityInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/memory-durability-instrumentation.txt"
memory_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.agent.ToolCallBudgetFenceInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/tool-budget-fence-instrumentation.txt"
tool_budget_fence_status=${PIPESTATUS[0]}
timeout 240s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.home.PlatformPickerInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/picker-instrumentation.txt"
picker_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.home.GatewayProfileCardInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/gateway-profile-card-instrumentation.txt"
gateway_profile_card_status=${PIPESTATUS[0]}
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
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedFinalizationLifecycleInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/combined-finalization-instrumentation.txt"
combined_finalization_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubWorkflowExecutionInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/github-workflow-execution-instrumentation.txt"
github_workflow_execution_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.github.GitHubBoundTransportInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/github-bound-transport-instrumentation.txt"
github_bound_transport_status=${PIPESTATUS[0]}
# The suite requests both 320 dp and 600 dp layouts; the device must accommodate the wider one.
timeout 30s adb shell wm size 1200x2560
settings_home_display_status=$?
if [ "$settings_home_display_status" -eq 0 ]; then
  python3 scripts/prepare_emulator_ui.py --settle-display --size 1200x2560 --output "$acceptance_output/settings-home-display.json"
  settings_home_display_status=$?
fi
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
if [ "$settings_home_restore_status" -eq 0 ]; then
  python3 scripts/prepare_emulator_ui.py --settle-display --output "$acceptance_output/settings-home-restore.json"
  settings_home_restore_status=$?
fi
if [ "$settings_home_restore_status" -ne 0 ]; then
  printf 'Settings Home 320 dp viewport restore failed (exit %s).\n' "$settings_home_restore_status" >&2
fi
# The workflow disables animations. These suites measure intermediate navigation/chat frames.
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
github_prompt_motion_status=125
chat_bottom_scroll_status=125
if [ "$navigation_setup_status" -eq 0 ]; then
  timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.navigation.NavigationMotionInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/navigation-motion-instrumentation.txt"
  navigation_motion_status=${PIPESTATUS[0]}
  timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubPromptMotionInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/github-prompt-motion-instrumentation.txt"
  github_prompt_motion_status=${PIPESTATUS[0]}
  timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatBottomAutoScrollerInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/chat-bottom-scroll-instrumentation.txt"
  chat_bottom_scroll_status=${PIPESTATUS[0]}
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
timeout 240s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.chat.ResponseSwipeInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/response-swipe-instrumentation.txt"
swipe_status=${PIPESTATUS[0]}
timeout 240s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.setting.AuditActionsInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/audit-actions-instrumentation.txt"
audit_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.localmodel.NativeModelSourceInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/model-source-instrumentation.txt"
model_source_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.data.security.SecretVaultInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/secret-vault-instrumentation.txt"
secret_vault_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.voice.LocalVoicePluginInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/local-voice-instrumentation.txt"
local_voice_status=${PIPESTATUS[0]}
timeout 180s adb shell am instrument -w -r -e class dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceConversationInstrumentedTest dev.melo.gptmobile.improved.test/androidx.test.runner.AndroidJUnitRunner | tee "$acceptance_output/voice-conversation-instrumentation.txt"
voice_conversation_status=${PIPESTATUS[0]}
set -e
timeout 10s adb logcat -d > "$acceptance_output/logcat.txt"
settings_home_artifact_status=0
timeout 30s adb exec-out run-as dev.melo.gptmobile.improved tar -cf - -C files/ui-review settings-home > "$acceptance_output/settings-home.tar" || settings_home_artifact_status=$?
adb exec-out run-as dev.melo.gptmobile.improved cat files/acceptance-0950-performance.json > "$acceptance_output/performance.json" || true
for github_fixture_case in bound-read owned-deletion; do
  adb exec-out run-as dev.melo.gptmobile.improved cat "files/github-$github_fixture_case-evidence.json" > "$acceptance_output/github-$github_fixture_case-evidence.json"
done
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
if [ "$secret_vault_status" -ne 0 ]; then exit "$secret_vault_status"; fi
if [ "$local_voice_status" -ne 0 ]; then exit "$local_voice_status"; fi
if [ "$voice_conversation_status" -ne 0 ]; then exit "$voice_conversation_status"; fi
if [ "$model_source_status" -ne 0 ]; then exit "$model_source_status"; fi
if [ "$swipe_status" -ne 0 ]; then exit "$swipe_status"; fi
if [ "$audit_status" -ne 0 ]; then exit "$audit_status"; fi
if [ "$settings_home_display_status" -ne 0 ]; then exit "$settings_home_display_status"; fi
if [ "$settings_home_restore_status" -ne 0 ]; then exit "$settings_home_restore_status"; fi
if [ "$navigation_setup_status" -ne 0 ]; then exit "$navigation_setup_status"; fi
if [ "$navigation_restore_status" -ne 0 ]; then exit "$navigation_restore_status"; fi
if [ "$combined_settings_status" -ne 0 ]; then exit "$combined_settings_status"; fi
if [ "$combined_finalization_status" -ne 0 ]; then exit "$combined_finalization_status"; fi
if [ "$github_workflow_execution_status" -ne 0 ]; then exit "$github_workflow_execution_status"; fi
if [ "$github_bound_transport_status" -ne 0 ]; then exit "$github_bound_transport_status"; fi
if [ "$settings_home_status" -ne 0 ]; then exit "$settings_home_status"; fi
if [ "$navigation_motion_status" -ne 0 ]; then exit "$navigation_motion_status"; fi
if [ "$github_prompt_motion_status" -ne 0 ]; then exit "$github_prompt_motion_status"; fi
if [ "$chat_bottom_scroll_status" -ne 0 ]; then exit "$chat_bottom_scroll_status"; fi
if [ "$settings_home_artifact_status" -ne 0 ]; then exit "$settings_home_artifact_status"; fi
if [ "$conversation_glass_status" -ne 0 ]; then exit "$conversation_glass_status"; fi
if [ "$composer_status" -ne 0 ]; then exit "$composer_status"; fi
if [ "$glass_status" -ne 0 ]; then exit "$glass_status"; fi
if [ "$bubble_status" -ne 0 ]; then exit "$bubble_status"; fi
if [ "$picker_status" -ne 0 ]; then exit "$picker_status"; fi
if [ "$gateway_profile_card_status" -ne 0 ]; then exit "$gateway_profile_card_status"; fi
if [ "$editor_status" -ne 0 ]; then exit "$editor_status"; fi
if [ "$performance_status" -ne 0 ]; then exit "$performance_status"; fi
if [ "$memory_status" -ne 0 ]; then exit "$memory_status"; fi
if [ "$tool_budget_fence_status" -ne 0 ]; then exit "$tool_budget_fence_status"; fi
python3 - <<'CHECK'
import json
import os
import re
import subprocess
import sys
import tarfile
from pathlib import Path
sys.path.insert(0, 'scripts')
from check_native_memory_smoke import check_instrumentation
folder = Path('app/build/acceptance-0950')
for test in (
    'longCaptionsAndLargeTextKeepTheMicrophoneVisibleAndFixed',
    'theProfilePickerChangesProfileOnlyBetweenTurns',
    'accessibilityCaptionScrollingCanReadEarlierWordsAndResumeFollowing',
):
    check_instrumentation(folder / 'voice-conversation-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.voice.VoiceConversationInstrumentedTest', test)
for test in (
    'packagedNativeSpeechRuntimeMatchesItsJavaApi',
    'kokoroPlaybackClassVerifiesBeforeAnyModelIsInstalled',
    'kokoroDelegateFallsBackToRealAndroidTextToSpeechWhenModelIsMissing',
    'modelInstallRequiresExplicitAction',
    'downloadCancellationAndModelRemovalHaveSeparateActions',
):
    check_instrumentation(folder / 'local-voice-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.voice.LocalVoicePluginInstrumentedTest', test)
for test in ('roundTripOverwriteAndDelete', 'rejectsUnsafeReferencesAndOversizedSecrets', 'rejectsMalformedRecords', 'oversizedRecordIsRejectedBeforeAllocationAndSizeDoesNotRequireAKey', 'rejectsRecordMovedToAnotherReference', 'missingKeystoreKey_preservesEncryptedRecord'):
    check_instrumentation(folder / 'secret-vault-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.security.SecretVaultInstrumentedTest', test)
for test in ('readableDescriptorWithDeniedPathCanBeExplicitlyImported', 'compatibleSourceRemainsInPlaceAndProbeDoesNotOwnItsDescriptor', 'oversizedFileIsRejectedInsteadOfOfferingAnImpossibleCopy'):
    check_instrumentation(folder / 'model-source-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.localmodel.NativeModelSourceInstrumentedTest', test)
for test in ('horizontalSwipeSwitchesProfilesAndVerticalScrollDoesNot', 'rtlSwipeUsesReadingDirectionAndDoesNotWrap'):
    check_instrumentation(folder / 'response-swipe-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.ResponseSwipeInstrumentedTest', test)
check_instrumentation(folder / 'audit-actions-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.AuditActionsInstrumentedTest', 'modelPickerKeepsTheChosenProfileAfterReordering')
check_instrumentation(folder / 'audit-actions-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.AuditActionsInstrumentedTest', 'localModelCopyIsExplicitAndRestrictedSourcesExplainStorage')
check_instrumentation(folder / 'editor-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.Release0950InstrumentedTest', 'planEditorAppliesStepsAtCompactWidthLargeFontAndRtl')
check_instrumentation(folder / 'performance-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.telemetry.tool.StatisticsPerformanceInstrumentedTest', 'catalogParseAndStatisticsOverheadProduceMeasuredEvidence')
for test in ('encryptedCutoverPreservesIdentityAndRejectsStaleWrites', 'portableRestoreUsesNewKeyAndCannotResurrectDeletedFacts'):
    check_instrumentation(folder / 'memory-durability-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.memory.MemoryDurabilityInstrumentedTest', test)
check_instrumentation(folder / 'tool-budget-fence-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.agent.ToolCallBudgetFenceInstrumentedTest', 'privateFilesystemFenceSurvivesPayloadLossAndRejectsDeletedChatWrites')
for test in ('compactLightSelectionAndAccessibleFavorite', 'compactDarkRtlSelectionAndAccessibleFavorite'):
    check_instrumentation(folder / 'picker-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.home.PlatformPickerInstrumentedTest', test)
for test in ('onlineStatusRemainsReadableAndDoesNotChangeSelectionAtLargeText', 'onlineStatusRemainsReadableAndDoesNotChangeSelectionInDarkRtl'):
    check_instrumentation(folder / 'gateway-profile-card-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.home.GatewayProfileCardInstrumentedTest', test)
for test in ('dialogBlurSwitchActuallyFiltersTheBackground', 'stackedDialogSamplesItsVisibleParentInsteadOfOnlyTheActivity', 'settingsBackdropAndTypographyChangesKeepNavigationContentComposed', 'openingSettingsWhileHomeBlurStillDrawsDoesNotReplayLiveHomeLayers'):
    check_instrumentation(folder / 'glass-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.common.GlassBackdropInstrumentedTest', test)
for test in ('typingFollowUpKeepsStopAndSendIndependent', 'glassComposerKeepsSendStopAndLatestActionsIndependent'):
    check_instrumentation(folder / 'composer-controls-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.ComposerControlsInstrumentedTest', test)
for test in ('conversationGlassKeepsRequestedOpacityOverFadedMessages', 'conversationGlassFiltersDetailInBothControls', 'conversationGlassDoesNotRevealMessagesBeforeEntryRestores'):
    check_instrumentation(folder / 'conversation-glass-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.ConversationGlassInstrumentedTest', test)
for test in ('combinedTabStagesBothAxesAndRemovesDelegationCard', 'cancelKeepsCommittedMembershipAndPreventsEmptySave', 'membershipRowStagesChangesWithoutSavingImmediately', 'saveableDraftRestoresTheTwoAxesTogether', 'unsupportedFutureControlsStayVisibleButCannotBeEdited'):
    check_instrumentation(folder / 'combined-settings-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedSettingsInstrumentedTest', test)
check_instrumentation(folder / 'combined-finalization-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedFinalizationLifecycleInstrumentedTest', 'finalizationWhilePointerIsDownAndScrollingDisposesThePreviousRenderer')
for test in ('savedAcceptanceStatesRemainDistinctFromSuccessfulOperations', 'recoverableReadRoutesResumeAndRetryToSavedRun', 'unavailableSavedReadOffersDetailsWithoutRepeatingTheExpiredView', 'unknownMutationOffersReconciliationAndIncidentDetailsWithoutRetry', 'unknownGatewayOutcomeKeepsDetailsWithoutResumeOrRetry', 'authorizationBlockKeepsDetailsWithoutRecovery', 'submittingRequestDisablesRecoveryButPreservesDetails'):
    check_instrumentation(folder / 'github-workflow-execution-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubWorkflowExecutionInstrumentedTest', test)
for test in ('boundReadUsesExactRepositoryAndRetainsRuntimeRevision', 'ownedDeletionReconcilesExactRefAbsenceWithoutRepeatingMutation'):
    check_instrumentation(folder / 'github-bound-transport-instrumentation.txt', 'dev.chungjungsoo.gptmobile.data.github.GitHubBoundTransportInstrumentedTest', test)
expected_revision = os.environ.get('GITHUB_SHA') or subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
for fixture in ('bound-read', 'owned-deletion'):
    receipt = json.loads((folder / f'github-{fixture}-evidence.json').read_text())
    assert receipt['schema'] == 'github.android-stub-acceptance.v1' and receipt['result'] == 'PASS'
    assert receipt['runtime']['app_source_sha'] == expected_revision, 'GitHub device fixture came from a different app revision'
    assert receipt['runtime']['identity_source'] == 'RUNNING_PROCESS_BUILD_CONFIG'
    assert int(receipt['runtime']['device_environment']['sdk_int']) >= 34
    assert re.fullmatch(r'[a-f0-9]{40}', receipt['repository_commit_sha'])
    assert receipt['evidence_class'] == 'CURRENT_DEVICE_STUB_EXECUTION' and receipt['transport'] == 'KTOR_MOCK_ENGINE'
    assert receipt['live_account_execution'] is False and receipt['live_side_effects'] == 0
    assert receipt['stub_http_requests'] > 0 and receipt['elapsed_realtime_ms'] >= 0
for test in ('oneToFiveItemsAtCompactWidth', 'oneToFiveItemsAtWideWidth', 'oneToFiveItemsAtLargeFontAndRtl', 'homeScreenNamesItsDestinationEnforcesFiveAndAllowsZero'):
    check_instrumentation(folder / 'settings-home-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.setting.SettingsHomeInstrumentedTest', test)
check_instrumentation(folder / 'navigation-motion-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.navigation.NavigationMotionInstrumentedTest', 'backUsesFadeWithoutScalingAndCompletesOnThePreviousScreen')
check_instrumentation(folder / 'github-prompt-motion-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubPromptMotionInstrumentedTest', 'promptMovesOverOneAndHalfSecondsWithoutAnImmediateJump')
check_instrumentation(folder / 'github-prompt-motion-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.GitHubPromptMotionInstrumentedTest', 'reattachmentWaitsForTheUserScrollAndRechecksAnchorIntent')
for test in ('openingExistingChat_overridesOldPositionAndShowsTrueBottomOfLongResponse', 'openingUnreadResponse_centersItsBeginningBetweenHeaderAndComposer', 'growingContentWhileFollowing_keepsTheTrueBottomVisible', 'bottomButton_reenablesFollowingForLateContentGrowth'):
    check_instrumentation(folder / 'chat-bottom-scroll-instrumentation.txt', 'dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatBottomAutoScrollerInstrumentedTest', test)
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
