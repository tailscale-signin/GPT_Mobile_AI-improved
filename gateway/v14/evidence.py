"""Receipt-level verification independent of model sampling, review and prose."""
import hashlib
import json
import re
import time
from v14.engineering_crypto import EngineeringCipher

MAX_AGE_MS = 120000
OMITTED = {'content', 'body', 'text', 'patch', 'message', 'summary', 'description', 'instructions', 'log', 'logs', 'error', 'error_message'}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def facts(result, limit=256):
    found = {}
    def visit(value, path, depth):
        if len(found) >= limit or depth > 12:
            return
        if isinstance(value, dict):
            for key, item in value.items():
                if key.lower() not in OMITTED and not re.search('token|secret|password|authorization|cookie', key, re.I):
                    visit(item, path + '/' + key.replace('~', '~0').replace('/', '~1'), depth + 1)
        elif isinstance(value, list):
            for index, item in enumerate(value[:100]):
                visit(item, path + '/' + str(index), depth + 1)
        elif value is not None and len(str(value)) <= 240:
            found[path] = value
    visit(result, '', 0)
    return found


def verify(claim, receipts, now_ms, epoch):
    receipt_id, pointer = claim.get('receipt_id', ''), claim.get('pointer', '')
    receipt = receipts.get(receipt_id, {})
    values = facts(receipt.get('result'))
    observed = values.get(pointer)
    timestamp = receipt.get('observed_at_ms', 0)
    if not receipt or pointer not in values or 'value' not in claim or receipt.get('success') is not True or receipt.get('trusted') is not True:
        status = 'NOT_MEASURED'
    elif timestamp <= 0 or timestamp > now_ms or (not receipt.get('immutable') and (now_ms - timestamp > MAX_AGE_MS or receipt.get('epoch') != epoch)):
        status = 'STALE'
    elif canonical(observed) != canonical(claim['value']):
        status = 'CONTRADICTED'
    else:
        status = 'VERIFIED'
    result = {'status': status, 'receipt_id': receipt_id, 'pointer': pointer, 'observed_at_ms': timestamp}
    if status in {'VERIFIED', 'CONTRADICTED'}:
        result['observed'] = observed
    return result


class EvidenceLedger:
    """Append-only, hash-linked private records. No model can update or delete them."""
    def __init__(self, store):
        self.store = store

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_evidence ('
                   'job_id TEXT NOT NULL, sequence INTEGER NOT NULL, receipt_id TEXT NOT NULL UNIQUE, '
                   'observed_at_ms INTEGER NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, '
                   'previous_hash TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(job_id,sequence))')
        db.execute("CREATE TRIGGER IF NOT EXISTS engineering_evidence_no_update BEFORE UPDATE ON engineering_evidence BEGIN SELECT RAISE(ABORT, 'Evidence is append-only'); END")
        db.execute("CREATE TRIGGER IF NOT EXISTS engineering_evidence_no_delete BEFORE DELETE ON engineering_evidence BEGIN SELECT RAISE(ABORT, 'Archive the ledger instead of deleting evidence'); END")

    def append(self, job, kind, payload):
        # Private storage only. Headers, credentials and signed redirect URLs are never accepted.
        raw = canonical(payload)
        if len(raw.encode()) > 6 * 1024 * 1024:
            raise ValueError('Evidence record exceeds capacity')
        observed = int(self.store.now() * 1000)
        def save(db):
            self.schema(db)
            count, size = db.execute('SELECT COUNT(*),COALESCE(SUM(LENGTH(CAST(payload AS BLOB))),0) FROM engineering_evidence WHERE job_id=?', (job['id'],)).fetchone()
            if count >= 10000 or size + len(raw.encode()) > 128 * 1024 * 1024:
                raise ValueError('Job evidence capacity reached; retained receipts were not discarded')
            previous = db.execute('SELECT hash FROM engineering_evidence WHERE job_id=? ORDER BY sequence DESC LIMIT 1', (job['id'],)).fetchone()
            previous_hash = previous[0] if previous else ''
            encrypted = EngineeringCipher.encrypt(db, job['id'] + ':' + str(count), raw)
            row = canonical([job['id'], count, observed, kind, encrypted, previous_hash])
            digest = hashlib.sha256(row.encode()).hexdigest()
            receipt_id = 'ev_' + digest[:24]
            db.execute('INSERT INTO engineering_evidence VALUES(?,?,?,?,?,?,?,?)',
                       (job['id'], count, receipt_id, observed, kind, encrypted, previous_hash, digest))
            return {'receipt_id': receipt_id, 'sequence': count, 'observed_at_ms': observed, 'sha256': digest}
        return self.store.tx(save)

    def page(self, job_id, after=-1):
        if type(after) is not int or after < -1:
            raise ValueError('Invalid evidence cursor')
        def read(db):
            self.schema(db)
            rows = db.execute('SELECT sequence,receipt_id,observed_at_ms,kind,previous_hash,hash FROM engineering_evidence WHERE job_id=? AND sequence>? ORDER BY sequence LIMIT 21', (job_id, after)).fetchall()
            # Metadata is the default. Raw source is fetched separately by receipt ID.
            return {'receipts': [{'sequence': row[0], 'receipt_id': row[1], 'observed_at_ms': row[2], 'kind': row[3], 'previous_hash': row[4], 'sha256': row[5]} for row in rows[:20]],
                    'has_more': len(rows) > 20, 'next_cursor': rows[min(len(rows), 20) - 1][0] if rows else after}
        return self.store.tx(read)

    def read(self, job_id, receipt_id):
        def fetch(db):
            self.schema(db)
            row = db.execute('SELECT sequence,observed_at_ms,kind,payload,previous_hash,hash FROM engineering_evidence WHERE job_id=? AND receipt_id=?', (job_id, receipt_id)).fetchone()
            if row is None:
                return None
            expected = hashlib.sha256(canonical([job_id, row[0], row[1], row[2], row[3], row[4]]).encode()).hexdigest()
            if expected != row[5]:
                raise ValueError('Evidence integrity mismatch')
            plaintext = EngineeringCipher.decrypt(db, job_id + ':' + str(row[0]), row[3])
            return {'receipt_id': receipt_id, 'observed_at_ms': row[1], 'kind': row[2], 'payload': json.loads(plaintext), 'sha256': row[5]}
        return self.store.tx(fetch)


def completion_gate(job, readiness, merged=False):
    """Check explicit acceptance contracts and actual CI. Model summaries are not inputs."""
    failures = []
    data = job['data']
    if readiness.get('head_sha') != data.get('head_sha'):
        failures.append('PR head changed')
    checks = readiness.get('checks', [])
    if not checks or readiness.get('checks_passed') is not True:
        failures.append('No complete successful CI receipt')
    for check in checks:
        if check.get('required') is True and check.get('status') != 'SUCCESS':
            failures.append('Required check did not pass: ' + str(check.get('name')))
    changed = set(readiness.get('changed_files', []))
    passed = {check.get('name') for check in checks if check.get('status') == 'SUCCESS'}
    requirements = []
    for requirement in job['request'].get('acceptance', []):
        implemented = set(requirement['paths']) <= changed
        tested = set(requirement['checks']) <= passed
        requirements.append(dict(requirement, status='VERIFIED' if implemented and tested and not failures else 'IMPLEMENTED' if implemented else 'BLOCKED',
                                 scope='Changed paths and exact-head CI only', head_sha=readiness.get('head_sha')))
        if not implemented or not tested:
            failures.append('Acceptance evidence missing: ' + requirement['id'])
    if merged:
        result = data.get('result', {})
        if result.get('merged') is not True or result.get('merge_sha') != data.get('merge_sha'):
            failures.append('Actual merge result was not verified')
    return {'schema': 'github.completion.v1', 'passed': not failures, 'failures': failures,
            'head_sha': data.get('head_sha'), 'acceptance_configured': bool(job['request'].get('acceptance')), 'requirements': requirements,
            'scope': 'Explicit changed-path/check requirements and workflow receipts. Semantic feature completeness is not automatically proven.',
            'benchmark_status': 'NOT_MEASURED', 'local_test_execution': 'NOT_MEASURED'}
