#!/usr/bin/env bash
# Run the minified app/test APKs through Android's observable test protocol.
set -euo pipefail

smoke_evidence=app/build/native-memory-smoke
mkdir -p "$smoke_evidence"
adb install -r app/build/outputs/apk/nativeSmoke/*x86_64*.apk
adb install -r app/build/outputs/apk/androidTest/nativeSmoke/*.apk
adb shell pm list instrumentation > "$smoke_evidence/instrumentations.txt"
adb logcat -b all -c
adb logcat -b all -v threadtime > "$smoke_evidence/logcat.txt" &
smoke_logcat_pid=$!
trap 'kill "$smoke_logcat_pid" 2>/dev/null || true' EXIT

# am instrument can exit zero after an assertion failure or process abort.
# Require the named test's actual start, success and final result packets.
timeout 180s adb shell am instrument -w -r \
  -e class dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemoryInstrumentedTest \
  dev.melo.gptmobile.improved.nativesmoke.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$smoke_evidence/instrumentation.txt"
python3 scripts/check_native_memory_smoke.py --instrumentation-output "$smoke_evidence/instrumentation.txt"

adb push /tmp/gguf-fixture/stories15M-chatml.gguf /data/local/tmp/stories15M-chatml.gguf
adb push /tmp/gguf-fixture/stories15M-jinja.gguf /data/local/tmp/stories15M-jinja.gguf
timeout 240s adb shell am instrument -w -r \
  -e class dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeInstrumentedTest \
  dev.melo.gptmobile.improved.nativesmoke.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$smoke_evidence/gguf-instrumentation.txt"
python3 scripts/check_gguf_android_smoke.py "$smoke_evidence/gguf-instrumentation.txt"
adb shell rm /data/local/tmp/stories15M-chatml.gguf /data/local/tmp/stories15M-jinja.gguf
