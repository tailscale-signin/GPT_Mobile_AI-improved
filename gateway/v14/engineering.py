"""Durable engineering state machine and supervisor. No model can grant approval."""
import json
import re
import threading
import time
import uuid
import logging
from contextlib import asynccontextmanager
import asyncio

from v14.actions import canonical_json
from v14.batch import digest, retry_delay
from v14.evidence import EvidenceLedger, completion_gate
from v14.engineering_memory import EngineeringMemory

PHASES = ('PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'CI', 'PROMOTE', 'MERGE_READY', 'MERGE', 'VERIFY', 'COMPLETED')
PAUSED = {'MERGE_READY', 'NEEDS_INPUT', 'COMPLETED', 'CANCELLED'}
MUTATIONS = {'COMMIT', 'OPEN_PR', 'PROMOTE', 'MERGE'}
SHA = re.compile(r'^[0-9a-f]{40}$')


def principal_authorized(config, principal, devices):
    if config.mode == 'loopback':
        return principal == 'local:single-user' or config.legacy_bearer and any(row[0] == principal and not row[2] for row in devices.list_devices())
    return any(principal == 'tailnet:' + grant['principalId'] and 'engineering' in grant.get('scopes', []) for grant in config.trusted_users.values())


def validate_request(request):
    allowed = {'repository', 'branch', 'base', 'objective', 'model', 'duration_hours', 'repair_attempts', 'chat_id', 'request_id', 'acceptance', 'profile_id', 'memory_mode'}
    if not isinstance(request, dict) or set(request) - allowed:
        raise ValueError('Unsupported engineering request fields')
    result = dict(request)
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
    if not isinstance(acceptance, list) or len(acceptance) > 32:
        raise ValueError('At most 32 explicit acceptance requirements are supported')
    ids = set()
    for requirement in acceptance:
        if not isinstance(requirement, dict) or set(requirement) != {'id', 'paths', 'checks'}:
            raise ValueError('Acceptance needs id, paths and checks')
        if not isinstance(requirement['id'], str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', requirement['id']) or requirement['id'] in ids:
            raise ValueError('Acceptance IDs must be unique and bounded')
        ids.add(requirement['id'])
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
                    return operation(db)
            finally:
                db.close()

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
        now = self.now()
        job = {'id': 'eng_' + uuid.uuid4().hex, 'principal': principal, 'request': request,
               'created_at': now, 'deadline': now + (request['duration_hours'] or 72) * 3600,
               'updated_at': now, 'steps': 0, 'milestones': [], 'repairs': {}, 'patches': [], 'stalls': 0,
               'message': 'Queued for repository inspection', 'data': {}, 'approval': None}
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
            receipt = db.execute('SELECT status FROM engineering_receipts WHERE job_id=? AND revision=?', (job_id, job['revision'])).fetchone()
            recovering = receipt is not None
            db.execute('INSERT OR IGNORE INTO engineering_receipts VALUES(?,?,?, ?,?,NULL)',
                       (job_id, job['revision'], job['state'], 'DISPATCHING', digest(job['data'])))
            return job, lease, recovering
        return self.tx(claim)

    def complete(self, job, lease, phase, output, delay=0):
        job = dict(job, updated_at=self.now())
        output_json, _ = canonical_json(output, maximum_bytes=2 * 1024 * 1024)
        payload, _ = canonical_json(job, maximum_bytes=4 * 1024 * 1024)
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
        self.evidence = EvidenceLedger(store)
        self.memory = EngineeringMemory(store, store.runtime)
        backend.record_evidence = lambda job, payload: self.evidence.append(job, 'github_api', payload)
        backend.recall_memory = self.memory.recall
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        self.authorized = lambda job: True

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
        completion = completion_gate(job, evidence)
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
        phase, output, delay = job['state'], {}, 0
        try:
            limit = EngineeringSupervisor.limit(job, self.store.now()) if self.authorized(job) else 'Gateway engineering permission was revoked'
            if limit:
                job['message'] = limit
                phase = 'NEEDS_INPUT'
            elif recovering and phase in MUTATIONS:
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
                valid = (EngineeringSupervisor.readiness(evidence) and completion_gate(job, evidence)['passed'] and approval.get('principal') == job['principal']
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
                    gate = completion_gate(job, output)
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
                elif phase == 'REVIEW' and output.get('review', {}).get('approved') is not True:
                    fingerprint = digest(output.get('review', {}))
                    phase = 'IMPLEMENT' if EngineeringSupervisor.repair(job, fingerprint) else 'NEEDS_INPUT'
                elif phase == 'VERIFY':
                    gate = completion_gate(job, job['data'].get('readiness', {}), merged=True)
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
                                      'REVIEW': 'Independent review of proposed changes', 'COMMIT': 'Publishing the reviewed branch changes',
                                      'OPEN_PR': 'Preparing the draft pull request', 'CI': 'Waiting for CI validation',
                                      'COMPLETED': 'Merge verified; engineering result published'}.get(phase, phase)
                elif phase == 'COMPLETED':
                    job['message'] = 'Approved merge and workflow evidence verified. Feature acceptance is limited to configured paths/checks; unexecuted benchmarks remain Not measured.'
        except Exception as error:
            output = {'error_type': type(error).__name__}
            transient = retry_delay(error, 0) is not None
            job['connection_retries'] = job.get('connection_retries', 0) + 1 if transient else 0
            if not transient:
                job['stalls'] += 1
            # Mutations stay uncertain; no automatic replay after a lost response.
            if job['state'] in MUTATIONS:
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
            else:
                phase = 'NEEDS_INPUT' if job['stalls'] >= 3 or job['connection_retries'] >= 12 else job['state']
                delay = min(300, 5 * 2 ** min(6, max(job['stalls'], job['connection_retries'])))
                job['message'] = 'Connection or evidence unavailable; confirmed work retained. ' + type(error).__name__
        if output and 'error_type' not in output:
            job['connection_retries'] = 0
            job['stalls'] = 0
        if job['state'] == 'COMMIT' and output.get('head_sha') and phase == 'OPEN_PR':
            job['data']['changed_paths'] = sorted(set(job['data'].get('changed_paths', [])) | {change['path'] for change in job['data'].get('changes', [])})
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
        self.store.complete(job, lease, phase, output, delay)

    def start(self):
        if not self.store.runtime.GATEWAY_DURABLE_JOBS:
            return
        self.store.recover()
        self.stop.clear()
        def work():
            while not self.stop.is_set():
                try:
                    for job_id in self.store.due():
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
            'latest_evidence': data.get('latest_evidence')}
