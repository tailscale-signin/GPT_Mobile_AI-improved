#!/usr/bin/env python3
"""Prepare a reviewable version/changelog patch; never publish or move a ref."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import re
import subprocess


def git(*arguments):
    return subprocess.check_output(['git', *arguments], text=True).strip()


def bump(source, version, previous=None):
    if not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', version):
        raise ValueError('Use a four-component numeric version')
    old = re.search(r'versionName = "([^"]+)"', previous or source)
    code = re.search(r'versionCode = (\d+)', previous or source)
    if not old or not code or not re.fullmatch(r'\d+\.\d+\.\d+\.\d+', old[1]):
        raise ValueError('Existing version is missing or ambiguous')
    if tuple(map(int, version.split('.'))) <= tuple(map(int, old[1].split('.'))):
        raise ValueError('Release version must increase from the current commit')
    next_code = int(code[1]) + 1
    if next_code > 2_100_000_000:
        raise ValueError('Android versionCode limit exceeded')
    if len(re.findall(r'versionName = "[^"]+"', source)) != 1 or len(re.findall(r'versionCode = \d+', source)) != 1:
        raise ValueError('Version declarations are ambiguous')
    result = re.sub(r'versionName = "[^"]+"', f'versionName = "{version}"', source)
    result = re.sub(r'versionCode = \d+', f'versionCode = {next_code}', result)
    return result, next_code


def notes(version, commits, base, since):
    lines = [f'# Release {version}', '', f'Prepared from `{base}`. Previous release revision: `{since or "unavailable"}`.', '']
    for sha, title in commits:
        # Commit titles are data. Strip controls and escape Markdown link syntax.
        title = ''.join(c for c in title[:300] if c.isprintable()).replace('<', '&lt;').replace('[', '\\[').replace(']', '\\]')
        lines.append(f'- {title} (`{sha[:12]}`)')
    if not commits:
        lines.append('No commit list available; review the compare view before publication.')
    lines += ['', 'Validation and release identity checks remain required before publication.', '']
    return '\n'.join(lines)


def rollback_plan(base, target, mainline=None):
    """Bind a forward revert proposal to observed ancestry; never mutate a ref."""
    if not all(re.fullmatch('[a-f0-9]{40}', sha) for sha in (base, target)):
        raise ValueError('Rollback requires exact source and target revisions')
    if git('rev-parse', 'HEAD') != base:
        raise ValueError('Rollback source changed')
    if subprocess.run(['git', 'merge-base', '--is-ancestor', target, base], capture_output=True).returncode != 0:
        raise ValueError('Rollback target must be an ancestor of the source revision')
    parents = git('rev-list', '--parents', '-n', '1', target).split()[1:]
    if not parents:
        raise ValueError('A root commit cannot be a release rollback target')
    if len(parents) > 1:
        if mainline is None or mainline not in range(1, len(parents) + 1):
            raise ValueError('A merge rollback requires an explicitly reviewed parent number')
    elif mainline is not None:
        raise ValueError('Mainline is only valid for merge commits')
    command = ['git', 'revert', '--no-edit']
    if mainline is not None:
        command += ['--mainline', str(mainline)]
    command.append(target)
    return {'schema_version': 1, 'mode': 'forward-revert-proposal', 'expected_head_sha': base,
            'commit_sha': target, 'parent_shas': parents, 'mainline': mainline,
            'review_command': command, 'automatic_remote_reset': False,
            'qualification': 'NOT_RUN', 'published': False,
            'note': 'Recheck the expected head, prepare the revert on a new branch, resolve conflicts, increment the release version, and qualify a new release. Published tags and refs are never rewritten.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, default=Path('build/reports/release-plan'))
    parser.add_argument('--expected-head', required=True)
    parser.add_argument('--since', help='A full commit SHA for the previous release')
    parser.add_argument('--rollback-commit', help='Record a forward-revert proposal for a full commit SHA')
    parser.add_argument('--rollback-mainline', type=int, help='Reviewed parent number for a merge-commit rollback')
    parser.add_argument('--apply', action='store_true', help='Apply the local version and notes files for review')
    args = parser.parse_args()
    if args.rollback_mainline is not None and args.rollback_commit is None:
        parser.error('--rollback-mainline requires --rollback-commit')
    base = git('rev-parse', 'HEAD')
    if not re.fullmatch('[a-f0-9]{40}', args.expected_head) or base != args.expected_head:
        parser.error('Head changed; inspect the new revision before preparing this release')
    for value in (args.since, args.rollback_commit):
        if value and not re.fullmatch('[a-f0-9]{40}', value):
            parser.error('History and rollback references must be full commit SHAs')
        if value:
            git('cat-file', '-e', value + '^{commit}')
    build = Path('app/build.gradle.kts')
    source = build.read_text()
    updated, next_code = bump(source, args.version, git('show', 'HEAD:app/build.gradle.kts'))
    since = args.since
    if since is None:
        try:
            tag = git('describe', '--tags', '--abbrev=0')
            since = git('rev-parse', '--verify', tag + '^{commit}')
        except subprocess.CalledProcessError:
            pass
    if since:
        # A release comparison must follow the selected branch ancestry.
        subprocess.run(['git', 'merge-base', '--is-ancestor', since, base], check=True)
    history = git('log', '-n', '200', '--format=%H%x09%s', f'{since}..{base}' if since else base)
    commits = [line.split('\t', 1) for line in history.splitlines() if '\t' in line]
    release_notes = notes(args.version, commits, base, since)
    plan = {'schema_version': 1, 'base_sha': base, 'version': args.version, 'version_code': next_code,
            'tag': 'v' + args.version, 'source_sha256': hashlib.sha256(source.encode()).hexdigest(),
            'commit_list_limit': 200, 'commit_list_may_be_truncated': len(commits) == 200,
            'published': False, 'rollback': None}
    if args.rollback_commit:
        plan['rollback'] = rollback_plan(base, args.rollback_commit, args.rollback_mainline)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
    (args.output / 'version.patch').write_text(''.join(difflib.unified_diff(source.splitlines(True), updated.splitlines(True), fromfile='a/app/build.gradle.kts', tofile='b/app/build.gradle.kts')))
    (args.output / 'RELEASE_NOTES.md').write_text(release_notes)
    if args.apply:
        if git('rev-parse', 'HEAD') != base or hashlib.sha256(build.read_bytes()).hexdigest() != plan['source_sha256']:
            raise ValueError('Working revision changed during preparation')
        build.write_text(updated)
        Path('RELEASE_NOTES.md').write_text(release_notes)
    print(f"Prepared v{args.version} for review; no release, tag, or ref was published.")


if __name__ == '__main__':
    main()
