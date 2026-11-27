"""Local supervisor stops with original-owner fences and durable command readback.

The host does not become the admitted caller. Administrative intent is committed
before signalling an existing worker; retrying a command only reads that intent
and the original job. It never restarts work or repeats an uncertain stop.
"""
from contextlib import nullcontext
import hashlib
import json
import re

JOB_ID = re.compile(r'[A-Za-z0-9_-]{1,100}')
COMMAND_ID = re.compile(r'[A-Za-z0-9_.:-]{1,128}')
TARGET_FIELDS = {'gateway_id', 'principal_id', 'job_kind', 'revision', 'generation'}


class RetainedWorkError(ValueError):
    def __init__(self, code, message, status=409):
        super().__init__(message)
        self.code, self.status = code, status


def cancellation_outcome(kind, job):
    state = str(job.get('state') if kind != 'generation' else job.get('status', '')).upper()
    if kind == 'copilot':
        # An uncertain submission or timeout must remain visible for reconciliation.
        confirmed = bool((job.get('progress') or {}).get('remote_task_confirmed'))
        return 'already_terminal' if state == 'REJECTED' or confirmed and state in {'COMPLETED', 'FAILED', 'CANCELLED'} else 'unconfirmed'
    if state in {'CANCELLED', 'CANCELED'}:
        return 'confirmed'
    if state in {'COMPLETED', 'FAILED', 'INTERRUPTED'}:
        return 'already_terminal'
    if state == 'CANCELLING' or job.get('cancellation_requested') is True:
        return 'pending'
    return 'unconfirmed'


class RetainedWorkControl:
    def __init__(self, runtime, engineering):
        self.runtime, self.engineering = runtime, engineering

    @property
    def store(self):
        return self.engineering.store

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS gateway_job_admin_commands ('
                   'command_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, fingerprint TEXT NOT NULL, receipt_json TEXT NOT NULL)')

    @staticmethod
    def validate_id(job_id):
        if not isinstance(job_id, str) or not JOB_ID.fullmatch(job_id):
            raise RetainedWorkError('retained_work_not_found', 'The original job is not retained.', 404)

    def generation_owner(self, job_id, public, db=None):
        owners = {public['principal_id']} if isinstance(public.get('principal_id'), str) and public['principal_id'] else set()
        if db is not None and db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_generation_admissions'").fetchone():
            row = db.execute('SELECT principal FROM gateway_generation_admissions WHERE job_id=?', (job_id,)).fetchone()
            if row:
                owners.add(row[0])
        ownership = getattr(self.runtime, 'device_store', None)
        if ownership is not None:
            with ownership.connect() as saved:
                row = saved.execute('SELECT device_id FROM ownership WHERE job_id=?', (job_id,)).fetchone()
            if row:
                owners.add(row[0])
        return next(iter(owners)) if len(owners) == 1 else None

    def read(self, job_id):
        self.validate_id(job_id)
        gateway = self.store.conversations.gateway_id()
        if re.fullmatch(r'eng_[a-f0-9]{32}', job_id):
            task = self.store.get(job_id)
            if task is None:
                return None
            kind, principal = 'engineering', task['principal']
            job = self.engineering.job_response(task)
            revision, generation = task.get('revision'), None
        elif re.fullmatch(r'cp_[a-f0-9]{32}', job_id):
            task = self.engineering.copilot.get(job_id)
            if task is None:
                return None
            kind, principal = 'copilot', task['principal']
            job = self.engineering.copilot.public(task)
            revision, generation = task.get('revision'), None
        else:
            def saved(db):
                if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_jobs'").fetchone():
                    return None
                row = db.execute('SELECT public_json,status FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
                if row is None:
                    return None
                public = json.loads(row[0])
                if public.get('job_id', job_id) != job_id:
                    raise RetainedWorkError('retained_work_identity_changed', 'The retained job identity cannot be verified.')
                public.update(job_id=job_id, status=row[1])
                public.pop('result_preview', None)
                return public, self.generation_owner(job_id, public, db)
            retained = self.store.conversations.read(saved)
            if retained is None:
                return None
            job, principal = retained
            kind, revision, generation = 'generation', job.get('last_sequence', 0), job.get('generation')
        target = None
        if (kind != 'copilot' and principal and type(revision) is int and revision >= 0 and
                (kind == 'engineering' or isinstance(generation, str) and 0 < len(generation) <= 128)):
            target = dict(gateway_id=gateway, principal_id=principal, job_kind=kind, revision=revision, generation=generation)
        outcome = cancellation_outcome(kind, job)
        reconcile = kind == 'generation' and bool(job.get('pending_client_tool_call_ids'))
        if kind == 'engineering' and hasattr(self.store.conversations, 'read'):
            def uncertain(db):
                if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='engineering_operations'").fetchone():
                    return False
                return db.execute("SELECT 1 FROM engineering_operations WHERE job_id=? AND state!='COMPLETED' AND json_extract(payload,'$.dispatched')=1 LIMIT 1", (job_id,)).fetchone() is not None
            reconcile = self.store.conversations.read(uncertain)
        return dict(gateway_id=gateway, principal_id=principal, job_kind=kind, job=job, read_only=True,
                    control_target=target, cancel_available=target is not None and outcome == 'unconfirmed',
                    removal_available=outcome in {'confirmed', 'already_terminal'}, cancellation_outcome=outcome,
                    cancellation_scope='local_execution' if kind != 'copilot' else 'unsupported',
                    reconciliation_required=reconcile,
                    reconciliation_action=('Inspect the original client-tool handoff and its tool result receipts; stopping local continuation does not cancel or reverse a remote tool.' if kind == 'generation' else
                                           'Inspect the original dispatched operation receipts; stopping local execution does not reverse a GitHub change.') if reconcile else None)

    def listing(self, cursor=None, limit=100):
        """Bounded active-first pages across owners, with complete observed counts."""
        from v14.job_catalog import generation_summary, engineering_summary, copilot_summary
        if type(limit) is not int or not 1 <= limit <= 100:
            raise RetainedWorkError('invalid_retained_work_page', 'Use a bounded retained-work page.', 400)
        now = self.store.now()
        def read(db):
            gateway = self.store.conversations.identity(db)
            scope = [gateway, 'local:manager', 'retained-work']
            upper = db.execute('SELECT COALESCE(MAX(sequence),0) FROM gateway_catalog_keys').fetchone()[0]
            anchor = None
            if cursor:
                value = self.store.conversations.decode_cursor(db, cursor)
                anchor = value.get('anchor')
                if (value.get('scope') != scope or type(value.get('upper')) is not int or value['upper'] < 0 or
                        not isinstance(anchor, list) or len(anchor) != 2 or type(anchor[0]) is not int or anchor[0] not in (0, 1) or
                        type(anchor[1]) is not int or anchor[1] < 1):
                    raise RetainedWorkError('invalid_retained_work_page', 'Refresh this Gateway before reading more jobs.', 400)
                upper = value['upper']
            state_counts = dict.fromkeys(('running', 'queued', 'needs_attention', 'completed', 'failed', 'cancelled', 'other'), 0)
            def count(state, amount=1):
                state_counts[state if state in state_counts else 'other'] += amount
            items = []
            definitions = (
                ('generation', 'gateway_jobs', 'job_id', 'public_json', "LOWER(COALESCE(j.status,'')) NOT IN ('completed','failed','cancelled','canceled','interrupted')"),
                ('engineering', 'engineering_jobs', 'id', 'data', "j.state NOT IN ('COMPLETED','CANCELLED','FAILED')"),
                ('copilot', 'engineering_copilot_tasks', 'id', 'data', "j.state NOT IN ('COMPLETED','FAILED','CANCELLED','REJECTED')"))
            for kind, table, key, data, active in definitions:
                if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
                    continue
                if kind == 'generation':
                    for row in db.execute("SELECT status,json_extract(public_json,'$.scheduling_state'),COUNT(*) FROM gateway_jobs GROUP BY 1,2"):
                        count(generation_summary(dict(job_id='', status=row[0], scheduling_state=row[1]), now)['state'], row[2])
                    extra = ',j.status,j.job_id'
                elif kind == 'engineering':
                    for row in db.execute("SELECT state,lease,json_extract(data,'$.provider_wait.retry_at') FROM engineering_jobs"):
                        phase, lease, retry = row
                        waiting = type(retry) in (int, float) and retry > now
                        state = ('completed' if phase == 'COMPLETED' else 'cancelled' if phase == 'CANCELLED' else 'failed' if phase == 'FAILED' else
                                 'needs_attention' if phase in {'NEEDS_INPUT', 'MERGE_READY', 'CANCELLING'} else 'running' if lease and not waiting else 'queued')
                        count(state)
                    extra = ',j.state,j.revision,j.lease'
                else:
                    for phase, amount in db.execute('SELECT state,COUNT(*) FROM engineering_copilot_tasks GROUP BY state'):
                        count({'ACCEPTED': 'queued', 'QUEUED': 'queued', 'IN_PROGRESS': 'running', 'DISPATCHING': 'running',
                               'COMPLETED': 'completed', 'CANCELLED': 'cancelled', 'FAILED': 'failed', 'TIMED_OUT': 'failed',
                               'REJECTED': 'failed'}.get(phase, 'needs_attention'), amount)
                    extra = ''
                query = ('SELECT j.' + data + ',c.sequence,(' + active + ')' + extra + ' FROM ' + table + ' j '
                         'JOIN gateway_catalog_keys c ON c.job_kind=? AND c.job_id=j.' + key + ' WHERE c.sequence<=?')
                params = [kind, upper]
                if anchor:
                    query += ' AND ((' + active + '),c.sequence)<(?,?)'
                    params.extend(anchor)
                query += ' ORDER BY (' + active + ') DESC,c.sequence DESC LIMIT ?'
                params.append(limit + 1)
                for row in db.execute(query, params):
                    saved = json.loads(row[0])
                    if kind == 'generation':
                        if saved.setdefault('job_id', row[4]) != row[4]:
                            raise RetainedWorkError('retained_work_identity_changed', 'The retained job identity cannot be verified.')
                        saved['status'] = row[3]
                        saved['principal_id'] = self.generation_owner(saved['job_id'], saved, db)
                        item = generation_summary(saved, now)
                        item['title'] = str(saved.get('model') or 'Chat')[:160]
                    elif kind == 'engineering':
                        saved.update(state=row[3], revision=row[4])
                        item = engineering_summary(saved, row[5], now)
                        item['title'] = saved['request']['repository']
                    else:
                        item = copilot_summary(saved, now)
                    # This list is observation, never a transferable approval or
                    # an adopted client identity. Stop requires a fresh target.
                    item['allowed_actions'] = ['open'] + (['cancel'] if kind != 'copilot' and row[2] and item.get('principal_id') and not item.get('cancellation_requested') else [])
                    item.update(read_only=True, _order=[row[2], row[1]])
                    items.append(item)
            items.sort(key=lambda item: item['_order'], reverse=True)
            more, page = len(items) > limit, items[:limit]
            next_cursor = self.store.conversations.encode_cursor(db, dict(scope=scope, upper=upper, anchor=page[-1]['_order'])) if more else None
            for item in page:
                item.pop('_order')
            return dict(gateway_id=gateway, scope='local_supervisor', items=page, has_more=more, next_cursor=next_cursor,
                        counts_complete=True, state_counts=state_counts, server_now=now)
        return self.store.conversations.read(read)

    def _receipt(self, command_id):
        def read(db):
            if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_job_admin_commands'").fetchone():
                return None
            row = db.execute('SELECT job_id,fingerprint,receipt_json FROM gateway_job_admin_commands WHERE command_id=?', (command_id,)).fetchone()
            return (row[0], row[1], json.loads(row[2])) if row else None
        return self.store.conversations.read(read)

    def _record(self, receipt, fingerprint):
        def record(db):
            self.schema(db)
            old = db.execute('SELECT job_id,fingerprint,receipt_json FROM gateway_job_admin_commands WHERE command_id=?', (receipt['command_id'],)).fetchone()
            if old:
                if tuple(old[:2]) != (receipt['job_id'], fingerprint):
                    raise RetainedWorkError('cancel_command_changed', 'A cancellation command cannot change its original job target.')
                return json.loads(old[2])
            db.execute('INSERT INTO gateway_job_admin_commands VALUES(?,?,?,?)',
                       (receipt['command_id'], receipt['job_id'], fingerprint, json.dumps(receipt)))
            return None
        return self.store.tx(record)

    def _settle(self, receipt, fingerprint):
        # Only the first dispatcher may settle its intent. Repeated commands and
        # rejected targets cannot overwrite a previously retained outcome.
        self.store.tx(lambda db: db.execute("UPDATE gateway_job_admin_commands SET receipt_json=? WHERE command_id=? AND fingerprint=? AND json_extract(receipt_json,'$.status')='INTENT_RECORDED'",
                                           (json.dumps(receipt), receipt['command_id'], fingerprint)))

    def response(self, receipt):
        current = self.read(receipt['job_id'])
        target = receipt['target']
        bound = current and all(current.get(key) == target[key] for key in ('gateway_id', 'principal_id', 'job_kind'))
        if bound and target['job_kind'] == 'generation':
            bound = current['job'].get('generation') == target['generation']
        return dict(gateway_id=target['gateway_id'], principal_id=target['principal_id'],
                    job_id=receipt['job_id'], job_kind=target['job_kind'], command_id=receipt['command_id'],
                    dispatch_state=receipt.get('dispatch_state', 'unconfirmed'),
                    cancellation_outcome=current['cancellation_outcome'] if bound else 'unconfirmed',
                    removal_available=current['removal_available'] if bound else False,
                    cancellation_scope='local_execution', reconciliation_required=current['reconciliation_required'] if bound else True,
                    reconciliation_action=current['reconciliation_action'] if bound else 'Read the original job and operation receipts before another action.',
                    job=current['job'] if bound else None, receipt=receipt)

    def receipt(self, job_id, command_id):
        self.validate_id(job_id)
        if not isinstance(command_id, str) or not COMMAND_ID.fullmatch(command_id):
            raise RetainedWorkError('invalid_cancel_command', 'Choose the original cancellation command.', 400)
        record = self._receipt(command_id)
        if record is None or record[0] != job_id:
            raise RetainedWorkError('cancel_command_not_found', 'This cancellation command is not retained.', 404)
        return self.response(record[2])

    def cancel(self, job_id, payload):
        self.validate_id(job_id)
        if not isinstance(payload, dict) or set(payload) != TARGET_FIELDS | {'command_id'}:
            raise RetainedWorkError('invalid_cancel_command', 'Read the exact retained job before stopping it.', 400)
        command = payload['command_id']
        target = {key: payload[key] for key in TARGET_FIELDS}
        if (not isinstance(command, str) or not COMMAND_ID.fullmatch(command) or
                any(not isinstance(target[key], str) or not target[key] or len(target[key]) > 512 for key in ('gateway_id', 'principal_id')) or
                target['job_kind'] not in {'engineering', 'generation'} or type(target['revision']) is not int or target['revision'] < 0 or
                (target['job_kind'] == 'engineering' and target['generation'] is not None) or
                (target['job_kind'] == 'generation' and (not isinstance(target['generation'], str) or not 0 < len(target['generation']) <= 128))):
            raise RetainedWorkError('invalid_cancel_command', 'Read the exact retained job before stopping it.', 400)
        if target['gateway_id'] != self.store.conversations.gateway_id():
            raise RetainedWorkError('retained_work_identity_changed', 'The selected Gateway installation changed.')
        fingerprint = hashlib.sha256(json.dumps([job_id, target], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        old = self._receipt(command)
        if old is not None:
            if old[:2] != (job_id, fingerprint):
                raise RetainedWorkError('cancel_command_changed', 'A cancellation command cannot change its original job target.')
            return self.response(old[2])
        intent = dict(schema_version=1, actor='local:manager', command_id=command, job_id=job_id,
                      target=target, status='INTENT_RECORDED', dispatch_state='unconfirmed', cancellation_outcome='unconfirmed',
                      accepted_at=self.store.now())
        def reject(code, message, status=409, *, recorded=False):
            refused = dict(intent, status='NOT_DISPATCHED', dispatch_state='not_dispatched',
                           error_code=code, message=message, observed_at=self.store.now())
            if recorded:
                self._settle(refused, fingerprint)
            else:
                old = self._record(refused, fingerprint)
                if old is not None:
                    return self.response(old)
            raise RetainedWorkError(code, message, status)
        kind = 'engineering' if re.fullmatch(r'eng_[a-f0-9]{32}', job_id) else 'copilot' if re.fullmatch(r'cp_[a-f0-9]{32}', job_id) else 'generation'
        if target['job_kind'] != kind:
            return reject('retained_work_changed', 'The original job kind changed. Read it before confirming Stop.')
        # Generation progress and worker replacement use this same lock. Never
        # signal a different generation between target verification and Stop.
        lock = self.runtime.job_registry_lock if target['job_kind'] == 'generation' else nullcontext()
        with lock:
            if target['job_kind'] == 'generation':
                self.runtime.ensure_gateway_job_loaded(job_id)
            observed = self.read(job_id)
            if observed is None:
                return reject('retained_work_not_found', 'The original job is not retained.', 404)
            if observed['control_target'] != target:
                return reject('retained_work_changed', 'The original job changed. Read it before confirming Stop.')
            if target['job_kind'] == 'generation':
                live = self.runtime.job_registry.get(job_id)
                if not live or live.get('generation') != target['generation']:
                    return reject('retained_work_changed', 'The original worker generation changed.')
            repeated = self._record(intent, fingerprint)
            if repeated is not None:
                return self.response(repeated)
            if observed['cancellation_outcome'] == 'unconfirmed':
                if target['job_kind'] == 'engineering':
                    from v14.engineering import EngineeringControlRejected
                    try:
                        self.store.control(job_id, target['principal_id'], 'cancel', target['revision'])
                    except EngineeringControlRejected:
                        return reject('retained_work_changed', 'The original job changed before Stop was applied. Read it before confirming again.', recorded=True)
                else:
                    self.runtime.cancel_gateway_job(job_id)
            current = self.response(intent)
            final = dict(intent, status='OBSERVED', cancellation_outcome=current['cancellation_outcome'],
                         observed_state=(current['job'] or {}).get('state', (current['job'] or {}).get('status')),
                         observed_at=self.store.now())
            self._settle(final, fingerprint)
            return self.response(final)
