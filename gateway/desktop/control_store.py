"""Supervisor-owned settings, device grants and command receipts in the job journal.

The ordinary key-free inference listener never grants access to this record.
Engineering admission shares its transaction with the existing job admission table.
"""
import base64
from contextlib import contextmanager
import hashlib
import hmac
import ipaddress
import json
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import time
import uuid
from urllib.parse import urlsplit

from desktop.configuration import load, public_repository_maps
from v14.engineering_batch_policy import load_policy

SCHEMA = 'gptmobile.gateway.settings.v1'
FIELDS = frozenset({'engineering_enabled', 'workers', 'active_chats', 'batch_mode',
                    'local_validation_workers', 'backend', 'model', 'public_repository_maps',
                    'start_at_login', 'update_mode', 'update_interval_hours', 'debug_mode',
                    'validation_profile', 'host_request'})
HOST_REQUESTS = frozenset({'Review phone network access', 'Open engineering setup', 'Review pending restart'})
RUNTIME_FIELDS = frozenset({'workers', 'active_chats', 'backend', 'model', 'public_repository_maps', 'debug_mode'})
ID = re.compile(r'[a-zA-Z0-9_-]{8,100}')
REPOSITORY = re.compile(r'[a-z0-9_.-]+/[a-z0-9_.-]+')
MAX_COMMANDS = 100000


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


class ControlError(Exception):
    def __init__(self, code, status=400, message=None):
        self.code, self.status = code, status
        super().__init__(message or code)


def reject_self_backend(origin, listener):
    """Check the actual listener, without trusting a default port or DNS lookup."""
    host, port, authorities = listener
    target = urlsplit(origin)
    if (target.port or (443 if target.scheme == 'https' else 80)) != port:
        return
    name = target.hostname.lower().rstrip('.')
    names = {str(host).lower().rstrip('.')}
    for authority in authorities:
        parsed = urlsplit('http://' + authority.strip())
        if parsed.hostname and (parsed.port is None or parsed.port == port):
            names.add(parsed.hostname.lower().rstrip('.'))
    address = ipaddress.ip_address(host)
    address = getattr(address, 'ipv4_mapped', None) or address
    if address.is_loopback or address.is_unspecified:
        names.add('localhost')
    try:
        candidate = ipaddress.ip_address(name)
        candidate = getattr(candidate, 'ipv4_mapped', None) or candidate
    except ValueError:
        candidate = None
    same = name in names or candidate == address
    if candidate and (address.is_loopback or address.is_unspecified) and (candidate.is_loopback or candidate.is_unspecified):
        same = True
    if candidate and address.is_unspecified and not same:
        # A wildcard listener also owns each local interface. A port-zero bind
        # checks local address ownership without connecting to a candidate host.
        try:
            with socket.socket(socket.AF_INET6 if candidate.version == 6 else socket.AF_INET) as check:
                check.bind((str(candidate), 0))
            same = True
        except OSError:
            pass
    if same:
        raise ControlError('invalid_backend', message='Choose a model backend separate from this Gateway listener')


def validate_changes(changes, current, *, local=False, listener=None, approved_backends=()):
    if not isinstance(changes, dict) or not changes or set(changes) - FIELDS:
        raise ControlError('unsupported_settings_fields')
    result = dict(current, **changes)
    if 'host_request' in changes and (len(changes) != 1 or not isinstance(changes['host_request'], str) or changes['host_request'] not in HOST_REQUESTS):
        raise ControlError('invalid_host_request')
    if 'validation_profile' in changes and (not isinstance(changes['validation_profile'], str) or not re.fullmatch('[a-f0-9]{64}', changes['validation_profile'])):
        raise ControlError('validation_profile_not_approved', 403)
    for key in ('engineering_enabled', 'public_repository_maps', 'start_at_login', 'debug_mode'):
        if key in changes and type(changes[key]) is not bool:
            raise ControlError('invalid_setting_value', message=key + ' must be boolean')
    for key, low, high in [('workers', 1, 16), ('active_chats', 1, 64),
                           ('local_validation_workers', 1, 4), ('update_interval_hours', 1, 168)]:
        if type(result[key]) is not int or not low <= result[key] <= high:
            raise ControlError('invalid_setting_value', message=key + ' is outside its supported range')
    if result['workers'] > result['active_chats']:
        raise ControlError('invalid_setting_value', message='Workers cannot exceed active chats')
    if (not isinstance(result['batch_mode'], str) or result['batch_mode'] not in {'standard', 'smart', 'full'}
            or not isinstance(result['update_mode'], str) or result['update_mode'] not in {'off', 'notify', 'auto'}):
        raise ControlError('invalid_setting_value')
    if not isinstance(result['model'], str) or len(result['model']) > 512 or any(ord(c) < 32 for c in result['model']):
        raise ControlError('invalid_setting_value', message='Choose an exact model identifier')
    if 'backend' in changes:
        # A phone may select an existing approved route, never authorize a new
        # origin or a new executable/secret. Local route edits still undergo probe.
        if not local and changes['backend'] != current['backend'] and changes['backend'] not in approved_backends:
            raise ControlError('host_approval_required', 403, 'Approve a new backend origin on this PC')
        from desktop.configuration import validate
        try:
            validate({'backend': changes['backend'], **({'port': listener[1]} if listener else {})})
            if listener:
                reject_self_backend(changes['backend'], listener)
        except (ValueError, TypeError):
            raise ControlError('invalid_backend') from None
    return result


class ControlStore:
    def __init__(self, root, now=time.time, *, listener=None):
        self.root, self.now = Path(root), now
        self.listener = listener
        self.path = self.root / 'data' / 'jobs.sqlite3'

    @contextmanager
    def tx(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            db.execute('PRAGMA busy_timeout=10000')
            db.execute('BEGIN IMMEDIATE')
            self.schema(db)
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def schema(self, db):
        statements = [
            'CREATE TABLE IF NOT EXISTS gateway_control_settings (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL, desired TEXT NOT NULL, effective TEXT NOT NULL, pending TEXT NOT NULL, author TEXT NOT NULL, changed REAL NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_commands (principal TEXT NOT NULL, id TEXT NOT NULL, digest TEXT NOT NULL, revision INTEGER NOT NULL, changes TEXT NOT NULL, created REAL NOT NULL, PRIMARY KEY(principal,id))',
            'CREATE TABLE IF NOT EXISTS gateway_control_rejections (principal TEXT NOT NULL, id TEXT NOT NULL, digest TEXT NOT NULL, receipt TEXT NOT NULL, PRIMARY KEY(principal,id))',
            'CREATE TABLE IF NOT EXISTS gateway_control_devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, public_key TEXT NOT NULL, token_hash TEXT UNIQUE NOT NULL, role TEXT NOT NULL, fields TEXT NOT NULL, repositories TEXT NOT NULL, revoked INTEGER NOT NULL, created REAL NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_pairing (id TEXT PRIMARY KEY, code_hash TEXT UNIQUE NOT NULL, name TEXT NOT NULL, expires REAL NOT NULL, state TEXT NOT NULL, device_id TEXT, public_key TEXT, fields TEXT NOT NULL, repositories TEXT NOT NULL, role TEXT NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_nonces (principal TEXT NOT NULL, nonce TEXT NOT NULL, expires REAL NOT NULL, PRIMARY KEY(principal,nonce))',
            'CREATE TABLE IF NOT EXISTS engineering_admission_settings (principal TEXT PRIMARY KEY, enabled INTEGER NOT NULL, revision INTEGER NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_runtime (id INTEGER PRIMARY KEY, observed REAL NOT NULL, ready INTEGER NOT NULL, prerequisites TEXT NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_validation_profiles (id TEXT PRIMARY KEY, label TEXT NOT NULL, document TEXT NOT NULL, repositories TEXT NOT NULL, created REAL NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_policy_intent (id INTEGER PRIMARY KEY, profile TEXT NOT NULL, expected_revision TEXT NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_policy_observation (id INTEGER PRIMARY KEY, revision TEXT NOT NULL, profile TEXT NOT NULL)',
            'CREATE TABLE IF NOT EXISTS gateway_control_host_requests (principal TEXT NOT NULL, id TEXT NOT NULL, request TEXT NOT NULL, state TEXT NOT NULL, created REAL NOT NULL, reviewed REAL, PRIMARY KEY(principal,id))',
            'CREATE TABLE IF NOT EXISTS gateway_control_backends (origin TEXT PRIMARY KEY, approved REAL NOT NULL)',
        ]
        for sql in statements:
            db.execute(sql)
        if not db.execute('SELECT 1 FROM gateway_control_settings WHERE id=1').fetchone():
            cfg = load(self.root)
            batch = load_policy(self.root / 'data' / 'execution-policy.json')
            initial = {key: cfg[key] for key in ('workers', 'active_chats', 'backend', 'start_at_login', 'update_mode', 'update_interval_hours', 'debug_mode')}
            initial.update(model='', public_repository_maps=public_repository_maps(self.root),
                           batch_mode=batch['default_mode'], local_validation_workers=batch['local_validation_workers'])
            db.execute('INSERT INTO gateway_control_settings VALUES (1,0,?,?,?, ?,?)',
                       (encoded(initial), encoded(initial), '[]', 'migration', self.now()))
        row = db.execute('SELECT desired,effective FROM gateway_control_settings WHERE id=1').fetchone()
        desired = json.loads(row['desired'])
        if 'validation_profile' not in desired:
            from desktop.phone_policies import state
            policy = state(self.root)
            desired['validation_profile'] = policy['profile_id']
            effective = dict(json.loads(row['effective']), validation_profile=policy['profile_id'])
            db.execute('UPDATE gateway_control_settings SET desired=?,effective=? WHERE id=1', (encoded(desired), encoded(effective)))
            db.execute('INSERT OR REPLACE INTO gateway_control_policy_observation VALUES (1,?,?)', (policy['revision'], policy['profile_id']))

    def _sync_host_policy(self, db):
        from desktop.phone_policies import state
        policy = state(self.root)
        old = db.execute('SELECT revision FROM gateway_control_policy_observation WHERE id=1').fetchone()
        if old and old[0] != policy['revision']:
            row = db.execute('SELECT desired,effective,pending FROM gateway_control_settings WHERE id=1').fetchone()
            desired = dict(json.loads(row['desired']), validation_profile=policy['profile_id'])
            pending = [item for item in json.loads(row['pending']) if item['field'] != 'validation_profile']
            if json.loads(row['effective']).get('validation_profile') != policy['profile_id']:
                pending.append({'field': 'validation_profile', 'reason': 'awaiting_supervisor', 'blocking_jobs': []})
            # A separate, explicit host editor action supersedes an older phone
            # selection and advances the same revision seen by both clients.
            db.execute('UPDATE gateway_control_settings SET revision=revision+1,desired=?,pending=?,author=?,changed=? WHERE id=1',
                       (encoded(desired), encoded(pending), 'local:validation-policy', self.now()))
            db.execute('DELETE FROM gateway_control_policy_intent')
        db.execute('INSERT OR REPLACE INTO gateway_control_policy_observation VALUES (1,?,?)', (policy['revision'], policy['profile_id']))
        return policy

    @staticmethod
    def installation(db):
        # The established installation identity also owns every saved job.
        try:
            row = db.execute("SELECT value FROM gateway_installation WHERE key='gateway_id'").fetchone()
        except sqlite3.OperationalError:
            row = None
        if not row:
            raise ControlError('gateway_not_ready', 503, 'Start Gateway before pairing or editing shared settings')
        return row[0]

    @staticmethod
    def device(db, principal):
        if not principal.startswith('paired:'):
            return None
        row = db.execute('SELECT * FROM gateway_control_devices WHERE id=? AND revoked=0', (principal[7:],)).fetchone()
        return dict(row) if row else None

    def grant(self, principal):
        with self.tx() as db:
            return self.device(db, principal)

    def _snapshot(self, db, principal, local=False):
        self._sync_host_policy(db)
        row = db.execute('SELECT * FROM gateway_control_settings WHERE id=1').fetchone()
        grant = self.device(db, principal)
        if not local and not grant:
            raise ControlError('access_revoked', 401)
        owner = db.execute('SELECT enabled,revision FROM engineering_admission_settings WHERE principal=?', (principal,)).fetchone()
        enabled = bool(owner['enabled']) if owner else not principal.startswith('paired:')
        desired, effective = json.loads(row['desired']), json.loads(row['effective'])
        desired['engineering_enabled'] = effective['engineering_enabled'] = enabled
        pending = json.loads(row['pending'])
        runtime = db.execute('SELECT * FROM gateway_control_runtime WHERE id=1').fetchone()
        prerequisites = json.loads(runtime['prerequisites']) if runtime and self.now() - runtime['observed'] <= 60 else ['runtime_observation_required']
        if grant and (grant['role'] != 'owner' or not json.loads(grant['repositories'])):
            prerequisites.append('repository_owner_permission')
        if enabled and (prerequisites or not runtime or self.now() - runtime['observed'] > 60 or not runtime['ready']):
            effective['engineering_enabled'] = False
            pending.append({'field': 'engineering_enabled', 'reason': 'setup_required', 'blocking_jobs': [], 'prerequisites': prerequisites})
        from desktop.phone_policies import choices
        repositories = json.loads(grant['repositories']) if grant else []
        return {'schema': SCHEMA, 'installation_id': self.installation(db), 'principal_id': principal,
                'revision': row['revision'], 'observed_at': self.now(), 'changed_at': row['changed'], 'changed_by': row['author'],
                'desired': desired, 'effective': effective, 'pending': pending,
                'writable_fields': sorted(FIELDS if local else json.loads(grant['fields']) if grant['role'] == 'owner' else []),
                'role': 'owner' if local else grant['role'],
                'capabilities': {'controlSettings.v1': True},
                'repositories': repositories,
                'validation_profiles': choices(db, None if local else repositories),
                'approved_backends': self._backend_options(db, desired['backend']),
                'host_requests': self._host_requests(db, principal),
                'engineering_prerequisites': prerequisites}

    def snapshot(self, principal='local:single-user', local=False):
        with self.tx() as db:
            return self._snapshot(db, principal, local)

    def patch(self, principal, payload, *, local=False):
        if (not isinstance(payload, dict) or set(payload) != {'installation_id', 'expected_revision', 'command_id', 'changes'}
                or not isinstance(payload['command_id'], str) or not ID.fullmatch(payload['command_id'])
                or type(payload['expected_revision']) is not int or payload['expected_revision'] < 0):
            raise ControlError('invalid_settings_command')
        digest = hashlib.sha256(encoded(payload).encode()).hexdigest()
        with self.tx() as db:
            snapshot = self._snapshot(db, principal, local)
            if payload['installation_id'] != snapshot['installation_id']:
                raise ControlError('installation_changed', 409)
            previous = db.execute('SELECT * FROM gateway_control_commands WHERE principal=? AND id=?', (principal, payload['command_id'])).fetchone()
            rejected = db.execute('SELECT * FROM gateway_control_rejections WHERE principal=? AND id=?', (principal, payload['command_id'])).fetchone()
            if rejected:
                if rejected['digest'] != digest:
                    raise ControlError('command_identity_conflict', 409)
                return json.loads(rejected['receipt'])
            if previous:
                if previous['digest'] != digest:
                    raise ControlError('command_identity_conflict', 409)
                return self._receipt(db, principal, payload['command_id'], local)
            count = db.execute('SELECT (SELECT COUNT(*) FROM gateway_control_commands) + '
                               '(SELECT COUNT(*) FROM gateway_control_rejections)').fetchone()[0]
            if count >= MAX_COMMANDS:
                raise ControlError('command_journal_full', 503)
            if payload['expected_revision'] != snapshot['revision']:
                return self._rejection(db, principal, payload, digest, snapshot, ControlError('settings_changed_elsewhere', 409))
            changes = payload['changes']
            if not isinstance(changes, dict) or set(changes) - set(snapshot['writable_fields']):
                return self._rejection(db, principal, payload, digest, snapshot, ControlError('field_permission_required', 403))
            try:
                listener = None
                if 'backend' in changes:
                    if self.listener:
                        listener = self.listener()
                    else:
                        cfg = load(self.root)
                        listener = (cfg['bind_host'], cfg['port'], cfg['network_hostnames'].split(',') + [socket.gethostname()])
                updated = validate_changes(changes, snapshot['desired'], local=local, listener=listener,
                                           approved_backends=snapshot['approved_backends'])
                if 'validation_profile' in changes:
                    from desktop.phone_policies import approved, state
                    approved(db, changes['validation_profile'])
                    if changes['validation_profile'] not in snapshot['validation_profiles']:
                        raise ControlError('repository_permission_required', 403)
                    policy = state(self.root)
                    observed = db.execute('SELECT revision FROM gateway_control_policy_observation WHERE id=1').fetchone()
                    if not observed or observed[0] != policy['revision']:
                        return self._rejection(db, principal, payload, digest, self._snapshot(db, principal, local), ControlError('settings_changed_elsewhere', 409))
                if 'host_request' in changes and db.execute("SELECT 1 FROM gateway_control_host_requests WHERE principal=? AND state='waiting_pc'", (principal,)).fetchone():
                    raise ControlError('host_request_pending', 409)
            except ControlError as error:
                return self._rejection(db, principal, payload, digest, snapshot, error)
            if 'validation_profile' in changes:
                db.execute('INSERT OR REPLACE INTO gateway_control_policy_intent VALUES (1,?,?)', (changes['validation_profile'], policy['revision']))
            if 'host_request' in changes:
                db.execute("INSERT INTO gateway_control_host_requests VALUES (?,?,?,'waiting_pc',?,NULL)",
                           (principal, payload['command_id'], changes['host_request'], self.now()))
                updated.pop('host_request')
            if 'engineering_enabled' in changes:
                current = db.execute('SELECT revision FROM engineering_admission_settings WHERE principal=?', (principal,)).fetchone()
                db.execute('INSERT OR REPLACE INTO engineering_admission_settings VALUES (?,?,?)',
                           (principal, changes['engineering_enabled'], (current[0] if current else 0) + 1))
            updated.pop('engineering_enabled')
            pending = [item for item in snapshot['pending'] if item['field'] not in changes and item['field'] != 'engineering_enabled']
            for field in changes:
                if field not in {'engineering_enabled', 'host_request'} and updated[field] != snapshot['effective'][field]:
                    pending.append({'field': field, 'reason': 'awaiting_supervisor', 'blocking_jobs': []})
            revision = snapshot['revision'] + 1
            db.execute('UPDATE gateway_control_settings SET revision=?,desired=?,pending=?,author=?,changed=? WHERE id=1',
                       (revision, encoded(updated), encoded(pending), principal, self.now()))
            db.execute('INSERT INTO gateway_control_commands VALUES (?,?,?,?,?,?)',
                       (principal, payload['command_id'], digest, revision, encoded(changes), self.now()))
            return self._receipt(db, principal, payload['command_id'], local)

    @staticmethod
    def _rejection(db, principal, payload, digest, snapshot, error):
        receipt = {'schema': 'gptmobile.gateway.command.v1', 'command_id': payload['command_id'],
                   'installation_id': snapshot['installation_id'], 'principal_id': principal,
                   'revision': snapshot['revision'], 'status': 'rejected', 'dispatch_state': 'not_dispatched',
                   'http_status': error.status, 'error': {'code': error.code, 'message': str(error)}, 'snapshot': snapshot}
        db.execute('INSERT INTO gateway_control_rejections VALUES (?,?,?,?)', (principal, payload['command_id'], digest, encoded(receipt)))
        return receipt

    def _receipt(self, db, principal, command_id, local=False):
        row = db.execute('SELECT * FROM gateway_control_commands WHERE principal=? AND id=?', (principal, command_id)).fetchone()
        if not row:
            rejected = db.execute('SELECT receipt FROM gateway_control_rejections WHERE principal=? AND id=?', (principal, command_id)).fetchone()
            if rejected:
                self._snapshot(db, principal, local)
                return json.loads(rejected[0])
            raise ControlError('command_not_found', 404)
        snapshot = self._snapshot(db, principal, local)
        fields = set(json.loads(row['changes']))
        pending = [item for item in snapshot['pending'] if item['field'] in fields]
        status = 'setup_required' if any(x['reason'] in {'probe_failed', 'invalid_backend', 'host_action_required', 'runtime_unavailable', 'setup_required', 'validation_policy_changed', 'validation_profile_changed', 'validation_profile_setup_required'} for x in pending) else 'waiting_for_jobs' if pending else 'applied'
        if 'host_request' in fields:
            status = db.execute('SELECT state FROM gateway_control_host_requests WHERE principal=? AND id=?', (principal, command_id)).fetchone()[0]
        elif any(snapshot['desired'][key] != value for key, value in json.loads(row['changes']).items()):
            status = 'superseded'
        return {'schema': 'gptmobile.gateway.command.v1', 'command_id': command_id,
                'installation_id': snapshot['installation_id'], 'principal_id': principal,
                'revision': row['revision'], 'status': status, 'requested': json.loads(row['changes']), 'snapshot': snapshot}

    @staticmethod
    def _host_requests(db, principal=None):
        where, parameters = (' WHERE principal=?', (principal,)) if principal is not None else ('', ())
        rows = db.execute('SELECT * FROM gateway_control_host_requests' + where +
                          " ORDER BY (state='waiting_pc') DESC,created DESC LIMIT " + ('16' if principal else '128'), parameters)
        return [{'command_id': row['id'], 'principal_id': row['principal'], 'request': row['request'],
                 'status': row['state'], 'created_at': row['created'], 'reviewed_at': row['reviewed']} for row in rows]

    def host_requests(self):
        with self.tx() as db:
            return {'items': self._host_requests(db)}

    def review_host_request(self, payload):
        if (not isinstance(payload, dict) or set(payload) != {'installation_id', 'principal_id', 'command_id'}
                or not isinstance(payload['principal_id'], str) or len(payload['principal_id']) > 256
                or not isinstance(payload['command_id'], str) or not ID.fullmatch(payload['command_id'])):
            raise ControlError('invalid_host_request')
        with self.tx() as db:
            if payload['installation_id'] != self.installation(db):
                raise ControlError('installation_changed', 409)
            row = db.execute('SELECT state FROM gateway_control_host_requests WHERE principal=? AND id=?', (payload['principal_id'], payload['command_id'])).fetchone()
            if not row:
                raise ControlError('command_not_found', 404)
            db.execute("UPDATE gateway_control_host_requests SET state='reviewed',reviewed=COALESCE(reviewed,?) WHERE principal=? AND id=?",
                       (self.now(), payload['principal_id'], payload['command_id']))
            return self._receipt(db, payload['principal_id'], payload['command_id'], local=True)

    @staticmethod
    def _backend_options(db, current):
        # Reserve one slot for the currently host-selected route. Approval is
        # bounded and never imports the old prototype's broader grants.
        return list(dict.fromkeys([current, *(row[0] for row in db.execute(
            'SELECT origin FROM gateway_control_backends ORDER BY approved,origin LIMIT 15'))]))

    def approve_backend(self, origin):
        with self.tx() as db:
            current = self._snapshot(db, 'local:single-user', local=True)['desired']
            cfg = load(self.root)
            listener = self.listener() if self.listener else (cfg['bind_host'], cfg['port'], cfg['network_hostnames'].split(',') + [socket.gethostname()])
            validate_changes({'backend': origin}, current, local=True, listener=listener)
            if (not db.execute('SELECT 1 FROM gateway_control_backends WHERE origin=?', (origin,)).fetchone()
                    and db.execute('SELECT COUNT(*) FROM gateway_control_backends').fetchone()[0] >= 15):
                raise ControlError('approved_backends_full', 409)
            db.execute('INSERT OR IGNORE INTO gateway_control_backends VALUES (?,?)', (origin, self.now()))
            return {'backend': origin, 'approved': True}

    def observe_runtime(self, ready, prerequisites):
        with self.tx() as db:
            db.execute('INSERT OR REPLACE INTO gateway_control_runtime VALUES (1,?,?,?)',
                       (self.now(), bool(ready), encoded(prerequisites)))

    def receipt(self, principal, command_id, local=False):
        with self.tx() as db:
            return self._receipt(db, principal, command_id, local)

    def apply_observed(self, revision, effective, pending):
        with self.tx() as db:
            row = db.execute('SELECT revision,effective,pending FROM gateway_control_settings WHERE id=1').fetchone()
            if row['revision'] != revision:
                return False
            values = encoded(effective), encoded(pending)
            if values != (row['effective'], row['pending']):
                db.execute('UPDATE gateway_control_settings SET effective=?,pending=? WHERE id=1', values)
            return True

    @staticmethod
    def digest(value):
        return hashlib.sha256(value.encode()).hexdigest()

    def pairing_create(self, payload, *, connect=False):
        name = payload.get('name', 'Android phone')
        fields = payload.get('fields', sorted(FIELDS))
        repositories = payload.get('repositories', [])
        role = payload.get('role', 'owner')
        if (not isinstance(name, str) or not 1 <= len(name) <= 100 or any(ord(c) < 32 for c in name)
                or role not in {'owner', 'viewer'} or not isinstance(fields, list) or any(x not in FIELDS for x in fields)
                or not isinstance(repositories, list) or len(repositories) > 256
                or any(not isinstance(x, str) or not REPOSITORY.fullmatch(x) for x in repositories)):
            raise ControlError('invalid_pairing_scope')
        # A PC-issued connection link grants exactly one named repository. Wider
        # management grants continue through the fingerprint-confirmation flow.
        if connect and (role != 'owner' or fields != ['engineering_enabled'] or len(repositories) != 1):
            raise ControlError('invalid_connection_scope')
        code, ident = secrets.token_urlsafe(32), uuid.uuid4().hex
        with self.tx() as db:
            installation = self.installation(db)
            db.execute('DELETE FROM gateway_control_pairing WHERE expires<=?', (self.now(),))
            if db.execute('SELECT COUNT(*) FROM gateway_control_pairing').fetchone()[0] >= 16:
                raise ControlError('too_many_pairing_invitations', 429)
            expires = int(self.now()) + (120 if connect else 300)
            db.execute('INSERT INTO gateway_control_pairing VALUES (?,?,?,?,?,NULL,NULL,?,?,?)',
                       (ident, self.digest(code), name, expires, 'connect_ready' if connect else 'unused', encoded(sorted(set(fields))), encoded(sorted(set(repositories))), role))
        return {'pairing_id': ident, 'code': code, 'installation_id': installation, 'expires_at': expires}

    def pairing_claim(self, code, payload):
        if not isinstance(payload, dict) or set(payload) != {'installation_id', 'device_name', 'public_key'}:
            raise ControlError('invalid_pairing_claim')
        name = payload['device_name']
        if not isinstance(name, str) or not 1 <= len(name) <= 100 or any(ord(c) < 32 for c in name):
            raise ControlError('invalid_device_name')
        with self.tx() as db:
            if payload['installation_id'] != self.installation(db):
                raise ControlError('installation_changed', 409)
            row = db.execute('SELECT * FROM gateway_control_pairing WHERE code_hash=? AND expires>?', (self.digest(code), self.now())).fetchone()
            if not row or row['state'] == 'denied':
                raise ControlError('pairing_expired_or_used', 401)
            if row['public_key'] and row['public_key'] != payload['public_key']:
                raise ControlError('pairing_expired_or_used', 401)
            state = row['state']
            if state in {'unused', 'connect_ready'}:
                state = 'connect_approved' if state == 'connect_ready' else 'awaiting_confirmation'
                db.execute("UPDATE gateway_control_pairing SET name=?,state=?,device_id=?,public_key=? WHERE id=?",
                           (name, state, uuid.uuid4().hex, payload['public_key'], row['id']))
            return {'pairing_id': row['id'], 'state': 'approved' if state == 'connect_approved' else state, 'expires_at': row['expires']}

    def pairing_key_allowed(self, code, public_key):
        # Refuse unknown invitations before consuming replay-cache entries for
        # an unauthenticated public key.
        with self.tx() as db:
            row = db.execute('SELECT public_key,state FROM gateway_control_pairing WHERE code_hash=? AND expires>?',
                             (self.digest(code), self.now())).fetchone()
            if not row or row['state'] == 'denied' or row['public_key'] and row['public_key'] != public_key:
                raise ControlError('pairing_expired_or_used', 401)

    def pairings(self):
        with self.tx() as db:
            rows = db.execute('SELECT * FROM gateway_control_pairing WHERE expires>? ORDER BY expires', (self.now(),)).fetchall()
            return [{'pairing_id': x['id'], 'device_name': x['name'], 'state': x['state'], 'expires_at': x['expires'],
                     'key_fingerprint': self.digest(x['public_key']) if x['public_key'] else None,
                     'fields': json.loads(x['fields']), 'repositories': json.loads(x['repositories']), 'role': x['role']} for x in rows]

    def pairing_confirm(self, pairing_id, approved):
        if type(approved) is not bool:
            raise ControlError('invalid_pairing_confirmation')
        with self.tx() as db:
            row = db.execute('SELECT * FROM gateway_control_pairing WHERE id=? AND expires>?', (pairing_id, self.now())).fetchone()
            if not row or row['state'] not in {'awaiting_confirmation', 'approved'}:
                raise ControlError('pairing_not_pending', 409)
            db.execute('UPDATE gateway_control_pairing SET state=? WHERE id=?', ('approved' if approved else 'denied', pairing_id))
            if not approved and row['device_id']:
                db.execute('UPDATE gateway_control_devices SET revoked=1 WHERE id=?', (row['device_id'],))
        return {'pairing_id': pairing_id, 'state': 'approved' if approved else 'denied'}

    def pairing_finish(self, code, payload, secret):
        if not isinstance(payload, dict) or set(payload) != {'pairing_id', 'public_key'}:
            raise ControlError('invalid_pairing_finish')
        with self.tx() as db:
            row = db.execute('SELECT * FROM gateway_control_pairing WHERE id=? AND code_hash=? AND expires>?',
                             (payload['pairing_id'], self.digest(code), self.now())).fetchone()
            if not row or row['public_key'] != payload['public_key'] or row['state'] == 'denied':
                raise ControlError('pairing_expired_or_used', 401)
            if row['state'] not in {'approved', 'connect_approved'}:
                return {'pairing_id': row['id'], 'state': 'awaiting_confirmation'}, 202
            # Reconstruct the same response after a lost delivery. Only its hash
            # is stored; the server secret is protected separately from exports.
            token = base64.urlsafe_b64encode(hmac.new(secret, ('control-device:' + row['device_id']).encode(), hashlib.sha256).digest()).decode().rstrip('=')
            db.execute('INSERT OR IGNORE INTO gateway_control_devices VALUES (?,?,?,?,?,?,?,0,?)',
                       (row['device_id'], row['name'], row['public_key'], self.digest(token), row['role'], row['fields'], row['repositories'], self.now()))
            device = self.device(db, 'paired:' + row['device_id'])
            if not device:
                raise ControlError('access_revoked', 401)
            quick = row['state'] == 'connect_approved'
            db.execute('INSERT OR IGNORE INTO engineering_admission_settings VALUES (?,?,0)', ('paired:' + row['device_id'], int(quick)))
            return {'token': token, 'installation_id': self.installation(db), 'principal_id': 'paired:' + row['device_id'],
                    'device_id': row['device_id'], 'role': row['role'], 'engineering_connection': quick}, 200

    def authenticate(self, token):
        if not token or len(token) > 256:
            raise ControlError('device_credential_required', 401)
        with self.tx() as db:
            row = db.execute('SELECT * FROM gateway_control_devices WHERE token_hash=? AND revoked=0', (self.digest(token),)).fetchone()
            if not row:
                raise ControlError('access_revoked', 401)
            return dict(row)

    def nonce(self, principal, nonce, timestamp):
        if not isinstance(nonce, str) or not re.fullmatch('[a-fA-F0-9-]{16,64}', nonce) or abs(self.now() - timestamp) > 90:
            raise ControlError('signature_expired', 401)
        with self.tx() as db:
            db.execute('DELETE FROM gateway_control_nonces WHERE expires<=?', (self.now(),))
            if db.execute('SELECT COUNT(*) FROM gateway_control_nonces').fetchone()[0] >= 20000:
                raise ControlError('replay_cache_full', 429)
            try:
                db.execute('INSERT INTO gateway_control_nonces VALUES (?,?,?)', (principal, nonce, self.now() + 180))
            except sqlite3.IntegrityError:
                raise ControlError('request_replayed', 401) from None

    def devices(self):
        with self.tx() as db:
            return [{'device_id': x['id'], 'principal_id': 'paired:' + x['id'], 'name': x['name'], 'role': x['role'],
                     'fields': json.loads(x['fields']), 'repositories': json.loads(x['repositories']),
                     'revoked': bool(x['revoked']), 'key_fingerprint': self.digest(x['public_key'])}
                    for x in db.execute('SELECT * FROM gateway_control_devices ORDER BY created DESC')]

    def revoke(self, device_id):
        with self.tx() as db:
            db.execute('UPDATE gateway_control_devices SET revoked=1 WHERE id=?', (device_id,))
        return {'device_id': device_id, 'revoked': True}


def retained_device_grant(path, principal):
    """Read-only runtime recheck; never initialize tables or grant a shared user."""
    if not principal.startswith('paired:'):
        return None
    try:
        db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True, timeout=2)
        try:
            db.row_factory = sqlite3.Row
            row = db.execute('SELECT * FROM gateway_control_devices WHERE id=? AND revoked=0', (principal[7:],)).fetchone()
            return dict(row) if row else None
        finally:
            db.close()
    except (OSError, sqlite3.Error):
        return None
