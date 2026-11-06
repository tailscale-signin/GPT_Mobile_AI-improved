"""The v2 backing journal for DebugLogStore, not an execution/effect receipt store.

One JSONL event is at most 2,048 UTF-8 bytes including its newline. The paired
principal/session retains at most 5,000 combined events. Durable sequence state
and short-lived verification digests deliberately outlive rotating event rows.
All public input is projected onto this metadata-only schema before persistence.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import sqlite3
import threading
import time
import uuid
import zipfile

MAX_EVENTS = 5000
MAX_EVENT_BYTES = 2048
MAX_BATCH_EVENTS = 64
MAX_BATCH_BYTES = 65536
MAX_SOURCES = 16
MAX_SESSIONS = 16
MAX_CAPTURE_SECONDS = 7 * 86400
MAX_RANGES = 256
MAX_SEQUENCE_AHEAD = 100000
MAX_DIGESTS = 10000
HEX32 = re.compile(r'[a-f0-9]{32}')
HEX64 = re.compile(r'[a-f0-9]{64}')
SPAN = re.compile(r'[a-f0-9]{16}')
OPAQUE = re.compile(r'(?:[a-f0-9]{32,64}|[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}|[a-z]{2,16}_[a-f0-9]{32,64})')
ENUMS = {
    'stage': {'request', 'response', 'transport', 'lifecycle', 'network', 'model_queue', 'model_load', 'prefill', 'decode',
              'mcp_discovery', 'mcp_call', 'operation_dispatch', 'operation_reconciliation', 'renderer', 'cancellation', 'terminal_delivery', 'unknown'},
    'direction': {'client_to_gateway', 'gateway_to_client', 'gateway_to_backend', 'backend_to_gateway', 'local'},
    'severity': {'debug', 'info', 'warning', 'error', 'critical'},
    'category': {'transport', 'authentication', 'authorization', 'model', 'tool', 'storage', 'renderer', 'lifecycle', 'network', 'cancellation', 'unknown'},
    'owner': {'android', 'gateway', 'backend', 'unknown'},
    'dispatch_state': {'not_dispatched', 'dispatched', 'response_received', 'unknown'},
    'retry_class': {'none', 'safe_read', 'after_backoff', 'reconcile', 'manual', 'unknown'},
    'error_code': {'NONE', 'UNKNOWN_CAUSE', 'HTTP_ERROR', 'TRANSPORT_FAILURE', 'TIMEOUT', 'CANCELLED', 'AUTHENTICATION_FAILED',
                   'AUTHORIZATION_FAILED', 'MODEL_UNAVAILABLE', 'MCP_UNAVAILABLE', 'TOOL_FAILURE', 'REPOSITORY_GRAPH_UNAVAILABLE',
                   'GITHUB_TRANSIENT_NETWORK', 'JAVA_HEAP_PRESSURE', 'STORAGE_UNAVAILABLE', 'RENDERER_STALL', 'SOURCE_UNAVAILABLE',
                   'PRECHECK_REJECTED', 'DISPATCH_UNCERTAIN'},
}
NUMBER_ATTRIBUTES = {'status', 'received_bytes', 'sent_bytes', 'queue_ms', 'load_ms', 'prefill_ms', 'decode_ms', 'output_tokens',
                     'input_tokens', 'frame_count', 'slow_frames', 'dropped_frames', 'gc_count', 'heap_bytes', 'network_epoch',
                     'android_sdk', 'app_version_code', 'occurrence_count', 'source_record_bytes', 'stalls_over_500_ms',
                     'refresh_rate_millihz', 'gc_duration_ms', 'native_heap_bytes'}
DIGEST_ATTRIBUTES = {'profile_digest', 'model_digest', 'endpoint_digest', 'repository_digest', 'app_revision', 'gateway_revision',
                     'capability_digest', 'runtime_config_digest', 'source_event_digest'}
BOOL_ATTRIBUTES = {'vendor_log_available', 'source_available', 'client_metadata_accepted', 'truncation_applied',
                   'response_headers_received', 'response_headers_sent', 'response_body_completed'}
ENUM_ATTRIBUTES = {'route': {'chat', 'models', 'readiness', 'liveness', 'capabilities', 'dashboard', 'job', 'engineering', 'other'},
                   'method': {'GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'}}
LOSS_REASONS = {'OFFLINE_EVICTED', 'QUEUE_FULL', 'RECORD_REJECTED', 'STORAGE_UNAVAILABLE', 'CAPTURE_INTERRUPTED'}


def encoded(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'), sort_keys=True)


def event_identity(origin, boot, sequence):
    return hashlib.sha256(f'{origin}:{boot}:{sequence}'.encode('ascii')).hexdigest()


def integer(value, minimum=0, maximum=2 ** 53):
    return type(value) is int and minimum <= value <= maximum


def identity(value):
    if not isinstance(value, dict):
        return None
    origin, boot, seq = (value.get(key) for key in ('origin_device_pseudonym', 'origin_boot_id', 'origin_sequence'))
    if not isinstance(origin, str) or not HEX32.fullmatch(origin) or not isinstance(boot, str) or not HEX32.fullmatch(boot) or not integer(seq, 1):
        return None
    expected = event_identity(origin, boot, seq)
    return (origin, boot, seq, expected) if value.get('event_id') == expected else None


def sanitize_event(value, *, imported=True):
    """No exception text, URLs, paths, arbitrary attributes or bodies survive this projection."""
    key = identity(value)
    if key is None or type(value.get('schema_version')) is not int or value['schema_version'] != 2:
        return None
    origin, boot, seq, event_id = key
    if not integer(value.get('occurred_at_utc')):
        return None
    result = {'schema_version': 2, 'event_id': event_id, 'origin_device_pseudonym': origin,
              'origin_boot_id': boot, 'origin_sequence': seq, 'occurred_at_utc': value['occurred_at_utc'],
              'redaction_applied': True, 'truncated': value.get('truncated') is True}
    for field, choices in ENUMS.items():
        candidate = value.get(field)
        if isinstance(candidate, str) and candidate in choices:
            result[field] = candidate
    result.setdefault('stage', 'unknown')
    result.setdefault('severity', 'info')
    result['owner'] = 'android' if imported else 'gateway'
    result.setdefault('category', 'unknown')
    result.setdefault('error_code', 'UNKNOWN_CAUSE' if result['severity'] in {'error', 'critical'} else 'NONE')
    result.setdefault('dispatch_state', 'unknown')
    result.setdefault('retry_class', 'unknown')
    if result['error_code'] == 'PRECHECK_REJECTED':
        result['dispatch_state'] = 'not_dispatched'
    elif result['error_code'] == 'DISPATCH_UNCERTAIN':
        result['dispatch_state'], result['retry_class'] = 'unknown', 'reconcile'
    for field in ('trace_id', 'span_id', 'parent_span_id'):
        candidate = value.get(field)
        pattern = HEX32 if field == 'trace_id' else SPAN
        if isinstance(candidate, str) and pattern.fullmatch(candidate) and int(candidate, 16):
            result[field] = candidate
    for field in ('run_id', 'job_id', 'request_id', 'operation_id', 'tool_call_id', 'binding_id', 'incident_id'):
        candidate = value.get(field)
        if isinstance(candidate, str) and OPAQUE.fullmatch(candidate):
            result[field] = candidate
    for field in ('local_duration_ms', 'clock_uncertainty_ms'):
        if integer(value.get(field), 0, 7 * 86400 * 1000):
            result[field] = value[field]
    attributes = value.get('bounded_attributes')
    kept = {}
    if isinstance(attributes, dict):
        for field in NUMBER_ATTRIBUTES:
            if integer(attributes.get(field)):
                kept[field] = attributes[field]
        for field in DIGEST_ATTRIBUTES:
            candidate = attributes.get(field)
            # Revisions are fixed hex, never a free-form build/profile label.
            if isinstance(candidate, str) and re.fullmatch(r'[a-f0-9]{40}|[a-f0-9]{64}', candidate):
                kept[field] = candidate
        for field in BOOL_ATTRIBUTES:
            if type(attributes.get(field)) is bool:
                kept[field] = False if field == 'vendor_log_available' else attributes[field]
        for field, choices in ENUM_ATTRIBUTES.items():
            candidate = attributes.get(field)
            if isinstance(candidate, str) and candidate in choices:
                kept[field] = candidate
    if kept:
        result['bounded_attributes'] = kept
    # Reserve the server-owned observation/cursor fields before accepting a record.
    while len((encoded(dict(result, gateway_ingest_sequence=2 ** 53, observed_at_utc=2 ** 53)) + '\n').encode()) > MAX_EVENT_BYTES:
        if kept:
            kept.pop(sorted(kept)[-1])
            result['truncated'] = True
            if not kept:
                result.pop('bounded_attributes', None)
        else:
            return None
    return result


def trace_context(raw):
    """W3C version 00 only; no baggage or arbitrary tracestate is retained."""
    if not isinstance(raw, str):
        return None
    match = re.fullmatch(r'00-([a-f0-9]{32})-([a-f0-9]{16})-([a-f0-9]{2})', raw)
    if not match or not int(match[1], 16) or not int(match[2], 16):
        return None
    return {'trace_id': match[1], 'parent_span_id': match[2], 'span_id': uuid.uuid4().hex[:16]}


class DiagnosticProtocolError(Exception):
    def __init__(self, code, status=400):
        super().__init__(code)
        self.code, self.status = code, status


def add_range(ranges, start, end):
    result = []
    for a, b in sorted([*ranges, [start, end]]):
        if result and a <= result[-1][1] + 1:
            result[-1][1] = max(b, result[-1][1])
        else:
            result.append([a, b])
    if len(result) > MAX_RANGES:
        raise DiagnosticProtocolError('SOURCE_METADATA_LIMIT', 409)
    return result


def uncovered(start, end, through, ranges):
    start = max(start, through + 1)
    remaining = []
    for a, b in ranges:
        if b < start:
            continue
        if a > end:
            break
        if a > start:
            remaining.append([start, a - 1])
        start = max(start, b + 1)
    if start <= end:
        remaining.append([start, end])
    return remaining


class DiagnosticSessionJournal:
    """SQLite transactions acknowledge only committed ingestion; rows and watermarks commit together.

    The owning DebugLogStore serializes mutations on its existing background writer.
    Reads/exports use short consistent snapshots. Storage faults never enter inference.
    """
    def __init__(self, path, now=time.time):
        self.path, self.now = Path(path), now
        self.lock = threading.RLock()
        self.db = None
        self.storage_error = None
        self.storage_checked = False

    def _connection(self):
        self.storage_checked = True
        if self.db is not None:
            return self.db
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(str(self.path), timeout=1, check_same_thread=False)
        try:
            db.row_factory = sqlite3.Row
            db.execute('PRAGMA foreign_keys=ON')
            # Every read/write uses this one cached connection under self.lock.
            # Exclusive ownership also prevents separate connections from racing
            # WAL checkpoints and commits on older SQLite versions bundled with Python.
            db.execute('PRAGMA locking_mode=EXCLUSIVE')
            # FULL WAL commits sync the append-only log before acknowledgment,
            # without creating, syncing and deleting a rollback journal per batch.
            # That repeated filesystem work stalls large captures on Windows.
            if db.execute('PRAGMA journal_mode=WAL').fetchone()[0].lower() != 'wal':
                raise sqlite3.OperationalError('Durable diagnostic WAL is unavailable')
            db.execute('PRAGMA synchronous=FULL')
            db.executescript('''
                CREATE TABLE IF NOT EXISTS captures(owner TEXT, session TEXT, created INTEGER, last_ingest INTEGER DEFAULT 0,
                    evicted INTEGER DEFAULT 0, rejected INTEGER DEFAULT 0, dropped INTEGER DEFAULT 0, updated INTEGER,
                    PRIMARY KEY(owner,session));
                CREATE TABLE IF NOT EXISTS origins(owner TEXT, session TEXT, origin TEXT, boot TEXT, accounted INTEGER DEFAULT 0,
                    highest_received INTEGER DEFAULT 0, received_count INTEGER DEFAULT 0, lost_count INTEGER DEFAULT 0,
                    ranges TEXT DEFAULT '[]', gaps TEXT DEFAULT '[]', updated INTEGER, kind TEXT NOT NULL,
                    PRIMARY KEY(owner,session,origin,boot), FOREIGN KEY(owner,session) REFERENCES captures ON DELETE CASCADE);
                CREATE TABLE IF NOT EXISTS events(owner TEXT, session TEXT, cursor INTEGER, event_id TEXT, origin TEXT,
                    boot TEXT, sequence INTEGER, payload TEXT, PRIMARY KEY(owner,session,cursor),
                    UNIQUE(owner,session,event_id), FOREIGN KEY(owner,session) REFERENCES captures ON DELETE CASCADE);
                CREATE TABLE IF NOT EXISTS verification(owner TEXT, session TEXT, event_id TEXT, digest TEXT, cursor INTEGER,
                    PRIMARY KEY(owner,session,event_id), FOREIGN KEY(owner,session) REFERENCES captures ON DELETE CASCADE);
                CREATE INDEX IF NOT EXISTS verification_retention ON verification(owner,session,cursor);
            ''')
            db.commit()
        except BaseException:
            db.close()
            raise
        self.db = db
        return db

    def close(self):
        with self.lock:
            if self.db is not None:
                self.db.close()
                self.db = None

    @staticmethod
    def owner_key(principal):
        return hashlib.sha256(principal.encode('utf-8')).hexdigest()

    def _capture(self, db, owner, session, started, create):
        if not isinstance(session, str) or not HEX32.fullmatch(session):
            raise DiagnosticProtocolError('INVALID_CAPTURE_ID')
        now = int(self.now() * 1000)
        if create and (not integer(started) or started > now + 300000 or now - started > MAX_CAPTURE_SECONDS * 1000):
            raise DiagnosticProtocolError('CAPTURE_EXPIRED', 409)
        row = db.execute('SELECT * FROM captures WHERE owner=? AND session=?', (owner, session)).fetchone()
        if row:
            if now - row['created'] > MAX_CAPTURE_SECONDS * 1000:
                raise DiagnosticProtocolError('CAPTURE_EXPIRED', 409)
            if create and row['created'] != started:
                raise DiagnosticProtocolError('CAPTURE_ID_CONFLICT', 409)
            return dict(row)
        if not create:
            raise DiagnosticProtocolError('CAPTURE_NOT_FOUND', 404)
        db.execute('DELETE FROM captures WHERE created < ?', (now - MAX_CAPTURE_SECONDS * 1000,))
        if db.execute('SELECT COUNT(*) FROM captures').fetchone()[0] >= MAX_SESSIONS:
            raise DiagnosticProtocolError('CAPTURE_LIMIT', 429)
        db.execute('INSERT INTO captures(owner,session,created,updated) VALUES(?,?,?,?)', (owner, session, started, now))
        return dict(db.execute('SELECT * FROM captures WHERE owner=? AND session=?', (owner, session)).fetchone())

    @staticmethod
    def _origin(db, owner, session, origin, boot, now, kind):
        if not isinstance(origin, str) or not HEX32.fullmatch(origin) or not isinstance(boot, str) or not HEX32.fullmatch(boot):
            raise DiagnosticProtocolError('INVALID_SOURCE_ID')
        row = db.execute('SELECT * FROM origins WHERE owner=? AND session=? AND origin=? AND boot=?', (owner, session, origin, boot)).fetchone()
        if row:
            result = dict(row)
            if result['kind'] != kind:
                raise DiagnosticProtocolError('SOURCE_ID_CONFLICT', 409)
            result['ranges'], result['gaps'] = json.loads(result['ranges']), json.loads(result['gaps'])
            return result
        if db.execute('SELECT COUNT(*) FROM origins WHERE owner=? AND session=?', (owner, session)).fetchone()[0] >= MAX_SOURCES:
            raise DiagnosticProtocolError('SOURCE_LIMIT', 429)
        db.execute('INSERT INTO origins(owner,session,origin,boot,updated,kind) VALUES(?,?,?,?,?,?)', (owner, session, origin, boot, now, kind))
        return {'owner': owner, 'session': session, 'origin': origin, 'boot': boot, 'accounted': 0, 'highest_received': 0,
                'received_count': 0, 'lost_count': 0, 'ranges': [], 'gaps': [], 'updated': now}

    @staticmethod
    def _account(source, start, end, reason=None):
        if not integer(start, 1) or not integer(end, start) or end > source['accounted'] + MAX_SEQUENCE_AHEAD:
            raise DiagnosticProtocolError('SOURCE_SEQUENCE_WINDOW', 409)
        if reason:
            new = uncovered(start, end, source['accounted'], source['ranges'])
            for a, b in new:
                # Loss ranges are durable, separate from retained events and receipt watermarks.
                prior = source['gaps'][-1] if source['gaps'] else None
                if prior and prior['reason'] == reason and prior['last'] + 1 == a:
                    prior['last'] = b
                else:
                    source['gaps'].append({'first': a, 'last': b, 'reason': reason})
                source['lost_count'] += b - a + 1
            if len(source['gaps']) > MAX_RANGES:
                raise DiagnosticProtocolError('SOURCE_METADATA_LIMIT', 409)
        ranges = add_range(source['ranges'], start, end)
        while ranges and ranges[0][0] <= source['accounted'] + 1:
            source['accounted'] = max(source['accounted'], ranges.pop(0)[1])
        source['ranges'] = ranges

    @staticmethod
    def _save_origin(db, source):
        db.execute('UPDATE origins SET accounted=?,highest_received=?,received_count=?,lost_count=?,ranges=?,gaps=?,updated=? '
                   'WHERE owner=? AND session=? AND origin=? AND boot=?',
                   (source['accounted'], source['highest_received'], source['received_count'], source['lost_count'],
                    encoded(source['ranges']), encoded(source['gaps']), source['updated'], source['owner'], source['session'], source['origin'], source['boot']))

    def ingest(self, principal, batch, *, local=False):
        if not isinstance(batch, dict) or type(batch.get('schema_version')) is not int or batch['schema_version'] != 2:
            raise DiagnosticProtocolError('INVALID_DIAGNOSTICS_SCHEMA')
        rows, gaps = batch.get('events', []), batch.get('gaps', [])
        if not isinstance(rows, list) or len(rows) > MAX_BATCH_EVENTS or not isinstance(gaps, list) or len(gaps) > MAX_BATCH_EVENTS:
            raise DiagnosticProtocolError('DIAGNOSTIC_BATCH_LIMIT', 413)
        # The HTTP handler enforces raw bytes before JSON parsing; direct callers obey the same bound.
        try:
            if len(encoded(batch).encode()) > MAX_BATCH_BYTES:
                raise DiagnosticProtocolError('DIAGNOSTIC_BATCH_LIMIT', 413)
        except (TypeError, ValueError, RecursionError):
            raise DiagnosticProtocolError('INVALID_DIAGNOSTICS_SCHEMA') from None
        owner, session = self.owner_key(principal), batch.get('session_id')
        with self.lock:
            try:
                db = self._connection()
                with db:
                    capture = self._capture(db, owner, session, batch.get('capture_started_at_utc'), True)
                    now = int(self.now() * 1000)
                    sources = {}

                    def source_for(origin, boot):
                        key = origin, boot
                        if key not in sources:
                            sources[key] = self._origin(db, owner, session, origin, boot, now, 'gateway' if local else 'android')
                        return sources[key]

                    rejected, committed, duplicate, accounted = [], [], [], []
                    # Batch the digest lookup rather than doing 50,000 individual
                    # SQLite SELECTs during a large import. Keep per-event inserts
                    # and the transaction's FULL durability contract unchanged.
                    identified = [(raw, identity(raw)) for raw in rows]
                    identities = [key[3] for _, key in identified if key is not None]
                    prior_digests = {}
                    if identities:
                        placeholders = ','.join('?' for _ in identities)
                        prior_digests = {
                            previous['event_id']: previous['digest']
                            for previous in db.execute(
                                f'SELECT event_id,digest FROM verification WHERE owner=? AND session=? AND event_id IN ({placeholders})',
                                (owner, session, *identities))
                        }
                    for raw, key in identified:
                        if key is None:
                            rejected.append({'event_id': None, 'code': 'INVALID_EVENT_ID'})
                            capture['rejected'] += 1
                            continue
                        origin, boot, sequence, event_id = key
                        source = source_for(origin, boot)
                        item = sanitize_event(raw, imported=not local)
                        too_large = len((encoded(raw) + '\n').encode()) > MAX_EVENT_BYTES
                        if item is None or too_large:
                            self._account(source, sequence, sequence, 'RECORD_REJECTED')
                            rejected.append({'event_id': event_id, 'code': 'EVENT_TOO_LARGE' if too_large else 'INVALID_EVENT'})
                            capture['rejected'] += 1
                            continue
                        verification = hashlib.sha256(encoded(item).encode()).hexdigest()
                        prior = prior_digests.get(event_id)
                        if prior is not None:
                            if prior != verification:
                                rejected.append({'event_id': event_id, 'code': 'EVENT_ID_CONFLICT'})
                                capture['rejected'] += 1
                            else:
                                duplicate.append({'event_id': event_id, 'content_verified': True})
                            continue
                        if sequence <= source['accounted'] or any(a <= sequence <= b for a, b in source['ranges']):
                            accounted.append({'event_id': event_id, 'content_verified': False})
                            continue
                        self._account(source, sequence, sequence)
                        capture['last_ingest'] += 1
                        item.update(gateway_ingest_sequence=capture['last_ingest'], observed_at_utc=now)
                        db.execute('INSERT INTO events VALUES(?,?,?,?,?,?,?,?)',
                                   (owner, session, capture['last_ingest'], event_id, origin, boot, sequence, encoded(item)))
                        db.execute('INSERT INTO verification VALUES(?,?,?,?,?)', (owner, session, event_id, verification, capture['last_ingest']))
                        # A repeated ID inside this same batch must be recognized
                        # without another database round-trip.
                        prior_digests[event_id] = verification
                        source['highest_received'] = max(source['highest_received'], sequence)
                        source['received_count'] += 1
                        source['updated'] = now
                        committed.append(event_id)
                    for gap in gaps:
                        if not isinstance(gap, dict) or gap.get('reason') not in LOSS_REASONS:
                            raise DiagnosticProtocolError('INVALID_GAP')
                        source = source_for(gap.get('origin_device_pseudonym'), gap.get('origin_boot_id'))
                        self._account(source, gap.get('first'), gap.get('last'), gap['reason'])
                    count = db.execute('SELECT COUNT(*) FROM events WHERE owner=? AND session=?', (owner, session)).fetchone()[0]
                    excess = max(0, count - MAX_EVENTS)
                    if excess:
                        db.execute('DELETE FROM events WHERE owner=? AND session=? AND cursor IN '
                                   '(SELECT cursor FROM events WHERE owner=? AND session=? ORDER BY cursor LIMIT ?)', (owner, session, owner, session, excess))
                        capture['evicted'] += excess
                    db.execute('DELETE FROM verification WHERE owner=? AND session=? AND cursor <= ?', (owner, session, capture['last_ingest'] - MAX_DIGESTS))
                    for source in sources.values():
                        self._save_origin(db, source)
                    db.execute('UPDATE captures SET last_ingest=?,evicted=?,rejected=?,updated=? WHERE owner=? AND session=?',
                               (capture['last_ingest'], capture['evicted'], capture['rejected'], now, owner, session))
                    watermarks = [self._watermark(source) for source in sources.values()]
                    result = {'schema_version': 2, 'session_id': session, 'committed_event_ids': committed, 'duplicates': duplicate,
                              'already_accounted': accounted, 'rejected': rejected, 'watermarks': watermarks,
                              'last_ingest_sequence': capture['last_ingest'], 'retained_count': min(count, MAX_EVENTS),
                              'evicted_count': capture['evicted'], 'storage_state': 'committed'}
                # Never return a receipt before the transaction's FULL synchronous commit.
                self.storage_error = None
                return result
            except (OSError, sqlite3.Error):
                self.storage_error = 'DEBUG_STORAGE_UNAVAILABLE'
                raise DiagnosticProtocolError(self.storage_error, 503) from None

    @staticmethod
    def _watermark(source, full=False):
        return {'origin_device_pseudonym': source['origin'], 'origin_boot_id': source['boot'],
                'accounted_through': source['accounted'], 'highest_committed_received': source['highest_received'],
                'received_count': source['received_count'], 'lost_count': source['lost_count'],
                'pending_accounted_ranges': source['ranges'] if full else source['ranges'][:16],
                'pending_ranges_truncated': not full and len(source['ranges']) > 16,
                'gaps': source['gaps'] if full else source['gaps'][-16:],
                'gap_ranges_count': len(source['gaps']), 'gap_ranges_truncated': not full and len(source['gaps']) > 16}

    def _snapshot(self, principal, session):
        owner = self.owner_key(principal)
        with self.lock:
            try:
                db = self._connection()
                capture = self._capture(db, owner, session, None, False)
                # All mutations share this lock; these three reads describe the same commit.
                events = [json.loads(row[0]) for row in db.execute('SELECT payload FROM events WHERE owner=? AND session=? ORDER BY cursor', (owner, session))]
                sources = []
                for row in db.execute('SELECT * FROM origins WHERE owner=? AND session=? ORDER BY origin,boot', (owner, session)):
                    source = dict(row)
                    source['ranges'], source['gaps'] = json.loads(source['ranges']), json.loads(source['gaps'])
                    sources.append(source)
                return capture, events, sources
            except (OSError, sqlite3.Error, ValueError):
                self.storage_error = 'DEBUG_STORAGE_UNAVAILABLE'
                raise DiagnosticProtocolError(self.storage_error, 503) from None

    def read(self, principal, session, after=0, limit=64, exclude_origin=None):
        if not integer(after) or not integer(limit, 1, MAX_BATCH_EVENTS) or (exclude_origin is not None and not HEX32.fullmatch(exclude_origin)):
            raise DiagnosticProtocolError('INVALID_DIAGNOSTIC_CURSOR')
        owner = self.owner_key(principal)
        with self.lock:
            try:
                db = self._connection()
                capture = self._capture(db, owner, session, None, False)
                if after > capture['last_ingest']:
                    raise DiagnosticProtocolError('DIAGNOSTIC_CURSOR_AHEAD', 409)
                oldest = db.execute('SELECT MIN(cursor) FROM events WHERE owner=? AND session=?', (owner, session)).fetchone()[0] or capture['last_ingest'] + 1
                events = [json.loads(row[0]) for row in db.execute('SELECT payload FROM events WHERE owner=? AND session=? AND cursor>? AND origin!=? ORDER BY cursor LIMIT ?',
                                                                  (owner, session, after, exclude_origin or '', limit + 1))]
                sources = []
                for row in db.execute('SELECT * FROM origins WHERE owner=? AND session=?', (owner, session)):
                    source = dict(row)
                    source['ranges'], source['gaps'] = json.loads(source['ranges']), json.loads(source['gaps'])
                    sources.append(source)
            except (OSError, sqlite3.Error, ValueError):
                self.storage_error = 'DEBUG_STORAGE_UNAVAILABLE'
                raise DiagnosticProtocolError(self.storage_error, 503) from None
        selected, size, next_cursor = [], 0, after
        for event in events:
            cursor = event['gateway_ingest_sequence']
            if cursor <= after:
                continue
            row_size = len((encoded(event) + '\n').encode())
            if len(selected) >= limit or size + row_size > 48 * 1024:
                break
            selected.append(event)
            size += row_size
            next_cursor = cursor
        if len(selected) == len(events):
            # Remaining committed rows belong to the excluded origin; advance without echoing them.
            next_cursor = max(next_cursor, capture['last_ingest'])
        return {'schema_version': 2, 'session_id': session, 'events': selected, 'next_cursor': next_cursor,
                'oldest_available_cursor': oldest, 'last_ingest_sequence': capture['last_ingest'],
                'has_more': next_cursor < capture['last_ingest'],
                'cursor_gap': {'first': after + 1, 'last': oldest - 1, 'reason': 'EVICTED'} if after + 1 < oldest else None,
                'watermarks': [self._watermark(source) for source in sources], 'evicted_count': capture['evicted'],
                'clock_ordering': 'origin_sequence_and_causal_trace_only', 'clock_uncertainty_ms': None}

    def export(self, principal, session, versions=None):
        capture, events, sources = self._snapshot(principal, session)
        # Re-project even persisted values. Never export database edits as arbitrary text.
        clean = []
        for event in events:
            item = sanitize_event(event, imported=event.get('owner') != 'gateway')
            if item is not None:
                item.update(gateway_ingest_sequence=event['gateway_ingest_sequence'], observed_at_utc=event['observed_at_utc'])
                clean.append(item)
        data = ''.join(encoded(item) + '\n' for item in clean).encode('utf-8')
        now = int(self.now() * 1000)
        coverage = []
        for source in sources:
            retained = [item for item in clean if item['origin_device_pseudonym'] == source['origin'] and item['origin_boot_id'] == source['boot']]
            coverage.append(dict(self._watermark(source, full=True), retained_count=len(retained),
                                 first_retained_sequence=min((x['origin_sequence'] for x in retained), default=None),
                                 last_retained_sequence=max((x['origin_sequence'] for x in retained), default=None),
                                 state='observed_recently' if now - source['updated'] <= 30000 else 'disconnected_or_idle',
                                 last_observed_at_utc=source['updated']))
        owners = {event.get('owner') for event in clean}
        missing = [owner for owner in ('android', 'gateway') if owner not in owners]
        incidents = {}
        for event in clean:
            if event['severity'] not in {'warning', 'error', 'critical'}:
                continue
            cause = event.get('incident_id') or event.get('operation_id') or event.get('request_id') or event.get('trace_id') or event['event_id']
            incident = hashlib.sha256(cause.encode()).hexdigest()
            group = incidents.setdefault(incident, {'incident_id': incident, 'error_codes': [], 'occurrences': 0, 'event_ids': [], 'cause_status': 'unknown' if event['error_code'] == 'UNKNOWN_CAUSE' else 'reported_code'})
            if event['error_code'] not in group['error_codes']:
                group['error_codes'].append(event['error_code'])
            group['occurrences'] += 1
            if len(group['event_ids']) < 16:
                group['event_ids'].append(event['event_id'])
            group['references_truncated'] = group['occurrences'] > len(group['event_ids'])
        manifest = {'schema_version': 2, 'redaction_version': 1, 'snapshot_id': uuid.uuid4().hex, 'session_id': session,
                    'captured_at_utc': now, 'capture_started_at_utc': capture['created'], 'utc_timestamp_unit': 'unix_milliseconds',
                    'event_limit': MAX_EVENTS, 'event_byte_limit': MAX_EVENT_BYTES, 'retained_count': len(clean),
                    'evicted_count': capture['evicted'], 'rejected_count': capture['rejected'],
                    'dropped_count': sum(source['lost_count'] for source in sources), 'export_rejected_count': len(events) - len(clean),
                    'last_ingest_sequence': capture['last_ingest'], 'sources': coverage, 'missing_sources': missing,
                    'vendor_log_available': False, 'source_versions': versions or {},
                    'source_availability': {owner: ('retained_evidence' if owner in owners else 'not_observed') for owner in ('android', 'gateway')},
                    'time_window': {'first_occurred_at_utc': min((x['occurred_at_utc'] for x in clean), default=None),
                                    'last_occurred_at_utc': max((x['occurred_at_utc'] for x in clean), default=None)},
                    'clock_ordering': 'origin_sequence_and_causal_trace_only', 'clock_uncertainty_ms': None,
                    'capture_policy': 'allowlisted_metadata_only; private_sessions_excluded; full_job_evidence_separate',
                    'files': [{'path': 'events.jsonl', 'records': len(clean), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}],
                    'incident_count': len(incidents), 'operation_count': len({x['operation_id'] for x in clean if x.get('operation_id')}),
                    'confirmed_defect_count': None, 'incidents': list(incidents.values()),
                    'complete': not missing and not capture['evicted'] and not capture['rejected'] and not any(s['lost_count'] for s in sources)
                                and all(source['state'] == 'observed_recently' for source in coverage)}
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('events.jsonl', data)
            archive.writestr('manifest.json', encoded(manifest).encode())
        return output.getvalue()
