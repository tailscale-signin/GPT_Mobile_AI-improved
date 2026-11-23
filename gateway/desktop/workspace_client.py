"""Desktop workspace observations and encrypted original-command recovery."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
from urllib.parse import quote

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from desktop.configuration import atomic_json
from desktop.control_security import server_secret
from desktop.conversation_client import ClientError, scope_key
from v14.state_lock import StateDirectoryLock


def path(job):
    return '/gateway/engineering/jobs/' + quote(job['id'], safe='') + '/workspace'


def board(client, job, root=None):
    result = client.request('GET', path(job))
    if (result.get('schema_version') != 1 or result.get('job_id') != job['id'] or
            scope_key(result) != scope_key(client.expected_identity) or
            result.get('repository') != job['repository'] or result.get('branch') != job['branch'] or
            not re.fullmatch('[a-f0-9]{40}', str(result.get('head_sha', ''))) or
            type(result.get('revision')) is not int):
        raise ClientError('The workspace identity changed. Refresh this original job.', not_dispatched=True)
    if root is not None:
        result['local_commands'] = []
        for record in _read(root).values():
            metadata = json.loads(record.get('identity', '[]'))
            if len(metadata) == 4 and metadata[:3] == [*scope_key(client.expected_identity), job['id']]:
                result['local_commands'].append({'command_id': metadata[3], 'status': 'READ_RECEIPT'})
    return result


def _file(root):
    return Path(root) / 'data' / 'desktop-workspace-commands.json'


def _read(root):
    file = _file(root)
    if not file.exists():
        return {}
    if file.is_symlink() or file.stat().st_size > 4 * 1024 * 1024:
        raise ClientError('The retained workspace commands need local recovery.')
    value = json.loads(file.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or value.get('schema_version') != 1 or not isinstance(value.get('commands'), dict):
        raise ClientError('The retained workspace commands need local recovery.')
    return value['commands']


def _key(root, *, create=False):
    if not create and not (Path(root) / 'data' / 'control-identity.secret').is_file():
        raise ClientError('Restore the protected Gateway identity to read this pending workspace command.')
    return hashlib.sha256(server_secret(root) + b'workspace-command-v1').digest()


def _identity(identity, job_id, command_id):
    return json.dumps([identity['gateway_id'], identity['principal_id'], job_id, command_id], separators=(',', ':'))


def _save(root, identity, job_id, payload):
    ident = _identity(identity, job_id, payload['command_id'])
    digest = hashlib.sha256(ident.encode()).hexdigest()
    records = _read(root)
    if digest in records:
        old = _decode(root, ident, records[digest])
        if old != payload:
            raise ClientError('A saved workspace command cannot change its arguments.', not_dispatched=True)
        return
    if len(records) >= 32:
        raise ClientError('Read the retained workspace command outcomes before starting more work.', not_dispatched=True)
    nonce = os.urandom(12)
    ciphertext = AESGCM(_key(root, create=True)).encrypt(nonce, json.dumps(payload).encode(), ident.encode())
    records[digest] = {'identity': ident, 'nonce': base64.b64encode(nonce).decode(), 'ciphertext': base64.b64encode(ciphertext).decode()}
    atomic_json(_file(root), {'schema_version': 1, 'commands': records})


def _decode(root, identity, record):
    return json.loads(AESGCM(_key(root)).decrypt(base64.b64decode(record['nonce'], validate=True),
                      base64.b64decode(record['ciphertext'], validate=True), identity.encode()))


def _original(root, identity, job_id, command_id):
    ident = _identity(identity, job_id, command_id)
    record = _read(root).get(hashlib.sha256(ident.encode()).hexdigest())
    if record is None:
        raise ClientError('The original workspace command is unavailable; inspect its server receipt.', not_dispatched=True)
    return _decode(root, ident, record)


def receipt(client, root, job, command_id, *, retire=True):
    if not isinstance(command_id, str) or not re.fullmatch('[A-Za-z0-9_-]{1,128}', command_id):
        raise ClientError('Choose an observed workspace command.', not_dispatched=True)
    result = client.request('GET', path(job) + '/commands/' + quote(command_id, safe=''))
    if result.get('schema_version') != 1 or result.get('job_id') != job['id'] or result.get('command_id') != command_id:
        raise ClientError('The workspace command receipt belongs to another request.')
    if result.get('status') not in {'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED', 'UNKNOWN', 'NOT_FOUND'}:
        raise ClientError('The workspace command returned an unknown state.')
    result['retry_available'] = False
    result['dismiss_available'] = False
    if result.get('status') == 'NOT_FOUND' and result.get('confirmed_absent') is True:
        try:
            old = _original(root, client.expected_identity, job['id'], command_id)
            unchanged = result.get('head_sha') == old['head_sha'] and result.get('revision') == old['revision']
            result['retry_available'] = unchanged
            result['dismiss_available'] = type(result.get('revision')) is int and not unchanged
        except (ClientError, ValueError, KeyError):
            pass
    if retire and result.get('status') in {'COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED'}:
        lock = StateDirectoryLock(Path(root) / 'data' / 'workspace-command-lock' / 'lock')
        lock.acquire()
        try:
            records = _read(root)
            key = hashlib.sha256(_identity(client.expected_identity, job['id'], command_id).encode()).hexdigest()
            if records.pop(key, None) is not None:
                atomic_json(_file(root), {'schema_version': 1, 'commands': records})
        finally:
            lock.release()
    return result


def submit(client, root, job, payload, *, retry=False):
    if not isinstance(payload, dict) or set(payload) != {'command_id', 'revision', 'head_sha', 'action', 'arguments'}:
        raise ClientError('Prepare an exact workspace command.', not_dispatched=True)
    if (not isinstance(payload['command_id'], str) or not re.fullmatch('[A-Za-z0-9_-]{1,128}', payload['command_id']) or
            type(payload['revision']) is not int or payload['revision'] < 0 or
            not re.fullmatch('[a-f0-9]{40}', str(payload['head_sha'])) or
            not isinstance(payload['action'], str) or not re.fullmatch('[a-z_]{1,80}', payload['action']) or
            not isinstance(payload['arguments'], dict)):
        raise ClientError('Use the observed source, revision and a valid workspace command identity.', not_dispatched=True)
    if len(json.dumps(payload).encode()) > 65536:
        raise ClientError('The workspace command is too large.', not_dispatched=True)
    identity = client.expected_identity
    lock = StateDirectoryLock(Path(root) / 'data' / 'workspace-command-lock' / 'lock')
    lock.acquire()
    try:
        if retry:
            original = _original(root, identity, job['id'], payload['command_id'])
            if original != payload:
                raise ClientError('Retry only the unchanged original workspace command.', not_dispatched=True)
            observed = receipt(client, root, job, payload['command_id'], retire=False)
            if observed.get('confirmed_absent') is not True or not observed.get('retry_available'):
                raise ClientError('The command is not confirmed absent at its original source.', not_dispatched=True)
            current = board(client, job)
            if current['revision'] != payload['revision'] or current['head_sha'] != payload['head_sha']:
                raise ClientError('The workspace changed; review a new action instead of retrying.', not_dispatched=True)
        _save(root, identity, job['id'], payload)
        result = client.request('POST', path(job), payload=payload, user_action='workspace-command')
        if (result.get('schema_version') != 1 or result.get('job_id') != job['id'] or
                result.get('command_id') != payload['command_id'] or result.get('head_sha') != payload['head_sha']):
            raise ClientError('Read the original workspace command receipt before continuing.')
        return result
    finally:
        lock.release()


def retry_original(client, root, job, command_id):
    original = _original(root, client.expected_identity, job['id'], command_id)
    return submit(client, root, job, original, retry=True)


def dismiss_absent(client, root, job, command_id):
    lock = StateDirectoryLock(Path(root) / 'data' / 'workspace-command-lock' / 'lock')
    lock.acquire()
    try:
        original = _original(root, client.expected_identity, job['id'], command_id)
        observed = receipt(client, root, job, command_id, retire=False)
        current = board(client, job)
        if (observed.get('status') != 'NOT_FOUND' or observed.get('confirmed_absent') is not True or
                current['head_sha'] == original['head_sha'] and current['revision'] == original['revision']):
            raise ClientError('Dismiss only a confirmed absent request whose original revision is no longer current.', not_dispatched=True)
        records = _read(root)
        key = hashlib.sha256(_identity(client.expected_identity, job['id'], command_id).encode()).hexdigest()
        records.pop(key, None)
        atomic_json(_file(root), {'schema_version': 1, 'commands': records})
        return {'command_id': command_id, 'local_state': 'DISMISSED_STALE_ABSENT', 'server_status': 'NOT_FOUND',
                'replayed': False, 'completed': False}
    finally:
        lock.release()


def host_configuration(root, action, args):
    from v14.agent_config import CATALOG, digest, load, save
    # This entry is called only by the local native bridge, never a phone API.
    selected = Path(root) / 'data' / 'coding-agents.json'
    if action == 'agent-config-save':
        result = save(args.get('document'), args.get('revision'), selected)
        return dict(result, restart_required=True)
    document, revision = load(selected) if selected.exists() else ({'schema_version': 1, 'agents': {}}, digest({'schema_version': 1, 'agents': {}}))
    return {'schema_version': 1, 'document': document, 'revision': revision,
            'catalog': [{'id': key, 'name': value[0], 'kind': value[1], 'capabilities': value[2]}
                        for key, value in CATALOG.items()]}
