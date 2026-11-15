"""Repair confirmed main-build failures through a new, normally gated job.

The merged job and its approval are immutable. A recovery has a new branch/PR,
shares the remaining deadline/budget, and requires its own exact-head approval.
"""
from copy import deepcopy
from urllib.parse import quote

from v14.engineering_defaults import effective_execution_budget
from v14.execution_binding import ExecutionBindingError


def enqueue(service, job, failed):
    from v14.engineering import digest
    from v14.engineering_source import prepare_source

    if failed.get('status') != 'FAILED' or failed.get('reason') != 'MERGE_WORKFLOW_FAILED':
        return None
    if job.get('recovery_depth', 0) >= job['request']['repair_attempts']:
        return None
    if service.store.now() >= job['deadline'] - 60:
        return None
    if job['data'].get('post_merge_recovery_job_id'):
        return service.store.get(job['data']['post_merge_recovery_job_id'], job['principal'])
    service.store.assert_claim(job)
    service.validate_execution(job, force=True)
    head = failed['branch_head']
    identity = digest([job['id'], job['data']['merge_sha'], head])[:24]
    request = deepcopy(job['request'])
    for key in ('conversation_id', 'client_conversation_id', 'command_id', 'expected_revision', 'draft_repair'):
        request.pop(key, None)
    original = job.get('recovery_objective', request['objective'])
    request.update(branch='codex/recovery-' + identity, source_branch=request['base'], source_sha=head,
                   request_id='post-merge-' + identity,
                   objective=('Repair the observed failed main workflows after merge ' + job['data']['merge_sha']
                              + '. Preserve the original objective: ' + original)[:16000])
    accepted = effective_execution_budget(job['request'])
    calls, tokens = service.store.tx(lambda db: db.execute(
        'SELECT COUNT(*),COALESCE(SUM(reserved_tokens),0) FROM engineering_model_attempts WHERE job_id=? AND dispatched=1',
        (job['id'],)).fetchone())
    budget = dict(accepted, model_calls=accepted['model_calls'] - calls, model_tokens=accepted['model_tokens'] - tokens)
    if budget['model_calls'] <= budget['verification_calls'] + budget['final_calls'] or budget['model_tokens'] < 1024:
        return None
    request['execution_budget'] = budget
    existing = service.store.existing_request(job['principal'], request)
    if existing:
        return existing
    source = dict(job, data=dict(job['data'], head_sha=head))
    parent = service.backend.api(service.backend.root(job) + '/git/commits/' + head, job=job)
    prepare_source(source, service.backend.token(), parent['tree']['sha'], service.backend.cancellation(job))
    admission = service.preflight_submission(job['principal'], request)
    if not admission.get('ready'):
        raise ExecutionBindingError('POST_MERGE_RECOVERY_ADMISSION_REQUIRED', 'The recovery needs the current approved validation environment.')
    child = service.store.create(job['principal'], request, admission=admission, recovery_of=job)
    service.wake.set()
    return child


def inspect(service, job):
    recovery_id = job['data'].get('post_merge_recovery_job_id')
    if not recovery_id:
        return None
    child = service.store.get(recovery_id, job['principal'])
    result = dict(job['data']['post_merge'], status='PENDING', reason='MERGE_RECOVERY_RUNNING', recovery_job_id=recovery_id)
    if not child or child.get('recovery_of') != job['id']:
        return dict(result, status='BLOCKED', reason='MERGE_RECOVERY_IDENTITY_UNAVAILABLE')
    result['recovery_state'] = child['state']
    if child['state'] == 'COMPLETED' and child['data'].get('post_merge', {}).get('status') == 'PASSED':
        sha = child['data'].get('merge_sha')
        current = service.backend.ref(job, job['request']['base'])
        comparison = service.backend.api(service.backend.root(job) + '/compare/' + str(sha) + '...' + quote(current or '', safe=''), job=job)
        if comparison.get('status') in {'ahead', 'identical'} and comparison.get('merge_base_commit', {}).get('sha') == sha:
            return dict(result, status='PASSED', reason='MERGE_RECOVERY_VERIFIED', original_status='FAILED',
                        recovery_merge_sha=sha, recovery_verification=child['data']['post_merge'])
        return dict(result, status='BLOCKED', reason='MERGE_RECOVERY_REVISION_NOT_REACHABLE')
    if child['state'] in {'FAILED', 'CANCELLED', 'NEEDS_INPUT'}:
        return dict(result, status='BLOCKED', reason='MERGE_RECOVERY_NEEDS_INPUT')
    return result
