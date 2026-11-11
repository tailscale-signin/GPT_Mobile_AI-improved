import copy
import json
import os
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_v13_transport import post_chat
from v14.engineering import EngineeringService, EngineeringStore
from v14.engineering_backend import GitHubEngineeringBackend
from v14.execution_binding import ExecutionBindingError
from v14.inference_budget import compact_evidence, fit_engineering_payload, plan_tokens
from v14.inference_scheduler import FairInferenceScheduler
from v14.debug import DebugLogStore, current_diagnostic_capture, current_diagnostic_trace


def selected(endpoint, model='A'):
    return {'profile_uid': 'profile-A', 'profile_revision': 'revision-1',
            'provider_connection_id': 'connection-A', 'provider_connection_revision': 'revision-1',
            'provider_kind': 'LLAMA', 'configured_endpoint': endpoint, 'requested_model_id': model,
            'generation_parameters': {'temperature': 0.1, 'max_tokens': 1000},
            'privacy_scope': 'repository-owner/repo', 'tool_owner': 'GATEWAY',
            'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']}


class SelectedExecutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.debug_stores = []
        self.calls, self.answer_model, self.empty_once, self.template_failure = [], 'A', False, False
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, data, status=200):
                raw = json.dumps(data).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                if self.path.startswith('/v1/models'):
                    return self.reply({'data': [{'id': 'A', 'context_length': 1048576}]})
                if self.path.startswith('/props'):
                    return self.reply({'model_alias': 'A', 'model_sha256': 'weights-A', 'chat_template': 'template-A',
                                       'default_generation_settings': {'n_ctx': 32768}})
                return self.reply({}, 404)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                if self.path == '/v1/chat/completions/input_tokens':
                    return self.reply({'input_tokens': 300})
                if self.path != '/v1/chat/completions':
                    return self.reply({}, 404)
                fixture.calls.append({'path': self.path, 'body': body, 'headers': dict(self.headers)})
                if fixture.template_failure:
                    return self.reply({'error': {'code': 500, 'type': 'server_error',
                                                'message': 'Jinja Exception: System message must be at the beginning.'}}, 500)
                content = '' if fixture.empty_once else '{"plan":"checked","paths":[],"approved":true,"issues":[]}'
                fixture.empty_once = False
                return self.reply({'model': fixture.answer_model, 'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': content}}],
                                   'usage': {'prompt_tokens': 300, 'completion_tokens': 10, 'total_tokens': 310}})
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = 'http://127.0.0.1:' + str(self.server.server_port)
        self.runtime = self.runtime_for('boot-one')
        self.store, self.backend, self.service = self.service_for(self.runtime)
        admitted = self.service.prepare_binding('owner', selected(self.endpoint))
        self.binding = admitted['binding']
        self.request = {'repository': 'owner/repo', 'branch': 'feature/job', 'base': 'main', 'objective': 'Repair tests',
                        'model': 'A', 'profile_id': 'profile-A', 'request_id': 'one', 'execution_binding_id': self.binding['binding_id']}
        self.job = self.store.claim(self.store.create('owner', self.request)['id'])[0]

    def test_public_model_handshake_observes_loaded_allocation_without_claiming_catalog_or_output_limits(self):
        observed = self.service.capabilities.selected_model('A')
        self.assertTrue(observed['limitsVerified'])
        self.assertEqual(32768, observed['contextTokens'])
        self.assertNotEqual(1048576, observed['contextTokens'])
        self.assertIsNone(observed['maxOutputTokens'])
        self.assertEqual('context_capacity', observed['outputLimitSource'])
        self.assertEqual('A', observed['requestedModel'])
        self.assertEqual('A', observed['reportedModel'])
        self.assertEqual('GATEWAY_OBSERVED_PROVIDER_METADATA', observed['evidenceOrigin'])
        self.assertNotIn('tokenizer_endpoint', observed)
        self.assertNotIn('backend_endpoint_identity', observed)
        self.assertEqual([], self.calls)

    def test_public_model_handshake_never_substitutes_a_different_catalog_model(self):
        observed = self.service.capabilities.selected_model('B')
        self.assertFalse(observed['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_UNAVAILABLE', observed['failureCode'])
        self.assertNotIn('contextTokens', observed)
        self.assertNotIn('reportedModel', observed)
        self.assertEqual([], self.calls)

    def test_public_model_handshake_leaves_ambiguous_router_allocation_unconfirmed(self):
        self.service.capabilities.cache.clear()
        with patch.object(self.service.capabilities, '_json', side_effect=[
            {'data': [{'id': 'A'}, {'id': 'B'}]},
            {'default_generation_settings': {'n_ctx': 8192}, 'max_output_tokens': 1024},
        ]):
            observed = self.service.capabilities.selected_model('A')
        self.assertFalse(observed['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_IDENTITY_INVALID', observed['failureCode'])
        self.assertNotIn('contextTokens', observed)
        self.assertNotIn('reportedModel', observed)
        self.assertEqual([], self.calls)

    def test_public_model_handshake_confirms_explicit_output_limits_and_rejects_loaded_identity_mismatch(self):
        for reported in ('A', 'different-loaded-model'):
            self.service.capabilities.cache.clear()
            with patch.object(self.service.capabilities, '_json', side_effect=[
                {'data': [{'id': 'A', 'context_length': 1048576}]},
                {'model_alias': reported, 'default_generation_settings': {'n_ctx': 8192}, 'max_output_tokens': 1024},
            ]):
                observed = self.service.capabilities.selected_model('A')
            self.assertEqual(reported == 'A', observed['limitsVerified'])
            if reported == 'A':
                self.assertEqual(8192, observed['contextTokens'])
                self.assertEqual(1024, observed['maxOutputTokens'])
                self.assertEqual('provider_reported', observed['outputLimitSource'])
            else:
                self.assertEqual('EXECUTION_MODEL_MISMATCH', observed['failureCode'])
                self.assertNotIn('reportedModel', observed)
        self.assertEqual([], self.calls)

    def runtime_for(self, boot):
        runtime = SimpleNamespace(LLAMA_BASE=self.endpoint, gateway_instance_id=boot,
            SELECTED_ONLY_PHYSICAL_GUARDS=True, GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
            durable_job_lock=threading.RLock(), _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / 'jobs.db'),
            _acquire_llama_model_gate=lambda *a, **k: None, _release_llama_model_gate=lambda *a: None,
            get_http_session=requests.Session, http_get=requests.get)
        def dispatch(url, json, _gateway_job_mode, _gateway_cancel_event):
            runtime._acquire_llama_model_gate(_gateway_job_mode, cancel_event=_gateway_cancel_event)
            try:
                with requests.Session() as session:
                    return post_chat(session, url, json, connect_timeout=2, idle_timeout=2, deadline_seconds=8,
                        cancelled=_gateway_cancel_event.is_set, progress=lambda event: None)
            finally:
                runtime._release_llama_model_gate(time.monotonic())
        runtime.http_post = dispatch
        return runtime

    def service_for(self, runtime):
        store = EngineeringStore(runtime)
        backend = GitHubEngineeringBackend(runtime)
        service = EngineeringService(store, backend)
        backend.api = lambda *args, **kwargs: {'permissions': {'pull': True, 'push': True}}
        backend.recall_memory = lambda *args: {'records': []}
        backend.recall_graph = lambda *args: {'status': 'NOT_INDEXED'}
        return store, backend, service

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        for store in self.debug_stores:
            store.stop()
        self.temp.cleanup()

    def test_actual_outbound_requests_keep_A_for_all_roles_repair_and_restart(self):
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_REVIEW_MODEL': 'B'}):
            for role in ('planner', 'coding agent', 'independent reviewer', 'repair planner', 'final summary'):
                self.backend.model(self.job, role, {'files': []})
            self.empty_once = True
            self.backend.model(self.job, 'planner', {})
            self.assertEqual(7, len(self.calls))
            self.store.complete(self.job, self.job['_execution_lease'], 'READ', {})
            runtime = self.runtime_for('boot-two')
            store, backend, service = self.service_for(runtime)
            binding = service.prepare_binding('owner', selected(self.endpoint))['binding']
            self.assertEqual(self.binding, binding)
            recovered = store.claim(self.job['id'])[0]
            backend.model(recovered, 'independent reviewer', {})
        self.assertEqual({'A'}, {call['body']['model'] for call in self.calls})
        self.assertTrue(all(call['headers']['X-Gateway-Execution-Binding'] == self.binding['binding_id'] for call in self.calls))
        self.assertTrue(all(call['body']['cache_prompt'] is False for call in self.calls))
        receipts = store.model_attempts(self.job['id'])
        self.assertEqual(len(self.calls), len(receipts))
        self.assertEqual({'VERIFIED'}, {receipt['identity_status'] for receipt in receipts})
        self.assertEqual({'boot-one', 'boot-two'}, {receipt['boot_id'] for receipt in receipts})
        self.assertEqual({'SELECTED_PROFILE_OVERRIDE_REJECTED'}, {receipt['rejected_configuration'] for receipt in receipts})
        self.assertNotIn('boot_id', binding)
        self.assertNotIn('lease_id', binding)

    def test_queued_configuration_change_never_sends_to_any_model(self):
        held = self.service.scheduler.acquire('interactive')
        errors = []
        worker = threading.Thread(target=lambda: self.capture_error(errors, lambda: self.backend.model(self.job, 'planner', {})))
        worker.start()
        until = time.monotonic() + 2
        while self.service.scheduler.snapshot()['queued'] == 0 and time.monotonic() < until:
            time.sleep(.01)
        self.runtime.LLAMA_BASE = self.endpoint + '/changed'
        self.service.scheduler.release(held)
        worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual('EXECUTION_CONFIGURATION_CHANGED', errors[0].code)
        self.assertEqual([], self.calls)
        self.assertEqual(0, self.service.scheduler.snapshot()['active'])

    def test_supervisor_model_events_keep_the_submitted_capture_after_request_and_restart(self):
        path = Path(self.temp.name) / 'debug.jsonl'
        debug = DebugLogStore(path, enabled=True)
        self.debug_stores.append(debug)
        self.runtime.gateway_debug = debug
        session, trace_id, parent_span = 'd' * 32, 'e' * 32, 'f' * 16
        capture = current_diagnostic_capture.set({'session': session, 'started': int(time.time() * 1000),
            'request_state': {'principal': SimpleNamespace(id='owner')}})
        trace = current_diagnostic_trace.set({'trace_id': trace_id, 'span_id': parent_span})
        try:
            submitted = self.store.create('owner', dict(self.request, request_id='captured-job'))
        finally:
            current_diagnostic_trace.reset(trace)
            current_diagnostic_capture.reset(capture)
        self.assertEqual(session, submitted['diagnostic_capture']['session_id'])

        def model_stage(backend):
            return lambda job, phase: {'plan': backend.model(job, 'planner', {'files': []})}

        self.backend.step = model_stage(self.backend)
        with ThreadPoolExecutor(max_workers=1) as pool:
            pool.submit(self.service.tick, submitted['id']).result(timeout=8)
        debug.flush()
        debug.stop()

        restarted_debug = DebugLogStore(path, enabled=True)
        self.debug_stores.append(restarted_debug)
        runtime = self.runtime_for('boot-two')
        runtime.gateway_debug = restarted_debug
        store, backend, service = self.service_for(runtime)
        backend.step = model_stage(backend)
        with ThreadPoolExecutor(max_workers=1) as pool:
            pool.submit(service.tick, submitted['id']).result(timeout=8)
        restarted_debug.flush()
        saved = store.get(submitted['id'])
        self.assertEqual('IMPLEMENT', saved['state'], saved['message'])
        self.assertEqual(2, len(self.calls))
        rows = restarted_debug.journal.read('owner', session)['events']
        self.assertEqual(4, len(rows))
        self.assertEqual({trace_id}, {row['trace_id'] for row in rows})
        self.assertEqual({parent_span}, {row['parent_span_id'] for row in rows})
        self.assertEqual({'model'}, {row['category'] for row in rows})
        self.assertTrue(all('model_digest' in row['bounded_attributes'] for row in rows))
        self.assertEqual(2, len({row['span_id'] for row in rows}))
        self.assertEqual(2, len({row['origin_boot_id'] for row in rows}))
        self.assertTrue(all(call['headers'].get('traceparent', '').split('-')[1:2] == [trace_id] for call in self.calls))
        self.assertIsNone(current_diagnostic_capture.get())

    @staticmethod
    def capture_error(errors, callback):
        try:
            callback()
        except BaseException as error:
            errors.append(error)

    def test_contradictory_or_unavailable_model_receipt_blocks_writes(self):
        for reported, expected in [('B', 'CONTRADICTED'), (None, 'UNAVAILABLE')]:
            self.answer_model = reported
            with self.assertRaises(ExecutionBindingError) as failure:
                self.backend.model(self.job, 'planner', {})
            self.assertTrue(failure.exception.dispatched)
            self.assertEqual(expected, self.job['data']['latest_execution_receipt']['identity_status'])
            with self.assertRaises(ExecutionBindingError):
                self.service.validate_mutation(self.job)

    def test_destination_and_model_selection_fail_before_inference(self):
        for change in ({'configured_endpoint': 'http://attacker.invalid'}, {'requested_model_id': 'B'}):
            with self.assertRaises(ExecutionBindingError):
                self.service.prepare_binding('owner', dict(selected(self.endpoint), **change))
        self.assertEqual([], self.calls)

    def test_template_rejection_has_a_terminal_physical_receipt_without_replay(self):
        from gateway_v13_runtime import GatewayContractError
        self.template_failure = True
        with self.assertRaises(GatewayContractError) as failure:
            self.backend.model(self.job, 'planner', {})
        self.assertEqual('backend_template_invalid', failure.exception.code)
        self.assertEqual(1, len(self.calls))
        receipt = self.store.model_attempts(self.job['id'])[0]
        self.assertEqual('FAILED', receipt['state'])
        self.assertTrue(receipt['dispatched'])
        self.assertEqual('backend_template_invalid', receipt['error_code'])
        self.assertEqual(500, receipt['failure_details']['backend_status'])
        self.assertEqual('backend_rejected', receipt['failure_details']['dispatch_state'])
        self.assertFalse(receipt['failure_details']['retryable'])
        self.assertIsInstance(receipt['completed_at_ms'], int)

    def test_revoked_binding_and_cancelled_lease_stop_before_dispatch(self):
        self.service.bindings.revoke('owner', self.binding['binding_id'])
        with self.assertRaises(ExecutionBindingError):
            self.backend.model(self.job, 'planner', {})
        self.assertEqual([], self.calls)

    def test_run_budget_is_counted_per_physical_request(self):
        self.job['request']['execution_budget'] = {'model_calls': 4}
        self.backend.model(self.job, 'planner', {})
        with self.assertRaises(ExecutionBindingError) as failure:
            self.backend.model(self.job, 'planner', {})
        self.assertEqual('EXECUTION_RUN_BUDGET_EXHAUSTED', failure.exception.code)
        self.assertEqual(1, len(self.calls))

    def test_ultra_model_call_ceiling_preserves_review_and_final_capacity_at_physical_dispatch(self):
        self.assertEqual(512, self.job['request']['execution_budget']['model_calls'])
        for index in range(508):
            self.store.record_model_attempt(self.job, {'attempt_id': 'seed-' + str(index),
                'binding_id': self.binding['binding_id'], 'state': 'DISPATCHED', 'dispatched': True,
                'reserved_tokens': 1, 'role': 'planner'})
        self.backend.model(self.job, 'planner', {})
        with self.assertRaises(ExecutionBindingError) as planner:
            self.backend.model(self.job, 'planner', {})
        self.assertEqual('EXECUTION_RUN_BUDGET_EXHAUSTED', planner.exception.code)
        self.assertEqual(1, len(self.calls))
        self.backend.model(self.job, 'selected-model reviewer', {})
        self.backend.model(self.job, 'selected-model reviewer', {})
        self.backend.model(self.job, 'final report', {})
        with self.assertRaises(ExecutionBindingError) as final:
            self.backend.model(self.job, 'final report', {})
        self.assertEqual('EXECUTION_RUN_BUDGET_EXHAUSTED', final.exception.code)
        self.assertEqual(4, len(self.calls))
        self.assertTrue(all(call['body']['model'] == 'A' for call in self.calls))
        dispatched = self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_model_attempts WHERE job_id=? AND dispatched=1', (self.job['id'],)).fetchone()[0])
        self.assertEqual(512, dispatched)


class BudgetAndSchedulerTests(unittest.TestCase):
    def test_explicit_output_and_reserves_never_silently_shrink(self):
        caps = {'context_tokens': 4096, 'max_output_tokens': 4096, 'limits_verified': True}
        result = plan_tokens({'max_tokens': 512}, caps, lambda _: (1000, 'SELECTED_TOKENIZER'), final_tokens=256)
        self.assertEqual(512, result['output_tokens'])
        self.assertEqual(2200, result['remaining_tokens'])
        with self.assertRaises(ExecutionBindingError):
            plan_tokens({'max_tokens': 4096}, caps, lambda _: (1, 'SELECTED_TOKENIZER'))

    def test_projection_preserves_objective_and_marks_retained_evidence(self):
        caps = {'context_tokens': 8192, 'max_output_tokens': 8192, 'limits_verified': True}
        request = {'max_tokens': 1024, 'messages': [{'role': 'system', 'content': 'policy'}, {'role': 'user', 'content': json.dumps({'objective': 'keep exact objective', 'evidence': {'file': 'x' * 30000}})}]}
        fitted, budget, compaction = fit_engineering_payload(request, caps, lambda value: (len(value['messages'][-1]['content']), 'CONSERVATIVE_UTF8_BOUND'))
        self.assertEqual('keep exact objective', json.loads(fitted['messages'][-1]['content'])['objective'])
        self.assertIsNotNone(compaction)
        self.assertFalse(json.loads(fitted['messages'][-1]['content'])['evidence_view']['complete'])
        self.assertGreaterEqual(budget['remaining_tokens'], 0)
        self.assertEqual(30000, len(json.loads(request['messages'][-1]['content'])['evidence']['file']))

    def test_wide_projection_stops_traversal_at_the_node_budget(self):
        class CountedEvidence(dict):
            visited = 0
            def items(self):
                for item in super().items():
                    self.visited += 1
                    yield item
        evidence = CountedEvidence({str(i): i for i in range(10000)})
        view, omissions = compact_evidence(evidence, 256, nodes=16)
        self.assertEqual(15, len(view))  # The root consumes one node.
        self.assertLessEqual(evidence.visited, 16)
        self.assertLess(len(json.dumps(view)), 256)
        self.assertEqual([{'pointer': '', 'reason': 'VIEW_NODE_LIMIT', 'retained_count': 10000,
                           'visible_count': 15}], omissions)
        self.assertEqual(10000, len(evidence))

    def test_nested_projection_and_omission_ledger_remain_bounded(self):
        # Key admission now has its own aggregate bound. Use unkeyed nested
        # evidence to exercise omission overflow independently of that bound.
        evidence = [['é' * 300 for _ in range(500)] for _ in range(64)]
        view, omissions = compact_evidence(evidence, 256, nodes=1024)
        self.assertLessEqual(len(omissions), 256)
        self.assertEqual('VIEW_OMISSION_LIMIT', omissions[-1]['reason'])
        self.assertGreater(omissions[-1]['additional_omissions'], 0)
        self.assertLess(len(view), len(evidence))
        excerpt = view[0][0]
        self.assertEqual(256, len(excerpt['excerpt'].encode()))
        self.assertFalse(excerpt['complete'])
        self.assertEqual(600, excerpt['retained_bytes'])

    def test_wide_compaction_replaces_omitted_root_fields_and_keeps_reserves(self):
        caps = {'context_tokens': 8192, 'max_output_tokens': 8192, 'limits_verified': True}
        # Exhaust the node budget across bounded containers before the other
        # root fields; a single wide object is now stopped by its item limit.
        evidence = [list(range(500)) for _ in range(500)]
        envelope = {'objective': 'keep exact objective', 'evidence': evidence,
                    'historical_memory': {'source': 'x' * 10000}, 'code_graph': {'nodes': list(range(10000))}}
        request = {'max_tokens': 1024, 'messages': [{'role': 'system', 'content': 'policy'},
                                                  {'role': 'user', 'content': json.dumps(envelope)}]}
        original = copy.deepcopy(request)
        fitted, budget, compaction = fit_engineering_payload(request, caps,
            lambda value: (len(value['messages'][-1]['content']), 'CONSERVATIVE_UTF8_BOUND'))
        view = json.loads(fitted['messages'][-1]['content'])
        self.assertEqual('keep exact objective', view['objective'])
        self.assertEqual({'available_in_retained_evidence': True, 'view_omitted': True}, view['historical_memory'])
        self.assertEqual(view['historical_memory'], view['code_graph'])
        self.assertLess(len(view['evidence']), len(evidence))
        self.assertLess(compaction['view_node_limit'], 2048)
        self.assertEqual(1024, budget['output_tokens'])
        self.assertGreater(budget['final_tokens'], 0)
        self.assertGreaterEqual(budget['remaining_tokens'], 0)
        self.assertEqual(original, request)

    def test_cancelled_queue_releases_budget_and_never_validates(self):
        scheduler = FairInferenceScheduler(max_queue_bytes=10)
        active = scheduler.acquire('interactive')
        with self.assertRaises(InterruptedError):
            scheduler.acquire('background', byte_length=10, cancelled=lambda: True, validate=lambda: self.fail('cancelled work validated'))
        scheduler.release(active)
        self.assertEqual(0, scheduler.snapshot()['queued_bytes'])
        with self.assertRaises(ExecutionBindingError):
            scheduler.acquire('background', byte_length=11)


if __name__ == '__main__':
    unittest.main()
