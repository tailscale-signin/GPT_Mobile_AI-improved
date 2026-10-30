import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci_observability as observe
import ci_artifact_retention as retention
import build_metrics


class CiObservabilityTest(unittest.TestCase):
    def test_cache_is_attempt_scoped_and_never_reuses_running_status(self):
        cache, fetch = {}, Mock(return_value={'value': 1})
        run = {'id': 1, 'run_attempt': 1, 'head_sha': 'a' * 40, 'status': 'completed'}
        self.assertFalse(observe.cached_completed_run(cache, run, fetch, 10)[1])
        self.assertTrue(observe.cached_completed_run(cache, run, fetch, 100)[1])
        self.assertFalse(observe.cached_completed_run(cache, run, fetch, 7211)[1])
        self.assertFalse(observe.cached_completed_run(cache, dict(run, status='in_progress'), fetch, 7212)[1])
        self.assertFalse(observe.cached_completed_run(cache, dict(run, run_attempt=2), fetch, 7213)[1])
        self.assertEqual(4, fetch.call_count)

    def test_pagination_marks_partial_inventory(self):
        api = observe.GitHub('owner/repo', 'token')
        api.request = Mock(return_value={'items': list(range(100))})
        rows, complete = api.pages('/items', 'items', limit=2)
        self.assertEqual(200, len(rows))
        self.assertFalse(complete)
        self.assertIn('page=2', api.request.call_args.args[0])

    def test_metrics_do_not_fabricate_empty_success_rate(self):
        empty = observe.summarize_runs([], 1000)
        self.assertIsNone(empty['success_rate'])
        self.assertIsNone(empty['median_elapsed_seconds'])
        now = observe.timestamp('2026-10-29T12:00:00Z')
        result = observe.summarize_runs([
            {'id': 1, 'status': 'completed', 'conclusion': 'failure', 'run_started_at': '2026-10-29T11:55:00Z', 'updated_at': '2026-10-29T12:00:00Z'},
            {'id': 2, 'status': 'in_progress', 'run_started_at': '2026-10-29T11:00:00Z'}], now)
        self.assertEqual(0, result['success_rate'])
        self.assertEqual(300, result['median_elapsed_seconds'])
        self.assertEqual(2, result['long_running'][0]['run_id'])

    def test_cadence_identifiers_are_stable_and_payload_excludes_content(self):
        report = {'runs': {'long_running': [], 'completed': 1, 'failed': 0}, 'recent_failures': [],
                  'stalled_prs': [{'pr_number': 7}], 'merged_prs': [], 'inventory_complete': True}
        first = list(observe.notifications('owner/repo', report, 20000))
        second = list(observe.notifications('owner/repo', report, 20001))
        self.assertEqual(first, second)
        self.assertEqual('pr_stalled', first[0][1]['kind'])
        self.assertNotIn('body', json.dumps(first))

    def test_delivery_state_is_bounded_and_roundtrips(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'state.json'
            state = {'deliveries': {str(i): i for i in range(600)}}
            observe.save_state(path, state)
            self.assertEqual(512, len(observe.load_state(path)['deliveries']))
            path.write_text('malformed')
            self.assertEqual({}, observe.load_state(path))

    def test_repository_and_paths_cannot_redirect_tokens(self):
        for repo in ('https://evil.test', 'owner/repo/extra', 'owner/repo?token'):
            with self.assertRaises(ValueError): observe.GitHub(repo, 'secret')
        api = observe.GitHub('owner/repo', 'secret', Mock())
        with self.assertRaises(ValueError): api.request('/../elsewhere')
        api.opener.open.assert_not_called()

    def test_job_cache_excludes_steps_and_is_bounded_by_bytes(self):
        api = Mock()
        api.pages.return_value = ([{'id': 1, 'status': 'completed', 'steps': [{'name': 'private source'}]}], True)
        value = observe.job_diagnostics(api, {'id': 1, 'run_attempt': 1})
        self.assertNotIn('private source', json.dumps(value))
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'state.json'
            state = {'completed_cache': {str(i): {'stored_at': i, 'value': 'x' * 100_000} for i in range(30)}, 'deliveries': {}}
            observe.save_state(path, state)
            self.assertLessEqual(path.stat().st_size, 1024 * 1024)



class ArtifactRetentionTest(unittest.TestCase):
    def artifact(self, id, name='app-debug', created='2026-09-01T00:00:00Z', size=100):
        return {'id': id, 'name': name, 'created_at': created, 'size_in_bytes': size,
                'expired': False, 'workflow_run': {'head_branch': 'main'}}

    def test_latest_and_unknown_artifacts_are_preserved(self):
        rows = [self.artifact(1), self.artifact(2, created='2026-09-02T00:00:00Z'), self.artifact(3, name='release-signing-key')]
        proposal = retention.plan(rows, observe.timestamp('2026-10-29T12:00:00Z'))
        self.assertEqual([1], [row['id'] for row in proposal['candidates']])
        self.assertEqual([2], proposal['protected_latest_artifact_ids'])

    def test_quota_never_deletes_recent_or_last_branch_artifact(self):
        rows = [self.artifact(1, created='2026-10-28T00:00:00Z'), self.artifact(2, created='2026-10-29T00:00:00Z')]
        proposal = retention.plan(rows, observe.timestamp('2026-10-29T12:00:00Z'), quota_bytes=10)
        self.assertEqual([], proposal['candidates'])
        self.assertFalse(proposal['quota_satisfied'])

    def test_changed_identity_rejects_delete(self):
        api = Mock()
        api.request.return_value = self.artifact(1, size=101)
        row = self.artifact(1)
        with self.assertRaises(ValueError): retention.apply(api, {'candidates': [row]})
        self.assertEqual(1, api.request.call_count)

    def test_lost_delete_response_keeps_pending_identity_for_reconciliation(self):
        api = Mock()
        api.request.side_effect = [self.artifact(1), TimeoutError()]
        proposal = {'candidates': [self.artifact(1)]}
        snapshots = []
        with self.assertRaises(TimeoutError):
            retention.apply(api, proposal, lambda: snapshots.append(json.loads(json.dumps(proposal))))
        self.assertEqual(1, snapshots[-1]['pending_delete'])
        self.assertEqual([], snapshots[-1]['deleted'])
        self.assertEqual(2, api.request.call_count)


class BuildMetricsTest(unittest.TestCase):
    def test_command_failure_is_preserved_with_measured_report(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'metrics.json'
            environment = os.environ.copy()
            environment.pop('GITHUB_STEP_SUMMARY', None)
            result = subprocess.run([sys.executable, str(Path(build_metrics.__file__).resolve()), '--label', 'fixture', '--output', str(output), '--', sys.executable, '-c', 'print("> Task :compile FROM-CACHE"); print("> Task :test FAILED"); raise SystemExit(7)'], cwd=root, env=environment, capture_output=True, text=True)
            self.assertEqual(7, result.returncode)
            report = json.loads(output.read_text())
            self.assertEqual(7, report['exit_code'])
            self.assertEqual(0.5, report['task_cache_hit_rate'])
            self.assertEqual([], report['apks'])
            self.assertIsNone(report['coverage'])

    def test_apk_categories_and_largest_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', b'x' * 100)
                archive.writestr('lib/arm64/libx.so', b'x' * 200)
                archive.writestr('res/image.webp', b'x' * 30)
            result = build_metrics.apk_metrics(path)
            self.assertEqual(100, result['compressed_categories']['dex'])
            self.assertEqual(200, result['compressed_categories']['native'])
            self.assertEqual('lib/arm64/libx.so', result['largest_entries'][0]['path'])

    def test_only_matching_baseline_produces_delta(self):
        current = {'comparison_key': 'a', 'elapsed_seconds': 50, 'child_peak_rss_kb': 500}
        baseline = {'comparison_key': 'a', 'elapsed_seconds': 100, 'child_peak_rss_kb': 1000}
        self.assertEqual(-50, build_metrics.compare(current, baseline)['elapsed_seconds_change_percent'])
        self.assertFalse(build_metrics.compare(current, dict(baseline, comparison_key='b'))['comparable'])

    def test_coverage_uses_actual_root_counters(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'coverage.xml'
            path.write_text('<report><counter type="LINE" missed="3" covered="7"/></report>')
            self.assertEqual(0.7, build_metrics.coverage(path)['LINE']['fraction'])
            self.assertIsNone(build_metrics.coverage(path.parent / 'missing.xml'))
