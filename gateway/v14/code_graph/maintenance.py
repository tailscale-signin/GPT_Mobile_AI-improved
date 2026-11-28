"""Account-bound branch maintenance using the existing durable index worker.

Watches contain no credentials. They can schedule read-only source preparation,
never engineering tasks or a move of an accepted task's immutable revision.
"""
import json
import re
import time

import requests

from v14.engineering_crypto import EngineeringCipher
from v14.github_api_governor import GitHubApiGovernor
from .extract import identity


class GraphMaintenance:
    ACTIVE_SECONDS = 90
    INACTIVE_SECONDS = 1800
    ACTIVE_WINDOW = 900
    MAX_WATCHES = 128

    def __init__(self, service, *, now=time.time):
        self.service = service
        self.store = service.store.store
        self.kind = 'public' if service.public else 'authenticated'
        self.now = now

    def _transaction(self, operation):
        def transact(db):
            db.execute('CREATE TABLE IF NOT EXISTS code_graph_watches ('
                       'namespace TEXT PRIMARY KEY, kind TEXT NOT NULL, next_at REAL NOT NULL, payload TEXT NOT NULL)')
            return operation(db)
        return self.store.tx(transact)

    @staticmethod
    def _read(db, namespace):
        row = db.execute('SELECT payload FROM code_graph_watches WHERE namespace=?', (namespace,)).fetchone()
        return json.loads(EngineeringCipher.decrypt(db, 'graph-watch:' + namespace, row[0])) if row else None

    @staticmethod
    def _write(db, watch):
        namespace = watch['namespace']
        payload = EngineeringCipher.encrypt(db, 'graph-watch:' + namespace, json.dumps(watch, separators=(',', ':')))
        db.execute('INSERT OR REPLACE INTO code_graph_watches VALUES(?,?,?,?)',
                   (namespace, watch['kind'], watch['next_at'], payload))

    def observe(self, principal, namespace, request, head):
        """Register only after the caller's current repository authorization."""
        now = self.now()
        enabled = request.get('auto_refresh', True)
        if not isinstance(enabled, bool):
            raise ValueError('Invalid repository maintenance preference')
        def save(db):
            previous = self._read(db, namespace)
            if previous and previous.get('state') == 'PAUSED' and 'auto_refresh' not in request:
                return
            if not enabled:
                if previous:
                    previous.update(enabled=False, next_at=now + self.INACTIVE_SECONDS, state='PAUSED')
                    self._write(db, previous)
                return
            if not previous and db.execute('SELECT COUNT(*) FROM code_graph_watches').fetchone()[0] >= self.MAX_WATCHES:
                # The map remains usable; watch capacity is explicit and bounded.
                return
            watch = previous or {'namespace': namespace, 'kind': self.kind, 'principal': principal,
                                 'request': {key: request[key] for key in ('repository', 'branch', 'commit_sha')},
                                 'next_at': now, 'state': 'UPDATING', 'observed_head': head,
                                 'last_checked_at': None, 'failures': 0}
            # An explicit later visit can restore a paused/revoked watch, but it
            # cannot shorten an observed provider rate-limit deadline.
            watch.update(enabled=True, last_selected_at=now)
            if previous and previous.get('state') in {'PAUSED', 'ACCESS_REVOKED'}:
                watch['next_at'] = max(now, previous.get('retry_at', 0))
            self._write(db, watch)
        self._transaction(save)

    def status(self, namespace):
        watch = self._transaction(lambda db: self._read(db, namespace))
        if not watch:
            return {'enabled': False, 'state': 'NOT_REGISTERED'}
        return {key: watch.get(key) for key in ('enabled', 'state', 'observed_head', 'last_checked_at', 'next_at', 'retry_at')}

    def _take(self):
        now = self.now()
        def take(db):
            # Reserve the observation before leaving the transaction. Restarted
            # workers retry it after the bounded lease without losing a watch.
            rows = db.execute('SELECT namespace FROM code_graph_watches WHERE kind=? AND next_at<=? '
                              'ORDER BY next_at,namespace LIMIT 16', (self.kind, now)).fetchall()
            for namespace, in rows:
                watch = self._read(db, namespace)
                if not watch.get('enabled'):
                    watch['next_at'] = now + self.INACTIVE_SECONDS
                    self._write(db, watch)
                    continue
                watch['next_at'] = now + 300
                self._write(db, watch)
                return watch
            return None
        return self._transaction(take)

    def _finish(self, watch, **changes):
        def finish(db):
            current = self._read(db, watch['namespace'])
            if not current or not current.get('enabled'):
                return
            current.update(changes)
            self._write(db, current)
        self._transaction(finish)

    def tick(self):
        """At most one due branch per worker turn; never busy-poll GitHub."""
        watch = self._take()
        if watch is None:
            return False
        now = self.now()
        interval = self.ACTIVE_SECONDS if now - watch.get('last_selected_at', 0) < self.ACTIVE_WINDOW else self.INACTIVE_SECONDS
        try:
            principal = watch['principal']
            if not self.service.authorized(principal):
                raise PermissionError('Repository maintenance access was revoked')
            namespace, _, head, _ = self.service.authorize(principal, watch['request'])
            if namespace != watch['namespace']:
                raise PermissionError('Repository maintenance account changed')
            if not re.fullmatch('[a-f0-9]{40}', head):
                raise ValueError('Repository branch did not return an immutable revision')
            request = dict(watch['request'], commit_sha=head)
            saved = self.service.store.summary(namespace, head)
            complete = saved is not None and saved['graph'].get('processing_complete') is True
            if not complete:
                job = self.service.index_jobs.status(principal, request)
                # Explicit cancellation/pause is sticky for this exact revision.
                # A failed job needs an explicit retry; polling is not permission
                # to repeat a permanently rejected read forever.
                if not job or job['state'] not in {'PAUSED', 'CANCELLED', 'FAILED'}:
                    request_id = identity('maintained-map-v1', namespace, head)
                    if job and job['state'] == 'COMPLETED':
                        # A historical completed receipt does not recreate an
                        # evicted snapshot. Renew only after this exact job and
                        # absent/incomplete snapshot were both observed.
                        request_id = identity('maintained-map-rebuild-v1', namespace, head, job['generation'] + 1)
                    self.service.index_jobs.handle(principal, dict(request, action='index', background=True, maintenance_request=True,
                        request_id=request_id))
            if not self.service.authorized(principal):
                raise PermissionError('Repository maintenance access was revoked')
            state = 'UP_TO_DATE' if complete and saved['graph'].get('coverage_complete') is True else 'PARTIAL_INDEX' if complete else 'UPDATING'
            self._finish(watch, request=request, observed_head=head, last_checked_at=now,
                         next_at=now + interval, state=state, failures=0, retry_at=0)
        except Exception as error:
            response = error.response if isinstance(error, requests.HTTPError) else None
            wait = GitHubApiGovernor.retry_delay(response.status_code, response.headers, now) if response is not None else None
            revoked = isinstance(error, PermissionError) or response is not None and response.status_code in {401, 403, 404} and wait is None
            failures = watch.get('failures', 0) + 1
            delay = wait if wait is not None else min(3600, interval * 2 ** min(failures, 5))
            self._finish(watch, enabled=not revoked, state='ACCESS_REVOKED' if revoked else 'OFFLINE_SNAPSHOT',
                         next_at=now + delay, retry_at=now + wait if wait is not None else 0, failures=failures)
        return True
