"""Optional agent execution owned by the existing EngineeringService lease.

There is no worker, timer, network-on-read or second scheduler here. The parent
controller invokes ``act``/``advance`` under its current lease. External writes
have durable dispatch fences in the same SQLite journal as the parent job.
Unknown outcomes are observed, never resubmitted automatically.
"""
from functools import wraps
import json
import re

from v14 import agent_config
from v14.agent_config import AgentError, CATALOG, SCHEMA, canonical, credential, digest
from v14.agent_cloud import CloudAgent, identifier, normalize_state
from v14.agent_local import LocalAgent
from v14.engineering_scope import accepted_scope

TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'RUNTIME_LOST', 'BLOCKED', 'START_FAILED', 'REMOTE_CONTINUES'}
SAFE_STATES = TERMINAL | {'PREPARED', 'QUEUED', 'RUNNING', 'WAITING_FOR_INPUT', 'STATE_UNVERIFIED', 'OUTCOME_UNKNOWN', 'DISCONNECTED', 'CANCELLING'}
QUALIFICATION_SECONDS = 600


def credential_bound(operation):
    @wraps(operation)
    def invoke(*args, **kwargs):
        with agent_config.credential_scope():
            return operation(*args, **kwargs)
    return invoke


class AgentRuns:
    def __init__(self, service, *, config_loader=None, transport_factory=None):
        self.service, self.store, self.backend = service, service.store, service.backend
        self.config_loader = config_loader or agent_config.load
        self.transport_factory = transport_factory

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_agent_runs ('
                   'run_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, principal TEXT NOT NULL, request_id TEXT NOT NULL, '
                   'request_digest TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(job_id,principal,request_id))')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_agent_operations ('
                   'operation_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, job_id TEXT NOT NULL, principal TEXT NOT NULL, '
                   'request_digest TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_agent_qualifications ('
                   'identity TEXT PRIMARY KEY, principal TEXT NOT NULL, repository TEXT NOT NULL, backend TEXT NOT NULL, payload TEXT NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_agent_experiments ('
                   'experiment_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, principal TEXT NOT NULL, request_digest TEXT NOT NULL, payload TEXT NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_agent_stops ('
                   'run_id TEXT PRIMARY KEY, job_id TEXT NOT NULL, principal TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL)')

    def tx(self, operation):
        def execute(db):
            self.schema(db)
            return operation(db)
        return self.store.tx(execute)

    @staticmethod
    def owner(db, job, *, source=True):
        row = db.execute('SELECT state,revision,lease,data FROM engineering_jobs WHERE id=? AND principal=?',
                         (job['id'], job['principal'])).fetchone()
        epoch = db.execute('SELECT epoch FROM engineering_owner_epochs WHERE job_id=?', (job['id'],)).fetchone()
        if (not row or tuple(row[:3]) != (job['state'], job['revision'], job.get('_execution_lease'))
                or row[2] is None or not epoch or epoch[0] != job.get('_owner_epoch')):
            raise AgentError('AGENT_EXECUTION_OWNER_CHANGED', 'The parent job lease changed; no agent operation was dispatched.')
        stored = json.loads(row[3])
        if source and stored.get('data', {}).get('head_sha') != job['data'].get('head_sha'):
            raise AgentError('AGENT_SOURCE_CHANGED', 'The parent source changed before agent dispatch.')
        return stored

    def rows(self, job):
        return self.tx(lambda db: [json.loads(v[0]) for v in db.execute(
            'SELECT payload FROM engineering_agent_runs WHERE job_id=? AND principal=? ORDER BY rowid LIMIT 32',
            (job['id'], job['principal']))])

    def get(self, job, run_id):
        if not isinstance(run_id, str) or not re.fullmatch(r'ar_[a-f0-9]{40}', run_id):
            raise AgentError('AGENT_RUN_INVALID', 'Select a retained agent run from this job.')
        row = self.tx(lambda db: db.execute('SELECT payload FROM engineering_agent_runs WHERE run_id=? AND job_id=? AND principal=?',
                                          (run_id, job['id'], job['principal'])).fetchone())
        if not row:
            raise AgentError('AGENT_RUN_UNAVAILABLE', 'The agent run is unavailable in this job scope.')
        return json.loads(row[0])

    @staticmethod
    def public(run):
        fields = ('run_id', 'backend', 'session_id', 'remote_run_id', 'source_sha', 'state', 'model_id', 'capabilities',
                  'created_at', 'updated_at', 'deadline', 'operation_state', 'candidate_status', 'candidate_check_id',
                  'experiment_id', 'error_code', 'events_ref', 'artifacts_ref', 'usage', 'detached', 'last_operation_id',
                  'cleanup_state', 'steer_delivery', 'observed_latest_remote_run_id', 'candidate_attribution', 'copilot_task')
        result = dict({key: run[key] for key in fields if key in run}, comparison_scope='AGENT_SYSTEM',
                    execution_location='PROVIDER_CLOUD' if CATALOG[run['backend']][1] in {'https', 'copilot'} else 'ISOLATED_CONTAINER',
                    automatic_merge=False, candidate_validation='NOT_VALIDATED_BY_AGENT', source_binding=run.get('source_binding'))
        result['capabilities'] = list(result.get('capabilities', []))
        if CATALOG[run['backend']][1] not in {'https', 'copilot', 'codex'} and run['state'] not in {'COMPLETED', 'WAITING_FOR_INPUT'}:
            result['capabilities'] = [value for value in result['capabilities'] if value != 'steer']
        return result

    def list(self, job):
        """Only journal observation; safe for a workspace GET projection."""
        return [self.public(run) for run in self.rows(job)]

    snapshot = list

    @staticmethod
    def privacy(job):
        binding = job.get('execution_binding') or {}
        return 'LOCAL_ONLY' not in binding.get('required_capability_policy', [])

    def configuration(self, job, backend, saved=None):
        document, revision = self.config_loader()
        config = agent_config.selected(document, job, backend)
        config_identity = digest({'backend': backend, 'configuration': config})
        references = dict(config.get('environment_references', {}))
        if config.get('credential_reference'):
            references['credential'] = config['credential_reference']
        resolved = {key: credential(ref) for key, ref in references.items()}
        credentials = digest({key: digest(value) for key, value in resolved.items()})
        if saved and (saved['config_digest'] != config_identity or saved['credential_digest'] != credentials):
            raise AgentError('AGENT_CONFIGURATION_CHANGED', 'The runtime configuration or account changed; restore the original account to observe this run.')
        return config, config_identity, credentials, revision

    def qualification_key(self, job, backend, config_identity, credentials):
        return digest([job['principal'], job['request']['repository'].lower(), backend, config_identity, credentials])

    def qualification(self, identity):
        row = self.tx(lambda db: db.execute('SELECT payload FROM engineering_agent_qualifications WHERE identity=?', (identity,)).fetchone())
        return json.loads(row[0]) if row else None

    @staticmethod
    def args_schema(capabilities):
        common = {'head_sha': {'type': 'string', 'pattern': '^[a-f0-9]{40}$'}}
        fields = {
            'qualify': {'backend': {'type': 'string'}},
            'start': {'backend': {'type': 'string'}, 'model_id': {'type': 'string'}, 'objective': {'type': 'string'},
                      'consent': {'const': True}, 'request_id': {'type': 'string'}},
            'status': {'run_id': {'type': 'string'}}, 'events': {'run_id': {'type': 'string'}},
            'artifacts': {'run_id': {'type': 'string'}}, 'import': {'run_id': {'type': 'string'}},
            'steer': {'run_id': {'type': 'string'}, 'message': {'type': 'string', 'maxLength': 16000},
                      'request_id': {'type': 'string'}, 'expected_remote_run_id': {'type': 'string'}},
            'cancel': {'run_id': {'type': 'string'}, 'request_id': {'type': 'string'}},
            'approve_plan': {'run_id': {'type': 'string'}, 'request_id': {'type': 'string'}}}
        return {'agent_' + key: {'type': 'object', 'properties': dict(common, request_id={'type': 'string'}, **{k: v for k, v in fields[key].items() if k != 'request_id'}),
                                 'required': list(common) + list(fields[key]), 'additionalProperties': False}
                for key in ['qualify', *capabilities] if key in fields}

    def capabilities(self, job):
        """Configuration + retained qualification only. Never contacts providers."""
        rows = []
        for backend, (label, transport, supported) in CATALOG.items():
            row = dict(id=backend, backend_id=backend, name=label, label=label, configured=False, ready=False,
                       state='SETUP_NEEDED', reason='Configure and qualify this runtime on the Gateway.',
                       model_id=None, capabilities=[], supported_capabilities=list(supported),
                       execution_location='PROVIDER_CLOUD' if transport in {'https', 'copilot'} else 'ISOLATED_CONTAINER',
                       requires_remote_consent=True, source_binding='UNQUALIFIED', qualification_scope='UNQUALIFIED')
            try:
                config, identity, credentials, _ = self.configuration(job, backend)
                row.update(configured=True, model_id=config['model_id'])
                qualified = self.qualification(self.qualification_key(job, backend, identity, credentials))
                if not self.privacy(job):
                    row.update(state='PRIVACY_BLOCKED', reason='This job requires local-only inference; external agent delegation is unavailable.')
                elif qualified and qualified.get('state') == 'READY' and qualified['checked_at'] + QUALIFICATION_SECONDS > self.store.now():
                    row.update(ready=True, state='READY', reason=None, capabilities=list(supported),
                               source_binding=qualified.get('source_binding'),
                               qualification_scope=qualified.get('scope', 'RUNTIME_ONLY'), checked_at=qualified['checked_at'],
                               model_selection=qualified.get('model_selection'), cost_limit_supported=qualified.get('cost_limit_supported', False),
                               runtime_version=qualified.get('runtime_version'), sdk_versions=qualified.get('sdk_versions', {}))
                elif qualified:
                    row.update(state='QUALIFICATION_EXPIRED' if qualified.get('state') == 'READY' else 'SETUP_NEEDED',
                               reason=qualified.get('reason') or 'Refresh runtime qualification before starting.', checked_at=qualified['checked_at'])
                else:
                    row.update(state='QUALIFICATION_REQUIRED', reason='Qualify the configured runtime before starting a task.')
                row['args_schema'] = self.args_schema(supported if row['ready'] else [])
            except (AgentError, ValueError, OSError, TypeError):
                row['args_schema'] = {}
            rows.append(row)
        return rows

    def transport(self, backend, config, job=None, run=None):
        if self.transport_factory:
            return self.transport_factory(backend, config)
        if CATALOG[backend][1] == 'https':
            cancel = self.backend.cancellation(job) if job is not None and hasattr(self.backend, 'cancellation') else None
            deadline = min(job['deadline'], (run or {}).get('deadline', job['deadline'])) if job is not None else None
            return CloudAgent(backend, config, cancel=cancel, deadline=deadline)
        return LocalAgent(backend, config)

    @credential_bound
    def qualify(self, job, backend):
        if not self.privacy(job):
            raise AgentError('AGENT_PRIVACY_BLOCKED', 'Local-only jobs cannot delegate to an external agent.')
        config, identity, credentials, _ = self.configuration(job, backend)
        key = self.qualification_key(job, backend, identity, credentials)
        self.store.assert_claim(job)
        try:
            if backend == 'github_copilot':
                result = self.service.copilot.capabilities(job['principal'])
                if not result.get('configured'):
                    raise AgentError('AGENT_COPILOT_UNAVAILABLE', 'Enable the existing Copilot cloud service and qualify its host account.')
                result = {'state': 'READY', 'scope': 'ACCOUNT', 'actor_id': result['actor_id'], 'source_binding': 'BRANCH_PREFLIGHT',
                          'model_selection': 'REQUESTED_NOT_ATTESTED', 'cost_limit_supported': False}
            else:
                result = self.transport(backend, config, job).qualify(job['request']['repository'])
                result['scope'] = 'RUNTIME_ONLY' if CATALOG[backend][1] != 'https' else 'ACCOUNT'
        except (AgentError, ValueError, OSError) as error:
            result = {'state': 'SETUP_NEEDED', 'error_code': getattr(error, 'code', 'AGENT_QUALIFICATION_FAILED'),
                      'reason': 'The configured runtime or account could not be qualified. Check its host setup.'}
        result.update(checked_at=self.store.now(), backend=backend, config_digest=identity, credential_digest=credentials)
        def save(db):
            self.owner(db, job)
            db.execute('INSERT INTO engineering_agent_qualifications VALUES(?,?,?,?,?) ON CONFLICT(identity) DO UPDATE SET payload=excluded.payload',
                       (key, job['principal'], job['request']['repository'].lower(), backend, canonical(result)))
        self.tx(save)
        return result

    def save(self, job, run, **values):
        run.update(values, updated_at=self.store.now())
        raw = canonical(run)
        if len(raw.encode()) > 32768:
            raise AgentError('AGENT_RETENTION_LIMIT', 'Agent metadata reached its bounded retention limit.')
        def update(db):
            self.owner(db, job)
            if db.execute('UPDATE engineering_agent_runs SET payload=? WHERE run_id=? AND job_id=? AND principal=?',
                          (raw, run['run_id'], job['id'], job['principal'])).rowcount != 1:
                raise AgentError('AGENT_RUN_UNAVAILABLE', 'The retained agent run is unavailable.')
        self.tx(update)
        return run

    def put(self, job, value, purpose):
        value = self.redact(job, value)
        def save(db):
            self.owner(db, job)
            return self.service.artifacts.put_json(job, value, purpose, transaction=db)
        return self.tx(save)

    def secrets(self, job):
        # The response may arrive after settings/credentials rotate. Keep the
        # exact values used by this call alongside currently configured values.
        values = set(agent_config.observed_credentials())
        try:
            document, _ = self.config_loader()
            for backend in document['agents']:
                try:
                    config = agent_config.selected(document, job, backend)
                    references = list(config.get('environment_references', {}).values())
                    if config.get('credential_reference'):
                        references.append(config['credential_reference'])
                    values.update(credential(reference) for reference in references)
                except (AgentError, ValueError, OSError):
                    continue
        except (AgentError, ValueError, OSError):
            pass
        return sorted((value for value in values if isinstance(value, str) and value), key=len, reverse=True)

    def redact(self, job, value):
        """Provider/SDK output may echo auth errors or tool environments."""
        secrets = self.secrets(job)
        def visit(item):
            if isinstance(item, str):
                for secret in secrets:
                    item = item.replace(secret, '[REDACTED_AGENT_CREDENTIAL]')
                return item
            if isinstance(item, list):
                return [visit(child) for child in item]
            if isinstance(item, dict):
                return {visit(key): visit(child) for key, child in item.items()}
            return item
        return visit(value)

    def protect_candidate(self, job, value):
        changes = value.get('changes', []) if isinstance(value, dict) else []
        if any(secret in (change.get('content') or '') for secret in self.secrets(job) for change in changes if isinstance(change, dict)):
            raise AgentError('AGENT_CREDENTIAL_IN_CANDIDATE', 'The agent output contains a configured credential; it cannot be retained as a code candidate.')
        return value

    def operation(self, job, run, kind, request_id, payload):
        if not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', request_id):
            raise AgentError('AGENT_REQUEST_ID_REQUIRED', 'Use a stable command request identifier.')
        operation_id = 'ao_' + digest([job['principal'], job['id'], run['run_id'], request_id])[:40]
        request_digest = digest([kind, payload])
        def prepare(db):
            self.owner(db, job)
            existing = db.execute('SELECT request_digest,state,payload FROM engineering_agent_operations WHERE operation_id=?', (operation_id,)).fetchone()
            if existing:
                if existing[0] != request_digest:
                    raise AgentError('AGENT_REQUEST_CONFLICT', 'This command identity already belongs to a different agent operation.')
                record = json.loads(existing[2])
                if record.get('state') != existing[1]:
                    # Older parent-cancel fences wrote only the state column.
                    # A stale PREPARED projection cannot authorize a replay.
                    record['state'] = existing[1]
                    db.execute('UPDATE engineering_agent_operations SET payload=? WHERE operation_id=?',
                               (canonical(record), operation_id))
                return record
            count = db.execute('SELECT COUNT(*) FROM engineering_agent_operations WHERE run_id=?', (run['run_id'],)).fetchone()[0]
            if count >= 64:
                raise AgentError('AGENT_OPERATION_LIMIT', 'This agent run reached its retained command allowance.')
            reference = self.service.artifacts.put_json(job, payload, 'agent-command', transaction=db)
            record = dict(operation_id=operation_id, kind=kind, state='PREPARED', request_digest=request_digest,
                          input_ref=reference, created_at=self.store.now(), source_sha=run['source_sha'])
            db.execute('INSERT INTO engineering_agent_operations VALUES(?,?,?,?,?,?,?)',
                       (operation_id, run['run_id'], job['id'], job['principal'], request_digest, 'PREPARED', canonical(record)))
            return record
        return self.tx(prepare)

    def dispatch(self, job, run, operation):
        # Read credentials again at the last boundary; config changes never
        # silently redirect an operation to another account or runtime.
        self.configuration(job, run['backend'], run)
        self.service.validate_execution(job, force=True)
        if not self.privacy(job):
            raise AgentError('AGENT_PRIVACY_BLOCKED', 'Local-only execution does not authorize this delegation.')
        if self.store.now() >= min(run['deadline'], job['deadline']):
            raise AgentError('AGENT_DEADLINE', 'The agent execution deadline elapsed before dispatch.')
        updates = {'operation_state': 'DISPATCHED', 'last_operation_id': operation['operation_id']}
        if operation['kind'] == 'steer':
            previous = {key: run.get(key) for key in ('candidate_raw_ref', 'candidate_check_id', 'candidate_capture_binding', 'remote_run_id')}
            updates.update(candidate_raw_ref=None, candidate_check_id=None, candidate_capture_binding=None,
                           candidate_status='NOT_CAPTURED', candidate_history=(run.get('candidate_history', []) + [previous])[-8:])
            if run['backend'] == 'cursor':
                updates.update(steer_delivery='UNCONFIRMED', candidate_attribution='UNVERIFIED_FOLLOWUP')
        def mark(db):
            self.owner(db, job)
            row = db.execute('SELECT state,payload FROM engineering_agent_operations WHERE operation_id=? AND run_id=?',
                             (operation['operation_id'], run['run_id'])).fetchone()
            if not row or row[0] != 'PREPARED':
                raise AgentError('AGENT_OPERATION_ALREADY_DISPATCHED', 'This operation already crossed its dispatch fence; observe its result before retrying.')
            current = json.loads(row[1]); current.update(state='DISPATCHED', dispatched_at=self.store.now())
            db.execute('UPDATE engineering_agent_operations SET state=?,payload=? WHERE operation_id=?',
                       ('DISPATCHED', canonical(current), operation['operation_id']))
            retained = dict(run, **updates)
            db.execute('UPDATE engineering_agent_runs SET payload=? WHERE run_id=? AND job_id=? AND principal=?',
                       (canonical(retained), run['run_id'], job['id'], job['principal']))
        self.tx(mark)
        operation['state'] = 'DISPATCHED'
        run.update(updates)

    def complete_operation(self, job, run, operation, result=None, error=None):
        result = self.redact(job, result)
        def complete(db):
            self.owner(db, job)
            saved = db.execute('SELECT state,payload FROM engineering_agent_operations WHERE operation_id=?', (operation['operation_id'],)).fetchone()
            if not saved:
                raise AgentError('AGENT_OPERATION_UNAVAILABLE', 'The operation journal is unavailable.')
            retained = json.loads(saved[1])
            dispatched = (any(value in {'DISPATCHED', 'OUTCOME_UNKNOWN', 'CONFIRMED'}
                              for value in (saved[0], retained.get('state'), operation.get('state')))
                          or retained.get('dispatched_at') is not None)
            state = 'CONFIRMED' if error is None else ('OUTCOME_UNKNOWN' if dispatched else 'NOT_DISPATCHED')
            record = dict(retained, state=state, finished_at=self.store.now())
            if result is not None:
                record['result_ref'] = self.service.artifacts.put_json(job, result, 'agent-operation-result', transaction=db)
            if error is not None:
                record['error_code'] = getattr(error, 'code', 'AGENT_OPERATION_UNCONFIRMED')
            db.execute('UPDATE engineering_agent_operations SET state=?,payload=? WHERE operation_id=?',
                       (state, canonical(record), operation['operation_id']))
            return record
        record = self.tx(complete)
        self.save(job, run, operation_state=record['state'], last_operation_id=operation['operation_id'],
                  **({'error_code': record.get('error_code')} if error is not None else {}))
        return record

    def source_branch(self, job):
        request, source = job['request'], job['data']['head_sha']
        branch = request['branch']
        current = self.backend.ref(job, branch)
        if current is not None:
            if current != source:
                raise AgentError('AGENT_SOURCE_CHANGED', 'New commits arrived on the working branch; refresh this job before delegation.')
            return branch
        branch = request.get('source_branch', request['base'])
        if self.backend.ref(job, branch) != source:
            raise AgentError('AGENT_SOURCE_CHANGED', 'The source branch moved before delegation.')
        return branch

    @credential_bound
    def start(self, job, action, *, experiment_id=None):
        if experiment_id is None:
            self.user_command(job, action)
        else:
            admitted = self.tx(lambda db: db.execute('SELECT payload FROM engineering_agent_experiments WHERE experiment_id=? AND job_id=? AND principal=?',
                                                    (experiment_id, job['id'], job['principal'])).fetchone())
            if not admitted or json.loads(admitted[0]).get('user_authorized') is not True:
                raise AgentError('AGENT_USER_COMMAND_REQUIRED', 'Comparison children require a retained user-authorized experiment.')
        backend = action.get('backend')
        if backend not in CATALOG:
            raise AgentError('AGENT_BACKEND_INVALID', 'Choose a supported configured runtime.')
        if (action.get('consent') is not True or not self.privacy(job)
                or accepted_scope(job['request']) != 'implement'):
            raise AgentError('AGENT_CONSENT_REQUIRED', 'This implementation job requires explicit remote-agent consent and an eligible privacy policy.')
        if action.get('objective') != job['request']['objective']:
            raise AgentError('AGENT_OBJECTIVE_CHANGED', 'Delegate the exact accepted objective; use steering for a separately recorded follow-up.')
        if any(secret in action['objective'] for secret in self.secrets(job)):
            raise AgentError('AGENT_CREDENTIAL_IN_PROMPT', 'Remove configured agent credentials from the delegated task text.')
        request_id = action.get('request_id')
        if not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', request_id):
            raise AgentError('AGENT_REQUEST_ID_REQUIRED', 'Use a stable start request identifier.')
        config, identity, credentials, revision = self.configuration(job, backend)
        if action.get('model_id') != config['model_id']:
            raise AgentError('AGENT_MODEL_CHANGED', 'Select the exact model configured for this runtime.')
        request_digest = digest([backend, action['objective'], action['head_sha'], action['model_id'], identity, experiment_id])
        run_id = 'ar_' + digest([job['principal'], job['id'], request_id])[:40]
        existing = self.tx(lambda db: db.execute('SELECT request_digest,payload FROM engineering_agent_runs WHERE run_id=?', (run_id,)).fetchone())
        if existing:
            if existing[0] != request_digest:
                raise AgentError('AGENT_REQUEST_CONFLICT', 'This request identity already belongs to a different delegated task.')
            # A start retry is a read only reconciliation, never another POST.
            retained = json.loads(existing[1])
            return self.public(retained) if retained['state'] == 'START_FAILED' else self.observe(job, retained)
        self.service.validate_execution(job, force=True)
        qualification = self.qualification(self.qualification_key(job, backend, identity, credentials))
        if not qualification or qualification.get('state') != 'READY':
            raise AgentError('AGENT_QUALIFICATION_REQUIRED', 'Qualify the runtime and account before delegation.')
        if qualification['checked_at'] + QUALIFICATION_SECONDS <= self.store.now():
            qualification = self.qualify(job, backend)
            if qualification['state'] != 'READY':
                raise AgentError('AGENT_QUALIFICATION_REQUIRED', 'The runtime no longer passes its setup qualification.')
        source_branch = self.source_branch(job)
        now = self.store.now()
        run = dict(run_id=run_id, job_id=job['id'], principal=job['principal'], backend=backend,
                   request_id=request_id, source_sha=action['head_sha'], source_branch=source_branch,
                   repository=job['request']['repository'], model_id=config['model_id'],
                   config_digest=identity, config_revision=revision, credential_digest=credentials,
                   policy_sha256=job['data'].get('admission', {}).get('policy_sha256'),
                   task_digest=digest(action['objective']), capabilities=list(CATALOG[backend][2]),
                   source_binding=qualification.get('source_binding'), state='PREPARED', operation_state='PREPARED',
                   created_at=now, updated_at=now, next_at=now + 10,
                   deadline=min(job['deadline'], now + config.get('max_seconds', 1800)),
                   candidate_status='NOT_CAPTURED', usage={'cost_usd': None, 'input_tokens': None, 'output_tokens': None},
                   experiment_id=experiment_id, holding=True, correlation='gateway-' + run_id,
                   limits={key: config.get(key, default) for key, default in [('max_seconds', 1800), ('max_iterations', 24), ('max_budget_usd', 2.0)]})
        def admit(db):
            self.owner(db, job)
            count = db.execute('SELECT COUNT(*) FROM engineering_agent_runs WHERE job_id=? AND principal=?', (job['id'], job['principal'])).fetchone()[0]
            own = db.execute("SELECT COUNT(*) FROM engineering_agent_runs WHERE job_id=? AND principal=? AND json_extract(payload,'$.backend')=?", (job['id'], job['principal'], backend)).fetchone()[0]
            if count >= 24 or own >= config.get('max_runs', 3):
                raise AgentError('AGENT_RUN_LIMIT', 'The job reached its delegated agent run allowance.')
            run['objective_ref'] = self.service.artifacts.put_json(job, {'objective': action['objective']}, 'agent-objective', transaction=db)
            db.execute('INSERT INTO engineering_agent_runs VALUES(?,?,?,?,?,?)',
                       (run_id, job['id'], job['principal'], request_id, request_digest, canonical(run)))
        self.tx(admit)
        operation = self.operation(job, run, 'start', request_id, dict(action, experiment_id=experiment_id))
        transport = None
        try:
            if backend == 'github_copilot':
                # Reuse the existing durable cloud task executor. This adapter
                # owns no extra Copilot poller or remote-task creation code.
                actor = self.backend.identity(job)
                if actor['id'] != qualification.get('actor_id'):
                    raise AgentError('AGENT_ACCOUNT_CHANGED', 'The qualified GitHub account changed before delegation.')
                self.dispatch(job, run, operation)
                submitted = self.service.copilot.submit(job['principal'], {
                    'request_id': run_id, 'repository': run['repository'], 'prompt': action['objective'],
                    'base_ref': source_branch, 'expected_base_sha': run['source_sha'], 'expected_actor_id': actor['id'],
                    'model': run['model_id'], 'create_pull_request': False, 'execution_owner': 'GATEWAY',
                    'copilot_enabled': True, 'conversation_copilot_enabled': True})
                result = {'state': 'QUEUED', 'session_id': submitted['id'], 'response': submitted}
            else:
                transport = self.transport(backend, config, job, run)
                if CATALOG[backend][1] == 'https':
                    result = transport.start(dict(run, objective=action['objective']), qualification, lambda: self.dispatch(job, run, operation))
                else:
                    self.save(job, run, **LocalAgent.intent(run))
                    prepared = transport.prepare(self.backend, job, run, check_owner=lambda: self.store.assert_claim(job))
                    self.save(job, run, **prepared)
                    result = transport.start(run, action['objective'], operation['operation_id'], lambda: self.dispatch(job, run, operation))
                    self.store.assert_claim(job)
            self.complete_operation(job, run, operation, result)
            self.observed(job, run, result)
        except Exception as error:
            # Also cover lease revocation between the last transport check and
            # operation-result retention; a concurrent cleanup may have seen an
            # absent container immediately before this worker created it.
            if transport is not None and CATALOG[backend][1] != 'https':
                try:
                    self.store.assert_claim(job)
                except Exception:
                    transport.dispose(run)
            self.complete_operation(job, run, operation, error=error)
            self.save(job, run, state='OUTCOME_UNKNOWN' if operation['state'] == 'DISPATCHED' else 'START_FAILED')
            if operation['state'] != 'DISPATCHED':
                raise AgentError(getattr(error, 'code', 'AGENT_START_FAILED'), 'The agent did not start. Its typed setup failure was retained.') from None
        return self.public(run)

    def observed(self, job, run, result):
        state = result.get('state', run['state'])
        if state not in SAFE_STATES:
            state = 'STATE_UNVERIFIED'
        values = {'state': state, 'next_at': self.store.now() + 15, 'error_code': result.get('error_code')}
        for key in ('session_id', 'remote_run_id'):
            if result.get(key) is not None:
                values[key] = identifier(result[key])
        if run['backend'] == 'github_copilot':
            response = result.get('response')
            session = values.get('session_id', run.get('session_id'))
            if (isinstance(response, dict) and response.get('schema') == 'github.copilot-tasks.v1'
                    and response.get('gateway_job_id') == session and response.get('request_id') == run['run_id']
                    and response.get('principal_id') == job['principal']
                    and response.get('repository', '').lower() == job['request']['repository'].lower()
                    and response.get('execution_owner') == 'GITHUB_CLOUD' and response.get('orchestration_owner') == 'GATEWAY'):
                # Forward the existing supervisor's bounded observation. Agent
                # admission/QUEUED alone does not prove GitHub accepted the task.
                values['copilot_task'] = {key: response[key] for key in (
                    'schema', 'gateway_job_id', 'task_id', 'state', 'remote_state', 'progress', 'task_name',
                    'pull_request_numbers', 'last_checked_at', 'ci_verified', 'merge_authorized') if key in response}
        values['status_ref'] = self.put(job, result, 'agent-status')
        if state == 'COMPLETED':
            values['candidate_status'] = run.get('candidate_status', 'NOT_CAPTURED')
        return self.save(job, run, **values)

    @credential_bound
    def observe(self, job, run):
        config, _, _, _ = self.configuration(job, run['backend'], run)
        capture_local = False
        if run['backend'] == 'github_copilot':
            if not run.get('session_id'):
                tasks = self.service.copilot.list(job['principal'], request_id=run['run_id'])['tasks']
                if len(tasks) != 1:
                    return self.public(run)
                self.save(job, run, session_id=tasks[0]['id'])
            status = self.service.copilot.status(job['principal'], run['session_id'])
            state = {'ACCEPTED': 'QUEUED', 'SUBMITTING': 'QUEUED', 'DISPATCHING': 'QUEUED',
                     'IN_PROGRESS': 'RUNNING', 'IDLE': 'WAITING_FOR_INPUT', 'WAITING_FOR_USER': 'WAITING_FOR_INPUT',
                     'RECONCILE_REQUIRED': 'OUTCOME_UNKNOWN', 'UNKNOWN': 'STATE_UNVERIFIED',
                     'REJECTED': 'FAILED'}.get(status['state'], status['state'])
            result = {'state': state, 'response': status, 'remote_run_id': status.get('upstream_task_id')}
        else:
            transport = self.transport(run['backend'], config, job, run)
            if not run.get('session_id') and CATALOG[run['backend']][1] == 'https':
                result = self.reconcile_cloud(job, run, transport)
                if result is None:
                    self.save(job, run, state='OUTCOME_UNKNOWN', next_at=self.store.now() + 30)
                    return self.public(run)
            elif not run.get('container') and CATALOG[run['backend']][1] != 'https':
                # Preparation may have happened before a crash. Its name is
                # deterministic, but creating/replaying is never a read.
                located = transport.locate(run)
                if not located:
                    self.save(job, run, state='RUNTIME_LOST', error_code='AGENT_RUNTIME_LOST')
                    return self.public(run)
                self.save(job, run, container=located['container'], container_identity=located['container_identity'])
                result = transport.status(run)
            else:
                try:
                    result = transport.status(run)
                except AgentError as error:
                    if error.code == 'AGENT_RUNTIME_LOST':
                        self.save(job, run, state='RUNTIME_LOST', error_code=error.code)
                        return self.public(run)
                    raise
            capture_local = (CATALOG[run['backend']][1] != 'https' and result.get('state') == 'COMPLETED'
                             and (not run.get('candidate_raw_ref') or run.get('candidate_capture_binding') !=
                                  digest([result.get('session_id'), result.get('remote_run_id'), run.get('last_operation_id')])))
            if run['backend'] == 'cursor' and run.get('steer_delivery') in {'queued', 'steered', 'UNCONFIRMED'}:
                latest = transport.api('GET', '/v1/agents/' + identifier(run['session_id']))
                if latest.get('id') != run['session_id']:
                    raise AgentError('AGENT_RESPONSE_IDENTITY_MISMATCH', 'Cursor returned a different agent identity.')
                latest_id = identifier(latest.get('latestRunId'))
                if latest_id != run.get('remote_run_id') or run['steer_delivery'] == 'queued' or run.get('candidate_attribution') == 'UNVERIFIED_FOLLOWUP':
                    # The 202 steering API has no request-correlated next-run
                    # identity or compare-and-set guard. Preserve that gap;
                    # never attribute another writer's follow-up to this task.
                    self.save(job, run, observed_latest_remote_run_id=latest_id, candidate_attribution='UNVERIFIED_FOLLOWUP')
                    result.update(state='WAITING_FOR_INPUT', error_code='AGENT_FOLLOWUP_ATTRIBUTION_REQUIRED')
        self.observed(job, run, result)
        if capture_local:
            try:
                captured = self.local_candidate(job, run, transport.artifacts(run))
                self.save(job, run, candidate_raw_ref=self.put(job, captured, 'agent-candidate-capture'), candidate_status='CAPTURED_UNVALIDATED',
                          candidate_capture_binding=digest([run.get('session_id'), run.get('remote_run_id'), run.get('last_operation_id')]))
            except (AgentError, ValueError, OSError) as error:
                self.save(job, run, candidate_status='CAPTURE_BLOCKED', error_code=getattr(error, 'code', 'AGENT_CANDIDATE_UNAVAILABLE'))
        # Observations do not claim an uncertain steer was accepted. Only the
        # isolated control ledger can reconcile that exact operation identity.
        if run.get('operation_state') in {'DISPATCHED', 'OUTCOME_UNKNOWN'} and run.get('last_operation_id') and CATALOG[run['backend']][1] not in {'https', 'copilot'}:
            transport = self.transport(run['backend'], config, job, run)
            observed = transport.operation(run, run['last_operation_id'])
            if observed.get('state') == 'COMPLETED':
                record = self.tx(lambda db: db.execute('SELECT payload FROM engineering_agent_operations WHERE operation_id=?', (run['last_operation_id'],)).fetchone())
                if record:
                    self.complete_operation(job, run, json.loads(record[0]), observed.get('result'))
        return self.public(run)

    def reconcile_cloud(self, job, run, transport):
        """Bounded read-only correlation; absence never licenses a second POST."""
        matches, page, seen = [], None, set()
        for _ in range(10):
            if run['backend'] == 'jules':
                response = transport.api('GET', '/v1alpha/sessions', params={'pageSize': 100, **({'pageToken': page} if page else {})})
                rows, following = response.get('sessions', []), response.get('nextPageToken')
                if not isinstance(rows, list) or len(rows) > 100 or any(not isinstance(row, dict) for row in rows):
                    raise AgentError('AGENT_RECONCILE_INVALID', 'The provider reconciliation page was invalid.')
                for row in rows:
                    context = row.get('sourceContext', {})
                    if (row.get('title') == run['correlation'] and context.get('githubRepoContext', {}).get('startingBranch') == run['source_branch']
                            and row.get('prompt') is not None and digest(row['prompt']) == run['task_digest']):
                        qualification = self.qualification(self.qualification_key(job, run['backend'], run['config_digest'], run['credential_digest'])) or {}
                        if context.get('source') == qualification.get('source'):
                            session = identifier(str(row.get('name', '')).removeprefix('sessions/'))
                            matches.append({'session_id': session, 'remote_run_id': session,
                                            'state': normalize_state(row.get('state')), 'response': row})
            else:
                response = transport.api('GET', '/v1/agents', params={'limit': 100, **({'cursor': page} if page else {})})
                rows, following = response.get('items', []), response.get('nextCursor')
                if not isinstance(rows, list) or len(rows) > 100 or any(not isinstance(row, dict) for row in rows):
                    raise AgentError('AGENT_RECONCILE_INVALID', 'The provider reconciliation page was invalid.')
                for row in rows:
                    if row.get('name') != run['correlation']:
                        continue
                    agent = transport.api('GET', '/v1/agents/' + identifier(row.get('id')))
                    expected = [{'url': 'https://github.com/' + run['repository'], 'startingRef': run['source_sha']}]
                    if agent.get('repos') == expected and agent.get('workOnCurrentBranch') is False and agent.get('autoCreatePR') is False:
                        # latestRunId can belong to another client's follow-up.
                        # Only a complete inventory containing the sole initial
                        # run can resolve a lost creation response.
                        session = identifier(agent.get('id'))
                        inventory = transport.api('GET', '/v1/agents/' + session + '/runs', params={'limit': 2})
                        turns = inventory.get('items')
                        if (not isinstance(turns, list) or len(turns) != 1 or not isinstance(turns[0], dict)
                                or inventory.get('nextCursor') or turns[0].get('agentId') != session
                                or turns[0].get('id') != agent.get('latestRunId')):
                            continue
                        candidate = dict(run, session_id=session, remote_run_id=identifier(turns[0].get('id')))
                        status = transport.status(candidate)
                        matches.append(dict(status, session_id=candidate['session_id'], remote_run_id=candidate['remote_run_id']))
            if not following:
                if len(matches) != 1:
                    return None
                return matches[0]
            if not isinstance(following, str) or following in seen or len(following) > 2048:
                break
            seen.add(following); page = following
        return None

    @credential_bound
    def control(self, job, run, kind, action):
        if kind not in run['capabilities']:
            raise AgentError('AGENT_CAPABILITY_UNAVAILABLE', 'This runtime does not advertise the requested control.')
        config, _, _, _ = self.configuration(job, run['backend'], run)
        if kind == 'steer':
            if CATALOG[run['backend']][1] not in {'https', 'codex'} and run['state'] not in {'COMPLETED', 'WAITING_FOR_INPUT'}:
                raise AgentError('AGENT_TURN_ACTIVE', 'This runtime accepts a follow-up after its active turn has ended.')
            message = action.get('message')
            if not isinstance(message, str) or not 1 <= len(message) <= 16000 or '\0' in message:
                raise AgentError('AGENT_MESSAGE_INVALID', 'Use a bounded explicit steering message.')
            if any(secret in message for secret in self.secrets(job)):
                raise AgentError('AGENT_CREDENTIAL_IN_PROMPT', 'Remove configured agent credentials from the steering text.')
            expected = run.get('remote_run_id') or run.get('session_id')
            if action.get('expected_remote_run_id') != expected or not expected:
                raise AgentError('AGENT_RUN_CHANGED', 'Refresh the active remote run before steering.')
        else:
            message = None
        operation = self.operation(job, run, kind, action.get('request_id'), action)
        if operation['state'] != 'PREPARED':
            return self.observe(job, run)
        transport = self.transport(run['backend'], config, job, run)
        try:
            if run['backend'] == 'cursor':
                current = transport.api('GET', '/v1/agents/' + identifier(run['session_id']))
                if current.get('latestRunId') != run.get('remote_run_id'):
                    raise AgentError('AGENT_RUN_CHANGED', 'The active provider run changed before control dispatch.')
            if CATALOG[run['backend']][1] == 'https':
                result = transport.control(run, kind, message, lambda: self.dispatch(job, run, operation))
                if run['backend'] == 'cursor' and kind == 'steer':
                    if result.get('delivery') not in {'queued', 'steered'}:
                        raise AgentError('AGENT_RESPONSE_INVALID', 'Cursor did not confirm a recognized steering delivery.', dispatched=True)
                    accepted = identifier(result['runId']) if result.get('runId') else None
                    self.save(job, run, steer_delivery=result['delivery'],
                              candidate_attribution='UNVERIFIED_FOLLOWUP' if accepted != run.get('remote_run_id') else 'CURRENT_RUN',
                              observed_latest_remote_run_id=accepted)
            else:
                result = transport.control(run, kind, message, operation['operation_id'], lambda: self.dispatch(job, run, operation))
            self.complete_operation(job, run, operation, result)
            if CATALOG[run['backend']][1] != 'https':
                self.observed(job, run, result)
            else:
                self.save(job, run, state='CANCELLING' if kind == 'cancel' else 'RUNNING', next_at=self.store.now() + 10)
        except Exception as error:
            self.complete_operation(job, run, operation, error=error)
            if operation['state'] == 'DISPATCHED':
                self.save(job, run, state='OUTCOME_UNKNOWN')
            else:
                raise AgentError(getattr(error, 'code', 'AGENT_CONTROL_FAILED'), 'The agent control was not dispatched.') from None
        return self.public(run)

    def local_candidate(self, job, run, result):
        if (run['state'] != 'COMPLETED' or result.get('state') != 'COMPLETED' or result.get('complete') is not True
                or result.get('session_id') != run.get('session_id') or not run.get('session_id')
                or result.get('remote_run_id') != run.get('remote_run_id') or not run.get('remote_run_id')):
            raise AgentError('AGENT_CANDIDATE_TURN_CHANGED', 'Capture a complete candidate from the exact completed runtime session and turn.')
        return self.protect_candidate(job, result)

    @credential_bound
    def capture(self, job, run, kind):
        config, _, _, _ = self.configuration(job, run['backend'], run)
        if kind not in run['capabilities']:
            raise AgentError('AGENT_CAPABILITY_UNAVAILABLE', 'This runtime does not advertise that evidence stream.')
        transport = self.transport(run['backend'], config, job, run)
        if run.get('event_pages', 0) >= 128 and kind == 'events':
            raise AgentError('AGENT_EVENT_RETENTION_LIMIT', 'This run reached its bounded event page allowance.')
        result = transport.events(run, run.get('event_cursor')) if kind == 'events' else transport.artifacts(run)
        if kind == 'artifacts' and CATALOG[run['backend']][1] != 'https':
            result = self.local_candidate(job, run, result)
        reference = self.put(job, result, 'agent-' + kind)
        values = {kind + '_ref': reference}
        if kind == 'events':
            values.update(event_cursor=result.get('next_cursor'), event_pages=run.get('event_pages', 0) + 1)
        if kind == 'artifacts' and CATALOG[run['backend']][1] != 'https':
            values.update(candidate_raw_ref=reference, candidate_status='CAPTURED_UNVALIDATED',
                          candidate_capture_binding=digest([run.get('session_id'), run.get('remote_run_id'), run.get('last_operation_id')]))
        self.save(job, run, **values)
        return dict(self.public(run), result_ref=reference, next_cursor=result.get('next_cursor'), complete=result.get('complete'))

    @credential_bound
    def import_candidate(self, job, run):
        from v14.agent_candidates import cursor_changes, unified_changes, verify_changes
        from v14.engineering_validation import patch_identity
        from v14.engineering_workspace_checks import catalog
        from v14.engineering_workspace import load_file
        config, _, _, _ = self.configuration(job, run['backend'], run)
        if 'import' not in run['capabilities']:
            raise AgentError('AGENT_CAPABILITY_UNAVAILABLE', 'This runtime has no attributable candidate import operation.')
        if run.get('candidate_attribution') == 'UNVERIFIED_FOLLOWUP':
            raise AgentError('AGENT_FOLLOWUP_ATTRIBUTION_REQUIRED', 'This queued provider follow-up has no request-bound run identity; it cannot be imported as the original run.')
        if run['source_sha'] != job['data']['head_sha']:
            raise AgentError('AGENT_SOURCE_CHANGED', 'The candidate source no longer matches the parent job.')
        policy, _ = catalog(job)
        if policy != run['policy_sha256']:
            raise AgentError('AGENT_POLICY_CHANGED', 'The validation policy changed after delegation.')
        transport = self.transport(run['backend'], config, job, run)
        attribution = {'source_sha': run['source_sha'], 'verified_by': 'SEALED_CHECKOUT'}
        if run['backend'] == 'cursor':
            changes, attribution = cursor_changes(self.backend, job, transport, run)
        elif run['backend'] == 'jules':
            status = transport.status(run)
            if status['state'] != 'COMPLETED':
                raise AgentError('AGENT_CANDIDATE_PENDING', 'Wait for the Jules session to finish before importing it.')
            patches, page, seen = [], None, set()
            for _ in range(20):
                response = transport.events(run, page)
                for event in response['events']:
                    for artifact in event.get('artifacts', []):
                        patch = artifact.get('changeSet', {}).get('gitPatch')
                        if patch:
                            patches.append(patch)
                following = response.get('next_cursor')
                if not following:
                    break
                if following in seen:
                    raise AgentError('AGENT_CANDIDATE_INCOMPLETE', 'Jules repeated an activity cursor.')
                seen.add(following); page = following
            else:
                raise AgentError('AGENT_CANDIDATE_INCOMPLETE', 'The bounded Jules activity window is incomplete.')
            patches = {digest(value): value for value in patches}
            if len(patches) != 1:
                raise AgentError('AGENT_CANDIDATE_AMBIGUOUS', 'The completed session must provide one unambiguous final Git patch.')
            patch = next(iter(patches.values()))
            if patch.get('baseCommitId') != run['source_sha']:
                raise AgentError('AGENT_CANDIDATE_SOURCE_CHANGED', 'The Jules patch was produced from a different commit.')
            changes = unified_changes(job, patch.get('unidiffPatch'), lambda path: load_file(self.backend, job, path)['content'])
            attribution['verified_by'] = 'PROVIDER_BASE_COMMIT_AND_EXACT_BLOB_CONTEXT'
        else:
            if run['state'] != 'COMPLETED' or run.get('operation_state') != 'CONFIRMED':
                raise AgentError('AGENT_CANDIDATE_PENDING', 'The exact completed local turn and its command receipt must be confirmed before import.')
            if (not run.get('candidate_raw_ref') or run.get('candidate_capture_binding') !=
                    digest([run.get('session_id'), run.get('remote_run_id'), run.get('last_operation_id')])):
                self.capture(job, run, 'artifacts')
            retained = self.local_candidate(job, run, self.service.artifacts.json_value(job, run['candidate_raw_ref']))
            changes = verify_changes(job, retained.get('changes'))
        patch_id = patch_identity(run['source_sha'], changes)
        if any(secret in (change['content'] or '') for secret in self.secrets(job) for change in changes):
            raise AgentError('AGENT_CREDENTIAL_IN_CANDIDATE', 'The candidate contains a configured agent credential; remove it before import.')
        check_id = digest(['agent-candidate', run['run_id'], patch_id, policy])
        def retain(db):
            stored = self.owner(db, job)
            reference = self.service.artifacts.put_json(job, changes, 'agent-candidate', transaction=db)
            candidate = dict(check_id=check_id, source_sha=run['source_sha'], patch_identity=patch_id, policy_sha256=policy,
                             check_index=None, status='UNVERIFIED', candidate_ref=reference, scope='exploratory',
                             complete_validation=False, agent_run_id=run['run_id'], comparison_scope='AGENT_SYSTEM',
                             experiment_id=run.get('experiment_id'), attribution=attribution)
            checks = stored.setdefault('data', {}).setdefault('workspace_checks', {})
            if len(checks) >= 64 and check_id not in checks:
                raise AgentError('AGENT_CANDIDATE_LIMIT', 'The parent job reached its retained candidate allowance.')
            checks[check_id] = candidate
            db.execute('UPDATE engineering_jobs SET data=? WHERE id=? AND principal=? AND lease=?',
                       (canonical(stored), job['id'], job['principal'], job['_execution_lease']))
            return candidate
        candidate = self.tx(retain)
        job['data'].setdefault('workspace_checks', {})[check_id] = candidate
        self.save(job, run, candidate_status='IMPORTED_UNVALIDATED', candidate_check_id=check_id, artifacts_ref=candidate['candidate_ref'])
        return dict(self.public(run), candidate=candidate)

    @credential_bound
    def compare(self, job, action):
        self.user_command(job, action)
        names = action.get('backends')
        if (not isinstance(names, list) or not 2 <= len(names) <= 8 or any(not isinstance(name, str) or name not in CATALOG for name in names)
                or len(set(names)) != len(names) or action.get('objective') != job['request']['objective'] or action.get('consent') is not True):
            raise AgentError('AGENT_EXPERIMENT_INVALID', 'Compare two to eight distinct configured agents on the same accepted task with explicit consent.')
        request_id = action.get('request_id')
        if not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,100}', request_id):
            raise AgentError('AGENT_REQUEST_ID_REQUIRED', 'Use a stable experiment request identifier.')
        configurations = {name: self.configuration(job, name) for name in names}
        limits = [{key: config[0].get(key, default) for key, default in [('max_seconds', 1800), ('max_iterations', 24), ('max_budget_usd', 2.0)]}
                  for config in configurations.values()]
        if any(value != limits[0] for value in limits):
            raise AgentError('AGENT_EXPERIMENT_BUDGET_MISMATCH', 'Configure identical declared time, iteration and cost budgets for an agent-system comparison.')
        identity = 'ae_' + digest([job['principal'], job['id'], request_id])[:40]
        request_digest = digest([action, {key: row[1] for key, row in configurations.items()}])
        record = dict(experiment_id=identity, comparison_scope='AGENT_SYSTEM', source_sha=job['data']['head_sha'],
                      task_digest=digest(action['objective']), policy_sha256=job['data'].get('admission', {}).get('policy_sha256'),
                      backends=names, limits=limits[0], run_ids=[], state='ADMITTING', scored=False,
                      user_authorized=True, user_command_id=action['request_id'],
                      caveat='Agent systems have different tools and model policies; no model-only benchmark or fair cost claim is inferred.')
        def prepare(db):
            self.owner(db, job)
            row = db.execute('SELECT request_digest,payload FROM engineering_agent_experiments WHERE experiment_id=?', (identity,)).fetchone()
            if row:
                if row[0] != request_digest:
                    raise AgentError('AGENT_REQUEST_CONFLICT', 'This experiment identity already belongs to a different configuration.')
                return json.loads(row[1])
            db.execute('INSERT INTO engineering_agent_experiments VALUES(?,?,?,?,?)', (identity, job['id'], job['principal'], request_digest, canonical(record)))
            return record
        record = self.tx(prepare)
        if record['state'] != 'ADMITTING':
            return record
        def save(db):
            self.owner(db, job)
            db.execute('UPDATE engineering_agent_experiments SET payload=? WHERE experiment_id=?', (canonical(record), identity))
        for name in names:
            child_request = 'experiment-' + digest([identity, name])[:40]
            try:
                result = self.start(job, dict(action='agent_start', head_sha=action['head_sha'], backend=name,
                    objective=action['objective'], model_id=configurations[name][0]['model_id'], consent=True,
                    request_id=child_request), experiment_id=identity)
            except AgentError as error:
                # A prior child may already be running. Retain that partial
                # admission honestly instead of reporting the whole command as
                # rejected/no effects, and never restart the failed child.
                self.tx(lambda db: self.owner(db, job))
                record.setdefault('failures', []).append({'backend': name, 'error_code': error.code})
                child_id = 'ar_' + digest([job['principal'], job['id'], child_request])[:40]
                try:
                    result = self.public(self.get(job, child_id))
                except AgentError:
                    result = None
            if result and result['run_id'] not in record['run_ids']:
                record['run_ids'].append(result['run_id'])
            self.tx(save)
        record['state'] = ('PARTIAL' if record['run_ids'] else 'FAILED') if record.get('failures') else 'RUNNING'
        self.tx(save)
        return record

    @staticmethod
    def user_command(job, action):
        # This marker is injected only while the authenticated durable workspace
        # command queue is drained. A model-generated consent boolean alone is
        # never authority to call a second, potentially paid model.
        command = job.get('_workspace_user_command_id')
        if not isinstance(command, str) or not command or action.get('request_id') != command:
            raise AgentError('AGENT_USER_COMMAND_REQUIRED', 'Start or steer an agent through an explicit user workspace command.')

    def act(self, job, action):
        if not isinstance(action, dict) or action.get('head_sha') != job['data'].get('head_sha') or not re.fullmatch('[a-f0-9]{40}', str(action.get('head_sha', ''))):
            raise AgentError('AGENT_SOURCE_REQUIRED', 'Agent commands must name the current exact source SHA.')
        self.store.assert_claim(job)
        kind = action.get('action', '').removeprefix('agent_')
        allowed = {'qualify': {'backend'}, 'start': {'backend', 'model_id', 'objective', 'consent', 'request_id'},
                   'compare': {'backends', 'objective', 'consent', 'request_id'},
                   'status': {'run_id'}, 'events': {'run_id'}, 'artifacts': {'run_id'}, 'import': {'run_id'},
                   'steer': {'run_id', 'message', 'request_id', 'expected_remote_run_id'},
                   'cancel': {'run_id', 'request_id'}, 'approve_plan': {'run_id', 'request_id'}}
        if kind not in allowed or set(action) - allowed[kind] - {'action', 'head_sha', 'request_id'}:
            raise AgentError('AGENT_ACTION_INVALID', 'Choose a supported agent operation and its exact argument contract.')
        if kind in {'steer', 'approve_plan'}:
            self.user_command(job, action)
        if kind == 'qualify':
            if action.get('backend') not in CATALOG:
                raise AgentError('AGENT_BACKEND_INVALID', 'Choose a supported agent runtime.')
            self.qualify(job, action.get('backend'))
            return next(row for row in self.capabilities(job) if row['id'] == action['backend'])
        if kind == 'start':
            return self.start(job, action)
        if kind == 'compare':
            return self.compare(job, action)
        run = self.get(job, action.get('run_id'))
        if run['source_sha'] != action['head_sha']:
            raise AgentError('AGENT_SOURCE_CHANGED', 'This agent run belongs to an earlier source revision.')
        if kind == 'status':
            return self.observe(job, run)
        if kind in {'events', 'artifacts'}:
            return self.capture(job, run, kind)
        if kind == 'import':
            return self.import_candidate(job, run)
        return self.control(job, run, kind, action)

    @credential_bound
    def cancel_one(self, job, run):
        """Parent cancellation closes its container even after config revocation."""
        if CATALOG[run['backend']][1] not in {'https', 'copilot'}:
            self.store.assert_claim(job)
            transport = self.transport(run['backend'], {})
            result = transport.dispose(run)
            self.save(job, run, state='CANCELLED' if result['state'] == 'CANCELLED' else 'RUNTIME_LOST', holding=False,
                      cancellation_receipt=self.put(job, result, 'agent-cancelled'))
        elif 'cancel' not in run['capabilities'] or not run.get('session_id'):
            self.save(job, run, state='REMOTE_CONTINUES', detached=True, holding=False, error_code='AGENT_REMOTE_CANCEL_UNSUPPORTED')
        else:
            # Cancel is an explicit parent action and may occur after the task
            # deadline. It uses the same operation journal, never a new task.
            config, _, _, _ = self.configuration(job, run['backend'], run)
            operation = self.operation(job, run, 'cancel', 'parent-cancel', {'source_sha': run['source_sha'], 'run_id': run['run_id']})
            if operation['state'] == 'PREPARED':
                def dispatch_cancel():
                    def mark(db):
                        self.owner(db, job, source=False)
                        current = dict(operation, state='DISPATCHED', dispatched_at=self.store.now())
                        if db.execute("UPDATE engineering_agent_operations SET state='DISPATCHED',payload=? WHERE operation_id=? AND state='PREPARED'",
                                      (canonical(current), operation['operation_id'])).rowcount != 1:
                            raise AgentError('AGENT_OPERATION_ALREADY_DISPATCHED', 'Cancellation was already dispatched.')
                        retained = dict(run, operation_state='DISPATCHED', last_operation_id=operation['operation_id'])
                        db.execute('UPDATE engineering_agent_runs SET payload=? WHERE run_id=? AND job_id=? AND principal=?',
                                   (canonical(retained), run['run_id'], job['id'], job['principal']))
                    self.tx(mark)
                    operation['state'] = 'DISPATCHED'
                    run.update(operation_state='DISPATCHED', last_operation_id=operation['operation_id'])
                try:
                    result = self.transport(run['backend'], config).control(run, 'cancel', None, dispatch_cancel)
                    operation = self.complete_operation(job, run, operation, result)
                except Exception as error:
                    operation = self.complete_operation(job, run, operation, error=error)
            self.save(job, run, state='REMOTE_CONTINUES', detached=True, holding=False, error_code='AGENT_REMOTE_STOP_UNCONFIRMED',
                      operation_state=operation['state'], last_operation_id=operation['operation_id'])
        return self.public(run)

    def cancel_all(self, job):
        results = []
        for run in self.rows(job):
            if run['state'] in {'CANCELLED', 'RUNTIME_LOST'}:
                continue
            try:
                results.append(self.cancel_one(job, run))
            except (AgentError, ValueError, OSError) as error:
                self.save(job, run, state='REMOTE_CONTINUES', detached=True, holding=False,
                          error_code=getattr(error, 'code', 'AGENT_CANCEL_UNCONFIRMED'))
                results.append(self.public(run))
        return {'schema': SCHEMA, 'runs': results, 'all_stopped': all(v['state'] in {'CANCELLED', 'RUNTIME_LOST'} for v in results)}

    @staticmethod
    def stopped_parent(db, job):
        """Post-cancel authority permits cleanup only, never execution."""
        row = db.execute('SELECT state,revision,data FROM engineering_jobs WHERE id=? AND principal=?',
                         (job['id'], job['principal'])).fetchone()
        if (not row or row[0] not in {'CANCELLING', 'CANCELLED', 'FAILED', 'COMPLETED'}
                or tuple(row[:2]) != (job['state'], job['revision'])
                or json.loads(row[2]).get('data', {}).get('head_sha') != job['data'].get('head_sha')):
            raise AgentError('AGENT_PARENT_CHANGED', 'Cleanup requires the current cancelled or terminal parent revision and source.')

    @credential_bound
    def stop_parent(self, job):
        """Cleanup after Store.control revoked the old execution lease.

        Local removal is identity-checked and idempotent. An uncertain cloud
        cancel is never POSTed a second time; its exact run is only observed.
        """
        self.tx(lambda db: self.stopped_parent(db, job))
        results = []
        for run in self.rows(job):
            def prepare(db):
                self.stopped_parent(db, job)
                row = db.execute('SELECT state,payload FROM engineering_agent_stops WHERE run_id=? AND job_id=? AND principal=?',
                                 (run['run_id'], job['id'], job['principal'])).fetchone()
                if row:
                    return json.loads(row[1])
                record = dict(run_id=run['run_id'], state='PREPARED', source_sha=run['source_sha'],
                              parent_revision=job['revision'], parent_state=job['state'])
                db.execute('INSERT INTO engineering_agent_stops VALUES(?,?,?,?,?)',
                           (run['run_id'], job['id'], job['principal'], record['state'], canonical(record)))
                return record
            stop = self.tx(prepare)
            if stop.get('state') == 'STOPPED':
                results.append({'run_id': run['run_id'], 'state': 'STOPPED', 'remote_state': stop.get('remote_state')})
                continue
            location = CATALOG[run['backend']][1]
            result = {'state': 'REMOTE_CONTINUES'}
            try:
                if location not in {'https', 'copilot'}:
                    self.tx(lambda db: self.stopped_parent(db, job))
                    observed = self.transport(run['backend'], {}).dispose(run)
                    result = {'state': 'STOPPED', 'remote_state': observed['state']}
                elif run['state'] in {'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'}:
                    result = {'state': 'STOPPED', 'remote_state': run['state']}
                elif 'cancel' not in run['capabilities'] or not run.get('session_id'):
                    result['error_code'] = 'AGENT_REMOTE_CANCEL_UNSUPPORTED'
                else:
                    config, _, _, _ = self.configuration(job, run['backend'], run)
                    transport = self.transport(run['backend'], config)
                    status = transport.status(run)
                    if status.get('state') in {'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'}:
                        result = {'state': 'STOPPED', 'remote_state': status['state']}
                    elif stop['state'] != 'DISPATCHED' and not stop.get('dispatched'):
                        def fence():
                            def mark(db):
                                self.stopped_parent(db, job)
                                saved = dict(stop, state='DISPATCHED', dispatched_at=self.store.now())
                                if db.execute('UPDATE engineering_agent_stops SET state=?,payload=? WHERE run_id=? AND state=?',
                                              ('DISPATCHED', canonical(saved), run['run_id'], stop['state'])).rowcount != 1:
                                    raise AgentError('AGENT_OPERATION_ALREADY_DISPATCHED', 'The parent stop was already dispatched.')
                            self.tx(mark)
                            stop['state'] = 'DISPATCHED'
                        transport.control(run, 'cancel', None, fence)
                        status = transport.status(run)
                        result = {'state': 'STOPPED' if status.get('state') in {'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'} else 'REMOTE_CONTINUES',
                                  'remote_state': status.get('state')}
                    else:
                        result.update(remote_state=status.get('state'), error_code='AGENT_REMOTE_STOP_UNCONFIRMED')
            except Exception as error:
                result['error_code'] = getattr(error, 'code', 'AGENT_STOP_UNCONFIRMED')
            def retain(db):
                self.stopped_parent(db, job)
                saved = dict(stop, **result, observed_at=self.store.now())
                # Preserve whether a cloud cancel crossed its dispatch fence
                # even when its subsequent observation remains inconclusive.
                saved['dispatched'] = stop['state'] == 'DISPATCHED' or stop.get('dispatched', False)
                db.execute('UPDATE engineering_agent_stops SET state=?,payload=? WHERE run_id=? AND job_id=? AND principal=?',
                           (saved['state'], canonical(saved), run['run_id'], job['id'], job['principal']))
                latest = db.execute('SELECT payload FROM engineering_agent_runs WHERE run_id=? AND job_id=? AND principal=?',
                                    (run['run_id'], job['id'], job['principal'])).fetchone()
                current = json.loads(latest[0])
                current.update(holding=False, cleanup_state=result['state'], detached=result['state'] != 'STOPPED',
                               updated_at=self.store.now(), error_code=result.get('error_code'))
                if current['state'] not in TERMINAL:
                    current['state'] = ('CANCELLED' if result['state'] == 'STOPPED' else 'REMOTE_CONTINUES')
                db.execute('UPDATE engineering_agent_runs SET payload=? WHERE run_id=?', (canonical(current), run['run_id']))
            self.tx(retain)
            results.append(dict(result, run_id=run['run_id']))
        return {'schema': SCHEMA, 'runs': results, 'all_stopped': all(row['state'] == 'STOPPED' for row in results)}

    def advance(self, job):
        """Observe at most one due run per parent step, with bounded backoff."""
        rows, observed, now = self.rows(job), [], self.store.now()
        for run in sorted(rows, key=lambda value: value.get('next_at', 0)):
            if run['state'] in TERMINAL or run.get('next_at', 0) > now:
                continue
            try:
                if now >= min(run['deadline'], job['deadline']):
                    self.cancel_one(job, run)
                else:
                    self.observe(job, run)
            except (AgentError, ValueError, OSError) as error:
                self.save(job, run, error_code=getattr(error, 'code', 'AGENT_OBSERVATION_UNAVAILABLE'), next_at=now + 30)
            observed.append(run['run_id'])
            break
        current = self.rows(job)
        active = [run for run in current if run['state'] not in TERMINAL]
        return {'pending': bool(active), 'holding': any(run.get('holding') for run in active),
                'next_at': min((max(now + 10, run.get('next_at', now + 15)) for run in active), default=now + 15), 'observed': observed}
