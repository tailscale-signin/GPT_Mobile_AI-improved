#!/usr/bin/env python3
"""Verify the fixed production-compiler budget on the hosted Linux cgroup-v2 VM.

Only numeric resource observations leave this check. Swap is recorded, never
counted as RAM. Unknown headroom fails; no smaller heap or retry is selected.
"""
import json
from pathlib import Path, PurePosixPath
import re
import sys

MIB = 1024 * 1024
HEAP_MIB, METASPACE_MIB, RESERVE_MIB = 6144, 768, 2048
REQUIRED_BYTES = (HEAP_MIB + METASPACE_MIB + RESERVE_MIB) * MIB
MAX_READ_BYTES = 64 * 1024
MAX_CGROUP_DEPTH = 32


def read_text(path):
    try:
        with Path(path).open(encoding='utf-8') as stream:
            text = stream.read(MAX_READ_BYTES + 1)
    except FileNotFoundError:
        return None
    if len(text.encode('utf-8')) > MAX_READ_BYTES:
        raise ValueError('Observation exceeds byte bound')
    return text.strip()


def unsigned(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{1,19}', value):
        raise ValueError('Invalid numeric observation')
    number = int(value)
    if number > (1 << 63) - 1:
        raise ValueError('Numeric observation exceeds bound')
    return number


def memory_fields(reader):
    values = {}
    for line in (reader('/proc/meminfo') or '').splitlines():
        name, separator, raw = line.partition(':')
        if separator and name in {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}:
            number, unit = raw.split()
            if unit != 'kB' or name in values:
                raise ValueError('Invalid memory observation')
            values[name] = unsigned(number) * 1024
    if values.get('MemTotal', 0) <= 0 or not 0 <= values.get('MemAvailable', -1) <= values['MemTotal']:
        raise ValueError('Available RAM is unknown')
    return {name: values.get(name) for name in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree')}


def cgroup_fields(reader):
    memberships = [row.split(':', 2)[2] for row in (reader('/proc/self/cgroup') or '').splitlines() if row.startswith('0::')]
    if len(memberships) != 1:
        raise ValueError('Unified cgroup membership is unknown')
    group = PurePosixPath(memberships[0])
    if not group.is_absolute() or '..' in group.parts or len(group.parts) > MAX_CGROUP_DEPTH:
        raise ValueError('Cgroup ancestry is outside bounds')
    mounts = []
    for row in (reader('/proc/self/mountinfo') or '').splitlines():
        left, separator, right = row.partition(' - ')
        fields = left.split()
        if separator and right.split()[0] == 'cgroup2' and len(fields) >= 5 and fields[3] == '/':
            mount = PurePosixPath(re.sub(r'\\([0-7]{3})', lambda match: chr(int(match[1], 8)), fields[4]))
            if not mount.is_absolute() or '..' in mount.parts:
                raise ValueError('Cgroup mount is invalid')
            mounts.append(mount)
    # A subtree-only mount would hide applicable ancestor limits. Do not infer
    # unlimited memory from an inaccessible parent or an unreadable limit.
    if len(mounts) != 1:
        raise ValueError('Complete visible cgroup ancestry is unknown')
    mount = mounts[0]
    current = mount.joinpath(*group.parts[1:])
    rows = []
    while True:
        limit = reader(str(current / 'memory.max'))
        usage = reader(str(current / 'memory.current'))
        if limit is None:
            if current == mount and reader(str(current / 'cgroup.controllers')) is not None:
                state = 'UNLIMITED_ROOT'
            elif current != mount and (enabled := reader(str(current.parent / 'cgroup.subtree_control'))) is not None and 'memory' not in enabled.split():
                state = 'INHERITS_ANCESTOR'
            else:
                raise ValueError('Applicable cgroup limit is unknown')
            row = {'state': state, 'limitBytes': None, 'usageBytes': unsigned(usage) if usage is not None else None, 'headroomBytes': None}
        elif limit == 'max':
            row = {'state': 'UNLIMITED', 'limitBytes': None, 'usageBytes': unsigned(usage), 'headroomBytes': None}
        else:
            maximum, used = unsigned(limit), unsigned(usage)
            row = {'state': 'FINITE', 'limitBytes': maximum, 'usageBytes': used, 'headroomBytes': max(0, maximum - used)}
        rows.append(row)
        if current == mount:
            return rows
        current = current.parent


def observe(reader=read_text):
    receipt = {'schemaVersion': 1, 'heapMiB': HEAP_MIB, 'metaspaceMiB': METASPACE_MIB, 'reserveMiB': RESERVE_MIB,
               'requiredBytes': REQUIRED_BYTES, 'swapCountsAsHeadroom': False, 'ram': None, 'cgroups': None,
               'availableBytes': None, 'status': 'UNKNOWN'}
    try:
        receipt['ram'] = memory_fields(reader)
        receipt['cgroups'] = cgroup_fields(reader)
        finite = [row['headroomBytes'] for row in receipt['cgroups'] if row['headroomBytes'] is not None]
        available = min([receipt['ram']['MemAvailable']] + finite)
        receipt['availableBytes'] = available
        receipt['status'] = 'SUFFICIENT' if available >= REQUIRED_BYTES else 'INSUFFICIENT'
    except (OSError, ValueError, IndexError, TypeError):
        # Do not print raw proc contents, cgroup names or exception text.
        receipt['status'] = 'UNKNOWN'
    return receipt


def main():
    receipt = observe()
    print(json.dumps(receipt, sort_keys=True))
    if receipt['status'] != 'SUFFICIENT':
        print('Production compilation requires 8960 MiB of verified RAM/cgroup headroom; swap is excluded. No compilation or retry was started.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
