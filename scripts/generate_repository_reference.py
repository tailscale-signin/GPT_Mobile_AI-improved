#!/usr/bin/env python3
"""Validate reviewed feature references and bind them to current Git blob bytes."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'docs/architecture/feature-map.source.json'
TARGET = 'docs/architecture/feature-map.json'
FIELDS = ('terms', 'paths', 'tests', 'commands', 'documents', 'invariants')
PATH_FIELDS = ('paths', 'tests', 'documents')


def source_path(root, name):
    if (not isinstance(name, str) or not name or len(name) > 512 or '\\' in name or
            any(ord(c) < 32 for c in name)):
        raise ValueError('Reference path must be bounded plain repository text')
    path = PurePosixPath(name)
    if path.is_absolute() or any(part in {'', '.', '..', '.git'} for part in name.split('/')):
        raise ValueError('Reference path must stay inside the selected repository')
    result = root / name
    if any(part.is_symlink() for part in (result, *result.parents)) or not result.is_file():
        raise ValueError('Reference must name an existing regular file: ' + name)
    if not result.resolve().is_relative_to(root.resolve()):
        raise ValueError('Reference escaped repository')
    return result


def blob_sha(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def is_git_root(root):
    result = subprocess.run(['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
                            capture_output=True, text=True)
    return result.returncode == 0 and Path(result.stdout.strip()).resolve() == root.resolve()


def git_source_bytes(root, name, raw):
    """Apply only Git's observed text normalization, never a repository clean command."""
    attributes = subprocess.run(['git', '-C', str(root), 'check-attr', '-z',
                                 'filter', 'working-tree-encoding', '--', name],
                                capture_output=True, check=True).stdout.split(b'\0')
    if any(value not in {b'unspecified', b'unset'} for value in attributes[2::3]):
        raise ValueError('Reference source cannot use external filters or encoding transforms: ' + name)
    expected = subprocess.run(['git', '-C', str(root), 'hash-object', '--path=' + name, '--stdin'],
                              input=raw, capture_output=True, check=True).stdout.decode('ascii').strip()
    for candidate in (raw, raw.replace(b'\r\n', b'\n')):
        if blob_sha(candidate) == expected:
            return candidate
    raise ValueError('Reference source has an unsupported Git text transform: ' + name)


def document(root=ROOT):
    source = json.loads((root / SOURCE).read_text(encoding='utf-8'))
    if not isinstance(source, dict):
        raise ValueError('Reference root must be an object')
    rows = source.get('features')
    if source.get('schema_version') != 1 or not isinstance(rows, list) or not 1 <= len(rows) <= 32:
        raise ValueError('Reference requires schema 1 and 1–32 reviewed features')
    ids, files = set(), {}
    git_root = is_git_root(root)
    for row in rows:
        if not isinstance(row, dict) or set(row) != {'id', 'title', *FIELDS}:
            raise ValueError('Feature fields differ from the reference contract')
        identity = row['id']
        if not isinstance(identity, str) or not re.fullmatch('[a-z][a-z0-9-]{1,63}', identity) or identity in ids:
            raise ValueError('Feature identities must be unique and stable')
        ids.add(identity)
        if not isinstance(row['title'], str) or not 1 <= len(row['title']) <= 160:
            raise ValueError('Feature title must be bounded')
        for field in FIELDS:
            values = row[field]
            if (not isinstance(values, list) or len(values) > 32 or
                    any(not isinstance(value, str) or not value.strip() or len(value) > 1000 or
                        any(ord(c) < 32 for c in value) for value in values) or len(set(values)) != len(values)):
                raise ValueError('Feature ' + field + ' must contain bounded unique strings')
        if not row['terms'] or not row['paths'] or not row['tests']:
            raise ValueError('Each reviewed feature needs terms, production paths and test candidates')
        for field in PATH_FIELDS:
            for name in row[field]:
                if name in files:
                    continue
                path = source_path(root, name)
                raw = path.read_bytes()
                if len(raw) > 2 * 1024 * 1024:
                    raise ValueError('Referenced file exceeds source limit: ' + name)
                raw.decode('utf-8')
                if git_root:
                    raw = git_source_bytes(root, name, raw)
                files[name] = {'blob_sha': blob_sha(raw), 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
    return {'schema_version': 1, 'generated_by': 'python3 scripts/generate_repository_reference.py',
            'source_binding': 'Resolve this manifest at the selected commit and verify referenced blob identities.',
            'coverage': 'Reviewed feature navigation and test candidates; not exhaustive semantic or runtime proof.',
            'commands_are_authority': False, 'features': rows, 'file_blobs': dict(sorted(files.items()))}


def synchronize(root=ROOT, check=False):
    expected = json.dumps(document(root), indent=2, ensure_ascii=False) + '\n'
    path = root / TARGET
    changed = not path.exists() or path.read_text(encoding='utf-8') != expected
    if changed and not check:
        path.write_text(expected, encoding='utf-8')
    return changed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if synchronize(check=args.check) and args.check:
        raise SystemExit('Repository reference is stale: run python3 scripts/generate_repository_reference.py')
