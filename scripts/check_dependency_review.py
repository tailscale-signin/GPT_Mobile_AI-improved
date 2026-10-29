#!/usr/bin/env python3
"""Reject newly introduced high/critical advisories using GitHub dependency review."""
import argparse
import json
import os
import re
import sys
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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository) or not all(re.fullmatch(r'[a-fA-F0-9]{40}', ref) for ref in (args.base, args.head)):
        raise SystemExit('Repository and exact base/head SHAs are required')
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
    Path('dependency-review.json').write_text(json.dumps({'base': args.base, 'head': args.head, 'new_high_or_critical': blocked}, indent=2) + '\n')
    for item in blocked:
        print(f"::error::Introduced {item['severity']} advisory {item['advisory']} in {item['package']} {item['version']}")
    print(f'Dependency review: {len(blocked)} introduced high/critical advisories. Coverage depends on GitHub manifest support.')
    return bool(blocked)


if __name__ == '__main__':
    sys.exit(main())
