"""Private content-addressed result storage with bounded authenticated pages.

Payloads and pins commit in the durable journal transaction before a reference is
returned. Debug rotation never owns this data. There is no automatic eviction of
active, uncertain-operation or final-report evidence.
"""
import base64
import hashlib
import json
import uuid

from v14.engineering_crypto import EngineeringCipher
from v14.execution_binding import ExecutionBindingError, canonical, fingerprint

CHUNK = 64 * 1024
MAX_ARTIFACT = 32 * 1024 * 1024


class EngineeringArtifacts:
    def __init__(self, store):
        self.store = store

    @staticmethod
    def schema(db):
        db.execute('CREATE TABLE IF NOT EXISTS engineering_artifacts (id TEXT PRIMARY KEY,job_id TEXT NOT NULL,principal TEXT NOT NULL,digest TEXT NOT NULL,size INTEGER NOT NULL,manifest TEXT NOT NULL,UNIQUE(job_id,digest))')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_artifact_chunks (artifact_id TEXT NOT NULL,sequence INTEGER NOT NULL,aad TEXT NOT NULL,digest TEXT NOT NULL,size INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(artifact_id,sequence))')
        db.execute('CREATE TABLE IF NOT EXISTS engineering_artifact_pins (artifact_id TEXT NOT NULL,job_id TEXT NOT NULL,purpose TEXT NOT NULL,PRIMARY KEY(artifact_id,job_id,purpose))')

    def put_json(self, job, value, purpose):
        def chunks():
            pending = bytearray()
            for text in json.JSONEncoder(sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).iterencode(value):
                raw = text.encode('utf-8')
                for start in range(0, len(raw), CHUNK):
                    pending.extend(raw[start:start + CHUNK])
                    while len(pending) >= CHUNK:
                        yield bytes(pending[:CHUNK])
                        del pending[:CHUNK]
            if pending:
                yield bytes(pending)
        return self.put(job, chunks(), purpose=purpose, media_type='application/json')

    def put(self, job, chunks, *, purpose, media_type='application/octet-stream'):
        if not isinstance(purpose, str) or not 1 <= len(purpose) <= 100:
            raise ValueError('Invalid artifact purpose')
        temporary = 'upload_' + uuid.uuid4().hex
        def save(db):
            self.schema(db)
            principal = db.execute('SELECT principal FROM engineering_jobs WHERE id=?', (job['id'],)).fetchone()
            if principal is None or principal[0] != job['principal']:
                raise PermissionError('Artifact owner mismatch')
            used = db.execute('SELECT COALESCE(SUM(size),0) FROM engineering_artifacts WHERE job_id=?', (job['id'],)).fetchone()[0]
            total = sequence = 0
            digest = hashlib.sha256()
            for block in chunks:
                if not isinstance(block, bytes) or len(block) > CHUNK:
                    raise ValueError('Artifact writer requires bounded byte chunks')
                total += len(block)
                if total > MAX_ARTIFACT or used + total > 256 * 1024 * 1024:
                    raise ExecutionBindingError('EXECUTION_ARTIFACT_CAPACITY', 'Retained evidence reached its private storage quota. Existing pinned artifacts were preserved.', 507)
                digest.update(block)
                aad = job['id'] + ':' + temporary + ':' + str(sequence)
                encrypted = EngineeringCipher.encrypt(db, aad, base64.b64encode(block).decode('ascii'))
                db.execute('INSERT INTO engineering_artifact_chunks VALUES(?,?,?,?,?,?)',
                           (temporary, sequence, aad, hashlib.sha256(block).hexdigest(), len(block), encrypted))
                sequence += 1
            checksum = digest.hexdigest()
            existing = db.execute('SELECT id,manifest FROM engineering_artifacts WHERE job_id=? AND digest=?', (job['id'], checksum)).fetchone()
            if existing:
                db.execute('DELETE FROM engineering_artifact_chunks WHERE artifact_id=?', (temporary,))
                reference = json.loads(existing[1])
            else:
                identity = 'artifact_' + fingerprint([job['principal'], job['id'], checksum])[:40]
                reference = {'schema_version': 1, 'result_ref': identity, 'sha256': checksum, 'byte_length': total,
                    'media_type': media_type, 'owner_scope': fingerprint([job['principal'], job['id']]),
                    'job_id': job['id'], 'repository': job['request']['repository'],
                    'source_sha': job['data'].get('head_sha'), 'coverage': 'COMPLETE', 'availability': 'AVAILABLE'}
                db.execute('INSERT INTO engineering_artifacts VALUES(?,?,?,?,?,?)', (identity, job['id'], job['principal'], checksum, total, canonical(reference)))
                db.execute('UPDATE engineering_artifact_chunks SET artifact_id=? WHERE artifact_id=?', (identity, temporary))
            db.execute('INSERT OR IGNORE INTO engineering_artifact_pins VALUES(?,?,?)', (reference['result_ref'], job['id'], purpose))
            return reference
        return self.store.tx(save)

    def page(self, job, artifact_id, offset=0, length=CHUNK):
        if type(offset) is not int or offset < 0 or type(length) is not int or not 1 <= length <= CHUNK:
            raise ValueError('Invalid artifact range')
        def read(db):
            self.schema(db)
            row = db.execute('SELECT manifest FROM engineering_artifacts WHERE id=? AND job_id=? AND principal=?', (artifact_id, job['id'], job['principal'])).fetchone()
            if row is None:
                raise ExecutionBindingError('EXECUTION_ARTIFACT_UNAVAILABLE', 'The requested artifact is unavailable in this job scope.', 404)
            reference = json.loads(row[0])
            if offset > reference['byte_length']:
                raise ValueError('Artifact range exceeds retained length')
            first, last = offset // CHUNK, (offset + length - 1) // CHUNK
            chunks = db.execute('SELECT sequence,aad,digest,size,payload FROM engineering_artifact_chunks WHERE artifact_id=? AND sequence BETWEEN ? AND ? ORDER BY sequence', (artifact_id, first, last)).fetchall()
            raw = bytearray()
            for sequence, aad, checksum, size, envelope in chunks:
                value = base64.b64decode(EngineeringCipher.decrypt(db, aad, envelope), validate=True)
                if len(value) != size or hashlib.sha256(value).hexdigest() != checksum:
                    raise ExecutionBindingError('EXECUTION_ARTIFACT_CORRUPT', 'The retained artifact failed its integrity check.')
                raw.extend(value)
            value = bytes(raw[offset % CHUNK:offset % CHUNK + length])
            expected = min(length, reference['byte_length'] - offset)
            if len(value) != expected:
                raise ExecutionBindingError('EXECUTION_ARTIFACT_CORRUPT', 'The retained artifact has missing chunks.')
            return {'reference': reference, 'offset': offset, 'byte_length': len(value),
                    'content_base64': base64.b64encode(value).decode(), 'page_sha256': hashlib.sha256(value).hexdigest(),
                    'next_offset': offset + len(value), 'has_more': offset + len(value) < reference['byte_length']}
        return self.store.tx(read)

    def json_value(self, job, reference):
        if reference.get('byte_length', MAX_ARTIFACT + 1) > MAX_ARTIFACT:
            raise ExecutionBindingError('EXECUTION_ARTIFACT_OVERSIZE', 'The retained result exceeds the bounded stage reader.')
        raw, offset = bytearray(), 0
        while True:
            page = self.page(job, reference['result_ref'], offset)
            if page['reference'] != reference:
                raise ExecutionBindingError('EXECUTION_ARTIFACT_CHANGED', 'The retained result reference changed.')
            raw.extend(base64.b64decode(page['content_base64'], validate=True))
            offset = page['next_offset']
            if not page['has_more']:
                break
        if hashlib.sha256(raw).hexdigest() != reference['sha256']:
            raise ExecutionBindingError('EXECUTION_ARTIFACT_CORRUPT', 'The reconstructed result failed its digest check.')
        return json.loads(raw)

    def checkpoint(self, job, value, purpose):
        result = dict(value)
        for key in ('files', 'tree', 'ci', 'model_input', 'raw_result'):
            item = result.get(key)
            if item is not None and not (isinstance(item, dict) and 'result_ref' in item):
                # Stop counting at the view bound instead of materializing JSON.
                size = 0
                for part in json.JSONEncoder(ensure_ascii=False).iterencode(item):
                    size += len(part.encode())
                    if size > 128 * 1024:
                        result[key] = self.put_json(job, item, purpose + ':' + key)
                        break
        return result

    def restore_stage(self, job):
        for key in ('files', 'tree', 'ci', 'model_input', 'raw_result'):
            item = job['data'].get(key)
            if isinstance(item, dict) and 'result_ref' in item:
                job['data'][key] = self.json_value(job, item)
