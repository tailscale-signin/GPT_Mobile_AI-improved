"""Durable, idempotent admission/readback for frozen production qualifications."""
import copy
import json
import re
import uuid
import threading

from benchmarks.contracts import digest
from benchmarks.engineering_qualification import run_manifest, snapshot, selected_route, validate_expected_route
from benchmarks.store import BenchmarkConflict


class QualificationService:
    def __init__(self, service):
        self.service, self.store = service, service.store
        self.lock = threading.RLock()

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS benchmark_qualifications (id TEXT PRIMARY KEY, principal TEXT NOT NULL, request_id TEXT NOT NULL, fingerprint TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(principal,request_id))')

    @staticmethod
    def public(record):
        return {key: value for key, value in record.items() if key not in {'manifest', 'principal'}}

    def create(self, principal, value):
        if not principal.engineering_allowed:
            raise PermissionError('Engineering scope is required')
        if not isinstance(value, dict) or set(value) != {'request_id', 'manifest', 'execute'} or type(value['execute']) is not bool:
            raise ValueError('Use a frozen manifest and explicit execution choice')
        request_id, manifest = value['request_id'], copy.deepcopy(value['manifest'])
        if not isinstance(request_id, str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', request_id):
            raise ValueError('Use a stable qualification request identity')
        if not isinstance(manifest, dict):
            raise ValueError('A qualification manifest is required')
        validate_expected_route(manifest.get('expected_route'))
        cohort = manifest.get('cohort')
        if manifest.get('disposable') is not True or not isinstance(cohort, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,96}', cohort):
            raise ValueError('Choose an explicit disposable fixture cohort')
        prefix = 'benchmark/' + cohort + '/'
        cases = manifest.get('cases')
        if not isinstance(cases, list):
            raise ValueError('Qualification cases are required')
        for case in cases:
            if not isinstance(case, dict):
                raise ValueError('Invalid qualification case')
            request = case.get('request')
            if request is None and not case.get('job_id'):
                raise ValueError('Each scenario needs its frozen request or an existing job identity')
            if request is not None:
                from v14.engineering import validate_request
                normalized = validate_request(request)
                if (not isinstance(request, dict) or not all(isinstance(request.get(key), str) and request[key].startswith(prefix)
                                                           and normalized[key].startswith(prefix) for key in ('branch', 'base'))):
                    raise ValueError('Qualification writes and merge bases must stay in this benchmark cohort')
                if request.get('repository') != manifest.get('test_repository') or request.get('task_scope') != 'implement':
                    raise ValueError('Each scenario must use the designated implementation repository')
                if any(request.get(key) != manifest['expected_route'][field] for key, field in (
                        ('model', 'model'), ('profile_id', 'profile_uid'), ('execution_binding_id', 'binding_id'), ('source_sha', 'base_sha'))):
                    raise ValueError('Each scenario must preserve the frozen model/profile/base identity')
        request_ids = [case['request']['request_id'] for case in cases if case.get('request')]
        job_ids = [case['job_id'] for case in cases if case.get('job_id')]
        if len(set(request_ids)) != len(request_ids) or len(set(job_ids)) != len(job_ids):
            raise ValueError('Each qualification scenario needs a distinct durable identity')
        # Validate the complete fixed schema without making a provider request.
        class ValidateOnly:
            def submit_engineering(self, _):
                raise AssertionError('Validation cannot submit')
        blank = copy.deepcopy(manifest)
        for case in blank['cases']:
            case.pop('job_id', None)
        run_manifest(ValidateOnly(), blank)
        frozen = digest(value)
        with self.store.db() as db:
            self.schema(db)
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT payload,fingerprint FROM benchmark_qualifications WHERE principal=? AND request_id=?', (principal.id, request_id)).fetchone()
            if old:
                if old[1] != frozen:
                    raise BenchmarkConflict('This qualification request already owns a different frozen manifest')
                return self.public(json.loads(old[0]))
            record = {'id': 'qual_' + uuid.uuid4().hex, 'request_id': request_id,
                      'principal': principal.id, 'manifest': manifest, 'manifestDigest': digest(manifest),
                      'executeAuthorized': value['execute'], 'state': 'SAVED',
                      'createdAt': self.store.now(), 'updatedAt': self.store.now(), 'result': None}
            db.execute('INSERT INTO benchmark_qualifications VALUES(?,?,?,?,?)',
                       (record['id'], principal.id, request_id, frozen, json.dumps(record, allow_nan=False)))
        return self.refresh(principal.id, record['id'], execute=value['execute'])

    def read(self, principal, ident):
        with self.store.db() as db:
            self.schema(db)
            row = db.execute('SELECT payload FROM benchmark_qualifications WHERE principal=? AND id=?', (principal, ident)).fetchone()
            if row is None:
                raise KeyError(ident)
            return json.loads(row[0])

    def save(self, record):
        record['updatedAt'] = self.store.now()
        with self.store.db() as db:
            self.schema(db)
            db.execute('UPDATE benchmark_qualifications SET payload=? WHERE principal=? AND id=?',
                       (json.dumps(record, allow_nan=False), record['principal'], record['id']))

    def history(self, principal, request_id=None):
        with self.store.db() as db:
            self.schema(db)
            if request_id is not None:
                row = db.execute('SELECT payload FROM benchmark_qualifications WHERE principal=? AND request_id=?', (principal, request_id)).fetchone()
                return {'submissionState': 'OBSERVED', 'run': self.public(json.loads(row[0]))} if row else {'submissionState': 'NOT_OBSERVED'}
            rows = db.execute('SELECT payload FROM benchmark_qualifications WHERE principal=? ORDER BY rowid DESC LIMIT 100', (principal,)).fetchall()
            return {'runs': [self.public(json.loads(row[0])) for row in rows]}

    def refresh(self, principal, ident, *, execute=False):
        with self.lock:
            return self._refresh(principal, ident, execute=execute)

    def _refresh(self, principal, ident, *, execute=False):
        record = self.read(principal, ident)
        if execute and not record['executeAuthorized']:
            raise PermissionError('The saved qualification did not authorize execution')
        if not self.service.engineering.authorized({'principal': principal}):
            raise PermissionError('Engineering authority is unavailable')
        parent = self
        class Adapter:
            def submit_engineering(self, request):
                # admit reconciles the stable request/command identity before any
                # new work. A missing HTTP reply is never a fresh write identity.
                binding = parent.service.engineering.validate_submission(principal, request)
                route, _ = selected_route({'request': request, 'execution_binding': binding}, [])
                if route != record['manifest']['expected_route']:
                    raise ValueError('The selected binding differs from the frozen qualification route')
                job = parent.service.engineering.admit(principal, request)
                parent.service.engineering.wake.set()
                return {'id': job['id']}

            def engineering_job(self, job_id):
                job = parent.service.engineering.evidence_access(principal, job_id)
                return {'repository': job['request']['repository']}

            def request(self, method, path):
                matched = re.fullmatch(r'/gateway/engineering/jobs/(eng_[a-f0-9]{32})/qualification\?scenario=([a-z_]+)', path)
                if method != 'GET' or matched is None:
                    raise ValueError('Unsupported qualification readback')
                return snapshot(parent.service.engineering, principal, matched[1], matched[2])
        def checkpoint(manifest):
            record.update(manifest=manifest, state='AWAITING_EVIDENCE',
                          jobIds=[row['job_id'] for row in manifest['cases'] if row.get('job_id')])
            parent.save(record)
        try:
            result = run_manifest(Adapter(), record['manifest'], execute=execute, checkpoint=checkpoint)
            record.update(result=result, state='COMPLETED' if result['qualified'] else 'AWAITING_EVIDENCE',
                          jobIds=[row['job_id'] for row in record['manifest']['cases'] if row.get('job_id')])
            record.pop('error', None)
        except Exception:
            # Do not export provider bodies, private source or guessed failure
            # details. The durable job IDs and original request remain available.
            record.update(state='READBACK_REQUIRED', error='QUALIFICATION_EVIDENCE_UNAVAILABLE')
            self.save(record)
            raise
        self.save(record)
        return self.public(record)
