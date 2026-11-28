"""Durable, revision-scoped source indexing independent of a phone screen.

Only source reads and local index checkpoints run here. Every batch rechecks the
gateway principal, GitHub identity, repository access, and immutable revision.
"""
import json
import logging
import re
import threading
import time
import uuid

import requests

from v14.engineering_crypto import EngineeringCipher
from v14.github_api_governor import GitHubApiGovernor
from .extract import identity


TERMINAL = frozenset({'COMPLETED', 'CANCELLED', 'FAILED'})
LEASE_SECONDS = 300
MAX_BATCHES = 512
MAX_JOBS = 1024


class GraphIndexJobs:
    def __init__(self, service, *, now=time.time):
        self.service = service
        self.store = service.store.store
        self.now = now
        self.kind = 'public' if service.public else 'authenticated'
        self.owner = uuid.uuid4().hex
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.guard = threading.Lock()
        self.admission_guard = threading.RLock()
        self.active_batches = 0
        self.thread = None

    def _admission_lock(self):
        state = getattr(self.store.runtime, 'gateway_service', None)
        return state.lock if state is not None else self.admission_guard

    def _draining(self):
        state = getattr(self.store.runtime, 'gateway_service', None)
        return self.stop.is_set() or bool(getattr(self.store.runtime, 'gateway_draining', False)) or bool(getattr(state, 'draining', False))

    def snapshot(self):
        # Keep physical work visible even after cancel/pause changes its durable
        # row. Maintenance holds this same lock through its idle/admission fence.
        with self._admission_lock():
            return {'activeIndexBatches': self.active_batches}

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS code_graph_index_jobs ('
                   'id TEXT PRIMARY KEY, namespace TEXT NOT NULL, kind TEXT NOT NULL, '
                   'state TEXT NOT NULL, updated REAL NOT NULL, next_at REAL NOT NULL, '
                   'lease_until REAL NOT NULL, payload TEXT NOT NULL)')

    def _transaction(self, operation):
        def transact(db):
            self.schema(db)
            return operation(db)
        return self.store.tx(transact)

    @staticmethod
    def _read(db, key):
        row = db.execute('SELECT payload FROM code_graph_index_jobs WHERE id=?', (key,)).fetchone()
        return json.loads(EngineeringCipher.decrypt(db, 'graph-index:' + key, row[0])) if row else None

    @staticmethod
    def _write(db, job):
        payload = EngineeringCipher.encrypt(db, 'graph-index:' + job['id'], json.dumps(job, separators=(',', ':')))
        db.execute('INSERT OR REPLACE INTO code_graph_index_jobs VALUES(?,?,?,?,?,?,?,?)',
                   (job['id'], job['namespace'], job['kind'], job['state'], job['updated_at'],
                    job.get('next_at', 0), job.get('lease_until', 0), payload))

    def _scope(self, principal, request):
        if not getattr(self.service, 'authorized', lambda value: True)(principal):
            raise PermissionError('Repository map access was revoked')
        namespace, _, _, _ = self.service.authorize(principal, request)
        return namespace, 'graph_index_' + identity('durable-index-v1', namespace, request['commit_sha'])

    @staticmethod
    def _request(request):
        return {name: request[name] for name in ('repository', 'branch', 'commit_sha')}

    @staticmethod
    def view(job):
        if not job:
            return None
        fields = ('id', 'state', 'generation', 'request_id', 'accepted_request_ids', 'created_at', 'updated_at',
                  'completed_files', 'total_files', 'failed_files', 'unsupported_files', 'partial_files',
                  'coverage_complete', 'processing_complete', 'batches', 'error_code', 'retry_at')
        return {name: job.get(name) for name in fields}

    def status(self, principal, request):
        _, key = self._scope(principal, request)
        return self.view(self._transaction(lambda db: self._read(db, key)))

    def handle(self, principal, request):
        with self._admission_lock():
            if self._draining():
                raise TimeoutError('Repository indexing admission is draining')
        namespace, key = self._scope(principal, request)
        action = request.get('job_action', 'start')
        if action not in {'start', 'cancel', 'pause', 'resume'}:
            raise ValueError('Unknown index job action')
        if action != 'start' and request.get('job_id') != key:
            raise ValueError('Index job does not match this repository revision')
        generation = request.get('job_generation')
        if action != 'start' and (type(generation) is not int or generation < 1):
            raise ValueError('An observed index job generation is required')
        request_id = request.get('request_id') or identity('graph-index-request', key, action, bool(request.get('retry_failed')), generation)
        if not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', request_id):
            raise ValueError('Invalid index request identity')
        retry_failed = request.get('retry_failed') is True
        request_digest = identity(namespace, self._request(request), action, retry_failed, generation)
        now = self.now()

        def mutate(db):
            job = self._read(db, key)
            retry_at = (job or {}).get('retry_at')
            rate_wait = {'retry_at': retry_at, 'rate_wait_code': job.get('rate_wait_code', job.get('error_code'))} \
                if retry_at is not None and retry_at > now else None
            if job and request_id in job.get('request_digests', {}):
                if job['request_digests'][request_id] != request_digest:
                    raise ValueError('Index request identity was reused for a different operation')
                return job
            if action != 'start':
                if not job:
                    raise ValueError('Index job is unavailable')
                if generation != job['generation']:
                    raise ValueError('Index job generation changed; inspect status before continuing')
                if action == 'cancel':
                    job.update(state='CANCELLED', lease=None, lease_until=0, error_code=None)
                elif action == 'pause' and job['state'] not in TERMINAL:
                    job.update(state='PAUSED', lease=None, lease_until=0)
                elif action == 'resume' and job['state'] in {'PAUSED', 'INTERRUPTED', 'FAILED'}:
                    job.update(state='QUEUED', lease=None, lease_until=0, next_at=now, error_code=None, attempts=0,
                               deadline=now + 24 * 3600, batches=0, generation=job['generation'] + 1, retry_at=None)
            elif not job or job['state'] in TERMINAL or job['state'] in {'PAUSED', 'INTERRUPTED'}:
                if not job and db.execute('SELECT COUNT(*) FROM code_graph_index_jobs').fetchone()[0] >= MAX_JOBS:
                    raise ValueError('Saved repository index job capacity reached')
                # A fresh request reconciles the current snapshot in the worker.
                # A historical completion cannot prove an evicted or invalidated
                # snapshot still exists. Replays were returned above without work.
                job = {'id': key, 'namespace': namespace, 'kind': self.kind, 'principal': principal,
                       'request': self._request(request), 'state': 'QUEUED', 'generation': (job or {}).get('generation', 0) + 1,
                       'created_at': now, 'updated_at': now, 'next_at': now, 'lease': None, 'lease_until': 0,
                       'deadline': now + 24 * 3600, 'batches': 0, 'attempts': 0, 'retry_pending': retry_failed,
                       'retry_outcome_unknown': False, 'retry_baseline': None,
                       'request_digests': {}, 'completed_files': None, 'total_files': None, 'failed_files': None,
                       'unsupported_files': None, 'partial_files': None, 'processing_complete': False,
                       'coverage_complete': None, 'error_code': None, 'retry_at': None, 'rate_wait_code': None}
            if rate_wait and job['state'] == 'QUEUED':
                # Control requests may renew the work budget, but cannot erase an
                # observed rate deadline after the in-memory governor restarts.
                job.update(rate_wait, next_at=min(retry_at, job['deadline']), error_code=rate_wait['rate_wait_code'])
            job['request_digests'][request_id] = request_digest
            job['request_digests'] = dict(list(job['request_digests'].items())[-16:])
            job.update(request_id=request_id, accepted_request_ids=list(job['request_digests']), updated_at=now)
            self._write(db, job)
            return job

        # Authorization can perform bounded network reads. Recheck admission
        # after those reads, before saving or starting any accepted work.
        with self._admission_lock():
            if self._draining():
                raise TimeoutError('Repository indexing admission is draining')
            job = self._transaction(mutate)
            maintenance = getattr(self.service, '_maintenance', None)
            if maintenance is not None and action == 'start' and not request.get('maintenance_request'):
                maintenance.observe(principal, namespace, request, request['commit_sha'])
            self.start()
            self.wake.set()
        # The receipt is sufficient to reconcile acceptance even before the first
        # source batch creates a snapshot. Graph coverage still comes from status.
        return {'repository': request['repository'], 'branch': request['branch'], 'commit_sha': request['commit_sha'],
                'status': 'INDEX_JOB_ACCEPTED', 'index_job': self.view(job), 'background': True}

    def start(self):
        with self.guard:
            if self.thread and self.thread.is_alive():
                return
            self.stop.clear()
            self.thread = threading.Thread(target=self._run, name='repository-index-' + self.kind, daemon=True)
            self.thread.start()

    def close(self):
        with self._admission_lock():
            self.stop.set()
            self.wake.set()
        if self.thread and self.thread is not threading.current_thread():
            # HTTP/source reads are bounded. Retain the backend and journal
            # until that work has actually settled, including cancellation.
            self.thread.join()
        # A later lifecycle can disable durable storage after this worker was
        # created. Stop observation without initializing or writing a disabled
        # journal; a retained lease will reconcile after storage is available.
        if not self.store.runtime.GATEWAY_DURABLE_JOBS or not self.store.runtime.durable_job_initialized:
            return
        def release(db):
            for key, in db.execute("SELECT id FROM code_graph_index_jobs WHERE kind=? AND state='RUNNING'", (self.kind,)).fetchall():
                job = self._read(db, key)
                if job.get('owner') == self.owner:
                    job.update(state='INTERRUPTED', lease=None, lease_until=0, updated_at=self.now(), next_at=self.now())
                    self._write(db, job)
        self._transaction(release)

    def _take(self):
        now = self.now()
        def take(db):
            row = db.execute("SELECT id FROM code_graph_index_jobs WHERE kind=? AND next_at<=? AND "
                             "(state IN ('QUEUED','INTERRUPTED') OR (state='RUNNING' AND lease_until<=?)) "
                             'ORDER BY updated,id LIMIT 1', (self.kind, now, now)).fetchone()
            if not row:
                return None
            job = self._read(db, row[0])
            if now >= job['deadline'] or job['batches'] >= MAX_BATCHES:
                job.update(state='PAUSED', error_code='INDEX_BUDGET_REACHED', updated_at=now, lease=None, lease_until=0)
                self._write(db, job)
                return None
            if (job.get('retry_at') or 0) > now:
                # Shutdown can retain an observation before it releases the
                # execution lease. Never let an interrupted/expired lease bypass
                # that wait, even when its older next_at is already due.
                job.update(next_at=min(job['retry_at'], job['deadline']))
                self._write(db, job)
                return None
            job.update(state='RUNNING', owner=self.owner, lease=uuid.uuid4().hex, lease_until=now + LEASE_SECONDS,
                       updated_at=now)
            self._write(db, job)
            return job
        with self._admission_lock():
            if self._draining():
                return None
            return self._transaction(take)

    def _finish(self, claimed, **changes):
        def finish(db):
            job = self._read(db, claimed['id'])
            if not job or job.get('lease') != claimed['lease'] or job['state'] != 'RUNNING':
                return
            updates = dict(changes)
            now = self.now()
            retained_wait = job.get('retry_at') or 0
            if retained_wait > now and (updates.get('retry_at', retained_wait) or 0) < retained_wait:
                # A late response from another lease can arrive after this
                # worker computed its checkpoint. Commit progress atomically
                # without clearing/shortening that still-authoritative wait.
                updates.update(retry_at=retained_wait, rate_wait_code=job.get('rate_wait_code'))
            effective_wait = updates.get('retry_at', job.get('retry_at')) or 0
            if updates.get('state') in {'QUEUED', 'INTERRUPTED'} and effective_wait > now:
                updates['next_at'] = min(max(updates.get('next_at', job['next_at']), effective_wait), job['deadline'])
            job.update(updates, updated_at=now)
            if updates.get('state') != 'RUNNING':
                job.update(lease=None, lease_until=0)
            self._write(db, job)
        self._transaction(finish)

    def _owned(self, claimed):
        def owned(db):
            job = self._read(db, claimed['id'])
            return bool(job and job.get('lease') == claimed['lease'] and job['state'] == 'RUNNING')
        return not self.stop.is_set() and self._transaction(owned)

    def _heartbeat(self, claimed, finished):
        while not finished.wait(30) and not self.stop.is_set():
            try:
                if not self._owned(claimed):
                    return
                self._finish(claimed, state='RUNNING', lease_until=self.now() + LEASE_SECONDS)
            except Exception as error:
                logging.getLogger(__name__).warning('Repository index lease unavailable: %s', type(error).__name__)
                return

    @staticmethod
    def _progress(result):
        graph = result.get('graph') or result
        counts = graph.get('file_statuses') or {}
        return {'completed_files': graph.get('processed_files'), 'total_files': graph.get('expected_files'),
                'failed_files': counts.get('failed', 0) if 'file_statuses' in graph else None,
                'unsupported_files': counts.get('unsupported', 0) if 'file_statuses' in graph else None,
                'partial_files': counts.get('partial', 0) if 'file_statuses' in graph else None,
                'coverage_complete': graph.get('coverage_complete', graph.get('complete')),
                'processing_complete': result.get('has_more') is False}

    @staticmethod
    def _retry_evidence(result):
        graph = result.get('graph') or result
        return identity(graph.get('index_metrics'), graph.get('file_statuses'), graph.get('processed_files'),
                        graph.get('pending_files'), result.get('has_more'), result.get('index_cursor'))

    def _step(self, job):
        if not self._owned(job):
            return
        principal, request = job['principal'], job['request']
        namespace, _ = self._scope(principal, dict(request, action='status'))
        if namespace != job['namespace']:
            raise PermissionError('Repository graph account scope changed')
        status = self.service.execute(principal, dict(request, action='status', budget_bytes=4096), expected_scope=job['namespace'])
        if job.get('retry_outcome_unknown') and job.get('retry_baseline') == self._retry_evidence(status):
            # An unchanged old complete snapshot cannot establish whether the
            # requested retry reset or read any source. Do not silently replay it
            # or promote the old completion to this new generation's outcome.
            self._finish(job, state='FAILED', error_code='INDEX_RETRY_OUTCOME_UNKNOWN', processing_complete=False)
            return
        if status.get('has_more') is False and not job['retry_pending']:
            self._finish(job, state='COMPLETED', error_code=None, retry_at=None, rate_wait_code=None,
                         retry_outcome_unknown=False, **self._progress(status))
            return
        arguments = dict(request, action='index', budget_bytes=4096)
        if job['retry_pending']:
            arguments['retry_failed'] = True
            # A stopped/lost call is reconciled through status on the next step;
            # never blindly reset failures again after dispatch may have occurred.
            self._finish(job, state='RUNNING', retry_pending=False, retry_outcome_unknown=True,
                         retry_baseline=self._retry_evidence(status))
        elif status.get('index_cursor'):
            arguments['index_cursor'] = status['index_cursor']
        if self.stop.is_set():
            self._finish(job, state='INTERRUPTED', next_at=self.now())
            return
        if not self._owned(job):
            return
        cancellation = getattr(self.service, '_cancellation', None)
        missing = object()
        previous = getattr(cancellation, 'check', missing)
        if cancellation is not None:
            cancellation.check = lambda: not self._owned(job)
        try:
            result = self.service.execute(principal, arguments, expected_scope=job['namespace'])
        finally:
            if cancellation is not None:
                if previous is missing:
                    del cancellation.check
                else:
                    cancellation.check = previous
        more = result.get('has_more')
        if type(more) is not bool:
            raise ValueError('Index batch outcome is unavailable')
        if more and (not result.get('index_cursor') or result.get('index_cursor') == arguments.get('index_cursor')):
            raise ValueError('Index batch did not advance')
        self._finish(job, state='QUEUED' if more else 'COMPLETED', next_at=self.now(), retry_pending=False,
                     retry_outcome_unknown=False, batches=job['batches'] + 1, attempts=0, error_code=None,
                     retry_at=None, rate_wait_code=None, **self._progress(result))

    def _retain_rate_wait(self, claimed, retry_at, code):
        def retain(db):
            job = self._read(db, claimed['id'])
            if not job or job['namespace'] != claimed['namespace'] or job['generation'] != claimed['generation']:
                return None
            # A same-generation pause/cancel may have revoked the execution
            # lease. Retain only the observed quota deadline, never its state,
            # lease, progress, request acknowledgments or replay authority.
            if retry_at > (job.get('retry_at') or 0):
                job.update(retry_at=retry_at, rate_wait_code=code)
                self._write(db, job)
            return job['retry_at'], job.get('rate_wait_code') or code
        return self._transaction(retain)

    def _failure(self, job, error):
        # GraphService carries an actually observed rate response as the direct
        # cause of cancellation. Do not infer a wait from messages or arbitrary
        # exception chains, and do not discard it just because shutdown won.
        rate_error = error if isinstance(error, requests.HTTPError) else error.__cause__ if isinstance(error, InterruptedError) else None
        response = rate_error.response if isinstance(rate_error, requests.HTTPError) else None
        wait = GitHubApiGovernor.retry_delay(response.status_code, response.headers, self.now()) if response is not None else None
        retained = None
        if wait is not None:
            code = 'INDEX_CAPACITY_RESERVED' if getattr(rate_error, 'reason', None) == 'FOREGROUND_RESERVE' else 'INDEX_RATE_LIMIT_WAIT'
            retained = self._retain_rate_wait(job, self.now() + wait, code)
            if retained is None:
                return
        if isinstance(error, InterruptedError) or self.stop.is_set():
            next_at = min(retained[0], job['deadline']) if retained else self.now()
            self._finish(job, state='INTERRUPTED', next_at=next_at, error_code=None)
            return
        if wait is not None:
            # Keep the durable revision/cursor and release the worker until the
            # observed reset. A rate wait does not spend transport retry attempts
            # or revoke access; the next step reconciles status before indexing.
            retry_at, code = retained or (self.now() + wait, code)
            # Wake at the budget boundary even if the server wait is longer.
            # _take pauses expired jobs without dispatching; retry_at remains the
            # full observed wait so an explicit resume cannot bypass it.
            self._finish(job, state='QUEUED', next_at=min(retry_at, job['deadline']),
                         retry_at=retry_at, rate_wait_code=code, error_code=code)
            return
        transient = isinstance(error, (requests.RequestException, TimeoutError, OSError))
        if isinstance(error, requests.HTTPError) and error.response is not None and error.response.status_code in {401, 403}:
            transient = False
        attempt = job.get('attempts', 0) + 1
        code = 'INDEX_ACCESS_REVOKED' if isinstance(error, PermissionError) else 'INDEX_CONNECTION_UNAVAILABLE' if transient else 'INDEX_REQUEST_REJECTED' if isinstance(error, ValueError) else 'INDEX_FAILED'
        self._finish(job, state='QUEUED' if transient and attempt <= 5 else 'FAILED', attempts=attempt,
                     next_at=self.now() + min(30, 2 ** attempt), error_code=code)

    def _run(self):
        while not self.stop.is_set():
            try:
                maintenance = getattr(self.service, '_maintenance', None)
                if maintenance is not None:
                    with self._admission_lock():
                        maintain = not self._draining()
                        if maintain:
                            self.active_batches += 1
                    if maintain:
                        try:
                            maintenance.tick()
                        finally:
                            with self._admission_lock():
                                self.active_batches -= 1
                with self._admission_lock():
                    job = None if self._draining() else self._take()
                    if job:
                        self.active_batches += 1
                if job:
                    finished = threading.Event()
                    heartbeat = None
                    try:
                        heartbeat = threading.Thread(target=self._heartbeat, args=(job, finished), daemon=True)
                        heartbeat.start()
                        self._step(job)
                    except Exception as error:
                        self._failure(job, error)
                    finally:
                        finished.set()
                        if heartbeat is not None and heartbeat.ident is not None:
                            heartbeat.join(1)
                        with self._admission_lock():
                            self.active_batches -= 1
                    continue
            except Exception as error:
                logging.getLogger(__name__).warning('Repository index checkpoint unavailable: %s', type(error).__name__)
            self.wake.wait(1)
            self.wake.clear()
