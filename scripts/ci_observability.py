#!/usr/bin/env python3
"""Bounded, read-only CI snapshots. Cached results never authorize a merge or skip CI."""
import argparse
import datetime as dt
import json
import os
import re
import statistics
import time
import urllib.request
from pathlib import Path

from notify_ci_status import NoRedirect, deliver_payload


def timestamp(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


class GitHub:
    def __init__(self, repository, token, opener=None):
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
            raise ValueError('Invalid repository')
        self.repository, self.token = repository, token
        self.opener = opener or urllib.request.build_opener(NoRedirect)

    def request(self, path, method='GET'):
        if not path.startswith('/') or '..' in path or '\\' in path:
            raise ValueError('Invalid API path')
        request = urllib.request.Request('https://api.github.com/repos/' + self.repository + path,
            method=method, headers={'Authorization': 'Bearer ' + self.token,
            'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
        with self.opener.open(request, timeout=20) as response:
            body = response.read(8 * 1024 * 1024 + 1)
            if len(body) > 8 * 1024 * 1024:
                raise ValueError('API response exceeds budget')
            return json.loads(body) if body else None

    def pages(self, path, field=None, limit=4):
        result = []
        for page in range(1, limit + 1):
            separator = '&' if '?' in path else '?'
            data = self.request(f'{path}{separator}per_page=100&page={page}')
            values = data[field] if field else data
            result.extend(values)
            if len(values) < 100:
                return result, True
        return result, False


def summarize_runs(runs, now):
    durations, completed, failed, active = [], 0, 0, []
    conclusions = {}
    for run in runs:
        if run['status'] == 'completed':
            completed += 1
            conclusion = run.get('conclusion') or 'unknown'
            conclusions[conclusion] = conclusions.get(conclusion, 0) + 1
            failed += conclusion in ('failure', 'timed_out', 'action_required', 'startup_failure')
            if run.get('run_started_at') and run.get('updated_at'):
                durations.append(max(0, timestamp(run['updated_at']) - timestamp(run['run_started_at'])))
        else:
            age = max(0, now - timestamp(run.get('run_started_at') or run['created_at']))
            if age >= 1800:
                active.append({'run_id': run['id'], 'status': run['status'], 'age_seconds': round(age)})
    return {'observed_runs': len(runs), 'completed': completed, 'failed': failed,
            'success_rate': None if not completed else conclusions.get('success', 0) / completed,
            'success_rate_definition': 'success conclusions / all completed runs; includes cancelled and skipped in denominator',
            'conclusions': conclusions,
            'median_elapsed_seconds': statistics.median(durations) if durations else None,
            'duration_definition': 'run_started_at to updated_at; includes waiting; not billed runner time',
            'long_running': active}


def cached_completed_run(cache, run, fetch, now):
    """Two-hour cache for immutable run-attempt diagnostics, never live status."""
    key = f"{run['id']}:{run.get('run_attempt', 1)}:{run['head_sha']}"
    old = cache.get(key)
    if run['status'] == 'completed' and old and 0 <= now - old['stored_at'] < 7200:
        return old['value'], True
    value = fetch()
    if run['status'] == 'completed':
        cache[key] = {'stored_at': now, 'value': value}
        for expired in sorted(cache, key=lambda item: cache[item]['stored_at'])[:-64]:
            del cache[expired]
    return value, False


def notifications(repository, report, now):
    signals = []
    for row in report['runs']['long_running']:
        signals.append(('long_running', 1800, {'run_id': row['run_id'], 'age_seconds': row['age_seconds']}))
    for row in report['recent_failures']:
        signals.append(('error', 900, row))
    for row in report['stalled_prs']:
        signals.append(('pr_stalled', 14400, row))
    for row in report['merged_prs']:
        signals.append(('pr_merged', 3600, row))
    if report['runs']['completed']:
        signals.append(('summary', 86400, {'completed': report['runs']['completed'], 'failed': report['runs']['failed']}))
    if not report['inventory_complete']:
        signals.append(('warning', 3600, {'reason': 'bounded inventory is incomplete'}))
    for kind, cadence, details in signals:
        identity = details.get('run_id', details.get('pr_number', 'aggregate'))
        bucket = int(now // cadence)
        payload = {'schema_version': 1, 'repository': repository, 'kind': kind, 'details': details,
                   'url': f'https://github.com/{repository}/actions', 'time_bucket': bucket}
        yield f'{repository}:{kind}:{identity}:{bucket}', payload


def load_state(path):
    if not path.exists() or path.stat().st_size > 2 * 1024 * 1024:
        return {}
    try:
        value = json.loads(path.read_text())
        return value if isinstance(value, dict) else {}
    except (ValueError, OSError):
        return {}


def job_diagnostics(api, run):
    jobs, complete = api.pages(f"/actions/runs/{int(run['id'])}/attempts/{int(run.get('run_attempt', 1))}/jobs", 'jobs')
    # Do not cache logs, step names or arbitrary response metadata.
    return [{key: job.get(key) for key in ('id', 'status', 'conclusion', 'started_at', 'completed_at')} for job in jobs], complete


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='ci-observability.json')
    parser.add_argument('--state', default='.ci-observer/state.json')
    parser.add_argument('--notify', action='store_true')
    args = parser.parse_args()
    api = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN'])
    now = time.time()
    state_path = Path(args.state)
    state = load_state(state_path)
    # Cached data is diagnostic-only; scopes cannot cross repositories.
    if state.get('repository') != api.repository:
        state = {'repository': api.repository, 'completed_cache': {}, 'deliveries': {}}
    runs, runs_complete = api.pages('/actions/runs', 'workflow_runs')
    pulls, pulls_complete = api.pages('/pulls?state=all&sort=updated&direction=desc')
    failures = [{'run_id': r['id'], 'attempt': r.get('run_attempt', 1), 'conclusion': r.get('conclusion')}
                for r in runs if r['status'] == 'completed' and r.get('conclusion') in ('failure', 'timed_out', 'action_required')
                and now - timestamp(r['updated_at']) < 86400]
    diagnostics, hits = [], 0
    for run in [r for r in runs if r['status'] == 'completed'][:8]:
        value, hit = cached_completed_run(state['completed_cache'], run,
            lambda run=run: job_diagnostics(api, run), now)
        jobs, complete = value
        diagnostics.append({'run_id': run['id'], 'jobs_complete': complete,
            'jobs': [{k: job.get(k) for k in ('id', 'status', 'conclusion', 'started_at', 'completed_at')} for job in jobs]})
        hits += hit
    report = {'schema_version': 1, 'repository': api.repository, 'observed_at': now,
              'inventory_complete': runs_complete and pulls_complete, 'runs': summarize_runs(runs, now),
              'recent_failures': failures, 'completed_diagnostic_cache_hits': hits, 'diagnostics': diagnostics,
              'stalled_prs': [{'pr_number': p['number']} for p in pulls if p['state'] == 'open' and not p.get('draft') and now - timestamp(p['updated_at']) > 14400],
              'merged_prs': [{'pr_number': p['number']} for p in pulls if p.get('merged_at') and now - timestamp(p['merged_at']) < 86400]}
    Path(args.output).write_text(json.dumps(report, indent=2) + '\n')
    if args.notify:
        url, secret = os.getenv('CI_STATUS_WEBHOOK_URL', ''), os.getenv('CI_STATUS_WEBHOOK_SECRET', '')
        if not url or not secret:
            raise ValueError('Notification destination/signing secret not configured')
        channels = json.loads(os.getenv('CI_STATUS_CHANNELS') or '{}')
        if not isinstance(channels, dict):
            raise ValueError('Notification channels must be a mapping')
        attempts, deferred = 0, 0
        delivery_started = time.monotonic()
        for identity, payload in notifications(api.repository, report, now):
            if identity in state['deliveries']:
                continue
            if attempts >= 20 or time.monotonic() - delivery_started >= 120:
                deferred += 1
                continue
            channel = channels.get(payload['kind'])
            if channel:
                if not isinstance(channel, str) or not re.fullmatch(r'[#A-Za-z0-9_-]{1,80}', channel):
                    raise ValueError('Invalid notification channel')
                payload['channel'] = channel
            # Record the attempt before sending; a lost response must not cause a blind retry.
            state['deliveries'][identity] = now
            save_state(state_path, state)
            attempts += 1
            deliver_payload(identity, payload, url, secret)
        report['notifications'] = {'attempted': attempts, 'deferred_by_budget': deferred}
        Path(args.output).write_text(json.dumps(report, indent=2) + '\n')
    save_state(state_path, state)
    print(f"CI snapshot saved: {len(runs)} runs; complete={report['inventory_complete']}; diagnostic cache hits={hits}")


def save_state(path, state):
    state['deliveries'] = dict(sorted(state['deliveries'].items(), key=lambda pair: pair[1])[-512:])
    cache = state.get('completed_cache', {})
    # Bound bytes as well as entries, including large repositories with many jobs.
    while cache and len(json.dumps(state).encode()) > 1024 * 1024:
        del cache[min(cache, key=lambda key: cache[key]['stored_at'])]
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(state))
    temporary.replace(path)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Request URLs may contain credentials in configured webhook paths.
        raise SystemExit('CI observability failed; inspect configuration and API availability. No mutation was retried.')
