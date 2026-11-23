"""Bounded SQLite journal. Read-only clients never own execution or replay."""
from contextlib import contextmanager
import json
from pathlib import Path
import re
import sqlite3
import threading
import time
import uuid

from benchmarks.contracts import SCHEMA, TERMINAL, content_digest, coverage, digest, validate_request


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
                db.execute('CREATE TABLE IF NOT EXISTS benchmark_sandboxes (run_id TEXT PRIMARY KEY REFERENCES benchmark_runs(id), container TEXT NOT NULL UNIQUE)')
                db.execute('CREATE TABLE IF NOT EXISTS benchmark_private_submissions (run_id TEXT PRIMARY KEY REFERENCES benchmark_runs(id) ON DELETE CASCADE, proposal TEXT NOT NULL)')
                db.execute('CREATE TABLE IF NOT EXISTS benchmark_cleanup_readbacks (run_id TEXT NOT NULL REFERENCES benchmark_runs(id) ON DELETE CASCADE, ordinal INTEGER NOT NULL, observed REAL NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,ordinal))')
                with db:
                    yield db
            finally:
                db.close()

    def create(self, principal, request, provenance, *, submission=None):
        request = validate_request(request)
        fingerprint = digest(request)
        if request['suite'] == 'coding_submission' and (not isinstance(submission, str) or content_digest(submission) != request.get('proposal_digest')):
            raise ValueError('Private proposal identity is required')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM benchmark_request_tombstones WHERE request_key=?',
                          (digest([principal, request['request_id']]),)).fetchone():
                raise BenchmarkConflict('This request belongs to a removed result. Start a new benchmark with a new request identity.')
            old = db.execute('SELECT payload,fingerprint FROM benchmark_runs WHERE principal=? AND request_id=?', (principal, request['request_id'])).fetchone()
            if old:
                if old[1] != fingerprint:
                    raise BenchmarkConflict('The request identity already belongs to a different benchmark')
                return json.loads(old[0]), False
            if db.execute("SELECT COUNT(*) FROM benchmark_runs WHERE state NOT IN ('COMPLETED','FAILED','BLOCKED','CANCELLED','INTERRUPTED')").fetchone()[0] >= 4:
                raise BenchmarkConflict('Four benchmark runs are already queued; wait or cancel one')
            count = db.execute('SELECT COUNT(*) FROM benchmark_runs').fetchone()[0]
            if count >= 10000:
                raise BenchmarkConflict('Benchmark history is full; export and remove old results in Benchmark History')
            now = self.now()
            run = dict(schema=SCHEMA, id='bench_' + uuid.uuid4().hex, request=request,
                       provenance=provenance, state='QUEUED', createdAt=now, updatedAt=now,
                       startedAt=None, endedAt=None, planned=0, completed=0, progress='Queued',
                       cancellationRequested=False, summary=None, executionOwner='GATEWAY', detachSafe=True)
            if request['suite'] == 'coding_submission':
                run.update(proposalDigest=request['proposal_digest'], fixtureDigest=request['fixture_digest'],
                           taskDigest=request['fixture_digest'], graderDigest=request['grader_digest'],
                           environmentDigest=request.get('environment_digest'))
            db.execute('INSERT INTO benchmark_runs VALUES(?,?,?,?,?,?,?)',
                       (run['id'], principal, request['request_id'], fingerprint, run['state'], now, json.dumps(run, allow_nan=False)))
            if submission is not None:
                db.execute('INSERT INTO benchmark_private_submissions VALUES(?,?)', (run['id'], submission))
            return run, True

    def private_submission(self, ident):
        """Internal evaluator access; never included in list or result responses."""
        with self.db() as db:
            row = db.execute('SELECT proposal FROM benchmark_private_submissions WHERE run_id=?', (ident,)).fetchone()
            if row is None:
                raise ValueError('Private candidate proposal is unavailable')
            return row[0]

    def get(self, ident, principal=None, cases=True):
        with self.db() as db:
            row = db.execute('SELECT payload,principal FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            if not row or principal is not None and row[1] != principal:
                return None
            run = self.project_cleanup(db, json.loads(row[0]))
            if cases:
                run['cases'] = self.read_cases(db, ident)
                run['caseEvidence'] = {'complete': True, 'recordedTotal': len(run['cases']), 'next': None}
                if 'casePlan' in run:
                    observed = {case['id'] for case in run['cases']}
                    run['pendingCaseIds'] = [case for case in run['casePlan'] if case not in observed]
                    run['activeCases'] = [case for case in run['cases'] if case.get('status') == 'RUNNING']
                    run['completed'] = len(run['cases']) - len(run['activeCases'])
                    if run['state'] not in TERMINAL:
                        run['summary'] = dict(run.get('summary') or {}, coverage=coverage(run['cases'], run['planned']))
            return run

    def case_page(self, ident, principal, *, after=-1, limit=25, through=None):
        if type(after) is not int or after < -1 or type(limit) is not int or not 1 <= limit <= 100 or through is not None and (type(through) is not int or through < -1):
            raise ValueError('Invalid benchmark case page')
        with self.db() as db:
            if not db.execute('SELECT 1 FROM benchmark_runs WHERE id=? AND principal=?', (ident, principal)).fetchone():
                raise KeyError(ident)
            latest = db.execute('SELECT COALESCE(MAX(ordinal),-1) FROM benchmark_cases WHERE run_id=?', (ident,)).fetchone()[0]
            upper = latest if through is None else through
            if upper > latest:
                raise ValueError('Case boundary has not been observed')
            total = db.execute('SELECT COUNT(*) FROM benchmark_cases WHERE run_id=? AND ordinal<=?', (ident, upper)).fetchone()[0]
            rows = db.execute('SELECT ordinal,payload FROM benchmark_cases WHERE run_id=? AND ordinal>? AND ordinal<=? ORDER BY ordinal LIMIT ?',
                              (ident, after, upper, limit + 1)).fetchall()
            more = len(rows) > limit
            selected = rows[:limit]
            return {'runId': ident, 'cases': [json.loads(row[1]) for row in selected],
                    'next': selected[-1][0] if more else None, 'complete': not more,
                    'recordedTotal': total, 'throughOrdinal': upper}

    @staticmethod
    def read_cases(db, ident):
        return [json.loads(row[0]) for row in db.execute(
            'SELECT payload FROM benchmark_cases WHERE run_id=? ORDER BY ordinal', (ident,)).fetchall()]

    def owner(self, ident):
        with self.db() as db:
            row = db.execute('SELECT principal FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            return row[0] if row else None

    def submission(self, principal, request_id):
        self.validate_request_id(request_id)
        with self.db() as db:
            row = db.execute('SELECT payload FROM benchmark_runs WHERE principal=? AND request_id=?', (principal, request_id)).fetchone()
            removed = db.execute('SELECT 1 FROM benchmark_request_tombstones WHERE request_key=?',
                                 (digest([principal, request_id]),)).fetchone()
            if row:
                run = self.project_cleanup(db, json.loads(row[0]))
                settled = self.settled(db, run)
                return {'submissionState': 'REMOVED' if removed and settled else 'OBSERVED', 'run': run,
                        'retired': bool(removed), 'terminal': settled}
            return {'submissionState': 'REMOVED' if removed else 'NOT_OBSERVED', 'run': None,
                    'retired': bool(removed), 'terminal': bool(removed),
                    'dispatchState': 'NOT_DISPATCHED' if removed else 'NOT_OBSERVED'}

    @staticmethod
    def validate_request_id(request_id):
        if not isinstance(request_id, str) or not 1 <= len(request_id) <= 128 or request_id != request_id.strip() or any(ord(c) < 32 for c in request_id):
            raise ValueError('Invalid benchmark request identity')

    @staticmethod
    def settled(db, run):
        if run['state'] not in TERMINAL:
            return False
        if db.execute('SELECT 1 FROM benchmark_sandboxes WHERE run_id=?', (run['id'],)).fetchone():
            return False
        receipts = (run.get('failure') or {}).get('cancellationReceipts', [])
        # Sandbox ownership is reconciled by its durable ledger. HTTP jobs with
        # an unknown physical cancellation remain observable until separately settled.
        return not any(row.get('kind') != 'sandbox' and not row.get('container')
                       and row.get('outcome') not in {'confirmed', 'terminal', 'not_canceled'} for row in receipts)

    def retire_request(self, principal, request_id):
        """Fence a delayed admission atomically, retaining any already admitted job."""
        self.validate_request_id(request_id)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('INSERT OR IGNORE INTO benchmark_request_tombstones(request_key,removed) VALUES(?,?)',
                       (digest([principal, request_id]), self.now()))
            row = db.execute('SELECT payload FROM benchmark_runs WHERE principal=? AND request_id=?', (principal, request_id)).fetchone()
            return self.project_cleanup(db, json.loads(row[0])) if row else None

    @staticmethod
    def project_cleanup(db, run):
        latest = db.execute('SELECT payload,observed FROM benchmark_cleanup_readbacks WHERE run_id=? ORDER BY ordinal DESC LIMIT 1', (run['id'],)).fetchone()
        if latest:
            receipt = json.loads(latest[0])
            run['liveGitHubReceipt'] = receipt
            run['cleanupReadbackAt'] = latest[1]
            cleanup = receipt.get('cleanup')
            failure = dict(run.get('failure') or {})
            original = failure.get('cancellationReceipts', [])
            if isinstance(cleanup, dict):
                run['recordedFailure'] = dict(failure)
                key = lambda row: tuple(row.get(field) for field in ('kind', 'repository', 'revision', 'workflowPath'))
                failure['initialCancellationReceipts'] = original
                failure['cancellationReceipts'] = [cleanup if key(row) == key(cleanup) else row for row in original]
                if failure.get('code') == 'CANCELLATION_UNCONFIRMED' and all(row.get('outcome') in {'confirmed', 'terminal', 'not_canceled'} for row in failure['cancellationReceipts']):
                    failure.update(code='BENCHMARK_STOPPED', initialCode='CANCELLATION_UNCONFIRMED')
                run['failure'] = failure
        return run

    def pending_external_cleanups(self):
        with self.db() as db:
            rows = db.execute("SELECT payload,principal FROM benchmark_runs WHERE state='INTERRUPTED' AND payload LIKE '%CANCELLATION_UNCONFIRMED%'").fetchall()
            pending = []
            for payload, principal in rows:
                run = self.project_cleanup(db, json.loads(payload))
                receipts = (run.get('failure') or {}).get('cancellationReceipts', [])
                if any(row.get('kind') == 'github_actions' and row.get('outcome') not in {'confirmed', 'terminal', 'not_canceled'} for row in receipts):
                    pending.append({'run_id': run['id'], 'principal': principal})
            return pending

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
            return {'runs': [self.project_cleanup(db, json.loads(x[1])) for x in rows[:limit]], 'next': rows[limit - 1][0] if len(rows) > limit else None}

    def update(self, ident, **fields):
        if set(fields) - {'state', 'progress', 'planned', 'completed', 'startedAt', 'endedAt', 'summary', 'cancellationRequested', 'failure', 'casePlan', 'liveGitHubReceipt'}:
            raise ValueError('Unsupported benchmark checkpoint')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT payload FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            if not row:
                raise ValueError('Benchmark is unavailable')
            run = json.loads(row[0])
            if run['state'] in TERMINAL:
                if set(fields) == {'liveGitHubReceipt'}:
                    current = self.project_cleanup(db, run).get('liveGitHubReceipt') or {}
                    receipt = fields['liveGitHubReceipt']
                    mutable = {'cleanup', 'cancellationMutation'}
                    cleanup = receipt.get('cleanup') if isinstance(receipt, dict) else None
                    if (not isinstance(receipt, dict) or not current
                            or {key: value for key, value in receipt.items() if key not in mutable} != {key: value for key, value in current.items() if key not in mutable}
                            or current.get('cancellationMutation') is not None and receipt.get('cancellationMutation') != current['cancellationMutation']
                            or not isinstance(cleanup, dict) or cleanup.get('kind') != 'github_actions'
                            or cleanup.get('outcome') not in {'unknown', 'confirmed', 'terminal'}
                            or any(cleanup.get(key) != current.get(field) for key, field in (
                                ('repository', 'repository'), ('revision', 'publishedRevision'), ('workflowPath', 'workflowPath')))
                            or len(json.dumps(receipt).encode()) > 131072):
                        raise BenchmarkConflict('Cleanup readback changed the immutable live execution identity')
                    if receipt == current:
                        return self.project_cleanup(db, run)
                    ordinal = db.execute('SELECT COALESCE(MAX(ordinal),-1)+1 FROM benchmark_cleanup_readbacks WHERE run_id=?', (ident,)).fetchone()[0]
                    db.execute('INSERT INTO benchmark_cleanup_readbacks VALUES(?,?,?,?)', (ident, ordinal, self.now(), json.dumps(receipt, allow_nan=False)))
                    return self.project_cleanup(db, run)
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
                        case.update(status=outcome, graded=False, resolved=False, score=None, endedAt=run['endedAt'])
                        if (run.get('failure') or {}).get('code') == 'PROCESS_INTERRUPTED':
                            case['error'] = 'PROCESS_INTERRUPTED'
                        db.execute('UPDATE benchmark_cases SET payload=? WHERE run_id=? AND ordinal=?',
                                   (json.dumps(case, allow_nan=False), ident, ordinal))
                # The last case may have been recorded after the last partial
                # summary. Project terminal coverage from the canonical case
                # journal, including the interrupted in-flight attempt, instead
                # of publishing stale counts for an immutable result.
                retained = self.read_cases(db, ident)
                summary = dict(run.get('summary') or {})
                summary.update(score=None, qualified=False, speedup=None, firstSubmissionScore=None,
                               passAtOne=None, finalResolvedRate=None, invalidatedBy=run['state'],
                               coverage=coverage(retained, run['planned']))
                run['summary'] = summary
            if run['state'] in TERMINAL:
                cases = self.read_cases(db, ident)
                run['completionReceipt'] = {'schema': 'gateway.benchmark.completion.v1',
                                            'state': run['state'], 'casesDigest': digest(cases),
                                            'summaryDigest': digest(run.get('summary')),
                                            'digest': digest([ident, run['request'], run['provenance'],
                                                              run['state'], run['endedAt'], run.get('failure'),
                                                              run.get('casePlan'), cases, run.get('summary')])}
            db.execute('UPDATE benchmark_runs SET state=?,updated=?,payload=? WHERE id=?',
                       (run['state'], run['updatedAt'], json.dumps(run, allow_nan=False), ident))
            return run

    def case(self, ident, ordinal, result):
        if result.get('status') == 'RUNNING':
            result = dict(result, checkpointAt=self.now())
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

    def claim_sandbox(self, ident, container):
        if not isinstance(container, str) or not re.fullmatch(r'gateway-benchmark-[a-f0-9]{32}', container):
            raise ValueError('Invalid benchmark sandbox identity')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            run = db.execute('SELECT state FROM benchmark_runs WHERE id=?', (ident,)).fetchone()
            if not run or run[0] not in {'RUNNING', 'CANCELLING'}:
                raise BenchmarkConflict('Only an admitted running benchmark can own a sandbox')
            old = db.execute('SELECT container FROM benchmark_sandboxes WHERE run_id=?', (ident,)).fetchone()
            if old:
                if old[0] != container:
                    raise BenchmarkConflict('The previous benchmark sandbox still needs cleanup')
                return
            # One container per sequential run; the existing 500-run history
            # bound also bounds this journal without dropping older ownership.
            db.execute('INSERT INTO benchmark_sandboxes VALUES(?,?)', (ident, container))

    def pending_sandboxes(self):
        with self.db() as db:
            return [{'run_id': row[0], 'container': row[1]} for row in
                    db.execute('SELECT run_id,container FROM benchmark_sandboxes ORDER BY rowid').fetchall()]

    def release_sandbox(self, ident, container):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT container FROM benchmark_sandboxes WHERE run_id=?', (ident,)).fetchone()
            if old and old[0] != container:
                raise BenchmarkConflict('Sandbox cleanup receipt belongs to a different container')
            db.execute('DELETE FROM benchmark_sandboxes WHERE run_id=? AND container=?', (ident, container))

    def recover(self):
        with self.db() as db:
            rows = db.execute("SELECT id,payload FROM benchmark_runs WHERE state IN ('QUEUED','RUNNING','CANCELLING')").fetchall()
        for ident, raw in rows:
            run = json.loads(raw)
            self.update(ident, state='INTERRUPTED', endedAt=self.now(),
                        progress='Gateway restarted. Retained evidence is available; start a new run to repeat.',
                        failure={'code': 'PROCESS_INTERRUPTED', 'dispatched': False if run['state'] == 'QUEUED' else None})

    def remove(self, ident, principal):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT state,request_id,payload FROM benchmark_runs WHERE id=? AND principal=?', (ident, principal)).fetchone()
            if not row:
                return False
            if row[0] not in TERMINAL:
                raise BenchmarkConflict('Stop the benchmark before removing its saved result')
            if db.execute('SELECT 1 FROM benchmark_sandboxes WHERE run_id=?', (ident,)).fetchone():
                raise BenchmarkConflict('Sandbox cleanup must be confirmed before removing its saved result')
            if not self.settled(db, self.project_cleanup(db, json.loads(row[2]))):
                raise BenchmarkConflict('Physical execution cleanup must be confirmed before removing its saved result')
            # Removing presentation/history must never make an uncertain retry
            # admissible again. Tombstones contain only a hash and timestamp.
            # At the finite bound, refuse deletion instead of forgetting identity.
            if db.execute('SELECT COUNT(*) FROM benchmark_request_tombstones').fetchone()[0] >= 10000:
                raise BenchmarkConflict('The removed-request identity journal is full; history removal is unavailable')
            db.execute('INSERT OR IGNORE INTO benchmark_request_tombstones VALUES(?,?)',
                       (digest([principal, row[1]]), self.now()))
            db.execute('DELETE FROM benchmark_runs WHERE id=? AND principal=?', (ident, principal))
            return True
