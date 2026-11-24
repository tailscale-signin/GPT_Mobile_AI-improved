"""Deterministic final/partial content assembled from persisted evidence only."""
from v14.engineering_scope import accepted_scope
from v14.engineering_recovery import continuous_progress
from collections import Counter
import re
from v14.diagnostic_support import terminal_receipt
from v14.graph_context import public_graph_receipt


def ci_failure_summary(data):
    """Render only status and revision verified by the persisted inspection."""
    post = data.get('post_merge') or {}
    merge = data.get('merge_sha')
    if post.get('status') == 'FAILED' and re.fullmatch(r'[0-9a-f]{40}', str(merge)) and post.get('merge_sha') == merge:
        return f'Post-merge verification failed at {merge[:12]}. Review the checks and release evidence for this merge.'
    ci = data.get('ci') or {}
    head = data.get('head_sha')
    pr = data.get('pr')
    verdict = ci.get('ci_verdict')
    if (post or type(pr) is not int or pr < 1 or ci.get('pr') != pr or not re.fullmatch(r'[0-9a-f]{40}', str(head))
            or ci.get('head_sha') != head or verdict not in {'FAILED', 'BLOCKED'} or not ci.get('checks')
            or ci.get('checks_failed' if verdict == 'FAILED' else 'checks_blocked') is not True):
        return None
    return f'CI {"failed" if verdict == "FAILED" else "is blocked"} on PR #{pr} at {head[:12]}. Review the check results for this revision.'


def build_report(job, state, verification, *, committed_revision=None):
    receipt_job = dict(job, revision=committed_revision) if committed_revision is not None else job
    receipt = terminal_receipt(receipt_job, state, verification)
    data = job['data']
    task_scope = accepted_scope(job['request'])
    latest_retry = data.get('latest_retry')
    recovery_evidence = {'continuous_progress': continuous_progress(job),
                         'last_retry': latest_retry, 'retry_history': data.get('retry_history', []),
                         'availability': 'AVAILABLE' if latest_retry else 'NOT_RECORDED'}
    if task_scope in {'read', 'plan'}:
        return {'schema_version': 1, 'job_id': job['id'], 'request_id': job['request']['request_id'],
                'terminal_receipt': receipt, 'recovery_evidence': recovery_evidence,
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
    summary = job.get('message', 'Work is retained with incomplete verification.')
    if completed:
        summary = 'All configured feature evidence and the approved merge were verified.'
    elif state in {'FAILED', 'NEEDS_INPUT'}:
        summary = ci_failure_summary(data) or summary
    return {'schema_version': 1, 'job_id': job['id'], 'request_id': job['request']['request_id'],
            'terminal_receipt': receipt, 'recovery_evidence': recovery_evidence,
            'status': 'COMPLETED' if completed else 'CANCELLED' if state == 'CANCELLED' else 'PARTIAL_UNVERIFIED',
            'repository': job['request']['repository'], 'branch': job['request']['branch'],
            'head_sha': data.get('head_sha'), 'merge_sha': data.get('merge_sha'),
            'binding_id': job['request'].get('execution_binding_id'),
            'summary': summary,
            'requirements_total': len(requirements), 'requirement_counts': counts, 'requirements': requirements,
            'confirmed_milestones': job.get('milestones', []), 'changed_paths': data.get('changed_paths', []),
            'checks': data.get('readiness', {}).get('checks', []), 'blocked_steps': verification.get('failures', []),
            'latest_evidence': data.get('latest_evidence'), 'latest_operation': data.get('latest_operation'),
            'latest_execution_receipt': data.get('latest_execution_receipt'),
            'post_merge': data.get('post_merge'), 'qualified_revision': (data.get('post_merge') or {}).get('qualified_revision'), 'source_preparation': data.get('source_preparation'),
            'context_compaction': data.get('context_compaction'),
            'workspace': {'iterations': data.get('workspace_iterations', 0),
                          'receipt_digests': [row.get('receipt_sha256') for row in data.get('workspace_receipts', [])],
                          'source_sha': data.get('head_sha'), 'active_instructions': data.get('active_instructions', []),
                          'hooks': [hook for hook in (data.get('after_edit_hook'), data.get('before_publish_hook')) if hook]},
            'ci_verdict': data.get('ci', {}).get('ci_verdict') if isinstance(data.get('ci'), dict) else None,
            'graph_retrieval': public_graph_receipt(data.get('graph_retrieval')),
            'artifact_refs': [value for value in data.values() if isinstance(value, dict) and value.get('result_ref')],
            'benchmark_status': 'NOT_MEASURED', 'device_performance': 'NOT_MEASURED',
            'recovery': 'Read this same durable job and its operation receipts before continuing; unknown writes are never replay permission.'}
