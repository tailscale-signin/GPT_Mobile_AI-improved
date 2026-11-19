"""Inspect retained attempts and restore only an unpublished local candidate."""
import json
from copy import deepcopy

from v14.engineering_workspace_checks import retained_candidate
from v14.engineering_operations import EngineeringOperations


def history(service, principal, job_id):
    job = service.evidence_access(principal, job_id)
    data = job['data']
    rows = []
    for saved in data.get('workspace_checks', {}).values():
        rows.append({key: saved.get(key) for key in ('check_id', 'source_sha', 'patch_identity',
            'check_index', 'status', 'reason', 'exit_code', 'attempt_id', 'candidate_ref', 'output_ref')})
    return {'job_id': job['id'], 'revision': job['revision'], 'head_sha': data.get('head_sha'),
            'state': job['state'], 'candidates': rows[-24:], 'updates': service.store.updates(job_id)[-200:],
            'published': bool(data.get('commits') or data.get('pr')), 'remote_history_rewritten': False}


def rewind(service, principal, job_id, revision, check_id):
    job = service.evidence_access(principal, job_id)
    if job['revision'] != revision or job['state'] != 'NEEDS_INPUT':
        raise ValueError('Pause at an exact reviewable checkpoint before restoring a candidate')
    service.artifacts.restore_stage(job)
    changes = retained_candidate(service.backend, job, check_id)
    if not changes:
        raise ValueError('The selected candidate contains no changes')
    head = job['data']['head_sha']
    current = service.backend.ref(job, job['request']['branch'])
    if current is not None and current != head:
        raise ValueError('The working branch changed; refresh before restoring')
    prepared = service.artifacts.checkpoint(job, {'changes': changes}, 'rewind-candidate')['changes']
    def update(db):
        row = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job_id, principal)).fetchone()
        stored = service.store.decode(row)
        if not stored or row[3] or stored['revision'] != revision or stored['state'] != 'NEEDS_INPUT':
            raise ValueError('The job changed before candidate restoration')
        data = stored['data']
        EngineeringOperations.schema(db)
        # Even a PREPARED or uncertain mutation means this is no longer a purely
        # local draft. Never rewind its bookkeeping or erase remote receipts.
        dispatched = db.execute('SELECT 1 FROM engineering_operations WHERE job_id=? LIMIT 1', (job_id,)).fetchone()
        if dispatched or data.get('commits') or data.get('pr') or data.get('head_sha') != head:
            raise ValueError('Only unpublished local candidates can be restored; published work requires a new change')
        data.update(changes=prepared, summary='Restore retained candidate ' + check_id[:12])
        for key in ('review', 'local_validation', 'readiness', 'completion_verification', 'final_report', 'blocked_reason'):
            data.pop(key, None)
        data['checkpoint_history'] = (data.get('checkpoint_history', []) + [
            {'action': 'restore_candidate', 'check_id': check_id, 'source_sha': head, 'from_revision': revision}])[-64:]
        stored.update(approval=None, updated_at=service.store.now(), message='Candidate restored locally. Complete validation and review will run again.')
        db.execute("UPDATE engineering_jobs SET data=?,state='REVIEW',revision=revision+1,next_at=? WHERE id=? AND revision=? AND lease IS NULL",
                   (json.dumps(stored), service.store.now(), job_id, revision))
        service.store.event(db, stored, 'REVIEW', revision + 1, service.store.now())
        service.store.conversations.engineering_projection(db, stored, 'REVIEW')
        return dict(stored, state='REVIEW', revision=revision + 1)
    result = service.store.tx(update)
    service.wake.set()
    return result


def candidate_details(service, principal, job_id, check_id, offset=0):
    import difflib
    job = service.evidence_access(principal, job_id)
    service.artifacts.restore_stage(job)
    changes = retained_candidate(service.backend, job, check_id)
    if type(offset) is not int or offset < 0:
        raise ValueError('Select a positive diff offset or zero')
    parts, position = [], 0
    end = offset + 16000
    for change in changes:
        before = job['data'].get('files', {}).get(change['path'], {}).get('content', '')
        for line in difflib.unified_diff(before.splitlines(True), (change['content'] or '').splitlines(True),
                                         fromfile=change['path'], tofile=change['path']):
            after = position + len(line)
            if after > offset and position < end:
                parts.append(line[max(0, offset - position):end - position])
            position = after
            if position > end:
                break
        if position > end:
            break
    if offset > position:
        raise ValueError('Diff offset exceeds the candidate')
    truncated = position > end
    saved = job['data']['workspace_checks'][check_id]
    service.evidence_access(principal, job_id)
    return {'job_id': job_id, 'check_id': check_id, 'head_sha': job['data']['head_sha'],
            'diff': ''.join(parts), 'truncated': truncated, 'offset': offset, 'next_offset': end if truncated else None, 'status': saved['status'],
            'output': saved.get('output_tail', '')[-16000:] if offset == 0 else '', 'scope': 'retained_candidate'}
