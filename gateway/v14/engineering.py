"""Durable engineering state machine and supervisor. No model can grant approval."""
from v14.engineering_scope import accepted_scope
from v14.adaptive_sampling import validate_policy
import json
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import sqlite3
import time
import uuid
import logging
from contextlib import asynccontextmanager
import asyncio

from v14.actions import canonical_json
from v14.batch import digest, is_rate_limit_response, provider_rate_limit_delay, retry_delay
from v14.evidence import EvidenceLedger, completion_gate
from v14.engineering_memory import EngineeringMemory
from v14.execution_binding import ExecutionBindings, ExecutionBindingError, fingerprint
from v14.inference_capabilities import InferenceCapabilities
from v14.inference_scheduler import install_scheduler
from v14.graph_context import public_graph_receipt
from v14.engineering_artifacts import EngineeringArtifacts
from v14.engineering_operations import EngineeringOperations
from v14.engineering_recovery import CONTINUOUS_SESSIONS, SESSION_REPAIRS, SESSION_STEPS, continuous_progress, retry_summary
from v14.engineering_defaults import EXECUTION_BUDGET_LIMITS, new_request_defaults, retained_request_defaults, effective_execution_budget
from v14.engineering_batch_policy import authorize_request, load_policy as load_batch_policy
from v14.engineering_batch_plan import new_plan, claimed as batch_claimed, transition as batch_transition, control_transition as batch_control, summary as batch_summary
from v14.debug import capture_context_snapshot, execution_diagnostic_context
from v14.conversations import Conversations, ConversationConflict, short_title

PHASES = ('PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'CI', 'PROMOTE', 'MERGE_READY', 'MERGE', 'VERIFY', 'COMPLETED')
PAUSED = {'MERGE_READY', 'NEEDS_INPUT', 'COMPLETED', 'CANCELLED', 'FAILED', 'CANCELLING'}
MUTATIONS = {'COMMIT', 'OPEN_PR', 'PROMOTE', 'MERGE'}
SHA = re.compile(r'^[0-9a-f]{40}$')


def record_verified_mutation_reconciliation(job, phase, recovered):
    """Store proof of a previously uncertain dispatch confirmed by a host read.

    Called only after the backend independently checks the exact remote commit,
    pull request or merge identity. Never authorizes a second mutation.
    """
    operation = job.get('data', {}).get('latest_operation')
    if (phase not in MUTATIONS or not isinstance(recovered, dict) or not recovered
            or not isinstance(operation, dict) or operation.get('dispatched') is not True
            or operation.get('state') not in {'DISPATCHED', 'UNKNOWN_OUTCOME'}
            or operation.get('job_id') != job.get('id')
            or operation.get('binding_id') != job.get('request', {}).get('execution_binding_id')
            or not isinstance(operation.get('operation_id'), str)):
        return None
    row = {'schema': 'gateway.mutation-reconciliation.v1',
           'job_id': job['id'], 'operation_id': operation['operation_id'], 'phase': phase,
           'original_state': operation['state'], 'original_dispatch_observed': True,
           'remote_state_verified': True, 'replay_performed': False,
           'source_head_sha': job['data'].get('head_sha'),
           'remote_result_sha256': fingerprint(recovered)}
    previous = job['data'].get('mutation_reconciliations', [])
    previous = previous if isinstance(previous, list) else []
    if not any(isinstance(item, dict) and item.get('operation_id') == row['operation_id'] for item in previous):
        job['data']['mutation_reconciliations'] = (previous + [row])[-32:]
    return row


def principal_authorized(config, principal, devices):
    if principal.startswith('paired:'):
        from v14.identity import control_grant
        grant = control_grant(config, principal)
        return bool(grant and grant['role'] == 'owner' and json.loads(grant['repositories']))
    if config.mode == 'open_network':
        from v14.identity import account_authorized
        return account_authorized(config, principal) or config.remote_engineering and principal == 'network:shared'
    if config.mode == 'loopback':
        return principal == 'local:single-user' or config.legacy_bearer and any(row[0] == principal and not row[2] for row in devices.list_devices())
    return any(principal == 'tailnet:' + grant['principalId'] and 'engineering' in grant.get('scopes', []) for grant in config.trusted_users.values())


def validate_request(request):
    allowed = {'repository', 'branch', 'base', 'objective', 'model', 'duration_hours', 'repair_attempts', 'chat_id', 'request_id', 'acceptance', 'profile_id', 'memory_mode', 'execution_binding_id', 'execution_budget', 'task_scope', 'scope', 'profile_name', 'source_branch', 'source_sha', 'conversation_id', 'client_conversation_id', 'command_id', 'expected_revision', 'draft_repair', 'adopt_pr', 'sampling_policy', 'execution_mode', 'batch_policy_version', 'batch_preferences', 'specialist_roles'}
    if not isinstance(request, dict) or set(request) - allowed:
        raise ValueError('Unsupported engineering request fields')
    result = dict(request)
    if 'specialist_roles' in result:
        from v14.engineering_specialists import ROLES
        roles = result['specialist_roles']
        if not isinstance(roles, list) or len(roles) > 6 or any(not isinstance(role, str) or role not in ROLES for role in roles) or len(roles) != len(set(roles)):
            raise ValueError('Choose up to six distinct read-only specialist roles')
    if 'sampling_policy' in result:
        result['sampling_policy'] = validate_policy(result['sampling_policy'])
    if 'execution_mode' in result and result['execution_mode'] not in {'standard', 'smart', 'full'}:
        raise ValueError('Unsupported Gateway execution mode')
    if 'batch_policy_version' in result and (type(result['batch_policy_version']) is not int or result['batch_policy_version'] != 1):
        raise ValueError('Unsupported batch policy protocol')
    if 'batch_preferences' in result and (not isinstance(result['batch_preferences'], dict) or
            set(result['batch_preferences']) - {'enable_provider_native_batch', 'max_git_api_inflight', 'allow_parallel_local_tests', 'resume_after_transient_limits'}):
        raise ValueError('Invalid batch preferences')
    if 'scope' in result:
        result['task_scope'] = accepted_scope(result)
        result.pop('scope')
    if 'profile_name' in result and (not isinstance(result['profile_name'], str) or not 1 <= len(result['profile_name']) <= 256 or any(ord(c) < 32 for c in result['profile_name'])):
        raise ValueError('Invalid profile name')
    if 'source_sha' in result and (not isinstance(result['source_sha'], str) or not SHA.fullmatch(result['source_sha'])):
        raise ValueError('Source revision must be an exact commit SHA')
    binding_id = result.get('execution_binding_id')
    if binding_id is not None and (not isinstance(binding_id, str) or not re.fullmatch(r'bind_[a-f0-9]{32}', binding_id)):
        raise ExecutionBindingError('EXECUTION_BINDING_INVALID', 'Use the verified binding returned by this gateway.')
    budget = result.get('execution_budget', {})
    if not isinstance(budget, dict) or set(budget) - {'model_calls', 'model_tokens', 'verification_calls', 'final_calls', 'final_seconds'}:
        raise ValueError('Unsupported engineering budget')
    for key, value in budget.items():
        maximum = EXECUTION_BUDGET_LIMITS[key]
        if type(value) is not int or not 1 <= value <= maximum:
            raise ValueError('Invalid engineering budget')
    if result.get('memory_mode', 'HYBRID') not in {'SESSION_ONLY', 'PERSISTENT', 'HYBRID'}:
        raise ValueError('Invalid engineering memory mode')
    if 'profile_id' in result and (not isinstance(result['profile_id'], str) or not 1 <= len(result['profile_id']) <= 128):
        raise ValueError('Invalid engineering profile scope')
    if not re.fullmatch(r'[A-Za-z0-9-]+/[A-Za-z0-9_.-]+', str(result.get('repository', ''))):
        raise ValueError('Select a valid repository')
    for key in ('branch', 'base', *(['source_branch'] if 'source_branch' in result else [])):
        value = result.get(key, 'main' if key == 'base' else '')
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./-]{0,199}', value) or any(x in value for x in ('..', '//', '@{')) or value.endswith(('/', '.', '.lock')):
            raise ValueError('Invalid branch')
        result[key] = value
    if accepted_scope(result) not in {'read', 'plan', 'implement'}:
        raise ValueError('Unsupported task scope')
    if 'adopt_pr' in result:
        adopt = result['adopt_pr']
        if (not isinstance(adopt, dict) or set(adopt) != {'number', 'head_sha', 'base_sha'}
                or type(adopt['number']) is not int or adopt['number'] < 1
                or any(not isinstance(adopt[key], str) or not SHA.fullmatch(adopt[key]) or adopt[key] == '0' * 40 for key in ('head_sha', 'base_sha'))
                or adopt['head_sha'] != result.get('source_sha') or result.get('source_branch') != result['branch']
                or result['branch'] == result['base'] or accepted_scope(result) != 'implement' or 'draft_repair' in result):
            raise ValueError('PR adoption requires exact head/base revisions and the selected writable source branch')
    if 'draft_repair' in result:
        repair = result['draft_repair']
        fields = {'repository', 'pull_number', 'head_sha', 'source_branch', 'base_branch'}
        if not isinstance(repair, dict) or not fields <= repair.keys() or repair.keys() - fields - {'run_id', 'attempt'}:
            raise ValueError('Invalid draft repair identity')
        run_id, attempt = repair.get('run_id'), repair.get('attempt')
        if (accepted_scope(result) != 'implement' or type(repair['pull_number']) is not int or repair['pull_number'] < 1
                or not isinstance(repair['repository'], str) or repair['repository'].lower() != result['repository'].lower()
                or repair['head_sha'] != result.get('source_sha') or not result.get('source_sha') or repair['head_sha'] == '0' * 40
                or repair['source_branch'] != result.get('source_branch') or repair['base_branch'] != result['base']
                or result['branch'] in {result['base'], repair['source_branch']}
                or not ((run_id is None and attempt is None) or (type(run_id) is int and run_id > 0 and type(attempt) is int and attempt > 0))):
            raise ValueError('Draft repair does not match the exact admitted source and workflow attempt')
    for name in ('conversation_id', 'client_conversation_id', 'command_id'):
        if name in result:
            Conversations.bounded_id(result[name], name)
    if 'expected_revision' in result and (type(result['expected_revision']) is not int or result['expected_revision'] < 0):
        raise ValueError('Invalid conversation revision')
    if accepted_scope(result) == 'implement' and result['branch'] == result['base']:
        if result.get('task_scope') != 'implement':
            raise ValueError('Base-branch implementation requires explicit task scope')
        if not result.get('source_sha'):
            raise ValueError('Base-branch implementation requires a pinned source revision')
        result.setdefault('source_branch', result['branch'])
        result['branch'] = 'codex/gateway-' + digest([result.get('request_id'), result['repository'], result['source_sha']])[:20]
    for key, maximum in (('objective', 16000), ('model', 200), ('request_id', 128)):
        if not isinstance(result.get(key), str) or not 1 <= len(result[key].strip()) <= maximum:
            raise ValueError('Missing or oversized ' + key)
    hours, retries = result.get('duration_hours', 3), result.get('repair_attempts', 5)
    if type(hours) is not int or hours not in (0, 3, 6, 12, 24) or type(retries) is not int or not 1 <= retries <= 5:
        raise ValueError('Choose 3, 6, 12, 24 hours or bounded until-complete; repair attempts are 1–5')
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
        self.conversations = getattr(runtime, 'gateway_conversations', None) or Conversations(runtime, now)

    def tx(self, operation):
        if not self.runtime.GATEWAY_DURABLE_JOBS:
            raise ValueError('Engineering requires durable gateway storage')
        if not self.runtime.durable_job_initialized:
            self.runtime.init_durable_job_store()
        with self.runtime.durable_job_lock:
            db = self.runtime._durable_connect()
            try:
                with db:
                    db.execute('BEGIN IMMEDIATE')
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
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_recoveries ('
                               'parent_job_id TEXT PRIMARY KEY, child_job_id TEXT NOT NULL UNIQUE)')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_model_attempts ('
                               'id TEXT PRIMARY KEY, job_id TEXT NOT NULL, binding_id TEXT NOT NULL, revision INTEGER NOT NULL, '
                               'state TEXT NOT NULL, dispatched INTEGER NOT NULL, reserved_tokens INTEGER NOT NULL, payload TEXT NOT NULL)')
                    db.execute('CREATE TABLE IF NOT EXISTS engineering_admission_settings ('
                               'principal TEXT PRIMARY KEY, enabled INTEGER NOT NULL, revision INTEGER NOT NULL)')
                    self.conversations.schema(db)
                    return operation(db)
            finally:
                db.close()

    @staticmethod
    def admission_settings(db, principal):
        row = db.execute('SELECT enabled, revision FROM engineering_admission_settings WHERE principal=?',
                         (principal,)).fetchone()
        return {'enabled': bool(row[0]), 'revision': row[1]} if row else {'enabled': not principal.startswith(('paired:', 'phone:')), 'revision': 0}

    def change_admission_settings(self, principal, payload):
        if (set(payload) != {'enabled', 'revision'} or type(payload['enabled']) is not bool or
                type(payload['revision']) is not int or payload['revision'] < 0):
            raise ExecutionBindingError('ENGINEERING_SETTINGS_INVALID', 'Read the current setting before changing it.', 400)
        def change(db):
            current = self.admission_settings(db, principal)
            if current['revision'] != payload['revision']:
                raise ExecutionBindingError('ENGINEERING_SETTINGS_CHANGED', 'Refresh the Gateway setting before editing.')
            if current['enabled'] != payload['enabled']:
                db.execute('INSERT OR REPLACE INTO engineering_admission_settings VALUES (?,?,?)',
                           (principal, payload['enabled'], current['revision'] + 1))
                if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_control_settings'").fetchone():
                    db.execute('UPDATE gateway_control_settings SET revision=revision+1,author=?,changed=? WHERE id=1',
                               (principal, time.time()))
            return self.admission_settings(db, principal)
        return self.tx(change)

    def require_new_jobs(self, db, principal):
        if principal.startswith('paired:'):
            from desktop.control_store import ControlStore
            grant = ControlStore.device(db, principal)
            if not grant or grant['role'] != 'owner' or not json.loads(grant['repositories']):
                raise ExecutionBindingError('PAIRED_OWNER_REQUIRED', 'Pair an owner with repository permission on this PC.', 403)
            if not db.execute('SELECT 1 FROM engineering_admission_settings WHERE principal=?', (principal,)).fetchone():
                raise ExecutionBindingError('ENGINEERING_JOBS_PAUSED', 'Enable new saved jobs for this paired owner.', 412)
        if not self.admission_settings(db, principal)['enabled']:
            raise ExecutionBindingError('ENGINEERING_JOBS_PAUSED', 'Enable new saved jobs in the Gateway settings for this account.', 412)

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

    def create(self, principal, request, *, admission=None, recovery_of=None):
        if recovery_of is not None:
            self.assert_claim(recovery_of)
            if (recovery_of['principal'] != principal or
                    any(recovery_of['request'].get(key) != request.get(key)
                        for key in ('repository', 'base', 'execution_binding_id'))):
                raise ValueError('Recovery cannot change repository, owner or execution authority')
            existing = self.recovery_for(recovery_of)
        else:
            existing = self.existing_request(principal, request)
        if existing:
            return existing
        request = validate_request(new_request_defaults(request))
        batch_policy = load_batch_policy() if request.get('execution_mode') == 'full' else None
        authorize_request(request, durable=self.runtime.GATEWAY_DURABLE_JOBS, policy=batch_policy)
        request_hash = digest(request)
        if admission is not None:
            if not admission.get('ready'):
                raise ValueError('Admission has unresolved prerequisites')
            request = validate_request(dict(request, acceptance=admission['acceptance']))
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
        if request['duration_hours'] == 0 and recovery_of is None:
            job['execution_session'] = {'number': 1, 'start_steps': 0, 'start_repairs': 0}
        if recovery_of is not None:
            if recovery_of['principal'] != principal or recovery_of['request']['repository'] != request['repository']:
                raise ValueError('Recovery cannot change repository or owner')
            job.update(recovery_of=recovery_of['id'], recovery_depth=recovery_of.get('recovery_depth', 0) + 1,
                       recovery_objective=recovery_of.get('recovery_objective', recovery_of['request']['objective']),
                       deadline=min(job['deadline'], recovery_of['deadline']))
            job['data']['ci'] = deepcopy(recovery_of['data'].get('post_merge', {}))
        if request.get('execution_mode') == 'full':
            job['data']['integration_batch'] = new_plan(job, batch_policy)
        if admission is not None:
            job['data']['admission'] = admission
        capture = capture_context_snapshot(principal, self.runtime)
        if capture is not None:
            job['diagnostic_capture'] = capture
        def insert(db):
            if recovery_of is not None:
                parent = db.execute('SELECT state,revision,lease,data FROM engineering_jobs WHERE id=? AND principal=?',
                                    (recovery_of['id'], principal)).fetchone()
                epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (recovery_of['id'],)).fetchone()
                if (not parent or tuple(parent[:3]) != (recovery_of['state'], recovery_of['revision'], recovery_of.get('_execution_lease'))
                        or parent[2] is None or not epoch or epoch[0] != recovery_of.get('_owner_epoch')):
                    raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'Recovery admission lost its original job owner.')
                retained = self._recovery_in_transaction(db, recovery_of)
                if retained is not None:
                    return retained
            old = db.execute('SELECT data,state,revision,request_digest FROM engineering_jobs WHERE principal=? AND request_id=?', (principal, request['request_id'])).fetchone()
            if old:
                if recovery_of is not None:
                    raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The recovery request belongs to another retained job.')
                if old[3] != request_hash:
                    raise ValueError('Request identity was reused with different settings')
                return self.decode(old[:3])
            if recovery_of is None:
                self.require_new_jobs(db, principal)
            if db.execute("SELECT COUNT(*) FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED','FAILED')").fetchone()[0] >= 32:
                raise ValueError('Engineering job capacity reached')
            if db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0] >= 4096:
                raise ValueError('Engineering history capacity reached; archive the journal before starting another job')
            def replace_paused(db, previous_id):
                prior = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (previous_id, principal)).fetchone()
                previous = self.decode(prior)
                if previous is None or prior[3] is not None or previous['state'] not in {'MERGE_READY', 'NEEDS_INPUT'}:
                    raise ConversationConflict('The preceding job is still active; wait for its safe checkpoint')
                if previous.get('recovery_of'):
                    self.replace_paused_recovery(db, previous, job, now)
                previous.update(approval=None, updated_at=now, finished_at=now, superseded_by=job['id'],
                                message='Stopped at its paused checkpoint by an explicit follow-up. Earlier operation receipts and uncertain outcomes remain retained.')
                batch_control(previous, 'CANCELLED', 'superseded')
                db.execute("UPDATE engineering_jobs SET state='CANCELLED',revision=revision+1,data=? WHERE id=? AND revision=? AND lease IS NULL", (json.dumps(previous), previous_id, previous['revision']))
                self.event(db, previous, 'CANCELLED', previous['revision'] + 1, now)
                self.conversations.engineering_projection(db, previous, 'CANCELLED')
            conversation_binding = {key: request.get(key) for key in ('profile_id', 'profile_name', 'model', 'repository', 'branch', 'base', 'source_sha')}
            conversation_binding.update(source_branch=request.get('source_branch', request['branch']), working_branch=request['branch'],
                                        execution_binding=binding, execution_mode=request.get('execution_mode', 'standard'))
            receipt = self.conversations.admission(db, principal, request.get('command_id', request['request_id']), 'engineering', job['id'], request,
                conversation_binding, request['objective'], now=now, replace_paused=replace_paused)
            previous = self.get_in_transaction(db, receipt.get('previous_job_id'), principal)
            if previous:
                job.update(continuation_of=previous['id'], continuation_branch=previous['request']['branch'])
                same_target = all(previous['request'].get(key) == request.get(key) for key in ('repository', 'branch', 'base'))
                if same_target and previous.get('conversation_id') == receipt['conversation_id'] and previous.get('data', {}).get('actor_id') is not None:
                    job['pr_owner_job_id'] = previous.get('pr_owner_job_id', previous['id'])
                    job['continuation_actor_id'] = previous.get('data', {}).get('actor_id')
                    job['continuation_pr'] = previous.get('data', {}).get('pr')
                    if previous.get('data', {}).get('adopted_pr'):
                        job['data']['adopted_pr'] = deepcopy(previous['data']['adopted_pr'])
                head = previous.get('data', {}).get('head_sha')
                if isinstance(head, str) and SHA.fullmatch(head):
                    job['continuation_head_sha'] = head
            history = db.execute('SELECT role,substr(content,1,8192),revision FROM gateway_messages WHERE conversation_id=? AND turn_id<>? ORDER BY created_at DESC,rowid DESC LIMIT 12', (receipt['conversation_id'], receipt['command_id'])).fetchall()
            if history:
                job['conversation_context'] = {'messages': [{'role': row[0], 'content': row[1], 'revision': row[2]} for row in reversed(history)],
                                               'bounded_history': True, 'history_complete': False}
            job.update({key: receipt[key] for key in ('conversation_id', 'command_id', 'gateway_id', 'principal_id', 'accepted_at', 'detach_safe')})
            job.update(title=short_title(request['objective']), queued_at=now, started_at=None)
            db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,0,?,NULL,?)',
                       (job['id'], principal, request['request_id'], request_hash, 'PLAN', now, json.dumps(job)))
            if recovery_of is not None:
                self._record_recovery(db, recovery_of, job, origin=recovery_of['data'].get('post_merge'))
            self.event(db, job, 'PLAN', 0, now)
            return dict(job, state='PLAN', revision=0)
        created = self.tx(insert)
        if recovery_of is not None:
            self.recovery_for(recovery_of)
        return created

    def replace_paused_recovery(self, db, previous, successor, now):
        """Transfer a recovery reservation in the conversation admission transaction."""
        principal = previous['principal']
        parent_id = previous['recovery_of']
        row = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?',
                         (parent_id, principal)).fetchone()
        parent = self.decode(row)
        previous_data = previous['data']
        request = successor['request']
        if (parent is None or parent['state'] != 'VERIFY' or row[3] is not None
                or parent['data'].get('post_merge_recovery_job_id') != previous['id']
                or previous_data.get('merge_sha') or previous_data.get('post_merge_recovery_job_id')
                or any(previous['request'].get(key) != request.get(key) for key in ('repository', 'branch', 'base'))
                or any(parent['request'][key] != request[key] for key in ('repository', 'base'))
                or previous['execution_binding'] != successor['execution_binding']
                or accepted_scope(request) != 'implement'):
            raise ConversationConflict('Recovery ownership or its safe checkpoint changed. Refresh the original task before sending.')
        retained = self._recovery_in_transaction(db, parent)
        if retained is None or retained['id'] != previous['id']:
            raise ConversationConflict('The retained recovery changed. Refresh the original task before sending.')
        accepted = effective_execution_budget(previous['request'])
        requested = effective_execution_budget(request)
        calls, tokens = db.execute(
            'SELECT COUNT(*),COALESCE(SUM(reserved_tokens),0) FROM engineering_model_attempts WHERE job_id=? AND dispatched=1',
            (previous['id'],)).fetchone()
        accepted['model_calls'] -= calls
        accepted['model_tokens'] -= tokens
        remaining = {key: min(value, requested[key]) for key, value in accepted.items()}
        deadline = min(successor['deadline'], previous['deadline'], parent['deadline'])
        if (remaining['model_calls'] <= remaining['verification_calls'] + remaining['final_calls']
                or remaining['model_tokens'] < 1024 or deadline <= now + remaining['final_seconds']):
            raise ConversationConflict('The recovery has insufficient time or model budget for another instruction.')
        successor.pop('execution_session', None)
        successor.update(
            recovery_of=parent_id, recovery_depth=previous['recovery_depth'],
            recovery_objective=previous['recovery_objective'], deadline=deadline,
            steps=previous['steps'], repairs=deepcopy(previous['repairs']),
            submitted_execution_budget=request['execution_budget'],
            submitted_repair_attempts=request['repair_attempts'])
        successor['request'] = dict(request, execution_budget=remaining,
                                    repair_attempts=min(request['repair_attempts'], previous['request']['repair_attempts']))
        successor['data']['ci'] = deepcopy(parent['data'].get('post_merge_recovery_origin', {}))
        if request.get('execution_mode') == 'full':
            successor['data']['integration_batch'] = new_plan(successor, load_batch_policy())
        parent_data = parent['data']
        changed = db.execute(
            'UPDATE engineering_recoveries SET child_job_id=? WHERE parent_job_id=? AND child_job_id=?',
            (successor['id'], parent_id, previous['id'])).rowcount
        if changed != 1:
            raise ConversationConflict('The retained recovery changed. Refresh the original task before sending.')
        parent_data['post_merge_recovery_job_id'] = successor['id']
        parent_data['recovery_budget_reservation'] = deepcopy(remaining)
        post_merge = parent_data['post_merge']
        for key in ('recovery_state', 'recovery_pr', 'recovery_action'):
            post_merge.pop(key, None)
        post_merge.update(status='PENDING', reason='MERGE_RECOVERY_RUNNING', recovery_job_id=successor['id'])
        db.execute('UPDATE engineering_jobs SET data=?,next_at=MIN(next_at,?) WHERE id=?',
                   (json.dumps(parent), now, parent_id))

    def _record_recovery(self, db, parent, child, origin=None):
        """The mapping and failed observation survive a lost parent phase result."""
        db.execute('INSERT OR IGNORE INTO engineering_recoveries VALUES(?,?)', (parent['id'], child['id']))
        mapped = db.execute('SELECT child_job_id FROM engineering_recoveries WHERE parent_job_id=?', (parent['id'],)).fetchone()
        if mapped is None or mapped[0] != child['id']:
            raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The recovery binding conflicts with retained ownership.')
        row = db.execute('SELECT data FROM engineering_jobs WHERE id=? AND principal=?',
                         (parent['id'], parent['principal'])).fetchone()
        if row is None:
            raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The recovery parent is unavailable.')
        stored = json.loads(row[0])
        linked = stored['data'].get('post_merge_recovery_job_id')
        if linked is not None and linked != child['id']:
            raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The parent already names another recovery.')
        stored['data']['post_merge_recovery_job_id'] = child['id']
        budget = child['request'].get('execution_budget')
        if isinstance(budget, dict):
            reserved = stored['data'].get('recovery_budget_reservation')
            if reserved is not None and reserved != budget:
                raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The retained recovery budget differs from its child.')
            stored['data']['recovery_budget_reservation'] = deepcopy(budget)
        if origin is not None:
            if (origin.get('status') != 'FAILED' or origin.get('reason') != 'MERGE_WORKFLOW_FAILED'
                    or origin.get('merge_sha') != parent['data'].get('merge_sha')):
                raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The original merge failure is not bound to this parent.')
            stored['data'].setdefault('post_merge_recovery_origin', deepcopy(origin))
            stored['data']['post_merge'] = dict(
                stored['data']['post_merge_recovery_origin'], status='PENDING', original_status='FAILED',
                reason='MERGE_RECOVERY_RUNNING', recovery_job_id=child['id'])
        db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(stored), parent['id']))

    def _recovery_in_transaction(self, db, parent):
        rows = db.execute("SELECT data,state,revision FROM engineering_jobs WHERE json_extract(data, '$.recovery_of')=? LIMIT 4097",
                          (parent['id'],)).fetchall()
        mapped = db.execute('SELECT child_job_id FROM engineering_recoveries WHERE parent_job_id=?', (parent['id'],)).fetchone()
        linked = parent.get('data', {}).get('post_merge_recovery_job_id')
        if not rows and not mapped and not linked:
            return None
        children = {child['id']: child for child in map(self.decode, rows)}
        child = children.get(mapped[0]) if mapped else next(iter(children.values()), None)
        if len(children) > 1:
            # Explicit paused follow-ups retain their original parent identity.
            # Accept only the complete cancelled predecessor chain ending at
            # the canonical child; unrelated legacy children stay ambiguous.
            seen, current, terminated = set(), child, False
            while current is not None and current['id'] not in seen:
                seen.add(current['id'])
                predecessor = children.get(current.get('continuation_of'))
                if predecessor is None:
                    terminated = current.get('continuation_of') is None
                    break
                if (predecessor['state'] != 'CANCELLED' or predecessor.get('approval') is not None
                        or predecessor.get('superseded_by') != current['id']
                        or predecessor.get('conversation_id') != current.get('conversation_id')
                        or any(predecessor['request'].get(key) != current['request'].get(key)
                               for key in ('repository', 'branch', 'base'))
                        or current.get('continuation_branch') != predecessor['request']['branch']
                        or predecessor['data'].get('merge_sha') or predecessor['data'].get('post_merge_recovery_job_id')
                        or current['deadline'] > predecessor['deadline'] or current['steps'] < predecessor['steps']
                        or any(value > effective_execution_budget(predecessor['request'])[key]
                               for key, value in effective_execution_budget(current['request']).items())):
                    break
                current = predecessor
            if not mapped or not terminated or len(children) > 4096 or seen != set(children):
                raise ExecutionBindingError('POST_MERGE_RECOVERY_AMBIGUOUS', 'Multiple retained recoveries need reconciliation; no new recovery was admitted.')
        if (child is None
                or any(candidate['principal'] != parent['principal']
                       or any(candidate['request'].get(key) != parent['request'].get(key)
                              for key in ('repository', 'base', 'execution_binding_id'))
                       or candidate.get('execution_binding') != parent.get('execution_binding')
                       or candidate.get('recovery_depth') != parent.get('recovery_depth', 0) + 1
                       for candidate in children.values())
                or mapped is not None and mapped[0] != child['id']
                or linked is not None and linked != child['id']):
            raise ExecutionBindingError('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', 'The retained recovery identity does not match its parent.')
        # Also binds a unique child retained by an older version before its
        # parent checkpoint. Never choose arbitrarily among historical children.
        self._record_recovery(db, parent, child)
        return child

    def recovery_for(self, parent):
        def read(db):
            child = self._recovery_in_transaction(db, parent)
            stored = self.get_in_transaction(db, parent['id'], parent['principal'])
            origin = stored.get('data', {}).get('post_merge_recovery_origin') if stored else None
            budget = stored.get('data', {}).get('recovery_budget_reservation') if stored else None
            return child, deepcopy(origin), deepcopy(budget)
        child, origin, budget = self.tx(read)
        if child is not None:
            parent['data']['post_merge_recovery_job_id'] = child['id']
            if budget is not None:
                parent['data']['recovery_budget_reservation'] = budget
            if origin is not None:
                parent['data']['post_merge_recovery_origin'] = origin
            else:
                parent['data'].pop('post_merge_recovery_origin', None)
        return child

    def get_in_transaction(self, db, job_id, principal):
        if not job_id:
            return None
        return self.decode(db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=? AND principal=?', (job_id, principal)).fetchone())

    def existing_request(self, principal, request):
        """Reconcile a lost create receipt without admitting new work or changing the request identity."""
        validate_request(request)
        def read(db):
            row = db.execute('SELECT data,state,revision,request_digest FROM engineering_jobs WHERE principal=? AND request_id=?',
                             (principal, request['request_id'])).fetchone()
            existing = self.decode(row[:3]) if row else None
            normalized = None
            if existing:
                accepted = existing['request']
                if 'submitted_execution_budget' in existing:
                    accepted = dict(accepted, execution_budget=existing['submitted_execution_budget'],
                                    repair_attempts=existing['submitted_repair_attempts'])
                normalized = validate_request(retained_request_defaults(request, accepted))
            if row and row[3] != digest(normalized):
                raise ValueError('Request identity was reused with different settings')
            return existing
        return self.tx(read)

    def get(self, job_id, principal=None):
        def read(db):
            row = db.execute('SELECT data,state,revision FROM engineering_jobs WHERE id=?' + (' AND principal=?' if principal else ''),
                             (job_id, principal) if principal else (job_id,)).fetchone()
            return self.decode(row)
        return self.tx(read)

    def list(self, principal):
        return self.tx(lambda db: [self.decode(row) for row in db.execute('SELECT data,state,revision FROM engineering_jobs WHERE principal=? ORDER BY next_at DESC LIMIT 100', (principal,))])

    def due(self, *, exclude_branches=()):
        query = "SELECT id FROM engineering_jobs WHERE lease IS NULL AND next_at<=? AND state NOT IN ('MERGE_READY','NEEDS_INPUT','COMPLETED','CANCELLED','FAILED','CANCELLING')"
        parameters = [self.now()]
        # Filter occupied branches before the bounded window, otherwise their
        # queued jobs can hide independent work while another worker is free.
        for repository, branch in sorted(exclude_branches):
            query += " AND NOT (LOWER(json_extract(data, '$.request.repository'))=? AND json_extract(data, '$.request.branch')=?)"
            parameters.extend((repository.lower(), branch))
        return self.tx(lambda db: [row[0] for row in db.execute(query + ' ORDER BY next_at LIMIT 2', parameters)])

    def recover(self):
        # The composition root holds the process-wide state-directory lock.
        def recover(db):
            db.execute('UPDATE engineering_jobs SET lease=NULL')
            self._confirm_cancellation(db, recovering=True)
        self.tx(recover)

    def wake_checks(self, principal):
        self.tx(lambda db: db.execute("UPDATE engineering_jobs SET next_at=? WHERE principal=? AND state=? AND lease IS NULL "
                                      "AND json_extract(data, '$.provider_wait') IS NULL", (self.now(), principal, 'CI')))

    def claim(self, job_id):
        lease = uuid.uuid4().hex
        def claim(db):
            row = db.execute('SELECT data,state,revision,next_at FROM engineering_jobs WHERE id=? AND lease IS NULL', (job_id,)).fetchone()
            job = self.decode(row)
            if not job or job['state'] in PAUSED or job.get('provider_wait') and row[3] > self.now():
                return None
            job['started_at'] = job.get('started_at') or self.now()
            self.conversations.consume_instructions(db, job)
            batch_claimed(job)
            db.execute('UPDATE engineering_jobs SET lease=?,data=? WHERE id=? AND lease IS NULL', (lease, json.dumps(job), job_id))
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
        # The stage graph is a projection of this exact leased job, not a new
        # executor. It is committed with the same journal CAS as phase results.
        batch_transition(job, phase, output)
        job = dict(job, updated_at=self.now(), queued_at=self.now() + delay)
        if phase in {'COMPLETED', 'CANCELLED', 'FAILED'}:
            job['finished_at'] = self.now()
        artifacts = EngineeringArtifacts(self)
        phase_result = artifacts.checkpoint(job, output, 'phase-result')
        checkpoint = {key: value for key, value in job.items() if not key.startswith('_')}
        checkpoint['data'] = artifacts.checkpoint(job, job['data'], 'checkpoint')
        def save(db):
            nonlocal phase
            recovery = db.execute('SELECT child_job_id FROM engineering_recoveries WHERE parent_job_id=?', (job['id'],)).fetchone()
            if recovery is not None:
                retained = self.get_in_transaction(db, job['id'], job['principal'])
                job['data']['post_merge_recovery_job_id'] = recovery[0]
                checkpoint['data']['post_merge_recovery_job_id'] = recovery[0]
                origin = retained['data'].get('post_merge_recovery_origin')
                budget = retained['data'].get('recovery_budget_reservation')
                for data in (job['data'], checkpoint['data']):
                    if budget is not None:
                        data['recovery_budget_reservation'] = deepcopy(budget)
                    if origin is not None:
                        data['post_merge_recovery_origin'] = deepcopy(origin)
                    else:
                        data.pop('post_merge_recovery_origin', None)
            if phase == 'COMPLETED' and job.get('conversation_id') and db.execute("SELECT 1 FROM gateway_pending_instructions WHERE conversation_id=? AND status='pending' LIMIT 1", (job['conversation_id'],)).fetchone():
                # A last-moment instruction was accepted before terminal commit.
                # Preserve it visibly; never silently drop it or replay a write.
                phase = 'NEEDS_INPUT'
                job['message'] = 'The run finished while new instructions were queued. Review the saved result and start a new turn for those instructions.'
                job.pop('finished_at', None)
                # The terminal projection was prepared before opening this
                # transaction. Reclassify it with the effective committed
                # phase so the UI cannot claim Full Batch completion while the
                # durable job is actually waiting for a user continuation.
                batch_transition(job, phase, dict(output, error_code='PENDING_INSTRUCTIONS'))
                from v14.engineering_report import build_report
                prior_report = job['data'].get('final_report') or {}
                verification = dict(job['data'].get('completion_verification') or {},
                                    passed=False, failures=['PENDING_INSTRUCTIONS'])
                report = build_report(job, phase, verification, committed_revision=job['revision'] + 1)
                report.update(summary=job['message'], prior_objective_status=prior_report.get('status'))
                job['data']['completion_verification'] = verification
                checkpoint['data']['completion_verification'] = verification
                job['data']['final_report'] = report
                phase_result['error_code'] = 'PENDING_INSTRUCTIONS'
                checkpoint.update(message=job['message'])
                checkpoint.pop('finished_at', None)
            report = job['data'].get('final_report') if phase in PAUSED else None
            if report:
                owner = db.execute('SELECT revision,lease FROM engineering_jobs WHERE id=?', (job['id'],)).fetchone()
                if not owner or tuple(owner) != (job['revision'], lease):
                    self._confirm_cancellation(db, job['id'], job.get('_owner_epoch'))
                    return False
                db.execute('SAVEPOINT final_report_artifact')
                try:
                    reference = artifacts.put_json(job, report, 'final-report', transaction=db)
                    report = dict(report, artifact=reference)
                except (ExecutionBindingError, OSError, sqlite3.Error) as error:
                    db.execute('ROLLBACK TO final_report_artifact')
                    report = dict(report, artifact_availability='UNAVAILABLE',
                                  artifact_error=getattr(error, 'code', type(error).__name__))
                finally:
                    db.execute('RELEASE final_report_artifact')
                job['data']['final_report'] = checkpoint['data']['final_report'] = report
                phase_result['terminal_receipt'] = report['terminal_receipt']
            payload, _ = canonical_json(checkpoint, maximum_bytes=4 * 1024 * 1024)
            output_json, _ = canonical_json(phase_result, maximum_bytes=2 * 1024 * 1024)
            # Keep every meaningful update, without thousands of identical CI
            # polls filling Android history during a multi-day job.
            unchanged = False
            if job['state'] == phase == 'CI':
                previous = db.execute('SELECT output_json FROM engineering_receipts WHERE job_id=? AND status=? ORDER BY revision DESC LIMIT 1', (job['id'], 'CONFIRMED')).fetchone()
                unchanged = previous is not None and previous[0] == output_json
            changed = db.execute('UPDATE engineering_jobs SET state=?,revision=revision+1,next_at=?,lease=NULL,data=? WHERE id=? AND revision=? AND lease=?',
                                 (phase, self.now() + delay, payload, job['id'], job['revision'], lease))
            if changed.rowcount != 1:
                # Returning from this exact owner's phase confirms its stop,
                # without publishing the output that lost to cancellation.
                self._confirm_cancellation(db, job['id'], job.get('_owner_epoch'))
                return False  # Cancel/revoke won the race; never resurrect it.
            self.conversations.engineering_projection(db, job, phase)
            if job.get('recovery_of') and phase != job['state']:
                db.execute("UPDATE engineering_jobs SET next_at=MIN(next_at,?) WHERE id=? AND principal=? "
                           "AND state='VERIFY' AND json_extract(data, '$.data.post_merge_recovery_job_id')=?",
                           (self.now(), job['recovery_of'], job['principal'], job['id']))
            if not unchanged:
                self.event(db, job, phase, job['revision'] + 1, self.now())
            db.execute("UPDATE engineering_receipts SET status='CONFIRMED',output_json=? WHERE job_id=? AND revision=?", (output_json, job['id'], job['revision']))
            return True
        committed = self.tx(save)
        if committed and job['data'].get('terminal_session') and phase in {'COMPLETED', 'CANCELLED', 'FAILED'}:
            from v14.workspace_terminal import dispose
            dispose(job)
        return committed

    def assert_claim(self, job):
        def read(db):
            row = db.execute('SELECT state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job['id'], job['principal'])).fetchone()
            epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
            if not row or tuple(row) != (job['state'], job['revision'], job.get('_execution_lease')) or row[2] is None or not epoch or epoch[0] != job.get('_owner_epoch'):
                raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The execution lease or job state changed; no new operation was dispatched.')
        self.tx(read)

    def save_adaptive_planning(self, job, value):
        raw, _ = canonical_json(value, maximum_bytes=128 * 1024)
        def save(db):
            row = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job['id'], job['principal'])).fetchone()
            epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
            if not row or tuple(row[1:]) != (job['state'], job['revision'], job.get('_execution_lease')) or row[3] is None or not epoch or epoch[0] != job.get('_owner_epoch'):
                raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The planning checkpoint owner changed.')
            stored = json.loads(row[0])
            stored.setdefault('data', {})['adaptive_planning'] = json.loads(raw)
            db.execute('UPDATE engineering_jobs SET data=? WHERE id=? AND lease=?', (json.dumps(stored), job['id'], job['_execution_lease']))
        self.tx(save)
        job['data']['adaptive_planning'] = json.loads(raw)

    def record_model_attempt(self, job, record):
        raw, _ = canonical_json(record, maximum_bytes=128 * 1024)
        def save(db):
            row = db.execute('SELECT state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job['id'], job['principal'])).fetchone()
            old = db.execute('SELECT dispatched FROM engineering_model_attempts WHERE id=? AND job_id=?', (record['attempt_id'], job['id'])).fetchone()
            if record['state'] == 'DISPATCHED':
                epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
                if not row or tuple(row) != (job['state'], job['revision'], job.get('_execution_lease')) or row[2] is None or not epoch or epoch[0] != job.get('_owner_epoch'):
                    raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The execution owner changed before model dispatch.')
                calls, tokens = db.execute('SELECT COUNT(*),COALESCE(SUM(reserved_tokens),0) FROM engineering_model_attempts WHERE job_id=? AND dispatched=1', (job['id'],)).fetchone()
                accepted = effective_execution_budget(job['request'])
                call_limit = accepted['model_calls']
                call_reserve = accepted['verification_calls'] + accepted['final_calls']
                if record.get('role') in {'final summary', 'final report'}:
                    call_reserve = 0
                elif record.get('role') == 'selected-model reviewer':
                    call_reserve = accepted['final_calls']
                token_limit = accepted['model_tokens']
                token_reserve = 0 if call_reserve == 0 else record.get('budget', {}).get('final_tokens', 0) + record.get('budget', {}).get('verification_tokens', 0)
                if (old is None or not old[0]) and (calls >= max(0, call_limit - call_reserve) or tokens + record.get('reserved_tokens', 0) + token_reserve > token_limit):
                    raise ExecutionBindingError('EXECUTION_RUN_BUDGET_EXHAUSTED', 'The retained job reached its inference budget. Verification and final reporting capacity were preserved.')
            db.execute('INSERT INTO engineering_model_attempts VALUES(?,?,?,?,?,?,?,?) '
                       'ON CONFLICT(id) DO UPDATE SET state=excluded.state,dispatched=MAX(dispatched,excluded.dispatched),reserved_tokens=excluded.reserved_tokens,payload=excluded.payload',
                       (record['attempt_id'], job['id'], record['binding_id'], job['revision'], record['state'], int(record.get('dispatched') is True), record.get('reserved_tokens', 0), raw))
        self.tx(save)

    def save_specialists(self, job, value):
        raw, _ = canonical_json(value, maximum_bytes=128 * 1024)
        def save(db):
            row = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?', (job['id'], job['principal'])).fetchone()
            epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
            if not row or tuple(row[1:]) != (job['state'], job['revision'], job.get('_execution_lease')) or row[3] is None or not epoch or epoch[0] != job.get('_owner_epoch'):
                raise ExecutionBindingError('EXECUTION_OWNER_CHANGED', 'The investigator checkpoint owner changed.')
            stored = json.loads(row[0])
            stored.setdefault('data', {})['specialists'] = json.loads(raw)
            db.execute('UPDATE engineering_jobs SET data=? WHERE id=? AND lease=?', (json.dumps(stored), job['id'], job['_execution_lease']))
        self.tx(save)
        job['data']['specialists'] = json.loads(raw)

    def model_attempts(self, job_id, after=None, limit=100):
        limit = max(1, min(100, limit))
        return self.tx(lambda db: [json.loads(row[0]) for row in db.execute('SELECT payload FROM engineering_model_attempts WHERE job_id=? AND rowid>COALESCE((SELECT rowid FROM engineering_model_attempts WHERE id=? AND job_id=?),0) ORDER BY rowid LIMIT ?', (job_id, after, job_id, limit))])

    def control(self, job_id, principal, action, expected_revision, approval=None):
        def update(db):
            row = db.execute('SELECT data,state,revision,lease FROM engineering_jobs WHERE id=? AND principal=?' + ('' if action == 'cancel' else ' AND lease IS NULL'), (job_id, principal)).fetchone()
            job = self.decode(row)
            if not job or job['revision'] != expected_revision:
                raise ValueError('Job changed; refresh before confirming')
            if job['state'] in {'CANCELLED', 'COMPLETED', 'FAILED'}:
                raise ValueError('The job is already finished')
            if job['state'] == 'CANCELLING':
                if action == 'cancel':
                    return job
                raise ValueError('Cancellation is still pending')
            job['updated_at'] = self.now()
            job.pop('provider_wait', None)
            job['data']['operator_controls'] = job['data'].get('operator_controls', 0) + 1
            if action == 'approve':
                if job['state'] != 'MERGE_READY':
                    raise ValueError('The job is not merge-ready')
                job['approval'] = approval
                job['message'] = 'Approval received. Rechecking the exact PR, commit and merge policy.'
                state = 'MERGE'
            elif action == 'cancel':
                job['approval'] = None
                state = 'CANCELLING' if row[3] else 'CANCELLED'
                job['cancellation_requested'] = state == 'CANCELLING'
                job['cancellation_outcome'] = 'pending' if state == 'CANCELLING' else 'confirmed'
                epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job_id,)).fetchone()
                job['cancellation_owner_epoch'] = epoch[0] if epoch else None
                if state == 'CANCELLED':
                    job['finished_at'] = self.now()
                job['message'] = 'Cancellation requested. Waiting for the current worker to stop; dispatched operations retain their original receipts.' if state == 'CANCELLING' else 'Cancelled before further execution. Retained receipts remain available.'
            elif action == 'request_changes':
                job['approval'] = None
                job['message'] = 'Changes requested. Submit the revised objective as a new job; retained evidence remains available.'
                state = 'NEEDS_INPUT'
            else:
                raise ValueError('Unsupported job action')
            batch_control(job, state, action)
            db.execute('UPDATE engineering_jobs SET data=?,state=?,revision=revision+1,next_at=?,lease=NULL WHERE id=?', (json.dumps(job), state, self.now(), job_id))
            self.event(db, job, state, job['revision'] + 1, self.now())
            self.conversations.engineering_projection(db, job, state)
            if action == 'cancel':
                descendants = {job_id}
                active = db.execute("SELECT data,state,revision,lease FROM engineering_jobs WHERE principal=? AND state NOT IN ('CANCELLED','COMPLETED','FAILED')", (principal,)).fetchall()
                for _ in range(6):
                    found = False
                    for child_row in active:
                        child = self.decode(child_row)
                        if child['id'] in descendants or child.get('recovery_of') not in descendants:
                            continue
                        descendants.add(child['id'])
                        found = True
                        child_state = 'CANCELLING' if child_row[3] else 'CANCELLED'
                        epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (child['id'],)).fetchone()
                        child.update(approval=None, cancellation_requested=child_state == 'CANCELLING',
                                     cancellation_outcome='pending' if child_state == 'CANCELLING' else 'confirmed',
                                     cancellation_owner_epoch=epoch[0] if epoch else None, updated_at=self.now(),
                                     message='Recovery cancelled with its original engineering job; dispatched receipts remain retained.')
                        if child_state == 'CANCELLED':
                            child['finished_at'] = self.now()
                        batch_control(child, child_state, 'cancel')
                        db.execute('UPDATE engineering_jobs SET data=?,state=?,revision=revision+1,next_at=?,lease=NULL WHERE id=?',
                                   (json.dumps(child), child_state, self.now(), child['id']))
                        self.event(db, child, child_state, child['revision'] + 1, self.now())
                        self.conversations.engineering_projection(db, child, child_state)
                    if not found:
                        break
            return dict(job, state=state, revision=job['revision'] + 1)
        updated = self.tx(update)
        if action == 'cancel' and updated['data'].get('terminal_session'):
            from v14.workspace_terminal import dispose
            dispose(updated)
        return updated

    def _confirm_cancellation(self, db, job_id=None, owner_epoch=None, *, recovering=False):
        if not recovering and owner_epoch is None:
            return
        rows = db.execute("SELECT data,state,revision FROM engineering_jobs WHERE state='CANCELLING'" + (' AND id=?' if job_id else ''), (job_id,) if job_id else ()).fetchall()
        for row in rows:
            job = self.decode(row)
            if not recovering and job.get('cancellation_owner_epoch') != owner_epoch:
                continue
            now = self.now()
            job.update(updated_at=now, finished_at=now, cancellation_requested=False, cancellation_outcome='confirmed',
                       message='Gateway work stopped. Operations dispatched before cancellation retain their recorded or uncertain outcomes.')
            batch_control(job, 'CANCELLED', 'cancellation_confirmed')
            changed = db.execute("UPDATE engineering_jobs SET state='CANCELLED',revision=revision+1,lease=NULL,data=? WHERE id=? AND state='CANCELLING' AND revision=?", (json.dumps(job), job['id'], job['revision']))
            if changed.rowcount:
                self.event(db, job, 'CANCELLED', job['revision'] + 1, now)
                self.conversations.engineering_projection(db, job, 'CANCELLED')

    def confirm_cancellation(self, job_id, owner_epoch):
        self.tx(lambda db: self._confirm_cancellation(db, job_id, owner_epoch))

    def invalidate_ready(self, job, evidence):
        """A stale approval prompt is retired durably before reporting rejection."""
        changed_head = evidence.get('head_sha') != job['data'].get('head_sha')
        state = 'NEEDS_INPUT' if changed_head else 'CI'
        job = dict(job, approval=None, updated_at=self.now(), message=(
            'The PR head changed outside this job. Review the new commit before continuing.' if changed_head else
            'Merge readiness changed. Waiting for current checks, reviews and policy.'))
        job['data'] = dict(job['data'], readiness=evidence)
        batch_control(job, state, 'readiness_changed')
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
        if job['stalls'] >= 3:
            return 'Three attempts made no verified progress'
        session = job.get('execution_session') or {}
        if job['steps'] - session.get('start_steps', 0) >= SESSION_STEPS and job['state'] not in {'CI', 'MERGE', 'VERIFY'}:
            if not EngineeringSupervisor.renew(job):
                return 'Step budget reached; confirmed work is retained'
        return None

    @staticmethod
    def renew(job):
        session = job.get('execution_session')
        if session is None or session['number'] >= CONTINUOUS_SESSIONS:
            return False
        job['execution_session'] = {'number': session['number'] + 1, 'start_steps': job['steps'],
                                    'start_repairs': sum(job['repairs'].values())}
        return True

    @staticmethod
    def repair(job, fingerprint):
        counts = dict(job['repairs'])
        count = counts.get(fingerprint, 0)
        if count >= job['request']['repair_attempts']:
            return False
        session = job.get('execution_session') or {}
        if sum(counts.values()) - session.get('start_repairs', 0) >= SESSION_REPAIRS:
            if not EngineeringSupervisor.renew(job):
                return False
        counts[fingerprint] = count + 1
        job['repairs'] = counts
        job['message'] = f"Repairing validation failure · attempt {count + 1}/{job['request']['repair_attempts']}"
        return True


class EngineeringService:
    def __init__(self, store, backend, *, public_repository_maps=False):
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
        graph_reader = getattr(backend, 'graph_reader', None)
        self.graph = GraphService(store, graph_reader() if callable(graph_reader) else backend, self.evidence)
        self.public_graph = None
        if public_repository_maps:
            from v14.code_graph.public_github import PublicGitHubReader
            self.public_graph = GraphService(store, PublicGitHubReader(), public=True)
        self.graph.public_service = self.public_graph
        self.graph.cancelled = lambda job: (self.stop.is_set() or
            getattr(backend, 'cancelled', lambda identifier: False)(job['id']) or time.time() >= job.get('deadline', float('inf')))
        backend.record_evidence = lambda job, payload: self.evidence.append(job, 'github_api', payload)
        backend.recall_memory = self.memory.recall
        backend.recall_graph = self.graph.recall
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.thread = None
        self.authorized = lambda job: True
        from v14.copilot_tasks import CopilotTasks
        self.copilot = CopilotTasks(self)
        backend.model_executor = self.execute_model
        backend.save_specialists = self.store.save_specialists
        backend.save_adaptive_planning = self.store.save_adaptive_planning
        backend.before_mutation = self.validate_mutation
        backend.operations = self.operations
        backend.private_artifacts = self.artifacts

    def prepare_binding(self, principal, selection, gateway_endpoint=None):
        return self.bindings.prepare(principal, selection, gateway_endpoint)

    def job_response(self, job):
        result = public_job(job)
        result.update(server_now=self.store.now(), server_time_ms=int(self.store.now() * 1000))
        if job.get('conversation_id'):
            snapshot = self.store.conversations.snapshot(job['principal'], job['conversation_id'])
            if snapshot:
                result.update(conversation_revision=snapshot['revision'], history_complete=snapshot['history_complete'],
                              history_limitation=snapshot['history_limitation'])
        return result

    def binding_capabilities(self, principal, binding_id):
        binding, _, capabilities = self.bindings.validate(principal, binding_id, force=True)
        return {'binding': binding, 'capabilities': capabilities}

    def validate_submission(self, principal, request):
        value = validate_request(request)
        batch_policy = load_batch_policy() if value.get('execution_mode') == 'full' else None
        authorize_request(value, durable=self.store.runtime.GATEWAY_DURABLE_JOBS, policy=batch_policy)
        binding, _, _ = self.bindings.validate(principal, value.get('execution_binding_id'), force=True)
        if value['model'] != binding['requested_model_id'] or value.get('profile_id') != binding['profile_uid']:
            raise ExecutionBindingError('EXECUTION_SELECTION_MISMATCH', 'The submitted model/profile does not match the selected execution binding.')
        return binding

    def preflight_submission(self, principal, request):
        self.store.tx(lambda db: self.store.require_new_jobs(db, principal))
        self.validate_submission(principal, request)
        from v14.engineering_preflight import preflight
        value = validate_request(request)
        if value.get('conversation_id'):
            conversation = self.store.conversations.snapshot(principal, value['conversation_id'])
            previous = self.store.get(conversation.get('active_job_id'), principal) if conversation and conversation.get('active_job_id') else None
            head = previous.get('data', {}).get('head_sha') if previous else None
            if previous and previous['request']['branch'] == value['branch'] and isinstance(head, str) and SHA.fullmatch(head):
                value = dict(value, source_branch=value['branch'], source_sha=head)
        result = preflight(value)
        result['validation_source_sha'] = value.get('source_sha')
        result['source_sha'] = request.get('source_sha')
        result['checks']['selected_model'] = 'ready'
        if result['ready']:
            access = getattr(self.backend, 'preflight_access', None)
            if callable(access):
                result['checks'].update(access(value))
        return result

    def admit(self, principal, request):
        existing = self.store.existing_request(principal, request)
        if existing:
            return existing
        from v14.engineering_preflight import EngineeringPreflightError
        admission = self.preflight_submission(principal, request)
        if not admission['ready']:
            raise EngineeringPreflightError(admission)
        return self.store.create(principal, request, admission=admission)

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
        if accepted_scope(job['request']) != 'implement':
            raise ExecutionBindingError('TASK_SCOPE_READ_ONLY', 'This conversation turn does not authorize repository writes.', 403)
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
        result = completion_gate(job, readiness, merged, semantic_receipts=receipts)
        retained = job['data'].setdefault('criterion_receipts', {})
        for requirement in result.get('requirements', []):
            value = dict(requirement, source_sha=job['data'].get('head_sha'),
                         evidence_digest=fingerprint(readiness), merged=merged)
            identity = fingerprint(value)
            previous = retained.get(requirement['id'], {})
            if previous.get('identity') != identity:
                saved = self.evidence.append(job, 'criterion_verdict', value)
                retained[requirement['id']] = {'identity': identity, 'receipt': saved,
                    'source_sha': value['source_sha'], 'status': requirement['status']}
        result['criterion_receipts'] = deepcopy(retained)
        if merged and job['data'].get('post_merge', {}).get('status') != 'PASSED':
            result['passed'] = False
            result['failures'].append('POST_MERGE_EVIDENCE_REQUIRED')
        return result

    def readiness(self, *, public_only=False):
        """Local engineering prerequisites; this never implies model or GitHub API success."""
        from v14.engineering_preflight import configuration_status
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
        configured = not public_only and bool(self.backend.available())
        graph_connection = ('configured' if not public_only and self.graph.backend.available()
                            else 'public_only' if self.public_graph is not None else 'missing')
        graph_configured = graph_connection != 'missing'
        worker = not public_only and self.thread is not None and self.thread.is_alive() and not self.stop.is_set()
        draining = bool(getattr(runtime, 'gateway_draining', False))
        graph_ready = journal == 'ready' and graph_configured and not draining
        return {'schema_version': 1, 'ready': journal == 'ready' and configured and worker and not draining, 'graph_ready': graph_ready,
                'saved_results': journal in {'ready', 'write_failed'},
                'checks': {'journal': journal, 'host_github_connection': 'configured' if configured else 'missing',
                           'graph_host_github_connection': graph_connection,
                           'worker': 'running' if worker else 'unavailable', 'draining': draining,
                           'validation_policy': configuration_status(), 'acceptance': 'requires_objective_preflight'},
                'implementation_ready': None, 'implementation_preflight_required': True,
                'github_reachable': None, 'repository_access': None, 'inference_ready': None,
                'graph_supported': True, 'graph_configured': graph_configured, 'graph_ready': graph_ready,
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
        try:
            with execution_diagnostic_context(job.get('diagnostic_capture'), self.store.runtime, job['principal'],
                                              lambda: self.authorized(job)):
                self._tick_claimed(job, lease, recovering)
        finally:
            self.store.confirm_cancellation(job_id, job['_owner_epoch'])

    @staticmethod
    def retry_checkpoint(job, retry_at, source, attempts):
        return {'retry_at': retry_at, 'source': source, 'attempts': attempts,
                'stage': job['state'], 'checkpoint_revision': job['revision'] + 1,
                'job_hash': digest(job['id']), 'binding_hash': digest(job['request'].get('execution_binding_id'))}

    def _tick_claimed(self, job, lease, recovering):
        phase, output, delay = job['state'], {}, 0
        previous_wait = job.pop('provider_wait', None) or {}
        try:
            self.artifacts.restore_stage(job)
            limit = EngineeringSupervisor.limit(job, self.store.now()) if self.authorized(job) else 'Gateway engineering permission was revoked'
            if job['data'].pop('instruction_requires_review', False):
                raise ValueError('New user instructions require review before the next repository write')
            if accepted_scope(job['request']) != 'implement' and phase not in {'PLAN', 'READ'}:
                raise PermissionError('Read and plan tasks cannot dispatch engineering writes')
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
                    record_verified_mutation_reconciliation(job, phase, output)
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
                elif output.get('checks_blocked'):
                    job['data']['ci'] = output
                    job['message'] = output.get('message', 'A completed CI check needs attention')
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
                    job['external_observations'] = job.get('external_observations', 0) + 1
            else:
                # Legacy jobs remain readable but cannot acquire new model
                # authority from today's settings or a reused display name.
                if phase in {'PLAN', 'READ', 'IMPLEMENT', 'REVIEW'}:
                    self.validate_execution(job, force=recovering)
                if phase == 'VERIFY':
                    self.store.recovery_for(job)
                if phase == 'VERIFY' and job['data'].get('post_merge_recovery_job_id'):
                    from v14.engineering_post_merge_recovery import inspect as inspect_recovery
                    recovered = inspect_recovery(self, job)
                    output = {'post_merge': recovered, 'result': dict(job['data'].get('result', {}),
                        qualification=recovered['status'], recovery_job_id=recovered.get('recovery_job_id'),
                        recovery_merge_sha=recovered.get('recovery_merge_sha'),
                        qualified_revision=recovered.get('qualified_revision'))}
                else:
                    output = self.backend.step(job, phase)
                job['data'].update(output)
                if phase == 'READ' and accepted_scope(job['request']) in {'read', 'plan'}:
                    phase = 'COMPLETED'
                elif phase == 'IMPLEMENT' and output.get('workspace_continue'):
                    phase = 'IMPLEMENT'
                elif phase == 'IMPLEMENT':
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
                    post_merge = output.get('post_merge', {})
                    if post_merge.get('status') == 'FAILED' and not job['data'].get('post_merge_recovery_job_id'):
                        from v14.engineering_post_merge_recovery import enqueue
                        recovery = enqueue(self, job, post_merge)
                        if recovery is not None:
                            job['data']['post_merge_recovery_job_id'] = recovery['id']
                            post_merge = dict(post_merge, status='PENDING', original_status='FAILED',
                                              reason='MERGE_RECOVERY_RUNNING', recovery_job_id=recovery['id'])
                            job['data']['post_merge'] = post_merge
                    if post_merge.get('status') == 'PENDING' and self.store.now() < job['deadline']:
                        observations = job.get('external_observations', 0)
                        phase, delay = 'VERIFY', min(120, 30 * (1 + observations // 10),
                                                     job['deadline'] - self.store.now())
                        job['message'] = ('The linked recovery needs attention' if post_merge.get('recovery_state') in {'MERGE_READY', 'NEEDS_INPUT'}
                                          else 'Merge confirmed; verifying workflows on the resulting revision')
                    elif post_merge.get('status') != 'PASSED':
                        phase = 'NEEDS_INPUT'
                        gate['passed'] = False
                        gate['failures'].append(post_merge.get('reason', 'POST_MERGE_EVIDENCE_REQUIRED'))
                        job['message'] = 'Merge confirmed; resulting revision is not qualified: ' + post_merge.get('reason', 'POST_MERGE_EVIDENCE_REQUIRED')
                    else:
                        phase = 'COMPLETED' if gate['passed'] else 'NEEDS_INPUT'
                    if not gate['passed'] and post_merge.get('status') == 'PASSED':
                        job['message'] = 'Merge occurred but completion evidence is incomplete: ' + '; '.join(gate['failures'])
                    job['data']['completion_verification'] = gate
                    self.evidence.append(job, 'completion_gate', gate)
                else:
                    phase = 'CI' if phase == 'PROMOTE' else PHASES[PHASES.index(phase) + 1]
                job['stalls'] = 0
                if phase == 'VERIFY' and delay:
                    job['external_observations'] = job.get('external_observations', 0) + 1
                else:
                    job['steps'] += 1
                if phase not in PAUSED and phase != 'VERIFY':
                    job['message'] = {'READ': 'Reading selected repository files in batches', 'IMPLEMENT': 'Preparing coordinated changes',
                                      'REVIEW': 'Selected-model review of proposed changes', 'COMMIT': 'Publishing the reviewed branch changes',
                                      'OPEN_PR': 'Preparing the draft pull request', 'CI': 'Waiting for CI validation',
                                      'COMPLETED': 'Merge verified; engineering result published'}.get(phase, phase)
                elif phase == 'COMPLETED' and accepted_scope(job['request']) != 'implement':
                    job['message'] = 'Repository answer saved' if job['request']['task_scope'] == 'read' else 'Repository plan saved'
                elif phase == 'COMPLETED':
                    job['message'] = 'Approved merge and workflow evidence verified. Feature acceptance is limited to configured paths/checks; unexecuted benchmarks remain Not measured.'
        except Exception as error:
            output = {'error_type': type(error).__name__}
            if isinstance(error, ExecutionBindingError):
                output.update(error_code=error.code, dispatched=getattr(error, 'dispatched', False))
            response = getattr(error, 'response', None)
            http_status = getattr(response, 'status_code', None)
            http_status = http_status if type(http_status) is int else None
            response_headers = getattr(response, 'headers', None) or {}
            rate_limited = is_rate_limit_response(http_status, response_headers)
            mutation = job['state'] in MUTATIONS
            before_dispatch = (mutation and getattr(self.backend, 'physical_operation_journal', False)
                               and not self.operations.dispatched(job))
            model_readiness_wait = (
                isinstance(error, ExecutionBindingError) and
                error.code in {'EXECUTION_MODEL_NOT_LOADED', 'EXECUTION_MODEL_LOADING'}
            )
            backend_readiness_wait = (
                isinstance(error, ExecutionBindingError) and error.retryable and
                error.code in {'EXECUTION_BACKEND_UNAVAILABLE', 'EXECUTION_METADATA_DEADLINE'} and
                not getattr(error, 'dispatched', False)
            )
            readiness_wait = model_readiness_wait or backend_readiness_wait
            host_interrupted = isinstance(error, InterruptedError) and (self.stop.is_set() or getattr(self.store.runtime, 'gateway_draining', False))
            recovery_delay = (None if rate_limited or readiness_wait else
                              retry_delay(error, 0, max_retry_after=None, now=self.store.now))
            transient = recovery_delay is not None
            # Before-dispatch recovery can retry safely. A dispatched mutation
            # must still reconcile, even when its error looks transient.
            deferred_write = before_dispatch and (rate_limited or transient or readiness_wait or host_interrupted)
            if not host_interrupted:
                job['connection_retries'] = job.get('connection_retries', 0) + 1 if transient else 0
            if not transient and not rate_limited and not readiness_wait and not host_interrupted:
                job['stalls'] += 1
            # Mutations stay uncertain; no automatic replay after a lost response.
            if accepted_scope(job['request']) != 'implement' and job['state'] not in {'PLAN', 'READ'}:
                phase = 'NEEDS_INPUT'
                job['message'] = 'The retained phase is outside this read-only task scope. No repository operation was dispatched.'
                output.update(error_code='TASK_SCOPE_READ_ONLY', dispatched=False)
            elif before_dispatch and not deferred_write:
                phase = 'NEEDS_INPUT'
                output['dispatched'] = False
                job['message'] = 'The write was rejected before dispatch. Confirmed work is retained. ' + str(getattr(error, 'code', type(error).__name__))
                job['data']['blocked_reason'] = {'code': getattr(error, 'code', 'OPERATION_PRECHECK_REJECTED'), 'retryable': False, 'dispatched': False}
            elif mutation and not deferred_write:
                recovered = None
                try:
                    recovered = self.backend.reconcile(job, job['state'])
                except Exception:
                    pass
                if recovered is not None:
                    record_verified_mutation_reconciliation(job, job['state'], recovered)
                    job['data'].update(recovered)
                    output = recovered
                    phase = {'COMMIT': 'OPEN_PR', 'OPEN_PR': 'CI', 'PROMOTE': 'CI', 'MERGE': 'VERIFY'}[job['state']]
                else:
                    phase = 'NEEDS_INPUT'
                    job['message'] = 'A write could not be confirmed. Reconcile its remote outcome before continuing.'
            elif host_interrupted:
                if previous_wait:
                    job['provider_wait'] = previous_wait
                output.update(error_code='ENGINEERING_HOST_INTERRUPTED', retryable=True)
                job['message'] = 'The saved step will resume when the gateway is running.'
            elif rate_limited:
                attempts = previous_wait.get('attempts', 0) + 1
                now = self.store.now()
                wait, source = provider_rate_limit_delay(response_headers, attempts, now)
                retry_at = now + wait
                # A durable job owns provider cooldown recovery until its accepted
                # deadline. An arbitrary retry-count ceiling converted a healthy
                # long-running job into NEEDS_INPUT during extended 429 windows.
                available = retry_at < job['deadline'] - job['request'].get('execution_budget', {}).get('final_seconds', 30)
                resume_limit = (job['request'].get('batch_preferences') or {}).get('resume_after_transient_limits', True)
                can_resume = available and (job['request'].get('execution_mode') != 'full' or resume_limit)
                output.update(error_code='ENGINEERING_RATE_LIMIT_WAIT', http_status=http_status, retryable=can_resume,
                              retry_at=retry_at, retry_source=source, attempts=attempts)
                if deferred_write:
                    output['dispatched'] = False
                if can_resume:
                    job['provider_wait'] = self.retry_checkpoint(job, retry_at, source, attempts)
                    job['message'] = 'Waiting for provider capacity. The saved step will resume automatically.'
                    delay = wait
                else:
                    phase = 'NEEDS_INPUT'
                    job['message'] = ('Automatic recovery is disabled by the accepted batch policy. Confirmed work is saved for review.'
                                      if available else 'The provider wait reached the job budget. Confirmed work is saved for review.')
                    job['data']['blocked_reason'] = dict(output)
            elif readiness_wait:
                attempts = previous_wait.get('attempts', 0) + 1
                now = self.store.now()
                # Transient metadata outages and host-controlled model preparation
                # keep the exact selected binding. Only this non-mutating stage
                # resumes; the mutation reconciliation branches above take priority.
                wait = min(300, 5 * 2 ** min(6, max(0, attempts - 1)))
                retry_at = now + wait
                available = retry_at < job['deadline'] - job['request'].get('execution_budget', {}).get('final_seconds', 30)
                resume_limit = (job['request'].get('batch_preferences') or {}).get('resume_after_transient_limits', True)
                can_resume = available and (job['request'].get('execution_mode') != 'full' or resume_limit)
                source = 'MODEL_READINESS' if model_readiness_wait else 'BACKEND_READINESS'
                output.update(error_code=error.code, retryable=can_resume,
                              retry_at=retry_at, retry_source=source, attempts=attempts)
                if can_resume:
                    job['provider_wait'] = dict(self.retry_checkpoint(job, retry_at, source, attempts), reason_code=error.code)
                    job['message'] = ('Waiting for the exact selected model. The saved step will resume automatically after host preparation completes.'
                                      if model_readiness_wait else
                                      'Waiting for the selected backend to recover. The saved step will resume with the same model and binding.')
                    delay = wait
                else:
                    phase = 'NEEDS_INPUT'
                    job['message'] = ('Automatic recovery is disabled by the accepted batch policy. Confirmed work is saved for review.' if available else
                                      'The selected backend or model remained unavailable through the accepted job deadline. Confirmed work is saved for review.')
                    job['data']['blocked_reason'] = dict(output)
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
                if status == 401:
                    code, instruction = 'ENGINEERING_AUTHENTICATION_REQUIRED', 'Restore the host connection authentication'
                elif status == 402:
                    code, instruction = 'ENGINEERING_PROVIDER_CREDITS_REQUIRED', 'Restore provider credits or explicitly select an approved alternative'
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
                attempts = job['connection_retries']
                wait = max(recovery_delay, min(300, 5 * 2 ** min(6, attempts - 1)))
                retry_at = self.store.now() + wait
                available = retry_at < job['deadline'] - effective_execution_budget(job['request'])['final_seconds']
                resume_limit = (job['request'].get('batch_preferences') or {}).get('resume_after_transient_limits', True)
                can_resume = available and (job['request'].get('execution_mode') != 'full' or resume_limit)
                output.update(error_code='ENGINEERING_TRANSPORT_RETRY_WAIT', retryable=can_resume,
                              retry_after_seconds=wait, retry_at=retry_at, retry_source='TRANSIENT_TRANSPORT', attempts=attempts)
                if can_resume:
                    job['provider_wait'] = self.retry_checkpoint(job, retry_at, 'TRANSIENT_TRANSPORT', attempts)
                    job['message'] = 'Connection unavailable. The saved step will resume automatically.'
                    delay = wait
                else:
                    phase = 'NEEDS_INPUT'
                    job['data']['blocked_reason'] = dict(output)
                    job['message'] = 'Connection recovery reached the accepted limits. Confirmed work is retained.'
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
        wait = job.get('provider_wait')
        retry_event = None
        if wait and wait != previous_wait:
            retry_event = dict(wait, outcome='SCHEDULED', http_status=output.get('http_status'),
                               observed_at=self.store.now(), boot_hash=digest(self.bindings.boot_id))
        elif previous_wait and not wait:
            outcome = ('NEEDS_INPUT' if phase == 'NEEDS_INPUT' else
                       'RESUMED' if 'error_type' not in output else 'INTERRUPTED')
            retry_event = dict(previous_wait, outcome=outcome, observed_at=self.store.now(),
                               boot_hash=digest(self.bindings.boot_id))
        if retry_event:
            job['data']['latest_retry'] = retry_event
            history = job['data'].get('retry_history', [])
            job['data']['retry_history'] = (history + [retry_event])[-12:]
        confirmed = output and 'error_type' not in output and phase != 'NEEDS_INPUT' and phase != job['state']
        if job['state'] == 'REVIEW' and output.get('review', {}).get('approved') is not True:
            confirmed = False
        if job['state'] == 'CI' and output.get('checks_passed') is not True:
            confirmed = False
        if confirmed:
            job['milestones'] = sorted(set(job.get('milestones', [])) | {job['state']})
            job['data']['last_completed_stage'] = job['state'].lower()
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
            report = build_report(job, phase, self.completion_verification(job, job['data'].get('readiness', {}), merged=phase == 'COMPLETED'),
                                  committed_revision=job['revision'] + 1)
            job['data']['final_report'] = report
        self.store.complete(job, lease, phase, output, delay)

    def start(self):
        if not self.store.runtime.GATEWAY_DURABLE_JOBS:
            return
        self.store.recover()
        self.stop.clear()
        self.copilot.start()
        for graph in (self.graph, self.public_graph):
            if graph is not None:
                graph.start_jobs()
        def work():
            # Stages on independent branches may proceed while another job validates.
            # Publication for one repository/branch is always serialized.
            recovery_pending = False
            pending = {}
            configured = os.getenv('GATEWAY_ENGINEERING_WORKERS', '2')
            workers = max(1, min(4, int(configured))) if configured.isdigit() else 2
            with ThreadPoolExecutor(max_workers=workers, thread_name_prefix='engineering-stage') as pool:
                while not self.stop.is_set():
                    for job_id, (future, _) in list(pending.items()):
                        if future.done():
                            del pending[job_id]
                            try:
                                future.result()
                            except Exception as error:
                                logging.getLogger(__name__).warning('Engineering checkpoint unavailable: %s', type(error).__name__)
                                recovery_pending = True
                    try:
                        # recover() clears leases, so never run it over another
                        # live worker's publication or validation stage.
                        if recovery_pending and not pending:
                            self.store.recover()
                            recovery_pending = False
                        if not recovery_pending and not getattr(self.store.runtime, 'gateway_draining', False):
                            occupied = {key for _, key in pending.values()}
                            for job_id in self.store.due(exclude_branches=occupied):
                                if len(pending) >= workers or self.stop.is_set():
                                    break
                                if job_id in pending:
                                    continue
                                job = self.store.get(job_id)
                                key = (job['request']['repository'].lower(), job['request']['branch'])
                                if key in occupied:
                                    continue
                                pending[job_id] = (pool.submit(self.tick, job_id), key)
                                occupied.add(key)
                    except Exception as error:
                        logging.getLogger(__name__).warning('Engineering scheduling unavailable: %s', type(error).__name__)
                        recovery_pending = True
                    self.wake.wait(.2 if pending else 2)
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
                    await asyncio.to_thread(self.copilot.close)
                    if self.thread:
                        await asyncio.to_thread(self.thread.join, 15)
                    for graph in (self.graph, self.public_graph):
                        jobs = getattr(graph, '_index_jobs', None)
                        if jobs is not None:
                            await asyncio.to_thread(jobs.close)
                    if self.public_graph is not None:
                        self.public_graph.backend.close()
        return lifecycle


def public_job(job):
    data = job['data']
    state = job['state']
    completed = len(job.get('milestones', []))
    wait = job.get('provider_wait') or {}
    active_retry = retry_summary(job, active_only=True) or {}
    readiness = data.get('readiness') or {}
    return {'conversation_id': job.get('conversation_id'), 'gateway_id': job.get('gateway_id'), 'principal_id': job['principal'],
            'command_id': job.get('command_id', job['request']['request_id']), 'accepted_at': job.get('accepted_at', job['created_at']),
            'server_now': time.time(), 'detach_safe': bool(job.get('detach_safe')), 'task_scope': accepted_scope(job['request']),
            'started_at': job.get('started_at'), 'queued_at': job.get('queued_at'), 'title': job.get('title'),
            'provider_retry_at': wait.get('retry_at'),
            'provider_retry_source': active_retry.get('source'), 'provider_retry_attempts': active_retry.get('attempts'),
            'retry': retry_summary(job), 'continuous_progress': continuous_progress(job),
            'execution_mode': job['request'].get('execution_mode', 'standard'),
            'batch_progress': batch_summary(data.get('integration_batch')),
            'cancellation_requested': bool(job.get('cancellation_requested')), 'cancellation_outcome': job.get('cancellation_outcome'),
            'id': job['id'], 'job_id': job['id'], 'repository': job['request']['repository'], 'branch': job['request']['branch'],
            'profile_id': job['request'].get('profile_id'), 'profile_name': job['request'].get('profile_name'), 'model': job['request']['model'],
            'source_branch': job['request'].get('source_branch', job['request']['branch']), 'source_sha': job['request'].get('source_sha'),
            'working_branch': job['request']['branch'], 'base': job['request']['base'],
            'duration_hours': job['request'].get('duration_hours', 3), 'execution_budget': effective_execution_budget(job['request']),
            'chat_id': job['request'].get('chat_id', 0), 'state': state, 'revision': job['revision'],
            'created_at': job['created_at'], 'finished_at': job['updated_at'] if state in {'COMPLETED', 'CANCELLED', 'FAILED'} else None, 'deadline': job['deadline'], 'message': job['message'],
            'milestones_completed': completed, 'milestones_total': 2 if accepted_scope(job['request']) != 'implement' else len(PHASES) - 2,
            'repair_attempts': sum(job['repairs'].values()), 'pr': data.get('pr'), 'pr_url': data.get('pr_url'),
            'head_sha': data.get('head_sha'), 'changed_files': readiness.get('changed_files', data.get('changed_paths', [change['path'] for change in data.get('changes', [])])),
            'tests': readiness.get('checks', []), 'checks_passed': readiness.get('checks_passed', False),
            'merge_ready': state == 'MERGE_READY' and bool(EngineeringSupervisor.readiness(readiness)),
            'approval_expires_at': (job.get('approval') or {}).get('expires_at'),
            'merge_sha': data.get('merge_sha'), 'review': data.get('review', {}),
            'post_merge': data.get('post_merge'), 'recovery_of': job.get('recovery_of'),
            'recovery_job_id': data.get('post_merge_recovery_job_id'),
            'qualified_revision': (data.get('post_merge') or {}).get('qualified_revision'),
            'acceptance': job['request'].get('acceptance', []), 'admission': data.get('admission'),
            'execution_owner': 'GATEWAY' if job.get('detach_safe') else 'NOT_CONFIRMED',
            'completion_verification': data.get('completion_verification'), 'evidence_policy': 'github.evidence.v1',
            'workspace_plan': str((data.get('plan') or {}).get('plan', ''))[:8000] if isinstance(data.get('plan'), dict) else '',
            'active_instruction_files': data.get('active_instructions', []),
            'workspace_next_action': (data.get('progress_notes') or {}).get('next_action'),
            'workspace_outputs': [dict(item, revision=(data.get('local_validation') or {}).get('source_sha'),
                                       patch_identity=(data.get('local_validation') or {}).get('patch_identity'), command_index=cmd.get('command_index'))
                                  for cmd in (data.get('local_validation') or {}).get('commands', []) for item in cmd.get('build_outputs', [])][:96],
            'workspace_workers': [{'id': name, 'role': value['role'], 'state': value['state'],
                                   'calls': value['calls'], 'budget': value['budget']} for name, value in (data.get('workspace_workers') or {}).items()],
            'workspace_tests': [{'name': str(value.get('check_id', ''))[:12], 'status': value.get('status'),
                                 'revision': value.get('source_sha'), 'exit_code': value.get('exit_code')} for value in (data.get('workspace_checks') or {}).values()],
            'workspace_steps': [{'name': step, 'done': i < len((data.get('workflow') or {}).get('completed', []))}
                                for i, step in enumerate((data.get('workflow') or {}).get('steps', []))],
            'latest_evidence': data.get('latest_evidence'),
            'execution_binding_id': job['request'].get('execution_binding_id'),
            'execution_binding': job.get('execution_binding'),
            'latest_execution_receipt': data.get('latest_execution_receipt'),
            'execution_budget_receipt': data.get('execution_budget_receipt'),
            'graph_retrieval': public_graph_receipt(data.get('graph_retrieval')),
            'blocked_reason': data.get('blocked_reason'), 'latest_operation': data.get('latest_operation'),
            'final_report': data.get('final_report')}
