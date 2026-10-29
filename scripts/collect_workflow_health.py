#!/usr/bin/env python3
"""Export bounded workflow history for a dashboard, without changing repository state."""
import argparse
import datetime as dt
import json
import os
import re
import statistics
import urllib.request
from pathlib import Path


def summarize(runs):
    groups = {}
    for run in runs:
        groups.setdefault(run['name'], []).append(run)
    result = []
    for name, rows in sorted(groups.items()):
        completed = [r for r in rows if r.get('status') == 'completed']
        durations = []
        for row in completed:
            start, end = row.get('run_started_at'), row.get('updated_at')
            if start and end:
                seconds = (dt.datetime.fromisoformat(end.replace('Z', '+00:00')) - dt.datetime.fromisoformat(start.replace('Z', '+00:00'))).total_seconds()
                if seconds >= 0:
                    durations.append(seconds)
        result.append({'workflow': name, 'runs': len(rows), 'completed': len(completed),
                       'successes': sum(r.get('conclusion') == 'success' for r in completed),
                       'failures': sum(r.get('conclusion') in ('failure', 'timed_out', 'action_required') for r in completed),
                       'cancelled': sum(r.get('conclusion') == 'cancelled' for r in completed),
                       'median_observed_seconds': statistics.median(durations) if durations else None})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True)
    parser.add_argument('--output', default='workflow-health.json')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        raise SystemExit('Invalid repository')
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    if os.getenv('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    rows = []
    for page in range(1, 4):
        req = urllib.request.Request(f'https://api.github.com/repos/{args.repository}/actions/runs?per_page=100&page={page}', headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            batch = json.load(response)['workflow_runs']
        rows.extend(batch)
        if len(batch) < 100:
            break
    snapshot = {'schema_version': 1, 'repository': args.repository, 'collected_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                'sample_limit': 300, 'sample_size': len(rows), 'metrics': summarize(rows),
                'duration_definition': 'run_started_at to updated_at; includes queue/post-processing and is not an Android startup benchmark',
                'runs': [{k: row.get(k) for k in ('id', 'name', 'head_sha', 'run_attempt', 'status', 'conclusion', 'run_started_at', 'updated_at', 'html_url')} for row in rows]}
    Path(args.output).write_text(json.dumps(snapshot, indent=2) + '\n')
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write('## Workflow health\n\nLatest 300 runs at most; durations include queue and post-processing.\n\n')
            stream.write('| Workflow | Completed | Success | Failure | Cancelled | Median seconds |\n|---|---:|---:|---:|---:|---:|\n')
            for metric in snapshot['metrics']:
                name = metric['workflow'].replace('|', '/').replace('<', '&lt;').replace('\n', ' ')
                stream.write(f"| {name} | {metric['completed']} | {metric['successes']} | {metric['failures']} | {metric['cancelled']} | {metric['median_observed_seconds']} |\n")


if __name__ == '__main__':
    main()
