"""Deterministic final/partial content assembled from persisted evidence only."""
from collections import Counter


def build_report(job, state, verification):
    data = job['data']
    requirements = verification.get('requirements', [])
    counts = dict(Counter(row['status'] for row in requirements))
    completed = state == 'COMPLETED' and verification.get('passed') is True
    return {'schema_version': 1, 'job_id': job['id'], 'request_id': job['request']['request_id'],
            'status': 'COMPLETED' if completed else 'CANCELLED' if state == 'CANCELLED' else 'PARTIAL_UNVERIFIED',
            'repository': job['request']['repository'], 'branch': job['request']['branch'],
            'head_sha': data.get('head_sha'), 'merge_sha': data.get('merge_sha'),
            'binding_id': job['request'].get('execution_binding_id'),
            'summary': 'All configured feature evidence and the approved merge were verified.' if completed else job.get('message', 'Work is retained with incomplete verification.'),
            'requirements_total': len(requirements), 'requirement_counts': counts, 'requirements': requirements,
            'confirmed_milestones': job.get('milestones', []), 'changed_paths': data.get('changed_paths', []),
            'checks': data.get('readiness', {}).get('checks', []), 'blocked_steps': verification.get('failures', []),
            'latest_evidence': data.get('latest_evidence'), 'latest_operation': data.get('latest_operation'),
            'latest_execution_receipt': data.get('latest_execution_receipt'),
            'artifact_refs': [value for value in data.values() if isinstance(value, dict) and value.get('result_ref')],
            'benchmark_status': 'NOT_MEASURED', 'device_performance': 'NOT_MEASURED',
            'recovery': 'Read this same durable job and its operation receipts before continuing; unknown writes are never replay permission.'}
