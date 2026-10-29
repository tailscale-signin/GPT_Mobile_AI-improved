#!/usr/bin/env python3
"""Use NUL-separated paths and argv, never interpolate branch names into shell."""
import argparse
import subprocess
from check_change_size import changed_paths

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='')
    parser.add_argument('--ktlint', required=True)
    args = parser.parse_args()
    paths = [p for p in changed_paths(args.base) if p.endswith(('.kt', '.kts'))]
    for start in range(0, len(paths), 100):
        subprocess.run(['java', '-jar', args.ktlint, *paths[start:start + 100]], check=True)
