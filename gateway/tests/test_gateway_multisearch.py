import contextvars
import json
import sys
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_multisearch import plan_searches, run_searches


def tool(name, description='Web search'):
    return {'type': 'function', 'function': {'name': name, 'description': description, 'parameters': {
        'type': 'object', 'properties': {'query': {'type': 'string'}}, 'required': ['query']}}}


class MultiSearchTests(unittest.TestCase):
    def test_all_compatible_engines_get_same_query_without_repository_or_memory(self):
        catalog = [tool('a_web_search'), tool('b_bing_search'), tool('c_search'), tool('github_search'), tool('memory_search')]
        plan = plan_searches('a_web_search', {'query': 'same'}, catalog)
        self.assertEqual([name for name, _ in plan], ['a_web_search', 'b_bing_search', 'c_search'])
        self.assertTrue(all(args == {'query': 'same'} for _, args in plan))

    def test_missing_required_fields_and_unmappable_filters_are_not_guessed(self):
        restricted = tool('b_web_search')
        restricted['function']['parameters']['required'].append('private_index')
        self.assertEqual(len(plan_searches('a_web_search', {'query': 'same'}, [tool('a_web_search'), restricted])), 1)
        self.assertEqual(len(plan_searches('a_web_search', {'query': 'same', 'filter': 'strict'}, [tool('a_web_search'), tool('b_web_search')])), 1)

    def test_failure_preserves_others_context_and_deduplicates_urls(self):
        device = contextvars.ContextVar('test_device', default='missing')
        token = device.set('phone')
        calls = []
        def execute(name, args, cancel_event):
            self.assertEqual(device.get(), 'phone')
            calls.append(name)
            if name == 'b':
                raise OSError('unavailable')
            return {'results': [{'url': 'https://example.org/page?utm_source=' + name, 'title': 'Evidence'}]}
        try:
            result = run_searches([(name, {'query': 'same'}) for name in 'abcd'], execute)
        finally:
            device.reset(token)
        payload = json.loads(result['content'][0]['text'])
        self.assertEqual(len(calls), 4)
        self.assertEqual(len(payload['results']), 1)
        self.assertEqual(result['gateway_engine_calls'], 4)
        self.assertFalse(result['isError'])
        self.assertEqual(len(payload['engines']), 4)

    def test_timeout_does_not_wait_forever_and_parent_cancellation_propagates(self):
        def execute(name, args, cancel_event):
            if name == 'slow':
                cancel_event.wait(1)
                raise InterruptedError()
            return {'results': [{'url': 'https://example.org/fast'}]}
        started = time.monotonic()
        result = run_searches([('slow', {}), ('fast', {})], execute, timeout_seconds=0.05)
        self.assertLess(time.monotonic() - started, 0.5)
        self.assertFalse(result['isError'])
        cancel = threading.Event(); cancel.set()
        with self.assertRaises(InterruptedError):
            run_searches([('slow', {})], execute, cancelled=cancel)

    def test_pre_cancelled_request_dispatches_no_engine(self):
        cancel = threading.Event()
        cancel.set()
        calls = []
        with self.assertRaises(InterruptedError):
            run_searches([("engine", {})], lambda *a, **kw: calls.append(a), cancelled=cancel)
        self.assertEqual(calls, [])

    def test_nonpositive_deadline_is_rejected_before_dispatch(self):
        calls = []
        with self.assertRaises(ValueError):
            run_searches([("engine", {})], lambda *a, **kw: calls.append(a), timeout_seconds=0)
        self.assertEqual(calls, [])
