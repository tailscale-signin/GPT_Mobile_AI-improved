"""Revisioned, replayable phone settings. The supervisor projects host changes when safe."""
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from contextlib import closing

from desktop.configuration import atomic_json, load, public_repository_maps, save_public_repository_maps, validate
from desktop.phone_access import directory
from desktop import phone_policies
from v14.state_lock import StateDirectoryLock

FIELDS = {'workers', 'active_chats', 'debug_mode', 'start_at_login', 'update_mode', 'backend', 'public_repository_maps', 'batch_mode', 'validation_profile'}


class Changed(ValueError):
    pass


def write_lock(root):
    return StateDirectoryLock(Path(root) / 'data' / 'settings-write-lock' / 'owner')


def approved_backends(root):
    try:
        values = json.loads((directory(root) / 'backends.json').read_text())
        return [s for s in values if isinstance(s, str)][:16] if isinstance(values, list) else []
    except FileNotFoundError:
        return []


def host_values(root):
    cfg = load(root)
    from v14.engineering_batch_policy import load_policy
    batch = load_policy(Path(root) / 'data' / 'execution-policy.json')
    return {'validation_profile': phone_policies.active(root), **{k: cfg[k] for k in FIELDS if k in cfg}, 'public_repository_maps': public_repository_maps(root), 'batch_mode': batch['default_mode']}


def schema(db):
    db.execute('CREATE TABLE IF NOT EXISTS phone_setting_commands (seq INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, '
               'command TEXT NOT NULL, payload TEXT NOT NULL, old_values TEXT NOT NULL, state TEXT NOT NULL, UNIQUE(device,command))')


def _stamp(value):
    return hashlib.sha256(json.dumps(value, separators=(',', ':'), sort_keys=True).encode()).hexdigest()


def snapshot(root, db, store, device, role, effective, command=None):
    schema(db)
    settings = host_values(root)
    gate = store.admission_settings(db, device)
    pending = db.execute("SELECT command,payload FROM phone_setting_commands WHERE state='queued' ORDER BY seq LIMIT 1").fetchone()
    sequence = db.execute('SELECT COALESCE(MAX(seq),0) FROM phone_setting_commands').fetchone()[0]
    desired = dict(settings)
    if pending:
        patch = json.loads(pending[1])['changes']
        desired.update({k: v for k, v in patch.items() if k in FIELDS})
    desired['engineering'] = gate['enabled'] if role == 'owner' else False
    running = {**settings, **effective, 'engineering': desired['engineering']}
    pending_fields = sorted(k for k in desired if desired[k] != running.get(k))
    from v14.engineering_batch_policy import capability, load_policy
    batch_modes = capability(load_policy(Path(root) / 'data' / 'execution-policy.json'))['modes']
    latest = db.execute('SELECT seq,state,payload FROM phone_setting_commands WHERE device=? ORDER BY seq DESC LIMIT 1', (device,)).fetchone()
    latest_command = json.loads(latest[2]) if latest else {}
    jobs = []
    if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='engineering_jobs'").fetchone():
        jobs = [dict(id=ident, state=state) for ident, state in db.execute(
            "SELECT id,state FROM engineering_jobs WHERE principal=? AND state NOT IN ('COMPLETED','CANCELLED','FAILED') ORDER BY rowid DESC LIMIT 12", (device,))]
    receipt = dict(last_change={'sequence': latest[0], 'state': latest[1], 'field': next(iter(latest_command['changes'])), 'source': latest_command.get('source', 'Android')} if latest else {})

    if command:
        if not re.fullmatch('[0-9a-f]{32}', command):
            raise ValueError('Invalid command identity.')
        row = db.execute('SELECT state FROM phone_setting_commands WHERE device=? AND command=?', (device, command)).fetchone()
        receipt.update(command_id=command, command_state=row[0] if row else 'unconfirmed')
    return dict(**receipt, jobs=jobs, sequence=sequence, validation_profiles=phone_policies.choices(root), batch_modes=batch_modes, protocol='controlSettings.v1', installation=store.conversations.identity(db), device=device, role=role,
                revision=_stamp([settings, gate, sequence, pending[0] if pending else None]), desired=desired,
                effective=running, pending_fields=pending_fields, backends=list(dict.fromkeys([settings['backend'], *approved_backends(root)])))


def change(root, db, store, device, role, effective, body, source="Android"):
    if role != 'owner':
        raise PermissionError('Owner access required.')
    if not isinstance(body, dict) or set(body) != {'command_id', 'installation', 'device', 'revision', 'changes'}:
        raise ValueError('Invalid command.')
    if body['device'] != device or body['installation'] != store.conversations.identity(db):
        raise Changed('Gateway or owner changed.')
    command, patch = body['command_id'], body['changes']
    if not isinstance(command, str) or not re.fullmatch('[0-9a-f]{32}', command) or not isinstance(patch, dict) or len(patch) != 1:
        raise ValueError('Change one setting per command.')
    current = snapshot(root, db, store, device, role, effective)
    encoded = json.dumps(dict(body, source=source), sort_keys=True, separators=(',', ':'))
    existing = db.execute('SELECT payload,state FROM phone_setting_commands WHERE device=? AND command=?', (device, command)).fetchone()
    if existing:
        previous = json.loads(existing[0])
        previous.setdefault('source', 'Android')
        if previous != dict(body, source=source):
            raise Changed('Command identity already used.')
        return dict(current, command_id=command, command_state=existing[1])
    if current['revision'] != body['revision']:
        raise Changed('Settings changed. Refresh the Gateway.')
    key, value = next(iter(patch.items()))
    old = host_values(root)
    if key == 'engineering':
        if type(value) is not bool:
            raise ValueError('Engineering must be on or off.')
        gate = store.admission_settings(db, device)
        if gate['enabled'] != value:
            db.execute('INSERT OR REPLACE INTO engineering_admission_settings VALUES (?,?,?)', (device, value, gate['revision'] + 1))
        state = 'applied'
    elif key == 'host_request':
        if value not in ('Review phone network access', 'Open engineering setup', 'Review pending restart'):
            raise ValueError('Unknown Windows request.')
        if db.execute("SELECT 1 FROM phone_setting_commands WHERE device=? AND state='waiting_pc'", (device,)).fetchone():
            raise Changed('A Windows request is already pending.')
        state = 'waiting_pc'
    elif key in FIELDS:
        if db.execute("SELECT 1 FROM phone_setting_commands WHERE state='queued'").fetchone():
            raise Changed('Another host change is being applied.')
        if key == 'validation_profile':
            if value not in phone_policies.choices(root):
                raise ValueError('Select a Windows-approved validation profile.')
        elif key == 'batch_mode':
            if value not in current['batch_modes']:
                raise ValueError('Batch mode requires host approval.')
        elif key == 'public_repository_maps':
            if type(value) is not bool:
                raise ValueError('Maps must be on or off.')
        else:
            if key == 'backend' and value not in current['backends']:
                raise ValueError('Choose a backend already approved on Windows.')
            validate(dict(load(root), **patch))
        state = 'queued'
    else:
        raise ValueError('Setting is outside the paired grant.')
    db.execute('INSERT INTO phone_setting_commands(device,command,payload,old_values,state) VALUES(?,?,?,?,?)',
               (device, command, encoded, json.dumps(old), state))
    # Earlier commands retain their stale revision even after journal compaction.
    db.execute("DELETE FROM phone_setting_commands WHERE seq < (SELECT MAX(seq)-2048 FROM phone_setting_commands) AND state NOT IN ('queued','saved','waiting_pc')")
    return dict(snapshot(root, db, store, device, role, effective), command_id=command, command_state=state)


def project(root, startup):
    """Recover an interrupted projection by comparing the original and requested field."""
    path = Path(root) / 'data' / 'jobs.sqlite3'
    if not path.exists():
        return
    with closing(sqlite3.connect(path, timeout=.2)) as db:
        if not db.execute("SELECT 1 FROM sqlite_master WHERE name='phone_setting_commands'").fetchone():
            return
        row = db.execute("SELECT seq,device,payload,old_values FROM phone_setting_commands WHERE state='queued' ORDER BY seq LIMIT 1").fetchone()
        if not row:
            return
        change_key, change_value = next(iter(json.loads(row[2])['changes'].items()))
        route_available = True
        if change_key == 'backend' and change_value != host_values(root)['backend']:
            from desktop.benchmark import client_session, models
            from requests import RequestException
            try:
                with client_session() as client:
                    route_available = change_value in approved_backends(root) and bool(models(client, change_value))
            except (OSError, ValueError, RequestException):
                route_available = False
        from desktop.phone_access import authorized
        lock = write_lock(root)
        lock.acquire()
        try:
            with db:
                db.execute('BEGIN IMMEDIATE')
                if db.execute('SELECT state FROM phone_setting_commands WHERE seq=?', (row[0],)).fetchone()[0] != 'queued':
                    return
                key, value = next(iter(json.loads(row[2])['changes'].items()))
                cfg = load(root)
                current = host_values(root)[key]
                if not authorized(root, row[1]):
                    state = 'revoked'
                elif not route_available:
                    state = 'backend_unavailable'
                elif current not in (json.loads(row[3])[key], value):
                    state = 'conflict'
                else:
                    if key == 'validation_profile':
                        try:
                            phone_policies.activate(root, value, current)
                        except (ValueError, OSError):
                            db.execute("UPDATE phone_setting_commands SET state='conflict' WHERE seq=?", (row[0],))
                            return
                    elif key == 'batch_mode':
                        from v14.engineering_batch_policy import capability, load_policy, save_policy
                        policy_path = Path(root) / 'data' / 'execution-policy.json'
                        policy = load_policy(policy_path)
                        if value not in capability(policy)['modes']:
                            db.execute("UPDATE phone_setting_commands SET state='conflict' WHERE seq=?", (row[0],))
                            return
                        save_policy(policy_path, dict(policy, default_mode=value))
                    elif key == 'public_repository_maps':
                        save_public_repository_maps(root, value)
                    else:
                        target = validate(dict(cfg, **{key: value}))
                        if key == 'start_at_login':
                            startup(root, value)
                        atomic_json(Path(root) / 'settings.json', target, retry_windows_sharing=True)
                    state = 'saved'
                db.execute('UPDATE phone_setting_commands SET state=? WHERE seq=?', (state, row[0]))
        finally:
            lock.release()


def needs_activation(root, effective):
    """Only a phone-originated deferred change requests a guarded engine restart."""
    path = Path(root) / 'data' / 'jobs.sqlite3'
    with closing(sqlite3.connect(path, timeout=.2)) as db:
        if not db.execute("SELECT 1 FROM sqlite_master WHERE name='phone_setting_commands'").fetchone():
            return False
        rows = db.execute("SELECT seq,payload FROM phone_setting_commands WHERE state='saved' ORDER BY seq").fetchall()
        current = host_values(root)
        # A map setting is read by the engine from its launch environment.
        for seq, payload in rows:
            key, desired = next(iter(json.loads(payload)['changes'].items()))
            actual = effective.get(key)
            if key in {'workers','active_chats','debug_mode','backend','public_repository_maps','validation_profile'} and current[key] == desired and desired != actual:
                return True
            with db:
                db.execute("UPDATE phone_setting_commands SET state='applied' WHERE seq=? AND state='saved'", (seq,))
        return False
