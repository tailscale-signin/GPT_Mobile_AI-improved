"""Load, lifecycle, disk and actual child-process failures for v14.1."""
import asyncio
import importlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.config import Config
from v14.journal import backup_database, SCHEMA_VERSION
from v14.mcp import Circuit, CircuitOpen
from v14.service import LoadGuard, ServiceState
from v14.jobs import DurableCommitError
import test_gateway_v14_jobs as journal_tests


class JournalStabilityTests(unittest.TestCase):
    setUpClass = classmethod(journal_tests.JournalTests.setUpClass.__func__)
    setUp = journal_tests.JournalTests.setUp
    client = journal_tests.JournalTests.client
    legacy_database = journal_tests.JournalTests.legacy_database

    def test_upgrade_backup_precedes_schema_changes_and_is_not_repeated(self):
        self.legacy_database()
        self.runtime.init_durable_job_store()
        backups = list(self.db.parent.glob('backups/*.sqlite3'))
        self.assertEqual(len(backups), 1)
        with closing(sqlite3.connect(backups[0])) as db, db:
            self.assertNotIn('created_at', {row[1] for row in db.execute('PRAGMA table_info(gateway_jobs)')})
            self.assertEqual(json.loads(db.execute('SELECT result_json FROM gateway_jobs').fetchone()[0]), self.old_result)
        self.runtime.durable_job_initialized = False
        self.runtime.init_durable_job_store()
        self.assertEqual(list(self.db.parent.glob('backups/*.sqlite3')), backups)
        db = self.runtime._durable_connect()
        try:
            self.assertEqual(db.execute('PRAGMA synchronous').fetchone()[0], 2)
            self.assertEqual(db.execute('SELECT version FROM gateway_schema').fetchone()[0], SCHEMA_VERSION)
        finally:
            db.close()

    def test_future_version_is_rejected_without_retention_or_data_changes(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('CREATE TABLE gateway_schema(id INTEGER PRIMARY KEY,version INTEGER)')
            db.execute('INSERT INTO gateway_schema VALUES(1,999)')
        with self.assertRaises(DurableCommitError):
            self.runtime.init_durable_job_store()
        self.assertFalse(self.runtime.durable_job_initialized)
        self.assertFalse(list(self.db.parent.glob('backups/*.sqlite3')))
        with closing(sqlite3.connect(self.db)) as db, db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM gateway_jobs').fetchone()[0], 1)

    def test_failed_snapshot_prevents_schema_mutation(self):
        self.legacy_database()
        with patch('v14.journal.backup_database', side_effect=OSError('disk full')):
            with self.assertRaises(DurableCommitError):
                self.runtime.init_durable_job_store()
        with closing(sqlite3.connect(self.db)) as db, db:
            self.assertNotIn('created_at', {row[1] for row in db.execute('PRAGMA table_info(gateway_jobs)')})
            self.assertEqual(db.execute('SELECT COUNT(*) FROM gateway_jobs').fetchone()[0], 1)

    def test_process_crash_preserves_completion_and_marks_active_job_failed(self):
        script = '''
import os, sys, threading
from contextlib import closing
from pathlib import Path
from gateway_v14 import runtime as r
r.GATEWAY_JOB_DB_PATH=Path(sys.argv[1])
r.GATEWAY_DURABLE_JOBS=True
r.init_durable_job_store()
for key in ('completed', 'active'):
    r.register_gateway_job(key, 'local', 'chat', threading.Event(), threading.Event())
r.finish_gateway_job('completed', 200, {'content': 'committed before crash'})
os._exit(0)
'''
        subprocess.run([sys.executable, '-c', script, str(self.db)],
                       cwd=Path(__file__).resolve().parents[1], check=True, capture_output=True, timeout=15)
        self.runtime.init_durable_job_store()
        self.assertEqual(self.runtime.job_registry['completed']['_result']['content'], 'committed before crash')
        self.assertEqual(self.runtime.job_registry['active']['status'], 'failed')
        self.assertIn('automatic replay', self.runtime.job_registry['active']['message'])

    def test_busy_database_rejects_registration_without_in_memory_ghost(self):
        self.runtime.init_durable_job_store()
        blocker = sqlite3.connect(self.db)
        try:
            blocker.execute('BEGIN IMMEDIATE')
            with patch.object(self.runtime, 'DURABLE_JOB_SQLITE_BUSY_TIMEOUT_MS', 20):
                with self.assertRaises(DurableCommitError):
                    self.runtime.register_gateway_job('busy', 'local', 'chat', threading.Event(), threading.Event())
            self.assertNotIn('busy', self.runtime.job_registry)
        finally:
            blocker.rollback()
            blocker.close()

    def test_backup_includes_uncheckpointed_wal_and_refuses_overwrite(self):
        db = sqlite3.connect(self.db)
        self.addCleanup(db.close)
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('PRAGMA wal_autocheckpoint=0')
        db.execute('CREATE TABLE evidence(value TEXT)')
        db.execute('INSERT INTO evidence VALUES(?)', ('latest committed data',))
        db.commit()
        target = self.db.with_name('backup.sqlite3')
        backup_database(db, target)
        with closing(sqlite3.connect(target)) as saved, saved:
            self.assertEqual(saved.execute('SELECT value FROM evidence').fetchone()[0], 'latest committed data')
        with self.assertRaises(FileExistsError):
            backup_database(db, target)
        with self.assertRaises(FileExistsError):
            backup_database(db, self.db)

    def test_failed_progress_commit_rolls_back_memory_event_and_sequence(self):
        self.runtime.init_durable_job_store()
        self.runtime.register_gateway_job('j', 'local', 'chat', threading.Event(), threading.Event())
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("CREATE TRIGGER reject_events BEFORE INSERT ON gateway_events BEGIN SELECT RAISE(ABORT,'disk failure'); END")
        with self.assertRaises(DurableCommitError):
            self.runtime.update_gateway_job('j', {'sequence': 5, 'stage': 'unsaved'})
        job = self.runtime.job_registry['j']
        self.assertEqual(job['_last_sequence'], 0)
        self.assertFalse(job['_events'])
        self.assertNotEqual(job['stage'], 'unsaved')

    def test_terminal_job_cannot_be_reopened(self):
        self.runtime.init_durable_job_store()
        self.runtime.register_gateway_job('j', 'local', 'chat', threading.Event(), threading.Event())
        self.runtime.finish_gateway_job('j', 200, {'content': 'saved'})
        with self.assertRaises(ValueError):
            self.runtime.register_gateway_job('j', 'local', 'chat', threading.Event(), threading.Event())
        self.assertEqual(self.runtime.job_registry['j']['status'], 'completed')

    def test_liveness_survives_backend_failure_and_readiness_reports_journal_failure(self):
        with self.client() as client, patch.object(self.runtime, 'http_get', return_value=Mock(status_code=503)):
            self.assertEqual(client.get('/gateway/live').status_code, 200)
            response = client.get('/gateway/ready')
            self.assertEqual(response.status_code, 503)
            self.assertEqual(response.json()['checks'], {'journal': 'ready', 'backend': 'unavailable'})
            self.runtime.durable_write_healthy = False
            with patch.object(self.runtime, 'http_get', return_value=Mock(status_code=200)):
                self.assertEqual(client.get('/gateway/ready').json()['checks']['journal'], 'write_failed')


class LoadTests(unittest.TestCase):
    def test_real_worker_limit_and_release_on_completion(self):
        state = ServiceState(1)
        started, release = threading.Event(), threading.Event()
        def work(*args):
            started.set()
            release.wait(2)
            return 200, {}
        runtime = SimpleNamespace(process_chat_payload=work)
        state.install(runtime)
        worker = threading.Thread(target=runtime.process_chat_payload, args=({},))
        worker.start()
        try:
            self.assertTrue(started.wait(1))
            self.assertEqual(runtime.process_chat_payload({})[0], 503)
            self.assertEqual(state.snapshot()['activeWorkers'], 1)
        finally:
            release.set()
            worker.join(2)
        self.assertEqual(state.snapshot()['activeWorkers'], 0)

    def test_shutdown_signals_active_work_before_closing_sessions(self):
        async def scenario():
            state = ServiceState(1)
            started, cancel = threading.Event(), threading.Event()
            def work(*args):
                started.set()
                self.assertTrue(cancel.wait(2))
                return 499, {}
            runtime = SimpleNamespace(process_chat_payload=work, start_gateway_background_services=Mock(),
                                      close_all_mcp_sessions=Mock(), startup_services_started=True)
            state.install(runtime)
            async with state.lifespan(runtime, 1)(None):
                worker = threading.Thread(target=runtime.process_chat_payload, args=({}, cancel))
                worker.start()
                self.assertTrue(await asyncio.to_thread(started.wait, 1))
            worker.join(1)
            self.assertTrue(state.snapshot()['draining'])
            self.assertEqual(state.snapshot()['activeWorkers'], 0)
            runtime.close_all_mcp_sessions.assert_called_once()
            self.assertEqual(runtime.process_chat_payload({})[0], 503)
        asyncio.run(scenario())

    def test_body_limits_count_received_bytes_even_without_content_length(self):
        async def scenario():
            called = False
            async def app(scope, receive, send):
                nonlocal called
                called = True
            guard = LoadGuard(app, Config(max_body_bytes=1024), ServiceState())
            sent = []
            async def receive(): return {'type': 'http.request', 'body': b'x' * 1025}
            async def send(message): sent.append(message)
            await guard({'type': 'http', 'path': '/v1/chat/completions', 'method': 'POST'}, receive, send)
            self.assertEqual(sent[0]['status'], 413)
            self.assertFalse(called)
            self.assertEqual(guard.active, 0)
        asyncio.run(scenario())

    def test_concurrent_stream_limit_and_disconnect_release(self):
        async def scenario():
            started, release = asyncio.Event(), asyncio.Event()
            async def app(scope, receive, send):
                started.set()
                await release.wait()
                raise ConnectionError('disconnected')
            guard = LoadGuard(app, Config(max_active_chats=1), ServiceState())
            scope = {'type': 'http', 'path': '/v1/chat/completions', 'method': 'POST'}
            async def receive(): return {'type': 'http.request', 'body': b'{}'}
            sent = []
            async def send(message): sent.append(message)
            first = asyncio.create_task(guard(scope, receive, send))
            await started.wait()
            await guard(scope, receive, send)
            self.assertEqual(sent[0]['status'], 503)
            release.set()
            with self.assertRaises(ConnectionError): await first
            self.assertEqual(guard.active, 0)
        asyncio.run(scenario())

    def test_slow_upload_deadline_prevents_dispatch(self):
        async def scenario():
            app = Mock()
            guard = LoadGuard(app, Config(body_timeout=0.02), ServiceState())
            async def receive(): await asyncio.Event().wait()
            sent = []
            async def send(message): sent.append(message)
            await guard({'type': 'http', 'path': '/v1/chat/completions', 'method': 'POST'}, receive, send)
            self.assertEqual(sent[0]['status'], 408)
            app.assert_not_called()
        asyncio.run(scenario())


class MCPStabilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runtime = importlib.import_module('gateway_v14').runtime

    def setUp(self):
        active = patch.object(self.runtime, 'gateway_draining', False)
        active.start()
        self.addCleanup(active.stop)

    def session(self, mode, extra=()):
        cfg = {'command': sys.executable, 'args': [str(Path(__file__).with_name('fixture_mcp_server.py')), mode, *extra]}
        session = self.runtime.PersistentMCPStdioSession('fixture', cfg)
        self.addCleanup(session.close)
        return session

    def test_actual_stdio_handshake_null_id_notification_and_server_ping(self):
        session = self.session('notification')
        result = session.call('tools/call', {'name': 'read', 'arguments': {}}, absolute_timeout=3)
        self.assertEqual(result['content'][0]['text'], 'OK')
        self.assertEqual(session.protocol_version, '2024-11-05')

    def test_mutation_crash_is_never_replayed(self):
        with tempfile.TemporaryDirectory() as tmp:
            counter = str(Path(tmp) / 'calls')
            session = self.session('crash', [counter])
            with self.assertRaises(RuntimeError):
                session.call('tools/call', {'name': 'write'}, absolute_timeout=3)
            self.assertEqual(Path(counter).read_text(encoding="utf-8"), 'executed\n')
            self.assertEqual(session.calls, 1)
            self.assertIsNone(session.process)

    def test_stale_ids_cannot_extend_idle_deadline(self):
        session = self.session('stale')
        session.ensure_started()
        started = time.monotonic()
        with self.assertRaises(TimeoutError):
            session.call('tools/call', {}, inactivity_timeout=0.08, absolute_timeout=2)
        self.assertLess(time.monotonic() - started, 1)

    def test_matching_progress_cannot_extend_absolute_deadline(self):
        session = self.session('progress')
        session.ensure_started()
        started = time.monotonic()
        with self.assertRaises((TimeoutError, RuntimeError)):
            session.call('tools/call', {}, inactivity_timeout=0.1, absolute_timeout=0.25)
        self.assertLess(time.monotonic() - started, 1)

    def test_oversized_stdout_terminates_transport(self):
        session = self.session('oversize')
        with patch.object(self.runtime, 'MCP_MAX_STDOUT_LINE_CHARS', 1024):
            with self.assertRaises(RuntimeError):
                session.call('tools/call', {}, absolute_timeout=3)
        self.assertLessEqual(session.stdout_queue.qsize(), 129)

    def test_unconsumed_stdout_flood_has_bounded_queue_and_stops_child(self):
        session = self.session('flood')
        session.ensure_started()
        proc = session.process
        session._send_json({'jsonrpc': '2.0', 'id': 123, 'method': 'tools/call', 'params': {}})
        proc.wait(timeout=3)
        session.stdout_thread.join(timeout=1)
        self.assertLessEqual(session.stdout_queue.qsize(), 129)
        self.assertLessEqual(session.queued_bytes, 8 * 1024 * 1024)
        self.assertFalse(session.stdout_thread.is_alive())

    def test_cancelled_call_never_starts_process(self):
        session = self.session('normal')
        cancelled = threading.Event(); cancelled.set()
        with self.assertRaises(InterruptedError):
            session.call('tools/call', {}, cancel_event=cancelled)
        self.assertIsNone(session.process)

    def test_shutdown_prevents_new_mcp_processes(self):
        session = self.session('normal')
        with patch.object(self.runtime, 'gateway_draining', True):
            with self.assertRaises(InterruptedError):
                session.call('tools/list')
        self.assertIsNone(session.process)

    def test_active_cancellation_closes_a_silent_process_promptly(self):
        session = self.session('silent')
        session.ensure_started()
        cancelled = threading.Event()
        timer = threading.Timer(0.03, cancelled.set)
        timer.start()
        started = time.monotonic()
        try:
            with self.assertRaises(InterruptedError):
                session.call('tools/call', {}, absolute_timeout=3, cancel_event=cancelled)
        finally:
            timer.join()
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertIsNone(session.process)

    def test_queue_deadline_does_not_dispatch(self):
        session = self.session('normal')
        held, release = threading.Event(), threading.Event()
        def hold():
            with session.call_lock:
                held.set()
                release.wait(2)
        worker = threading.Thread(target=hold)
        worker.start()
        try:
            self.assertTrue(held.wait(1))
            with self.assertRaises(TimeoutError):
                session.call('tools/call', {}, absolute_timeout=0.03)
            self.assertIsNone(session.process)
        finally:
            release.set(); worker.join(2)

    def test_unknown_modern_protocol_fails_explicitly(self):
        session = self.session('modern')
        with self.assertRaisesRegex(ValueError, 'modern adapter'):
            session.call('tools/list', absolute_timeout=3)
        self.assertIsNone(session.process)

    def test_rpc_validation_error_does_not_open_transport_circuit(self):
        session = self.session('rpc_error')
        for _ in range(4):
            with self.assertRaises(ValueError):
                session.call('tools/call', {}, absolute_timeout=3)
        self.assertEqual(session.circuit.snapshot()['state'], 'closed')

    def test_circuit_allows_only_one_probe_and_recovers_after_success(self):
        now = [0]
        circuit = Circuit(clock=lambda: now[0])
        for _ in range(3):
            circuit.enter(); circuit.finish(True)
        with self.assertRaises(CircuitOpen): circuit.enter()
        now[0] = 31
        circuit.enter()
        with self.assertRaises(CircuitOpen): circuit.enter()
        circuit.finish(False)
        circuit.enter()
        self.assertEqual(circuit.snapshot()['state'], 'closed')


if __name__ == '__main__':
    unittest.main()
