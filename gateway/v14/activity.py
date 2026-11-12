"""Local desktop observations; never an authentication or job-ownership source."""
from collections import OrderedDict
import contextvars
import functools
import json
from pathlib import Path
import threading
import time
import uuid

from gateway_security import current_device

current_peer = contextvars.ContextVar('gateway_observed_peer', default=None)
MAX_ACTIVITIES = 300
MAX_DEVICES = 128


def text(value, limit=160):
    return ''.join(c for c in str(value or '') if c.isprintable())[:limit]


def category(name):
    name = str(name).lower()
    if 'github' in name:
        return 'github'
    if any(word in name for word in ('search', 'research', 'crawl', 'browse')):
        return 'search'
    return 'tool'


class ActivityTracker:
    def __init__(self, history_path=None, now=time.time):
        self.now = now
        self.started = now()
        self.lock = threading.RLock()
        self.devices = OrderedDict()
        self.activities = OrderedDict()
        self.requests = self.errors = self.received = self.sent = 0
        if history_path:
            try:
                path = Path(history_path)
                if path.stat().st_size <= 1024 * 1024:
                    saved = json.loads(path.read_text(encoding='utf-8'))
                    for item in saved.get('activities', [])[-MAX_ACTIVITIES:]:
                        if not isinstance(item, dict) or not isinstance(item.get('id'), str):
                            continue
                        record = {key: item.get(key) for key in ('id', 'kind', 'title', 'status', 'started', 'updated', 'duration', 'peer', 'job')}
                        if record['status'] == 'running':
                            record['status'] = 'interrupted'
                        self.activities[record['id']] = record
            except (OSError, ValueError, TypeError):
                pass

    def begin(self, kind, title, peer=None, job=None):
        ident = uuid.uuid4().hex
        stamp = self.now()
        with self.lock:
            self.activities[ident] = {'id': ident, 'kind': kind, 'title': text(title), 'status': 'running',
                                      'started': stamp, 'updated': stamp, 'duration': None, '_principal': current_device.get(),
                                      'peer': text(peer, 80), 'job': text(job, 128)}
            while len(self.activities) > MAX_ACTIVITIES:
                self.activities.popitem(last=False)
        return ident

    def finish(self, ident, status):
        with self.lock:
            item = self.activities.get(ident)
            if item:
                item.update(status=status, updated=self.now(), duration=max(0, self.now() - item['started']))

    def enter(self, peer, agent):
        stamp = self.now()
        peer = text(peer, 80) or 'unknown'
        with self.lock:
            self.requests += 1
            owner = current_device.get()
            key = (owner, peer)
            item = self.devices.pop(key, {'address': peer, 'firstSeen': stamp, 'activeRequests': 0, 'requests': 0,
                                          'errors': 0, 'receivedBytes': 0, 'sentBytes': 0, '_principal': owner})
            item.update(lastSeen=stamp, agent=text(agent, 100), requests=item['requests'] + 1,
                        activeRequests=item['activeRequests'] + 1)
            self.devices[key] = item
            while len(self.devices) > MAX_DEVICES:
                self.devices.popitem(last=False)
        return peer

    def leave(self, peer, status, received, sent):
        with self.lock:
            item = self.devices.get((current_device.get(), peer))
            if item:
                item.update(activeRequests=max(0, item['activeRequests'] - 1), lastSeen=self.now(),
                            errors=item['errors'] + int(status >= 400), receivedBytes=item['receivedBytes'] + received,
                            sentBytes=item['sentBytes'] + sent)
            self.errors += int(status >= 400)
            self.received += received
            self.sent += sent

    def snapshot(self, principal=None):
        now = self.now()
        def visible(item):
            return principal is None or item.get('_principal') == principal
        def public(item):
            return {key: value for key, value in item.items() if not key.startswith('_')}
        with self.lock:
            devices = [dict(public(item), state='active' if item['activeRequests'] else 'recent' if now - item['lastSeen'] <= 60 else 'idle')
                       for item in reversed(self.devices.values()) if visible(item)]
            counters = {'requests': self.requests, 'errors': self.errors, 'receivedBytes': self.received, 'sentBytes': self.sent}
            if principal is not None:
                counters = {key: sum(item.get(key, 0) for item in devices) for key in counters}
            return {'capturedAt': now, 'startedAt': self.started, 'uptime': max(0, now - self.started), **counters,
                    'requestCounterScope': 'retained_devices' if principal is not None else 'since_gateway_start',
                    'devices': devices, 'activities': [public(item) for item in self.activities.values() if visible(item)],
                    'deviceWindowSeconds': 60, 'activityLimit': MAX_ACTIVITIES}

    def install_tools(self, runtime):
        original = runtime._execute_mcp_tool_single
        @functools.wraps(original)
        def observed(name, *args, **kwargs):
            from v14.actions import current_gateway_job_id
            ident = self.begin(category(name), name, current_peer.get(), current_gateway_job_id.get())
            try:
                result = original(name, *args, **kwargs)
            except BaseException:
                self.finish(ident, 'failed')
                raise
            else:
                bad = isinstance(result, dict) and (result.get('isError') is True or result.get('error') is not None)
                self.finish(ident, 'failed' if bad else 'completed')
                return result
        runtime._execute_mcp_tool_single = observed


class ActivityMiddleware:
    def __init__(self, app, tracker):
        self.app, self.tracker = app, tracker

    async def __call__(self, scope, receive, send):
        path = scope.get('path', '').removeprefix('/v1')
        if scope['type'] != 'http' or path.startswith('/gateway/admin/') or path in {'/gateway/live', '/gateway/ready', '/gateway/dashboard'}:
            return await self.app(scope, receive, send)
        headers = dict(scope.get('headers', []))
        peer = self.tracker.enter((scope.get('client') or ('unknown', 0))[0], headers.get(b'user-agent', b'').decode('latin1'))
        marker = current_peer.set(peer)
        ident = self.tracker.begin('chat', 'Chat completion', peer) if path == '/chat/completions' else None
        received = sent = 0
        status = 499
        async def observed_receive():
            nonlocal received
            message = await receive()
            received += len(message.get('body', b''))
            return message
        async def observed_send(message):
            nonlocal sent, status
            if message['type'] == 'http.response.start':
                status = message['status']
            sent += len(message.get('body', b''))
            await send(message)
        try:
            await self.app(scope, observed_receive, observed_send)
        except BaseException:
            status = 499
            raise
        finally:
            self.tracker.leave(peer, status, received, sent)
            if ident:
                self.tracker.finish(ident, 'completed' if status < 400 else 'interrupted' if status == 499 else 'failed')
            current_peer.reset(marker)


def desktop_snapshot(runtime, service, tracker, principal=None, ownership=None):
    """Bounded metadata only: no auth headers, tool arguments, or result bodies."""
    from v14.usage import usage_snapshot
    from v14.engineering_batch_plan import summary as batch_summary
    result = tracker.snapshot(principal)
    result['usage'] = usage_snapshot(runtime)
    result['service'] = service.snapshot()
    result['jobs'], result['engineering'] = [], []
    result['historyError'] = None
    result['openJobs'] = None
    if runtime.GATEWAY_DURABLE_JOBS and runtime.durable_job_initialized:
        try:
            with runtime.durable_job_lock:
                db = runtime._durable_connect()
                try:
                    rows = list(db.execute('SELECT job_id, public_json, status, created_at, updated_at FROM gateway_jobs ORDER BY updated_at DESC LIMIT 100'))
                    owned = ownership.owned_job_ids([row[0] for row in rows], principal) if principal and ownership else set()
                    for row in rows:
                        if principal is not None and row[0] not in owned:
                            continue
                        public = json.loads(row[1])
                        result['jobs'].append({'id': row[0], 'kind': 'chat', 'title': text(public.get('model') or 'Chat'),
                            'status': text(row[2], 40), 'started': row[3], 'updated': row[4],
                            'detail': text(public.get('stage')), 'toolCalls': public.get('total_tool_calls', 0)})
                    # Count the complete active inventory, never the 100 displayed history rows.
                    active_ids = [row[0] for row in db.execute(
                        "SELECT job_id FROM gateway_jobs WHERE LOWER(status) NOT IN ('completed','failed','cancelled','canceled') LIMIT 201")]
                    open_chats = None
                    if len(active_ids) <= 200:
                        open_chats = len(ownership.owned_job_ids(active_ids, principal)) if principal and ownership else len(active_ids) if principal is None else None
                    open_engineering = 0
                    exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='engineering_jobs'").fetchone()
                    if exists:
                        active_engineering = list(db.execute("SELECT data FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED') LIMIT 201"))
                        open_engineering = (sum(principal is None or json.loads(row[0]).get('principal') == principal for row in active_engineering)
                                            if len(active_engineering) <= 200 else None)
                        for row in db.execute('SELECT id, state, data FROM engineering_jobs ORDER BY next_at DESC LIMIT 100'):
                            job = json.loads(row[2])
                            if principal is not None and job.get('principal') != principal:
                                continue
                            request = job.get('request', {})
                            result['engineering'].append({'id': row[0], 'kind': 'github', 'title': text(request.get('repository') or 'GitHub job'),
                                'status': text(row[1], 40), 'started': job.get('created_at'), 'updated': job.get('updated_at'),
                                'detail': text(job.get('message')), 'branch': text(request.get('branch')), 'pr': job.get('pr'),
                                'execution_mode': request.get('execution_mode', 'standard'),
                                'batch_progress': batch_summary(job.get('data', {}).get('integration_batch'))})
                    if open_chats is not None and open_engineering is not None:
                        result['openJobs'] = open_chats + open_engineering
                finally:
                    db.close()
        except Exception:
            result['historyError'] = 'Saved job history is temporarily unavailable.'
    return result


def client_snapshot(runtime, service, tracker, principal, ownership):
    """Same measured dashboard contract, with device/activity/history scoped to the admitted caller."""
    result = desktop_snapshot(runtime, service, tracker, principal=principal.id, ownership=ownership)
    result.update(schema='gptmobile.gateway.dashboard.v1', schemaVersion=1, instanceId=service.instance_id, scope='admitted_identity',
                  serviceScope='this_installation', historyLimit=100,
                  availability={'devices': True, 'activities': True, 'service': True,
                                'usage': bool(result['usage'].get('available')),
                                'openJobs': result['openJobs'] is not None,
                                'history': result['historyError'] is None and bool(runtime.GATEWAY_DURABLE_JOBS and runtime.durable_job_initialized)})
    return result
