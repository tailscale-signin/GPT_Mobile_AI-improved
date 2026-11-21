"""Optional host supervision of GitHub Copilot cloud tasks in the durable journal.

The Agent Tasks preview has no idempotency key or conditional SHA write. Commit
the dispatch fence before POST, never replay an uncertain POST, and describe ref
checks as preflight only. Cloud completion is not CI or merge authorization.
"""
import hashlib
import json
import logging
import os
import re
import threading
import uuid
from urllib.parse import quote

import requests

from v14.github_api_governor import GitHubApiGovernor


SCHEMA = 'github.copilot-tasks.v1'
API_VERSION = '2026-03-10'
REMOTE_STATES = {'queued', 'in_progress', 'completed', 'failed', 'idle', 'waiting_for_user', 'timed_out', 'cancelled'}
TERMINAL = {'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'REJECTED', 'RECONCILE_REQUIRED'}
SHA = re.compile(r'^[a-f0-9]{40}(?:[a-f0-9]{24})?$')


class CopilotTaskError(Exception):
    def __init__(self, code, message, status=409):
        super().__init__(message)
        self.code, self.status = code, status


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def validate_request(value):
    fields = {'request_id', 'repository', 'prompt', 'base_ref', 'expected_base_sha', 'head_ref', 'expected_head_sha',
              'expected_actor_id', 'model', 'custom_agent', 'create_pull_request', 'execution_owner',
              'copilot_enabled', 'conversation_copilot_enabled'}
    if not isinstance(value, dict) or set(value) - fields:
        raise CopilotTaskError('COPILOT_REQUEST_INVALID', 'Choose a supported Copilot task request.', 400)
    result = dict(value)
    if result.get('execution_owner') != 'GATEWAY':
        raise CopilotTaskError('COPILOT_OWNER_REQUIRED', 'Explicitly select this Gateway to supervise the Copilot task.', 400)
    if result.get('copilot_enabled') is not True or result.get('conversation_copilot_enabled') is not True:
        raise CopilotTaskError('COPILOT_CONSENT_REQUIRED', 'Enable Copilot for the application and this conversation before delegating.', 412)
    for name, maximum in [('request_id', 128), ('prompt', 24000), ('base_ref', 200)]:
        item = result.get(name)
        if not isinstance(item, str) or not item.strip() or len(item) > maximum or '\x00' in item:
            raise CopilotTaskError('COPILOT_REQUEST_INVALID', 'Supply a bounded task identity, prompt and exact base branch.', 400)
    if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', result['request_id']):
        raise CopilotTaskError('COPILOT_REQUEST_INVALID', 'Use a stable task request identifier.', 400)
    repo = result.get('repository')
    if not isinstance(repo, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}', repo) or repo.split('/')[1] in {'.', '..'}:
        raise CopilotTaskError('COPILOT_REPOSITORY_INVALID', 'Choose an exact GitHub owner/repository.', 400)
    for name in ('base_ref', 'head_ref'):
        ref = result.get(name)
        if name == 'head_ref' and ref is None and name not in result:
            continue
        if (not isinstance(ref, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./@-]{0,199}', ref)
                or '..' in ref or ref.endswith(('/', '.'))
                or any(part in {'', '.', '..'} or part.endswith('.lock') for part in ref.split('/'))):
            raise CopilotTaskError('COPILOT_REF_INVALID', 'Choose an exact branch name.', 400)
    for name in ('expected_base_sha', 'expected_head_sha'):
        if name == 'expected_head_sha' and 'head_ref' not in result and name not in result:
            continue
        if not isinstance(result.get(name), str) or not SHA.fullmatch(result[name]):
            raise CopilotTaskError('COPILOT_SHA_REQUIRED', 'Verify the exact base and any selected head commit before delegation.', 400)
    if ('head_ref' in result) != ('expected_head_sha' in result):
        raise CopilotTaskError('COPILOT_SHA_REQUIRED', 'A selected head requires its exact verified commit.', 400)
    if result.get('head_ref') == result['base_ref']:
        raise CopilotTaskError('COPILOT_REF_INVALID', 'Select a work branch separate from the base branch.', 400)
    if type(result.get('expected_actor_id')) is not int or not 0 < result['expected_actor_id'] < 2**63:
        raise CopilotTaskError('COPILOT_ACTOR_REQUIRED', 'Verify the host GitHub account before delegation.', 400)
    for name in ('model', 'custom_agent'):
        if name in result and (not isinstance(result[name], str) or not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,128}', result[name])):
            raise CopilotTaskError('COPILOT_REQUEST_INVALID', 'Use a supported model or custom agent identifier.', 400)
    if type(result.get('create_pull_request', False)) is not bool:
        raise CopilotTaskError('COPILOT_REQUEST_INVALID', 'Choose whether Copilot may create a pull request.', 400)
    result['create_pull_request'] = result.get('create_pull_request', False)
    return result


class CopilotTasks:
    def __init__(self, service, *, enabled=None, request=None):
        self.service, self.store, self.backend = service, service.store, service.backend
        self.enabled = enabled or (lambda: os.getenv('GATEWAY_COPILOT_ENABLED', 'false').lower() == 'true')
        self.request = request or requests.request
        self.stop, self.wake = threading.Event(), threading.Event()
        self.thread = None
        self._tick_lock = threading.Lock()

    def stopped(self):
        return self.stop.is_set() or self.service.stop.is_set()

    def tx(self, action):
        def run(db):
            db.execute('CREATE TABLE IF NOT EXISTS engineering_copilot_tasks ('
                       'id TEXT PRIMARY KEY, principal TEXT NOT NULL, request_id TEXT NOT NULL, digest TEXT NOT NULL, '
                       'state TEXT NOT NULL, next_at REAL NOT NULL, data TEXT NOT NULL, UNIQUE(principal,request_id))')
            return action(db)
        return self.store.tx(run)

    def _save(self, db, task):
        task['revision'] += 1
        task['updated_at'] = self.store.now()
        db.execute('UPDATE engineering_copilot_tasks SET state=?,next_at=?,data=? WHERE id=?',
                   (task['state'], task['next_at'], canonical(task), task['id']))

    def get(self, task_id, principal=None):
        def read(db):
            row = db.execute('SELECT data FROM engineering_copilot_tasks WHERE id=?', (task_id,)).fetchone()
            task = json.loads(row[0]) if row else None
            if task and principal is not None and task['principal'] != principal:
                return None
            return task
        return self.tx(read)

    def _existing(self, principal, request):
        digest = hashlib.sha256(canonical(request).encode()).hexdigest()
        def read(db):
            row = db.execute('SELECT digest,data FROM engineering_copilot_tasks WHERE principal=? AND request_id=?',
                             (principal, request['request_id'])).fetchone()
            if row and row[0] != digest:
                raise CopilotTaskError('COPILOT_REQUEST_CONFLICT', 'This request identity already belongs to a different task.')
            return json.loads(row[1]) if row else None
        return self.tx(read)

    @staticmethod
    def credential_hash(token):
        return hashlib.sha256(token.encode('utf-8')).hexdigest()

    def _api(self, method, path, token, body=None, before_dispatch=None):
        governor = self.backend.github_governor
        with governor.lease(token, mutating=method != 'GET', cancelled=self.stopped):
            if before_dispatch:
                before_dispatch()
            with self.request(method, 'https://api.github.com' + path,
                              headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                                       'X-GitHub-Api-Version': API_VERSION}, json=body,
                              auth=lambda request: request, timeout=(10, 30), allow_redirects=False, stream=True) as response:
                governor.observe(token, response.status_code, response.headers)
                # Do not follow redirects or retain error bodies that may include
                # private prompts, credentials or provider diagnostic details.
                if response.status_code != (201 if method == 'POST' else 200):
                    failure = requests.HTTPError('GitHub Copilot request was not confirmed')
                    failure.response = response
                    raise failure
                value = self.backend.read_response(response, False)
                if not isinstance(value, dict):
                    raise CopilotTaskError('COPILOT_RESPONSE_INVALID', 'GitHub did not return a verified task response.')
                return value

    def _identity(self, token):
        actor = self._api('GET', '/user', token)
        if type(actor.get('id')) is not int or actor['id'] <= 0:
            raise CopilotTaskError('COPILOT_ACTOR_UNVERIFIED', 'GitHub did not verify the selected account.', 412)
        return actor

    def capabilities(self, principal):
        durable = bool(self.store.runtime.GATEWAY_DURABLE_JOBS)
        enabled = bool(self.enabled() and durable)
        result = dict(schema=SCHEMA, supported=True, enabled=enabled, configured=False,
                      gateway_id=self.store.conversations.gateway_id() if durable else None, principal_id=principal,
                      api_version=API_VERSION, execution_owner='GITHUB_CLOUD', orchestration_owner='GATEWAY',
                      requires_explicit_consent=True, remote_cancel_supported=False, automatic_merge=False,
                      sha_verification='PREFLIGHT_ONLY')
        if not enabled:
            return dict(result, reason='COPILOT_DISABLED' if durable else 'DURABLE_STORAGE_REQUIRED')
        try:
            actor = self._identity(self.backend.token())
            return dict(result, configured=True, actor_id=actor['id'], actor_login=actor.get('login'))
        except Exception:
            return dict(result, reason='COPILOT_HOST_CONNECTION_REQUIRED')

    def submit(self, principal, value):
        request = validate_request(value)
        if not self.store.runtime.GATEWAY_DURABLE_JOBS:
            raise CopilotTaskError('COPILOT_DURABLE_STORAGE_REQUIRED', 'Enable durable Gateway storage before delegation.', 412)
        existing = self._existing(principal, request)
        if existing:
            return self.public(existing)
        if not self.enabled():
            raise CopilotTaskError('COPILOT_DISABLED', 'Enable Copilot cloud tasks in Gateway Settings before delegation.', 412)
        if not self.service.authorized({'principal': principal}):
            raise PermissionError('Engineering scope is required')
        try:
            token = self.backend.token()
        except (ValueError, OSError):
            raise CopilotTaskError('COPILOT_HOST_CONNECTION_REQUIRED', 'Connect a host GitHub account with Agent tasks access.', 412) from None
        now = self.store.now()
        task = dict(id='cp_' + uuid.uuid4().hex, principal=principal, request=request, state='ACCEPTED', revision=0,
                    next_at=now, accepted_at=now, updated_at=now, last_checked_at=None, upstream_task_id=None,
                    remote_state=None, gateway_id=self.store.conversations.gateway_id(),
                    credential_hash=self.credential_hash(token), repository_id=None, repository_owner_id=None,
                    error_code=None, dispatched_at=None)
        def insert(db):
            row = db.execute('SELECT digest,data FROM engineering_copilot_tasks WHERE principal=? AND request_id=?',
                             (principal, request['request_id'])).fetchone()
            digest = hashlib.sha256(canonical(request).encode()).hexdigest()
            if row:
                if row[0] != digest:
                    raise CopilotTaskError('COPILOT_REQUEST_CONFLICT', 'This request identity already belongs to a different task.')
                return json.loads(row[1])
            count = db.execute('SELECT COUNT(*) FROM engineering_copilot_tasks WHERE principal=?', (principal,)).fetchone()[0]
            if count >= 1000:
                raise CopilotTaskError('COPILOT_STORAGE_LIMIT', 'The saved Copilot task limit has been reached.', 429)
            db.execute('INSERT INTO engineering_copilot_tasks VALUES(?,?,?,?,?,?,?)',
                       (task['id'], principal, request['request_id'], digest, task['state'], now, canonical(task)))
            db.execute("INSERT OR IGNORE INTO gateway_catalog_keys(job_kind,job_id) VALUES('copilot',?)", (task['id'],))
            return task
        saved = self.tx(insert)
        self.wake.set()
        return self.public(saved)

    def status(self, principal, task_id):
        task = self.get(task_id, principal)
        if not task:
            raise CopilotTaskError('COPILOT_TASK_NOT_FOUND', 'No saved Copilot task belongs to this account.', 404)
        return self.public(task)

    def list(self, principal, request_id=None, repository=None, page=1, per_page=30):
        if request_id is not None and (not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', request_id)):
            raise CopilotTaskError('COPILOT_REQUEST_INVALID', 'Use a bounded saved request identifier.', 400)
        if repository is not None and (not isinstance(repository, str) or len(repository) > 140):
            raise CopilotTaskError('COPILOT_REPOSITORY_INVALID', 'Choose an exact GitHub owner/repository.', 400)
        if type(page) is not int or not 1 <= page <= 1000 or type(per_page) is not int or not 1 <= per_page <= 100:
            raise CopilotTaskError('COPILOT_PAGE_INVALID', 'Choose a task page from 1 to 1000 and page size from 1 to 100.', 400)
        query = 'SELECT data FROM engineering_copilot_tasks WHERE principal=?'
        parameters = [principal]
        if request_id is not None:
            query += ' AND request_id=?'
            parameters.append(request_id)
        if repository is not None:
            query += " AND lower(json_extract(data,'$.request.repository'))=lower(?)"
            parameters.append(repository)
        # Polling changes next_at. Keep page membership stable while workers observe tasks.
        query += ' ORDER BY rowid DESC LIMIT ? OFFSET ?'
        parameters.extend((per_page + 1, (page - 1) * per_page))
        tasks = self.tx(lambda db: [json.loads(row[0]) for row in db.execute(query, parameters)])
        more = len(tasks) > per_page
        return {'schema': SCHEMA, 'tasks': [self.public(task) for task in tasks[:per_page]],
                'page': page, 'per_page': per_page, 'has_more': more, 'complete': not more}

    @staticmethod
    def public(task):
        request = task['request']
        result = dict(schema=SCHEMA, id=task['id'], gateway_job_id=task['id'], request_id=request['request_id'],
                      repository=request['repository'], state=task['state'], revision=task['revision'],
                      upstream_task_id=task['upstream_task_id'], task_id=task['upstream_task_id'], remote_state=task['remote_state'],
                      gateway_id=task['gateway_id'], principal_id=task['principal'], github_actor_id=request['expected_actor_id'],
                      execution_owner='GITHUB_CLOUD', orchestration_owner='GATEWAY', detached=True,
                      accepted_at=task['accepted_at'], updated_at=task['updated_at'], last_checked_at=task['last_checked_at'],
                      base_ref=request['base_ref'], expected_base_sha=request['expected_base_sha'],
                      create_pull_request=request['create_pull_request'], error_code=task['error_code'],
                      merge_authorized=False, ci_verified=False, head_sha_verified=False,
                      sha_verification='PREFLIGHT_ONLY', remote_cancel_supported=False)
        for field in ('head_ref', 'expected_head_sha', 'model', 'custom_agent'):
            if field in request:
                result[field] = request[field]
        if task['upstream_task_id']:
            result['task_url'] = ('https://github.com/' + request['repository'] + '/copilot/tasks/' +
                                  quote(task['upstream_task_id'], safe=''))
        return result

    def _checkpoint(self, task, **changes):
        def save(db):
            current = json.loads(db.execute('SELECT data FROM engineering_copilot_tasks WHERE id=?', (task['id'],)).fetchone()[0])
            if current['revision'] != task['revision']:
                raise CopilotTaskError('COPILOT_STATE_CHANGED', 'The task checkpoint changed before this operation.')
            task.update(changes)
            self._save(db, task)
        self.tx(save)

    def _preflight(self, task, token):
        request = task['request']
        actor = self._identity(token)
        if actor['id'] != request['expected_actor_id']:
            raise CopilotTaskError('COPILOT_ACCOUNT_CHANGED', 'The host GitHub account changed before delegation.', 412)
        root = '/repos/' + request['repository']
        repo = self._api('GET', root, token)
        if (repo.get('full_name', '').lower() != request['repository'].lower() or repo.get('archived')
                or repo.get('permissions', {}).get('push') is not True or type(repo.get('id')) is not int
                or type(repo.get('owner', {}).get('id')) is not int):
            raise CopilotTaskError('COPILOT_REPOSITORY_ACCESS_REQUIRED', 'Verify write access to the exact selected repository.', 412)
        for branch, sha in (('base_ref', 'expected_base_sha'), ('head_ref', 'expected_head_sha')):
            if branch in request:
                ref = self._api('GET', root + '/git/ref/heads/' + quote(request[branch], safe=''), token)
                if ref.get('object', {}).get('sha') != request[sha]:
                    raise CopilotTaskError('COPILOT_SOURCE_CHANGED', 'Refresh the selected source commit before delegation.', 412)
        return repo['id'], repo['owner']['id']

    def _observe(self, task, remote):
        identifier = remote.get('id')
        identities = [remote.get(field) for field in ('repository', 'owner', 'creator')]
        if any(not isinstance(identity, dict) or type(identity.get('id')) is not int for identity in identities):
            raise CopilotTaskError('COPILOT_REMOTE_IDENTITY_UNVERIFIED', 'The remote task identity needs reconciliation.')
        if (not isinstance(identifier, str) or not 1 <= len(identifier) <= 256 or identifier in {'.', '..'}
                or any(ord(ch) < 33 or ord(ch) == 127 for ch in identifier)
                or task['upstream_task_id'] is not None and task['upstream_task_id'] != identifier
                or remote.get('repository', {}).get('id') != task['repository_id']
                or remote.get('owner', {}).get('id') != task['repository_owner_id']
                or remote.get('creator', {}).get('id') != task['request']['expected_actor_id']):
            raise CopilotTaskError('COPILOT_REMOTE_IDENTITY_UNVERIFIED', 'The remote task identity needs reconciliation.')
        remote_state = remote.get('state')
        known_state = isinstance(remote_state, str) and remote_state in REMOTE_STATES
        state = remote_state.upper() if known_state else 'UNKNOWN'
        self._checkpoint(task, upstream_task_id=identifier, remote_state=remote_state if known_state else None,
                         state=state, last_checked_at=self.store.now(), next_at=self.store.now() + 30,
                         error_code=None if state != 'UNKNOWN' else 'COPILOT_REMOTE_STATE_UNKNOWN')

    def tick(self, task_id):
        if not self._tick_lock.acquire(blocking=False):
            return
        try:
            task = self.get(task_id)
            if not task or task['state'] in TERMINAL or self.stopped():
                return
            if task['state'] == 'DISPATCHING':
                self._checkpoint(task, state='RECONCILE_REQUIRED', error_code='COPILOT_SUBMISSION_UNCONFIRMED')
                return
            if not self.service.authorized({'principal': task['principal']}):
                self._checkpoint(task, error_code='COPILOT_OWNER_UNAVAILABLE', next_at=self.store.now() + 60)
                return
            try:
                token = self.backend.token()
                if self.credential_hash(token) != task['credential_hash']:
                    raise CopilotTaskError('COPILOT_CREDENTIAL_CHANGED', 'The selected host GitHub credential changed.', 412)
                if task['upstream_task_id']:
                    actor = self._identity(token)
                    if actor['id'] != task['request']['expected_actor_id']:
                        raise CopilotTaskError('COPILOT_ACCOUNT_CHANGED', 'The host GitHub account changed.', 412)
                    path = '/agents/repos/' + task['request']['repository'] + '/tasks/' + quote(task['upstream_task_id'], safe='')
                    self._observe(task, self._api('GET', path, token))
                    return
                if not self.enabled():
                    raise CopilotTaskError('COPILOT_DISABLED', 'Copilot delegation is disabled on this Gateway.', 412)
                repository_id, owner_id = self._preflight(task, token)
                request = task['request']
                payload = {key: request[key] for key in ('prompt', 'base_ref', 'head_ref', 'model', 'custom_agent', 'create_pull_request') if key in request}

                def fence():
                    if not self.enabled() or not self.service.authorized({'principal': task['principal']}):
                        raise CopilotTaskError('COPILOT_CONSENT_REVOKED', 'Host authorization changed before delegation.', 412)
                    if self.credential_hash(self.backend.token()) != task['credential_hash']:
                        raise CopilotTaskError('COPILOT_CREDENTIAL_CHANGED', 'The selected host GitHub credential changed.', 412)
                    self._checkpoint(task, state='DISPATCHING', dispatched_at=self.store.now(),
                                     repository_id=repository_id, repository_owner_id=owner_id)

                remote = self._api('POST', '/agents/repos/' + request['repository'] + '/tasks', token, payload, fence)
                self._observe(task, remote)
            except Exception as error:
                # Read the committed fence again; the response can be lost or
                # a checkpoint can fail after GitHub accepted the write.
                current = self.get(task_id)
                if current['state'] in TERMINAL:
                    return
                task = current
                response = getattr(error, 'response', None)
                status = getattr(response, 'status_code', None)
                code = error.code if isinstance(error, CopilotTaskError) else 'COPILOT_CONNECTION_UNAVAILABLE'
                state = task['state']
                if state == 'DISPATCHING':
                    state = 'REJECTED' if status in {400, 401, 403, 404, 422, 429} else 'RECONCILE_REQUIRED'
                    code = 'COPILOT_SUBMISSION_REJECTED' if state == 'REJECTED' else 'COPILOT_SUBMISSION_UNCONFIRMED'
                elif task['upstream_task_id'] is None and isinstance(error, CopilotTaskError):
                    state = 'REJECTED'
                wait = GitHubApiGovernor.retry_delay(status, getattr(response, 'headers', {}), self.store.now()) if status else None
                self._checkpoint(task, state=state, error_code=code, next_at=self.store.now() + max(30, wait or 0))
        finally:
            self._tick_lock.release()

    def start(self):
        if not self.store.runtime.GATEWAY_DURABLE_JOBS or self.thread and self.thread.is_alive():
            return
        self.stop.clear()
        # A process restart cannot establish whether a fenced POST reached
        # GitHub. Known upstream IDs are recovered exclusively with GET.
        def recover(db):
            for row in db.execute("SELECT data FROM engineering_copilot_tasks WHERE state='DISPATCHING'").fetchall():
                task = json.loads(row[0])
                task.update(state='RECONCILE_REQUIRED', error_code='COPILOT_SUBMISSION_UNCONFIRMED')
                self._save(db, task)
        self.tx(recover)

        def work():
            while not self.stopped():
                try:
                    if not getattr(self.store.runtime, 'gateway_draining', False):
                        due = self.tx(lambda db: [row[0] for row in db.execute(
                            'SELECT id FROM engineering_copilot_tasks WHERE next_at<=? AND state NOT IN (' +
                            ','.join('?' for _ in TERMINAL) + ') ORDER BY next_at LIMIT 16', (self.store.now(), *sorted(TERMINAL)))])
                        for task_id in due:
                            if self.stopped():
                                break
                            self.tick(task_id)
                except Exception as error:
                    logging.getLogger(__name__).warning('Copilot checkpoint unavailable: %s', type(error).__name__)
                self.wake.wait(2)
                self.wake.clear()
        self.thread = threading.Thread(target=work, name='copilot-supervisor', daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        self.wake.set()
        if self.thread:
            self.thread.join(15)
