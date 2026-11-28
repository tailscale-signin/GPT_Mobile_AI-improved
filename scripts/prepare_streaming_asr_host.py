#!/usr/bin/env python3
"""Prepare exact catalog CPU packs for the application's actual Sherpa JNI regression."""

import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "local-kokoro-runtime/src/main/cpp"
FIXTURES = ROOT / "local-kokoro-runtime/src/testHost/streaming_asr_fixtures.json"


def native_preparer():
    spec = importlib.util.spec_from_file_location("streaming_asr_dependencies", NATIVE / "prepare_streaming_asr_dependencies.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(output):
    dependency = native_preparer()
    for fixture in json.loads(FIXTURES.read_text())["fixtures"]:
        for record in fixture["files"]:
            relative = Path(record["file"])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Unsafe ASR fixture path")
            dependency.checked_download(record, output / fixture["id"] / relative)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    prepare(parser.parse_args().output.resolve())
