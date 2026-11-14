#!/usr/bin/env python3
"""Measure packaged chat/voice DEX definitions against ART's fixed code-unit limit.

Like check_research_dispatch_size.py, read code_item.insns_size, which counts
16-bit code units including instruction payloads, not decoded opcodes. ART warns
and declines compilation when this value is >= UINT16_MAX / 4 (16,383).
Source: https://android.googlesource.com/platform/art/+/refs/heads/main/compiler/compiler.cc
"""
import argparse
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import zipfile


ART_CODE_UNIT_LIMIT = 65535 // 4
CHAT = 'Ldev/chungjungsoo/gptmobile/presentation/ui/chat/'
VOICE = 'Ldev/chungjungsoo/gptmobile/presentation/ui/voice/'
SCREEN = CHAT + 'ChatScreenKt;'
MAX_APK_BYTES = 2 * 1024 * 1024 * 1024
MAX_DEX_BYTES = 128 * 1024 * 1024
MAX_TOTAL_DEX_BYTES = 1024 * 1024 * 1024
MAX_DEX_FILES = 128
MAX_NAME_BYTES = 65536
MAX_MEASURED_METHODS = 100000


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def dex_methods(data, dex_name):
    """Read definitions, rejecting malformed tables and truncated code items."""
    if len(data) < 112 or data[:8] not in {
        b'dex\n035\0', b'dex\n037\0', b'dex\n038\0', b'dex\n039\0', b'dex\n040\0',
    }:
        raise ValueError('Unsupported or truncated DEX header')

    def region(offset, size):
        if offset < 0 or size < 0 or offset + size > len(data):
            raise ValueError('DEX range extends outside the file')

    def word(offset):
        region(offset, 4)
        return struct.unpack_from('<I', data, offset)[0]

    def short(offset):
        region(offset, 2)
        return struct.unpack_from('<H', data, offset)[0]

    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            region(offset, 1)
            byte = data[offset]
            offset += 1
            if shift == 28 and byte > 15:
                raise ValueError('DEX ULEB128 exceeds 32 bits')
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError('Invalid DEX ULEB128')

    if word(32) != len(data) or word(36) != 112 or word(40) != 0x12345678:
        raise ValueError('Invalid DEX size, header or endianness')
    for header, width in ((56, 4), (64, 4), (72, 12), (80, 8), (88, 8), (96, 32)):
        count, offset = word(header), word(header + 4)
        if count and (offset < 112 or offset % 4):
            raise ValueError('Invalid DEX table offset')
        region(offset, count * width)
    if word(64) > 65535 or word(72) > 65535:
        raise ValueError('DEX type or prototype table exceeds the format limit')
    # Field and method indices can use every unsigned 16-bit value. Class
    # definitions are instead bounded by the number of distinct types.
    if word(80) > 65536 or word(88) > 65536 or word(96) > word(64):
        raise ValueError('DEX member or class table exceeds its identifier space')

    @lru_cache(maxsize=8192)
    def string(index):
        if index >= word(56):
            raise ValueError('Invalid DEX string index')
        offset = word(word(60) + index * 4)
        _, offset = uleb(offset)
        end = data.find(b'\0', offset, min(len(data), offset + MAX_NAME_BYTES))
        if end < 0:
            raise ValueError('Unterminated or oversized DEX identifier')
        # These package descriptors and Kotlin method names are ASCII. Other
        # legal MUTF-8 names can be represented lossily without changing size.
        return data[offset:end].decode('utf-8', errors='replace')

    def type_name(index):
        if index >= word(64):
            raise ValueError('Invalid DEX type index')
        return string(word(word(68) + index * 4))

    rows, owners = [], set()
    for index in range(word(96)):
        entry = word(100) + index * 32
        owner = type_name(word(entry))
        if not owner.startswith((CHAT, VOICE)):
            continue
        if owner in owners:
            raise ValueError('Duplicate chat/voice class definition')
        owners.add(owner)
        cursor = word(entry + 24)
        if not cursor:
            continue
        counts = []
        for _ in range(4):
            count, cursor = uleb(cursor)
            counts.append(count)
        if sum(counts[:2]) > word(80) or sum(counts[2:]) > word(88):
            raise ValueError('Class members exceed DEX identifier counts')
        for _ in range(counts[0] + counts[1]):
            _, cursor = uleb(cursor)
            _, cursor = uleb(cursor)
        for count in counts[2:]:
            method_index = 0
            for method_position in range(count):
                delta, cursor = uleb(cursor)
                if method_position and delta == 0:
                    raise ValueError('Duplicate DEX method definition')
                method_index += delta
                _, cursor = uleb(cursor)
                code_offset, cursor = uleb(cursor)
                if method_index >= word(88):
                    raise ValueError('Invalid DEX method index')
                method = word(92) + method_index * 8
                if type_name(short(method)) != owner:
                    raise ValueError('DEX method definition belongs to another class')
                if not code_offset:
                    continue
                if code_offset < 112 or code_offset % 4:
                    raise ValueError('Misaligned DEX code item')
                region(code_offset, 16)
                units = word(code_offset + 12)
                if units == 0:
                    raise ValueError('Executable DEX definition has an empty code item')
                region(code_offset + 16, units * 2)
                rows.append({
                    'dex': dex_name, 'class': owner,
                    'method': string(word(method + 4)),
                    'code_units': units, 'dex_instruction_bytes': units * 2,
                })
    return rows, owners


def primary_method(row, name):
    # Inline Dp parameters mangle the actual function name. Synthetic accessors,
    # $default methods and lambda/continuation wrappers cannot substitute for it.
    return row['class'] == SCREEN and '$' not in row['method'] and (
        row['method'] == name or row['method'].startswith(name + '-')
    )


def inspect_apk(apk):
    rows, owners, dex_names = [], set(), []
    with zipfile.ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist()
                   if re.fullmatch(r'classes(?:[0-9]+)?\.dex', entry.filename)]
        if not entries or len(entries) > MAX_DEX_FILES:
            raise ValueError('Missing or unexpectedly numerous packaged DEX files')
        if len({entry.filename for entry in entries}) != len(entries):
            raise ValueError('Duplicate packaged DEX names')
        if sum(entry.file_size for entry in entries) > MAX_TOTAL_DEX_BYTES:
            raise ValueError('Packaged DEX bytes exceed the bounded audit size')
        for entry in entries:
            if entry.file_size > MAX_DEX_BYTES:
                raise ValueError('A DEX file exceeds the bounded audit size')
            with archive.open(entry) as stream:
                data = stream.read(MAX_DEX_BYTES + 1)
            if len(data) != entry.file_size or len(data) > MAX_DEX_BYTES:
                raise ValueError('Packaged DEX length differs from its bounded ZIP entry')
            found, definitions = dex_methods(data, entry.filename)
            if owners & definitions:
                raise ValueError('Duplicate chat/voice class across DEX files')
            owners.update(definitions)
            if len(rows) + len(found) > MAX_MEASURED_METHODS:
                raise ValueError('Measured chat/voice definitions exceed the bounded audit size')
            rows.extend(found)
            dex_names.append(entry.filename)
    rows.sort(key=lambda row: (-row['code_units'], row['class'], row['method']))
    return rows, dex_names


def report_apk(apk, source_commit=None):
    report = {
        'schema_version': 1, 'apk': apk.name, 'status': 'ERROR',
        'source_commit': source_commit,
        'source_commit_provenance': 'CI GITHUB_SHA or explicit caller argument; APK bytes are independently hashed',
        'exclusive_max_code_units': ART_CODE_UNIT_LIMIT,
        'art_warning_condition': 'code_item.insns_size >= UINT16_MAX / 4',
        'measurement': '16-bit DEX code units, including variable-width instructions and payloads',
        'scope': ['chat UI and generated classes', 'voice UI and generated classes'],
        'limitation': 'Static packaged size; not evidence of device ART compilation or frame-time performance',
    }
    try:
        if source_commit is not None and not re.fullmatch(r'[0-9a-fA-F]{40}', source_commit):
            raise ValueError('Source commit must be a complete Git SHA-1')
        report['apk_bytes'] = apk.stat().st_size
        if report['apk_bytes'] > MAX_APK_BYTES:
            raise ValueError('APK exceeds the bounded audit size')
        report['apk_sha256'] = sha256(apk)
        rows, dex_names = inspect_apk(apk)
        primary = {name: [row for row in rows if primary_method(row, name)]
                   for name in ('ChatScreen', 'ChatScreenContent')}
        for row in rows:
            row['code_units_before_warning'] = ART_CODE_UNIT_LIMIT - row['code_units']
        oversized = [row for row in rows if row['code_units'] >= ART_CODE_UNIT_LIMIT]
        errors = [f'Expected one actual {name} definition, found {len(found)}; wrappers are insufficient'
                  for name, found in primary.items() if len(found) != 1]
        report.update({
            'dex_files': dex_names, 'method_count': len(rows),
            'primary_methods': primary, 'oversized_methods': oversized,
            'largest_methods': rows[:25], 'errors': errors,
            'status': 'FAIL' if errors or oversized else 'PASS',
        })
    except (OSError, ValueError, IndexError, struct.error, zipfile.BadZipFile, RuntimeError) as error:
        report['errors'] = [str(error)]
    return report


def main(argv=None):
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument('apk', type=Path)
    arguments.add_argument('--report', type=Path, required=True)
    arguments.add_argument('--source-commit', default=os.environ.get('GITHUB_SHA'))
    args = arguments.parse_args(argv)
    report = report_apk(args.apk, args.source_commit)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: report.get(key) for key in (
        'apk', 'apk_sha256', 'source_commit', 'status', 'primary_methods',
        'oversized_methods', 'errors',
    )}, indent=2))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
