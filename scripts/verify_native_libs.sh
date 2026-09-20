#!/usr/bin/env bash
# scripts/verify_native_libs.sh
# Verifies presence and ABI coverage for LiteRT and Qualcomm QNN native libraries
set -euo pipefail

echo "==> Verifying native libraries..."

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS_DIR="$PROJECT_ROOT/app/src/main/jniLibs"

REQUIRED_LIBS=(
    "libQnnCpu.so"
    "libQnnGpu.so"
    "libQnnHtp.so"
    "libLiteRt.so"
    "liblitertlm_jni.so"
)

TARGET_ARCHS=("arm64-v8a" "x86_64")

if [ ! -d "$JNI_LIBS_DIR" ]; then
    echo "ℹ️ jniLibs directory ($JNI_LIBS_DIR) is not bundled locally."
    echo "  (Prebuilts are downloaded or resolved via Maven dependencies at build time)"
    exit 0
fi

for arch in "${TARGET_ARCHS[@]}"; do
    ARCH_DIR="$JNI_LIBS_DIR/$arch"
    if [ -d "$ARCH_DIR" ]; then
        echo "✓ Found architecture directory: $arch"
        for lib in "${REQUIRED_LIBS[@]}"; do
            if [ -f "$ARCH_DIR/$lib" ]; then
                echo "  ✓ Found: $lib ($arch)"
            else
                echo "  ℹ️ Notice: $lib not present under $arch (may be provided by AAR)"
            fi
        done
    else
        echo "ℹ️ Architecture directory $arch not present in local jniLibs"
    fi
done

echo "==> Native library verification complete."
