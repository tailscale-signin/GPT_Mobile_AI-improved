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
from workflow_service_levels import evaluate, markdown


def summarize(runs):
    groups = {}
    for run in runs:
        groups.setdefault(run.get('workflow_id') or run.get('name') or 'Unknown workflow', []).append(run)
    result = []
    for _, rows in sorted(groups.items(), key=lambda entry: str(entry[0])):
        name = rows[0].get('name') or 'Unknown workflow'
        completed = [r for r in rows if r.get('status') == 'completed']
        durations = []
        for row in completed:
            start, end = row.get('run_started_at'), row.get('updated_at')
            if start and end:
                seconds = (dt.datetime.fromisoformat(end.replace('Z', '+00:00')) - dt.datetime.fromisoformat(start.replace('Z', '+00:00'))).total_seconds()
                if seconds >= 0:
                    durations.append(seconds)
        result.append({'workflow': name, 'workflow_id': rows[0].get('workflow_id'), 'runs': len(rows), 'completed': len(completed),
                       'successes': sum(r.get('conclusion') == 'success' for r in completed),
                       'failures': sum(r.get('conclusion') in ('failure', 'timed_out', 'action_required') for r in completed),
                       'cancelled': sum(r.get('conclusion') == 'cancelled' for r in completed),
                       'skipped': sum(r.get('conclusion') == 'skipped' for r in completed),
                       'events': {event: sum((r.get('event') or 'unknown') == event for r in rows) for event in sorted({(r.get('event') or 'unknown') for r in rows})},
                       'recent_failures': [{key: r.get(key) for key in ('id', 'head_sha', 'html_url', 'conclusion')} for r in rows if r.get('conclusion') in ('failure', 'timed_out', 'action_required')][:3],
                       'median_observed_seconds': statistics.median(durations) if durations else None})
    return result


FIELDS = ('id', 'workflow_id', 'name', 'path', 'event', 'head_sha', 'run_attempt', 'status', 'conclusion', 'run_started_at', 'created_at', 'updated_at', 'html_url')


def collect(fetch, repository, max_pages=3, checkpoint=None, save=lambda state: None):
    """Read a bounded page budget, deduplicate, and checkpoint only accepted pages."""
    if not 1 <= max_pages <= 1000:
        raise ValueError('max_pages must be 1–1000')
    state = checkpoint or {'schema_version': 2, 'repository': repository, 'next_page': 1, 'runs': [], 'traversal_complete': False}
    if state.get('schema_version') != 2 or state.get('repository') != repository:
        raise ValueError('Checkpoint belongs to another repository or schema')
    if not isinstance(state.get('next_page'), int) or not 1 <= state['next_page'] <= 1001:
        raise ValueError('Invalid checkpoint page')
    rows = {r['id']: r for r in state['runs']}
    for _ in range(max_pages):
        if state['traversal_complete'] or state['next_page'] > 1000:
            break
        page = fetch(state['next_page'])
        batch = page['workflow_runs']
        if not isinstance(batch, list) or len(batch) > 100:
            raise ValueError('Invalid run page')
        if 'initial_total' not in state:
            state['initial_total'] = page['total_count']
            state['anchor_id'] = max((r['id'] for r in batch), default=0)
        for row in batch:
            if row['id'] <= state['anchor_id']:
                rows[row['id']] = {key: row.get(key) for key in FIELDS}
        state['runs'] = list(rows.values())
        state['next_page'] += 1
        state['traversal_complete'] = len(batch) < 100
        state['unique_runs'] = len(rows)
        state['history_complete'] = state['traversal_complete'] and len(rows) == state['initial_total']
        state['has_more'] = not state['traversal_complete']
        state['metrics'] = summarize(state['runs'])
        save(state)
    return state


def write_snapshot(path, state):
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(state, indent=2) + '\n')
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repository', required=True)
    parser.add_argument('--output', default='workflow-health.json')
    parser.add_argument('--max-pages', type=int, default=3, help='100 runs per page; 1–1000 pages per invocation')
    parser.add_argument('--resume', type=Path, help='Resume a previous output file; never repeats a mutation')
    parser.add_argument('--objectives', type=Path, default=Path('.github/ci-objectives.json'))
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        raise SystemExit('Invalid repository')
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    if os.getenv('GH_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GH_TOKEN']
    def fetch(page):
        req = urllib.request.Request(f'https://api.github.com/repos/{args.repository}/actions/runs?per_page=100&page={page}', headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    snapshot = collect(fetch, args.repository, args.max_pages, json.loads(args.resume.read_text()) if args.resume else None,
                       lambda state: write_snapshot(Path(args.output), state))
    snapshot.update({'collected_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                     'sample_limit': args.max_pages * 100, 'sample_size': len(snapshot['runs']),
                     'duration_definition': 'run_started_at to updated_at; includes post-processing, not CPU time or a device benchmark',
                     'consistency_note': 'Run history is mutable. Complete means every initial run ID was observed; concurrent deletions or page movement can leave incomplete coverage.'})
    snapshot['service_levels'] = evaluate(snapshot['runs'], json.loads(args.objectives.read_text()))
    write_snapshot(Path(args.output), snapshot)
    if os.getenv('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
            stream.write('## Workflow health\n\n')
            stream.write(f"Observed {snapshot['sample_size']} unique runs; complete={snapshot.get('history_complete', False)}; next_page={snapshot['next_page']}. Durations include post-processing.\n\n")
            stream.write('| Workflow | Completed | Success | Failure | Cancelled | Median seconds |\n|---|---:|---:|---:|---:|---:|\n')
            for metric in snapshot['metrics']:
                name = metric['workflow'].replace('|', '/').replace('<', '&lt;').replace('\n', ' ')
                stream.write(f"| {name} | {metric['completed']} | {metric['successes']} | {metric['failures']} | {metric['cancelled']} | {metric['median_observed_seconds']} |\n")
            stream.write('\n' + markdown(snapshot['service_levels']))


if __name__ == '__main__':
    main()
