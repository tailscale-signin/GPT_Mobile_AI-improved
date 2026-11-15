"""Qualification of the actual merge revision, independent of PR readiness."""
import time
from urllib.parse import quote


def inspect(backend, job):
    data, root = job['data'], backend.root(job)
    sha = data['merge_sha']
    branch_head = backend.ref(job, job['request']['base'])
    result = {'status': 'PENDING', 'merge_sha': sha, 'branch_head': branch_head,
              'merged': True, 'artifact_verification': 'NOT_REQUESTED', 'observed_at': time.time()}
    comparison = backend.api(root + '/compare/' + sha + '...' + quote(branch_head or '', safe=''), job=job)
    if comparison.get('status') not in {'identical', 'ahead'} or comparison.get('merge_base_commit', {}).get('sha') != sha:
        return dict(result, status='BLOCKED', reason='MERGE_REVISION_NOT_REACHABLE')
    runs = backend.paged(root + '/actions/runs?head_sha=' + sha, 'workflow_runs', job)
    latest = {}
    for run in runs:
        if run.get('head_sha') != sha or run.get('event') not in {'push', 'workflow_dispatch', 'workflow_run'}:
            continue
        key = run['workflow_id']
        if key not in latest or (run['id'], run.get('run_attempt', 1)) > (latest[key]['id'], latest[key].get('run_attempt', 1)):
            latest[key] = run
    result['workflows'] = [{'id': run['id'], 'workflow_id': run['workflow_id'], 'name': run['name'],
                            'attempt': run.get('run_attempt', 1), 'status': run['status'], 'conclusion': run.get('conclusion')}
                           for run in latest.values()]
    if not latest or any(run['status'] != 'completed' for run in latest.values()):
        return dict(result, reason='MERGE_WORKFLOWS_PENDING')
    if any(run.get('conclusion') != 'success' for run in latest.values()):
        return dict(result, status='FAILED', reason='MERGE_WORKFLOW_FAILED', recovery_action='REPAIR_MERGED_REVISION')
    # Require a stable second observation so the first fast workflow cannot hide
    # slower workflow creation. Never reuse PR/test-merge receipts.
    previous = data.get('post_merge', {})
    signature = sorted((row['workflow_id'], row['id'], row['attempt']) for row in result['workflows'])
    prior_signature = sorted((row['workflow_id'], row['id'], row['attempt']) for row in previous.get('workflows', []))
    if previous.get('merge_sha') != sha or signature != prior_signature or result['observed_at'] - previous.get('observed_at', result['observed_at']) < 20:
        return dict(result, reason='MERGE_WORKFLOW_INVENTORY_SETTLING')
    return dict(result, status='PASSED', reason='MERGE_REVISION_WORKFLOWS_VERIFIED')
