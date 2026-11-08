"""Behavior tests for D01-D04. No model, repository write or physical device is simulated as verified."""
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from v14.config import Config
from v14.debug import (DebugLogStore, DebugMiddleware, install_routes, diagnostic_trace_headers,
                       capture_context_snapshot, execution_diagnostic_context, diagnostic_execution_event,
                       current_diagnostic_capture, current_diagnostic_trace)
from v14.diagnostic_events import (DiagnosticSessionJournal, DiagnosticProtocolError, event_identity,
                                  MAX_EVENTS, MAX_EVENT_BYTES, MAX_CAPTURE_SECONDS, sanitize_event, trace_context)
from v14.identity import Admission

NOW = 1800000000
SESSION, ORIGIN, BOOT = 'a' * 32, 'b' * 32, 'c' * 32


def event(sequence, origin=ORIGIN, boot=BOOT, **fields):
    return dict(schema_version=2, event_id=event_identity(origin, boot, sequence), origin_device_pseudonym=origin,
                origin_boot_id=boot, origin_sequence=sequence, occurred_at_utc=NOW * 1000 + sequence,
                stage='transport', severity='info', **fields)


def batch(events=(), gaps=(), session=SESSION):
    return {'schema_version': 2, 'session_id': session, 'capture_started_at_utc': NOW * 1000, 'events': list(events), 'gaps': list(gaps)}


def unpack(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        raw = archive.read('events.jsonl')
        manifest = json.loads(archive.read('manifest.json'))
        rows = [json.loads(line) for line in raw.splitlines()]
    assert manifest['files'][0]['sha256'] == hashlib.sha256(raw).hexdigest()
    assert manifest['files'][0]['bytes'] == len(raw)
    assert manifest['files'][0]['records'] == len(rows)
    assert len(rows) <= MAX_EVENTS
    assert all(len(line) + 1 <= MAX_EVENT_BYTES for line in raw.splitlines())
    return manifest, rows, raw


class DiagnosticJournalTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'events.sqlite3'
        self.store = DiagnosticSessionJournal(self.path, now=lambda: NOW)
        self.addCleanup(lambda: self.store.close())

    def append(self, first, last):
        for start in range(first, last + 1, 64):
            self.store.ingest('paired-owner', batch(event(n) for n in range(start, min(last + 1, start + 64))))

    def test_metadata_acknowledgment_states_survive_durable_export_without_boolean_coercion(self):
        states = ('ACCEPTED', 'NOT_REQUESTED', 'REJECTED_BEFORE_PROCESSING', 'NOT_CONFIRMED')
        values = [event(index, bounded_attributes={'client_metadata_acknowledgment': state,
                                                  'gateway_admission_code': 'remote_surface_not_admitted'})
                  for index, state in enumerate(states, 1)]
        self.store.ingest('paired-owner', batch(values))
        self.store.close()
        self.store = DiagnosticSessionJournal(self.path, now=lambda: NOW)
        _, rows, raw = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual(list(states), [row['bounded_attributes']['client_metadata_acknowledgment'] for row in rows])
        self.assertTrue(all(row['bounded_attributes']['gateway_admission_code'] == 'remote_surface_not_admitted' for row in rows))
        self.assertNotIn(b'client_metadata_accepted', raw)

    def test_metadata_projection_discards_unknown_acknowledgment_and_admission_text(self):
        result = sanitize_event(event(1, bounded_attributes={'client_metadata_acknowledgment': 'private credential',
                                                           'gateway_admission_code': 'https://private.example/token'}))
        self.assertEqual({}, result.get('bounded_attributes', {}))
        self.assertNotIn('private', json.dumps(result))

    def test_5001_unique_combined_events_evict_exactly_one_with_truthful_integrity(self):
        self.append(1, 5000)
        self.store.ingest('paired-owner', batch([event(1, origin='d' * 32, boot='e' * 32)]), local=True)
        manifest, rows, _ = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual(5000, len(rows))
        self.assertEqual(2, rows[0]['gateway_ingest_sequence'])
        self.assertEqual(5001, rows[-1]['gateway_ingest_sequence'])
        self.assertEqual(1, manifest['evicted_count'])
        self.assertEqual({'android', 'gateway'}, {row['owner'] for row in rows})
        self.assertFalse(manifest['complete'])
        self.assertFalse(manifest['vendor_log_available'])

    def test_acknowledged_wal_commit_survives_process_exit_and_uncommitted_watermark_does_not(self):
        script = (
            'import json,os,sys\n'
            f'sys.path.insert(0,{str(Path(__file__).resolve().parents[1])!r})\n'
            'from v14.diagnostic_events import DiagnosticSessionJournal\n'
            f'journal=DiagnosticSessionJournal(sys.argv[1],now=lambda:{NOW})\n'
            f'receipt=journal.ingest("paired-owner",json.loads({json.dumps(batch([event(1)]))!r}))\n'
            f'assert receipt["committed_event_ids"]==[{event(1)["event_id"]!r}]\n'
            'assert journal.db.execute("PRAGMA journal_mode").fetchone()[0]=="wal"\n'
            'assert journal.db.execute("PRAGMA synchronous").fetchone()[0]==2\n'
            'journal.db.execute("BEGIN IMMEDIATE")\n'
            'journal.db.execute("UPDATE captures SET last_ingest=999")\n'
            # No connection close/checkpoint or transaction rollback can run.
            'os._exit(0)\n'
        )
        result = subprocess.run([sys.executable, '-c', script, str(self.path)],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(Path(str(self.path) + '-wal').is_file())
        receipt = self.store.ingest('paired-owner', batch([event(1)]))
        self.assertEqual([{'event_id': event(1)['event_id'], 'content_verified': True}], receipt['duplicates'])
        self.assertEqual(1, receipt['last_ingest_sequence'])
        manifest, rows, _ = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual([1], [row['origin_sequence'] for row in rows])
        self.assertEqual(1, manifest['sources'][0]['accounted_through'])

    def test_second_journal_owner_cannot_overlap_and_recovers_after_the_first_closes(self):
        self.store.ingest('paired-owner', batch([event(1)]))
        replacement = DiagnosticSessionJournal(self.path, now=lambda: NOW)
        self.addCleanup(replacement.close)
        with self.assertRaises(DiagnosticProtocolError) as blocked:
            replacement.ingest('paired-owner', batch([event(2)]))
        self.assertEqual('DEBUG_STORAGE_UNAVAILABLE', blocked.exception.code)
        self.assertIsNone(replacement.db)
        self.store.close()
        receipt = replacement.ingest('paired-owner', batch([event(2)]))
        self.assertEqual(2, receipt['last_ingest_sequence'])
        self.assertEqual(2, receipt['watermarks'][0]['accounted_through'])
        self.assertIsNone(replacement.storage_error)

    def test_out_of_order_lost_range_and_duplicate_receipts_have_separate_received_and_accounted_counts(self):
        first = self.store.ingest('paired-owner', batch([event(2), event(4)]))
        self.assertEqual(0, first['watermarks'][0]['accounted_through'])
        gap = {'origin_device_pseudonym': ORIGIN, 'origin_boot_id': BOOT, 'first': 1, 'last': 1, 'reason': 'OFFLINE_EVICTED'}
        receipt = self.store.ingest('paired-owner', batch([event(3), event(2)], [gap]))
        source = receipt['watermarks'][0]
        self.assertEqual(4, source['accounted_through'])
        self.assertEqual(4, source['highest_committed_received'])
        self.assertEqual(3, source['received_count'])
        self.assertEqual(1, source['lost_count'])
        self.assertEqual(1, len(receipt['duplicates']))
        self.assertEqual(3, receipt['retained_count'])
        duplicate_gap = self.store.ingest('paired-owner', batch(gaps=[gap]))
        self.assertEqual(1, duplicate_gap['watermarks'][0]['lost_count'])

    def test_batched_insert_preserves_in_batch_duplicate_conflict_and_restart(self):
        identical = event(1)
        changed = event(1, bounded_attributes={'status': 503})
        following = event(2)
        receipt = self.store.ingest('paired-owner', batch([identical, identical, changed, following]))
        self.assertEqual([identical['event_id'], following['event_id']], receipt['committed_event_ids'])
        self.assertEqual([{'event_id': identical['event_id'], 'content_verified': True}], receipt['duplicates'])
        self.assertEqual([{'event_id': changed['event_id'], 'code': 'EVENT_ID_CONFLICT'}], receipt['rejected'])
        self.assertEqual(2, receipt['last_ingest_sequence'])
        self.assertEqual(2, receipt['watermarks'][0]['accounted_through'])
        self.store.close()
        replay = self.store.ingest('paired-owner', batch([identical]))
        self.assertEqual([{'event_id': identical['event_id'], 'content_verified': True}], replay['duplicates'])
        self.assertEqual(2, replay['last_ingest_sequence'])

    def test_batched_insert_rolls_back_events_digests_and_watermark_on_invalid_gap(self):
        invalid = {'origin_device_pseudonym': ORIGIN, 'origin_boot_id': BOOT,
                   'first': 2, 'last': 2, 'reason': 'INVALID'}
        with self.assertRaises(DiagnosticProtocolError) as rejected:
            self.store.ingest('paired-owner', batch([event(1)], [invalid]))
        self.assertEqual('INVALID_GAP', rejected.exception.code)
        receipt = self.store.ingest('paired-owner', batch([event(1)]))
        self.assertEqual([event(1)['event_id']], receipt['committed_event_ids'])
        self.assertEqual([], receipt['duplicates'])
        self.assertEqual(1, receipt['last_ingest_sequence'])

    def test_conflicting_identity_is_rejected_while_verification_digest_survives_row_eviction(self):
        self.append(1, 5001)
        changed = event(1, bounded_attributes={'status': 500})
        receipt = self.store.ingest('paired-owner', batch([changed]))
        self.assertEqual('EVENT_ID_CONFLICT', receipt['rejected'][0]['code'])
        self.assertEqual(5001, receipt['last_ingest_sequence'])
        self.assertEqual(5000, receipt['retained_count'])

    def test_rejected_oversized_event_is_a_durable_tombstone_and_cannot_stall_ack_forever(self):
        oversized = event(1, exception='Bearer private-secret\r\nforged=' + 'x' * 3000)
        receipt = self.store.ingest('paired-owner', batch([oversized, event(2)]))
        self.assertEqual('EVENT_TOO_LARGE', receipt['rejected'][0]['code'])
        self.assertEqual(2, receipt['watermarks'][0]['accounted_through'])
        self.assertEqual(1, receipt['watermarks'][0]['lost_count'])
        replay = self.store.ingest('paired-owner', batch([event(1)]))
        self.assertEqual(False, replay['already_accounted'][0]['content_verified'])
        manifest, rows, raw = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual(1, manifest['dropped_count'])
        self.assertEqual(1, len(rows))
        self.assertNotIn(b'private-secret', raw)

    def test_secret_fields_and_crlf_are_discarded_before_storage_and_export(self):
        malicious = event(1, exception='Bearer secret-token\r\nforged event', url='https://private/path?token=secret',
                          bounded_attributes={'status': 502, 'password': 'private', 'nested': {'token': 'private'}, 'vendor_log_available': True},
                          owner='gateway', error_code='UNKNOWN_CAUSE', operation_id='op_' + 'f' * 32)
        self.store.ingest('paired-owner', batch([malicious]))
        self.store.close()
        self.assertNotIn(b'secret-token', self.path.read_bytes())
        manifest, rows, raw = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual(1, len(raw.splitlines()))
        self.assertNotIn(b'private', raw)
        self.assertEqual('android', rows[0]['owner'])
        self.assertFalse(rows[0]['bounded_attributes']['vendor_log_available'])
        self.assertIsNone(manifest['confirmed_defect_count'])

    def test_owner_scoping_missing_sources_and_offline_export_are_explicit(self):
        self.store.ingest('paired-owner', batch([event(1)]))
        with self.assertRaises(DiagnosticProtocolError) as failed:
            self.store.read('other-owner', SESSION)
        self.assertEqual('CAPTURE_NOT_FOUND', failed.exception.code)
        self.store.now = lambda: NOW + 60
        manifest, _, _ = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual(['gateway'], manifest['missing_sources'])
        self.assertEqual('disconnected_or_idle', manifest['sources'][0]['state'])
        self.assertFalse(manifest['complete'])
        self.assertIsNone(manifest['clock_uncertainty_ms'])

    def test_read_pages_never_echo_origin_and_return_eviction_gaps(self):
        self.append(1, 5001)
        page = self.store.read('paired-owner', SESSION, exclude_origin=ORIGIN)
        self.assertEqual([], page['events'])
        self.assertEqual(5001, page['next_cursor'])
        self.assertFalse(page['has_more'])
        self.assertEqual({'first': 1, 'last': 1, 'reason': 'EVICTED'}, page['cursor_gap'])
        self.store.ingest('paired-owner', batch([event(1, origin='d' * 32, boot='e' * 32)]), local=True)
        page = self.store.read('paired-owner', SESSION, after=5001, exclude_origin=ORIGIN)
        self.assertEqual('gateway', page['events'][0]['owner'])
        self.assertEqual(5002, page['next_cursor'])

    def test_storage_error_returns_no_committed_receipt_and_transaction_rolls_back(self):
        self.store.ingest('paired-owner', batch([event(1)]))
        connection = self.store.db
        connection.set_authorizer(lambda action, table, *_: sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_INSERT and table == 'events' else sqlite3.SQLITE_OK)
        with self.assertRaises(DiagnosticProtocolError) as failed:
            self.store.ingest('paired-owner', batch([event(2)]))
        self.assertEqual('DEBUG_STORAGE_UNAVAILABLE', failed.exception.code)
        connection.set_authorizer(None)
        self.assertEqual(1, self.store.read('paired-owner', SESSION)['last_ingest_sequence'])
        receipt = self.store.ingest('paired-owner', batch([event(2)]))
        self.assertEqual([event(2)['event_id']], receipt['committed_event_ids'])
        with patch.object(self.store, '_connection', side_effect=OSError('disk full: /private/path Bearer secret')):
            with self.assertRaises(DiagnosticProtocolError) as failure:
                self.store.ingest('paired-owner', batch([event(3)]))
            self.assertEqual('DEBUG_STORAGE_UNAVAILABLE', str(failure.exception))

    def test_concurrent_export_has_one_commit_consistent_counts_hashes_and_no_duplicate_rows(self):
        self.append(1, 64)
        start = threading.Event()
        def produce():
            start.wait()
            self.append(65, 512)
        with ThreadPoolExecutor(max_workers=2) as pool:
            writer = pool.submit(produce)
            start.set()
            for _ in range(8):
                manifest, rows, _ = unpack(self.store.export('paired-owner', SESSION))
                self.assertEqual(len(rows), len({row['event_id'] for row in rows}))
                self.assertEqual(manifest['last_ingest_sequence'], rows[-1]['gateway_ingest_sequence'])
            writer.result(timeout=20)

    def test_capture_source_and_sequence_metadata_are_bounded(self):
        self.store.ingest('paired-owner', batch([event(1)]))
        with self.assertRaises(DiagnosticProtocolError) as too_far:
            self.store.ingest('paired-owner', batch([event(100002)]))
        self.assertEqual('SOURCE_SEQUENCE_WINDOW', too_far.exception.code)
        for n in range(1, 16):
            self.store.ingest('paired-owner', batch([event(1, boot=f'{n:032x}')]))
        with self.assertRaises(DiagnosticProtocolError) as limit:
            self.store.ingest('paired-owner', batch([event(1, boot='f' * 32)]))
        self.assertEqual('SOURCE_LIMIT', limit.exception.code)
        self.store.now = lambda: NOW + MAX_CAPTURE_SECONDS + 1
        with self.assertRaises(DiagnosticProtocolError) as expired:
            self.store.ingest('paired-owner', batch([event(2)]))
        self.assertEqual('CAPTURE_EXPIRED', expired.exception.code)

    def test_error_taxonomy_preserves_unknown_dispatch_and_groups_causal_occurrences(self):
        rows = [event(n, error_code=code, operation_id='op_' + 'd' * 32, dispatch_state='dispatched') for n, code in enumerate(('PRECHECK_REJECTED', 'DISPATCH_UNCERTAIN', 'UNKNOWN_CAUSE'), 1)]
        for row in rows:
            row['severity'] = 'error'
        self.store.ingest('paired-owner', batch(rows))
        manifest, exported, _ = unpack(self.store.export('paired-owner', SESSION))
        self.assertEqual('not_dispatched', exported[0]['dispatch_state'])
        self.assertEqual('unknown', exported[1]['dispatch_state'])
        self.assertEqual('reconcile', exported[1]['retry_class'])
        self.assertEqual(1, manifest['incident_count'])
        self.assertEqual(1, manifest['operation_count'])
        self.assertEqual(3, manifest['incidents'][0]['occurrences'])
        self.assertEqual(3, len(manifest['incidents'][0]['event_ids']))
        self.assertIsNone(manifest['confirmed_defect_count'])


class DiagnosticProtocolHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = DebugLogStore(Path(self.temp.name) / 'debug.jsonl', True, lambda: NOW)
        self.addCleanup(self.store.stop)
        self.app = FastAPI()
        install_routes(self.app, self.store)

        @self.app.post('/chat/completions')
        async def backend(request: Request):
            return {'backend_headers': diagnostic_trace_headers()}

        self.app.add_middleware(Admission, config=Config())
        self.app.add_middleware(DebugMiddleware, store=self.store, instance_id='d' * 32)
        self.client = TestClient(self.app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 4444))
        self.addCleanup(self.client.close)

    def test_v2_ingestion_fetch_export_and_trace_response_are_real_routes(self):
        response = self.client.post('/gateway/debug/events', json=batch([event(1)]))
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual([event(1)['event_id']], response.json()['committed_event_ids'])
        trace = '00-' + '1' * 32 + '-' + '2' * 16 + '-01'
        response = self.client.post('/chat/completions', json={}, headers={
            'traceparent': trace, 'X-Gateway-Diagnostic-Session': SESSION, 'X-Gateway-Diagnostic-Started': str(NOW * 1000)})
        self.assertEqual('2', response.headers['X-Gateway-Debug-Protocol'])
        self.assertEqual('2' * 16, response.headers['X-Gateway-Client-Span-ID'])
        self.assertEqual(response.headers['traceparent'], response.json()['backend_headers']['traceparent'])
        self.store.flush()
        result = self.client.get('/gateway/debug/events', params={'session_id': SESSION, 'exclude_origin': ORIGIN}).json()
        self.assertEqual(2, len(result['events']))
        self.assertTrue(all(row['trace_id'] == '1' * 32 for row in result['events']))
        export = self.client.get('/gateway/debug/export', params={'session_id': SESSION})
        self.assertEqual('no-store', export.headers['cache-control'])
        manifest, rows, _ = unpack(export.content)
        self.assertEqual(3, len(rows))
        self.assertEqual([], manifest['missing_sources'])

    def test_disabled_capture_no_upload_and_invalid_body_do_not_grant_admin(self):
        self.store.set_enabled(False)
        self.assertEqual(409, self.client.post('/gateway/debug/events', json=batch([event(1)])).status_code)
        self.assertEqual(413, self.client.post('/gateway/debug/events', content=b'x' * 65537).status_code)
        self.assertEqual(400, self.client.post('/gateway/debug/events', content=b'{').status_code)
        unadmitted = FastAPI()
        install_routes(unadmitted, self.store)
        with TestClient(unadmitted) as unauthenticated:
            self.assertEqual(401, unauthenticated.post('/gateway/debug/events', json=batch([event(1)])).status_code)
            self.assertEqual(401, unauthenticated.get('/gateway/debug/events', params={'session_id': SESSION}).status_code)

    def test_w3c_context_rejects_zero_and_malicious_context_without_retaining_baggage(self):
        self.assertIsNone(trace_context('00-' + '0' * 32 + '-' + '1' * 16 + '-01'))
        self.assertIsNone(trace_context('00-' + '1' * 32 + '-' + '0' * 16 + '-01'))
        self.assertIsNone(trace_context('Bearer private-secret\r\nforged'))
        context = trace_context('00-' + '1' * 32 + '-' + '2' * 16 + '-01')
        self.assertEqual('1' * 32, context['trace_id'])
        self.assertEqual('2' * 16, context['parent_span_id'])


class DiagnosticExecutionContextTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.now = NOW
        self.store = DebugLogStore(Path(self.temp.name) / 'debug.jsonl', True, lambda: self.now)
        self.addCleanup(self.store.stop)
        self.runtime = SimpleNamespace(gateway_debug=self.store)

    def snapshot(self, owner='paired-owner'):
        capture = current_diagnostic_capture.set({'session': SESSION, 'started': NOW * 1000,
            'request_state': {'principal': SimpleNamespace(id=owner)}, 'untrusted': 'private-secret'})
        trace = current_diagnostic_trace.set({'trace_id': '1' * 32, 'span_id': '2' * 16})
        try:
            return capture_context_snapshot('paired-owner', self.runtime)
        finally:
            current_diagnostic_trace.reset(trace)
            current_diagnostic_capture.reset(capture)

    def test_durable_snapshot_keeps_only_admitted_identity_and_worker_context_is_restored(self):
        snapshot = self.snapshot()
        self.assertEqual({'schema_version', 'session_id', 'capture_started_at_utc', 'principal_digest', 'trace_id', 'parent_span_id'}, set(snapshot))
        self.assertNotIn('private-secret', json.dumps(snapshot))
        self.assertIsNone(self.snapshot('other-owner'))

        def execute():
            self.assertIsNone(current_diagnostic_capture.get())
            with execution_diagnostic_context(snapshot, self.runtime, 'paired-owner', lambda: True):
                self.assertTrue(diagnostic_execution_event(self.runtime, 'paired-owner', 'MODEL_EXECUTION', state='DISPATCHED', dispatched=True))
            self.assertIsNone(current_diagnostic_capture.get())
            self.assertIsNone(current_diagnostic_trace.get())
            self.assertFalse(diagnostic_execution_event(self.runtime, 'paired-owner', 'MODEL_EXECUTION', state='DISPATCHED', dispatched=True))

        with ThreadPoolExecutor(max_workers=1) as pool:
            pool.submit(execute).result(timeout=5)
        self.store.flush()
        _, rows, _ = unpack(self.store.export_session('paired-owner', SESSION))
        self.assertEqual(1, len(rows))
        self.assertEqual('1' * 32, rows[0]['trace_id'])
        self.assertEqual('2' * 16, rows[0]['parent_span_id'])
        self.assertNotEqual('2' * 16, rows[0]['span_id'])
        self.assertEqual('operation_dispatch', rows[0]['stage'])
        self.assertEqual('model', rows[0]['category'])

    def test_capture_rechecks_revocation_disable_and_expiry_before_each_event(self):
        snapshot = self.snapshot()
        allowed = [True]
        with execution_diagnostic_context(snapshot, self.runtime, 'paired-owner', lambda: allowed[0]):
            allowed[0] = False
            self.assertFalse(diagnostic_execution_event(self.runtime, 'paired-owner', 'MODEL_EXECUTION'))
            allowed[0] = True
            self.store.set_enabled(False)
            self.assertFalse(diagnostic_execution_event(self.runtime, 'paired-owner', 'MODEL_EXECUTION'))
            self.store.set_enabled(True)
            self.now += MAX_CAPTURE_SECONDS + 1
            self.assertFalse(diagnostic_execution_event(self.runtime, 'paired-owner', 'MODEL_EXECUTION'))
        for snapshot_value, owner in ((snapshot, 'paired-owner'), (snapshot, 'other-owner'), (None, 'paired-owner')):
            with execution_diagnostic_context(snapshot_value, self.runtime, owner, lambda: True):
                self.assertIsNone(current_diagnostic_capture.get())
                self.assertEqual({}, diagnostic_trace_headers())
        self.assertEqual(0, self.store.event_queue.qsize())
        self.assertEqual({}, self.store.origin_sequences)

    def test_execution_error_taxonomy_keeps_failed_prechecks_and_uncertain_dispatch(self):
        with execution_diagnostic_context(self.snapshot(), self.runtime, 'paired-owner', lambda: True):
            diagnostic_execution_event(self.runtime, 'paired-owner', 'MODEL_EXECUTION', state='PRECHECK_REJECTED',
                dispatched=False, error_code='EXECUTION_PARAMETERS_CHANGED', bounded_attributes={'model_digest': 'a' * 64})
            diagnostic_execution_event(self.runtime, 'paired-owner', 'GITHUB_OPERATION', state='UNKNOWN_OUTCOME',
                dispatched=True, error_code='TimeoutError', operation_id='operation_' + 'b' * 40)
        self.store.flush()
        _, rows, _ = unpack(self.store.export_session('paired-owner', SESSION))
        self.assertEqual(['error', 'error'], [row['severity'] for row in rows])
        self.assertEqual(['PRECHECK_REJECTED', 'DISPATCH_UNCERTAIN'], [row['error_code'] for row in rows])
        self.assertEqual(['not_dispatched', 'unknown'], [row['dispatch_state'] for row in rows])
        self.assertEqual('reconcile', rows[1]['retry_class'])
        self.assertEqual(['model', 'tool'], [row['category'] for row in rows])
        self.assertEqual('a' * 64, rows[0]['bounded_attributes']['model_digest'])

    def test_storage_readiness_requires_an_observed_storage_operation(self):
        snapshot = self.store.snapshot()
        self.assertFalse(snapshot['storageChecked'])
        self.assertIsNone(snapshot['storageReady'])
        self.store.ingest('paired-owner', batch([event(1)])).result(timeout=5)
        self.assertTrue(self.store.snapshot()['storageChecked'])
        self.assertTrue(self.store.snapshot()['storageReady'])
        self.store.journal.db.set_authorizer(lambda action, table, *_:
            sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_INSERT and table == 'events' else sqlite3.SQLITE_OK)
        with self.assertRaises(DiagnosticProtocolError):
            self.store.ingest('paired-owner', batch([event(2)])).result(timeout=5)
        self.assertFalse(self.store.snapshot()['storageReady'])
        self.store.journal.db.set_authorizer(None)
        self.store.ingest('paired-owner', batch([event(2)])).result(timeout=5)
        self.assertTrue(self.store.snapshot()['storageReady'])

    def test_overlapping_full_exports_are_rejected_before_allocating_a_second_snapshot(self):
        self.store.ingest('paired-owner', batch([event(1)])).result(timeout=5)
        entered, release = threading.Event(), threading.Event()
        original = self.store.journal.export

        def blocked(*args):
            entered.set()
            self.assertTrue(release.wait(5))
            return original(*args)

        with patch.object(self.store.journal, 'export', side_effect=blocked), ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.store.export_session, 'paired-owner', SESSION)
            self.assertTrue(entered.wait(5))
            try:
                with self.assertRaises(DiagnosticProtocolError) as busy:
                    self.store.export_session('paired-owner', SESSION)
                self.assertEqual('DIAGNOSTIC_EXPORT_BUSY', busy.exception.code)
                self.assertEqual(429, busy.exception.status)
            finally:
                release.set()
            _, rows, _ = unpack(first.result(timeout=5))
            self.assertEqual(1, len(rows))
        self.assertEqual(1, len(unpack(self.store.export_session('paired-owner', SESSION))[1]))


if __name__ == '__main__':
    unittest.main()
