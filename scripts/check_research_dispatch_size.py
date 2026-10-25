#!/usr/bin/env python3
"""Check packaged DEX code units, not JVM Code bytes or decoded opcode counts.

ART's IsPathologicalCase logs the reported warning when InsnsSizeInCodeUnits()
is >= UINT16_MAX / 4, i.e. 16,383 16-bit code units. The code_item.insns_size
field includes variable-width instructions and payloads; it is not an opcode
count. Source: https://android.googlesource.com/platform/art/+/refs/heads/main/compiler/compiler.cc
"""
import argparse
import json
import struct
import zipfile

ART_CODE_UNIT_LIMIT = 65535 // 4
AGENT_PREFIXES = (
    'Ldev/chungjungsoo/gptmobile/data/agent/AgentRunner',
    'Ldev/chungjungsoo/gptmobile/data/research/ResearchSessionStore',
    'Ldev/chungjungsoo/gptmobile/data/research/ResearchSourceIndexer',
    'Ldev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver',
    'Ldev/chungjungsoo/gptmobile/data/benchmark/BenchmarkRunner',
    'Ldev/chungjungsoo/gptmobile/data/benchmark/BenchmarkCaseExecution',
)
GUARDED_PREFIXES = ('Ldev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl',) + AGENT_PREFIXES
DISPATCH_STAGES = (
    'executePreparedRequest', 'dispatchWithPreparedContext',
    'preparePrimaryAdmission', 'preparePrimaryToolContext',
    'preparePrimaryRecall', 'preparePrimaryTools', 'recallPrimaryTurnMemory',
    'preparePrimaryDispatch', 'preparePrimaryDispatchMemory',
    'preparePrimaryDispatchBudgets', 'planPreparedRunContext',
    'buildPrimarySystemPrompt', 'effectivePrimaryDelegationSettings',
    'acceptPrimaryResearchTask', 'prepareCombinedDeliveryWork',
    'streamPrimaryAnswer', 'openPrimarySession',
    'prepareDelegatedEvidence', 'prepareDelegatedTools', 'prepareDelegatedInput', 'acquirePrimaryResearch', 'delegateToProfile',
)


def is_dispatch_method(descriptor, name):
    if descriptor.startswith(AGENT_PREFIXES):
        return True
    if not descriptor.startswith('Ldev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl'):
        return False
    # Kotlin local suspend functions are static methods on their enclosing lambda;
    # their small continuation classes are not the actual dispatch implementation.
    return any(stage in name or stage in descriptor for stage in DISPATCH_STAGES)


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7f) << shift
        if byte < 0x80:
            return value, offset
    raise ValueError('Invalid DEX ULEB128')


def dispatch_methods(data, dex_name):
    if not data.startswith(b'dex\n') or len(data) < 112:
        raise ValueError(f'{dex_name}: unsupported DEX header')

    def word(offset):
        return struct.unpack_from('<I', data, offset)[0]

    strings_offset = word(60)
    types_offset = word(68)
    methods_offset = word(92)
    classes_size, classes_offset = word(96), word(100)

    def string(index):
        offset = word(strings_offset + 4 * index)
        _, offset = uleb(data, offset)
        end = data.index(0, offset)
        # Class and method descriptors used here are ASCII (a subset of MUTF-8).
        return data[offset:end].decode('utf-8', errors='replace')

    for index in range(classes_size):
        entry = classes_offset + index * 32
        descriptor = string(word(types_offset + word(entry) * 4))
        if not descriptor.startswith(GUARDED_PREFIXES):
            continue
        cursor = word(entry + 24)
        if not cursor:
            continue
        counts = []
        for _ in range(4):
            count, cursor = uleb(data, cursor)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, cursor = uleb(data, cursor)
            _, cursor = uleb(data, cursor)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                delta, cursor = uleb(data, cursor)
                method_index += delta
                _, cursor = uleb(data, cursor)
                code_offset, cursor = uleb(data, cursor)
                if not code_offset:
                    continue
                name = string(word(methods_offset + method_index * 8 + 4))
                if is_dispatch_method(descriptor, name):
                    units = word(code_offset + 12)
                    yield {'dex': dex_name, 'class': descriptor, 'method': name, 'code_units': units, 'dex_instruction_bytes': units * 2}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--max-code-units', type=int, default=ART_CODE_UNIT_LIMIT,
                        help='exclusive upper bound; ART warning threshold defaults to 16,383 code units')
    args = parser.parse_args()
    if args.max_code_units < 1:
        parser.error('--max-code-units must be positive')
    rows = []
    with zipfile.ZipFile(args.apk) as apk:
        for entry in apk.infolist():
            if entry.filename.startswith('classes') and entry.filename.endswith('.dex'):
                if entry.file_size > 128 * 1024 * 1024:
                    raise ValueError('Unexpectedly oversized DEX file')
                rows.extend(dispatch_methods(apk.read(entry), entry.filename))
    if not any(row['method'].endswith('executePreparedRequest') and not row['method'].startswith('access$') for row in rows):
        raise ValueError('The actual executePreparedRequest method was not found; continuation wrappers alone are not a valid size check')
    for descriptor, method in (
        ('Ldev/chungjungsoo/gptmobile/data/agent/AgentRunner;', 'executeLoop'),
        ('Ldev/chungjungsoo/gptmobile/data/agent/tool/AgentToolResolver;', 'resolve'),
        ('Ldev/chungjungsoo/gptmobile/data/research/ResearchSourceIndexer;', 'index'),
        ('Ldev/chungjungsoo/gptmobile/data/benchmark/BenchmarkRunner;', 'runOnce'),
        ('Ldev/chungjungsoo/gptmobile/data/benchmark/BenchmarkCaseExecution;', 'generate'),
        ('Ldev/chungjungsoo/gptmobile/data/benchmark/BenchmarkCaseExecution;', 'accept'),
        ('Ldev/chungjungsoo/gptmobile/data/benchmark/BenchmarkCaseExecution;', 'validate'),
    ):
        if not any(row['class'] == descriptor and row['method'] == method for row in rows):
            raise ValueError(f'The actual {descriptor}.{method} method was not found')
    rows.sort(key=lambda row: row['code_units'], reverse=True)
    print(json.dumps({'apk': args.apk, 'max_code_units': args.max_code_units, 'methods': rows}, indent=2))
    if rows[0]['code_units'] >= args.max_code_units:
        raise SystemExit('Dispatch coroutine exceeds the code-unit gate; extract more non-inline stages')


if __name__ == '__main__':
    main()
