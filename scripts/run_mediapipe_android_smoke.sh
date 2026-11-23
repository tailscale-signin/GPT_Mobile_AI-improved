#!/usr/bin/env bash
# Run one exact engine against the single minified app/test pair built by CI.
set -euo pipefail

speech_engine=${1:?Pass kokoro, phonon or pocket}
case "$speech_engine" in
  kokoro|phonon|pocket) ;;
  *) echo "Unsupported speech smoke engine: $speech_engine" >&2; exit 2 ;;
esac
speech_models=${MEDIAPIPE_SMOKE_MODEL_DIR:?Set the verified model directory}
speech_apks=${MEDIAPIPE_SMOKE_APK_DIR:?Set the verified APK directory}
speech_evidence=${MEDIAPIPE_SMOKE_EVIDENCE_DIR:-app/build/native-speech-smoke/$speech_engine}
speech_package=dev.melo.gptmobile.improved.nativesmoke
speech_component=$speech_package.test/androidx.test.runner.AndroidJUnitRunner
speech_test=dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechNativeSmokeTest#pinnedCheckpointStreamsAndCancelsThroughThePackagedAndroidJni
speech_revision=$(git rev-parse HEAD)
speech_run_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
mkdir -p "$speech_evidence"
rm -f "$speech_evidence/status.json" "$speech_evidence/instrumentation.txt"
python3 - "$speech_evidence" "$speech_engine" "$speech_revision" "$speech_run_id" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1], 'invocation.json').write_text(json.dumps({
    'engine': sys.argv[2], 'source_revision': sys.argv[3], 'run_id': sys.argv[4],
    'status': 'STARTED', 'scope': 'android_x86_64_cpu_jni_checkpoint_smoke',
}, indent=2) + '\n')
PY

# The artifact's source/hash identity is checked before installation and again
# when accepting evidence. The phone separately hashes the actual model bytes.
speech_model_name=$(python3 - "$speech_apks" "$speech_revision" "$speech_evidence/provisioning.json" "$speech_engine" <<'PY'
from pathlib import Path
import sys
sys.path.insert(0, 'scripts')
from check_mediapipe_android_smoke import verify_apks, verify_provisioning
from smoke_mediapipe_speech import MODELS
verify_apks(Path(sys.argv[1]), sys.argv[2])
verify_provisioning(Path(sys.argv[3]), sys.argv[4])
print(MODELS[sys.argv[4]]['file_name'])
PY
)
test -f "$speech_models/$speech_model_name"
adb install -r "$speech_apks/app-x86_64.apk"
adb install -r "$speech_apks/test.apk"
adb shell pm list instrumentation > "$speech_evidence/instrumentations.txt"
adb shell getprop > "$speech_evidence/device-properties.txt"
adb logcat -b all -c
adb logcat -b all -v threadtime > "$speech_evidence/logcat.txt" &
speech_logcat_pid=$!
speech_device_model=/data/local/tmp/mediapipe-speech/$speech_model_name
speech_cleanup() {
  # Cleanup cannot turn a failed test into a pass. Preserve the caller's status.
  speech_exit=$?
  trap - EXIT
  timeout 15s adb shell am force-stop "$speech_package" >/dev/null 2>&1 || true
  timeout 15s adb shell rm -f "$speech_device_model" >/dev/null 2>&1 || true
  kill "$speech_logcat_pid" 2>/dev/null || true
  wait "$speech_logcat_pid" 2>/dev/null || true
  exit "$speech_exit"
}
trap speech_cleanup EXIT
adb shell mkdir -p /data/local/tmp/mediapipe-speech
adb push "$speech_models/$speech_model_name" "$speech_device_model"

# JUnit owns a 300-second test deadline. The outer deadline leaves one minute
# for reporting/retirement, then kills a stuck adb client without accepting it.
set +e
timeout --kill-after=10s 360s adb shell am instrument -w -r \
  -e class "$speech_test" \
  -e mediapipeSmokeEngine "$speech_engine" \
  -e mediapipeSmokeRunId "$speech_run_id" \
  "$speech_component" 2>&1 | tee "$speech_evidence/instrumentation.txt"
speech_native_status=${PIPESTATUS[0]}
set -e
python3 scripts/check_mediapipe_android_smoke.py \
  --engine "$speech_engine" \
  --instrumentation-output "$speech_evidence/instrumentation.txt" \
  --provisioning-receipt "$speech_evidence/provisioning.json" \
  --apk-dir "$speech_apks" --source-revision "$speech_revision" \
  --run-id "$speech_run_id" --process-exit "$speech_native_status" \
  --status-output "$speech_evidence/status.json"
