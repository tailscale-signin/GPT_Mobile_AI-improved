#!/usr/bin/env python3
"""Produce bounded, redacted diagnostics. Never upload raw environment variables."""
import argparse
import json
import os
import re
from collections import Counter
from pathlib import Path

ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')
ERROR = re.compile(r'(?:^e: |error:|fatal:|FAILURE:|Caused by:|Execution failed for task|OutOfMemoryError|licenses? not accepted|Could not resolve)', re.I)
TOKEN = re.compile(r'(?:gh[pousr]_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+|Bearer\s+\S+|(?:token|password|secret|authorization)\s*[:=]\s*\S+)', re.I)


def summarize(text):
    errors = []
    for line in text.splitlines():
        clean = re.sub(r'Authorization\s*:\s*(?:Bearer|Basic)\s+\S+', '[REDACTED]', ANSI.sub('', line), flags=re.I)
        clean = TOKEN.sub('[REDACTED]', clean).strip()
        if ERROR.search(clean):
            errors.append(clean[:500])
    grouped = Counter(errors)
    return {'first_actionable_failure': errors[0] if errors else None,
            'groups': [{'message': message, 'count': count} for message, count in list(grouped.items())[:30]]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('logs', nargs='*')
    parser.add_argument('--output', default='ci-diagnostics.json')
    args = parser.parse_args()
    reports = []
    for name in args.logs:
        path = Path(name)
        if not path.is_file():
            continue
        with path.open('rb') as stream:
            data = stream.read(1024 * 1024)
        reports.append({'file': path.name, 'truncated': path.stat().st_size > len(data), **summarize(data.decode('utf-8', errors='replace'))})
    result = {'schema_version': 1, 'head_sha': os.getenv('GITHUB_SHA'), 'run_id': os.getenv('GITHUB_RUN_ID'), 'reports': reports}
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n')
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write('\n### First actionable failures\n\n')
            for report in reports:
                # Escape markdown so remote output cannot inject summary links or HTML.
                message = (report['first_actionable_failure'] or 'No recognized failure in captured prefix').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('`', "'")
                stream.write(f"- {report['file']}: `{message}`\n")


if __name__ == '__main__':
    main()
