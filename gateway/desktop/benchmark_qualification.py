"""Durable desktop receipts for explicitly selected live qualification manifests.

Readback never dispatches a job. Lost admission is reconciled under the original
Gateway/account/request identity; execution requires the separate execute action.
"""
import json
from pathlib import Path
import re
import time
from urllib.parse import urlencode
import uuid

from desktop import benchmark
from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock

SCHEMA = 'gateway.desktop.qualification.v1'
MAX_MANIFEST_BYTES = 240 * 1024


def _directory(root):
    return Path(root) / 'data' / 'benchmark-qualifications'


def _path(root, ident):
    if not isinstance(ident, str) or not re.fullmatch(r'qualification_[a-f0-9]{32}', ident):
        raise ValueError('Choose a saved qualification receipt.')
    return _directory(root) / (ident + '.json')


def _load(root, ident):
    path = _path(root, ident)
    if path.stat().st_size > 20 * 1024 * 1024:
        raise ValueError('The saved qualification exceeds its evidence bound.')
    value = json.loads(path.read_bytes())
    if not isinstance(value, dict) or value.get('schema') != SCHEMA or value.get('localId') != ident:
        raise ValueError('The saved qualification receipt is invalid.')
    return value


def public(value):
    return {key: item for key, item in value.items() if key not in {'binding', 'payload'}}


def history(root):
    rows = []
    for path in _directory(root).glob('qualification_*.json'):
        try:
            value = _load(root, path.stem)
            rows.append({key: value.get(key) for key in ('localId', 'createdAt', 'state', 'executeAuthorized', 'route', 'run')})
        except (OSError, ValueError, TypeError):
            continue
    return sorted(rows, key=lambda item: item['createdAt'], reverse=True)


def _save(root, value):
    value['updatedAt'] = time.time()
    atomic_json(_path(root, value['localId']), value)
    return public(value)


def _observed(root, value, run):
    if not isinstance(run, dict) or not re.fullmatch(r'qual_[a-f0-9]{32}', str(run.get('id', ''))):
        raise ValueError('The Gateway returned an invalid qualification receipt.')
    value.update(run=run, state=run.get('state', 'AWAITING_EVIDENCE'))
    value.pop('recovery', None)
    return _save(root, value)


def start(root, path, *, execute=False):
    if type(execute) is not bool:
        raise ValueError('Choose readback or explicit execution.')
    source = Path(path)
    if source.stat().st_size > MAX_MANIFEST_BYTES:
        raise ValueError('The qualification manifest must be at most 240 KiB.')
    manifest = json.loads(source.read_bytes())
    if not isinstance(manifest, dict):
        raise ValueError('Choose a JSON qualification manifest.')
    lock = StateDirectoryLock(_directory(root) / 'owner')
    lock.acquire()
    try:
        if any(row['state'] in {'DISPATCHING', 'UNCERTAIN'} for row in history(root)):
            raise ValueError('Check the original uncertain qualification before starting another.')
        ident = 'qualification_' + uuid.uuid4().hex
        value = dict(schema=SCHEMA, localId=ident, createdAt=time.time(), state='DISPATCHING',
                     executeAuthorized=execute, route=manifest.get('expected_route'), run=None,
                     payload=dict(request_id=ident, manifest=manifest, execute=execute),
                     binding=benchmark.execution_binding(root))
        _save(root, value)
        try:
            run = benchmark.service_request(root, '/qualifications', method='POST', payload=value['payload'], binding=value['binding'])
        except benchmark.BenchmarkRejected:
            value['state'] = 'REJECTED'
            _save(root, value)
            raise
        except Exception:
            value.update(state='UNCERTAIN', recovery='Check the original submission. No new job will be sent by readback.')
            _save(root, value)
            raise
        return _observed(root, value, run)
    finally:
        lock.release()


def refresh(root, ident=None, *, execute=False):
    lock = StateDirectoryLock(_directory(root) / 'owner')
    lock.acquire()
    try:
        if ident is None:
            saved = history(root)
            if not saved:
                raise ValueError('Open a qualification manifest first.')
            ident = saved[0]['localId']
        value = _load(root, ident)
        if execute and not value['executeAuthorized']:
            raise ValueError('This receipt authorized readback only. Select the manifest and use Execute to authorize its fixed jobs.')
        run = value.get('run')
        if run is None:
            result = benchmark.service_request(root, '/qualifications?' + urlencode({'request_id': value['payload']['request_id']}), binding=value['binding'])
            if result.get('submissionState') != 'OBSERVED' or not isinstance(result.get('run'), dict):
                value.update(state='UNCERTAIN', recovery='The original request is not visible. Restore the original Gateway/account and check again; no work was resent.')
                return _save(root, value)
            run = result['run']
            _observed(root, value, run)
        if not re.fullmatch(r'qual_[a-f0-9]{32}', str(run.get('id', ''))):
            raise ValueError('The saved Gateway qualification identity is invalid.')
        result = benchmark.service_request(root, '/qualifications/' + run['id'] + ('/resume' if execute else ''),
            method='POST' if execute else 'GET', payload={} if execute else None, binding=value['binding'])
        return _observed(root, value, result)
    finally:
        lock.release()
