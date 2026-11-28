#!/usr/bin/env python3
"""Prepare pinned full-model inputs for the actual native/JNI host qualification.

This prepares bytes only. It never grants model, Android or device qualification.
The independent lifecycle runner must build and execute the current native source.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import tempfile
import zipfile

from build_qualcomm_dispatch_candidate import checked_download, digest
from prepare_qnn_host_probe import DEFAULT_MANIFEST as QNN_HOST_MANIFEST, prepare as prepare_qnn
from prepare_streaming_asr_host import native_preparer, prepare as prepare_asr

ROOT = Path(__file__).resolve().parents[1]
QNN_SDK_MANIFEST = ROOT / "docs/audits/qnn-runtime-candidate.json"
DEPENDENCIES = (
    {"file": "sherpa-onnx-38016c6e0622e90bf417c35e8b47e0eb805af417.tar.gz",
     "url": "https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/38016c6e0622e90bf417c35e8b47e0eb805af417",
     "bytes": 12292551, "sha256": "39416f4bcb4e0535908bda03991c0a5580214617135057f653124b45eb783086"},
    {"file": "flite-e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88.tar.gz",
     "url": "https://codeload.github.com/festvox/flite/tar.gz/e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88",
     "bytes": 20236683, "sha256": "1eebf476797cea10b4706cfbb9db9ba34f7b8ccb1fe7e0069b0b7528e0b0be46"},
    {"file": "utf8proc-v2.12.0.tar.gz",
     "url": "https://codeload.github.com/JuliaStrings/utf8proc/tar.gz/refs/tags/v2.12.0",
     "bytes": 207976, "sha256": "f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111"},
)
ORT_ROOT = "onnxruntime-linux-x64-glibc2_28-Release-1.31.0"
ORT = {
    "url": f"https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.31.0/{ORT_ROOT}.zip",
    "bytes": 11738264, "sha256": "c29ac82b45d958bbff08bc3d36057403831916b43a3475da24152bf6a11a6f86",
    "publisherAsset": "https://api.github.com/repos/csukuangfj/onnxruntime-libs/releases/assets/677762408",
}
MODEL_ROOT = "kokoro-int8-multi-lang-v1_0"
MODEL = {
    "url": f"https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{MODEL_ROOT}.tar.bz2",
    "bytes": 132353410, "sha256": "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653",
}
MODEL_FILES = {
    "model.int8.onnx": {"bytes": 114202993, "sha256": "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e"},
    "voices.bin": {"bytes": 28200960, "sha256": "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"},
    "tokens.txt": {"bytes": 687, "sha256": "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"},
    "lexicon-us-en.txt": {"bytes": 5956885, "sha256": "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"},
    "lexicon-gb-en.txt": {"bytes": 6366635, "sha256": "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7"},
    "LICENSE": {"bytes": 11358, "sha256": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"},
}


def safe_member(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError("Unsafe archive member path")
    return path


def extract_model(archive, output, expected=MODEL_FILES):
    """Stream only the independently pinned production model/frontend files."""
    seen = set()
    with tarfile.open(archive, "r|bz2") as package:
        for count, member in enumerate(package, 1):
            if count > 4096:
                raise ValueError("Model archive member count exceeds its bound")
            relative = safe_member(member.name)
            if relative.parts[:1] != (MODEL_ROOT,) or len(relative.parts) != 2:
                continue
            name = relative.name
            if name not in expected:
                continue
            record = expected[name]
            if not member.isfile() or member.issparse() or member.size != record["bytes"] or name in seen:
                raise ValueError("Invalid or duplicate pinned model member")
            with package.extractfile(member) as source, (output / name).open("xb") as target:
                shutil.copyfileobj(source, target, 64 * 1024)
            if digest(output / name) != record["sha256"]:
                raise ValueError("Extracted model input differs from the production pin")
            seen.add(name)
    if seen != set(expected):
        raise ValueError("Pinned model archive is incomplete")


def extract_ort(archive, output):
    required = {"VERSION_NUMBER", "include/onnxruntime_c_api.h", "include/onnxruntime_cxx_api.h",
                "include/onnxruntime_cxx_inline.h", "include/onnxruntime_ep_c_api.h", "lib/libonnxruntime.so"}
    seen, size = set(), 0
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        if len(entries) > 128:
            raise ValueError("ORT archive member count exceeds its bound")
        for member in entries:
            relative = safe_member(member.filename)
            if relative.parts[:1] != (ORT_ROOT,):
                raise ValueError("ORT archive root differs from the pinned release")
            if member.is_dir():
                continue
            name = PurePosixPath(*relative.parts[1:])
            if name.as_posix() not in required and not (len(name.parts) == 2 and name.parts[0] == "include" and name.suffix == ".h") and name.name not in {"LICENSE", "ThirdPartyNotices.txt"}:
                continue
            if stat.S_ISLNK(member.external_attr >> 16) or name.as_posix() in seen:
                raise ValueError("Invalid or duplicate ORT archive member")
            size += member.file_size
            if member.file_size > 64 * 1024**2 or size > 96 * 1024**2:
                raise ValueError("ORT expanded files exceed their bound")
            destination = output.joinpath(*name.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with package.open(member) as source, destination.open("xb") as target:
                shutil.copyfileobj(source, target, 64 * 1024)
            seen.add(name.as_posix())
    if not required.issubset(seen) or (output / "VERSION_NUMBER").read_text().strip() != "1.31.0":
        raise ValueError("ORT headers/library/version are incomplete or mismatched")


def prepare(output):
    if output.exists():
        raise ValueError("Choose a new native qualification input directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".kokoro-native-", dir=output.parent) as temporary:
        stage = Path(temporary)
        dependencies = stage / "dependencies"
        dependencies.mkdir()
        for record in DEPENDENCIES:
            checked_download(record, dependencies / record["file"])
        # Host shared libraries and the SDK header tree have distinct publisher
        # manifests. The wheel manifest contains no SDK/header identity.
        native_preparer().prepare(dependencies, QNN_SDK_MANIFEST)
        prepare_asr(stage / "asr-packs")
        ort, pack = stage / "ort", stage / "pack"
        ort.mkdir()
        pack.mkdir()
        ort_archive = checked_download(ORT, stage / "ort.zip")
        extract_ort(ort_archive, ort)
        ort_archive.unlink()
        model_archive = checked_download(MODEL, stage / "model.tar.bz2")
        extract_model(model_archive, pack)
        model_archive.unlink()
        prepare_qnn(QNN_HOST_MANIFEST, stage / "qnn")
        receipt = {
            "schemaVersion": 1, "scope": "PINNED_FULL_MODEL_NATIVE_HOST_INPUTS_ONLY",
            "physicalDeviceQualified": False, "modelExecuted": False,
            "dependencies": DEPENDENCIES, "ort": ORT, "model": MODEL, "modelFiles": MODEL_FILES,
            "qnnPublisherManifestSha256": digest(QNN_HOST_MANIFEST),
            "qnnSdkManifestSha256": digest(QNN_SDK_MANIFEST),
            "preparedFiles": {p.relative_to(stage).as_posix(): {"bytes": p.stat().st_size, "sha256": digest(p)}
                              for p in sorted(stage.rglob("*")) if p.is_file()},
            "preparerSha256": digest(Path(__file__)),
        }
        (stage / "inputs.json").write_text(json.dumps(receipt, indent=2) + "\n")
        stage.rename(output)
    print("Prepared pinned Kokoro and streaming ASR JNI inputs; the required model execution gates have not run.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    prepare(parser.parse_args().output_dir.resolve())
