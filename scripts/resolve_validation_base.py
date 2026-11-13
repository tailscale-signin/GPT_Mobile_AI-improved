#!/usr/bin/env python3
"""Pin changed-file validation to the event base or an explicit manual comparison."""
import os
from pathlib import Path
import re
import subprocess
import sys


def git(*args, cwd=None):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True, stderr=subprocess.PIPE).strip()


def exact_commit(value, cwd=None):
    if not re.fullmatch(r'[a-fA-F0-9]{40}', value or '') or set(value) == {'0'}:
        raise ValueError('Change comparison requires a nonzero full commit SHA.')
    if git('cat-file', '-t', value, cwd=cwd) != 'commit':
        raise ValueError('Change comparison must identify a commit, not another Git object.')
    return value.lower()


def resolve_base(event, event_base='', manual_base='', default_branch='', current_ref='', *, cwd=None):
    if event in ('pull_request', 'push', 'merge_group'):
        # A manual input must never override the authoritative event comparison.
        return exact_commit(event_base, cwd)
    if event != 'workflow_dispatch':
        raise ValueError('Unsupported validation event; no comparison base was selected.')
    if manual_base:
        base = exact_commit(manual_base, cwd)
        head = git('rev-parse', '--verify', 'HEAD^{commit}', cwd=cwd)
        if git('merge-base', base, head, cwd=cwd) == head:
            raise ValueError('The explicit comparison includes HEAD and checks no changes; choose an earlier or divergent commit.')
        return base
    if not default_branch or not current_ref.startswith('refs/heads/'):
        raise ValueError('Manual validation needs a branch and repository default branch, or an explicit commit SHA.')
    git('check-ref-format', 'refs/heads/' + default_branch, cwd=cwd)
    git('check-ref-format', current_ref, cwd=cwd)
    # Checkout fetch-depth: 0 supplies this remote ref. Missing history must fail
    # instead of silently expanding the formatting check to every tracked file.
    default = exact_commit(git('rev-parse', '--verify', 'refs/remotes/origin/' + default_branch + '^{commit}', cwd=cwd), cwd)
    if current_ref == 'refs/heads/' + default_branch:
        # On the default branch, validate the latest change (including an entire
        # merge) against its first parent; comparing HEAD to itself checks nothing.
        return exact_commit(git('rev-parse', '--verify', 'HEAD^1^{commit}', cwd=cwd), cwd)
    return default


def main():
    try:
        base = resolve_base(
            os.environ.get('GITHUB_EVENT_NAME', ''),
            os.environ.get('CHANGE_BASE', ''),
            os.environ.get('MANUAL_CHANGE_BASE', ''),
            os.environ.get('DEFAULT_BRANCH', ''),
            os.environ.get('GITHUB_REF', ''),
        )
    except (ValueError, subprocess.CalledProcessError) as error:
        detail = str(error) if isinstance(error, ValueError) else 'The required comparison commit or branch is unavailable in the checkout.'
        print('::error::' + detail, file=sys.stderr)
        return 1
    with Path(os.environ['GITHUB_ENV']).open('a', encoding='utf-8') as output:
        output.write(f'CHANGE_BASE={base}\n')
    print(f'Changed-file validation comparison: {base}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
