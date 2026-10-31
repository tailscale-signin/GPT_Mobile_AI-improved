import contextvars
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.batch import BatchExecutor, BatchJournal, retry_delay, validate_plan


def node(i, dependencies=()):
    return {'id': str(i), 'tool': 'github_get_file_contents', 'arguments': {'path': str(i)}, 'depends_on': list(dependencies)}


class IntelligentBatchTests(unittest.TestCase):
    def test_reads_overlap_and_dependencies_wait_with_stable_receipts(self):
        barrier = threading.Barrier(3)
        finished, lock = set(), threading.Lock()
        def invoke(call):
            if call['id'] in {'a', 'b', 'c'}:
                barrier.wait(2)
            else:
                self.assertTrue({'a', 'b'} <= finished)
            with lock:
                finished.add(call['id'])
            return {'value': call['id']}
        progress = []
        result = BatchExecutor().execute([node('a'), node('b'), node('c'), node('d', ['a', 'b'])], invoke, progress=progress.append)
        self.assertEqual({'a', 'b', 'c', 'd'}, set(result))
        self.assertEqual(4, progress[-1]['progress'])
        self.assertEqual(0, progress[-1]['active'])

    def test_failed_dependency_is_not_dispatched_and_other_reads_finish(self):
        calls = []
        result = BatchExecutor().execute([node('a'), node('b', ['a']), node('c')],
            lambda call: calls.append(call['id']) or ({'isError': True} if call['id'] == 'a' else {'ok': True}))
        self.assertEqual({'a', 'c'}, set(calls))
        self.assertEqual('DEPENDENCY_FAILED', result['b']['error']['code'])
        self.assertFalse(result['b']['dispatched'])

    def test_duplicate_reads_coalesce_and_completed_receipts_resume(self):
        calls = []
        a, b = node('a'), node('b')
        b['arguments'] = a['arguments']
        first = BatchExecutor().execute([a, b], lambda call: calls.append(call) or {'ok': True})
        self.assertEqual(1, len(calls))
        resumed = BatchExecutor().execute([a, b], lambda call: self.fail('completed reads must not repeat'), receipts=first)
        self.assertEqual(first, resumed)

    def test_context_identity_reaches_every_worker(self):
        principal = contextvars.ContextVar('batch_test_principal')
        marker = principal.set('owner')
        try:
            result = BatchExecutor().execute([node('a'), node('b')], lambda call: {'owner': principal.get()})
            self.assertTrue(all(value['owner'] == 'owner' for value in result.values()))
        finally:
            principal.reset(marker)

    def test_transient_read_retries_are_bounded_and_permission_errors_do_not_retry(self):
        attempts = []
        def read(call):
            attempts.append(1)
            if len(attempts) < 3:
                raise TimeoutError()
            return {'ok': True}
        with patch('v14.batch.retry_delay', return_value=0):
            self.assertEqual({'ok': True}, BatchExecutor().execute([node('a')], read)['a'])
        self.assertEqual(3, len(attempts))
        self.assertIsNone(retry_delay(PermissionError(), 0))
        error = Exception()
        error.response = SimpleNamespace(status_code=403, headers={})
        self.assertIsNone(retry_delay(error, 0))

    def test_failure_reduces_adaptive_window_and_cancellation_stops_pending(self):
        cancellation, progress = threading.Event(), []
        def invoke(call):
            cancellation.set()
            return {'isError': True}
        with self.assertRaises(InterruptedError):
            BatchExecutor(1).execute([node(i) for i in range(10)], invoke, progress=progress.append, cancel_event=cancellation)
        self.assertLessEqual(progress[-1]['progress'], 1)

    def test_entire_graph_and_authorized_schema_are_validated_before_dispatch(self):
        schema = {'type': 'object', 'required': ['path'], 'properties': {'path': {'type': 'string'}}, 'additionalProperties': False}
        definitions = {'read': {'function': {'parameters': schema}}, 'write': {'function': {'parameters': schema}}}
        metadata = {'read': {'tool': 'get_file_contents'}, 'write': {'tool': 'push_files'}}
        valid = {'id': 'a', 'tool': 'read', 'arguments': {'path': 'a'}}
        self.assertEqual('a', validate_plan({'calls': [valid]}, definitions, metadata)[0]['id'])
        for calls in ([valid, valid], [dict(valid, tool='write')], [dict(valid, tool='client_read')],
                      [dict(valid, depends_on=['missing'])], [dict(valid, depends_on=['a'])],
                      [dict(valid, depends_on=['b']), dict(valid, id='b', depends_on=['a'])],
                      [dict(valid, arguments={'path': 3})]):
            with self.assertRaises(ValueError):
                validate_plan({'calls': calls}, definitions, metadata)

    def test_journal_reopens_after_restart_and_fences_owner_and_connection(self):
        with tempfile.TemporaryDirectory() as temp:
            runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                      _durable_connect=lambda: sqlite3.connect(Path(temp) / 'jobs.db'))
            journal = BatchJournal(runtime)
            self.assertEqual({}, journal.open('owner', 'job', 'batch', [node('a')], 'binding'))
            journal.save('owner', 'job', 'batch', {'a': {'value': 1}})
            resumed = BatchJournal(runtime).open('owner', 'job', 'batch', [node('a')], 'binding')
            self.assertEqual({'a': {'value': 1}}, resumed)
            self.assertEqual({}, journal.open('other', 'job', 'batch', [node('a')], 'binding'))
            with self.assertRaises(ValueError):
                journal.open('owner', 'job', 'batch', [node('a')], 'changed')
