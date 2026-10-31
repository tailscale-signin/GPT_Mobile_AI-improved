#!/usr/bin/env python3
"""Diagnostic service levels from observed runs; never a merge authorization."""
import datetime as dt
import math
from pathlib import Path

FAILURES = {'failure', 'timed_out', 'action_required', 'startup_failure'}


def epoch(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp() if value else None


def evaluate(runs, objectives, now=None):
    if objectives.get('schema_version') != 1:
        raise ValueError('Unsupported CI objective schema')
    now = dt.datetime.now(dt.timezone.utc).timestamp() if now is None else now
    groups, pending = {}, []
    for row in runs:
        groups.setdefault(row.get('workflow_id') or row.get('path') or row.get('name', 'unknown'), []).append(row)
        if row.get('status') != 'completed':
            start = epoch(row.get('run_started_at') or row.get('created_at'))
            pending.append({'run_id': row.get('id'), 'status': row.get('status'), 'age_seconds': max(0, now - start) if start else None})
    reports = []
    for identity, rows in sorted(groups.items(), key=lambda item: str(item[0])):
        path = rows[0].get('path', '').split('@')[0]
        target = dict(objectives['default'], **objectives.get('workflows', {}).get(Path(path).name, {}))
        minimum, rate_target, duration_target = target['minimum_samples'], target['success_rate'], target['p95_seconds']
        if not isinstance(minimum, int) or minimum < 2 or not 0 <= rate_target <= 1 or duration_target <= 0:
            raise ValueError('Invalid CI service level objective')
        eligible = sorted([r for r in rows if r.get('status') == 'completed' and r.get('conclusion') in FAILURES | {'success'}],
                          key=lambda r: (r.get('created_at') or '', r.get('id') or 0), reverse=True)
        successes = [r for r in eligible if r.get('conclusion') == 'success']
        rate = len(successes) / len(eligible) if eligible else None
        durations = []
        for row in successes:
            start, end = epoch(row.get('run_started_at')), epoch(row.get('updated_at'))
            if start is not None and end is not None and end >= start:
                durations.append(end - start)
        durations.sort()
        p95 = durations[math.ceil(len(durations) * 0.95) - 1] if durations else None
        enough = len(eligible) >= minimum
        duration_enough = len(durations) >= minimum
        middle = len(eligible) // 2
        def success_rate(batch):
            return sum(r.get('conclusion') == 'success' for r in batch) / len(batch) if batch else None
        recent, previous = success_rate(eligible[:middle]), success_rate(eligible[middle:])
        trend = 'INSUFFICIENT_SAMPLES' if not enough else ('DEGRADING' if previous - recent >= 0.1 else 'STABLE_OR_IMPROVING')
        reports.append({'workflow_id': identity, 'workflow': rows[0].get('name'), 'path': path,
                        'eligible_samples': len(eligible), 'success_rate': rate, 'p95_success_seconds': p95,
                        'duration_samples': len(durations), 'objectives': target,
                        'availability': 'INSUFFICIENT_SAMPLES' if not enough else ('MET' if rate >= rate_target else 'MISSED'),
                        'latency': 'INSUFFICIENT_SAMPLES' if not duration_enough else ('MET' if p95 <= duration_target else 'MISSED'),
                        'retried_run_count': sum((r.get('run_attempt') or 1) > 1 for r in eligible),
                        'recent_half_success_rate': recent, 'previous_half_success_rate': previous, 'trend': trend})
    return {'schema_version': 1, 'workflows': reports,
            'capacity': {'active': sum(r['status'] == 'in_progress' for r in pending),
                         'queued_or_waiting': sum(r['status'] != 'in_progress' for r in pending),
                         'oldest_pending_seconds': max((r['age_seconds'] for r in pending if r['age_seconds'] is not None), default=None)},
            'definition': 'Observed terminal successes / successes + failure-class conclusions. Cancelled, skipped, neutral and pending are excluded. P95 uses successful run wall duration including post-processing. Retry counts/trends are investigation signals, not proof of flaky tests. Sample coverage is inherited from the collector.'}


def markdown(report):
    lines = ['## CI service levels', '', report['definition'], '',
             '| Workflow | Samples | Success rate | P95 seconds | Availability | Latency | Trend |',
             '|---|---:|---:|---:|---|---|---|']
    for row in report['workflows']:
        name = str(row['workflow'] or row['workflow_id']).replace('|', '/').replace('\n', ' ').replace('<', '&lt;')
        rate = f"{row['success_rate']:.1%}" if row['success_rate'] is not None else 'unknown'
        duration = f"{row['p95_success_seconds']:.0f}" if row['p95_success_seconds'] is not None else 'unknown'
        lines.append(f"| {name} | {row['eligible_samples']} | {rate} | {duration} | {row['availability']} | {row['latency']} | {row['trend']} |")
    lines += ['', f"Observed capacity: {report['capacity']['active']} running, {report['capacity']['queued_or_waiting']} queued/waiting. This is a sample, not the account runner quota.", '']
    return '\n'.join(lines)
