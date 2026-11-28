#!/usr/bin/env bash
# Run an exact delegate against an ABI-matched minified app/test pair. CI defaults to x86_64 CPU.
# For a phone use the manual native-smoke workflow's minified-native-smoke-apks-arm64 artifact,
# set MEDIAPIPE_SMOKE_ABI=arm64-v8a and pass kokoro GPU or kokoro NPU.
set -euo pipefail

speech_engine=${1:?Pass kokoro, phonon, pocket or pocket-multilingual}
case "$speech_engine" in
  kokoro|phonon|pocket|pocket-multilingual) ;;
  *) echo "Unsupported speech smoke engine: $speech_engine" >&2; exit 2 ;;
esac
speech_delegate=${2:-CPU}
speech_abi=${MEDIAPIPE_SMOKE_ABI:-x86_64}
case "$speech_engine/$speech_delegate" in
  */CPU|kokoro/GPU|kokoro/NPU) ;;
  *) echo "Unsupported speech smoke route" >&2; exit 2 ;;
esac
case "$speech_abi" in
  x86_64|arm64-v8a) ;;
  *) echo "Unsupported speech smoke ABI" >&2; exit 2 ;;
esac
speech_models=${MEDIAPIPE_SMOKE_MODEL_DIR:?Set the verified model directory}
speech_apks=${MEDIAPIPE_SMOKE_APK_DIR:?Set the verified APK directory}
speech_default_evidence=app/build/native-speech-smoke/$speech_engine
if [[ "$speech_abi/$speech_delegate" != "x86_64/CPU" ]]; then
  speech_default_evidence="$speech_default_evidence/$speech_abi-${speech_delegate,,}"
fi
speech_evidence=${MEDIAPIPE_SMOKE_EVIDENCE_DIR:-$speech_default_evidence}
speech_package=dev.melo.gptmobile.improved.nativesmoke
speech_component=$speech_package.test/androidx.test.runner.AndroidJUnitRunner
speech_test=dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechNativeSmokeTest#pinnedCheckpointStreamsAndCancelsThroughThePackagedAndroidJni
speech_revision=$(git rev-parse HEAD)
speech_run_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
mkdir -p "$speech_evidence"
rm -f "$speech_evidence/status.json" "$speech_evidence/instrumentation.txt"
python3 - "$speech_evidence" "$speech_engine" "$speech_revision" "$speech_run_id" "$speech_abi" "$speech_delegate" <<'PY'
import json, pathlib, sys
pathlib.Path(sys.argv[1], 'invocation.json').write_text(json.dumps({
    'engine': sys.argv[2], 'source_revision': sys.argv[3], 'run_id': sys.argv[4],
    'status': 'STARTED', 'scope': f'android_{sys.argv[5]}_{sys.argv[6].lower()}_jni_checkpoint_smoke',
}, indent=2) + '\n')
PY

# The artifact's source/hash identity is checked before installation and again
# when accepting evidence. The phone separately hashes the actual model bytes.
speech_model_name=$(python3 - "$speech_apks" "$speech_revision" "$speech_evidence/provisioning.json" "$speech_engine" "$speech_abi" <<'PY'
from pathlib import Path
import sys
sys.path.insert(0, 'scripts')
from check_mediapipe_android_smoke import verify_apks, verify_provisioning
from smoke_mediapipe_speech import MODELS
verify_apks(Path(sys.argv[1]), sys.argv[2], sys.argv[5])
verify_provisioning(Path(sys.argv[3]), sys.argv[4])
print(MODELS[sys.argv[4]]['file_name'])
PY
)
test -f "$speech_models/$speech_model_name"
adb install -r "$speech_apks/app-$speech_abi.apk"
adb install -r "$speech_apks/test.apk"
adb shell pm list instrumentation "$speech_package" > "$speech_evidence/instrumentations.txt"
speech_logcat_pid=
if [[ "$speech_abi" == x86_64 ]]; then
  adb shell getprop > "$speech_evidence/device-properties.txt"
  adb logcat -b all -T 1 -v threadtime > "$speech_evidence/logcat.txt" &
  speech_logcat_pid=$!
else
  for property in ro.build.version.sdk ro.product.cpu.abilist ro.soc.manufacturer ro.soc.model; do
    printf '%s=' "$property"
    adb shell getprop "$property"
  done > "$speech_evidence/device-properties.txt"
fi
speech_device_directory=/data/local/tmp/mediapipe-speech/$speech_run_id
speech_device_model=$speech_device_directory/$speech_model_name
speech_cleanup() {
  # Cleanup cannot turn a failed test into a pass. Preserve the caller's status.
  speech_exit=$?
  trap - EXIT
  timeout 15s adb shell am force-stop "$speech_package" >/dev/null 2>&1 || true
  timeout 15s adb shell rm -f "$speech_device_model" >/dev/null 2>&1 || true
  timeout 15s adb shell rmdir "$speech_device_directory" >/dev/null 2>&1 || true
  if [[ -n "$speech_logcat_pid" ]]; then
    kill "$speech_logcat_pid" 2>/dev/null || true
    wait "$speech_logcat_pid" 2>/dev/null || true
  fi
  exit "$speech_exit"
}
trap speech_cleanup EXIT
adb shell mkdir -p "$speech_device_directory"
adb push "$speech_models/$speech_model_name" "$speech_device_model"

# JUnit owns a 300-second test deadline. The outer deadline leaves one minute
# for reporting/retirement, then kills a stuck adb client without accepting it.
set +e
timeout --kill-after=10s 360s adb shell am instrument -w -r \
  -e class "$speech_test" \
  -e mediapipeSmokeIsolatedModel true \
  -e mediapipeSmokeDelegate "$speech_delegate" \
  -e mediapipeSmokeEngine "$speech_engine" \
  -e mediapipeSmokeRunId "$speech_run_id" \
  "$speech_component" 2>&1 | tee "$speech_evidence/instrumentation.txt"
speech_native_status=${PIPESTATUS[0]}
set -e
python3 scripts/check_mediapipe_android_smoke.py \
  --engine "$speech_engine" --abi "$speech_abi" --delegate "$speech_delegate" \
  --instrumentation-output "$speech_evidence/instrumentation.txt" \
  --provisioning-receipt "$speech_evidence/provisioning.json" \
  --apk-dir "$speech_apks" --source-revision "$speech_revision" \
  --run-id "$speech_run_id" --process-exit "$speech_native_status" \
  --status-output "$speech_evidence/status.json"
