"""GitHub ownership, immutable caching and truthful metrics behavior."""
import concurrent.futures
import json
import sys
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.github import ImmutableGitHubReads, install_github_fast_path, native_github_request
from v14.results import bound_result, ToolViewCapacity


class GitHubGatewayTests(unittest.TestCase):
    def test_client_catalog_routes_one_round_without_changing_prompt_or_budgets(self):
        calls = []
        runtime = SimpleNamespace(process_chat_payload=lambda payload, *args: calls.append(payload) or (200, {
            'usage': {'prompt_tokens': 10, 'completion_tokens': 2},
            'choices': [{'message': {'tool_calls': [{'function': {'name': 'github_ci__github_api'}}]}}]}))
        install_github_fast_path(runtime)
        original = {'tools': [{'function': {'name': 'github_ci__github_api'}}],
                    'messages': [{'role': 'user', 'content': 'Continue'}], 'max_tokens': 400,
                    '_gateway_performance': {'reasoning': 'high'}}
        runtime.process_chat_payload(original)
        self.assertTrue(calls[0]['_gateway_performance']['github_client_owned'])
        self.assertEqual('high', calls[0]['_gateway_performance']['reasoning'])
        self.assertNotIn('github_client_owned', original['_gateway_performance'])
        self.assertEqual(original['messages'], calls[0]['messages'])
        self.assertEqual(400, calls[0]['max_tokens'])
        stats = runtime.gateway_github_performance.snapshot()
        self.assertEqual(0, stats['counts']['active'])
        self.assertEqual(1, stats['counts']['catalogDiscoverySkipped'])
        self.assertEqual(1, stats['returnedToolCalls']['github_ci'])
        self.assertEqual(1, stats['successRate'])
        self.assertTrue(stats['usageComplete'])

    def test_issue_operation_returns_to_client_without_gateway_execution(self):
        payload = {'tools': [{'function': {'name': 'github_issue__github_api'}}]}
        seen = []
        runtime = SimpleNamespace(process_chat_payload=lambda payload, *args: (seen.append(payload) or 200, {}))
        install_github_fast_path(runtime)
        runtime.process_chat_payload(payload)
        self.assertTrue(seen[0]['_gateway_performance']['github_client_owned'])
        self.assertEqual(payload['tools'], seen[0]['tools'])

    def test_keywords_do_not_enable_native_routing_and_missing_usage_is_unknown(self):
        self.assertFalse(native_github_request({'messages': [{'content': 'github_write__github_api'}]}))
        self.assertFalse(native_github_request({'tools': [{'function': {'name': 'github_admin_unknown'}}]}))
        runtime = SimpleNamespace(process_chat_payload=lambda *args: (502, {}))
        install_github_fast_path(runtime)
        runtime.process_chat_payload({'tools': [{'function': {'name': 'github_read'}}]})
        self.assertFalse(runtime.gateway_github_performance.snapshot()['usageComplete'])
        self.assertEqual(0, runtime.gateway_github_performance.snapshot()['successRate'])

    def test_concurrent_immutable_reads_share_one_producer_and_cache_detached_results(self):
        reads = ImmutableGitHubReads()
        entered, release = threading.Event(), threading.Event()
        calls = []
        def fetch():
            calls.append(1)
            entered.set()
            release.wait(2)
            return {'content': ['original']}
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(reads.execute, 'key', fetch)
            self.assertTrue(entered.wait(1))
            second = pool.submit(reads.execute, 'key', fetch)
            release.set()
            self.assertEqual(first.result(), second.result())
        value = reads.execute('key', fetch)
        value['content'].append('mutated')
        self.assertEqual({'content': ['original']}, reads.execute('key', fetch))
        self.assertEqual(1, len(calls))
        self.assertEqual(0, reads.snapshot()['activeReads'])

    def test_cache_keys_fence_principal_connection_revision_and_arguments(self):
        key = ImmutableGitHubReads.key
        base = key('phone', {'revision': 'one'}, {'ref': 'a' * 40})
        self.assertNotEqual(base, key('other', {'revision': 'one'}, {'ref': 'a' * 40}))
        self.assertNotEqual(base, key('phone', {'revision': 'two'}, {'ref': 'a' * 40}))
        self.assertNotEqual(base, key('phone', {'revision': 'one'}, {'ref': 'b' * 40}))

    def test_errors_are_not_cached_and_cache_memory_is_bounded(self):
        reads = ImmutableGitHubReads(max_bytes=100, max_entries=2)
        calls = []
        for _ in range(2):
            reads.execute('error', lambda: calls.append(1) or {'isError': True})
        self.assertEqual(2, len(calls))
        for index in range(10):
            reads.execute(str(index), lambda: {'text': 'x' * 30})
        self.assertLessEqual(reads.snapshot()['entries'], 2)
        self.assertLessEqual(reads.snapshot()['bytes'], 100)
        canceled = threading.Event(); canceled.set()
        with self.assertRaises(InterruptedError):
            reads.execute('9', lambda: self.fail('must not dispatch'), canceled)

    def test_transport_failure_cleans_flights_without_retry(self):
        reads = ImmutableGitHubReads()
        calls = []
        def fail():
            calls.append(1)
            raise OSError('lost connection')
        with self.assertRaises(OSError):
            reads.execute('key', fail)
        self.assertEqual(1, len(calls))
        self.assertEqual(0, reads.snapshot()['activeReads'])
        self.assertEqual(0, reads.snapshot()['entries'])

    def test_structured_search_continuation_survives_truncation_exactly(self):
        continuation = {'tool': 'github_search__github_api', 'arguments': {
            'commit_sha': 'a' * 40, 'index_cursor': 500, 'search_id': 'search',
            'query': 'class', 'path_prefix': 'src/', 'file_extension': 'kt'}}
        receipt = json.loads(bound_result({'body': 'large ' * 1000, 'continuation_call': continuation}, 1400))
        self.assertIn({'key': 'continuation_call', 'value': continuation}, receipt['continuations'])
        self.assertFalse(receipt['complete'])
        with self.assertRaises(ToolViewCapacity):
            bound_result({'body': 'large' * 1000, 'continuation_call': continuation}, 100)
