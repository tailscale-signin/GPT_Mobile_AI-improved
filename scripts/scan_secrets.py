#!/usr/bin/env python3
"""High-confidence credential scan; output locations only, never matched values.

Complements GitHub secret scanning/push protection; it does not claim to detect
all secret formats or validate whether a token is live.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

PATTERNS = [
    re.compile(rb'gh[pousr]_[A-Za-z0-9]{36,}'),
    re.compile(rb'github_pat_[A-Za-z0-9_]{60,}'),
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    re.compile(rb'(?:sk-(?:proj-|svcacct-|ant-api[0-9]+-|or-v1-)|gsk_|nvapi-)[A-Za-z0-9_-]{32,}'),
    re.compile(rb'AIza[0-9A-Za-z_-]{35}'),
    re.compile(rb'GOCSPX-[A-Za-z0-9_-]{20,}'),
    re.compile(rb'AKIA[0-9A-Z]{16}'),
]


def findings(data):
    return sorted({data[:m.start()].count(b'\n') + 1 for pattern in PATTERNS for m in pattern.finditer(data)})


def private_path(path):
    return (path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx', '.key'} or
            path.name in {'google-services.json', 'local.properties', '.env'} or
            path.name.startswith('.env.') and path.name not in {'.env.example', '.env.sample'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--history-paths', action='store_true', help='Also check names of private files deleted from reachable history.')
    args = parser.parse_args()
    failed = False
    if args.history_paths:
        patterns = ['*.jks', '*.keystore', '*.p12', '*.pfx', '*.key', 'google-services.json', 'local.properties', '.env', '.env.*']
        history = subprocess.check_output(['git', 'log', '--all', '--full-history', '-m', '--format=', '--name-only',
                                           '-z', '--diff-filter=ACMR', '--', *[':(icase)' + p for p in patterns]])
        for name in sorted(set(history.decode(errors='surrogateescape').split('\0'))):
            if name and private_path(Path(name)):
                print(f'Private configuration filename in history: {name!r}.')
                failed = True
    paths = subprocess.check_output(['git', 'ls-files', '-z']).decode(errors='surrogateescape').split('\0')
    for name in filter(None, paths):
        path = Path(name)
        if (private_path(path) or path.suffix.lower() in {'.apk', '.aab', '.apks'} or
                {'build', '.gradle', 'node_modules', '__pycache__', '.venv'} & set(path.parts[:-1])):
            print(f'Private configuration or generated output tracked at {name!r}.')
            failed = True
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
