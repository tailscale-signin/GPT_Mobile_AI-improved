#!/usr/bin/env python3
"""Verify the fixed release-lint budget before starting the isolated JVM."""
import json
import sys

import check_android_compile_memory as production_memory

HEAP_MIB, METASPACE_MIB, RESERVE_MIB = 4096, 1024, 2048
REQUIRED_BYTES = (HEAP_MIB + METASPACE_MIB + RESERVE_MIB) * production_memory.MIB


def observe(reader=production_memory.read_text):
    # Share the bounded RAM/cgroup parser, not another phase's admission policy.
    # A known production shortfall may fit this budget; unknown remains refused.
    receipt = production_memory.observe(reader)
    known = receipt['status'] in ('SUFFICIENT', 'INSUFFICIENT')
    receipt.update(heapMiB=HEAP_MIB, metaspaceMiB=METASPACE_MIB,
                   reserveMiB=RESERVE_MIB, requiredBytes=REQUIRED_BYTES)
    receipt['status'] = ('SUFFICIENT' if receipt['availableBytes'] >= REQUIRED_BYTES else 'INSUFFICIENT') if known else 'UNKNOWN'
    return receipt


def main():
    receipt = observe()
    print(json.dumps(receipt, sort_keys=True))
    if receipt['status'] != 'SUFFICIENT':
        print('Release lint requires 7168 MiB of verified RAM/cgroup headroom; swap is excluded. No lint or retry was started.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
