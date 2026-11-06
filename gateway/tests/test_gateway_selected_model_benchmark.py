import hashlib
import json
import unittest
from benchmark_selected_model import measure


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
