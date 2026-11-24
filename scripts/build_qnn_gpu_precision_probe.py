#!/usr/bin/env python3
"""Compile the native GPU configuration probe with audited SDK headers and NDK.

Compilation is not device execution. This tool never changes app provider options.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

from build_qualcomm_dispatch_candidate import DEFAULT_INPUTS, digest, headers_digest, validate_inputs
from check_local_runtime_apk import ANDROID_SYSTEM_LIBRARIES, check_host_alignment, elf_needed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--qairt-sdk", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    candidate = json.loads(DEFAULT_INPUTS.read_text())
    pins = validate_inputs(candidate)
    ndk, sdk, output = args.ndk.resolve(), args.qairt_sdk.resolve(), args.output_dir.resolve()
    if output.exists():
        raise ValueError("Choose a new probe output directory")
    if f'Pkg.Revision = {pins["ndkRevision"]}' not in (ndk / "source.properties").read_text():
        raise ValueError("NDK differs from the audited candidate toolchain")
    if sdk.name != pins["qairtSdkVersion"] or headers_digest(sdk) != pins["qnnHeadersSha256"]:
        raise ValueError("SDK headers differ from the audited publisher archive")
    compiler = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang++"
    source = Path(__file__).resolve().parent / "native/qnn_gpu_precision_probe.cpp"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".gpu-probe-", dir=output.parent) as temporary:
        staging = Path(temporary)
        binary_path = staging / "qnn_gpu_precision_probe"
        command = [str(compiler), "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-static-libstdc++",
                   "-Wl,-z,max-page-size=16384", "-Wl,--strip-all", "-Wl,--build-id=sha1",
                   "-I" + str(sdk / "include/QNN"), str(source), "-ldl", "-o", str(binary_path)]
        subprocess.run(command, check=True)
        binary = binary_path.read_bytes()
        if not check_host_alignment(binary_path.name, binary) or int.from_bytes(binary[18:20], "little") != 183:
            raise ValueError("Probe must be an ARM64 Android executable with 16-KiB alignment")
        dependencies = elf_needed(binary)
        if dependencies - ANDROID_SYSTEM_LIBRARIES:
            raise ValueError("Probe has unexpected shared dependencies")
        receipt = {
            "schemaVersion": 1, "status": "COMPILED_NOT_EXECUTED", "sourceSha256": digest(source),
            "binarySha256": digest(binary_path), "bytes": len(binary), "androidApi": 28,
            "ndkRevision": pins["ndkRevision"], "qairtSdkVersion": pins["qairtSdkVersion"],
            "qnnHeadersSha256": pins["qnnHeadersSha256"], "neededLibraries": sorted(dependencies),
            "physicalDeviceQualified": False, "kokoroQualified": False,
            "precisionTransport": "QnnGraph_Config_t.customConfig -> QnnGpuGraph_CustomConfig_t.precision"
        }
        (staging / "probe-manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
        staging.rename(output)
    print(output / "probe-manifest.json")


if __name__ == "__main__":
    main()
