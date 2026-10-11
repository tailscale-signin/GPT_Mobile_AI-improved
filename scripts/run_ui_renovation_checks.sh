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
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.package=dev.chungjungsoo.gptmobile.presentation \
  --no-daemon

# A green runner that silently discovers only part of the suite is not a pass.
python3 - <<'PYTHON'
from pathlib import Path
import xml.etree.ElementTree as ET
required = {
    "RenovationInstrumentedTest": 5,
    "RenovationVisualInstrumentedTest": 3,
    "ComposerControlsInstrumentedTest": 1,
    "ComposerVisualReviewInstrumentedTest": 4,
    "ChatBottomAutoScrollerInstrumentedTest": 4,
}
counts = {name: 0 for name in required}
for report in Path("app/build/outputs/androidTest-results/connected/debug").glob("TEST-*.xml"):
    for case in ET.parse(report).iter("testcase"):
        name = case.attrib.get("classname", "").rsplit(".", 1)[-1]
        if name in counts and case.find("skipped") is None:
            counts[name] += 1
missing = {name: (counts[name], expected) for name, expected in required.items() if counts[name] < expected}
if missing:
    raise SystemExit(f"Incomplete UI device execution (actual, minimum): {missing}")
print(f"Verified UI device test coverage: {counts}")
PYTHON
