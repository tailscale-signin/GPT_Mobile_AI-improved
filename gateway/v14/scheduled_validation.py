"""Run an explicitly scheduled, host-approved test suite against a local bare mirror."""
from contextlib import closing
import hashlib
import json
import re
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import time
import uuid

from desktop.repository_validation import check_mirror, repository_name
from desktop.validation_policy import load_policy_snapshot
from v14.engineering_validation import load_policy, validate, validate_policy_entry
from v14.validation_host import executable

KIND = 'Test approved repository mirror'


def signature(policy):
    return hashlib.sha256(json.dumps(policy, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def admission(root, value):
    repository = repository_name(value)
    settings = load_policy_snapshot(root)
    if not settings['enabled'] or settings['error'] or repository not in settings['repositories']:
        raise ValueError('Enable and save the validation policy for this repository first.')
    policy, _, _, _, _ = validate_policy_entry(settings['repositories'][repository])
    return repository, signature(policy)


def observed_run(report, head, count):
    if report.get('source_sha') != head or report.get('complete_validation') is not True or report.get('network') != 'disabled':
        return None
    status, commands = report.get('status'), report.get('commands')
    if status not in ('PASSED', 'FAILED') or not isinstance(commands, list) or not 1 <= len(commands) <= count:
        return None
    rows = []
    for item in commands:
        if not isinstance(item, dict) or type(item.get('exit_code')) is not int or type(item.get('elapsed_ms')) is not int or not re.fullmatch('[0-9a-f]{64}', str(item.get('output_sha256'))):
            return None
        rows.append({k: item[k] for k in ('exit_code', 'elapsed_ms', 'output_sha256')})
    if status == 'PASSED' and (len(rows) != count or any(row['exit_code'] != 0 for row in rows)):
        return None
    return {'status': status, 'source_sha': head, 'commands': rows}


class Cancellation:
    def __init__(self, store, key, token, stop):
        self.store, self.key, self.token, self.stop = store, key, token, stop
        self.next_check = 0
        self.removed = False

    def is_set(self):
        if self.stop.is_set() or self.removed:
            return True
        now = time.monotonic()
        if now >= self.next_check:
            self.next_check = now + 1
            with closing(self.store.connect()) as db:
                self.removed = not db.execute('SELECT 1 FROM repository_test WHERE id=? AND token=?', (self.key, self.token)).fetchone()
        return self.removed


def run_tests(store, stop):
    if stop.is_set() or not store.path.exists():
        return
    now = int(store.now())
    token = uuid.uuid4().hex
    with closing(store.connect()) as db, db:
        db.execute('BEGIN IMMEDIATE')
        # A crashed occurrence may have already executed. Preserve that outcome
        # as unverified and never dispatch the same scheduled occurrence again.
        stale = db.execute('SELECT id,last_due FROM repository_test JOIN schedule USING(id) '
                           'WHERE token IS NOT NULL AND expires<=?', (now,)).fetchall()
        for key, due in stale:
            db.execute('UPDATE repository_test SET token=NULL,expires=NULL WHERE id=?', (key,))
            db.execute("UPDATE schedule SET result='Interrupted · test outcome unverified' WHERE id=?", (key,))
            if due is not None:
                store.notify(db, key, due, 'Interrupted · test outcome unverified')
        if db.execute('SELECT 1 FROM repository_test WHERE token IS NOT NULL LIMIT 1').fetchone():
            return
        row = db.execute('''SELECT s.id,s.due,s.period,t.repository,t.policy_hash FROM schedule s
                            JOIN repository_test t ON t.id=s.id WHERE s.due<=? ORDER BY s.due,s.id LIMIT 1''', (now,)).fetchone()
        if not row:
            return
        due, period = row['due'], row['period']
        next_due = due + ((now - due) // period + 1) * period if period else None
        db.execute('UPDATE repository_test SET token=?,expires=?,head=NULL WHERE id=?', (token, now + 2100, row['id']))
        db.execute("UPDATE schedule SET due=?,last_due=?,result='Running · checking approved mirror' WHERE id=?", (next_due, due, row['id']))
    cancel = Cancellation(store, row['id'], token, stop)
    result, head, receipt = 'Blocked · approved validation unavailable', None, None
    try:
        policy, mirror, _, commands, _ = load_policy(row['repository'])
        if signature(policy) != row['policy_hash'] or admission(store.directory.parent, row['repository'])[1] != row['policy_hash']:
            result = 'Blocked · validation policy changed'
        elif not cancel.is_set():
            head = check_mirror(executable('git'), row['repository'], Path(mirror), cancel=cancel)
            job = {'request': {'repository': row['repository']}, 'deadline': time.time() + 1800,
                   'data': {'head_sha': head, 'admission': {'policy_sha256': row['policy_hash']}}}
            report = validate(job, [], cancel)
            receipt = observed_run(report, head, len(commands))
            state = receipt['status'].title() if receipt else 'Blocked' if report.get('status') == 'BLOCKED' else 'Unverified'
            result = state + ' · ' + head[:12]
    except InterruptedError:
        result = 'Interrupted · test outcome unverified'
    except (KeyError, TypeError, OSError, ValueError, subprocess.SubprocessError, tarfile.TarError, sqlite3.Error):
        pass
    with closing(store.connect()) as db, db:
        db.execute('BEGIN IMMEDIATE')
        updated = db.execute('UPDATE repository_test SET token=NULL,expires=NULL,head=? WHERE id=? AND token=?',
                             (head, row['id'], token)).rowcount
        if updated:
            if receipt:
                db.execute('INSERT INTO test_receipt(id,due,receipt) VALUES (?,?,?)', (row['id'], row['due'], json.dumps(receipt)))
                db.execute('DELETE FROM test_receipt WHERE id=? AND due NOT IN (SELECT due FROM test_receipt WHERE id=? ORDER BY due DESC LIMIT 10)', (row['id'], row['id']))
            db.execute('UPDATE schedule SET result=? WHERE id=?', (result, row['id']))
            if not receipt or receipt['status'] == 'FAILED':
                store.notify(db, row['id'], row['due'], result)
