"""Durable physical mutation intent and uncertainty, separate from stage leases."""
import json
import time

from v14.execution_binding import ExecutionBindingError, canonical, fingerprint
from v14.debug import diagnostic_execution_event


class EngineeringOperations:
    def __init__(self, service):
        self.service, self.store = service, service.store

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_operations (id TEXT PRIMARY KEY,job_id TEXT NOT NULL,revision INTEGER NOT NULL,state TEXT NOT NULL,payload TEXT NOT NULL)')

    def prepare(self, job, method, path, body):
        self.service.validate_mutation(job)
        identity = 'operation_' + fingerprint([job['id'], job['revision'], method, path, body])[:40]
        def read(db):
            self.schema(db)
            row = db.execute('SELECT payload FROM engineering_operations WHERE id=? AND job_id=?', (identity, job['id'])).fetchone()
            return json.loads(row[0]) if row else None
        existing = self.store.tx(read)
        if existing:
            if existing['state'] == 'COMPLETED':
                return existing
            if existing['dispatched']:
                error = ExecutionBindingError('EXECUTION_OPERATION_UNKNOWN_OUTCOME', 'This exact mutation was dispatched. Reconcile its remote outcome before any retry.')
                error.dispatched = True
                raise error
        request_ref = self.service.artifacts.put_json(job, {'method': method, 'path': path, 'body': body}, 'operation-intent')
        record = {'schema_version': 1, 'operation_id': identity, 'job_id': job['id'], 'revision': job['revision'],
                  'binding_id': job['request'].get('execution_binding_id'), 'lease_id': job['_execution_lease'],
                  'owner_epoch': job['_owner_epoch'], 'method': method, 'path': path,
                  'request_ref': request_ref, 'state': 'PREPARED', 'dispatched': False,
                  'created_at_ms': int(self.store.now() * 1000), 'reconciliation': 'Inspect the exact branch, commit, pull request or merge resource recorded by this operation; do not infer failure from lease turnover.'}
        self.save(job, record)
        return record

    def save(self, job, record):
        def write(db):
            self.schema(db)
            previous = db.execute('SELECT payload FROM engineering_operations WHERE id=?', (record['operation_id'],)).fetchone()
            if previous:
                previous = json.loads(previous[0])
                if previous.get('dispatched') and not record.get('dispatched'):
                    raise ExecutionBindingError('EXECUTION_OPERATION_UNKNOWN_OUTCOME', 'A prior physical dispatch cannot be erased by a stale owner or precheck rejection.')
                if previous['state'] == 'COMPLETED' and record['state'] != 'COMPLETED':
                    raise ExecutionBindingError('EXECUTION_OPERATION_ALREADY_COMPLETED', 'Confirmed mutation evidence cannot be downgraded by a late failure.')
            if record['state'] == 'DISPATCHED':
                row = db.execute('SELECT state,revision,lease FROM engineering_jobs WHERE id=?', (job['id'],)).fetchone()
                epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
                if row != (job['state'], job['revision'], job['_execution_lease']) or not epoch or epoch[0] != job['_owner_epoch']:
                    raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The operation owner changed before mutation dispatch.')
            db.execute('INSERT INTO engineering_operations VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,payload=excluded.payload',
                       (record['operation_id'], job['id'], job['revision'], record['state'], canonical(record)))
        self.store.tx(write)
        job['data']['latest_operation'] = dict(record)
        diagnostic_execution_event(self.store.runtime, job['principal'], 'GITHUB_OPERATION',
            binding_id=record.get('binding_id'), job_id=job['id'], operation_id=record['operation_id'],
            state=record['state'], dispatched=record['dispatched'], error_code=record.get('error_code'))

    def dispatch(self, job, record):
        if record.get('dispatched'):
            error = ExecutionBindingError('EXECUTION_OPERATION_UNKNOWN_OUTCOME', 'This operation already has a dispatch receipt. Reconcile it before retrying.')
            error.dispatched = True
            raise error
        try:
            self.service.validate_mutation(job)
            record.update(state='DISPATCHED', dispatched=True, dispatched_at_ms=int(time.time() * 1000))
            self.save(job, record)
        except Exception as error:
            record.update(state='PRECHECK_REJECTED', dispatched=False, error_code=getattr(error, 'code', type(error).__name__))
            self.save(job, record)
            raise

    def finish(self, job, record, value, status):
        reference = self.service.artifacts.put_json(job, value, 'operation-result')
        record.update(state='COMPLETED' if 200 <= status < 300 else 'FAILED' if 400 <= status < 500 else 'UNKNOWN_OUTCOME',
                      response_ref=reference, http_status=status, completed_at_ms=int(time.time() * 1000))
        self.save(job, record)

    def failed(self, job, record, error):
        if record['state'] == 'DISPATCHED':
            record.update(state='UNKNOWN_OUTCOME', error_code=getattr(error, 'code', type(error).__name__))
            self.save(job, record)

    def page(self, job, after=0):
        def read(db):
            self.schema(db)
            rows = db.execute('SELECT rowid,payload FROM engineering_operations WHERE job_id=? AND rowid>? ORDER BY rowid LIMIT 51', (job['id'], after)).fetchall()
            return {'operations': [json.loads(row[1]) for row in rows[:50]], 'next_cursor': rows[min(50, len(rows)) - 1][0] if rows else after, 'has_more': len(rows) > 50}
        return self.store.tx(read)

    def dispatched(self, job):
        def read(db):
            self.schema(db)
            rows = db.execute('SELECT payload FROM engineering_operations WHERE job_id=? AND revision=?', (job['id'], job['revision'])).fetchall()
            return any(json.loads(row[0]).get('dispatched') is True for row in rows)
        return self.store.tx(read)
