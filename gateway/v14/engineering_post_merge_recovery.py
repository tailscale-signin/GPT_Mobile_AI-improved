"""Repair confirmed main-build failures through a new, normally gated job.

The merged job and its approval are immutable. A recovery has a new branch/PR,
shares the remaining deadline/budget, and requires its own exact-head approval.
"""
from copy import deepcopy
import re
from urllib.parse import quote

from v14.engineering_defaults import effective_execution_budget
from v14.execution_binding import ExecutionBindingError


def enqueue(service, job, failed):
    from v14.engineering import digest
    from v14.engineering_source import prepare_source

    if failed.get('status') != 'FAILED' or failed.get('reason') != 'MERGE_WORKFLOW_FAILED':
        return None
    service.store.assert_claim(job)
    existing = service.store.recovery_for(job)
    if existing is not None:
        return existing
    if job.get('recovery_depth', 0) >= job['request']['repair_attempts']:
        return None
    if service.store.now() >= job['deadline'] - 60:
        return None
    service.validate_execution(job, force=True)
    head = failed['branch_head']
    identity = digest([job['id'], job['data']['merge_sha']])[:24]
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
    source = dict(job, data=dict(job['data'], head_sha=head))
    parent = service.backend.api(service.backend.root(job) + '/git/commits/' + head, job=job)
    prepare_source(source, service.backend.token(), parent['tree']['sha'], service.backend.cancellation(job))
    admission = service.preflight_submission(job['principal'], request)
    if not admission.get('ready'):
        raise ExecutionBindingError('POST_MERGE_RECOVERY_ADMISSION_REQUIRED', 'The recovery needs the current approved validation environment.')
    job['data']['post_merge'] = deepcopy(failed)
    child = service.store.create(job['principal'], request, admission=admission, recovery_of=job)
    job['data']['post_merge_recovery_job_id'] = child['id']
    job['data']['recovery_budget_reservation'] = child['request']['execution_budget']
    service.wake.set()
    return child


def inspect(service, job):
    unavailable = {'merge_sha': job['data'].get('merge_sha'), 'origin_evidence': 'UNAVAILABLE'}
    original = job['data'].get('post_merge_recovery_origin', unavailable)
    result = dict(original, status='PENDING', reason='MERGE_RECOVERY_RUNNING')
    try:
        child = service.store.recovery_for(job)
    except ExecutionBindingError as error:
        return dict(result, status='BLOCKED', reason=error.code)
    if child is None:
        return None
    original = job['data'].get('post_merge_recovery_origin', unavailable)
    result = dict(original, status='PENDING', reason='MERGE_RECOVERY_RUNNING', recovery_job_id=child['id'])
    result.update(recovery_state=child['state'], recovery_pr=child['data'].get('pr'),
                  recovery_action='APPROVE_RECOVERY' if child['state'] == 'MERGE_READY' else
                  'REVIEW_RECOVERY' if child['state'] == 'NEEDS_INPUT' else None)
    if child['state'] == 'COMPLETED' and child['data'].get('post_merge', {}).get('status') == 'PASSED':
        # A failed intermediate merge can qualify only through its retained
        # descendant. Recheck the final qualified revision, not that ancestor.
        seen, claimed_shas, candidate = {job['id']}, [], child
        try:
            for _ in range(5):
                if candidate['id'] in seen or candidate['state'] != 'COMPLETED':
                    raise ValueError('Invalid recovery chain')
                seen.add(candidate['id'])
                verification = candidate['data'].get('post_merge', {})
                if verification.get('status') != 'PASSED':
                    raise ValueError('Unqualified recovery')
                descendant = service.store.recovery_for(candidate)
                if descendant is not None:
                    if (verification.get('reason') != 'MERGE_RECOVERY_VERIFIED'
                            or verification.get('recovery_job_id') != descendant['id']):
                        raise ValueError('Recovery receipt names another child')
                    claimed_shas.append(verification.get('recovery_merge_sha'))
                    if verification.get('qualified_revision') is not None:
                        claimed_shas.append(verification['qualified_revision'])
                    candidate = descendant
                    continue
                sha = candidate['data'].get('merge_sha')
                if (not isinstance(sha, str) or not re.fullmatch('[0-9a-f]{40}', sha)
                        or verification.get('merge_sha') != sha
                        or verification.get('qualified_revision', sha) != sha
                        or verification.get('reason') == 'MERGE_RECOVERY_VERIFIED'
                        or any(claim != sha for claim in claimed_shas)):
                    raise ValueError('Recovery receipt does not bind the qualified revision')
                break
            else:
                raise ValueError('Recovery chain exceeds its admitted depth')
        except (ExecutionBindingError, KeyError, TypeError, ValueError):
            return dict(result, status='BLOCKED', reason='MERGE_RECOVERY_IDENTITY_UNAVAILABLE')
        current = service.backend.ref(job, job['request']['base'])
        comparison = service.backend.api(service.backend.root(job) + '/compare/' + str(sha) + '...' + quote(current or '', safe=''), job=job)
        if comparison.get('status') in {'ahead', 'identical'} and comparison.get('merge_base_commit', {}).get('sha') == sha:
            return dict(result, status='PASSED', reason='MERGE_RECOVERY_VERIFIED',
                        original_status='FAILED' if original.get('status') == 'FAILED' else 'UNKNOWN',
                        recovery_merge_sha=sha, qualified_revision=sha,
                        recovery_verification=child['data']['post_merge'])
        return dict(result, status='BLOCKED', reason='MERGE_RECOVERY_REVISION_NOT_REACHABLE')
    if child['state'] in {'FAILED', 'CANCELLED'}:
        return dict(result, status='BLOCKED', reason='MERGE_RECOVERY_NEEDS_INPUT')
    return result
