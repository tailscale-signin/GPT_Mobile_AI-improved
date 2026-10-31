#!/usr/bin/env python3
"""Compare native-rendered PNGs to explicitly reviewed, SHA-pinned baselines."""
import argparse
import binascii
import hashlib
import json
from pathlib import Path
import re
import struct
import zlib

MAX_PIXELS = 8_000_000


def png(path):
    if path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError('PNG exceeds the 32 MiB input limit')
    source = path.read_bytes()
    if source[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('Invalid PNG signature')
    offset, compressed, header, ended = 8, bytearray(), None, False
    while offset + 12 <= len(source):
        size = struct.unpack('>I', source[offset:offset + 4])[0]
        kind = source[offset + 4:offset + 8]
        end = offset + 8 + size
        if end + 4 > len(source) or binascii.crc32(source[offset + 4:end]) & 0xffffffff != struct.unpack('>I', source[end:end + 4])[0]:
            raise ValueError('Invalid PNG chunk or checksum')
        data = source[offset + 8:end]
        if kind == b'IHDR':
            if header is not None or size != 13:
                raise ValueError('Invalid PNG header')
            header = struct.unpack('>IIBBBBB', data)
        elif kind == b'IDAT':
            compressed.extend(data)
        elif kind == b'IEND':
            ended = True
            break
        offset = end + 4
    if header is None or not ended:
        raise ValueError('Incomplete PNG')
    width, height, depth, color, compression, filtering, interlace = header
    if not (0 < width * height <= MAX_PIXELS) or depth != 8 or color not in (2, 6) or (compression, filtering, interlace) != (0, 0, 0):
        raise ValueError('Only bounded, noninterlaced 8-bit RGB/RGBA PNGs are supported')
    channels = 4 if color == 6 else 3
    stride = width * channels
    limit = (stride + 1) * height
    decoder = zlib.decompressobj()
    raw = decoder.decompress(bytes(compressed), limit + 1)
    if len(raw) != limit or not decoder.eof or decoder.unused_data:
        raise ValueError('Invalid or oversized PNG pixel stream')
    previous, rgba = bytearray(stride), bytearray()
    for y in range(height):
        start = y * (stride + 1)
        mode, row = raw[start], bytearray(raw[start + 1:start + stride + 1])
        if mode > 4:
            raise ValueError('Invalid PNG filter')
        for x in range(stride):
            left, up, corner = (row[x - channels] if x >= channels else 0), previous[x], (previous[x - channels] if x >= channels else 0)
            estimate = left + up - corner
            distances = (abs(estimate - left), abs(estimate - up), abs(estimate - corner))
            paeth = (left, up, corner)[distances.index(min(distances))]
            predictor = (0, left, up, (left + up) // 2, paeth)[mode]
            row[x] = (row[x] + predictor) & 255
        if channels == 4:
            rgba.extend(row)
        else:
            for x in range(0, stride, 3):
                rgba.extend(row[x:x + 3])
                rgba.append(255)
        previous = row
    return width, height, rgba


def compare(baseline, candidate, tolerance=0):
    old_w, old_h, old = png(baseline)
    new_w, new_h, new = png(candidate)
    if (old_w, old_h) != (new_w, new_h):
        return {'status': 'DIMENSION_MISMATCH', 'baseline_size': [old_w, old_h], 'candidate_size': [new_w, new_h]}
    changed, total, peak = 0, 0, 0
    for offset in range(0, len(old), 4):
        delta = [abs(old[offset + i] - new[offset + i]) for i in range(4)]
        changed += max(delta) > tolerance
        total += sum(delta)
        peak = max(peak, *delta)
    return {'status': 'COMPARED', 'changed_pixels': changed, 'total_pixels': old_w * old_h,
            'changed_fraction': changed / (old_w * old_h), 'mean_channel_delta': total / len(old), 'max_channel_delta': peak}


def assess(candidates, manifest_path):
    rows = sorted(candidates.glob('*.png'))
    if not manifest_path.is_file():
        return {'schema_version': 1, 'status': 'NOT_QUALIFIED', 'reason': 'A reviewed baseline manifest is required', 'candidate_count': len(rows)}
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('schema_version') != 1 or not re.fullmatch('[a-f0-9]{40}', manifest.get('commit_sha', '')) or not manifest.get('reviewed_by') or not manifest.get('renderer'):
        raise ValueError('Baseline needs schema 1, source commit SHA, renderer identity, and reviewer')
    expected = manifest['images']
    tolerance, fraction = manifest.get('channel_tolerance', 0), manifest.get('max_changed_fraction', 0)
    if not isinstance(tolerance, int) or not 0 <= tolerance <= 32 or not 0 <= fraction <= 0.05 or not 0 < len(expected) <= 100:
        raise ValueError('Invalid visual comparison bounds')
    results = []
    for name, digest in sorted(expected.items()):
        if Path(name).name != name or not name.endswith('.png') or not re.fullmatch('[a-f0-9]{64}', digest):
            raise ValueError('Invalid baseline image name or digest')
        baseline, candidate = manifest_path.parent / name, candidates / name
        if not baseline.is_file() or hashlib.sha256(baseline.read_bytes()).hexdigest() != digest:
            raise ValueError('Baseline image does not match the reviewed digest')
        result = compare(baseline, candidate, tolerance) if candidate.is_file() else {'status': 'MISSING_CANDIDATE'}
        result['name'] = name
        result['passed'] = result['status'] == 'COMPARED' and result['changed_fraction'] <= fraction
        results.append(result)
    unexpected = sorted(p.name for p in rows if p.name not in expected)
    return {'schema_version': 1, 'status': 'PASS' if all(r['passed'] for r in results) and not unexpected else 'FAIL',
            'baseline_commit_sha': manifest['commit_sha'], 'renderer': manifest['renderer'],
            'results': results, 'unreviewed_candidates': unexpected}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidates', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--require-qualified', action='store_true')
    args = parser.parse_args()
    root = (Path.cwd() / 'docs/ui-baselines').resolve()
    if root not in args.baseline.resolve().parents:
        parser.error('Baseline manifest must be committed under docs/ui-baselines')
    report = assess(args.candidates, args.baseline)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f"Visual comparison: {report['status']}")
    return int(report['status'] == 'FAIL' or (args.require_qualified and report['status'] != 'PASS'))


if __name__ == '__main__':
    raise SystemExit(main())
