#!/usr/bin/env python3
"""Prepare the pinned native ASR archives and audited QNN headers for offline builds."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / "streaming_asr_dependencies.json"


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            result.update(data)
    return result.hexdigest()


def checked_download(record, target):
    if target.is_file() and target.stat().st_size == record["bytes"] and digest(target) == record["sha256"]:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="asr-dependency-", dir=target.parent) as temporary:
        path = Path(temporary) / "download"
        with path.open("wb") as output, urllib.request.urlopen(record["url"], timeout=60) as source:
            received = 0
            while data := source.read(64 * 1024):
                received += len(data)
                if received > record["bytes"]:
                    raise ValueError("Native ASR dependency exceeded its pinned byte count")
                output.write(data)
        if received != record["bytes"] or digest(path) != record["sha256"]:
            raise ValueError("Native ASR dependency differs from the immutable pin")
        # Same-filesystem replacement keeps partial bytes invisible to readers.
        path.replace(target)


def prepare(output, qnn_manifest):
    records = json.loads(MANIFEST.read_text())["dependencies"]
    for record in records:
        if Path(record["file"]).name != record["file"]:
            raise ValueError("Invalid ASR dependency filename")
        checked_download(record, output / record["file"])
    spec = importlib.util.spec_from_file_location("asr_qnn_headers", HERE / "prepare_asr_qnn_headers.py")
    headers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(headers)
    version = json.loads(qnn_manifest.read_text())["dispatchBuild"]["qairtSdkVersion"]
    headers.prepare(qnn_manifest, output / ("asr-qairt-" + version))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--qnn-manifest", required=True, type=Path)
    arguments = parser.parse_args()
    prepare(arguments.output.resolve(), arguments.qnn_manifest.resolve())
