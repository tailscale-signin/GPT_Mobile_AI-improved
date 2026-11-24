#!/usr/bin/env bash
# Actual native policy and JNI receipt decoder controls. No device/PCM quality claim.
set -euo pipefail
cd "$(dirname "$0")/.."
speech_contract_dir="$(mktemp -d)"
trap 'rm -rf "$speech_contract_dir"' EXIT

timeout 90s g++ -std=c++17 -O2 -Wall -Wextra -Werror \
    local-kokoro-runtime/src/main/cpp/kokoro_measurement_test.cpp \
    -o "$speech_contract_dir/measurement-test"
timeout 30s "$speech_contract_dir/measurement-test"

timeout 90s g++ -std=c++17 -O2 -Wall -Wextra -Werror \
    local-kokoro-runtime/src/main/cpp/kokoro_context_cache_test.cpp \
    -o "$speech_contract_dir/context-cache-test"
timeout 30s "$speech_contract_dir/context-cache-test"

timeout 90s g++ -std=c++17 -O2 -Wall -Wextra -Werror \
    local-kokoro-runtime/src/main/cpp/kokoro_provider_profile_test.cpp \
    -o "$speech_contract_dir/provider-profile-test"
timeout 30s "$speech_contract_dir/provider-profile-test"

# Use the JDK compiler module so a JDK with no javac launcher on PATH is still usable.
timeout 30s java -Xmx256m --add-modules jdk.compiler com.sun.tools.javac.Main \
    -d "$speech_contract_dir/java" \
    local-kokoro-runtime/src/main/java/dev/melo/kokoro/TimedKokoro.java \
    local-kokoro-runtime/src/test/java/dev/melo/kokoro/KokoroEvidenceContractTest.java
timeout 30s java -Xmx128m -cp "$speech_contract_dir/java" dev.melo.kokoro.KokoroEvidenceContractTest
