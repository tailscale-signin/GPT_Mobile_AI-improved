#!/usr/bin/env python3
"""Measure packaged payloads before considering optional-feature extraction."""
import argparse
import json
import zipfile
from pathlib import Path


def category(name):
    value = name.lower()
    if any(v in value for v in ('gguf', 'llama', 'ggml')):
        return 'gguf-runtime'
    if any(v in value for v in ('qnn', 'qairt', 'htp', 'qualcomm')):
        return 'qualcomm-runtime'
    if any(v in value for v in ('litert', 'mediapipe', 'tensorflow')):
        return 'litert-and-mediapipe'
    if any(v in value for v in ('sentence_encoder', 'embedding')):
        return 'memory-model-data'
    if 'maplibre' in value:
        return 'map-runtime'
    if value.endswith('.dex'):
        return 'shared-dex-not-feature-attributable'
    return 'other'


def inventory(path):
    totals = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            item = totals.setdefault(category(entry.filename), {'compressedBytes': 0, 'uncompressedBytes': 0, 'entries': 0})
            item['compressedBytes'] += entry.compress_size
            item['uncompressedBytes'] += entry.file_size
            item['entries'] += 1
    return {'artifact': path.name, 'archiveBytes': path.stat().st_size, 'groups': totals}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps({'schema': 1, 'qualification': 'packaging inventory, not measured extraction savings', 'artifacts': [inventory(p) for p in args.artifacts]}, indent=2) + '\n')
