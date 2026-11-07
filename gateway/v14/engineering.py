"""Durable engineering state machine and supervisor. No model can grant approval."""
import json
import re
import threading
import sqlite3
import time
import uuid
import logging
from contextlib import asynccontextmanager
import asyncio

from v14.actions import canonical_json
from v14.batch import digest, retry_delay
from v14.evidence import EvidenceLedger, completion_gate
from v14.engineering_memory import EngineeringMemory
from v14.execution_binding import ExecutionBindings, ExecutionBindingError, fingerprint
from v14.inference_capabilities import InferenceCapabilities
from v14.inference_scheduler import install_scheduler
from v14.engineering_artifacts import EngineeringArtifacts
from v14.engineering_operations import EngineeringOperations
from v14.debug import capture_context_snapshot, execution_diagnostic_context

PHASES = ('PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'CI', 'PROMOTE', 'MERGE_READY', 'MERGE', 'VERIFY', 'COMPLETED')
PAUSED = {'MERGE_READY', 'NEEDS_INPUT', 'COMPLETED', 'CANCELLED'}
MUTATIONS = {'COMMIT', 'OPEN_PR', 'PROMOTE', 'MERGE'}
SHA = re.compile(r'^[0-9a-f]{40}$')


def principal_authorized(config, principal, devices):
    if config.mode == 'open_network':
        return config.remote_engineering and principal == 'network:shared'
    if config.mode == 'loopback':
        return principal == 'local:single-user' or config.legacy_bearer and any(row[0] == principal and not row[2] for row in devices.list_devices())
    return any(principal == 'tailnet:' + grant['principalId'] and 'engineering' in grant.get('scopes', []) for grant in config.trusted_users.values())


def validate_request(request):
    allowed = {'repository', 'branch', 'base', 'objective', 'model', 'duration_hours', 'repair_attempts', 'chat_id', 'request_id', 'acceptance', 'profile_id', 'memory_mode', 'execution_binding_id', 'execution_budget'}
    if not isinstance(request, dict) or set(request) - allowed:
        raise ValueError('Unsupported engineering request fields')
    result = dict(request)
    binding_id = result.get('execution_binding_id')
    if binding_id is not None and (not isinstance(binding_id, str) or not re.fullmatch(r'bind_[a-f0-9]{32}', binding_id)):
        raise ExecutionBindingError('EXECUTION_BINDING_INVALID', 'Use the verified binding returned by this gateway.')
    budget = result.get('execution_budget', {})
    if not isinstance(budget, dict) or set(budget) - {'model_calls', 'model_tokens', 'verification_calls', 'final_calls', 'final_seconds'}:
        raise ValueError('Unsupported engineering budget')
    for key, value in budget.items():
        maximum = 8388608 if key == 'model_tokens' else 900 if key == 'final_seconds' else 256
        if type(value) is not int or not 1 <= value <= maximum:
            raise ValueError('Invalid engineering budget')
    if result.get('memory_mode', 'HYBRID') not in {'SESSION_ONLY', 'PERSISTENT', 'HYBRID'}:
        raise ValueError('Invalid engineering memory mode')
    if 'profile_id' in result and (not isinstance(result['profile_id'], str) or not 1 <= len(result['profile_id']) <= 128):
        raise ValueError('Invalid engineering profile scope')
    if not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', str(result.get('repository', ''))):
        raise ValueError('Select a valid repository')
    for key in ('branch', 'base'):
        value = result.get(key, 'main' if key == 'base' else '')
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./-]{0,199}', value) or any(x in value for x in ('..', '//', '@{')) or value.endswith(('/', '.', '.lock')):
            raise ValueError('Invalid branch')
        result[key] = value
    if result['branch'] == result['base']:
        raise ValueError('Engineering jobs require a separate working branch')
    for key, maximum in (('objective', 16000), ('model', 200), ('request_id', 128)):
        if not isinstance(result.get(key), str) or not 1 <= len(result[key].strip()) <= maximum:
            raise ValueError('Missing or oversized ' + key)
    hours, retries = result.get('duration_hours', 3), result.get('repair_attempts', 5)
    if type(hours) is not int or hours not in (0, 3, 6, 12) or type(retries) is not int or not 1 <= retries <= 5:
        raise ValueError('Choose 3, 6, 12 hours or bounded until-complete; repair attempts are 1–5')
    result.update(duration_hours=hours, repair_attempts=retries)
    acceptance = result.get('acceptance', [])
    if not isinstance(acceptance, list) or len(acceptance) > 256:
        raise ValueError('At most 256 explicit acceptance requirements are supported')
    ids = set()
    for requirement in acceptance:
        if not isinstance(requirement, dict) or not {'id', 'paths', 'checks'} <= requirement.keys() or requirement.keys() - {'id', 'paths', 'checks', 'evidence'}:
            raise ValueError('Acceptance needs id, paths and checks')
        if not isinstance(requirement['id'], str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', requirement['id']) or requirement['id'] in ids:
            raise ValueError('Acceptance IDs must be unique and bounded')
        ids.add(requirement['id'])
        specifications = requirement.get('evidence', [])
        if not isinstance(specifications, list) or len(specifications) > 32:
            raise ValueError('Requirement evidence must be bounded')
        for specification in specifications:
            if not isinstance(specification, dict) or set(specification) != {'kind', 'name'} or specification['kind'] not in {'behavior_test', 'api_receipt', 'device_trace', 'model_runtime', 'artifact'} or not isinstance(specification['name'], str) or not 1 <= len(specification['name']) <= 200:
                raise ValueError('Requirement evidence needs a supported kind and exact case/resource name')
        for key in ('paths', 'checks'):
            if not isinstance(requirement[key], list) or len(requirement[key]) > 32 or any(not isinstance(item, str) or not item.strip() or len(item) > 400 for item in requirement[key]):
                raise ValueError('Acceptance paths/checks must be bounded strings')
        if not requirement['paths'] or not requirement['checks']:
            raise ValueError('Each acceptance requirement needs changed paths and successful CI check names')
        if any(path.startswith('/') or '\\' in path or any(part in {'', '.', '..', '.git'} for part in path.split('/')) for path in requirement['paths']):
            raise ValueError('Acceptance paths must be repository-relative files')
    if acceptance:
        result['acceptance'] = acceptance
    if type(result.get('chat_id', 0)) is not int or result.get('chat_id', 0) < 0:
        raise ValueError('Invalid conversation')
    return result


class EngineeringStore:
    def __init__(self, runtime, now=time.time):
        self.runtime, self.now = runtime, now
        self.bindings = None

    def tx(self, operation):
        if not self.runtime.GATEWAY_DURABLE_JOBS:
            raise ValueError('Engineering requires durable gateway storage')
        if not self.runtime.durable_job_initialized:
            self.runtime.init_durable_job_store()
        with self.runtime.durable_job_lock:
            db = self.runtime._durable_connect()
            try:
                with db:
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_jobs ('
                               'id TEXT PRIMARY KEY, principal TEXT NOT NULL, request_id TEXT NOT NULL, request_digest TEXT NOT NULL, '
                               'state TEXT NOT NULL, revision INTEGER NOT NULL, next_at REAL NOT NULL, lease TEXT, data TEXT NOT NULL, '
                               'UNIQUE(principal,request_id))')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_receipts ('
                               'job_id TEXT NOT NULL, revision INTEGER NOT NULL, phase TEXT NOT NULL, status TEXT NOT NULL, '
                               'input_digest TEXT NOT NULL, output_json TEXT, PRIMARY KEY(job_id,revision))')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_events ('
                               'job_id TEXT NOT NULL, revision INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(job_id,revision))')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_webhooks (principal TEXT NOT NULL, delivery TEXT NOT NULL, digest TEXT NOT NULL, received_at REAL NOT NULL, PRIMARY KEY(principal,delivery))')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_owner_epochs (job_id TEXT PRIMARY KEY, epoch INTEGER NOT NULL)')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_model_attempts ('
                               'id TEXT PRIMARY KEY, job_id TEXT NOT NULL, binding_id TEXT NOT NULL, revision INTEGER NOT NULL, '
                               'state TEXT NOT NULL, dispatched INTEGER NOT NULL, reserved_tokens INTEGER NOT NULL, payload TEXT NOT NULL)')
                    return operation(db)
            finally:
                db.close()

    def accept_webhook(self, principal, delivery, body_digest):
        """Durable replay filter. A delivery identifier cannot be rebound to another payload."""
        def accept(db):
            existing = db.execute('SELECT digest FROM engineering_webhooks WHERE principal=? AND delivery=?', (principal, delivery)).fetchone()
            if existing:
                if existing[0] != body_digest:
                    raise PermissionError('Webhook delivery identifier changed payload')
                return False
            db.execute('DELETE FROM engineering_webhooks WHERE received_at < ?', (self.now() - 7 * 86400,))
            if db.execute('SELECT COUNT(*) FROM engineering_webhooks').fetchone()[0] >= 100000:
                raise ValueError('Webhook replay filter is full; polling remains available')
            db.execute('INSERT INTO engineering_webhooks VALUES(?,?,?,?)', (principal, delivery, body_digest, self.now()))
            return True
        return self.tx(accept)

    @staticmethod
    def decode(row):
        if row is None:
            return None
        job = json.loads(row[0])
        job.update(state=row[1], revision=row[2])
        return job

    @staticmethod
    def event(db, job, state, revision, now):
        value = {'revision': revision, 'state': state, 'message': job['message'], 'created_at': now}
        db.execute('INSERT OR REPLACE INTO engineering_events VALUES(?,?,?)', (job['id'], revision, json.dumps(value)))

    def updates(self, job_id, after=-1, through=2**63 - 1):
        return self.tx(lambda db: [json.loads(row[0]) for row in db.execute(
            'SELECT data FROM engineering_events WHERE job_id=? AND revision>? AND revision<=? ORDER BY revision LIMIT 201', (job_id, after, through))])

    def create(self, principal, request):
        request = validate_request(request)
        request_hash = digest(request)
        existing = self.existing_request(principal, request)
        if existing:
            return existing
        if self.bindings is None:
            raise ExecutionBindingError('EXECUTION_BINDING_REQUIRED', 'Verify the selected execution profile before admitting a new job.')
        binding = self.bindings.read(principal, request.get('execution_binding_id'))
        if request['model'] != binding['requested_model_id'] or request.get('profile_id') != binding['profile_uid']:
            raise ExecutionBindingError('EXECUTION_SELECTION_MISMATCH', 'The job model/profile differs from its immutable execution binding.')
        now = self.now()
        job = {'id': 'eng_' + uuid.uuid4().hex, 'principal': principal, 'request': request,
               'created_at': now, 'deadline': now + (request['duration_hours'] or 72) * 3600,
               'updated_at': now, 'steps': 0, 'milestones': [], 'repairs': {}, 'patches': [], 'stalls': 0,
               'message': 'Queued for repository inspection', 'data': {}, 'approval': None,
               'execution_binding': binding}
        capture = capture_context_snapshot(principal, self.runtime)
        if capture is not None:
            job['diagnostic_capture'] = capture
        def insert(db):
            old = db.execute('SELECT data,state,revision,request_digest FROM engineering_jobs WHERE principal=? AND request_id=?', (principal, request['request_id'])).fetchone()
            if old:
                if old[3] != request_hash:
                    raise ValueError('Request identity was reused with different settings')
                return self.decode(old[:3])
            if db.execute("SELECT COUNT(*) FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED')").fetchone()[0] >= 32:
                raise ValueError('Engineering job capacity reached')
            if db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0] >= 4096:
                raise ValueError('Engineering history capacity reached; archive the journal before starting another job')
            db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,0,?,NULL,?)',
                       (job['id'], principal, request['request_id'], request_hash, 'PLAN', now, json.dumps(job)))
            self.event(db, job, 'PLAN', 0, now)
            return dict(job, state='PLAN', revision=0)
        return self.tx(insert)

    def existing_request(self, principal, request):
        """Reconcile a lost create receipt without admitting new work or changing the request identity."""
        request = validate_request(request)
        def read(db):
            row = db.execute('SELECT data,state,revision,request_digest FROM engineering_jobs WHERE principal=? AND request_id=?',
                             (principal, request['request_id'])).fetchone()
            if row and row[3] != digest(request):
                raise ValueError('Request identity was reused with different settings')
            return self.decode(row[:3]) if row else None
        return self.tx(read)

    def get(self, job_id, principal=None):
        def read(db):
            row = db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=?' + (' AND principal=?' if principal else ''),
                             (job_id, principal) if principal else (job_id,)).fetchone()
            return self.decode(row)
        return self.tx(read)

    def list(self, principal):
        return self.tx(lambda db: [self.decode(row) for row in db.execute('SELECT data,state,revision FROM engineering_jobs WHERE principal=? ORDER BY next_at DESC LIMIT 100', (principal,))])

    def due(self):
        return self.tx(lambda db: [row[0] for row in db.execute("SELECT id FROM engineering_jobs WHERE lease IS NULL AND next_at<=? AND state NOT IN ('MERGE_READY','NEEDS_INPUT','COMPLETED','CANCELLED') ORDER BY next_at LIMIT 2", (self.now(),))])

    def recover(self):
        # The composition root holds the process-wide state-directory lock.
        self.tx(lambda db: db.execute('UPDATE engineering_jobs SET lease=NULL'))

    def wake_checks(self, principal):
        self.tx(lambda db: db.execute('UPDATE engineering_jobs SET next_at=? WHERE principal=? AND state=? AND lease IS NULL', (self.now(), principal, 'CI')))

    def claim(self, job_id):
        lease = uuid.uuid4().hex
        def claim(db):
            row = db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=? AND lease IS NULL', (job_id,)).fetchone()
            job = self.decode(row)
            if not job or job['state'] in PAUSED:
                return None
            db.execute('UPDATE engineering_jobs SET lease=? WHERE id=? AND lease IS NULL', (lease, job_id))
            db.execute('INSERT INTO engineering_owner_epochs VALUES(?,1) ON CONFLICT(job_id) DO UPDATE SET epoch=epoch+1', (job_id,))
            epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job_id,)).fetchone()[0]
            receipt = db.execute('SELECT status FROM engineering_receipts WHERE job_id=? AND revision=?', (job_id, job['revision'])).fetchone()
            recovering = receipt is not None
            db.execute('INSERT OR IGNORE INTO engineering_receipts VALUES(?,?,?, ?,?,NULL)',
                       (job_id, job['revision'], job['state'], 'CLAIMED', digest(job['data'])))
            job['_legacy_dispatch_receipt'] = receipt is not None and receipt[0] == 'DISPATCHING'
            job['_execution_lease'] = lease
            job['_owner_epoch'] = epoch
            return job, lease, recovering
        return self.tx(claim)

    def complete(self, job, lease, phase, output, delay=0):
        job = dict(job, updated_at=self.now())
        artifacts = EngineeringArtifacts(self)
        output_json, _ = canonical_json(artifacts.checkpoint(job, output, 'phase-result'), maximum_bytes=2 * 1024 * 1024)
        checkpoint = {key: value for key, value in job.items() if not key.startswith('_')}
        checkpoint['data'] = artifacts.checkpoint(job, job['data'], 'checkpoint')
        payload, _ = canonical_json(checkpoint, maximum_bytes=4 * 1024 * 1024)
        def save(db):
            # Keep every meaningful update, without thousands of identical CI
            # polls filling Android history during a multi-day job.
            unchanged = False
            if job['state'] == phase == 'CI':
                previous = db.execute('SELECT output_json FROM engineering_receipts WHERE job_id=? AND status=? ORDER BY revision DESC LIMIT 1', (job['id'], 'CONFIRMED')).fetchone()
                unchanged = previous is not None and previous[0] == output_json
            changed = db.execute('UPDATE engineering_jobs SET state=?,revision=revision+1,next_at=?,lease=NULL,data=? WHERE id=? AND revision=? AND lease=?',
                                 (phase, self.now() + delay, payload, job['id'], job['revision'], lease))
            if changed.rowcount != 1:
                return False  # Cancel/revoke won the race; never resurrect it.
            if not unchanged:
                self.event(db, job, phase, job['revision'] + 1, self.now())
            db.execute("UPDATE engineering_receipts SET status='CONFIRMED',output_json=? WHERE job_id=? AND revision=?", (output_json, job['id'], job['revision']))
            return True
        return self.tx(save)

    def assert_claim(self, job):
        def read(db):
            row = db.execute('SELECT state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job['id'], job['principal'])).fetchone()
            epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
            if row != (job['state'], job['revision'], job.get('_execution_lease')) or not row or row[2] is None or not epoch or epoch[0] != job.get('_owner_epoch'):
                raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The execution lease or job state changed; no new operation was dispatched.')
        self.tx(read)

    def record_model_attempt(self, job, record):
        raw, _ = canonical_json(record, maximum_bytes=128 * 1024)
        def save(db):
            row = db.execute('SELECT state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job['id'], job['principal'])).fetchone()
            old = db.execute('SELECT dispatched FROM engineering_model_attempts WHERE id=? AND job_id=?', (record['attempt_id'], job['id'])).fetchone()
            if record['state'] == 'DISPATCHED':
                if row != (job['state'], job['revision'], job.get('_execution_lease')) or row[2] is None:
                    raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The execution owner changed before model dispatch.')
                calls, tokens = db.execute('SELECT COUNT(*),COALESCE(SUM(reserved_tokens),0) FROM engineering_model_attempts WHERE job_id=? AND dispatched=1', (job['id'],)).fetchone()
                accepted = job['request'].get('execution_budget', {})
                call_limit = accepted.get('model_calls', 128)
                call_reserve = accepted.get('verification_calls', 2) + accepted.get('final_calls', 1)
                if record.get('role') in {'final summary', 'final report'}:
                    call_reserve = 0
                elif record.get('role') == 'selected-model reviewer':
                    call_reserve = accepted.get('final_calls', 1)
                token_limit = accepted.get('model_tokens', 4194304)
                token_reserve = 0 if call_reserve == 0 else record.get('budget', {}).get('final_tokens', 0) + record.get('budget', {}).get('verification_tokens', 0)
                if (old is None or not old[0]) and (calls >= max(0, call_limit - call_reserve) or tokens + record.get('reserved_tokens', 0) + token_reserve > token_limit):
                    raise ExecutionBindingError('EXECUTION_RUN_BUDGET_EXHAUSTED', 'The retained job reached its inference budget. Verification and final reporting capacity were preserved.')
            db.execute('INSERT INTO engineering_model_attempts VALUES(?,?,?,?,?,?,?,?) '
                       'ON CONFLICT(id) DO UPDATE SET state=excluded.state,dispatched=MAX(dispatched,excluded.dispatched),reserved_tokens=excluded.reserved_tokens,payload=excluded.payload',
                       (record['attempt_id'], job['id'], record['binding_id'], job['revision'], record['state'], int(record.get('dispatched') is True), record.get('reserved_tokens', 0), raw))
        self.tx(save)

    def model_attempts(self, job_id, after=None, limit=100):
        limit = max(1, min(100, limit))
        return self.tx(lambda db: [json.loads(row[0]) for row in db.execute('SELECT payload FROM engineering_model_attempts WHERE job_id=? AND rowid>COALESCE((SELECT rowid FROM engineering_model_attempts WHERE id=? AND job_id=?),0) ORDER BY rowid LIMIT ?', (job_id, after, job_id, limit))])

    def control(self, job_id, principal, action, expected_revision, approval=None):
        def update(db):
            row = db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=? AND principal=?' + ('' if action == 'cancel' else ' AND lease IS NULL'), (job_id, principal)).fetchone()
            job = self.decode(row)
            if not job or job['revision'] != expected_revision:
                raise ValueError('Job changed; refresh before confirming')
            if job['state'] in {'CANCELLED', 'COMPLETED'}:
                raise ValueError('The job is already finished')
            job['updated_at'] = self.now()
            if action == 'approve':
                if job['state'] != 'MERGE_READY':
                    raise ValueError('The job is not merge-ready')
                job['approval'] = approval
                job['message'] = 'Approval received. Rechecking the exact PR, commit and merge policy.'
                state = 'MERGE'
            elif action == 'cancel':
                job['approval'] = None
                state = 'CANCELLED'
                job['message'] = 'Cancelled. Already dispatched operations may finish; retained receipts remain available.'
            elif action == 'request_changes':
                job['approval'] = None
                job['message'] = 'Changes requested. Submit the revised objective as a new job; retained evidence remains available.'
                state = 'NEEDS_INPUT'
            else:
                raise ValueError('Unsupported job action')
            db.execute('UPDATE engineering_jobs SET data=?,state=?,revision=revision+1,next_at=?,lease=NULL WHERE id=?', (json.dumps(job), state, self.now(), job_id))
            self.event(db, job, state, job['revision'] + 1, self.now())
            return dict(job, state=state, revision=job['revision'] + 1)
        return self.tx(update)

    def invalidate_ready(self, job, evidence):
        """A stale approval prompt is retired durably before reporting rejection."""
        changed_head = evidence.get('head_sha') != job['data'].get('head_sha')
        state = 'NEEDS_INPUT' if changed_head else 'CI'
        job = dict(job, approval=None, updated_at=self.now(), message=(
            'The PR head changed outside this job. Review the new commit before continuing.' if changed_head else
            'Merge readiness changed. Waiting for current checks, reviews and policy.'))
        job['data'] = dict(job['data'], readiness=evidence)
        def update(db):
            changed = db.execute('UPDATE engineering_jobs SET state=?,revision=revision+1,next_at=?,data=? WHERE id=? AND revision=? AND state=? AND lease IS NULL',
                                 (state, self.now(), json.dumps(job), job['id'], job['revision'], 'MERGE_READY'))
            if changed.rowcount:
                self.event(db, job, state, job['revision'] + 1, self.now())
        self.tx(update)


class EngineeringSupervisor:
    """Deterministic limits and evidence checks, separate from coding and review prompts."""
    @staticmethod
    def readiness(evidence):
        return (isinstance(evidence, dict) and evidence.get('head_sha') and SHA.fullmatch(evidence['head_sha']) is not None
                and evidence.get('checks_passed') is True and evidence.get('policy_verified') is True
                and evidence.get('mergeable') is True and evidence.get('reviews_satisfied') is True
                and evidence.get('draft') is False and evidence.get('state') == 'OPEN')

    @staticmethod
    def limit(job, now):
        if job['state'] not in {'MERGE', 'VERIFY'} and now >= job['deadline']:
            return 'Job duration limit reached; confirmed work is retained'
        if job['state'] not in {'MERGE', 'VERIFY'} and now >= job['deadline'] - job['request'].get('execution_budget', {}).get('final_seconds', 30):
            return 'The remaining duration is reserved for verification and a durable partial report'
        if job['steps'] >= 256:
            return 'Step budget reached'
        if job['stalls'] >= 3:
            return 'Three attempts made no verified progress'
        return None

    @staticmethod
    def repair(job, fingerprint):
        counts = dict(job['repairs'])
        count = counts.get(fingerprint, 0)
        if count >= job['request']['repair_attempts'] or sum(counts.values()) >= 20:
            return False
        counts[fingerprint] = count + 1
        job['repairs'] = counts
        job['message'] = f"Repairing validation failure · attempt {count + 1}/{job['request']['repair_attempts']}"
        return True


class EngineeringService:
    def __init__(self, store, backend):
        self.store, self.backend = store, backend
        self.capabilities = InferenceCapabilities(store.runtime)
        self.bindings = ExecutionBindings(store, store.runtime, self.capabilities)
        store.bindings = self.bindings
        self.scheduler = install_scheduler(store.runtime)
        self.artifacts = EngineeringArtifacts(store)
        self.operations = EngineeringOperations(self)
        self.evidence = EvidenceLedger(store)
        self.memory = EngineeringMemory(store, store.runtime)
        from v14.code_graph.service import GraphService
        self.graph = GraphService(store, backend, self.evidence)
        backend.record_evidence = lambda job, payload: self.evidence.append(job, 'github_api', payload)
        backend.recall_memory = self.memory.recall
        backend.recall_graph = self.graph.recall
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        self.authorized = lambda job: True
        backend.model_executor = self.execute_model
        backend.before_mutation = self.validate_mutation
        backend.operations = self.operations

    def prepare_binding(self, principal, selection, gateway_endpoint=None):
        return self.bindings.prepare(principal, selection, gateway_endpoint)

    def binding_capabilities(self, principal, binding_id):
        binding, _, capabilities = self.bindings.validate(principal, binding_id, force=True)
        return {'binding': binding, 'capabilities': capabilities}

    def validate_submission(self, principal, request):
        value = validate_request(request)
        binding, _, _ = self.bindings.validate(principal, value.get('execution_binding_id'), force=True)
        if value['model'] != binding['requested_model_id'] or value.get('profile_id') != binding['profile_uid']:
            raise ExecutionBindingError('EXECUTION_SELECTION_MISMATCH', 'The submitted model/profile does not match the selected execution binding.')
        return binding

    def validate_execution(self, job, *, force=False):
        self.store.assert_claim(job)
        if not self.authorized(job):
            raise ExecutionBindingError('EXECUTION_AUTHORIZATION_REVOKED', 'The gateway execution authority was revoked.', 403)
        binding, route, capability = self.bindings.validate(job['principal'], job['request'].get('execution_binding_id'), force=force)
        if job.get('execution_binding') != binding or job['request']['model'] != binding['requested_model_id'] or job['request'].get('profile_id') != binding['profile_uid']:
            raise ExecutionBindingError('EXECUTION_BINDING_CHANGED', 'The retained job cannot be served under a different selected profile.')
        return binding, route, capability

    def validate_mutation(self, job):
        self.store.assert_claim(job)
        if job['state'] not in MUTATIONS:
            raise ExecutionBindingError('EXECUTION_OPERATION_PHASE_INVALID', 'This stage has no authority to dispatch a repository mutation.')
        if not self.authorized(job) or job['data'].get('execution_integrity') != 'VERIFIED':
            raise ExecutionBindingError('EXECUTION_INTEGRITY_BLOCKED', 'A revoked or contradictory execution receipt blocks dependent writes.')
        binding = self.bindings.read(job['principal'], job['request'].get('execution_binding_id'))
        if job.get('execution_binding') != binding:
            raise ExecutionBindingError('EXECUTION_BINDING_CHANGED', 'The job binding changed before mutation dispatch.')

    def execute_model(self, job, role, payload, cancellation):
        from v14.inference_execution import BoundModelInvocation
        return BoundModelInvocation(self, job, role).execute(payload, cancellation)

    def evidence_access(self, principal, job_id):
        job = self.store.get(job_id, principal)
        if job is None or not self.authorized(job):
            raise PermissionError('Evidence scope is unavailable')
        # Saved-result reads have their own lifetime, independent of the model
        # execution deadline. GitHub authorization is refreshed before private
        # source leaves the encrypted journal.
        inspection = dict(job, state='VERIFY')
        self.backend.identity(inspection)
        metadata = self.backend.api(self.backend.root(inspection), job=inspection)
        if metadata.get('permissions', {}).get('pull') is not True:
            raise PermissionError('Repository evidence authorization was revoked')
        return job

    def artifact_page(self, principal, job_id, artifact_id, offset=0, length=65536):
        job = self.evidence_access(principal, job_id)
        result = self.artifacts.page(job, artifact_id, offset, length)
        self.evidence_access(principal, job_id)
        return result

    def operation_page(self, principal, job_id, after=0):
        if type(after) is not int or after < 0:
            raise ValueError('Invalid operation cursor')
        return self.operations.page(self.evidence_access(principal, job_id), after)

    def completion_verification(self, job, readiness, merged=False):
        receipts = []
        for reference in job['data'].get('requirement_evidence_refs', [])[:512]:
            row = self.evidence.read(job['id'], reference)
            if row is not None and row['kind'] == 'requirement_evidence':
                receipts.append(dict(row['payload'], receipt_id=row['receipt_id']))
        return completion_gate(job, readiness, merged, semantic_receipts=receipts)

    def readiness(self):
        """Local engineering prerequisites; this never implies model or GitHub API success."""
        runtime = self.store.runtime
        journal = 'disabled' if not runtime.GATEWAY_DURABLE_JOBS else 'unavailable'
        if runtime.GATEWAY_DURABLE_JOBS and runtime.durable_job_initialized:
            try:
                with runtime.durable_job_lock:
                    db = runtime._durable_connect()
                    try:
                        db.execute('SELECT id FROM engineering_jobs LIMIT 1').fetchone()
                        journal = 'ready' if getattr(runtime, 'durable_write_healthy', True) else 'write_failed'
                    finally:
                        db.close()
            except (sqlite3.Error, OSError, ValueError):
                journal = 'unavailable'
        configured = bool(self.backend.available())
        worker = self.thread is not None and self.thread.is_alive() and not self.stop.is_set()
        draining = bool(getattr(runtime, 'gateway_draining', False))
        graph_ready = journal == 'ready' and configured and not draining
        return {'schema_version': 1, 'ready': graph_ready and worker, 'graph_ready': graph_ready,
                'saved_results': journal in {'ready', 'write_failed'},
                'checks': {'journal': journal, 'host_github_connection': 'configured' if configured else 'missing',
                           'worker': 'running' if worker else 'unavailable', 'draining': draining},
                'github_reachable': None, 'repository_access': None, 'inference_ready': None,
                'graph_supported': True, 'graph_configured': configured, 'graph_ready': graph_ready,
                'execution_binding': {'schema_version': 1, 'supported': True, 'routing_policy': 'SELECTED_ONLY'},
                'inference_scheduler': self.scheduler.snapshot()}

    def approve(self, job_id, principal, expected_revision, pr, head):
        job = self.store.get(job_id, principal)
        if not job or job['state'] != 'MERGE_READY' or job['revision'] != expected_revision:
            raise ValueError('Refresh the merge approval')
        evidence = self.backend.inspect(job)
        if not EngineeringSupervisor.readiness(evidence) or evidence['pr'] != pr or evidence['head_sha'] != head or job['data'].get('head_sha') != head:
            if evidence.get('head_sha') != job['data'].get('head_sha') or not EngineeringSupervisor.readiness(evidence):
                self.store.invalidate_ready(job, evidence)
                self.wake.set()
            raise ValueError('PR head or merge readiness changed; approval rejected')
        completion = self.completion_verification(job, evidence)
        if not completion['passed']:
            self.store.invalidate_ready(job, dict(evidence, checks_passed=False, message='Configured acceptance evidence is incomplete'))
            raise ValueError('Completion evidence is missing: ' + '; '.join(completion['failures']))
        approval = {'principal': principal, 'repository': job['request']['repository'], 'pr': pr, 'head_sha': head,
                    'base_sha': evidence['base_sha'], 'evidence_digest': digest(evidence),
                    'expires_at': self.store.now() + 1800}
        result = self.store.control(job_id, principal, 'approve', expected_revision, approval)
        self.wake.set()
        return result

    def tick(self, job_id):
        claimed = self.store.claim(job_id)
        if not claimed:
            return
        job, lease, recovering = claimed
        with execution_diagnostic_context(job.get('diagnostic_capture'), self.store.runtime, job['principal'],
                                          lambda: self.authorized(job)):
            self._tick_claimed(job, lease, recovering)

    def _tick_claimed(self, job, lease, recovering):
        phase, output, delay = job['state'], {}, 0
        try:
            self.artifacts.restore_stage(job)
            limit = EngineeringSupervisor.limit(job, self.store.now()) if self.authorized(job) else 'Gateway engineering permission was revoked'
            if limit:
                job['message'] = limit
                phase = 'NEEDS_INPUT'
            elif recovering and phase in MUTATIONS and (job.get('_legacy_dispatch_receipt') or not getattr(self.backend, 'physical_operation_journal', False) or self.operations.dispatched(job)):
                output = self.backend.reconcile(job, phase)
                if output is None:
                    output = {}
                    job['message'] = 'An interrupted write has an unknown outcome. Reconciliation is required; the write was not repeated.'
                    phase = 'NEEDS_INPUT'
                else:
                    job['data'].update(output)
                    phase = {'COMMIT': 'OPEN_PR', 'OPEN_PR': 'CI', 'PROMOTE': 'CI', 'MERGE': 'VERIFY'}[phase]
            elif phase == 'MERGE':
                evidence = self.backend.inspect(job)
                approval = job.get('approval') or {}
                valid = (EngineeringSupervisor.readiness(evidence) and self.completion_verification(job, evidence)['passed'] and approval.get('principal') == job['principal']
                         and approval.get('repository') == job['request']['repository'] and approval.get('pr') == evidence.get('pr')
                         and approval.get('head_sha') == evidence.get('head_sha') and approval.get('base_sha') == evidence.get('base_sha')
                         and self.store.now() < approval.get('expires_at', 0))
                if not valid:
                    job['approval'] = None
                    job['message'] = 'Merge approval expired or repository state changed. Checking readiness again.'
                    phase = 'CI'
                else:
                    output = self.backend.merge(job, evidence)
                    job['data'].update(output)
                    phase = 'VERIFY'
            elif phase == 'CI':
                output = self.backend.inspect(job)
                job['data']['readiness'] = output
                if output.get('head_sha') != job['data'].get('head_sha'):
                    job['approval'] = None
                    job['message'] = 'The working branch changed outside this job. Review the new head before continuing.'
                    phase = 'NEEDS_INPUT'
                elif output.get('checks_failed'):
                    fingerprint = output['failure_fingerprint']
                    job['data']['ci'] = output
                    phase = 'READ' if EngineeringSupervisor.repair(job, fingerprint) else 'NEEDS_INPUT'
                    if phase == 'NEEDS_INPUT':
                        job['message'] = 'CI repair budget exhausted; failure logs and attempted patches are retained'
                elif output.get('checks_passed') and output.get('draft') is True:
                    job['data']['readiness'] = output
                    phase = 'PROMOTE'
                elif EngineeringSupervisor.readiness(output):
                    job['data']['readiness'] = output
                    gate = self.completion_verification(job, output)
                    job['data']['completion_verification'] = gate
                    self.evidence.append(job, 'completion_gate', gate)
                    job['approval'] = None
                    if gate['passed']:
                        job['message'] = 'CI and configured acceptance evidence passed. Review the feature requirements before exact-commit merge approval.'
                        phase = 'MERGE_READY'
                    else:
                        job['message'] = 'Completion evidence missing: ' + '; '.join(gate['failures'])
                        phase = 'NEEDS_INPUT'
                else:
                    job['data']['readiness'] = output
                    job['message'] = output.get('message', 'Waiting for checks, required reviews or merge policy')
                    delay = 30
            else:
                # Legacy jobs remain readable but cannot acquire new model
                # authority from today's settings or a reused display name.
                if phase in {'PLAN', 'READ', 'IMPLEMENT', 'REVIEW'}:
                    self.validate_execution(job, force=recovering)
                output = self.backend.step(job, phase)
                job['data'].update(output)
                if phase == 'IMPLEMENT':
                    patch = digest(output.get('changes', []))
                    if not output.get('changes') or patch in job['patches']:
                        job['stalls'] += 1
                        phase = 'NEEDS_INPUT'
                        job['message'] = 'The coding agent produced no new patch. Retained work requires review.'
                    else:
                        job['patches'].append(patch)
                        phase = 'REVIEW'
                elif phase == 'REVIEW' and output.get('local_validation', {}).get('status') == 'BLOCKED':
                    phase = 'NEEDS_INPUT'
                    job['message'] = output['local_validation'].get('reason', 'Pre-publication validation configuration is required')
                elif phase == 'REVIEW' and output.get('review', {}).get('approved') is not True:
                    fingerprint = digest(output.get('review', {}))
                    phase = 'IMPLEMENT' if EngineeringSupervisor.repair(job, fingerprint) else 'NEEDS_INPUT'
                elif phase == 'VERIFY':
                    gate = self.completion_verification(job, job['data'].get('readiness', {}), merged=True)
                    job['data']['completion_verification'] = gate
                    self.evidence.append(job, 'completion_gate', gate)
                    phase = 'COMPLETED' if gate['passed'] else 'NEEDS_INPUT'
                    if not gate['passed']:
                        job['message'] = 'Merge occurred but completion evidence is incomplete: ' + '; '.join(gate['failures'])
                else:
                    phase = 'CI' if phase == 'PROMOTE' else PHASES[PHASES.index(phase) + 1]
                job['stalls'] = 0
                job['steps'] += 1
                if phase not in PAUSED:
                    job['message'] = {'READ': 'Reading selected repository files in batches', 'IMPLEMENT': 'Preparing coordinated changes',
                                      'REVIEW': 'Selected-model review of proposed changes', 'COMMIT': 'Publishing the reviewed branch changes',
                                      'OPEN_PR': 'Preparing the draft pull request', 'CI': 'Waiting for CI validation',
                                      'COMPLETED': 'Merge verified; engineering result published'}.get(phase, phase)
                elif phase == 'COMPLETED':
                    job['message'] = 'Approved merge and workflow evidence verified. Feature acceptance is limited to configured paths/checks; unexecuted benchmarks remain Not measured.'
        except Exception as error:
            output = {'error_type': type(error).__name__}
            if isinstance(error, ExecutionBindingError):
                output.update(error_code=error.code, dispatched=getattr(error, 'dispatched', False))
            http_status = getattr(getattr(error, 'response', None), 'status_code', None)
            http_status = http_status if type(http_status) is int else None
            recovery_delay = retry_delay(error, 0)
            transient = recovery_delay is not None
            job['connection_retries'] = job.get('connection_retries', 0) + 1 if transient else 0
            if not transient:
                job['stalls'] += 1
            # Mutations stay uncertain; no automatic replay after a lost response.
            if job['state'] in MUTATIONS and getattr(self.backend, 'physical_operation_journal', False) and not self.operations.dispatched(job):
                phase = 'NEEDS_INPUT'
                output['dispatched'] = False
                job['message'] = 'The write was rejected before dispatch. Confirmed work is retained. ' + str(getattr(error, 'code', type(error).__name__))
                job['data']['blocked_reason'] = {'code': getattr(error, 'code', 'OPERATION_PRECHECK_REJECTED'), 'retryable': False, 'dispatched': False}
            elif job['state'] in MUTATIONS:
                recovered = None
                try:
                    recovered = self.backend.reconcile(job, job['state'])
                except Exception:
                    pass
                if recovered is not None:
                    job['data'].update(recovered)
                    output = recovered
                    phase = {'COMMIT': 'OPEN_PR', 'OPEN_PR': 'CI', 'PROMOTE': 'CI', 'MERGE': 'VERIFY'}[job['state']]
                else:
                    phase = 'NEEDS_INPUT'
                    job['message'] = 'A write could not be confirmed. Reconcile its remote outcome before continuing.'
            elif isinstance(error, ExecutionBindingError):
                phase = 'NEEDS_INPUT'
                job['message'] = str(error)
                job['data']['blocked_reason'] = {'code': error.code, 'retryable': False, 'binding_id': job['request'].get('execution_binding_id')}
            elif (isinstance(error, (PermissionError, ValueError, TypeError, InterruptedError)) or
                  not transient and http_status is not None and 400 <= http_status < 500):
                # Definitive authorization/contract failures cannot be repaired by
                # polling the same stage. Mutation reconciliation above retains
                # precedence: a validation failure never erases a dispatch receipt.
                status = http_status
                if status == 429:
                    code, instruction = 'ENGINEERING_RATE_LIMIT_WAIT', 'Honor the server cooldown and review the bounded retry budget'
                elif status == 401:
                    code, instruction = 'ENGINEERING_AUTHENTICATION_REQUIRED', 'Restore the host connection authentication'
                elif status == 403 or isinstance(error, PermissionError):
                    code, instruction = 'ENGINEERING_ACCESS_DENIED', 'Review execution authority and repository access'
                elif isinstance(error, InterruptedError):
                    code, instruction = 'ENGINEERING_EXECUTION_INTERRUPTED', 'Review cancellation, privacy and execution limits'
                else:
                    code, instruction = 'ENGINEERING_VALIDATION_REJECTED', 'Review the rejected stage inputs and retained evidence'
                phase = 'NEEDS_INPUT'
                job['message'] = instruction + ' before explicitly resuming. Confirmed work is retained; this failure will not retry automatically.'
                reason = {'code': code, 'retryable': False, 'binding_id': job['request'].get('execution_binding_id')}
                if status is not None:
                    reason['http_status'] = status
                job['data']['blocked_reason'] = reason
                output.update(error_code=code, retryable=False)
                if status is not None:
                    output['http_status'] = status
            elif not transient:
                phase = 'NEEDS_INPUT'
                code = 'ENGINEERING_RECOVERY_REQUIRES_REVIEW'
                job['message'] = 'Recovery is not safely retryable within this request budget. Review transport security, the server cooldown and retained evidence before resuming; confirmed work is retained.'
                job['data']['blocked_reason'] = {'code': code, 'retryable': False}
                output.update(error_code=code, retryable=False)
            else:
                phase = 'NEEDS_INPUT' if job['connection_retries'] >= 12 else job['state']
                delay = max(recovery_delay, min(300, 5 * 2 ** min(6, job['connection_retries'])))
                output.update(retryable=phase != 'NEEDS_INPUT', retry_after_seconds=delay)
                job['message'] = 'Connection unavailable; waiting for the bounded retry deadline. Confirmed work is retained.'
        if output and 'error_type' not in output:
            job['connection_retries'] = 0
            job['stalls'] = 0
        if job['state'] == 'COMMIT' and output.get('head_sha') and phase == 'OPEN_PR':
            job['data']['changed_paths'] = sorted(set(job['data'].get('changed_paths', [])) | {change['path'] for change in job['data'].get('changes', [])})
            validation = job['data'].get('local_validation', {})
            references = job['data'].setdefault('requirement_evidence_refs', [])
            for evidence in validation.get('requirement_evidence', [])[:256]:
                payload = dict(evidence, repository=job['request']['repository'], branch=job['request']['branch'],
                    head_sha=output['head_sha'], binding_id=job['request'].get('execution_binding_id'),
                    patch_identity=validation.get('patch_identity'), source='HOST_VALIDATION', trusted=True)
                reference = self.evidence.append(job, 'requirement_evidence', payload)
                if reference['receipt_id'] not in references:
                    references.append(reference['receipt_id'])
            del references[:-512]
        confirmed = output and 'error_type' not in output and phase != 'NEEDS_INPUT' and phase != job['state']
        if job['state'] == 'REVIEW' and output.get('review', {}).get('approved') is not True:
            confirmed = False
        if job['state'] == 'CI' and output.get('checks_passed') is not True:
            confirmed = False
        if confirmed:
            job['milestones'] = sorted(set(job.get('milestones', [])) | {job['state']})
        try:
            self.memory.capture(job, job['state'], output)
        except Exception as error:
            # Memory failure must not lose a confirmed mutation receipt or replay a write.
            job['data']['memory_status'] = 'UNAVAILABLE:' + type(error).__name__
        try:
            self.graph.capture(job, job['state'], output)
        except Exception as error:
            job['data']['graph_status'] = 'UNAVAILABLE:' + type(error).__name__
        if phase in PAUSED:
            from v14.engineering_report import build_report
            report = build_report(job, phase, self.completion_verification(job, job['data'].get('readiness', {}), merged=phase == 'COMPLETED'))
            try:
                reference = self.artifacts.put_json(job, report, 'final-report')
                job['data']['final_report'] = dict(report, artifact=reference)
            except (ExecutionBindingError, OSError, sqlite3.Error) as error:
                # Final delivery has an inline bounded fallback; an artifact
                # quota must not erase the reason a retained job stopped.
                job['data']['final_report'] = dict(report, artifact_availability='UNAVAILABLE', artifact_error=getattr(error, 'code', type(error).__name__))
        self.store.complete(job, lease, phase, output, delay)

    def start(self):
        if not self.store.runtime.GATEWAY_DURABLE_JOBS:
            return
        self.store.recover()
        self.stop.clear()
        def work():
            while not self.stop.is_set():
                try:
                    for job_id in ([] if getattr(self.store.runtime, "gateway_draining", False) else self.store.due()):
                        if self.stop.is_set():
                            break
                        self.tick(job_id)
                except Exception as error:
                    # Only this worker owns leases under the process state lock.
                    # A failed journal save must not strand a job until restart.
                    logging.getLogger(__name__).warning('Engineering checkpoint unavailable: %s', type(error).__name__)
                    try:
                        self.store.recover()
                    except Exception:
                        pass
                self.wake.wait(2)
                self.wake.clear()
        self.thread = threading.Thread(target=work, name='engineering-supervisor', daemon=True)
        self.thread.start()

    def lifecycle(self, existing):
        @asynccontextmanager
        async def lifecycle(app):
            async with existing(app):
                await asyncio.to_thread(self.start)
                try:
                    yield
                finally:
                    self.stop.set()
                    self.wake.set()
                    self.backend.cancel()
                    if self.thread:
                        await asyncio.to_thread(self.thread.join, 15)
        return lifecycle


def public_job(job):
    data = job['data']
    state = job['state']
    completed = len(job.get('milestones', []))
    readiness = data.get('readiness') or {}
    return {'id': job['id'], 'repository': job['request']['repository'], 'branch': job['request']['branch'],
            'chat_id': job['request'].get('chat_id', 0), 'state': state, 'revision': job['revision'],
            'created_at': job['created_at'], 'finished_at': job['updated_at'] if state in {'COMPLETED', 'CANCELLED'} else None, 'deadline': job['deadline'], 'message': job['message'],
            'milestones_completed': completed, 'milestones_total': len(PHASES) - 2,
            'repair_attempts': sum(job['repairs'].values()), 'pr': data.get('pr'), 'pr_url': data.get('pr_url'),
            'head_sha': data.get('head_sha'), 'changed_files': readiness.get('changed_files', data.get('changed_paths', [change['path'] for change in data.get('changes', [])])),
            'tests': readiness.get('checks', []), 'checks_passed': readiness.get('checks_passed', False),
            'merge_ready': state == 'MERGE_READY' and bool(EngineeringSupervisor.readiness(readiness)),
            'approval_expires_at': (job.get('approval') or {}).get('expires_at'),
            'merge_sha': data.get('merge_sha'), 'review': data.get('review', {}),
            'completion_verification': data.get('completion_verification'), 'evidence_policy': 'github.evidence.v1',
            'latest_evidence': data.get('latest_evidence'),
            'execution_binding_id': job['request'].get('execution_binding_id'),
            'execution_binding': job.get('execution_binding'),
            'latest_execution_receipt': data.get('latest_execution_receipt'),
            'blocked_reason': data.get('blocked_reason'), 'latest_operation': data.get('latest_operation'),
            'final_report': data.get('final_report')}
