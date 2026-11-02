import datetime as dt
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.usage import UsageStore, install_usage, dashboard_snapshot


class UsageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'usage.sqlite3'
        self.now = dt.datetime(2026, 11, 1, 12).timestamp()
        self.store = UsageStore(self.path, lambda: self.now)

    def test_provider_totals_persist_across_restart_and_daily_buckets(self):
        self.store.record({'usage': {'completion_tokens': 150, 'prompt_tokens': 90},
                           'timings': {'predicted_n': 150, 'predicted_ms': 3000}})
        self.now += 24 * 3600
        self.store.record({'usage': {'completion_tokens': 75}, 'timings': {'predicted_n': 75, 'predicted_ms': 3000}})
        result = UsageStore(self.path, lambda: self.now).snapshot()
        self.assertEqual(225, result['outputTokens'])
        self.assertEqual(37.5, result['tokensPerSecond'])
        self.assertEqual([150, 75], [day['outputTokens'] for day in result['days'][-2:]])
        self.assertEqual(2, result['measuredRounds'])

    def test_unknown_usage_is_not_fabricated_as_zero_or_estimated(self):
        self.store.record({'choices': [{'message': {'content': 'a very long answer'}}]})
        result = self.store.snapshot()
        self.assertIsNone(result['outputTokens'])
        self.assertIsNone(result['tokensPerSecond'])
        self.assertIsNone(result['days'][-1]['outputTokens'])
        self.assertEqual(1, result['unmeasuredRounds'])
        self.store.record({'usage': {'completion_tokens': 0}})
        self.assertEqual(0, self.store.snapshot()['outputTokens'])

    def test_invalid_counts_and_timing_are_not_used(self):
        for value in (True, -1, 2**63, '10', 2.5):
            self.store.record({'usage': {'completion_tokens': value}, 'timings': {'predicted_ms': float('nan')}})
        self.assertIsNone(self.store.snapshot()['outputTokens'])
        self.store.record({'timings': {'predicted_n': 20, 'predicted_ms': 1000}})
        self.assertEqual(20, self.store.snapshot()['outputTokens'])

    def test_transport_wrapper_records_one_combined_repair_result_without_changing_response(self):
        response = SimpleNamespace(status_code=200, json=lambda: {'usage': {'completion_tokens': 42}})
        runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=self.path.with_name('jobs.sqlite3'),
                                  post_llama_model_round=Mock(return_value=(response, 10)), logger=Mock())
        install_usage(runtime)
        self.assertEqual((response, 10), runtime.post_llama_model_round({}))
        self.assertEqual(42, runtime.gateway_usage.snapshot()['outputTokens'])
        runtime.gateway_usage.record = Mock(side_effect=sqlite3.OperationalError('disk failure'))
        self.assertEqual((response, 10), runtime.post_llama_model_round({}))
        runtime.logger.warning.assert_called_once()

    def test_tray_snapshot_keeps_measured_usage_and_activity_together(self):
        from v14.activity import ActivityTracker, desktop_snapshot
        from desktop.dashboard_model import read_snapshot, rows
        from desktop.configuration import atomic_json
        self.store.record({'usage': {'completion_tokens': 250}})
        tracker = ActivityTracker(now=lambda: self.now)
        activity_id = tracker.begin('search', 'web__search')
        tracker.finish(activity_id, 'completed')
        runtime = SimpleNamespace(gateway_usage=self.store, GATEWAY_DURABLE_JOBS=False)
        snapshot = desktop_snapshot(runtime, SimpleNamespace(snapshot=lambda: {'activeWorkers': 0}), tracker)
        root = Path(self.temp.name)
        atomic_json(root / 'dashboard.json', snapshot)
        atomic_json(root / 'status.json', {'state': 'running'})
        displayed = read_snapshot(root, now=self.now)
        self.assertTrue(displayed['fresh'])
        self.assertEqual(250, displayed['usage']['outputTokens'])
        self.assertEqual('web__search', rows(displayed, 'Search jobs')[0]['title'])
        self.assertEqual(7, len(displayed['usage']['days']))

    def test_recent_activity_does_not_expose_prompt_or_result_content(self):
        runtime = SimpleNamespace(gateway_usage=self.store, job_registry_lock=threading.RLock(), job_registry={
            'job': {'job_id': 'job', 'mode': 'repo', 'model': 'local', 'status': 'running', 'updated_at': self.now,
                    'task_text': 'private prompt', '_result': {'secret': 'private result'}}})
        snapshot = dashboard_snapshot(runtime)
        self.assertEqual('repo', snapshot['recentJobs'][0]['kind'])
        self.assertNotIn('private', json.dumps(snapshot))


if __name__ == '__main__':
    unittest.main()
