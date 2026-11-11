"""Durable, revisioned stage projection owned by the existing engineering job.

The plan is stored inside the engineering_jobs journal, never executed by an
independent worker. Real side effects still use EngineeringOperations receipts.
"""
import hashlib
import json

VERSION = 1
TERMINAL = {'COMPLETED', 'CANCELLED', 'FAILED', 'NEEDS_INPUT'}


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


def new_plan(job):
    request = job['request']
    if request.get('execution_mode', 'standard') != 'full':
        return None
    identity = [job['principal'], job['id'], request['request_id'],
                request['repository'], request['branch'], request['base'],
                request.get('source_sha'), request.get('execution_binding_id'),
                request.get('acceptance'), request.get('execution_budget')]
    return {'schema_version': VERSION, 'execution_mode': 'full',
            'identity_digest': _digest(identity), 'revision': 0,
            'nodes': [{'id': 'stage-0001', 'phase': 'PLAN', 'depends_on': [],
                       'state': 'queued', 'attempt': 1, 'receipt_sha256': None}]}


def transition(job, following, output):
    plan = job.get('data', {}).get('integration_batch')
    if not isinstance(plan, dict) or plan.get('schema_version') != VERSION:
        return
    nodes = plan['nodes']
    if not nodes:
        return
    current = nodes[-1]
    phase = job['state']
    if current['phase'] != phase:
        # Restored legacy stage or external inconsistency: do not claim success.
        plan['state'] = 'reconcile_required'
        return
    if following == phase:
        waiting = bool(job.get('provider_wait'))
        next_state = 'waiting_rate_limit' if waiting else 'running'
        if current['state'] != next_state:
            current['state'] = next_state
            plan['revision'] += 1
        return
    code = output.get('error_code') or output.get('error_type')
    current['state'] = 'blocked' if code or following in {'NEEDS_INPUT', 'CANCELLED', 'FAILED'} else 'completed'
    current['receipt_sha256'] = _digest({'phase': phase, 'output': output,
                                         'revision': job['revision']})
    plan['revision'] += 1
    if following in TERMINAL:
        plan['state'] = 'completed' if following == 'COMPLETED' and not code else 'needs_attention'
        return
    if len(nodes) >= 256:
        plan['state'] = 'capacity_exhausted'
        return
    attempts = sum(1 for node in nodes if node['phase'] == following) + 1
    nodes.append({'id': 'stage-' + str(len(nodes) + 1).zfill(4),
                  'phase': following, 'depends_on': [current['id']],
                  'state': 'queued', 'attempt': attempts, 'receipt_sha256': None})


def summary(plan):
    if not isinstance(plan, dict) or plan.get('schema_version') != VERSION:
        return None
    nodes = plan.get('nodes', [])
    completed = sum(item.get('state') == 'completed' for item in nodes)
    current = nodes[-1] if nodes else None
    return {'schema_version': VERSION, 'mode': 'full', 'revision': plan.get('revision', 0),
            'completed': completed, 'total': len(nodes),
            'current_phase': current['phase'] if current else None,
            'current_state': current['state'] if current else None,
            'status': plan.get('state', 'active')}


def page(plan, *, after=0, limit=20):
    if not isinstance(after, int) or not isinstance(limit, int) or after < 0 or not 1 <= limit <= 50:
        raise ValueError('Invalid batch plan page')
    nodes = plan.get('nodes', []) if isinstance(plan, dict) else []
    return {'schema_version': VERSION, 'summary': summary(plan),
            'nodes': nodes[after:after + limit],
            'next': after + limit if after + limit < len(nodes) else None}
