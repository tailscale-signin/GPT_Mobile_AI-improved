"""Behavioral faults at production retry, batching, discovery, replay and budget boundaries."""
import copy
import datetime
import json
import threading
import time
import unittest
from email.utils import format_datetime
from types import SimpleNamespace
from unittest.mock import patch

import requests

from v14.batch import BatchExecutor, retry_delay
from v14.catalog import CatalogCache
from v14.events import replay_snapshot
from v14.inference_budget import compact_evidence, fit_engineering_payload


def http_failure(status, cooldown=None):
    response = requests.Response()
    response.status_code = status
    if cooldown is not None:
        response.headers['Retry-After'] = cooldown
    return requests.HTTPError('fixture-only response', response=response)


class SafeReadRetryFaultTest(unittest.TestCase):
    def test_long_server_cooldown_cannot_be_clamped_into_an_early_retry(self):
        self.assertIsNone(retry_delay(http_failure(429, '600'), 0))
        self.assertIsNone(retry_delay(http_failure(503, '121'), 0))
        self.assertEqual(120, retry_delay(http_failure(429, '120'), 0))

    def test_http_date_cooldown_is_a_lower_bound(self):
        now = 1_800_000_000
        date = format_datetime(datetime.datetime.fromtimestamp(now + 80, datetime.timezone.utc), usegmt=True)
        with patch('v14.batch.time.time', return_value=now):
            self.assertEqual(80, retry_delay(http_failure(503, date), 0))

    def test_malformed_and_nonfinite_explicit_cooldowns_are_not_guessed(self):
        for value in ('NaN', 'Infinity', '-Infinity', 'not-a-date'):
            with self.subTest(value=value):
                self.assertIsNone(retry_delay(http_failure(429, value), 0))

    def test_tls_and_wrapped_tls_do_not_become_transport_retries(self):
        tls = requests.exceptions.SSLError('fixture certificate failure')
        wrapped = requests.ConnectionError('fixture connection failure')
        wrapped.__cause__ = tls
        for error in (tls, wrapped):
            self.assertIsNone(retry_delay(error, 0))

    def test_only_safe_transient_http_and_connection_failures_retry(self):
        for status in (400, 401, 403, 404, 409, 422):
            self.assertIsNone(retry_delay(http_failure(status), 0))
        for error in (requests.ConnectionError('offline'), requests.Timeout('timeout'), http_failure(502)):
            self.assertIsNotNone(retry_delay(error, 0))
        for error in (PermissionError(), InterruptedError(), ValueError()):
            self.assertIsNone(retry_delay(error, 0))


class BatchDrainFaultTest(unittest.TestCase):
    def nodes(self):
        return [{'id': name, 'tool': 'github_get_file_contents', 'arguments': {'path': name},
                 'depends_on': ['one', 'two'] if name == 'three' else []}
                for name in ('one', 'two', 'three')]

    def test_cancellation_checkpoints_observed_inflight_reads_before_releasing_admission(self):
        started = threading.Barrier(3)
        finish = threading.Event()
        cancel = threading.Event()
        calls, saved, errors = [], [], []
        executor = BatchExecutor(maximum=2)
        def invoke(node):
            calls.append(node['id'])
            started.wait(timeout=3)
            finish.wait(timeout=3)
            return {'content': [{'type': 'text', 'text': node['id']}]}
        def run():
            try:
                executor.execute(self.nodes(), invoke, checkpoint=lambda results: saved.append(copy.deepcopy(results)), cancel_event=cancel)
            except BaseException as error:
                errors.append(error)
        worker = threading.Thread(target=run)
        worker.start()
        try:
            started.wait(timeout=3)
            cancel.set()
            # Wait until the coordinator notices cancellation, while physical reads remain held.
            time.sleep(.15)
            self.assertTrue(worker.is_alive())
            self.assertTrue(executor.admission.acquire(blocking=False))
            self.assertFalse(executor.admission.acquire(blocking=False))
            executor.admission.release()
        finally:
            finish.set()
            worker.join(timeout=4)
        self.assertFalse(worker.is_alive())
        self.assertIsInstance(errors[0], InterruptedError)
        self.assertEqual({'one', 'two'}, set(saved[-1]))
        self.assertEqual({'one', 'two'}, set(calls))
        resumed = []
        result = executor.execute(self.nodes(), lambda node: resumed.append(node['id']) or {'ok': True}, saved[-1])
        self.assertEqual(['three'], resumed)
        self.assertEqual(3, len(result))

    def test_coordinator_failure_still_persists_a_read_that_finishes_during_drain(self):
        saved, calls = [], []
        def fail_progress(event):
            if event['active']:
                raise RuntimeError('fixture UI callback failed')
        with self.assertRaises(RuntimeError):
            BatchExecutor(maximum=1).execute(self.nodes()[:1], lambda node: calls.append(node['id']) or {'ok': True},
                checkpoint=lambda result: saved.append(copy.deepcopy(result)), progress=fail_progress)
        # The worker may be cancelled before dispatch, or the actual completed read must be retained.
        if calls:
            self.assertEqual({'one': {'ok': True}}, saved[-1])

    def test_coordinator_failure_drains_success_but_keeps_interrupted_worker_resumable(self):
        started = threading.Barrier(3)
        saved, calls = [], []
        executor = BatchExecutor(maximum=2)
        def invoke(node):
            calls.append(node['id'])
            started.wait(timeout=3)
            if node['id'] == 'one':
                raise InterruptedError('fixture read stopped before a result')
            return {'content': node['id']}
        def progress(event):
            if event['active'] == 2:
                started.wait(timeout=3)
                raise RuntimeError('fixture coordinator failed')
        with self.assertRaisesRegex(RuntimeError, 'coordinator'):
            executor.execute(self.nodes(), invoke, checkpoint=lambda results: saved.append(copy.deepcopy(results)),
                             progress=progress)
        self.assertEqual({'one', 'two'}, set(calls))
        self.assertEqual({'two': {'content': 'two'}}, saved[-1])
        resumed = []
        result = executor.execute(self.nodes(), lambda node: resumed.append(node['id']) or {'ok': True}, saved[-1])
        self.assertEqual(['one', 'three'], resumed)
        self.assertEqual(3, len(result))
        self.assertTrue(executor.admission.acquire(blocking=False))
        self.assertTrue(executor.admission.acquire(blocking=False))
        executor.admission.release()
        executor.admission.release()

    def test_tls_failure_has_one_physical_attempt(self):
        calls = []
        def invoke(node):
            calls.append(node['id'])
            raise requests.exceptions.SSLError('fixture certificate failed')
        results = BatchExecutor().execute(self.nodes()[:1], invoke)
        self.assertEqual(['one'], calls)
        self.assertTrue(results['one']['isError'])

    def test_executor_construction_failure_does_not_leak_admission(self):
        executor = BatchExecutor()
        with patch('v14.batch.ThreadPoolExecutor', side_effect=RuntimeError('fixture allocation failure')):
            for _ in range(3):
                with self.assertRaisesRegex(RuntimeError, 'allocation'):
                    executor.execute(self.nodes(), lambda node: {})
        self.assertTrue(executor.admission.acquire(blocking=False))
        self.assertTrue(executor.admission.acquire(blocking=False))
        executor.admission.release()
        executor.admission.release()


class ReplayEvidenceFaultTest(unittest.TestCase):
    def snapshot(self, last, sequences, cursor=0):
        return replay_snapshot('job', {'last_sequence': last, 'generation': 'g', 'status': 'running'},
                               [{'sequence': sequence} for sequence in sequences], cursor, 'v14', 'g')

    def test_internal_gap_is_not_mistaken_for_contiguous_replay(self):
        snapshot = self.snapshot(6, [2, 3, 5, 6], 1)
        self.assertEqual([{'start': 4, 'end': 4}], snapshot['missingRanges'])
        self.assertEqual(6, snapshot['sequence'])
        self.assertTrue(snapshot['replayGap'])

    def test_tail_gap_and_fully_pruned_history_require_result_reconciliation(self):
        self.assertEqual([{'start': 4, 'end': 7}], self.snapshot(7, [2, 3], 1)['missingRanges'])
        self.assertEqual([{'start': 2, 'end': 7}], self.snapshot(7, [], 1)['missingRanges'])
        self.assertEqual('poll_job_result_before_continuing', self.snapshot(7, [], 1)['recovery'])

    def test_duplicate_malformed_or_future_sequences_are_not_valid_history(self):
        for last, sequences, cursor in ((3, [1, 2, 2, 3], 0), (3, [True, 2, 3], 0), (3, [1, 2, 4], 0), (3, [], 4)):
            with self.subTest(sequences=sequences, cursor=cursor):
                self.assertFalse(self.snapshot(last, sequences, cursor)['sequenceEvidenceConsistent'])

    def test_contiguous_history_and_empty_new_job_need_no_snapshot(self):
        self.assertIsNone(self.snapshot(4, [1, 2, 3, 4]))
        self.assertIsNone(self.snapshot(4, [3, 4], 2))
        self.assertIsNone(self.snapshot(0, []))

    def test_missing_range_metadata_is_bounded(self):
        snapshot = self.snapshot(100, list(range(2, 101, 2)))
        self.assertEqual(32, len(snapshot['missingRanges']))
        self.assertTrue(snapshot['missingRangesTruncated'])

    def test_invalid_cursor_cannot_coerce_to_another_generation(self):
        for cursor in (True, -1, '2'):
            with self.assertRaises(ValueError):
                self.snapshot(3, [1, 2, 3], cursor)


class EvidenceProjectionFaultTest(unittest.TestCase):
    def test_wide_object_visits_and_allocates_only_the_node_budget(self):
        class Counted(dict):
            visited = 0
            def items(self):
                for item in super().items():
                    self.visited += 1
                    yield item
        source = Counted({str(i): i for i in range(20_000)})
        view, omissions = compact_evidence(source, 256, nodes=32)
        self.assertLessEqual(source.visited, 32)
        self.assertEqual(31, len(view))
        self.assertEqual(20_000, omissions[-1]['retained_count'])
        self.assertLess(len(json.dumps([view, omissions])), 4096)
        self.assertEqual(20_000, len(source))

    def test_aggregate_text_and_untrusted_keys_remain_bounded(self):
        source = {str(i): '\U0001f680' * 10_000 for i in range(1000)}
        view, omissions = compact_evidence(source, 256, nodes=2048)
        self.assertLess(len(json.dumps([view, omissions], ensure_ascii=False).encode()), 16_000)
        self.assertTrue(omissions)
        long_keys, key_omissions = compact_evidence({'k' * 100_000: 'sensitive source'}, 256)
        self.assertEqual({}, long_keys)
        self.assertEqual('VIEW_KEY_LIMIT', key_omissions[0]['reason'])
        self.assertLess(len(json.dumps(key_omissions)), 1024)

    def test_compaction_preserves_selected_model_objective_and_output_reserves(self):
        body = {'objective': 'Inspect the selected repository only', 'evidence': {str(i): 'x' * 200 for i in range(20_000)},
                'historical_memory': 'old' * 10_000, 'code_graph': {'paths': ['p'] * 1000}}
        payload = {'model': 'selected-A', 'max_tokens': 512,
                   'messages': [{'role': 'system', 'content': 'Preserve authorization.'}, {'role': 'user', 'content': json.dumps(body)}]}
        original = copy.deepcopy(payload)
        def counter(value):
            return len(json.dumps(value, ensure_ascii=False).encode()) // 3, 'CONSERVATIVE_ESTIMATE'
        fitted, budget, projection = fit_engineering_payload(payload,
            {'limits_verified': True, 'context_tokens': 8192, 'max_output_tokens': 2048}, counter)
        self.assertEqual(original, payload)
        self.assertEqual('selected-A', fitted['model'])
        self.assertEqual(512, fitted['max_tokens'])
        self.assertEqual(original['messages'][0], fitted['messages'][0])
        self.assertEqual(body['objective'], json.loads(fitted['messages'][-1]['content'])['objective'])
        self.assertTrue(projection['completion_reserves_preserved'])
        self.assertGreater(budget['verification_tokens'], 0)
        self.assertGreater(budget['final_tokens'], 0)
        self.assertFalse(budget['input_tokens_measured'])

    def test_utf8_excerpts_are_valid_and_marked_incomplete(self):
        view, omissions = compact_evidence('\U0001f680' * 100, 7)
        self.assertEqual('\U0001f680', view['excerpt'])
        self.assertFalse(view['complete'])
        self.assertEqual(4, omissions[0]['visible_bytes'])


class CatalogueContractFaultTest(unittest.TestCase):
    def setUp(self):
        self.config = {'server': {'command': 'fixture'}}
        self.reply = {'tools': [{'name': 'read', 'inputSchema': {'type': 'object', 'properties': {}}}]}
        self.error = None
        self.calls = []
        def call(*args, **kwargs):
            self.calls.append(args)
            if self.error is not None:
                raise self.error
            return copy.deepcopy(self.reply)
        self.runtime = SimpleNamespace(load_mcp_config=lambda: self.config, mcp_tool_metadata={}, mcp_call=call)
        self.cache = CatalogCache(self.runtime, interactive=1, total=2)

    def refresh(self):
        self.cache.get(True)
        with self.cache.lock:
            events = [event for event, _ in self.cache.flights.values()]
        for event in events:
            self.assertTrue(event.wait(3))
        return self.cache.get()

    def test_malformed_pages_are_held_until_explicit_refresh(self):
        for reply in ({}, {'isError': True, 'tools': []}, {'tools': {}}, {'tools': [None]},
                      {'tools': [{'name': 'read'}]}, {'tools': [{'name': None, 'inputSchema': {}}]},
                      {'tools': [{'name': 'read', 'inputSchema': {'type': 'invented'}}]},
                      {'tools': [], 'nextCursor': 10}):
            with self.subTest(reply=reply):
                self.reply = reply
                self.assertEqual([], self.refresh())
                calls = len(self.calls)
                self.assertEqual([], self.cache.get())
                self.assertEqual(calls, len(self.calls))
                self.assertFalse(self.cache.readiness()['schemaReady'])
        self.reply = {'tools': []}
        self.assertEqual([], self.refresh())
        self.assertTrue(self.cache.readiness()['schemaReady'])
        self.assertIsNone(self.cache.readiness()['executionVerified'])

    def test_invalid_replacement_cannot_keep_an_old_executable_catalogue(self):
        self.assertEqual(1, len(self.refresh()))
        self.reply = {'tools': [{'name': 'read', 'inputSchema': {'type': 'impossible'}}]}
        self.assertEqual([], self.refresh())
        self.assertFalse(self.cache.readiness()['schemaReady'])
        self.assertFalse(self.cache.readiness()['toolsReady'])

    def test_changed_schema_invalidates_previous_execution_evidence(self):
        self.assertEqual(1, len(self.refresh()))
        self.cache.observe_execution('server', self.config['server'], 'read', True,
            contract=self.cache.execution_contract('server', self.config['server'], 'read'))
        self.assertTrue(self.cache.readiness()['executionVerified'])
        self.refresh()
        self.assertTrue(self.cache.readiness()['executionVerified'])
        self.reply['tools'][0]['inputSchema']['required'] = ['id']
        self.refresh()
        self.assertTrue(self.cache.readiness()['schemaReady'])
        self.assertIsNone(self.cache.readiness()['executionVerified'])
        self.assertIsNone(self.cache.readiness()['toolsReady'])

    def test_late_old_contract_success_cannot_verify_changed_schema_or_new_authorization(self):
        self.refresh()
        old = self.cache.execution_contract('server', self.config['server'], 'read')
        self.reply['tools'][0]['inputSchema']['required'] = ['new']
        self.refresh()
        self.cache.observe_execution('server', self.config['server'], 'read', True, contract=old)
        self.assertIsNone(self.cache.readiness()['executionVerified'])
        new = self.cache.execution_contract('server', self.config['server'], 'read')
        self.assertNotEqual(old, new)
        self.cache.observe_execution('server', self.config['server'], 'read', True, contract=new)
        self.assertTrue(self.cache.readiness()['executionVerified'])
        self.error = http_failure(401)
        self.refresh()
        self.cache.observe_execution('server', self.config['server'], 'read', True, contract=new)
        self.assertEqual('AUTH_REQUIRED', self.cache.readiness()['toolsState'])
        self.assertFalse(self.cache.readiness()['toolsReady'])

    def test_production_mcp_facade_binds_observation_before_the_physical_call(self):
        from v14.mcp import install_mcp_resilience
        self.refresh()
        changed = [False]
        def physical(method, params, **kwargs):
            if method == 'tools/list':
                return copy.deepcopy(self.reply)
            if changed[0]:
                self.reply['tools'][0]['inputSchema']['required'] = ['new-id']
                self.refresh()
            return {'content': [{'type': 'text', 'text': 'observed'}], 'isError': False}
        session = SimpleNamespace(cfg=self.config['server'], call=physical)
        self.runtime.PersistentMCPStdioSession = object
        self.runtime.get_mcp_session = lambda name: session
        self.runtime.MCP_CALL_INACTIVITY_TIMEOUT_SECONDS = 1
        self.runtime.MCP_CALL_ABSOLUTE_TIMEOUT_SECONDS = 2
        self.runtime.gateway_catalogue = self.cache
        install_mcp_resilience(self.runtime)
        self.runtime.mcp_call('server', 'tools/call', {'name': 'read'})
        self.assertTrue(self.cache.readiness()['executionVerified'])
        changed[0] = True
        self.runtime.mcp_call('server', 'tools/call', {'name': 'read'})
        self.assertIsNone(self.cache.readiness()['executionVerified'])

    def test_auth_denial_is_not_healthy_and_unrelated_numbers_do_not_claim_denial(self):
        self.error = http_failure(403)
        self.assertEqual([], self.refresh())
        self.assertEqual('AUTH_REQUIRED', self.cache.readiness()['toolsState'])
        self.error = TimeoutError('read 403 bytes before fixture timed out')
        self.refresh()
        self.assertNotEqual('AUTH_REQUIRED', self.cache.readiness()['toolsState'])

    def test_changed_configuration_cancels_old_discovery_without_late_publication(self):
        started, released, cancelled = threading.Event(), threading.Event(), threading.Event()
        def call(*args, **kwargs):
            started.set()
            if kwargs['cancel_event'].wait(2):
                cancelled.set()
            released.wait(2)
            return self.reply
        self.runtime.mcp_call = call
        self.cache.interactive = .01
        self.cache.get()
        self.assertTrue(started.wait(1))
        self.config.clear()
        self.assertEqual([], self.cache.get())
        self.assertTrue(cancelled.wait(1))
        with self.cache.lock:
            done = list(self.cache.flights.values())[0][0]
        released.set()
        self.assertTrue(done.wait(2))
        self.assertEqual({}, self.cache.cache)
        self.assertEqual({}, self.cache.health)
        self.assertEqual('NOT_CONFIGURED', self.cache.readiness()['toolsState'])


if __name__ == '__main__':
    unittest.main()
