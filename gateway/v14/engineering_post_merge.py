"""Qualification of the actual merge revision, independent of PR readiness."""
import time
from urllib.parse import quote
from v14.engineering_workflows import expected, WorkflowContractError


def inspect(backend, job):
    data, root = job['data'], backend.root(job)
    sha = data['merge_sha']
    branch_head = backend.ref(job, job['request']['base'])
    result = {'status': 'PENDING', 'merge_sha': sha, 'branch_head': branch_head,
              'merged': True, 'artifact_verification': 'NOT_REQUESTED', 'observed_at': time.time()}
    comparison = backend.api(root + '/compare/' + sha + '...' + quote(branch_head or '', safe=''), job=job)
    if comparison.get('status') not in {'identical', 'ahead'} or comparison.get('merge_base_commit', {}).get('sha') != sha:
        return dict(result, status='BLOCKED', reason='MERGE_REVISION_NOT_REACHABLE')
    contract = data.get('post_merge', {}).get('contract', {})
    if contract.get('merge_sha') != sha or contract.get('branch') != job['request']['base']:
        try:
            contract = expected(backend, job)
        except (WorkflowContractError, KeyError, TypeError, UnicodeError, ValueError):
            return dict(result, status='BLOCKED', reason='MERGE_WORKFLOW_CONTRACT_UNAVAILABLE',
                        recovery_action='REVIEW_PINNED_WORKFLOW_TRIGGERS')
    result['contract'] = contract
    required = {row['path'] for row in contract['workflows']}
    runs = backend.paged(root + '/actions/runs?head_sha=' + sha, 'workflow_runs', job)
    latest = {}
    for run in runs:
        if (run.get('head_sha') != sha or run.get('event') != 'push' or
                run.get('head_branch') != job['request']['base'] or
                run.get('head_repository', {}).get('full_name', '').lower() != job['request']['repository'].lower()):
            continue
        key = run.get('path', '').split('@', 1)[0]
        if key not in required:
            continue
        if key not in latest or (run['id'], run.get('run_attempt', 1)) > (latest[key]['id'], latest[key].get('run_attempt', 1)):
            latest[key] = run
    result['workflows'] = [{'id': run['id'], 'workflow_id': run['workflow_id'], 'path': path, 'name': run['name'],
                            'attempt': run.get('run_attempt', 1), 'status': run['status'], 'conclusion': run.get('conclusion')}
                           for path, run in latest.items()]
    result['missing_workflows'] = sorted(required - latest.keys())
    if any(run['status'] == 'completed' and run.get('conclusion') != 'success' for run in latest.values()):
        return dict(result, status='FAILED', reason='MERGE_WORKFLOW_FAILED', recovery_action='REPAIR_MERGED_REVISION')
    if result['missing_workflows'] or any(run['status'] != 'completed' for run in latest.values()):
        return dict(result, reason='MERGE_WORKFLOWS_PENDING')
    # Require a stable second observation so the first fast workflow cannot hide
    # slower workflow creation. Never reuse PR/test-merge receipts.
    previous = data.get('post_merge', {})
    signature = sorted((row['workflow_id'], row['id'], row['attempt']) for row in result['workflows'])
    prior_signature = sorted((row['workflow_id'], row['id'], row['attempt']) for row in previous.get('workflows', []))
    if previous.get('merge_sha') != sha or signature != prior_signature or result['observed_at'] - previous.get('observed_at', result['observed_at']) < 20:
        return dict(result, reason='MERGE_WORKFLOW_INVENTORY_SETTLING')
    from v14.engineering_release_checks import verify
    release = verify(backend, job, latest)
    result.update(release_checks=release, artifact_verification=release['artifact_verification'],
                  installation_verification=release['installation_verification'], smoke_verification=release['smoke_verification'])
    if release['status'] != 'PASSED':
        return dict(result, status=release['status'], reason=release['reason'])
    return dict(result, status='PASSED', reason='MERGE_REVISION_WORKFLOWS_VERIFIED', qualified_revision=sha)
