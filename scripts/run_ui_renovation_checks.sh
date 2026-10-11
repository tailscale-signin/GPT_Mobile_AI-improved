#!/usr/bin/env bash
set -euo pipefail

# Capture while emulator-runner still owns the live emulator. Preserve the original
# failing test status; missing visual evidence is itself a failure after green tests.
capture_evidence() {
  local test_status=$?
  trap - EXIT
  mkdir -p app/build/ui-renovation-evidence
  if adb exec-out run-as dev.melo.gptmobile.improved tar -cf - files/ui-review > app/build/ui-renovation-evidence/screenshots.tar; then
    if [[ "$test_status" -eq 0 ]]; then
      tar -tf app/build/ui-renovation-evidence/screenshots.tar > app/build/ui-renovation-evidence/screenshot-manifest.txt
      test -s app/build/ui-renovation-evidence/screenshot-manifest.txt || exit 1
    fi
  elif [[ "$test_status" -eq 0 ]]; then
    echo 'Device tests passed, but screenshot collection failed.' >&2
    exit 1
  fi
  exit "$test_status"
}
trap capture_evidence EXIT

./gradlew :app:connectedDebugAndroidTest -PenableAbiSplits=false \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.presentation.renovation.RenovationInstrumentedTest,dev.chungjungsoo.gptmobile.presentation.renovation.RenovationVisualInstrumentedTest,dev.chungjungsoo.gptmobile.presentation.ui.chat.ComposerControlsInstrumentedTest,dev.chungjungsoo.gptmobile.presentation.ui.chat.ComposerVisualReviewInstrumentedTest \
  --no-daemon
