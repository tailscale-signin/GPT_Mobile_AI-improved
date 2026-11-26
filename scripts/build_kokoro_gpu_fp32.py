#!/usr/bin/env python3
"""Rebuild the packaged native Kokoro GPU backend using the audited QAIRT ABI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from build_qualcomm_dispatch_candidate import DEFAULT_INPUTS, headers_digest, validate_inputs
from check_local_runtime_apk import ANDROID_SYSTEM_LIBRARIES, check_host_alignment, elf_needed

NAME = "libkokoro_qnn_gpu_fp32.so"


def test_provider(source, includes, staging):
    test = source.with_name("kokoro_qnn_gpu_fp32_test.c")
    compiler = [os.environ.get("CC", "cc"), "-std=c11", "-Wall", "-Wextra", "-Werror", "-I", includes]
    subprocess.run([*compiler, "-fPIC", "-shared", source, "-ldl", "-pthread", "-o", staging / NAME], check=True)
    subprocess.run([*compiler, "-fPIC", "-shared", "-DTEST_GPU_PROVIDER", test, "-o", staging / "libQnnGpu.so"], check=True)
    runner = staging / "client"
    subprocess.run([*compiler, test, "-L", staging, "-lkokoro_qnn_gpu_fp32", "-o", runner], check=True)
    env = {**os.environ, "LD_LIBRARY_PATH": str(staging)}
    for control in ({}, {"TEST_WRONG_BACKEND": "1"}, {"TEST_AMBIGUOUS": "1"}):
        subprocess.run([runner], env={**env, **control}, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--qairt-sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    pins = validate_inputs(json.loads(DEFAULT_INPUTS.read_text()))
    if args.qairt_sdk.name != pins["qairtSdkVersion"] or headers_digest(args.qairt_sdk) != pins["qnnHeadersSha256"]:
        raise ValueError("QAIRT headers do not match the pinned GPU interface")
    if f'Pkg.Revision = {pins["ndkRevision"]}' not in (args.ndk / "source.properties").read_text().splitlines():
        raise ValueError("Android NDK revision differs from the production pin")
    tools = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    source = Path(__file__).parent.resolve() / "native/kokoro_qnn_gpu_fp32.c"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=args.output.parent) as directory:
        test_provider(source, args.qairt_sdk / "include/QNN", Path(directory).resolve())
        out = Path(directory) / NAME
        subprocess.run([tools / "aarch64-linux-android28-clang", "-std=c11", "-O2", "-fPIC", "-shared",
                        "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden", "-Wl,-z,max-page-size=16384",
                        "-Wl,--strip-all", "-Wl,--build-id=sha1", f"-Wl,-soname,{NAME}",
                        "-I", args.qairt_sdk / "include/QNN", source, "-ldl", "-o", out], check=True)
        binary = out.read_bytes()
        if elf_needed(binary) - ANDROID_SYSTEM_LIBRARIES:
            raise ValueError("Precision backend contains an unexpected dynamic dependency")
        check_host_alignment(NAME, binary)
        out.replace(args.output)
        print(f"{len(binary)} bytes; SHA-256 {hashlib.sha256(binary).hexdigest()}")


if __name__ == "__main__":
    main()
