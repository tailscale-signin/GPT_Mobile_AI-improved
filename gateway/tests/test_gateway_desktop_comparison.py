import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from desktop import benchmark_comparison as comparison
from desktop.configuration import atomic_json, DEFAULTS
from benchmarks.contracts import digest


class DesktopComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.binding = dict(origin='http://127.0.0.1:8090', gateway_id='gateway', principal_id='principal')
        for target, value in [('verify_submission', None), ('benchmark.execution_binding', self.binding), ('benchmark.discover', ['a', 'b'])]:
            p = patch('desktop.benchmark_comparison.' + target, return_value=value)
            p.start()
            self.addCleanup(p.stop)

    def create(self, **kwargs):
        return comparison.create(self.root, ['a', 'b'], **kwargs)

    def fake_run(self, model, seed, success=True):
        cases = [dict(id=f'task-{i}', family=f'family-{i}', taskDigest=digest([i, seed]), observedModel=model,
                      resolved=success, attempts=[dict(resolved=success, observedModel=model, taskDigest=digest([i, seed]), graderDigest=digest('grader'),
                                                      timing=dict(inputTokens=10, outputTokens=5, totalMs=20))]) for i in range(12)]
        return dict(id='bench_' + ('a' if model == 'a' else 'b') * 32, state='COMPLETED', planned=12, cases=cases,
                    summary=dict(qualified=True, calibration=dict(status='PASSED'), fixtureManifest=digest(seed),
                                 graderDigest=digest('grader'), repairBudget=1, executionScope='ISOLATED_PYTHON_CONTRACT',
                                 finalResolved=12 if success else 0))

    def complete(self, a=True, b=True):
        session = self.create()
        with patch.object(comparison.benchmark, 'start_run', side_effect=lambda root, model, *args, **kwargs: self.fake_run(model, kwargs['seed'], a if model == 'a' else b)):
            for _ in range(6):
                session = comparison.advance(self.root, session['id'])
        return session

    def test_order_is_frozen_and_counterbalancing_requires_selection(self):
        session = self.create()
        self.assertEqual(['a', 'b', 'a', 'b', 'a', 'b'], [row['model'] for row in session['queue']])
        self.assertEqual([17, 17, 18, 18, 19, 19], [row['seed'] for row in session['queue']])
        comparison.stop(self.root, session['id'])
        changed = self.create(ordering='COUNTERBALANCED')
        self.assertEqual(['a', 'b', 'b', 'a', 'a', 'b'], [row['model'] for row in changed['queue']])

    def test_lost_admission_is_not_dispatched_twice_after_reopen(self):
        session = self.create()
        with patch.object(comparison.benchmark, 'start_run', side_effect=TimeoutError('lost receipt')) as start:
            with self.assertRaises(TimeoutError):
                comparison.advance(self.root, session['id'])
            saved = comparison.read(self.root, session['id'])
            self.assertEqual('UNCERTAIN', saved['queue'][0]['state'])
            with patch.object(comparison.benchmark, 'reconcile_run', return_value={'submissionState': 'NOT_OBSERVED'}):
                comparison.advance(self.root, session['id'])
            self.assertEqual(1, start.call_count)
            with patch.object(comparison.benchmark, 'reconcile_run', return_value={'submissionState': 'OBSERVED', 'run': self.fake_run('a', 17)}):
                recovered = comparison.advance(self.root, session['id'])
            self.assertEqual(1, start.call_count)
            self.assertEqual('COMPLETED', recovered['queue'][0]['state'])

    def test_configuration_change_blocks_next_dispatch(self):
        session = self.create()
        atomic_json(self.root / 'settings.json', dict(DEFAULTS, workers=3))
        with patch.object(comparison.benchmark, 'start_run') as start:
            result = comparison.advance(self.root, session['id'])
            self.assertEqual('SETTINGS_CHANGED', result['state'])
            start.assert_not_called()

    def test_repository_pack_freezes_actual_counts_and_dispatch_selection(self):
        session = self.create(task_pack='repository', preset='deep')
        self.assertEqual(12, session['manifest']['taskCount'])
        self.assertEqual('repository', session['manifest']['taskPack'])
        with patch.object(comparison.benchmark, 'start_run', return_value=self.fake_run('a', 17)) as start:
            comparison.advance(self.root, session['id'])
        self.assertEqual('repository', start.call_args.kwargs['task_pack'])

    def test_episode_files_preserve_complete_evidence_and_detect_corruption(self):
        session = self.complete()
        directory = self.root / 'data' / 'benchmark-comparisons'
        manifest = json.loads((directory / (session['id'] + '.json')).read_text())
        self.assertNotIn('run', manifest['queue'][0])
        self.assertEqual(12, len(comparison.read(self.root, session['id'])['queue'][0]['run']['cases']))
        evidence = directory / session['id'] / manifest['queue'][0]['runFile']
        changed = json.loads(evidence.read_text())
        changed['cases'][0]['resolved'] = False
        evidence.write_text(json.dumps(changed))
        with self.assertRaisesRegex(ValueError, 'evidence changed'):
            comparison.read(self.root, session['id'])

    def test_rejected_model_does_not_cancel_other_model(self):
        session = self.create()
        with patch.object(comparison.benchmark, 'start_run', side_effect=[comparison.benchmark.BenchmarkRejected('not supported'), self.fake_run('b', 17)]) as start:
            comparison.advance(self.root, session['id'])
            result = comparison.advance(self.root, session['id'])
        self.assertEqual(2, start.call_count)
        self.assertEqual(['BLOCKED', 'COMPLETED'], [row['state'] for row in result['queue'][:2]])
        self.assertIsNone(result['summary']['winner'])

    def test_queued_run_with_no_summary_retains_its_admission(self):
        session = self.create()
        queued = dict(id='bench_' + 'a' * 32, state='QUEUED', summary=None, cases=[], planned=0)
        with patch.object(comparison.benchmark, 'start_run', return_value=queued) as start:
            accepted = comparison.advance(self.root, session['id'])
            self.assertEqual(queued['id'], accepted['queue'][0]['runId'])
            self.assertIsNone(accepted['summary']['winner'])
            with patch.object(comparison.benchmark, 'service_request', return_value=self.fake_run('a', 17)):
                settled = comparison.advance(self.root, session['id'])
            self.assertEqual('COMPLETED', settled['queue'][0]['state'])
            start.assert_called_once()

    def test_fast_incorrect_model_cannot_win(self):
        session = self.complete(a=True, b=False)
        self.assertEqual('a', session['summary']['winner'])
        self.assertEqual(36, session['summary']['pairedTasks'])
        self.assertEqual(12, session['summary']['independentTaskGroups'])
        self.assertEqual(360, session['summary']['models'][1]['measurements']['inputTokens'])

    def test_best_candidate_does_not_replace_failed_final_submission(self):
        session = self.complete(a=True, b=True)
        for row in session['queue']:
            if row['arm'] == 1:
                for case in row['run']['cases']:
                    case['attempts'].append(dict(resolved=False, observedModel='b', taskDigest=case['taskDigest'], graderDigest=digest('grader'),
                                                 timing=dict(inputTokens=2, outputTokens=1, totalMs=10)))
        summary = comparison.summarize(session)
        self.assertEqual('a', summary['winner'])
        self.assertEqual(432, summary['models'][1]['measurements']['inputTokens'])
        self.assertEqual(72, summary['models'][1]['measurements']['attempts'])

    def test_identical_qualified_models_are_inconclusive(self):
        summary = self.complete()['summary']
        self.assertEqual('INCONCLUSIVE', summary['status'])
        self.assertIsNone(summary['winner'])
        self.assertLess(summary['confidenceInterval95'][0], 0)
        self.assertGreater(summary['confidenceInterval95'][1], 0)
        self.assertEqual(1, summary['signProbability'])

    def test_native_projection_preserves_inconclusive_null_and_qualified_winner(self):
        from desktop import native_bridge

        for a, b, expected in ((True, True, None), (True, False, 'a')):
            with self.subTest(a=a, b=b):
                session = self.complete(a=a, b=b)
                projected = native_bridge.dispatch(self.root, 'benchmark-compare-read',
                                                   {'id': session['id']}, io.StringIO())
                summary = json.loads(json.dumps(projected))['summary']
                self.assertIn('winner', summary)
                self.assertEqual(expected, summary['winner'])
                self.assertEqual('INCONCLUSIVE' if expected is None else 'SUPERIOR_ON_MATCHED_SUITE', summary['status'])

    def test_projected_best_identity_cannot_hide_another_model_in_the_final_attempt(self):
        session = self.complete(a=True, b=False)
        case = session['queue'][0]['run']['cases'][0]
        case['attempts'].append(dict(case['attempts'][0], observedModel='unselected-model'))
        summary = comparison.summarize(session)
        self.assertIsNone(summary['winner'])
        self.assertTrue(any('Every generation attempt' in reason for reason in summary['reasons']))

    def test_repaired_candidate_cannot_hide_an_earlier_unbound_grader(self):
        session = self.complete(a=True, b=False)
        case = session['queue'][0]['run']['cases'][0]
        case['attempts'].insert(0, dict(case['attempts'][0], graderDigest='different-grader', resolved=False))
        self.assertIsNone(comparison.summarize(session)['winner'])

    def test_duplicate_case_rows_cannot_disappear_when_indexed_by_task(self):
        session = self.complete(a=True, b=False)
        session['queue'][0]['run']['cases'].append(copy.deepcopy(session['queue'][0]['run']['cases'][0]))
        summary = comparison.summarize(session)
        self.assertIsNone(summary['winner'])
        self.assertTrue(any('duplicate task identities' in reason for reason in summary['reasons']))

    def test_open_and_export_recompute_a_stale_completed_winner(self):
        session = self.complete(a=True, b=True)
        path = self.root / 'data' / 'benchmark-comparisons' / (session['id'] + '.json')
        saved = json.loads(path.read_text())
        saved['summary'].update(winner='a', status='SUPERIOR_ON_MATCHED_SUITE', qualificationPolicy='old')
        atomic_json(path, saved)
        self.assertIsNone(comparison.history(self.root)[0]['summary']['winner'])
        self.assertIsNone(comparison.read(self.root, session['id'])['summary']['winner'])
        self.assertIsNone(comparison.advance(self.root, session['id'])['summary']['winner'])
        destination = self.root / 'comparison.json'
        comparison.export(self.root, session['id'], destination)
        self.assertIsNone(json.loads(destination.read_text())['summary']['winner'])

    def test_completed_manifest_identity_is_checked_on_read_without_resuming(self):
        session = self.complete()
        path = self.root / 'data' / 'benchmark-comparisons' / (session['id'] + '.json')
        saved = json.loads(path.read_text())
        saved['manifest']['models'][0] = 'substituted-model'
        atomic_json(path, saved)
        with self.assertRaisesRegex(ValueError, 'manifest changed'):
            comparison.read(self.root, session['id'])

    def test_missing_grader_or_actual_model_receipt_never_produces_winner(self):
        original = self.complete(a=True, b=False)
        for field in ('calibration', 'graderDigest', 'observedModel'):
            with self.subTest(field=field):
                session = copy.deepcopy(original)
                if field == 'observedModel':
                    session['queue'][0]['run']['cases'][0][field] = 'unexpected-model'
                else:
                    session['queue'][0]['run']['summary'].pop(field)
                self.assertIsNone(comparison.summarize(session)['winner'])

    def test_stop_does_not_claim_uncertain_submission_never_ran(self):
        session = self.create()
        with patch.object(comparison.benchmark, 'start_run', side_effect=TimeoutError()):
            with self.assertRaises(TimeoutError):
                comparison.advance(self.root, session['id'])
        result = comparison.stop(self.root, session['id'])
        self.assertEqual('STOPPING', result['state'])
        self.assertEqual('UNCERTAIN', result['queue'][0]['state'])
        self.assertTrue(all(row['state'] == 'CANCELLED' for row in result['queue'][1:]))
        with patch.object(comparison.benchmark, 'reconcile_run', return_value={'run': self.fake_run('a', 17)}):
            result = comparison.advance(self.root, session['id'])
        self.assertEqual('CANCELLED', result['state'])

    def test_export_omits_private_reconnection_origin(self):
        session = self.create()
        self.assertNotIn('binding', comparison.public_snapshot(session))
        self.assertIn('configurationDigest', comparison.public_snapshot(session)['manifest'])

    def test_display_is_bounded_but_export_keeps_every_retained_attempt(self):
        session = self.complete()
        display = comparison.public_snapshot(session)
        self.assertTrue(all('cases' not in row['run'] for row in display['queue']))
        destination = self.root / 'full-comparison.json'
        comparison.export(self.root, session['id'], destination)
        exported = json.loads(destination.read_text())
        self.assertEqual(72, sum(len(row['run']['cases']) for row in exported['queue']))
        self.assertNotIn('binding', exported)

    def test_all_in_time_does_not_mislabel_model_request_time(self):
        session = self.complete()
        for row in session['queue']:
            row['run'].update(createdAt=10.0, endedAt=11.0)
        measured = comparison.summarize(session)['models'][0]['measurements']
        self.assertEqual(3000, measured['endToEndMs'])
        self.assertEqual(720, measured['totalMs'])


if __name__ == '__main__':
    unittest.main()
