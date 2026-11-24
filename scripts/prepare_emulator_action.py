#!/usr/bin/env python3
"""Remove only premature MENU input from the exact reviewed emulator action."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

UPSTREAM_COMMIT = 'a421e43855164a8197daf9d8d40fe71c6996bb0d'
UPSTREAM_TREE = '30f687b0945b18f77e56323c141e3646b6f246b0'
PATCHES = (
    ('src/emulator-manager.ts',
     '1e9ca00c20d35d925440afc1ce82303c42cddc4c1ac196da65e39209ef87696f',
     '743b49c8fb8d23a9e31b54602f44a36d86625c7bec3c1c7fbf533140a78ddd8b',
     b'    await adb(port, `shell input keyevent 82`);\n'),
    ('lib/emulator-manager.js',
     'a63db8fe9ffb4fb3651e9a2c97f8bac5ca4ce0078a7bbe61ef2e02019082c577',
     '21572bad6b7a285191ae94f31bf27ddb618e03f5278d42b0dde7679c8a8104c6',
     b'        await adb(port, `shell input keyevent 82`);\n'),
)


def git(checkout, *arguments):
    try:
        return subprocess.run(
            ['git', '-c', 'core.fsmonitor=false', '-C', str(checkout), *arguments],
            check=True, capture_output=True, text=True, timeout=30,
        ).stdout.strip()
    except (subprocess.SubprocessError, OSError) as failure:
        raise ValueError('Could not verify the pinned upstream action checkout') from failure


def patch_checkout(checkout):
    checkout = Path(checkout).absolute()
    if not checkout.is_dir() or checkout.resolve() != checkout or checkout.is_symlink():
        raise ValueError('Expected a real dedicated action checkout, without symlinks')
    if Path(git(checkout, 'rev-parse', '--show-toplevel')).resolve() != checkout:
        raise ValueError('The action directory must be its own Git checkout')
    if git(checkout, 'rev-parse', 'HEAD') != UPSTREAM_COMMIT:
        raise ValueError('The action commit does not match the reviewed upstream pin')
    if git(checkout, 'rev-parse', 'HEAD^{tree}') != UPSTREAM_TREE:
        raise ValueError('The action tree does not match the reviewed upstream tree')
    if git(checkout, 'status', '--porcelain=v1', '--untracked-files=all', '--ignored=matching'):
        raise ValueError('The upstream action checkout must be clean before patching')

    # Validate both files completely before either write. An absent, duplicated,
    # already-patched or otherwise changed call is never accepted as equivalent.
    planned = []
    receipts = []
    for relative, original_digest, patched_digest, removed_line in PATCHES:
        path = checkout / relative
        if any(parent.is_symlink() for parent in (path, *path.parents) if parent != checkout.parent):
            raise ValueError('Action patch targets must not traverse symlinks')
        if not path.is_file() or path.stat().st_size > 65_536:
            raise ValueError('Expected a bounded regular upstream action file')
        before = path.read_bytes()
        if hashlib.sha256(before).hexdigest() != original_digest:
            raise ValueError('The upstream action file digest does not match its reviewed source')
        if before.count(removed_line) != 1:
            raise ValueError('Expected exactly one premature MENU operation')
        after = before.replace(removed_line, b'', 1)
        if hashlib.sha256(after).hexdigest() != patched_digest:
            raise ValueError('The patched action digest does not match the reviewed one-line deletion')
        planned.append((path, after))
        receipts.append({'path': relative, 'before_sha256': original_digest, 'after_sha256': patched_digest})
    for path, contents in planned:
        path.write_bytes(contents)
    return {
        'schema_version': 1,
        'status': 'PATCHED_SOURCE_ONLY',
        'upstream_repository': 'ReactiveCircus/android-emulator-runner',
        'upstream_commit': UPSTREAM_COMMIT,
        'upstream_tree': UPSTREAM_TREE,
        'removed_operation': 'Premature shell input keyevent 82 immediately after sys.boot_completed',
        'files': receipts,
        'emulator_execution_qualified': False,
        'readiness': 'Existing bounded process, broadcast, application, display and foreground checks remain required.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = patch_checkout(args.checkout)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Verified exact upstream action; removed only premature MENU input. Device qualification remains pending.')


if __name__ == '__main__':
    main()
