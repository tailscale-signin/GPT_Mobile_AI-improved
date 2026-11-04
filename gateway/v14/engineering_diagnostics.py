"""Bounded, owner-scoped transport probes. Never invoke a model or mutate a repository."""
import hashlib
import json
import re


def diagnostic_job(store, principal, request_id=None, job_id=None, after=0, limit=10):
    if request_id is not None and (not isinstance(request_id, str) or not re.fullmatch(r'[a-f0-9]{64}', request_id)):
        raise ValueError('Expected a hashed request identity')
    if request_id is None and (not isinstance(job_id, str) or not re.fullmatch(r'diag_[a-f0-9]{32}', job_id)):
        raise ValueError('Invalid diagnostic job identity')
    if type(after) is not int or after not in range(4) or type(limit) is not int or not 1 <= limit <= 10:
        raise ValueError('Invalid event cursor')
    if request_id is not None:
        job_id = 'diag_' + hashlib.sha256((principal + '\0' + request_id).encode()).hexdigest()[:32]

    def operation(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_diagnostics ('
                   'id TEXT PRIMARY KEY, principal TEXT NOT NULL, request_id TEXT NOT NULL, created REAL NOT NULL, '
                   'UNIQUE(principal, request_id))')
        db.execute('DELETE FROM engineering_diagnostics WHERE created < ?', (store.now() - 7 * 86400,))
        row = db.execute('SELECT id FROM engineering_diagnostics WHERE id=? AND principal=?', (job_id, principal)).fetchone()
        if not row and request_id is not None:
            if db.execute('SELECT COUNT(*) FROM engineering_diagnostics').fetchone()[0] >= 10000:
                raise ValueError('Diagnostic retention capacity reached')
            db.execute('INSERT INTO engineering_diagnostics VALUES(?,?,?,?)', (job_id, principal, request_id, store.now()))
            row = (job_id,)
        if not row:
            return None
        events = [{'sequence': n, 'kind': kind} for n, kind in enumerate(('accepted', 'probe_executed', 'completed'), 1) if n > after][:limit]
        return {'id': row[0], 'kind': 'transport_probe', 'status': 'COMPLETED', 'protocol_version': 1,
                'events': events, 'next_sequence': events[-1]['sequence'] if events else after,
                'has_more': bool(events and events[-1]['sequence'] < 3), 'repository_actions': 0, 'model_invocations': 0}
    return store.tx(operation)
