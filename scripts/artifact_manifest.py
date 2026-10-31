#!/usr/bin/env python3
"""Index actual artifacts by immutable digest, source revision, runtime, and version."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re


def build(root, runtime, commit_sha, version):
    if runtime not in ('legacy', 'geniex') or not re.fullmatch('[a-f0-9]{40}', commit_sha):
        raise ValueError('Runtime and full source SHA are required')
    root = root.resolve()
    entries = []
    for path in sorted(root.rglob('*')):
        if not path.is_file() or path.is_symlink() or path.suffix not in ('.apk', '.aab', '.json', '.sarif') or path.name == 'artifact-index.json':
            continue
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        entries.append({'path': path.relative_to(root).as_posix(), 'size_bytes': path.stat().st_size,
                        'sha256': digest.hexdigest(), 'tags': [runtime, path.suffix[1:], version, commit_sha]})
    return {'schema_version': 1, 'commit_sha': commit_sha, 'runtime': runtime, 'version': version,
            'run_id': os.getenv('GITHUB_RUN_ID'), 'run_attempt': os.getenv('GITHUB_RUN_ATTEMPT'),
            'artifacts': entries, 'coverage': 'Files present in this artifact directory at indexing time; not a complete release or Actions history.'}


def search(manifest, query):
    needle = query.casefold()
    return [row for row in manifest['artifacts'] if any(needle in text.casefold() for text in [row['path'], *row['tags']])]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path)
    parser.add_argument('--runtime', choices=('legacy', 'geniex'), default='legacy')
    parser.add_argument('--commit-sha', default=os.getenv('GITHUB_SHA', ''))
    parser.add_argument('--version')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--query', help='Search an existing output index without modifying it')
    args = parser.parse_args()
    if args.query is not None:
        print(json.dumps(search(json.loads(args.output.read_text()), args.query), indent=2))
        return
    if args.root is None:
        parser.error('--root is required when creating an index')
    version = args.version or re.search(r'versionName = "([^"]+)"', Path('app/build.gradle.kts').read_text())[1]
    result = build(args.root, args.runtime, args.commit_sha, version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
