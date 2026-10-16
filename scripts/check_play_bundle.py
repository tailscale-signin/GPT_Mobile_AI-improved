#!/usr/bin/env python3
"""Fail closed on Android App Bundle packaging and Google Play native ELF prerequisites.

This checks the artifact actually uploaded to Play, not only standalone APKs.
It cannot replace running the resulting APKs on a 16 KB page-size device.
"""

import argparse
from pathlib import Path
import re
import struct
from zipfile import ZipFile

PLAY_TARGET_SDK_MIN = 36  # New Android app submissions/updates since 2026-08-31.
EXPECTED_ABIS = {"arm64-v8a": 183, "x86_64": 62}


def check_gradle_config(path: Path) -> None:
    content = path.read_text(encoding="utf-8")

    def value(key: str) -> int:
        match = re.search(rf"\b{key}\s*=\s*(\d+)\b", content)
        if not match:
            raise ValueError(f"Missing numeric {key} in {path}")
        return int(match.group(1))

    target = value("targetSdk")
    compile_sdk = value("compileSdk")
    minimum = value("minSdk")
    code = value("versionCode")
    if target < PLAY_TARGET_SDK_MIN or compile_sdk < target:
        raise ValueError(f"Google Play requires target SDK >= {PLAY_TARGET_SDK_MIN}; got target {target}, compile {compile_sdk}")
    if not 1 <= minimum <= target or not 1 <= code < 2100000000:
        raise ValueError(f"Invalid minSdk {minimum} or versionCode {code}")
    if 'applicationId = if (geniexRuntime) "dev.melo.gptmobile.improved.geniex" else "dev.melo.gptmobile.improved"' not in content:
        raise ValueError("Regular release application ID changed; would break upgrades")
    if not re.search(r'\bversionName\s*=\s*"\d+\.\d+\.\d+\.\d+"', content):
        raise ValueError("Release versionName is missing or malformed")
    if not re.search(r'useLegacyPackaging\s*=\s*true', content):
        raise ValueError("QNN host/DSP runtime currently requires extracted native libraries")
    print(f"Google Play SDK/identity configuration: min={minimum}, target={target}, compile={compile_sdk}, code={code}")


def check_library(name: str, content: bytes, abi: str) -> bool:
    if len(content) < 20 or content[:4] != b"\x7fELF":
        raise ValueError(f"{name}: not a valid ELF binary")
    machine = struct.unpack_from("<H", content, 18)[0]
    # Qualcomm Hexagon DSP skeletons can be ELF32. Skip only these specifically
    # named DSP payloads before asserting the Android host ELF64 format.
    if abi == "arm64-v8a" and machine != EXPECTED_ABIS[abi] and re.fullmatch(r"libQnnHtpV\d+Skel\.so", Path(name).name):
        return False
    if len(content) < 64 or content[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{name}: expected little-endian ELF64")
    if machine != EXPECTED_ABIS[abi]:
        raise ValueError(f"{name}: unexpected host machine {machine}, expected {EXPECTED_ABIS[abi]}")
    header_offset = struct.unpack_from("<Q", content, 32)[0]
    header_size, count = struct.unpack_from("<HH", content, 54)
    if header_size < 56 or count == 0 or header_offset + header_size * count > len(content):
        raise ValueError(f"{name}: invalid program header table")
    loaded = 0
    for i in range(count):
        offset = header_offset + header_size * i
        if struct.unpack_from("<I", content, offset)[0] != 1:  # PT_LOAD
            continue
        loaded += 1
        file_offset, virt_addr = struct.unpack_from("<QQ", content, offset + 8)
        alignment = struct.unpack_from("<Q", content, offset + 48)[0]
        if alignment < 16384 or alignment & (alignment - 1):
            raise ValueError(f"{name}: PT_LOAD alignment {alignment} is less than 16 KB")
        if file_offset % 16384 != virt_addr % 16384:
            raise ValueError(f"{name}: PT_LOAD offset and address violate 16 KB alignment")
    if loaded == 0:
        raise ValueError(f"{name}: ELF has no loadable segments")
    return True


def check_bundle(path: Path) -> None:
    with ZipFile(path) as bundle:
        entries = bundle.namelist()
        if len(entries) != len(set(entries)):
            raise ValueError(f"{path}: duplicate AAB ZIP entries")
        required = {"BundleConfig.pb", "base/manifest/AndroidManifest.xml"}
        if not required.issubset(entries):
            raise ValueError(f"{path}: missing AAB base manifest or BundleConfig.pb")
        abi_found = set()
        host_libs = 0
        dsp_skeletons = 0
        for name in entries:
            match = re.fullmatch(r"base/lib/([^/]+)/([^/]+\.so)", name)
            if not match:
                continue
            abi = match.group(1)
            if abi not in EXPECTED_ABIS:
                raise ValueError(f"{name}: unsupported or 32-bit ABI for normal Play distribution")
            abi_found.add(abi)
            if check_library(name, bundle.read(name), abi):
                host_libs += 1
            else:
                dsp_skeletons += 1
        if abi_found != set(EXPECTED_ABIS) or host_libs == 0:
            raise ValueError(f"{path}: expected arm64-v8a and x86_64 host libraries; found {sorted(abi_found)}")
        print(f"{path.name}: {host_libs} host shared libraries have 16 KB aligned PT_LOAD segments; {dsp_skeletons} Qualcomm DSP skeletons excluded")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundles", type=Path, nargs="+")
    parser.add_argument("--gradle-build", type=Path, default=Path("app/build.gradle.kts"))
    options = parser.parse_args()
    check_gradle_config(options.gradle_build)
    for path in options.bundles:
        check_bundle(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
