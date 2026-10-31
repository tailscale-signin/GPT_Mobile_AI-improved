#!/usr/bin/env python3
"""Index actual artifacts by immutable digest, source revision, runtime, and version."""
import argparse
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re


def build(root, runtime, commit_sha, version):
    if runtime not in ('legacy', 'geniex') or not re.fullmatch('[a-f0-9]{40}', commit_sha):
        raise ValueError('Runtime and full source SHA are required')
    root = root.resolve()
    if not root.is_dir():
        raise ValueError('Artifact root must exist')
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


def verify(root, manifest, runtime, commit_sha, required=(), version=None):
    """Verify the indexed bytes and identity; this is not signature verification."""
    root = root.resolve()
    if not root.is_dir() or manifest.get('schema_version') != 1:
        raise ValueError('Unsupported manifest or missing artifact root')
    if manifest.get('runtime') != runtime or manifest.get('commit_sha') != commit_sha or not re.fullmatch('[a-f0-9]{40}', commit_sha):
        raise ValueError('Artifact runtime or source revision does not match')
    if version is not None and manifest.get('version') != version:
        raise ValueError('Artifact version does not match')
    rows = manifest.get('artifacts')
    if not isinstance(rows, list) or not rows or len(rows) > 10000:
        raise ValueError('Artifact inventory is empty or exceeds its limit')
    paths = set()
    for row in rows:
        name = row.get('path')
        if not isinstance(name, str) or not name or '\\' in name:
            raise ValueError('Invalid artifact path')
        relative = Path(name)
        if relative.is_absolute() or any(part in ('..', '.', '') for part in name.split('/')) or name in paths:
            raise ValueError('Invalid or duplicate artifact path')
        paths.add(name)
        path = root
        for part in relative.parts:
            path = path / part
            if path.is_symlink():
                raise ValueError('Artifact paths cannot contain symlinks')
        if not path.is_file() or not path.resolve().is_relative_to(root):
            raise ValueError('Indexed artifact is missing or outside the root')
        size, expected = row.get('size_bytes'), row.get('sha256')
        if not isinstance(size, int) or isinstance(size, bool) or size < 0 or not isinstance(expected, str) or not re.fullmatch('[a-f0-9]{64}', expected):
            raise ValueError('Invalid artifact size or digest')
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        if path.stat().st_size != size or digest.hexdigest() != expected:
            raise ValueError('Artifact digest or size mismatch: ' + name)
    actual = {p.relative_to(root).as_posix() for p in root.rglob('*')
              if p.is_file() and not p.is_symlink() and p.suffix in ('.apk', '.aab', '.json', '.sarif') and p.name != 'artifact-index.json'}
    if actual != paths:
        raise ValueError('Artifact inventory changed after indexing')
    for pattern in required:
        if not any(fnmatch.fnmatchcase(path, pattern) for path in paths):
            raise ValueError('Required artifact is missing: ' + pattern)
    return {'schema_version': 1, 'status': 'VERIFIED', 'artifact_count': len(paths), 'runtime': runtime,
            'commit_sha': commit_sha, 'coverage': 'Content digests and indexed paths only; not a signature or device qualification.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path)
    parser.add_argument('--runtime', choices=('legacy', 'geniex'), default='legacy')
    parser.add_argument('--commit-sha', default=os.getenv('GITHUB_SHA', ''))
    parser.add_argument('--version')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--query', help='Search an existing output index without modifying it')
    parser.add_argument('--verify', action='store_true', help='Verify an existing index against the artifact directory')
    parser.add_argument('--require', action='append', default=[], help='Require at least one indexed path matching this glob')
    args = parser.parse_args()
    if args.query is not None and args.verify:
        parser.error('--query and --verify cannot be combined')
    if args.query is not None:
        print(json.dumps(search(json.loads(args.output.read_text()), args.query), indent=2))
        return
    if args.root is None:
        parser.error('--root is required when creating an index')
    version = args.version or re.search(r'versionName = "([^"]+)"', Path('app/build.gradle.kts').read_text())[1]
    if args.verify:
        print(json.dumps(verify(args.root, json.loads(args.output.read_text()), args.runtime, args.commit_sha, args.require, version), indent=2))
        return
    if args.require:
        parser.error('--require is only valid with --verify')
    result = build(args.root, args.runtime, args.commit_sha, version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    main()
