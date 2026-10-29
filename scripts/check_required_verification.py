#!/usr/bin/env python3
"""Fail closed when any expected job is missing, skipped, cancelled or failing."""
import json
import os
import sys
from pathlib import Path

EXPECTED_JOBS = {'reliability_v2_completion', 'validate', 'contracts', 'format'}


def failures(needs):
    if not isinstance(needs, dict):
        return ['Invalid verification job inventory']
    issues = [f'Missing job: {name}' for name in sorted(EXPECTED_JOBS - needs.keys())]
    for name, job in needs.items():
        result = job.get('result') if isinstance(job, dict) else None
        if result != 'success':
            issues.append(f'{name}: {result or "unknown"}')
    return issues


def main():
    try:
        needs = json.loads(os.environ.get('VERIFICATION_NEEDS', '{}'))
    except ValueError:
        needs = None
    issues = failures(needs)
    summary = '## Required verification\n\n' + ('Passed: all expected jobs succeeded.' if not issues else '\n'.join(f'- {x}' for x in issues)) + '\n'
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write(summary)
    return 1 if issues else 0


if __name__ == '__main__':
    sys.exit(main())
