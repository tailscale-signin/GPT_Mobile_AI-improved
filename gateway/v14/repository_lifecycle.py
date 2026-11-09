"""Explicit, durable GitHub clone/import operations; never an arbitrary shell surface.

The caller authorizes one exact source/destination. A recorded remote dispatch is
never replayed after an uncertain result. Git runs in a new host-owned directory,
with fixed HTTPS remotes, disabled hooks/configuration and no credential argv.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import threading
import time
import uuid
from urllib.parse import quote

import requests


SCHEMA = 'github.repository-lifecycle.v1'
SHA = re.compile(r'^[a-f0-9]{40}(?:[a-f0-9]{24})?$')
MAX_REFS = 512
MAX_REF_ARGUMENT_BYTES = 24000
MAX_SOURCE_SIZE_KIB = 512 * 1024
MAX_WORKSPACE_BYTES = 1024 * 1024 * 1024
MAX_RETAINED_WORKSPACE_BYTES = 4 * MAX_WORKSPACE_BYTES
MIN_FREE_BYTES = 256 * 1024 * 1024
OWNER_LEASE_MS = 30000
TERMINAL = {'VERIFIED', 'RECONCILE_REQUIRED', 'BLOCKED', 'FAILED'}


def require_safe_lfs_version(raw):
    match = re.match(rb'^git-lfs/(\d+)\.(\d+)\.(\d+)(?:\s|\(|$)', raw.strip())
    if not match or tuple(int(part) for part in match.groups()) < (3, 7, 1):
        raise RepositoryLifecycleError('REPOSITORY_LFS_UPDATE_REQUIRED', 'Git LFS 3.7.1 or newer is required for safe repository checkout.', 412)


class RepositoryLifecycleError(Exception):
    def __init__(self, code, message, status=409):
        super().__init__(message)
        self.code, self.status = code, status


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def validate_request(value):
    common = {'operation', 'source_owner', 'source_repo', 'source_ref', 'expected_actor_id', 'source_commit_sha'}
    importing = isinstance(value, dict) and value.get('operation') == 'import_repository'
    allowed = common | ({'destination_owner', 'destination_repo', 'owner_type', 'visibility', 'copy_mode'} if importing else set())
    if not isinstance(value, dict) or set(value) - allowed or value.get('operation') not in {'clone_repository', 'import_repository'}:
        raise RepositoryLifecycleError('REPOSITORY_REQUEST_INVALID', 'Choose an explicit supported repository operation.', 400)
    result = dict(value)
    for name in ['source_owner'] + (['destination_owner'] if importing else []):
        if not isinstance(result.get(name), str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}', result[name]):
            raise RepositoryLifecycleError('REPOSITORY_OWNER_INVALID', 'Choose the exact GitHub repository owner.', 400)
    for name in ['source_repo'] + (['destination_repo'] if importing else []):
        if not isinstance(result.get(name), str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', result[name]) or result[name] in {'.', '..'}:
            raise RepositoryLifecycleError('REPOSITORY_NAME_INVALID', 'Choose the exact GitHub repository name.', 400)
    ref = result.get('source_ref')
    if (not isinstance(ref, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./@-]{0,199}', ref)
            or any(part in {'', '.', '..'} or part.endswith('.lock') for part in ref.split('/'))
            or '..' in ref or ref.endswith(('/', '.'))):
        raise RepositoryLifecycleError('REPOSITORY_REF_INVALID', 'Choose an exact branch, tag or commit.', 400)
    if type(result.get('expected_actor_id')) is not int or not 0 < result['expected_actor_id'] < 2**63:
        raise RepositoryLifecycleError('REPOSITORY_ACTOR_REQUIRED', 'Verify the GitHub account before this operation.', 400)
    if 'source_commit_sha' in result and (not isinstance(result['source_commit_sha'], str) or not SHA.fullmatch(result['source_commit_sha'])):
        raise RepositoryLifecycleError('REPOSITORY_COMMIT_INVALID', 'Use an exact source commit SHA.', 400)
    if importing:
        if result.get('owner_type') not in {'user', 'organization'} or result.get('visibility') not in {'private', 'public'} or result.get('copy_mode') != 'history':
            raise RepositoryLifecycleError('REPOSITORY_DESTINATION_REQUIRED', 'Choose the destination owner type, visibility and history copy mode.', 400)
        if (result['source_owner'].lower(), result['source_repo'].lower()) == (result['destination_owner'].lower(), result['destination_repo'].lower()):
            raise RepositoryLifecycleError('REPOSITORY_DESTINATION_EXISTS', 'An import requires a new destination repository.')
    return result


def ref_map(raw):
    result = {}
    for line in raw.decode('utf-8', 'strict').splitlines():
        parts = line.split('\t')
        if len(parts) != 2:
            raise RepositoryLifecycleError('REPOSITORY_REFS_INVALID', 'The Git reference inventory could not be verified.')
        sha, name = parts
        if (not SHA.fullmatch(sha) or not name.startswith(('refs/heads/', 'refs/tags/'))
                or len(name) > 400 or any(ord(char) < 33 for char in name) or name.endswith('^{}') or name in result):
            raise RepositoryLifecycleError('REPOSITORY_REFS_INVALID', 'The Git reference inventory could not be verified.')
        result[name] = sha
    if len(result) > MAX_REFS:
        raise RepositoryLifecycleError('REPOSITORY_LIMIT', 'This repository exceeds the host reference limit.')
    return result


def creation_push_arguments(destination, refs):
    """Each lease requires absence; an atomic failure cannot partially replace refs."""
    args = ['push', '--atomic', '--porcelain']
    args += ['--force-with-lease=' + name + ':' for name in sorted(refs)]
    args += [destination] + [sha + ':' + name for name, sha in sorted(refs.items())]
    if not refs or len(refs) > MAX_REFS or sum(len(arg.encode('utf-8')) + 3 for arg in args) > MAX_REF_ARGUMENT_BYTES:
        raise RepositoryLifecycleError('REPOSITORY_LIMIT', 'The complete reference set exceeds one safe atomic push.')
    return args


class SafeGit:
    """Bounded argv-only subprocesses. Output is inspected in memory, never logged."""
    def __init__(self, root):
        self.root = Path(root)
        self.executable = shutil.which('git')

    def available(self):
        return self.executable is not None

    def environment(self, token, lfs_url=None):
        env = {'PATH': os.path.dirname(self.executable) + os.pathsep + os.defpath,
               'HOME': str(self.root), 'LANG': 'C.UTF-8', 'GIT_TERMINAL_PROMPT': '0',
               'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull,
               'GIT_ATTR_NOSYSTEM': '1', 'GIT_LFS_SKIP_SMUDGE': '1'}
        if os.name == 'nt' and os.environ.get('SystemRoot'):
            env['SystemRoot'] = os.environ['SystemRoot']
        settings = [('credential.helper', ''), ('http.followRedirects', 'false'),
                    ('http.https://github.com/.extraheader', 'Authorization: Basic ' + base64.b64encode(('x-access-token:' + token).encode()).decode()),
                    ('protocol.allow', 'never'), ('protocol.https.allow', 'always'),
                    ('core.hooksPath', str(self.root / 'disabled-hooks')),
                    ('submodule.recurse', 'false'), ('fetch.recurseSubmodules', 'false'),
                    ('http.lowSpeedLimit', '1024'), ('http.lowSpeedTime', '60'),
                    ('filter.lfs.process', ''), ('filter.lfs.smudge', ''), ('filter.lfs.required', 'false'),
                    ('lfs.basictransfersonly', 'true'), ('lfs.standalonetransferagent', ''), ('lfs.allowincompletepush', 'false'), ('lfs.locksverify', 'false')]
        if lfs_url:
            settings += [('lfs.url', lfs_url + '/info/lfs'), ('lfs.pushurl', lfs_url + '/info/lfs')]
        env['GIT_CONFIG_COUNT'] = str(len(settings))
        for index, (key, value) in enumerate(settings):
            env['GIT_CONFIG_KEY_' + str(index)] = key
            env['GIT_CONFIG_VALUE_' + str(index)] = value
        return env

    @staticmethod
    def _kill(process):
        try:
            if os.name == 'posix':
                os.killpg(process.pid, signal.SIGKILL)
            else:
                taskkill = Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32' / 'taskkill.exe'
                try:
                    subprocess.run([str(taskkill), '/PID', str(process.pid), '/T', '/F'], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
        except ProcessLookupError:
            pass

    def disk_budget(self, cwd):
        root = self.root.resolve()
        relative = Path(cwd).resolve().relative_to(root)
        workspace = root / relative.parts[0] if relative.parts else root
        total, selected = 0, 0
        for directory, _, files in os.walk(root, followlinks=False):
            path = Path(directory)
            for name in files:
                candidate = path / name
                try:
                    size = candidate.lstat().st_size
                except FileNotFoundError:
                    continue
                total += size
                if candidate.is_relative_to(workspace):
                    selected += size
                if total > MAX_RETAINED_WORKSPACE_BYTES or selected > MAX_WORKSPACE_BYTES:
                    raise RepositoryLifecycleError('REPOSITORY_DISK_LIMIT', 'The bounded host repository workspace is full. No further Git work was started.')
        if shutil.disk_usage(root).free < MIN_FREE_BYTES:
            raise RepositoryLifecycleError('REPOSITORY_DISK_LIMIT', 'The gateway host needs more free disk space before this repository operation.')

    def run(self, args, cwd, token, *, timeout=300, maximum=4 * 1024 * 1024, input_bytes=None, lfs_url=None):
        if not self.executable:
            raise RepositoryLifecycleError('REPOSITORY_GIT_REQUIRED', 'Install Git on the gateway host before using repository operations.', 412)
        self.disk_budget(cwd)
        env = self.environment(token, lfs_url)
        relative = Path(cwd).resolve().relative_to(self.root.resolve())
        workspace = self.root / relative.parts[0] if relative.parts else self.root
        index = int(env['GIT_CONFIG_COUNT'])
        env.update(GIT_CONFIG_COUNT=str(index + 1))
        env['GIT_CONFIG_KEY_' + str(index)] = 'lfs.storage'
        env['GIT_CONFIG_VALUE_' + str(index)] = str(workspace / 'lfs-storage')
        process = subprocess.Popen([self.executable, *args], cwd=cwd, env=env,
                                   shell=False, stdin=subprocess.PIPE if input_bytes is not None else subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=os.name == 'posix')
        output, exceeded = bytearray(), threading.Event()

        def read(stream, retain):
            total = 0
            try:
                while block := stream.read(8192):
                    total += len(block)
                    if total > maximum:
                        exceeded.set()
                        self._kill(process)
                        break
                    if retain:
                        output.extend(block)
            finally:
                stream.close()

        readers = [threading.Thread(target=read, args=(process.stdout, True), daemon=True),
                   threading.Thread(target=read, args=(process.stderr, False), daemon=True)]
        for reader in readers:
            reader.start()

        def write():
            try:
                process.stdin.write(input_bytes)
                process.stdin.close()
            except (BrokenPipeError, OSError):
                pass
        if input_bytes is not None:
            threading.Thread(target=write, daemon=True).start()
        try:
            deadline, next_disk_check = time.monotonic() + timeout, 0.0
            while process.poll() is None:
                now = time.monotonic()
                if now >= deadline:
                    raise subprocess.TimeoutExpired('git', timeout)
                if now >= next_disk_check:
                    self.disk_budget(cwd)
                    next_disk_check = now + .5
                time.sleep(.05)
            code = process.wait()
            self.disk_budget(cwd)
        except subprocess.TimeoutExpired:
            self._kill(process)
            process.wait()
            raise RepositoryLifecycleError('REPOSITORY_GIT_TIMEOUT', 'The host Git operation exceeded its deadline.') from None
        except Exception:
            self._kill(process)
            process.wait()
            raise
        finally:
            for reader in readers:
                reader.join(timeout=2)
        if exceeded.is_set():
            raise RepositoryLifecycleError('REPOSITORY_LIMIT', 'The Git operation exceeded its bounded output limit.')
        if code:
            raise RepositoryLifecycleError('REPOSITORY_GIT_FAILED', 'The host Git operation did not finish successfully. Inspect the saved operation before continuing.')
        return bytes(output)


class RepositoryLifecycle:
    def __init__(self, service, git=None, start=None):
        self.service, self.store, self.backend = service, service.store, service.backend
        self.instance = uuid.uuid4().hex
        database = getattr(self.store.runtime, 'GATEWAY_JOB_DB_PATH', None)
        self.root = Path(database).resolve().parent / 'repository-workspaces' if database else None
        self.git = git or (SafeGit(self.root) if self.root else None)
        self.start = start or (lambda action: threading.Thread(target=action, daemon=True).start())
        self.slots = threading.BoundedSemaphore(2)
        self.lock = threading.Lock()
        self.active = set()
        self.heartbeat = None

    def capabilities(self):
        available = getattr(self.backend, 'available', None)
        configured = bool(self.root and self.git and self.git.available() and self.store.runtime.GATEWAY_DURABLE_JOBS and callable(available) and available())
        return {'schema': SCHEMA, 'enabled': configured, 'operations': ['clone_repository', 'import_repository'] if configured else [],
                'copy_modes': ['history'] if configured else [], 'requires_expected_actor_id': True,
                'remote_authorization_verified': False, 'max_source_size_kib': MAX_SOURCE_SIZE_KIB, 'max_refs': MAX_REFS,
                'max_workspace_bytes': MAX_WORKSPACE_BYTES, 'min_free_bytes': MIN_FREE_BYTES}

    def _tx(self, action):
        def run(db):
            db.execute('CREATE TABLE IF NOT EXISTS repository_lifecycle_operations ('
                       'id TEXT PRIMARY KEY, principal TEXT NOT NULL, request_digest TEXT NOT NULL, status TEXT NOT NULL, data TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS repository_lifecycle_instances (id TEXT PRIMARY KEY, heartbeat_ms INTEGER NOT NULL)')
            return action(db)
        return self.store.tx(run)

    def _touch_owner(self, db):
        db.execute('INSERT INTO repository_lifecycle_instances VALUES(?,?) ON CONFLICT(id) DO UPDATE SET heartbeat_ms=excluded.heartbeat_ms',
                   (self.instance, int(time.time() * 1000)))

    def _heartbeat_owner(self):
        stopping = getattr(self.backend, 'stopping', threading.Event())
        try:
            while not stopping.wait(5):
                with self.lock:
                    if not self.active:
                        if self.heartbeat is threading.current_thread():
                            self.heartbeat = None
                        return
                    self._tx(self._touch_owner)
        except Exception:
            # A lease cannot authorize writes. Missing heartbeats permit only
            # read-only reconciliation; revision fencing rejects a stale worker.
            pass
        finally:
            with self.lock:
                if self.heartbeat is threading.current_thread():
                    self.heartbeat = None

    def _finish_owner(self, operation_id):
        with self.lock:
            self.active.discard(operation_id)
            if not self.active:
                try:
                    self._tx(lambda db: db.execute('DELETE FROM repository_lifecycle_instances WHERE id=?', (self.instance,)))
                except Exception:
                    pass

    def _owner_active(self, row):
        if row.get('owner_instance') == self.instance:
            with self.lock:
                return row['operation_id'] in self.active
        def read(db):
            lease = db.execute('SELECT heartbeat_ms FROM repository_lifecycle_instances WHERE id=?', (row.get('owner_instance'),)).fetchone()
            return lease is not None and lease[0] >= int(time.time() * 1000) - OWNER_LEASE_MS
        return self._tx(read)

    def _get(self, owner, operation_id):
        def read(db):
            row = db.execute('SELECT data FROM repository_lifecycle_operations WHERE id=? AND principal=?', (operation_id, owner)).fetchone()
            return json.loads(row[0]) if row else None
        return self._tx(read)

    def _save(self, row, **values):
        updated = dict(row, **values, revision=row.get('revision', 0) + 1, observed_at_ms=int(time.time() * 1000))
        def save(db):
            current = db.execute('SELECT data FROM repository_lifecycle_operations WHERE id=? AND principal=?', (row['operation_id'], row['principal'])).fetchone()
            retained = json.loads(current[0]) if current else None
            if retained is None or retained.get('owner_instance') != row['owner_instance'] or retained.get('revision', 0) != row.get('revision', 0):
                raise RepositoryLifecycleError('REPOSITORY_OWNER_CHANGED', 'The saved operation owner changed. Reconcile its existing result.')
            persisted = {key: value for key, value in updated.items() if not key.startswith('_')}
            db.execute('UPDATE repository_lifecycle_operations SET data=?,status=? WHERE id=?', (canonical(persisted), updated['status'], row['operation_id']))
        self._tx(save)
        row.update(updated)

    def submit(self, owner, payload):
        payload = validate_request(payload)
        request_digest = hashlib.sha256(canonical([owner, payload]).encode()).hexdigest()
        operation_id = 'repoop_' + request_digest[:32]
        existing = self._get(owner, operation_id)
        if existing:
            return self.status(owner, operation_id)
        if not self.capabilities()['enabled']:
            raise RepositoryLifecycleError('REPOSITORY_HOST_UNAVAILABLE', 'Configure durable storage, Git and the host GitHub connection before this operation.', 412)
        self._authorize(owner)
        row = {'schema': SCHEMA, 'operation_id': operation_id, 'operation': payload['operation'], 'principal': owner,
               'request': payload, 'request_digest': request_digest, 'owner_instance': self.instance,
               'revision': 0,
               'status': 'PENDING', 'phase': 'ACCEPTED', 'remote_state_verified': False, 'remote_write_started': False,
               'source_repository': payload['source_owner'] + '/' + payload['source_repo'], 'observed_at_ms': int(time.time() * 1000)}
        if payload['operation'] == 'import_repository':
            row.update(destination_repository=payload['destination_owner'] + '/' + payload['destination_repo'],
                       visibility=payload['visibility'], copy_mode='history', history_preserved=False)

        def accept(db):
            existing = db.execute('SELECT data FROM repository_lifecycle_operations WHERE id=?', (operation_id,)).fetchone()
            if existing:
                return json.loads(existing[0]), False
            if db.execute('SELECT COUNT(*) FROM repository_lifecycle_operations').fetchone()[0] >= 10000:
                raise RepositoryLifecycleError('REPOSITORY_JOURNAL_FULL', 'The repository-operation journal requires host maintenance.', 503)
            if db.execute("SELECT COUNT(*) FROM repository_lifecycle_operations WHERE status IN ('PENDING','RUNNING')").fetchone()[0] >= 8:
                raise RepositoryLifecycleError('REPOSITORY_QUEUE_FULL', 'Wait for the saved repository operations to finish before adding another.', 429)
            db.execute('INSERT INTO repository_lifecycle_operations VALUES(?,?,?,?,?)', (operation_id, owner, request_digest, row['status'], canonical(row)))
            self._touch_owner(db)
            return row, True
        with self.lock:
            accepted, created = self._tx(accept)
            if created:
                self.active.add(operation_id)
        if created:
            try:
                with self.lock:
                    if self.heartbeat is None:
                        self.heartbeat = threading.Thread(target=self._heartbeat_owner, daemon=True)
                        try:
                            self.heartbeat.start()
                        except Exception:
                            self.heartbeat = None
                            raise
                self.start(lambda: self._run(accepted))
            except Exception:
                self._finish_owner(operation_id)
                self._save(accepted, status='BLOCKED', error_code='REPOSITORY_WORKER_UNAVAILABLE', message='The host could not start this saved repository operation.')
        return self.public(accepted)

    def status(self, owner, operation_id):
        if not isinstance(operation_id, str) or not re.fullmatch(r'repoop_[a-f0-9]{32}', operation_id):
            raise RepositoryLifecycleError('REPOSITORY_OPERATION_UNKNOWN', 'The saved repository operation is unavailable.', 404)
        row = self._get(owner, operation_id)
        if row is None:
            raise RepositoryLifecycleError('REPOSITORY_OPERATION_UNKNOWN', 'The saved repository operation is unavailable.', 404)
        self._authorize(owner)
        if row['status'] in {'PENDING', 'RUNNING'} and not self._owner_active(row):
            # An expired process lease permits reconciliation, never redispatch.
            self._save(row, status='RECONCILE_REQUIRED' if row.get('remote_write_started') else 'BLOCKED',
                       error_code='REPOSITORY_PROCESS_INTERRUPTED', message='This operation was interrupted. Its original intent is retained and will not be submitted again.')
        was_verified = row['status'] == 'VERIFIED' or row.get('verified_once') is True
        reconcile_push = row['status'] == 'RECONCILE_REQUIRED' and row.get('phase') in {'REFS_PUSH_DISPATCHED', 'DEFAULT_BRANCH_DISPATCHED', 'VERIFYING'}
        if was_verified or reconcile_push:
            if not self.slots.acquire(blocking=False):
                raise RepositoryLifecycleError('REPOSITORY_VERIFICATION_BUSY', 'The host is busy. Read this saved operation again to obtain fresh verification.', 503)
            try:
                row['_deadline'] = time.monotonic() + 300
                token = self._credentials(row)
                self._verify_source(row, token)
                if row['operation'] == 'import_repository':
                    self._verify_import(row, token)
                else:
                    self._verify_clone(row, token)
                updates = {'history_preserved': True} if row['operation'] == 'import_repository' else {}
                self._save(row, status='VERIFIED', phase='VERIFIED', remote_state_verified=True, verified_once=True, error_code=None, message=None, **updates)
            except (RepositoryLifecycleError, requests.RequestException, OSError, ValueError, KeyError, TypeError) as error:
                if was_verified:
                    safe = error if isinstance(error, RepositoryLifecycleError) else RepositoryLifecycleError('REPOSITORY_VERIFICATION_UNAVAILABLE', 'The original repository result could not be freshly verified.')
                    updates = {'history_preserved': False} if row['operation'] == 'import_repository' else {}
                    self._save(row, status='BLOCKED', phase='REVERIFY_FAILED', remote_state_verified=False, error_code=safe.code, message=str(safe), **updates)
            finally:
                self.slots.release()
        return self.public(row)

    @staticmethod
    def public(row):
        names = {'schema', 'operation_id', 'request_digest', 'operation', 'status', 'phase', 'source_repository', 'source_commit_sha',
                 'source_tree_sha', 'destination_tree_sha', 'destination_repository', 'repository_id', 'visibility',
                 'copy_mode', 'history_preserved', 'ref_count', 'refs_sha256', 'workspace_id', 'checked_out_sha',
                 'remote_url', 'remote_state_verified', 'observed_at_ms', 'error_code', 'message', 'lfs_objects'}
        return {name: value for name, value in row.items() if name in names and value is not None}

    def _authorize(self, owner):
        if not self.service.authorized({'principal': owner}):
            raise RepositoryLifecycleError('REPOSITORY_PERMISSION_REVOKED', 'Gateway engineering permission is no longer available.', 403)
        if getattr(self.backend, 'stopping', threading.Event()).is_set():
            raise RepositoryLifecycleError('REPOSITORY_HOST_STOPPING', 'The gateway is stopping. Inspect this operation after reconnecting.', 503)

    def _api(self, path, token, method='GET', body=None):
        headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
        with requests.request(method, 'https://api.github.com' + path, headers=headers, json=body, timeout=(10, 60), allow_redirects=False, stream=True) as response:
            if not 200 <= response.status_code < 300:
                raise RepositoryLifecycleError('REPOSITORY_HTTP_REJECTED', 'GitHub did not admit the exact repository operation. Verify its target and account.', response.status_code)
            value = self.backend.read_response(response, False)
            if not isinstance(value, dict):
                raise RepositoryLifecycleError('REPOSITORY_RESPONSE_INVALID', 'The GitHub response could not be verified.')
            return value

    def _credentials(self, row):
        self._authorize(row['principal'])
        token = self.backend.token()
        actor = self._api('/user', token)
        if actor.get('id') != row['request']['expected_actor_id']:
            raise RepositoryLifecycleError('REPOSITORY_ACTOR_CHANGED', 'The gateway host GitHub account differs from the approved account.', 403)
        if row['operation'] == 'import_repository' and row['request']['owner_type'] == 'user' and str(actor.get('login', '')).lower() != row['request']['destination_owner'].lower():
            raise RepositoryLifecycleError('REPOSITORY_OWNER_CHANGED', 'The approved destination owner is not the authenticated GitHub user.', 403)
        return token

    @staticmethod
    def _url(repository):
        return 'https://github.com/' + repository + '.git'

    @staticmethod
    def _root(repository):
        return '/repos/' + '/'.join(quote(part, safe='') for part in repository.split('/'))

    def _workspace(self, operation_id, create=False):
        if self.root is None:
            raise RepositoryLifecycleError('REPOSITORY_STORAGE_REQUIRED', 'Configure a durable gateway workspace.', 412)
        def link(path):
            return path.is_symlink() or getattr(path, 'is_junction', lambda: False)()
        if link(self.root):
            raise RepositoryLifecycleError('REPOSITORY_WORKSPACE_INVALID', 'The host repository workspace must not be a filesystem link.')
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        hooks = self.root / 'disabled-hooks'
        if link(hooks):
            raise RepositoryLifecycleError('REPOSITORY_WORKSPACE_INVALID', 'The host hooks boundary is unavailable.')
        hooks.mkdir(mode=0o700, exist_ok=True)
        if any(hooks.iterdir()):
            raise RepositoryLifecycleError('REPOSITORY_WORKSPACE_INVALID', 'The host hooks boundary must be empty.')
        workspace = self.root / operation_id
        if link(workspace):
            raise RepositoryLifecycleError('REPOSITORY_WORKSPACE_INVALID', 'The saved workspace is not an isolated directory.')
        if create:
            try:
                workspace.mkdir(mode=0o700)
            except FileExistsError:
                raise RepositoryLifecycleError('REPOSITORY_WORKSPACE_EXISTS', 'The saved workspace already exists and will not be overwritten.') from None
        return workspace

    def _git(self, row, args, cwd, token, **kwargs):
        self._authorize(row['principal'])
        remaining = row.get('_deadline', time.monotonic() + 300) - time.monotonic()
        if remaining <= 0:
            raise RepositoryLifecycleError('REPOSITORY_DEADLINE', 'The repository operation exceeded its total deadline.')
        return self.git.run(args, cwd, token, timeout=min(300, remaining), **kwargs)

    def _source(self, row, token, workspace):
        root = self._root(row['source_repository'])
        source = self._api(root, token)
        if (str(source.get('full_name', '')).lower() != row['source_repository'].lower() or type(source.get('id')) is not int or source['id'] <= 0
                or type(source.get('size')) is not int or not 0 <= source['size'] <= MAX_SOURCE_SIZE_KIB):
            raise RepositoryLifecycleError('REPOSITORY_SOURCE_UNVERIFIED', 'The exact source identity or supported repository size could not be verified.')
        commit = self._api(root + '/commits/' + quote(row['request']['source_ref'], safe=''), token)
        sha, tree = commit.get('sha'), commit.get('commit', {}).get('tree', {}).get('sha')
        if not isinstance(sha, str) or not SHA.fullmatch(sha) or not isinstance(tree, str) or not SHA.fullmatch(tree) or row['request'].get('source_commit_sha', sha) != sha:
            raise RepositoryLifecycleError('REPOSITORY_SOURCE_CHANGED', 'The requested source commit could not be verified. Resolve it again before importing.')
        self._save(row, source_repository_id=source['id'], source_commit_sha=sha, source_tree_sha=tree, source_default_branch=source.get('default_branch'), phase='SOURCE_VERIFIED')
        target = workspace / ('mirror.git' if row['operation'] == 'import_repository' else 'checkout')
        args = ['clone', '--mirror' if row['operation'] == 'import_repository' else '--no-checkout', '--no-recurse-submodules', '--', self._url(row['source_repository']), str(target)]
        self._git(row, args, workspace, token)
        ref = row['request']['source_ref']
        if row['operation'] == 'clone_repository' and not SHA.fullmatch(ref) and not ref.startswith('refs/tags/'):
            branch = ref.removeprefix('refs/heads/')
            candidates = ['refs/remotes/origin/' + branch, ref]
        else:
            candidates = [ref]
        resolved = None
        for candidate in candidates:
            try:
                resolved = self._git(row, ['rev-parse', '--verify', '--end-of-options', candidate + '^{commit}'], target, token).decode().strip()
                break
            except RepositoryLifecycleError as error:
                if error.code != 'REPOSITORY_GIT_FAILED':
                    raise
        actual_tree = self._git(row, ['rev-parse', '--verify', sha + '^{tree}'], target, token).decode().strip()
        if resolved != sha or actual_tree != tree:
            raise RepositoryLifecycleError('REPOSITORY_SOURCE_CHANGED', 'The source changed between GitHub inspection and Git fetch. No destination was created.')
        return target

    def _lfs(self, row, checkout, token):
        if row.get('source_refs'):
            revisions = ('\n'.join(sorted(set(row['source_refs'].values()))) + '\n').encode()
            reachable = self._git(row, ['rev-list', '--objects', '--no-object-names', '--stdin'], checkout, token, input_bytes=revisions)
            objects = self._git(row, ['cat-file', '--batch-check=%(objectname) %(objecttype) %(objectsize)'], checkout, token, input_bytes=reachable)
        else:
            objects = self._git(row, ['cat-file', '--batch-all-objects', '--batch-check=%(objectname) %(objecttype) %(objectsize)'], checkout, token)
        small = []
        for line in objects.decode('ascii', 'strict').splitlines():
            sha, kind, size = line.split(' ')
            if not SHA.fullmatch(sha) or not size.isdigit():
                raise RepositoryLifecycleError('REPOSITORY_OBJECTS_INVALID', 'The source object inventory could not be verified.')
            if kind == 'blob' and 80 <= int(size) <= 4096:
                small.append(sha)
        if not small:
            return 0
        raw = self._git(row, ['cat-file', '--batch'], checkout, token, input_bytes=('\n'.join(small) + '\n').encode(), maximum=8 * 1024 * 1024)
        position, pointers, by_blob = 0, {}, {}
        for expected in small:
            end = raw.index(b'\n', position)
            sha, kind, size = raw[position:end].decode('ascii').split(' ')
            length = int(size)
            if sha != expected or kind != 'blob' or not 80 <= length <= 4096 or end + 1 + length >= len(raw):
                raise RepositoryLifecycleError('REPOSITORY_OBJECTS_INVALID', 'The source object contents could not be verified.')
            body = raw[end + 1:end + 1 + length]
            position = end + 2 + length
            if body.startswith(b'version https://git-lfs.github.com/spec/'):
                normalized = body.replace(b'\r\n', b'\n')
                match = re.findall(rb'^oid sha256:([a-f0-9]{64})$', normalized, re.MULTILINE)
                size_match = re.findall(rb'^size ([0-9]{1,16})$', normalized, re.MULTILINE)
                if not normalized.startswith(b'version https://git-lfs.github.com/spec/v1\n') or len(match) != 1 or len(size_match) != 1:
                    raise RepositoryLifecycleError('REPOSITORY_LFS_INVALID', 'A Git LFS pointer is malformed; the destination was not created.')
                oid, size = match[0].decode(), int(size_match[0])
                if oid in pointers and pointers[oid] != size:
                    raise RepositoryLifecycleError('REPOSITORY_LFS_INVALID', 'Git LFS object sizes disagree in the source history.')
                pointers[oid] = size
                by_blob[sha] = {'oid': oid, 'size': size}
        if pointers:
            if sum(pointers.values()) > MAX_WORKSPACE_BYTES:
                raise RepositoryLifecycleError('REPOSITORY_DISK_LIMIT', 'The source LFS objects exceed this host workspace budget. No destination was created.')
            try:
                version = self._git(row, ['lfs', 'version'], checkout, token, maximum=4096)
            except RepositoryLifecycleError:
                raise RepositoryLifecycleError('REPOSITORY_LFS_REQUIRED', 'Install Git LFS on the gateway host before copying this repository. No destination was created.', 412) from None
            require_safe_lfs_version(version)
            self._git(row, ['lfs', 'fetch', '--all', 'origin', *sorted(row.get('source_refs', {}))], checkout, token, lfs_url=self._url(row['source_repository']))
            objects = self._workspace(row['operation_id']) / 'lfs-storage' / 'objects'
            for oid, size in pointers.items():
                self._verify_lfs_file(row, objects / oid[:2] / oid[2:4] / oid, oid, size)
            self._save(row, lfs_pointers=by_blob)
        return len(pointers)

    def _verify_lfs_file(self, row, path, expected, size):
        if path.is_symlink() or not path.is_file() or path.stat().st_size != size:
            raise RepositoryLifecycleError('REPOSITORY_LFS_UNVERIFIED', 'A source LFS object is missing or has a different size.')
        hasher = hashlib.sha256()
        with path.open('rb') as stream:
            while block := stream.read(1024 * 1024):
                if time.monotonic() > row.get('_deadline', float('inf')):
                    raise RepositoryLifecycleError('REPOSITORY_DEADLINE', 'LFS verification exceeded the operation deadline.')
                hasher.update(block)
        if hasher.hexdigest() != expected:
            raise RepositoryLifecycleError('REPOSITORY_LFS_UNVERIFIED', 'A source LFS object does not match its recorded SHA-256.')

    def _verify_source(self, row, token):
        source = self._api(self._root(row['source_repository']), token)
        if source.get('id') != row['source_repository_id'] or str(source.get('full_name', '')).lower() != row['source_repository'].lower():
            raise RepositoryLifecycleError('REPOSITORY_SOURCE_CHANGED', 'The original source repository identity has changed.')
        commit = self._api(self._root(row['source_repository']) + '/commits/' + row['source_commit_sha'], token)
        if commit.get('sha') != row['source_commit_sha'] or commit.get('commit', {}).get('tree', {}).get('sha') != row['source_tree_sha']:
            raise RepositoryLifecycleError('REPOSITORY_SOURCE_CHANGED', 'The exact original source commit is no longer verified.')

    def _verify_clone(self, row, token):
        checkout = self._workspace(row['operation_id']) / 'checkout'
        actual = self._git(row, ['rev-parse', 'HEAD'], checkout, token).decode().strip()
        remote = self._git(row, ['remote', 'get-url', 'origin'], checkout, token).decode().strip()
        if actual != row['source_commit_sha'] or remote != self._url(row['source_repository']):
            raise RepositoryLifecycleError('REPOSITORY_CLONE_UNVERIFIED', 'The saved checkout does not match the approved source.')
        changes = self._git(row, ['diff', '--name-only', '-z', '--no-ext-diff', '--no-textconv', '--ignore-submodules=all', 'HEAD', '--'], checkout, token)
        if changes:
            tree = self._git(row, ['ls-tree', '-r', '-z', 'HEAD'], checkout, token)
            entries = {}
            for entry in tree.split(b'\0'):
                if entry:
                    prefix, name = entry.split(b'\t', 1)
                    mode, _, sha = prefix.decode('ascii').split(' ')
                    entries[os.fsdecode(name)] = (mode, sha)
            for name in changes.split(b'\0'):
                if not name:
                    continue
                relative = os.fsdecode(name)
                mode, sha = entries.get(relative, (None, None))
                pointer = row.get('lfs_pointers', {}).get(sha)
                path = checkout / relative
                if (pointer is None or mode not in {'100644', '100755'} or not path.resolve().is_relative_to(checkout.resolve())
                        or path.is_symlink() or not path.is_file() or os.name == 'posix' and bool(path.stat().st_mode & stat.S_IXUSR) != (mode == '100755')):
                    raise RepositoryLifecycleError('REPOSITORY_CLONE_CHANGED', 'The saved checkout has tracked changes and is no longer an exact source copy.')
                self._verify_lfs_file(row, path, pointer['oid'], pointer['size'])
        return actual, remote

    def _destination(self, row, token):
        value = self._api(self._root(row['destination_repository']), token)
        if (value.get('id') != row.get('repository_id') or str(value.get('full_name', '')).lower() != row['destination_repository'].lower()
                or value.get('private') is not (row['visibility'] == 'private') or value.get('visibility', row['visibility']) != row['visibility']):
            raise RepositoryLifecycleError('REPOSITORY_DESTINATION_CHANGED', 'The destination identity or visibility differs from the saved operation.')
        return value

    def _verify_import(self, row, token):
        if not row.get('source_refs') or row.get('lfs_objects', 0) and not row.get('lfs_uploaded'):
            raise RepositoryLifecycleError('REPOSITORY_IMPORT_UNVERIFIED', 'The source reference or LFS receipt is incomplete.')
        destination = self._destination(row, token)
        workspace = self._workspace(row['operation_id'])
        actual = ref_map(self._git(row, ['ls-remote', '--refs', '--heads', '--tags', self._url(row['destination_repository'])], workspace, token))
        if actual != row['source_refs']:
            raise RepositoryLifecycleError('REPOSITORY_IMPORT_UNVERIFIED', 'The destination heads and tags do not match the saved source inventory.')
        commit = self._api(self._root(row['destination_repository']) + '/git/commits/' + row['source_commit_sha'], token)
        if commit.get('sha') != row['source_commit_sha'] or commit.get('tree', {}).get('sha') != row['source_tree_sha']:
            raise RepositoryLifecycleError('REPOSITORY_IMPORT_UNVERIFIED', 'The destination commit and tree do not match the saved source.')
        default = row.get('source_default_branch')
        if default and 'refs/heads/' + default in actual and destination.get('default_branch') != default:
            raise RepositoryLifecycleError('REPOSITORY_IMPORT_UNVERIFIED', 'The destination default branch has not been verified.')
        row['destination_tree_sha'] = commit['tree']['sha']

    def _snapshot_refs(self, row, checkout, token):
        refs = ref_map(self._git(row, ['for-each-ref', '--format=%(objectname)%09%(refname)', 'refs/heads/', 'refs/tags/'], checkout, token))
        creation_push_arguments(self._url(row['destination_repository']), refs)
        containing = self._git(row, ['for-each-ref', '--contains=' + row['source_commit_sha'], '--format=%(refname)', 'refs/heads/', 'refs/tags/'], checkout, token).decode().strip()
        if not containing:
            raise RepositoryLifecycleError('REPOSITORY_SOURCE_NOT_EXPORTED', 'The selected commit is not reachable from the source heads or tags. Choose a source ref that can be preserved before creating the destination.')
        self._save(row, source_refs=refs, ref_count=len(refs), refs_sha256=hashlib.sha256(canonical(refs).encode()).hexdigest())

    def _import(self, row, checkout, token):
        refs = row['source_refs']
        destination = self._url(row['destination_repository'])
        push = creation_push_arguments(destination, refs)
        token = self._credentials(row)
        try:
            self._api(self._root(row['destination_repository']), token)
        except RepositoryLifecycleError as error:
            if error.status != 404:
                raise
        else:
            raise RepositoryLifecycleError('REPOSITORY_DESTINATION_EXISTS', 'The destination already exists and will not be overwritten.')
        path = '/user/repos' if row['request']['owner_type'] == 'user' else '/orgs/' + quote(row['request']['destination_owner'], safe='') + '/repos'
        self._save(row, phase='CREATE_PREPARED')
        self._authorize(row['principal'])
        self._save(row, phase='CREATE_DISPATCHED', remote_write_started=True)
        self._authorize(row['principal'])
        created = self._api(path, token, 'POST', {'name': row['request']['destination_repo'], 'private': row['visibility'] == 'private', 'auto_init': False})
        if type(created.get('id')) is not int or created['id'] <= 0:
            raise RepositoryLifecycleError('REPOSITORY_CREATE_UNVERIFIED', 'The repository creation response has no verifiable identity.')
        self._save(row, repository_id=created['id'], phase='CREATED')
        self._destination(row, token)
        if ref_map(self._git(row, ['ls-remote', '--refs', '--heads', '--tags', destination], checkout, token)):
            raise RepositoryLifecycleError('REPOSITORY_DESTINATION_NOT_EMPTY', 'The newly created destination now contains references and will not be overwritten.')
        if row['lfs_objects']:
            token = self._credentials(row)
            self._save(row, phase='LFS_PUSH_DISPATCHED')
            self._git(row, ['lfs', 'push', '--all', destination, *sorted(refs)], checkout, token, lfs_url=destination)
            self._save(row, lfs_uploaded=True, phase='LFS_UPLOADED')
        token = self._credentials(row)
        self._destination(row, token)
        self._save(row, phase='REFS_PUSH_DISPATCHED')
        self._git(row, push, checkout, token)
        default = row.get('source_default_branch')
        if default and 'refs/heads/' + default in refs:
            token = self._credentials(row)
            self._destination(row, token)
            self._save(row, phase='DEFAULT_BRANCH_DISPATCHED')
            self._authorize(row['principal'])
            self._api(self._root(row['destination_repository']), token, 'PATCH', {'default_branch': default})
        self._save(row, phase='VERIFYING')
        self._verify_import(row, self._credentials(row))
        self._save(row, status='VERIFIED', phase='VERIFIED', remote_state_verified=True, history_preserved=True, verified_once=True)

    def _run(self, row):
        try:
            with self.slots:
                row['_deadline'] = time.monotonic() + 1200
                self._save(row, status='RUNNING', phase='SOURCE_PRECHECK')
                token = self._credentials(row)
                workspace = self._workspace(row['operation_id'], create=True)
                checkout = self._source(row, token, workspace)
                if row['operation'] == 'import_repository':
                    self._snapshot_refs(row, checkout, token)
                lfs = self._lfs(row, checkout, token)
                self._save(row, lfs_objects=lfs)
                if row['operation'] == 'import_repository':
                    self._import(row, checkout, token)
                else:
                    self._git(row, ['checkout', '--detach', row['source_commit_sha'], '--'], checkout, token)
                    if lfs:
                        self._git(row, ['lfs', 'checkout'], checkout, token, lfs_url=self._url(row['source_repository']))
                    actual, remote = self._verify_clone(row, token)
                    self._save(row, status='VERIFIED', phase='VERIFIED', workspace_id=row['operation_id'], checked_out_sha=actual, remote_url=remote, remote_state_verified=True, verified_once=True)
        except Exception as error:
            unknown = row.get('remote_write_started', False)
            safe = error if isinstance(error, RepositoryLifecycleError) else RepositoryLifecycleError('REPOSITORY_OPERATION_INTERRUPTED', 'The operation could not finish. Inspect its saved receipt before continuing.')
            try:
                self._save(row, status='RECONCILE_REQUIRED' if unknown else 'BLOCKED', error_code=safe.code, message=str(safe), remote_state_verified=False)
            except Exception:
                # A failed journal write cannot prove absence or permit replay.
                # Status detects the inactive owner and keeps the saved dispatch.
                pass
        finally:
            self._finish_owner(row['operation_id'])
