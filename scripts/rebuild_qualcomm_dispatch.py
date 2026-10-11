#!/usr/bin/env python3
"""Build the dispatch from pinned upstream sources; validate before replacing it.

Requires git, Bazel 7.6.1, Android NDK r28b and QAIRT 2.50.0.260828.
The supplied SDK root must contain include/QNN; no SDK is redistributed here.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

from check_local_runtime_apk import check_host_alignment

ROOT = Path(__file__).resolve().parents[1]
INPUTS = ROOT / "docs/audits/qualcomm-dispatch-inputs.json"
LIBRARY = "lib/arm64-v8a/libLiteRtDispatch_Qualcomm.so"


def run(*args, cwd=None, env=None):
    return subprocess.check_output(args, cwd=cwd, env=env, text=True).strip()


def verify_workspace(workspace, inputs):
    if run("git", "rev-parse", "HEAD", cwd=workspace) != inputs["litertlmCommit"]:
        raise ValueError("LiteRT-LM checkout does not match the pinned release commit")
    if run("git", "status", "--porcelain", "--untracked-files=no", cwd=workspace):
        raise ValueError("LiteRT-LM checkout has modified tracked source files")
    text = (workspace / "WORKSPACE").read_text()
    for name, expected in (("LITERT_REF", inputs["litertRevision"]),
                           ("LITERT_SHA256", inputs["litertArchiveSha256"])):
        match = re.search(rf'^{name}\s*=\s*"([^"]+)"', text, re.MULTILINE)
        if not match or match.group(1) != expected:
            raise ValueError(f"Upstream {name} differs from the audited release pin")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--qairt-sdk", required=True, type=Path)
    parser.add_argument("--bazel", default="bazel")
    parser.add_argument("--host-cc", default="clang", help="Host compiler used for flatc (not the Android target compiler)")
    parser.add_argument("--host-cxx", default="clang++")
    args = parser.parse_args()
    inputs = json.loads(INPUTS.read_text())
    workspace = args.workspace.resolve()
    verify_workspace(workspace, inputs)
    if f'Pkg.Revision = {inputs["ndkRevision"]}' not in (args.ndk / "source.properties").read_text():
        raise ValueError("Android NDK revision differs from the pinned toolchain")
    version = run(args.bazel, "version", cwd=workspace)
    if f'Build label: {inputs["bazelVersion"]}' not in version.splitlines():
        raise ValueError("Bazel version differs from the pinned toolchain")
    sdk = args.qairt_sdk.resolve()
    if sdk.name != inputs["qairtSdkVersion"] or not (sdk / "include/QNN/QnnInterface.h").is_file():
        raise ValueError("Expected the versioned QAIRT SDK root containing include/QNN/QnnInterface.h")
    manifest_path = ROOT / "docs/audits/local-runtime-native-libraries.json"
    manifest = json.loads(manifest_path.read_text())
    if manifest["litertlmVersion"] != inputs["litertlmVersion"] or manifest["qairtVersion"] != inputs["qairtVersion"]:
        raise ValueError("Runtime manifest versions differ from the dispatch build inputs")
    headers_digest = hashlib.sha256()
    for header in sorted((sdk / "include/QNN").rglob("*.h")):
        headers_digest.update(header.relative_to(sdk).as_posix().encode() + b"\0")
        headers_digest.update(header.read_bytes())
    if headers_digest.hexdigest() != inputs["qnnHeadersSha256"]:
        raise ValueError("QAIRT SDK headers differ from the audited dispatch build inputs")
    env = dict(os.environ, ANDROID_NDK_HOME=str(args.ndk.resolve()), LITERT_QAIRT_SDK=str(sdk))
    subprocess.run([
        args.bazel, "build", "--config=android_arm64",
        f"--repo_env=CC={args.host_cc}", f"--repo_env=CXX={args.host_cxx}",
        f"--action_env=CC={args.host_cc}", f"--action_env=CXX={args.host_cxx}",
        "--linkopt=-Wl,-z,max-page-size=16384", inputs["target"]
    ], cwd=workspace, env=env, check=True)
    output = workspace / "bazel-bin/external/litert/litert/vendors/qualcomm/dispatch/libLiteRtDispatch_Qualcomm.so"
    binary = output.read_bytes()
    if not check_host_alignment(LIBRARY, binary) or int.from_bytes(binary[18:20], "little") != 183:
        raise ValueError("Dispatch must be an ARM64 Android library")
    readelf = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    symbols = run(str(readelf), "--dyn-syms", "--wide", str(output))
    if not re.search(r'GLOBAL\s+DEFAULT\s+\d+\s+LiteRtDispatchGetApi\b', symbols):
        raise ValueError("Dispatch API entry point is not exported")
    dependencies = run(str(readelf), "--dynamic", str(output))
    if "libLiteRt.so" in dependencies or "libc++_shared.so" in dependencies:
        raise ValueError("Dispatch must not require a separate LiteRT or C++ shared runtime")
    digest = hashlib.sha256(binary).hexdigest()
    manifest["dispatchBuild"] = inputs
    manifest["libraries"][LIBRARY] = {
        "sha256": digest, "bytes": len(binary),
        "source": f'LiteRT {inputs["litertRevision"]} (LiteRT-LM {inputs["litertlmVersion"]} pin), built against QAIRT {inputs["qairtSdkVersion"]} headers with Android NDK r28b'
    }
    destination = ROOT / "app/src/main/jniLibs/arm64-v8a" / output.name
    shutil.copyfile(output, destination)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Rebuilt {destination.name}: {digest}; update the Markdown audit and verify the APK before committing.")


if __name__ == "__main__":
    main()
