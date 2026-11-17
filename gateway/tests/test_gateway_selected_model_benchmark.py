import hashlib
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmark_selected_model import BenchmarkInvocationError, measure


class Stream:
    status_code = 200
    def __init__(self, model='selected', terminal=True):
        self.model, self.terminal = model, terminal
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def iter_lines(self, **kwargs):
        for text in ('first\n', '雪', ' tail'):
            yield ('data: ' + json.dumps({'model': self.model, 'choices': [{'delta': {'content': text}}]})).encode()
        if self.terminal:
            yield b'data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":3}}'
            yield b'data: [DONE]'


class SelectedModelBenchmarkTest(unittest.TestCase):
    def test_stream_measurements_preserve_order_without_retaining_text_or_credentials(self):
        calls = []
        def post(url, **kwargs):
            calls.append((url, kwargs))
            return Stream()
        report = measure(post, 'http://localhost:8080', 'selected', [{'role': 'user', 'content': 'private'}], headers={'Authorization': 'secret'})
        self.assertEqual(hashlib.sha256('first\n雪 tail'.encode()).hexdigest(), report['outputSha256'])
        self.assertEqual(3, report['contentEvents'])
        self.assertEqual('selected', calls[0][1]['json']['model'])
        self.assertIsNone(report['queueMs'])
        self.assertNotIn('private', json.dumps(report))
        self.assertNotIn('secret', json.dumps(report))

    def test_contradictory_model_and_missing_terminal_never_produce_measurements(self):
        for stream in (Stream(model='reviewer-B'), Stream(terminal=False)):
            with self.assertRaises(ValueError):
                measure(lambda *a, **kw: stream, 'http://localhost:8080', 'selected', [])

    def test_scored_gateway_fixture_explicitly_disables_client_and_gateway_tools(self):
        calls = []

        def post(url, **kwargs):
            calls.append((url, kwargs))
            return Stream()

        options = {'store_conversation': False, 'budget': {'totalDeadlineMs': 120_000}}
        report = measure(post, 'http://localhost:8090', 'selected', [],
                         gateway_options=options, validate_output=lambda text: text == 'first\n雪 tail')
        self.assertIs(True, report['passed'])
        self.assertEqual(1, len(calls))
        self.assertEqual('http://localhost:8090/v1/chat/completions', calls[0][0])
        sent = calls[0][1]['json']
        self.assertEqual('none', sent['tool_choice'])
        self.assertEqual([], sent['tools'])
        self.assertEqual(options, sent['gateway'])
        self.assertEqual('selected', sent['model'])

    def test_fragmented_utf8_and_crlf_preserve_the_terminal_receipt(self):
        wire = ('data: {"choices":[{"delta":{"content":"雪"},"finish_reason":"stop"}]}\r\n\r\n'
                'data: [DONE]\r\n\r\n').encode()
        class Fragmented(Stream):
            def iter_content(self, **_kwargs):
                yield from (wire[i:i + 1] for i in range(len(wire)))
        report = measure(lambda *a, **kw: Fragmented(), 'http://localhost', 'selected', [],
                         validate_output=lambda text: text == '雪')
        self.assertTrue(report['passed'])

    def test_typed_http_rejection_keeps_status_and_allowlisted_code(self):
        class Rejected(Stream):
            status_code = 400
            def iter_content(self, **_kwargs):
                yield b'{"error":{"code":"EXECUTION_OUTPUT_LIMIT","message":"private model data"}}'
        with self.assertRaises(BenchmarkInvocationError) as observed:
            measure(lambda *a, **kw: Rejected(), 'http://localhost', 'selected', [])
        self.assertEqual('BENCHMARK_HTTP_REJECTED', observed.exception.code)
        self.assertEqual(400, observed.exception.status)
        self.assertEqual('EXECUTION_OUTPUT_LIMIT', observed.exception.admission_code)
        self.assertNotIn('private', str(observed.exception))

    def test_stream_context_failure_retains_job_and_requires_confirmed_settlement(self):
        class Rejected(Stream):
            headers = {'X-Gateway-Job-ID': 'job-123'}
            def iter_lines(self, **_kwargs):
                yield b'data: {"error":{"code":"context_budget_exceeded","message":"private prompt"}}'
        settled = []
        def settle(job_id):
            settled.append(job_id)
            return {'outcome': 'terminal'}
        with self.assertRaises(BenchmarkInvocationError) as observed:
            measure(lambda *a, **kw: Rejected(), 'http://localhost', 'selected', [], cancel_request=settle)
        self.assertEqual(['job-123'], settled)
        self.assertEqual('terminal', observed.exception.settlement)
        self.assertEqual('context_budget_exceeded', observed.exception.admission_code)
        self.assertEqual('job-123', observed.exception.job_id)
