#!/usr/bin/env python3
"""Build a verified QAIRT candidate without modifying production pins or libraries.

The output is a candidate library and an exact input/output receipt. Promoting it
requires the matching shared QNN package, native inventory and APK verification.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import zipfile

from check_local_runtime_apk import ANDROID_SYSTEM_LIBRARIES, check_host_alignment, elf_needed
from rebuild_qualcomm_dispatch import verify_workspace

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUTS = ROOT / "docs/audits/qnn-runtime-candidate.json"


def digest(path, algorithm="sha256"):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def checked_download(record, destination):
    destination = Path(destination)
    if not record["url"].startswith("https://") or not re.fullmatch(r"[0-9a-f]{64}", record["sha256"]):
        raise ValueError("Downloads require HTTPS and a pinned SHA-256")
    if destination.exists() and destination.stat().st_size == record.get("bytes", destination.stat().st_size) and digest(destination) == record["sha256"]:
        if not record.get("publisherSha1") or digest(destination, "sha1") == record["publisherSha1"]:
            return destination
    partial = destination.with_suffix(destination.suffix + ".part")
    try:
        count = 0
        with urllib.request.urlopen(record["url"], timeout=60) as response, partial.open("wb") as output:
            if not response.geturl().startswith("https://"):
                raise ValueError("Refusing a non-HTTPS redirect")
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > record.get("bytes", 4 * 1024**3):
                    raise ValueError("Download exceeds the pinned or bounded artifact size")
                output.write(chunk)
        if record.get("bytes", count) != count or digest(partial) != record["sha256"]:
            raise ValueError("Publisher artifact size or SHA-256 mismatch")
        if record.get("publisherSha1") and digest(partial, "sha1") != record["publisherSha1"]:
            raise ValueError("Artifact differs from the audited publisher SHA-1 sidecar")
        partial.replace(destination)
        return destination
    finally:
        partial.unlink(missing_ok=True)


def extract_sdk_headers(archive, output, sdk_version):
    prefix = f"qairt/{sdk_version}/"
    count = 0
    with zipfile.ZipFile(archive) as package:
        for entry in package.infolist():
            if entry.is_dir() or not entry.filename.startswith(prefix):
                continue
            relative = PurePosixPath(entry.filename.removeprefix(prefix))
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Invalid SDK archive path")
            if relative.parts[0] != "include":
                continue
            if stat.S_ISLNK(entry.external_attr >> 16) or entry.file_size > 32 * 1024**2:
                raise ValueError("Invalid SDK header entry")
            destination = output.joinpath(*relative.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with package.open(entry) as source, destination.open("wb") as target:
                shutil.copyfileobj(source, target, 64 * 1024)
            count += 1
    if count == 0 or not (output / "include/QNN/QnnInterface.h").is_file():
        raise ValueError("SDK archive did not contain the expected QNN headers")


def headers_digest(sdk):
    result = hashlib.sha256()
    for header in sorted((sdk / "include/QNN").rglob("*.h")):
        result.update(header.relative_to(sdk).as_posix().encode() + b"\0")
        result.update(header.read_bytes())
    return result.hexdigest()


def validate_inputs(candidate):
    if candidate.get("schemaVersion") != 1 or candidate.get("productionPinsChanged") is not False:
        raise ValueError("Expected an isolated, unpromoted candidate manifest")
    pins = candidate["dispatchBuild"]
    if pins["qnnHeadersSha256"] != candidate["qairtSdk"]["qnnHeadersSha256"]:
        raise ValueError("SDK header identities differ")
    if not pins["qairtSdkVersion"].startswith(pins["qairtVersion"] + "."):
        raise ValueError("SDK and runtime versions differ")
    for field in ("litertlmCommit", "litertRevision"):
        if not re.fullmatch(r"[0-9a-f]{40}", pins[field]):
            raise ValueError("Source inputs must be immutable Git commits")
    for field in ("litertArchiveSha256", "qnnHeadersSha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", pins[field]):
            raise ValueError("Source inputs require exact SHA-256 identities")
    return pins


def run(*arguments, cwd=None, env=None):
    subprocess.run(arguments, cwd=cwd, env=env, check=True)


def capture(*arguments, cwd=None):
    return subprocess.check_output(arguments, cwd=cwd, text=True).strip()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, default=DEFAULT_INPUTS)
    parser.add_argument("--work-dir", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--check-inputs-only", action="store_true")
    args = parser.parse_args(argv)
    candidate = json.loads(args.inputs.read_text())
    pins = validate_inputs(candidate)
    if args.check_inputs_only:
        print("Candidate input identities are internally consistent; no build qualification claimed.")
        return
    if not args.work_dir or not args.output_dir or not args.ndk or not 1 <= args.jobs <= 8:
        parser.error("Build requires --work-dir, --output-dir, --ndk and one to eight jobs")
    work, output, ndk = args.work_dir.resolve(), args.output_dir.resolve(), args.ndk.resolve()
    work.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise ValueError("Choose a new candidate output directory")
    if f'Pkg.Revision = {pins["ndkRevision"]}' not in (ndk / "source.properties").read_text():
        raise ValueError("NDK differs from the audited candidate toolchain")
    bazel = checked_download(candidate["bazel"], work / "bazel")
    bazel.chmod(0o755)
    sdk = work / "qairt" / pins["qairtSdkVersion"]
    if not sdk.is_dir() or headers_digest(sdk) != pins["qnnHeadersSha256"]:
        archive = checked_download(candidate["qairtSdk"], work / "qairt.zip")
        sdk.mkdir(parents=True, exist_ok=True)
        extract_sdk_headers(archive, sdk, pins["qairtSdkVersion"])
        archive.unlink()  # The complete SDK is verified, but not redistributed or retained in CI.
    if headers_digest(sdk) != pins["qnnHeadersSha256"]:
        raise ValueError("SDK header digest differs from the audited publisher archive")
    for artifact in candidate["maven"]:
        checked_download(artifact, work / artifact["file"])
    workspace = work / "LiteRT-LM"
    if not (workspace / ".git").is_dir():
        workspace.mkdir(exist_ok=True)
        run("git", "init", "-q", str(workspace))
        run("git", "fetch", "--depth=1", "--filter=blob:none", "https://github.com/google-ai-edge/LiteRT-LM.git", pins["litertlmCommit"], cwd=workspace)
        run("git", "-c", "filter.lfs.smudge=", "-c", "filter.lfs.required=false", "checkout", "--detach", "FETCH_HEAD", cwd=workspace)
    verify_workspace(workspace, pins)
    startup = [str(bazel), f"--output_user_root={work / 'bazel-output'}", "--host_jvm_args=-Xmx2048m"]
    version = capture(*startup, "version", cwd=workspace)
    if f'Build label: {pins["bazelVersion"]}' not in version.splitlines():
        raise ValueError("Bazel differs from the audited toolchain")
    env = dict(os.environ, ANDROID_NDK_HOME=str(ndk), LITERT_QAIRT_SDK=str(sdk))
    run(*startup, "build", "--config=android_arm64", f"--jobs={args.jobs}",
        "--repo_env=CC=gcc", "--repo_env=CXX=g++", "--action_env=CC=gcc", "--action_env=CXX=g++",
        "--linkopt=-Wl,-z,max-page-size=16384", pins["target"], cwd=workspace, env=env)
    verify_workspace(workspace, pins)
    library = workspace / "bazel-bin/external/litert/litert/vendors/qualcomm/dispatch/libLiteRtDispatch_Qualcomm.so"
    binary = library.read_bytes()
    if not check_host_alignment(library.name, binary) or int.from_bytes(binary[18:20], "little") != 183:
        raise ValueError("Candidate must be an ARM64 Android library with 16-KiB LOAD alignment")
    dependencies = elf_needed(binary)
    if dependencies - ANDROID_SYSTEM_LIBRARIES:
        raise ValueError(f"Unexpected dynamic dependencies: {sorted(dependencies - ANDROID_SYSTEM_LIBRARIES)}")
    readelf = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    symbols = capture(str(readelf), "--dyn-syms", "--wide", str(library))
    if not re.search(r"GLOBAL\s+DEFAULT\s+\d+\s+LiteRtDispatchGetApi\b", symbols):
        raise ValueError("Candidate does not export the LiteRT dispatch API")
    receipt = {
        "schemaVersion": 1, "status": "STRUCTURAL_VERIFICATION_PASSED", "productionPinsChanged": False,
        "physicalDeviceQualified": False, "inputManifestSha256": digest(args.inputs), "dispatchBuild": pins,
        "qnnEpVersion": candidate["qnnEpVersion"], "library": library.name,
        "sha256": hashlib.sha256(binary).hexdigest(), "bytes": len(binary),
        "neededLibraries": sorted(dependencies), "gccVersion": capture("gcc", "--version").splitlines()[0],
        "repositoryCommit": capture("git", "rev-parse", "HEAD", cwd=ROOT),
        "maven": candidate["maven"],
        "qualification": "Source build, publisher inputs, ARM64, exported ABI, dependency closure and ELF alignment only. Android integration and compatible-device execution remain required."
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".dispatch-", dir=output.parent) as temporary:
        staged = Path(temporary)
        (staged / library.name).write_bytes(binary)
        (staged / "candidate-manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
        staged.rename(output)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
