#!/usr/bin/env python3
"""Warn about review size without blocking a legitimate cross-cutting change."""
import argparse
import os
import re
import subprocess
from pathlib import Path


def changed_paths(base):
    if re.fullmatch(r'[a-fA-F0-9]{40}', base or '') and set(base) != {'0'}:
        subprocess.run(['git', 'cat-file', '-e', f'{base}^{{commit}}'], check=True)
        return subprocess.check_output(['git', 'diff', '--name-only', '--diff-filter=ACMR', '-z', f'{base}...HEAD']).decode().split('\0')[:-1]
    return subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')[:-1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='')
    args = parser.parse_args()
    paths = changed_paths(args.base)
    if len(paths) > 50:
        print(f'::warning::Large review: {len(paths)} files. Review by subsystem and verify cross-module contracts.')
    summary = f'\n### Change scope\n\n{len(paths)} changed files (full inventory when no base is available).\n'
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write(summary)
    print(summary)


if __name__ == '__main__':
    main()
