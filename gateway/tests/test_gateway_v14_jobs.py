"""Real SQLite upgrade, streaming admission and restart regressions."""
import asyncio
import importlib
import json
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from contextlib import ExitStack, closing
from pathlib import Path
from unittest.mock import patch

import httpx
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient
from gateway_security import DeviceStore
from v14.jobs import DurableCommitError, record_execution_isolation


class JournalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.temp = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.db = Path(self.temp) / 'jobs.sqlite3'
        self.store = DeviceStore(Path(self.temp) / 'auth.sqlite3')
        self.runtime = self.gateway.runtime
        # Each fixture represents a fresh server lifetime. Admission state is
        # process-local; a preceding TestClient shutdown must not leak draining
        # or reserved worker slots into a new temporary journal.
        for name, value in {'draining': False, 'workers': {}, 'queued': set(), 'admitted': set()}.items():
            self.stack.enter_context(patch.object(self.gateway.service, name, value))
        for name, value in {
            'GATEWAY_JOB_DB_PATH': self.db, 'GATEWAY_DURABLE_JOBS': True,
            'durable_job_initialized': False, 'startup_services_started': False,
            'MCP_PREWARM_ON_STARTUP': False, 'job_registry': {},
            'singleflight_workers': {}, 'singleflight_request_index': {},
            'durable_job_metrics': dict.fromkeys(self.runtime.durable_job_metrics, 0),
            'device_store': self.store,
            'gateway_draining': False,
        }.items():
            self.stack.enter_context(patch.object(self.runtime, name, value))
        self.stack.enter_context(patch('v14.identity.device_store', self.store))
        self.gateway.app.middleware_stack = None
        self.addCleanup(setattr, self.gateway.app, 'middleware_stack', None)

    def client(self):
        return TestClient(self.gateway.app, base_url='http://127.0.0.1:8090', client=('127.0.0.1', 2222))

    def legacy_database(self, missing_finished=False):
        # Reproduce an existing journal without created_at, as in the Windows log.
        finished = '' if missing_finished else ', finished_at REAL'
        now = time.time()
        self.old_result = {'choices': [{'message': {'content': 'Saved answer'}}]}
        self.old_public = {'job_id': 'saved', 'status': 'completed', 'created_at': now - 20,
                           'updated_at': now, 'finished_at': now - 1, 'last_sequence': 7}
        self.old_event = {'sequence': 7, 'event': 'progress', 'message': 'Saved progress'}
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('CREATE TABLE gateway_jobs (job_id TEXT PRIMARY KEY, public_json TEXT NOT NULL, '
                       'result_json TEXT, status TEXT, updated_at REAL' + finished + ')')
            db.execute('CREATE TABLE gateway_events (job_id TEXT NOT NULL, sequence INTEGER NOT NULL, '
                       'event_json TEXT NOT NULL, created_at REAL NOT NULL, PRIMARY KEY(job_id, sequence))')
            db.execute('INSERT INTO gateway_jobs(job_id,public_json,result_json,status,updated_at) VALUES(?,?,?,?,?)',
                       ('saved', json.dumps(self.old_public), json.dumps(self.old_result), 'completed', now))
            db.execute('INSERT INTO gateway_events VALUES(?,?,?,?)',
                       ('saved', 7, json.dumps(self.old_event), now))
        self.store.claim('saved', 'local:single-user')

    def completion(self, payload, cancel, progress, hard_cancel):
        progress({'phase': 'working', 'message': 'Working'})
        return 200, {'id': 'chatcmpl-test', 'model': 'local', 'choices': [
            {'index': 0, 'message': {'role': 'assistant', 'content': 'SQLite reply'}, 'finish_reason': 'stop'}]}

    def test_durably_admitted_interactive_job_survives_android_disconnect_and_retains_one_saved_result(self):
        job_id = 'durable-disconnected'
        soft, hard = threading.Event(), threading.Event()
        self.runtime.register_gateway_job(job_id, 'local', 'interactive', soft, hard)
        self.store.claim(job_id, 'local:single-user')
        self.runtime.mark_gateway_job_disconnected(job_id)
        result = {'choices': [{'message': {'role': 'assistant', 'content': 'Finished while Android was offline'}}]}
        executions = []

        async def scenario():
            release = asyncio.Event()
            async def run_once():
                executions.append(job_id)
                await release.wait()
                self.runtime.finish_gateway_job(job_id, 200, result)
            worker = asyncio.create_task(run_once())
            self.runtime.singleflight_workers[job_id] = {'task': worker, 'clients': 0, 'job_mode': 'interactive'}
            try:
                with patch.object(self.runtime, 'SINGLEFLIGHT_ORPHAN_GRACE_SECONDS', 0):
                    await self.runtime.cancel_orphaned_singleflight_after_grace(job_id, worker, soft)
                self.assertFalse(worker.done())
                self.assertFalse(soft.is_set())
                self.assertTrue(self.runtime.gateway_continue_disconnected(job_id))
            finally:
                release.set()
                await worker
        asyncio.run(scenario())
        self.assertEqual([job_id], executions)
        with closing(sqlite3.connect(self.db)) as db:
            status, saved = db.execute('SELECT status,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
        self.assertEqual('completed', status)
        self.assertEqual(result, json.loads(saved))
        self.runtime.job_registry.clear()
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            recovered = client.get('/gateway/jobs/' + job_id + '/result')
            self.assertEqual(200, recovered.status_code, recovered.text)
            self.assertEqual(result, recovered.json()['result'])
            model.assert_not_called()

    def test_durable_retention_never_overrides_explicit_cancellation_or_uncommitted_admission(self):
        soft, hard = threading.Event(), threading.Event()
        self.runtime.register_gateway_job('manual-cancel', 'local', 'interactive', soft, hard)
        self.assertTrue(self.runtime.gateway_continue_disconnected('manual-cancel'))
        hard.set()
        self.assertFalse(self.runtime.gateway_continue_disconnected('manual-cancel'))
        self.runtime.job_registry['not-committed'] = {'status': 'running'}
        self.assertFalse(self.runtime.gateway_continue_disconnected('not-committed'))
        with patch.object(self.runtime, 'GATEWAY_DURABLE_JOBS', False):
            hard.clear()
            self.assertFalse(self.runtime.gateway_continue_disconnected('manual-cancel'))
        from v14.actions import current_gateway_job_id
        token = current_gateway_job_id.set('manual-cancel')
        try:
            self.assertTrue(self.runtime.should_continue_after_disconnect('interactive'))
        finally:
            current_gateway_job_id.reset(token)

    def chat(self, client, stream=True):
        headers = {'Origin': 'http://127.0.0.1:8090'}
        headers['X-Gateway-CSRF'] = client.get('/gateway/session', headers=headers).json()['csrfToken']
        return client.post('/v1/chat/completions', headers=headers, json={
            'model': 'local', 'messages': [{'role': 'user', 'content': 'Hello'}], 'stream': stream})

    def test_client_tool_handoff_continues_same_job_over_http_and_sqlite(self):
        import requests
        for stream, restart in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(stream=stream, restart=restart), self.client() as client:
                call = {'id': 'github-call-' + str((stream, restart)), 'type': 'function',
                        'function': {'name': 'github_ci__github_api', 'arguments': '{"action":"workflows"}'}}
                tool_message = {'role': 'assistant', 'content': None, 'tool_calls': [call]}
                replies = iter([
                    {'choices': [{'index': 0, 'message': tool_message, 'finish_reason': 'tool_calls'}]},
                    {'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': 'Verified workflow reply'}, 'finish_reason': 'stop'}]},
                ])
                def model_round(payload, *args, **kwargs):
                    response = requests.Response()
                    response.status_code = 200
                    response._content = json.dumps(next(replies)).encode()
                    return response, 1
                headers = {'X-Gateway-Request-Role': 'github-primary', 'X-Gateway-Delegated-Worker': 'true'}
                body = {'model': 'fixture', 'stream': stream, 'max_tokens': 128,
                        'messages': [{'role': 'user', 'content': 'Inspect workflows ' + str((stream, restart))}],
                        'tools': [{'type': 'function', 'function': {'name': 'github_ci__github_api',
                                  'parameters': {'type': 'object', 'required': ['action']}}}]}
                with patch.object(self.runtime, 'post_llama_model_round', side_effect=model_round) as backend, \
                        patch.object(self.runtime, 'apply_context_guard'), \
                        patch.object(self.runtime, 'configure_llama_model_round'):
                    first = client.post('/v1/chat/completions', json=body, headers=headers)
                    self.assertEqual(200, first.status_code, first.text)
                    job_id = first.headers['x-gateway-job-id']
                    self.assertEqual('waiting_for_client_tool', self.runtime.job_registry[job_id]['status'])
                    self.assertFalse(self.runtime.job_registry[job_id]['result_available'])
                    with closing(sqlite3.connect(self.db)) as db:
                        self.assertEqual('waiting_for_client_tool', db.execute('SELECT status FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()[0])
                    self.assertEqual(job_id, self.runtime.remote_handoff_index[call['id']]['job_id'])
                    if restart:
                        self.runtime.job_registry.clear()
                        self.runtime.singleflight_workers.clear()
                        self.runtime.singleflight_request_index.clear()
                        self.runtime.remote_handoff_index.clear()
                        self.runtime.durable_job_initialized = False
                        self.assertTrue(self.runtime.init_durable_job_store())
                        self.assertEqual('waiting_for_client_tool', self.runtime.job_registry[job_id]['status'])
                        self.assertEqual(1, backend.call_count)
                    pending = client.get('/gateway/jobs/' + job_id + '/result')
                    self.assertEqual(202, pending.status_code, pending.text)
                    self.assertEqual(tool_message, pending.json()['result']['choices'][0]['message'])
                    self.assertEqual('tool_calls', pending.json()['result_quality'])
                    self.assertEqual(1, backend.call_count)
                    missing = client.post('/v1/chat/completions', json=body, headers=dict(headers, **{'X-Gateway-Job-ID': job_id}))
                    self.assertEqual(409, missing.status_code, missing.text)
                    self.assertEqual('client_tool_results_required', missing.json()['error']['code'])
                    self.assertEqual('tool_continuation', missing.json()['error']['failed_stage'])
                    self.assertEqual(job_id, missing.json()['error']['job_id'])
                    self.assertTrue(missing.json()['error']['request_id'])
                    self.assertEqual('not_dispatched', missing.json()['error']['dispatch_state'])
                    self.assertEqual([call['id']], missing.json()['error']['pending_client_tool_call_ids'])
                    tool_result = {'role': 'tool', 'tool_call_id': call['id'],
                                   'content': '{"error":{"code":"NOT_FOUND","retryable":false}}' if restart else 'Two workflows found'}
                    changed_call = dict(call, function=dict(call['function'], arguments='{"action":"different"}'))
                    for suffix, code in (([tool_result], 'invalid_tool_history'),
                                         ([tool_message, tool_result, tool_result], 'invalid_tool_history'),
                                         ([dict(tool_message, tool_calls=[changed_call]), tool_result], 'client_tool_result_identity_mismatch')):
                        malformed = dict(body, messages=body['messages'] + suffix)
                        rejected = client.post('/v1/chat/completions', json=malformed, headers=dict(headers, **{'X-Gateway-Job-ID': job_id}))
                        self.assertEqual(422 if code == 'invalid_tool_history' else 409, rejected.status_code, rejected.text)
                        self.assertEqual(code, rejected.json()['error']['code'])
                        self.assertEqual(job_id, rejected.json()['error']['job_id'])
                        self.assertEqual('waiting_for_client_tool', self.runtime.job_registry[job_id]['status'])
                        self.assertEqual(1, backend.call_count)
                    body['messages'] += [tool_message, tool_result]
                    second = client.post('/v1/chat/completions', json=body, headers=dict(headers, **{'X-Gateway-Job-ID': job_id}))
                    self.assertEqual(200, second.status_code, second.text)
                    self.assertEqual(job_id, second.headers['x-gateway-job-id'])
                    self.assertIn('Verified workflow reply', second.text)
                    self.assertEqual(2, backend.call_count)
                    self.assertEqual('completed', self.runtime.job_registry[job_id]['status'])
                    self.assertEqual(tool_result, backend.call_args.args[0]['messages'][-1])
                    # A completed answer remains terminal; a new payload cannot resurrect it.
                    body['messages'].append({'role': 'user', 'content': 'Another task'})
                    terminal = client.post('/v1/chat/completions', json=body, headers=dict(headers, **{'X-Gateway-Job-ID': job_id}))
                    self.assertEqual(409, terminal.status_code, terminal.text)
                    self.assertEqual('terminal_job_not_resumable', terminal.json()['error']['code'])
                    self.assertFalse(terminal.json()['error']['retryable'])
                    self.assertEqual(2, backend.call_count)
                    # Reconnection reads the original terminal receipt; it never
                    # reruns the model or tools, including after SQLite restore.
                    if restart:
                        self.runtime.job_registry.clear()
                        self.runtime.singleflight_workers.clear()
                        self.runtime.singleflight_request_index.clear()
                        self.runtime.durable_job_initialized = False
                        self.assertTrue(self.runtime.init_durable_job_store())
                    recovered = client.get('/gateway/jobs/' + job_id + '/result')
                    self.assertEqual(200, recovered.status_code, recovered.text)
                    self.assertEqual(job_id, recovered.json()['job_id'])
                    self.assertEqual('complete', recovered.json()['result_quality'])
                    self.assertEqual('Verified workflow reply', recovered.json()['content'])
                    self.assertIsInstance(recovered.json()['completed_at'], (int, float))
                    proof = recovered.json()['job']
                    self.assertEqual(1, proof['execution_isolation_schema'])
                    self.assertEqual('entire_job', proof['execution_isolation_scope'])
                    self.assertEqual('github_client_owned', proof['execution_isolation'])
                    self.assertIs(False, proof['server_tools_allowed'])
                    self.assertNotIn('_server_isolation_new', proof)
                    self.assertEqual(2, backend.call_count)

    def test_historical_tool_ids_do_not_bind_new_turns_but_terminal_returns_remain_fenced(self):
        import requests
        call = {'id': 'historical-client-call', 'type': 'function',
                'function': {'name': 'github_ci__github_api', 'arguments': '{"action":"workflows"}'}}
        tool_message = {'role': 'assistant', 'content': None, 'tool_calls': [call]}
        tool_result = {'role': 'tool', 'tool_call_id': call['id'], 'content': 'Two workflows found'}
        answer = {'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': 'Verified workflow reply'},
                               'finish_reason': 'stop'}]}
        replies = iter([{'choices': [{'index': 0, 'message': tool_message, 'finish_reason': 'tool_calls'}]}])

        def model_round(payload, *args, **kwargs):
            response = requests.Response()
            response.status_code = 200
            response._content = json.dumps(next(replies, answer)).encode()
            return response, 1

        headers = {'X-Gateway-Request-Role': 'github-primary', 'X-Gateway-Delegated-Worker': 'true'}
        body = {'model': 'fixture', 'stream': False, 'max_tokens': 128,
                'messages': [{'role': 'user', 'content': 'Inspect workflow history'}],
                'tools': [{'type': 'function', 'function': {'name': 'github_ci__github_api',
                          'parameters': {'type': 'object', 'required': ['action']}}}]}
        with self.client() as client, \
                patch.object(self.runtime, 'post_llama_model_round', side_effect=model_round) as backend, \
                patch.object(self.runtime, 'apply_context_guard'), \
                patch.object(self.runtime, 'configure_llama_model_round'):
            first = client.post('/v1/chat/completions', json=body, headers=headers)
            self.assertEqual(200, first.status_code, first.text)
            old_job_id = first.headers['x-gateway-job-id']
            body['messages'] += [tool_message, tool_result]
            # The current pending exchange still resumes implicitly, with its
            # stored call fingerprint checked at the normal admission boundary.
            completed = client.post('/v1/chat/completions', json=body, headers=headers)
            self.assertEqual(200, completed.status_code, completed.text)
            self.assertEqual(old_job_id, completed.headers['x-gateway-job-id'])
            self.assertEqual(2, backend.call_count)

            for index, instructions in enumerate(([], [{'role': 'system', 'content': 'Keep the response concise.'}])):
                duplicate = dict(body, messages=body['messages'] + instructions)
                # Distinct attempt IDs bypass short-lived request coalescing;
                # the original terminal job must still prevent model dispatch.
                repeated = client.post('/v1/chat/completions', json=duplicate,
                                       headers=dict(headers, **{'X-Gateway-Attempt-ID': 'duplicate-' + str(index)}))
                self.assertEqual(409, repeated.status_code, repeated.text)
                self.assertEqual('terminal_job_not_resumable', repeated.json()['error']['code'])
                self.assertEqual(old_job_id, repeated.json()['error']['job_id'])
                self.assertEqual(2, backend.call_count)

            suffixes = (
                [{'role': 'user', 'content': 'Now inspect the release configuration'}],
                [{'role': 'assistant', 'content': 'The workflow inspection is complete.'}],
                [{'role': 'assistant', 'content': 'The workflow inspection is complete.'},
                 {'role': 'user', 'content': 'Inspect a different workflow'}],
            )
            new_job_ids = set()
            for index, suffix in enumerate(suffixes):
                with self.subTest(suffix=suffix):
                    new_turn = dict(body, messages=body['messages'] + suffix)
                    response = client.post('/v1/chat/completions', json=new_turn, headers=headers)
                    self.assertEqual(200, response.status_code, response.text)
                    new_job_id = response.headers['x-gateway-job-id']
                    self.assertNotEqual(old_job_id, new_job_id)
                    self.assertNotIn(new_job_id, new_job_ids)
                    new_job_ids.add(new_job_id)
                    self.assertEqual(3 + index, backend.call_count)
                    sent = backend.call_args.args[0]['messages']
                    self.assertIn(tool_message, sent)
                    self.assertIn(tool_result, sent)
                    self.assertEqual(suffix, sent[-len(suffix):])
            recovered = client.get('/gateway/jobs/' + old_job_id + '/result')
            self.assertEqual(200, recovered.status_code, recovered.text)
            self.assertEqual('Verified workflow reply', recovered.json()['content'])
            self.assertEqual('completed', self.runtime.job_registry[old_job_id]['status'])

    def test_unknown_explicit_saved_job_cannot_silently_create_and_replay(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            for stream in (True, False):
                response = client.post('/v1/chat/completions', headers={'X-Gateway-Job-ID': 'expired-saved-job'},
                                       json={'model': 'local', 'stream': stream,
                                             'messages': [{'role': 'user', 'content': 'Resume'}]})
                self.assertEqual(404, response.status_code, response.text)
                error = response.json()['error']
                self.assertEqual('gateway_job_unavailable', error['code'])
                self.assertEqual('expired-saved-job', error['job_id'])
                self.assertFalse(error['retryable'])
                self.assertEqual('not_dispatched', error['dispatch_state'])
            model.assert_not_called()
            self.assertFalse(self.runtime.job_registry)

    def test_original_backend_template_failure_survives_stream_sqlite_restart_and_reconnect(self):
        from gateway_v13_runtime import backend_template_error
        for stream in (True, False):
            with self.subTest(stream=stream), self.client() as client:
                failure = backend_template_error({'code': 500, 'type': 'server_error',
                                                  'message': 'Fixture-private-template: System message must be at the beginning.'}, status=500)
                body = {'model': 'local', 'stream': stream,
                        'messages': [{'role': 'user', 'content': 'Template failure ' + str(stream)}]}
                with patch.object(self.runtime, '_process_chat_payload_core', side_effect=failure) as model:
                    first = client.post('/v1/chat/completions', json=body)
                    self.assertEqual(200 if stream else 422, first.status_code, first.text)
                    self.assertIn('backend_template_invalid', first.text)
                    self.assertNotIn('Fixture-private-template', first.text)
                    model.assert_called_once()
                    job_id = first.headers['x-gateway-job-id']
                    for restart in (False, True):
                        if restart:
                            self.runtime.job_registry.clear()
                            self.runtime.singleflight_workers.clear()
                            self.runtime.singleflight_request_index.clear()
                            self.runtime.durable_job_initialized = False
                            self.assertTrue(self.runtime.init_durable_job_store())
                        recovered = client.get('/gateway/jobs/' + job_id + '/result')
                        self.assertEqual(200, recovered.status_code, recovered.text)
                        receipt = recovered.json()
                        self.assertEqual('failed', receipt['status'])
                        error = receipt['structured_error']
                        self.assertEqual('backend_template_invalid', error['code'])
                        self.assertEqual('invalid_request', error['failureCode'])
                        self.assertEqual(500, error['backend_status'])
                        self.assertEqual('backend_system_message_position', error['cause_code'])
                        self.assertFalse(error['retryable'])
                        self.assertEqual(job_id, error['job_id'])
                        self.assertEqual(error['message'], receipt['error'])
                        self.assertEqual(error['message'], receipt['job']['message'])
                        self.assertEqual(error, receipt['result']['error'])
                        terminal = client.post('/v1/chat/completions', json=body, headers={'X-Gateway-Job-ID': job_id})
                        self.assertEqual(409, terminal.status_code, terminal.text)
                        self.assertEqual('terminal_job_not_resumable', terminal.json()['error']['code'])
                        self.assertEqual('failed', terminal.json()['error']['terminal_status'])
                        self.assertEqual(job_id, terminal.json()['error']['job_id'])
                        self.assertEqual(error, client.get('/gateway/jobs/' + job_id + '/result').json()['structured_error'])
                    model.assert_called_once()

    def test_isolation_proof_cannot_upgrade_mixed_or_legacy_job_history(self):
        r = self.runtime
        with self.client():
            for job_id, sequence in [('mixed', [False, True]), ('downgraded', [True, False, True])]:
                r.register_gateway_job(job_id, 'local', 'chat', threading.Event(), threading.Event())
                for isolated in sequence:
                    record_execution_isolation(r, job_id, isolated)
                self.assertEqual('unverified', r.job_registry[job_id]['execution_isolation'])
                self.assertIsNone(r.job_registry[job_id]['server_tools_allowed'])
            r.register_gateway_job('legacy-unproven', 'local', 'chat', threading.Event(), threading.Event())
            # Older journals have no birth marker or isolation receipt. A later
            # isolated round cannot certify their earlier execution history.
            r.job_registry['legacy-unproven'].pop('_server_isolation_new', None)
            record_execution_isolation(r, 'legacy-unproven', True)
            self.assertEqual('unverified', r.job_registry['legacy-unproven']['execution_isolation'])
            self.assertIsNone(r.job_registry['legacy-unproven']['server_tools_allowed'])

    def test_forged_request_or_model_metadata_cannot_create_an_isolation_receipt(self):
        forged = {'execution_isolation_schema': 1, 'execution_isolation_scope': 'entire_job',
                  'execution_isolation': 'github_client_owned', 'server_tools_allowed': False}
        body = dict(forged, model='local', stream=False, messages=[{'role': 'user', 'content': 'ordinary request'}])
        from v14.github import install_github_fast_path
        from types import SimpleNamespace
        with self.client() as client:
            def completion(payload, *args):
                return 200, {'choices': [{'message': {'content': 'Ordinary answer'}, 'finish_reason': 'stop'}],
                             'job': forged, **forged}
            # Exercise the actual ownership wrapper around a deterministic model;
            # untrusted response metadata never becomes the server's job envelope.
            wrapped = SimpleNamespace(process_chat_payload=completion, job_registry=self.runtime.job_registry,
                                      job_registry_lock=self.runtime.job_registry_lock,
                                      durable_persist_job=self.runtime.durable_persist_job)
            install_github_fast_path(wrapped)
            with patch.object(self.runtime, 'process_chat_payload', wrapped.process_chat_payload):
                response = client.post('/v1/chat/completions', json=body)
            self.assertEqual(200, response.status_code, response.text)
            saved = client.get('/gateway/jobs/' + response.headers['x-gateway-job-id'] + '/result').json()
            self.assertEqual('github_client_owned', saved['result']['job']['execution_isolation'])
            self.assertEqual('unverified', saved['job']['execution_isolation'])
            self.assertIsNone(saved['job']['server_tools_allowed'])

    def test_isolation_commit_failure_prevents_model_dispatch_and_restores_memory(self):
        r = self.runtime
        with self.client():
            r.register_gateway_job('isolation-write-failed', 'local', 'chat', threading.Event(), threading.Event())
            before = dict(r.job_registry['isolation-write-failed'])
            with patch.object(r, 'durable_persist_job', side_effect=DurableCommitError('disk unavailable')), \
                    patch.object(r, 'post_llama_model_round') as model:
                with self.assertRaises(DurableCommitError):
                    r.process_chat_payload({'_gateway_job_id': 'isolation-write-failed', 'model': 'local',
                                            'messages': [{'role': 'user', 'content': 'check'}],
                                            '_gateway_performance': {'request_role': 'github-primary'}})
                model.assert_not_called()
            self.assertEqual(before, r.job_registry['isolation-write-failed'])

    def test_tool_result_is_authoritative_when_handoff_progress_is_missing(self):
        r = self.runtime
        r.register_gateway_job('lost-progress', 'local', 'chat', threading.Event(), threading.Event())
        call = {'id': 'lost-progress-call', 'type': 'function', 'function': {'name': 'github_read', 'arguments': '{}'}}
        r.finish_gateway_job('lost-progress', 200, {'choices': [{'message': {'tool_calls': [call]}}]})
        self.assertEqual('waiting_for_client_tool', r.job_registry['lost-progress']['status'])
        self.assertEqual('lost-progress', r.remote_handoff_index[call['id']]['job_id'])

    def test_schema_rejections_keep_actionable_code_and_do_not_dispatch(self):
        body = {'model': 'local', 'messages': [{'role': 'user', 'content': 'hello'}],
                'tools': [{'function': {'name': 'github_read'}}, {'function': {'name': 'github_read'}}]}
        with self.client() as client, patch.object(self.runtime, 'chat_completions') as dispatch:
            result = client.post('/v1/chat/completions', json=body)
        self.assertEqual(400, result.status_code)
        self.assertEqual('duplicate_tool_name', result.json()['error']['code'])
        self.assertFalse(result.json()['error']['retryable'])
        dispatch.assert_not_called()

    def test_fresh_database_stream_commits_and_restores_result_and_events(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            response = self.chat(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('SQLite reply', response.text)
            self.assertEqual(response.text.count('data: [DONE]'), 1)
            model.assert_called_once()
        job_id = response.headers['x-gateway-job-id']
        self.runtime.job_registry.clear()
        self.runtime.durable_job_initialized = False
        self.assertTrue(self.runtime.init_durable_job_store())
        restored = self.runtime.job_registry[job_id]
        self.assertEqual(restored['status'], 'completed')
        self.assertEqual(restored['_result']['choices'][0]['message']['content'], 'SQLite reply')
        self.assertEqual(restored['_events'][0]['sequence'], 1)
        self.assertEqual(self.runtime.durable_job_metrics['event_writes'], 1)

    def test_legacy_database_stream_upgrades_without_losing_saved_state(self):
        self.legacy_database(missing_finished=True)
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = self.chat(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('SQLite reply', response.text)
            self.assertEqual(client.get('/gateway/jobs/saved/result').status_code, 200)
        for _ in range(2):
            self.runtime.job_registry.clear()
            self.runtime.durable_job_initialized = False
            self.assertTrue(self.runtime.init_durable_job_store())
            self.assertEqual(self.runtime.job_registry['saved']['_result'], self.old_result)
            self.assertEqual(list(self.runtime.job_registry['saved']['_events']), [self.old_event])
        with closing(sqlite3.connect(self.db)) as db, db:
            public, created, finished = db.execute(
                'SELECT public_json,created_at,finished_at FROM gateway_jobs WHERE job_id="saved"').fetchone()
        self.assertEqual(json.loads(public), self.old_public)
        self.assertEqual(created, self.old_public['created_at'])
        self.assertEqual(finished, self.old_public['finished_at'])

    def test_interrupted_legacy_job_is_restored_without_replay(self):
        self.legacy_database()
        public = dict(self.old_public, status='running', finished_at=None)
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('UPDATE gateway_jobs SET public_json=?,status=?', (json.dumps(public), 'running'))
        with self.client(), patch.object(self.runtime, 'process_chat_payload') as model:
            model.assert_not_called()
            self.assertEqual(self.runtime.job_registry['saved']['status'], 'failed')
        with closing(sqlite3.connect(self.db)) as db, db:
            status, finished = db.execute('SELECT status,finished_at FROM gateway_jobs').fetchone()
        self.assertEqual(status, 'failed')
        self.assertIsNotNone(finished)

    def test_active_cancellation_reports_request_without_claiming_confirmation(self):
        import threading
        self.runtime.register_gateway_job('active', 'local', 'chat', threading.Event(), threading.Event())
        self.store.claim('active', 'local:single-user')
        with self.client() as client:
            response = client.post('/gateway/jobs/active/cancel')
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body['cancellationOutcome'], 'requested')
        self.assertTrue(body['cancellationRequested'])
        self.assertFalse(body['canceled'])

    def test_empty_legacy_database_accepts_nonstream_chat(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('DELETE FROM gateway_events')
            db.execute('DELETE FROM gateway_jobs')
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            response = self.chat(client, stream=False)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()['choices'][0]['message']['content'], 'SQLite reply')

    def test_legacy_event_timestamp_upgrade_preserves_event_payload(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('ALTER TABLE gateway_events DROP COLUMN created_at')
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
            self.assertEqual(self.chat(client).status_code, 200)
        with closing(sqlite3.connect(self.db)) as db, db:
            event, created = db.execute('SELECT event_json,created_at FROM gateway_events WHERE job_id="saved"').fetchone()
        self.assertEqual(json.loads(event), self.old_event)
        self.assertIsNone(created)  # Never invent a historical timestamp.

    def test_unsupported_schema_rolls_back_upgrade_and_stops_startup(self):
        self.legacy_database()
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute('ALTER TABLE gateway_events RENAME COLUMN event_json TO unknown_payload')
        with self.assertRaises(DurableCommitError):
            with self.client():
                self.fail('Startup must reject an unsupported journal')
        self.assertFalse(self.runtime.durable_job_initialized)
        with closing(sqlite3.connect(self.db)) as db, db:
            columns = {row[1] for row in db.execute('PRAGMA table_info(gateway_jobs)')}
            self.assertNotIn('created_at', columns)
            self.assertEqual(json.loads(db.execute('SELECT public_json FROM gateway_jobs').fetchone()[0]), self.old_public)
            self.assertEqual(json.loads(db.execute('SELECT unknown_payload FROM gateway_events').fetchone()[0]), self.old_event)

    def test_registration_write_failure_returns_503_before_stream_or_model(self):
        with self.client() as client:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_jobs BEFORE INSERT ON gateway_jobs BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            with patch.object(self.runtime, 'process_chat_payload') as model:
                for stream in (True, False):
                    response = self.chat(client, stream=stream)
                    self.assertEqual(response.status_code, 503)
                    self.assertEqual(response.json()['error']['code'], 'durable_commit_failed')
                model.assert_not_called()
            self.assertFalse(self.runtime.job_registry)

    def test_final_commit_failure_emits_error_without_successful_answer(self):
        with self.client() as client:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON gateway_jobs WHEN NEW.status='completed' "
                           "BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            with patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion):
                response = self.chat(client)
            self.assertEqual(response.status_code, 200)
            self.assertIn('gateway_stream_error', response.text)
            self.assertNotIn('SQLite reply', response.text)
            self.assertEqual(response.text.count('data: [DONE]'), 1)
            job_id = response.headers['x-gateway-job-id']
            self.assertNotEqual(self.runtime.job_registry[job_id]['status'], 'completed')
            with closing(sqlite3.connect(self.db)) as db, db:
                status, result = db.execute('SELECT status,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
            self.assertNotEqual(status, 'completed')
            self.assertIsNone(result)

    def test_failed_stream_commit_is_shared_with_nonstream_follower(self):
        started, release = threading.Event(), threading.Event()
        resolve = self.runtime.resolve_singleflight_job_id

        def blocked_model(*args):
            started.set()
            if not release.wait(3):
                raise AssertionError('Follower did not attach')
            return self.completion(*args)

        async def scenario():
            attached = asyncio.Event()
            def observe_follower(*args, **kwargs):
                result = resolve(*args, **kwargs)
                if result[1]:
                    attached.set()
                return result
            transport = httpx.ASGITransport(app=self.gateway.app, client=('127.0.0.1', 2222))
            async with httpx.AsyncClient(transport=transport, base_url='http://127.0.0.1:8090') as client:
                payload = {'model': 'local', 'messages': [{'role': 'user', 'content': 'Hello'}]}
                with patch.object(self.runtime, 'resolve_singleflight_job_id', side_effect=observe_follower):
                    leader = asyncio.create_task(client.post('/v1/chat/completions', json=dict(payload, stream=True)))
                    try:
                        self.assertTrue(await asyncio.to_thread(started.wait, 2))
                        follower = asyncio.create_task(client.post('/v1/chat/completions', json=dict(payload, stream=False)))
                        await asyncio.wait_for(attached.wait(), 2)
                    finally:
                        release.set()
                    return await asyncio.gather(leader, follower)

        with self.client(), patch.object(self.runtime, 'process_chat_payload', side_effect=blocked_model) as model:
            with closing(sqlite3.connect(self.db)) as db, db:
                db.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON gateway_jobs WHEN NEW.status='completed' "
                           "BEGIN SELECT RAISE(ABORT,'disk failure'); END")
            leader, follower = asyncio.run(scenario())
            model.assert_called_once()
            self.assertIn('gateway_stream_error', leader.text)
            self.assertNotIn('SQLite reply', leader.text)
            self.assertEqual(follower.status_code, 503)
            self.assertEqual(follower.json()['error']['code'], 'durable_commit_failed')


if __name__ == '__main__':
    unittest.main()
