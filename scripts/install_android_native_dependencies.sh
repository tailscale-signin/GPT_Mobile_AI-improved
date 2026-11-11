#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_ENV:?GitHub Actions environment file is required}"
workspace=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/android-vulkan.XXXXXXXX")
prefix="$workspace/sdk"

fetch_headers() {
    local url=$1 digest=$2 destination=$3
    local archive="$destination/source.tar.gz"
    mkdir -p "$destination"
    curl --fail --silent --show-error --location --retry 3 --max-time 90 "$url" -o "$archive"
    printf '%s  %s\n' "$digest" "$archive" | sha256sum --check
    tar -xzf "$archive" --strip-components=1 -C "$destination"
    rm -- "$archive"
}

# Use the Khronos vulkan-sdk-1.4.321.0 header releases and the shader compiler
# and platform loader from the pinned Android NDK.
fetch_headers https://codeload.github.com/KhronosGroup/Vulkan-Headers/tar.gz/2cd90f9d20df57eac214c148f3aed885372ddcfe \
    e3865307e665f6a4ac4f7bbca7b3ba95e4191c7f8adf9f04b084ef514a2454e9 "$workspace/vulkan"
fetch_headers https://codeload.github.com/KhronosGroup/SPIRV-Headers/tar.gz/2a611a970fdbc41ac2e3e328802aed9985352dca \
    c2225a49c3d7efa5c4f4ce4a6b42081e6ea3daca376f3353d9d7c2722d77a28a "$workspace/spirv"

mkdir -p "$prefix/include"
cp -R "$workspace/vulkan/include/vulkan" "$workspace/vulkan/include/vk_video" "$prefix/include/"
cmake -S "$workspace/spirv" -B "$workspace/spirv-build" \
    -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON -DCMAKE_INSTALL_PREFIX="$prefix"
cmake --install "$workspace/spirv-build"

printf 'VULKAN_SDK=%s\nCMAKE_PREFIX_PATH=%s%s\n' \
    "$prefix" "$prefix" "${CMAKE_PREFIX_PATH:+:$CMAKE_PREFIX_PATH}" >> "$GITHUB_ENV"
