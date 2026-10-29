#!/usr/bin/env python3
"""High-confidence credential scan; output locations only, never matched values.

Complements GitHub secret scanning/push protection; it does not claim to detect
all secret formats or validate whether a token is live.
"""
import re
import subprocess
import sys
from pathlib import Path

PATTERNS = [
    re.compile(rb'gh[pousr]_[A-Za-z0-9]{36,}'),
    re.compile(rb'github_pat_[A-Za-z0-9_]{60,}'),
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(rb'AKIA[0-9A-Z]{16}'),
]


def findings(data):
    return sorted({data[:m.start()].count(b'\n') + 1 for pattern in PATTERNS for m in pattern.finditer(data)})


def main():
    failed = False
    paths = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    for name in filter(None, paths):
        path = Path(name)
        if path.is_symlink() or not path.is_file():
            continue
        if path.stat().st_size > 2 * 1024 * 1024:
            continue
        data = path.read_bytes()
        if b'\0' in data:
            continue
        for line in findings(data):
            print(f'Potential credential at {name}:{line}; value withheld.')
            failed = True
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
