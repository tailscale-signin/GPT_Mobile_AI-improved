"""Small workspace checkpoints in the existing fenced engineering job row."""
import json

from v14.execution_binding import ExecutionBindingError, canonical

KEYS = {'workspace_workers', 'workspace_next_edits', 'workspace_summaries', 'workspace_task_plan',
        'workspace_tool_profile', 'workspace_integrations', 'workspace_context_pack'}


def save(backend, job, key, value, *, member=None):
    if key not in KEYS:
        raise ValueError('Unsupported workspace checkpoint key')
    # Pure callers without a durable job may inspect helpers in tests; actual
    # executor jobs always carry the lease and use the fenced transaction below.
    if '_execution_lease' not in job:
        return
    store = getattr(getattr(backend, 'private_artifacts', None), 'store', None)
    if store is None:
        raise ExecutionBindingError('WORKSPACE_CHECKPOINT_REQUIRED', 'Durable workspace storage is unavailable')
    raw = canonical(value)
    if len(raw.encode()) > 128 * 1024:
        raise ValueError('Workspace checkpoint exceeds its private inline budget')
    def write(db):
        row = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?',
                         (job['id'], job['principal'])).fetchone()
        epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
        if (not row or tuple(row[1:]) != (job['state'], job['revision'], job.get('_execution_lease')) or
                row[3] is None or not epoch or epoch[0] != job.get('_owner_epoch')):
            raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The workspace checkpoint owner changed')
        stored = json.loads(row[0])
        data = stored.setdefault('data', {})
        if member is None:
            data[key] = json.loads(raw)
        else:
            data.setdefault(key, {})[member] = json.loads(raw)
        db.execute('UPDATE engineering_jobs SET data=? WHERE id=? AND lease=?',
                   (canonical(stored), job['id'], job['_execution_lease']))
    store.tx(write)
