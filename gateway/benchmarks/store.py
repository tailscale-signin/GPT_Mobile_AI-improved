"""Bounded SQLite journal. Read-only clients never own execution or replay."""
from contextlib import contextmanager
import json
from pathlib import Path
import sqlite3
import threading
import time
import uuid

from benchmarks.contracts import SCHEMA, TERMINAL, digest, validate_request


class BenchmarkConflict(ValueError):
    pass


class BenchmarkStore:
    def __init__(self, path, now=time.time):
        self.path, self.now = Path(path), now
        self.lock = threading.RLock()

    @contextmanager
    def db(self):
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            db = sqlite3.connect(self.path, timeout=10)
            try:
                db.execute('PRAGMA journal_mode=WAL')
                db.execute('PRAGMA foreign_keys=ON')
                db.execute('CREATE TABLE IF NOT EXISTS benchmark_runs (id TEXT PRIMARY KEY, principal TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL, updated REAL NOT NULL, payload TEXT NOT NULL, UNIQUE(principal,request_id))')
                db.execute('CREATE TABLE IF NOT EXISTS benchmark_cases (run_id TEXT NOT NULL REFERENCES benchmark_runs(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,ordinal))')
                db.execute('CREATE TABLE IF NOT EXISTS benchmark_request_tombstones (request_key TEXT PRIMARY KEY, removed REAL NOT NULL)')
                with db:
                    yield db
            finally:
                db.close()

    def create(self, principal, request, provenance):
        request = validate_request(request)
        fingerprint = digest(request)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT payload,fingerprint FROM benchmark_runs WHERE principal=? AND request_id=?', (principal, request['request_id'])).fetchone()
            if old:
                if old[1] != fingerprint:
                    raise BenchmarkConflict('The request identity already belongs to a different benchmark')
                return json.loads(old[0]), False
            if db.execute('SELECT 1 FROM benchmark_request_tombstones WHERE request_key=?',
                          (digest([principal, request['request_id']]),)).fetchone():
                raise BenchmarkConflict('This request belongs to a removed result. Start a new benchmark with a new request identity.')
            if db.execute("SELECT COUNT(*) FROM benchmark_runs WHERE state NOT IN ('COMPLETED','FAILED','BLOCKED','CANCELLED','INTERRUPTED')").fetchone()[0] >= 4:
                raise BenchmarkConflict('Four benchmark runs are already queued; wait or cancel one')
            count = db.execute('SELECT COUNT(*) FROM benchmark_runs').fetchone()[0]
            if count >= 500:
                raise BenchmarkConflict('Benchmark history is full; export and remove old results in Benchmark History')
            now = self.now()
            run = dict(schema=SCHEMA, id='bench_' + uuid.uuid4().hex, request=request,
                       provenance=provenance, state='QUEUED', createdAt=now, updatedAt=now,
                       startedAt=None, endedAt=None, planned=0, completed=0, progress='Queued',
                       cancellationRequested=False, summary=None, executionOwner='GATEWAY', detachSafe=True)
            db.execute('INSERT INTO benchmark_runs VALUES(?,?,?,?,?,?,?)',
                       (run['id'], principal, request['request_id'], fingerprint, run['state'], now, json.dumps(run, allow_nan=False)))
            return run, True

    def get(self, ident, principal=None, cases=True):
        with self.db() as db:
            row = db.execute('SELECT payload,principal FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            if not row or principal is not None and row[1] != principal:
                return None
            run = json.loads(row[0])
            if cases:
                run['cases'] = [json.loads(x[0]) for x in db.execute('SELECT payload FROM benchmark_cases WHERE run_id=? ORDER BY ordinal', (ident,)).fetchall()]
            return run

    def owner(self, ident):
        with self.db() as db:
            row = db.execute('SELECT principal FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            return row[0] if row else None

    def submission(self, principal, request_id):
        if not isinstance(request_id, str) or not 1 <= len(request_id) <= 128 or request_id != request_id.strip() or any(ord(c) < 32 for c in request_id):
            raise ValueError('Invalid benchmark request identity')
        with self.db() as db:
            row = db.execute('SELECT payload FROM benchmark_runs WHERE principal=? AND request_id=?', (principal, request_id)).fetchone()
            if row:
                return {'submissionState': 'OBSERVED', 'run': json.loads(row[0])}
            removed = db.execute('SELECT 1 FROM benchmark_request_tombstones WHERE request_key=?',
                                 (digest([principal, request_id]),)).fetchone()
            return {'submissionState': 'REMOVED' if removed else 'NOT_OBSERVED', 'run': None}

    def history(self, principal, limit=50, before=None):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError('Invalid history page size')
        with self.db() as db:
            if before is None:
                rows = db.execute('SELECT rowid,payload FROM benchmark_runs WHERE principal=? ORDER BY rowid DESC LIMIT ?', (principal, limit + 1)).fetchall()
            else:
                if type(before) is not int or before < 1:
                    raise ValueError('Invalid history cursor')
                rows = db.execute('SELECT rowid,payload FROM benchmark_runs WHERE principal=? AND rowid<? ORDER BY rowid DESC LIMIT ?', (principal, before, limit + 1)).fetchall()
            return {'runs': [json.loads(x[1]) for x in rows[:limit]], 'next': rows[limit - 1][0] if len(rows) > limit else None}

    def update(self, ident, **fields):
        if set(fields) - {'state', 'progress', 'planned', 'completed', 'startedAt', 'endedAt', 'summary', 'cancellationRequested', 'failure'}:
            raise ValueError('Unsupported benchmark checkpoint')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT payload FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            if not row:
                raise ValueError('Benchmark is unavailable')
            run = json.loads(row[0])
            if run['state'] in TERMINAL:
                if any(run.get(key) != value for key, value in fields.items()):
                    raise BenchmarkConflict('A terminal benchmark is immutable; create a new run')
                return run
            run.update(fields, updatedAt=self.now())
            if run['state'] in TERMINAL and run['state'] != 'COMPLETED':
                # An interrupted parent cannot leave a case visibly running or
                # publish a score computed before a final identity/checkpoint
                # failure. Keep each completed attempt's separate evidence.
                outcome = {'FAILED': 'ERROR', 'BLOCKED': 'BLOCKED', 'CANCELLED': 'CANCELLED', 'INTERRUPTED': 'INTERRUPTED'}[run['state']]
                for ordinal, payload in db.execute('SELECT ordinal,payload FROM benchmark_cases WHERE run_id=?', (ident,)).fetchall():
                    case = json.loads(payload)
                    if case.get('status') == 'RUNNING':
                        case.update(status=outcome, graded=False, resolved=False, score=None)
                        db.execute('UPDATE benchmark_cases SET payload=? WHERE run_id=? AND ordinal=?',
                                   (json.dumps(case, allow_nan=False), ident, ordinal))
                if isinstance(run.get('summary'), dict):
                    run['summary'].update(score=None, qualified=False, speedup=None, firstSubmissionScore=None,
                                          passAtOne=None, finalResolvedRate=None, invalidatedBy=run['state'])
            db.execute('UPDATE benchmark_runs SET state=?,updated=?,payload=? WHERE id=?',
                       (run['state'], run['updatedAt'], json.dumps(run, allow_nan=False), ident))
            return run

    def case(self, ident, ordinal, result):
        raw = json.dumps(result, allow_nan=False)
        if len(raw.encode()) > 128 * 1024 or type(ordinal) is not int or not 0 <= ordinal < 1000:
            raise ValueError('Benchmark case exceeds its evidence bound')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT payload FROM benchmark_cases WHERE run_id=? AND ordinal=?', (ident, ordinal)).fetchone()
            if old and json.loads(old[0]).get('status') not in {'RUNNING'}:
                if old[0] != raw:
                    raise BenchmarkConflict('Completed case evidence is immutable')
                return
            parent = db.execute('SELECT state FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            if not parent or parent[0] in TERMINAL:
                raise BenchmarkConflict('A terminal benchmark cannot receive new case evidence')
            db.execute('INSERT INTO benchmark_cases VALUES(?,?,?) ON CONFLICT(run_id,ordinal) DO UPDATE SET payload=excluded.payload', (ident, ordinal, raw))

    def pending(self):
        with self.db() as db:
            return [x[0] for x in db.execute("SELECT id FROM benchmark_runs WHERE state='QUEUED' ORDER BY rowid").fetchall()]

    def recover(self):
        with self.db() as db:
            rows = db.execute("SELECT id,payload FROM benchmark_runs WHERE state IN ('QUEUED','RUNNING','CANCELLING')").fetchall()
        for ident, raw in rows:
            run = json.loads(raw)
            with self.db() as db:
                for ordinal, payload in db.execute('SELECT ordinal,payload FROM benchmark_cases WHERE run_id=?', (ident,)).fetchall():
                    case = json.loads(payload)
                    if case.get('status') == 'RUNNING':
                        case.update(status='INTERRUPTED', graded=False, resolved=False, score=None,
                                    error='PROCESS_INTERRUPTED')
                        db.execute('UPDATE benchmark_cases SET payload=? WHERE run_id=? AND ordinal=?',
                                   (json.dumps(case, allow_nan=False), ident, ordinal))
            self.update(ident, state='INTERRUPTED', endedAt=self.now(),
                        progress='Gateway restarted. Retained evidence is available; start a new run to repeat.',
                        failure={'code': 'PROCESS_INTERRUPTED', 'dispatched': run['state'] != 'QUEUED'})

    def remove(self, ident, principal):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT state,request_id FROM benchmark_runs WHERE id=? AND principal=?', (ident, principal)).fetchone()
            if not row:
                return False
            if row[0] not in TERMINAL:
                raise BenchmarkConflict('Stop the benchmark before removing its saved result')
            # Removing presentation/history must never make an uncertain retry
            # admissible again. Tombstones contain only a hash and timestamp.
            # At the finite bound, refuse deletion instead of forgetting identity.
            if db.execute('SELECT COUNT(*) FROM benchmark_request_tombstones').fetchone()[0] >= 10000:
                raise BenchmarkConflict('The removed-request identity journal is full; history removal is unavailable')
            db.execute('INSERT INTO benchmark_request_tombstones VALUES(?,?)',
                       (digest([principal, row[1]]), self.now()))
            db.execute('DELETE FROM benchmark_runs WHERE id=? AND principal=?', (ident, principal))
            return True
