"""Deterministic final/partial content assembled from persisted evidence only."""
from v14.engineering_scope import accepted_scope
from collections import Counter
from v14.diagnostic_support import terminal_receipt
from v14.graph_context import public_graph_receipt


def build_report(job, state, verification):
    data = job['data']
    task_scope = accepted_scope(job['request'])
    if task_scope in {'read', 'plan'}:
        return {'schema_version': 1, 'job_id': job['id'], 'request_id': job['request']['request_id'],
                'terminal_receipt': terminal_receipt(job, state, verification),
                'status': state, 'task_scope': task_scope, 'repository': job['request']['repository'],
                'branch': job['request']['branch'], 'head_sha': data.get('head_sha'),
                'binding_id': job['request'].get('execution_binding_id'),
                'summary': data.get('answer') or job.get('message', ''), 'repository_writes_authorized': False,
                'inspected_paths': (data.get('read_report') or {}).get('paths', []),
                'limitations': (data.get('read_report') or {}).get('limitations', []),
                'scope_verification': data.get('scope_verification'), 'repository_mutations': 0,
                'graph_retrieval': public_graph_receipt(data.get('graph_retrieval')),
                'tests_executed': False, 'merge_performed': False,
                'benchmark_status': 'NOT_MEASURED', 'device_performance': 'NOT_MEASURED'}
    requirements = verification.get('requirements', [])
    counts = dict(Counter(row['status'] for row in requirements))
    completed = state == 'COMPLETED' and verification.get('passed') is True
    return {'schema_version': 1, 'job_id': job['id'], 'request_id': job['request']['request_id'],
            'terminal_receipt': terminal_receipt(job, state, verification),
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
            'graph_retrieval': public_graph_receipt(data.get('graph_retrieval')),
            'artifact_refs': [value for value in data.values() if isinstance(value, dict) and value.get('result_ref')],
            'benchmark_status': 'NOT_MEASURED', 'device_performance': 'NOT_MEASURED',
            'recovery': 'Read this same durable job and its operation receipts before continuing; unknown writes are never replay permission.'}
