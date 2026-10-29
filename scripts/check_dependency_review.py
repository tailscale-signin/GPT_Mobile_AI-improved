#!/usr/bin/env python3
"""Reject newly introduced high/critical advisories using GitHub dependency review."""
import argparse
import json
import os
import re
import sys
import subprocess
import urllib.error
import urllib.request
from pathlib import Path


def findings(changes):
    if not isinstance(changes, list):
        raise ValueError('Invalid dependency review response')
    result = []
    for change in changes:
        if change.get('change_type') != 'added':
            continue
        for advisory in change.get('vulnerabilities', []):
            if advisory.get('severity', '').lower() in ('high', 'critical'):
                result.append({'package': change.get('name'), 'version': change.get('version'),
                               'manifest': change.get('manifest'), 'severity': advisory['severity'],
                               'advisory': advisory.get('advisory_ghsa_id')})
    return result


def dependency_manifest(path):
    name = Path(path).name.lower()
    return (name.endswith(('.gradle', '.gradle.kts', '.versions.toml', '.lockfile')) or
            name in {'gradle-wrapper.properties', 'verification-metadata.xml', 'gradle.lockfile',
                     'pyproject.toml', 'poetry.lock', 'uv.lock', 'pipfile', 'pipfile.lock',
                     'package.json', 'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml',
                     'cargo.toml', 'cargo.lock', 'go.mod', 'go.sum', 'pom.xml'} or
            name.startswith('requirements') and name.endswith(('.txt', '.in')))


def changed_manifests(base, head):
    paths = subprocess.check_output(['git', 'diff', '--name-only', '-z', f'{base}...{head}']).decode().split('\0')
    return [path for path in paths if dependency_manifest(path)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository) or not all(re.fullmatch(r'[a-fA-F0-9]{40}', ref) for ref in (args.base, args.head)):
        raise SystemExit('Repository and exact base/head SHAs are required')
    manifests = changed_manifests(args.base, args.head)
    if not manifests:
        Path('dependency-review.json').write_text(json.dumps({
            'base': args.base, 'head': args.head, 'status': 'NO_DEPENDENCY_CHANGES',
            'changed_manifests': [], 'scan_performed': False
        }, indent=2) + '\n')
        print('No dependency manifests changed between the pinned revisions; no advisory scan was requested.')
        return 0
    req = urllib.request.Request(f'https://api.github.com/repos/{args.repository}/dependency-graph/compare/{args.base}...{args.head}',
        headers={'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + os.environ['GH_TOKEN'], 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            payload = response.read(4 * 1024 * 1024 + 1)
            if len(payload) > 4 * 1024 * 1024:
                raise ValueError('Dependency review response exceeds 4 MiB')
            blocked = findings(json.loads(payload))
    except urllib.error.HTTPError as error:
        raise SystemExit(f'Dependency review unavailable (HTTP {error.code}); verify dependency graph/security availability and Contents:read. This is not a passing scan.') from None
    Path('dependency-review.json').write_text(json.dumps({'base': args.base, 'head': args.head, 'status': 'SCANNED', 'scan_performed': True, 'changed_manifests': manifests, 'new_high_or_critical': blocked}, indent=2) + '\n')
    for item in blocked:
        print(f"::error::Introduced {item['severity']} advisory {item['advisory']} in {item['package']} {item['version']}")
    print(f'Dependency review: {len(blocked)} introduced high/critical advisories. Coverage depends on GitHub manifest support.')
    return bool(blocked)


if __name__ == '__main__':
    sys.exit(main())
