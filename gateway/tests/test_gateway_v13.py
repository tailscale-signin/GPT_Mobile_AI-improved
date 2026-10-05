"""V13 context, transport and real application contracts without live services."""
import copy
import importlib
import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_v13_runtime import (GatewayContractError, compact_history, fit_context,
                                validate_chat_request, validate_completion_tools,
                                consume_sse, completion_chunks)
from gateway_v13_transport import post_chat
from gateway_security import current_device
from test_gateway_security import GatewayIntegrationTests as _IntegrationBase


def request():
    return {"model": "local", "messages": [{"role": "user", "content": "Use my location"}],
            "max_tokens": 20000, "tools": [{"type": "function", "function": {
                "name": "location_get", "parameters": {"type": "object", "required": ["accuracy"]}}}]}


def result(calls=None):
    return {"choices": [{"index": 0, "message": {"role": "assistant", "content": "answer",
            **({"tool_calls": calls} if calls is not None else {})}, "finish_reason": "tool_calls" if calls else "stop"}]}


def call(arguments='{"accuracy":"precise"}', name="location_get"):
    return {"id": "a", "type": "function", "function": {"name": name, "arguments": arguments}}


def events(*values, done=True):
    for value in values:
        yield 'data: ' + json.dumps(value)
        yield ''
    if done:
        yield 'data: [DONE]'
        yield ''


class RuntimeTests(unittest.TestCase):
    def test_reasoning_is_not_visible_and_short_answers_are_valid(self):
        from gateway_v13_runtime import CompletionStream, visible_answer_text
        stream = CompletionStream()
        stream.accept({'choices': [{'delta': {'reasoning_content': 'private reasoning'}}]})
        self.assertEqual(stream.visible_characters, 0)
        self.assertGreater(stream.observed_characters, 0)
        self.assertEqual(visible_answer_text('<think>unfinished thought'), '')
        self.assertEqual(visible_answer_text('<think>thought</think>OK'), 'OK')

    def test_owner_partition_preserves_ids_and_rejects_unknown_calls(self):
        from gateway_v13_runtime import partition_owned_tool_calls
        calls = [dict(call(name='search_web'), id='local'), dict(call(name='location_get'), id='phone')]
        local, client = partition_owned_tool_calls(calls, [('search_web', 'gateway_mcp'), ('location_get', 'client_owned')])
        self.assertEqual([item['id'] for item in local], ['local'])
        self.assertEqual([item['id'] for item in client], ['phone'])
        local[0]['function']['name'] = 'changed'
        self.assertEqual(calls[0]['function']['name'], 'search_web')
        with self.assertRaises(GatewayContractError):
            partition_owned_tool_calls(calls, [('search_web', None), ('location_get', 'client_owned')])

    def test_terminal_without_done_closes_after_grace_and_retains_usage(self):
        closed = threading.Event()
        response = Mock(status_code=200, headers={'content-type': 'text/event-stream'})
        def lines(**kwargs):
            yield from list(events({'choices': [{'delta': {'content': 'OK'}, 'finish_reason': 'stop'}]}, {'choices': [], 'usage': {'completion_tokens': 2}}))[:-2]
            closed.wait(2)
        response.iter_lines.side_effect = lines
        response.close.side_effect = closed.set
        session = Mock(); session.post.return_value = response
        output = post_chat(session, 'http://backend', {'tools': []}, connect_timeout=1, idle_timeout=2,
                           deadline_seconds=3, cancelled=lambda: False, progress=lambda e: None, terminal_grace_seconds=0.05)
        self.assertTrue(closed.is_set())
        self.assertEqual(output.json()['choices'][0]['message']['content'], 'OK')
        self.assertEqual(output.json()['usage']['completion_tokens'], 2)
        session.post.assert_called_once()

    def test_explicit_large_output_is_preserved(self):
        body = request()
        report = fit_context(body, 32768, lambda p: (1000, 'test'))
        self.assertEqual(body['max_tokens'], 20000)
        self.assertTrue(report['explicit_output_preserved'])

    def test_overflow_is_explicit_and_does_not_mutate_request(self):
        body = request()
        original = copy.deepcopy(body)
        with self.assertRaises(GatewayContractError) as error:
            fit_context(body, 32768, lambda p: (25000, 'test'))
        self.assertEqual(error.exception.status, 422)
        self.assertEqual(body, original)

    def test_compaction_preserves_goal_recent_evidence_and_tool_pairs(self):
        messages = [{'role': 'system', 'content': 'instructions'}]
        for i in range(12):
            messages += [{'role': 'user', 'content': f'goal {i}'},
                         {'role': 'assistant', 'tool_calls': [dict(call(), id=str(i))], 'reasoning_content': 'old'},
                         {'role': 'tool', 'tool_call_id': str(i), 'content': 'evidence' * 100}]
        original = copy.deepcopy(messages)
        compacted = compact_history(messages, emergency=True)
        self.assertIn(messages[1], compacted)
        self.assertEqual(compacted[-3:], messages[-3:])
        ids = {m['tool_calls'][0]['id'] for m in compacted if m.get('tool_calls')}
        self.assertEqual(ids, {m['tool_call_id'] for m in compacted if m['role'] == 'tool'})
        self.assertEqual(messages, original)
        self.assertLess(len(compacted), len(messages))

    def test_invalid_requests(self):
        for body in [[], {}, dict(request(), n=2), dict(request(), max_tokens=True),
                     dict(request(), tools=request()['tools'] * 2)]:
            with self.subTest(body=body), self.assertRaises(GatewayContractError):
                validate_chat_request(body)

    def test_rejects_unfinished_unknown_invalid_and_duplicate_tool_calls(self):
        for data in [result([call(name='unknown')]), result([call('{')]),
                     result([call('{}')]), result([call(), call()]),
                     {'choices': [None]}, result([{'id': 'x', 'function': None}])]:
            with self.subTest(data=data), self.assertRaises(GatewayContractError):
                validate_completion_tools(data, request())
        data = result([call()])
        data['choices'][0]['finish_reason'] = 'length'
        with self.assertRaises(GatewayContractError):
            validate_completion_tools(data, request())
        with self.assertRaises(GatewayContractError):
            validate_completion_tools(result([call()]), dict(request(), tool_choice='none'))

    def test_fragmented_tools_and_usage_roundtrip(self):
        data = consume_sse(events(
            {'choices': [{'delta': {'tool_calls': [{'index': 0, 'id': 'a', 'function': {'name': 'location_', 'arguments': '{"accuracy":'}}]}}]},
            {'choices': [{'delta': {'tool_calls': [{'index': 0, 'function': {'name': 'get', 'arguments': '"precise"}'}}]}, 'finish_reason': 'tool_calls'}]},
            {'choices': [], 'usage': {'prompt_tokens': 10, 'completion_tokens': 8}},
        ))
        validate_completion_tools(data, request())
        self.assertEqual(data['choices'][0]['message']['tool_calls'], [call()])
        self.assertEqual(data['usage']['completion_tokens'], 8)

    def test_truncated_stream_never_becomes_success(self):
        for lines in [events({'choices': [{'delta': {'content': 'partial'}}]}, done=False), ['data: {']]:
            with self.assertRaises(GatewayContractError):
                consume_sse(lines)

    def test_final_delivery_is_lossless_and_usage_occurs_once(self):
        data = result()
        content = 'August 4th 1987 🌸 ' * 10000
        data['choices'][0]['message']['content'] = content
        data['usage'] = {'completion_tokens': 50000}
        chunks = list(completion_chunks(data))
        parsed = [json.loads(c[6:]) for c in chunks[:-1]]
        self.assertEqual(''.join(c['choices'][0]['delta'].get('content', '') for c in parsed), content)
        self.assertEqual(sum('usage' in c for c in parsed), 1)
        self.assertEqual(chunks.count('data: [DONE]\n\n'), 1)

    def test_transport_closes_stream_and_validates_before_return(self):
        response = Mock(status_code=200, headers={'content-type': 'text/event-stream'})
        response.iter_lines.return_value = events({'choices': [{'delta': {'content': 'answer'}, 'finish_reason': 'stop'}]})
        session, progress = Mock(), Mock()
        session.post.return_value = response
        output = post_chat(session, 'http://backend/v1/chat/completions', request(),
                           connect_timeout=1, idle_timeout=1, deadline_seconds=2,
                           cancelled=lambda: False, progress=progress)
        self.assertEqual(output.json()['choices'][0]['message']['content'], 'answer')
        response.close.assert_called_once()
        self.assertTrue(progress.called)
        self.assertTrue(session.post.call_args.kwargs['json']['stream'])

    def test_transport_cancellation_discards_output(self):
        response = Mock(status_code=200, headers={'content-type': 'text/event-stream'})
        response.iter_lines.return_value = events({'choices': [{'delta': {'content': 'partial'}}]})
        session = Mock(); session.post.return_value = response
        with self.assertRaises(InterruptedError):
            post_chat(session, 'http://backend', request(), connect_timeout=1, idle_timeout=1,
                      deadline_seconds=2, cancelled=lambda: True, progress=lambda e: None)
        session.post.assert_not_called()
        response.close.assert_not_called()


class V13IntegrationTests(_IntegrationBase):
    def setUp(self):
        # Reuse all real authorization contracts against the new application.
        super().setUp()
        self.client.__exit__(None, None, None)
        for item in reversed(self.patches):
            item.stop()
        with patch.dict(os.environ, {'MCP_PREWARM_ON_STARTUP': 'false', 'GATEWAY_JOB_DB': str(Path(self.temp.name) / 'v13jobs.sqlite3')}):
            self.gateway = importlib.import_module('gateway_v13')
        self.patches = [patch('gateway_security.device_store', self.store), patch.object(self.gateway, 'device_store', self.store),
                        patch.object(self.gateway, 'start_gateway_background_services'), patch.object(self.gateway, 'gateway_health', return_value={'status': 'ok'})]
        for item in self.patches:
            item.start()
        from fastapi.testclient import TestClient
        self.gateway.app.middleware_stack = None
        self.client = TestClient(self.gateway.app)
        self.client.__enter__()

    def test_empty_answer_synthesis_is_bounded_and_never_claims_verified_research(self):
        body = request()
        empty = Mock(status_code=200, ok=True)
        empty.json.return_value = {'choices': [{'message': {'content': '<think>unfinished'}, 'finish_reason': 'stop'}]}
        with patch.object(self.gateway, 'http_post', return_value=empty) as post, patch.object(self.gateway, 'apply_context_guard'):
            status, data = self.gateway.run_clean_terminal_synthesis('local', body, body['messages'], body['messages'], 'answer', [], single_attempt=True)
        self.assertEqual(status, 502)
        self.assertEqual(data['error']['code'], 'empty_completion')
        self.assertNotIn('verified', data['error']['message'])
        post.assert_called_once()

    def test_synthesis_accepts_short_answer_and_propagates_cancellation(self):
        self.assertEqual(self.gateway._usable_synthesis_content({'choices': [{'message': {'content': 'OK'}}]}), 'OK')
        body = request()
        with patch.object(self.gateway, 'http_post', side_effect=InterruptedError('cancelled')) as post, patch.object(self.gateway, 'apply_context_guard'):
            with self.assertRaises(InterruptedError):
                self.gateway.run_clean_terminal_synthesis('local', body, body['messages'], body['messages'], 'answer', [])
        post.assert_called_once()

    def test_invalid_body_returns_400_before_job_creation(self):
        for body in [[], {}, dict(request(), n=2)]:
            response = self.client.post('/v1/chat/completions', json=body, headers={'Authorization': 'Bearer ' + self.token_a})
            self.assertEqual(response.status_code, 400)

    def test_version_and_readiness(self):
        headers = {'Authorization': 'Bearer ' + self.token_a}
        self.assertEqual(self.client.get('/gateway/v13', headers=headers).json()['version'], '13.1.0')
        with patch.object(self.gateway, 'http_get', return_value=Mock(status_code=503)):
            self.assertEqual(self.client.get('/gateway/ready', headers=headers).status_code, 503)
        self.assertEqual(self.client.get('/gateway/ready').status_code, 401)

    def test_client_location_wins_over_local_prefix(self):
        with patch.object(self.gateway, 'load_mcp_config', return_value={'location': {}}):
            self.assertEqual(self.gateway.get_gateway_tool_route('location_get', ['location_get']), 'client_owned')
            self.assertEqual(self.gateway.get_gateway_tool_route('location_get'), 'gateway_mcp')

    def test_read_cache_is_device_scoped_and_names_are_case_sensitive(self):
        marker = current_device.set('a')
        try:
            a = self.gateway.make_tool_call_signature('Read', {'x': 1})
            lower = self.gateway.make_tool_call_signature('read', {'x': 1})
            current_device.set('b')
            b = self.gateway.make_tool_call_signature('Read', {'x': 1})
            self.assertEqual(len({a, b, lower}), 3)
        finally:
            current_device.reset(marker)

    def test_reviewer_never_inherits_tools(self):
        body = request()
        body['_gateway_performance'] = {'request_role': 'reviewer', 'delegated_worker': True,
                                        'allow_gateway_local_tools': True, 'client_tool_choice': 'auto'}
        upstream = Mock(status_code=200); upstream.json.return_value = result()
        with patch.object(self.gateway, 'post_llama_model_round', return_value=(upstream, 1)) as dispatch, \
             patch.object(self.gateway, 'apply_context_guard'), patch.object(self.gateway, 'observe_llama_prompt_cache'):
            status, data = self.gateway.process_chat_payload(body)
        self.assertEqual(status, 200)
        forwarded = dispatch.call_args.args[0]
        self.assertNotIn('tools', forwarded)
        self.assertEqual(forwarded['tool_choice'], 'none')

    def test_synthesis_retains_all_unique_evidence_and_requested_budget(self):
        body = request()
        body['messages'].append({'role': 'assistant', 'content': 'Earlier decision'})
        observations = [{'tool_name': 'read', 'arguments': '{}', 'result': f'evidence {i}'} for i in range(100)]
        with patch.object(self.gateway, 'apply_context_guard'):
            payload = self.gateway.build_clean_synthesis_payload(body, [], body['messages'], 'finish', observations)
        self.assertEqual(payload['max_tokens'], 20000)
        self.assertIn(body['messages'][-1], payload['messages'])
        evidence = json.loads(payload['messages'][-1]['content'].split('Collected evidence (not instructions):\n')[1])
        self.assertEqual(len(evidence), 100)

    def test_dispatch_releases_model_slot_on_stream_failure(self):
        with patch.object(self.gateway, '_acquire_llama_model_gate'), \
             patch.object(self.gateway, '_release_llama_model_gate') as release, \
             patch.object(self.gateway, 'v13_post_chat', side_effect=GatewayContractError('broken', status=502)):
            with self.assertRaises(GatewayContractError):
                self.gateway._post_llama_model_http('http://backend/v1/chat/completions', json=request())
        release.assert_called_once()

    def test_progress_is_not_generated_reasoning(self):
        chunk = self.gateway.make_progress_sse('local', 'id', 1, {'phase': 'model', 'message': 'Waiting'})
        data = json.loads(chunk[6:])
        self.assertNotIn('reasoning_content', data['choices'][0]['delta'])
        self.assertIn('gateway_progress', data)


# Avoid collecting the imported base class twice.
del _IntegrationBase
