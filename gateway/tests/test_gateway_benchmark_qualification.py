"""Qualification stays unknown when the evaluator cannot verify its full contract."""
from collections import Counter
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import coding, fixtures, sandbox
from benchmarks.contracts import CODING_WEIGHTS, coding_score, digest
from benchmarks.runner import BenchmarkRunner
from benchmarks.store import BenchmarkStore
from test_gateway_benchmark_v17 import gold_proposal, request


class BenchmarkQualificationTest(unittest.TestCase):
    settings = {'sandbox_image': 'python@sha256:' + 'a' * 64,
                'repair_enabled': True, 'repair_attempts': 3}
    observation_digest = 'd' * 64

    def setUp(self):
        self.tasks = fixtures.suite('quick', 17)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = BenchmarkStore(Path(self.directory.name) / 'benchmarks.sqlite3')
        self.run, _ = self.store.create('owner', request(), {'executionIdentity': 'route'})
        self.store.update(self.run['id'], state='RUNNING')
        self.runner = BenchmarkRunner(self.store, 'http://localhost', threading.Event(),
                                      lambda: 'route', lambda: dict(self.settings))

    def observation(self, task, inputs, *, correct=True):
        return {'executed': True, 'status': 'COMPLETED',
                'outputs': [{'output': fixtures.expected(task, vector) if correct else
                             {'observed_wrong_output': True}, 'input_preserved': True}
                            for vector in inputs],
                'outputDigest': self.observation_digest, 'durationMs': 7}

    def grade_correct(self, task):
        def execute(candidate, inputs, *_args, **_kwargs):
            self.assertEqual(task['referenceSources'], candidate)
            return self.observation(task, inputs)
        return coding.grade(task, gold_proposal(task), self.settings, threading.Event(),
                            execute=execute)

    def assert_correct_but_unqualified(self, case, task):
        self.assertTrue(case['executed'])
        self.assertTrue(case['graded'])
        self.assertEqual('BLOCKED', case['status'])
        self.assertIsNone(case['score'])
        self.assertFalse(case['resolved'])
        self.assertTrue(case['outputCorrect'])
        self.assertFalse(case['qualified'])
        self.assertIsNone(case['dimensions']['safety'])
        self.assertEqual('PASSED', case['patchScopeVerification'])
        self.assertEqual('NOT_VERIFIED', case['inputMutationVerification'])
        self.assertEqual(['INPUT_PRESERVATION_NOT_VERIFIED'], case['qualificationBlockers'])
        self.assertEqual(950, case['earned'])
        self.assertEqual(1000, case['possible'])
        self.assertEqual(50, case['unknownWeight'])
        for dimension in CODING_WEIGHTS.keys() - {'safety'}:
            self.assertEqual(1.0, case['dimensions'][dimension], dimension)
        self.assertEqual(len(task['vectors']), case['checks']['hiddenPassed'])
        self.assertEqual(len(task['vectors']), case['checks']['hiddenTotal'])
        self.assertEqual(len(task['vectors']), case['checks']['replayPassed'])
        self.assertEqual(len(task['vectors']), case['checks']['replayTotal'])
        self.assertEqual(self.observation_digest, case['outputDigest'])
        self.assertEqual(7, case['validationMs'])
        self.assertNotIn('error', case)

    def assert_unqualified_summary(self, summary):
        self.assertIsNone(summary['score'])
        self.assertFalse(summary['qualified'])
        self.assertIsNone(summary['firstSubmissionScore'])
        self.assertIsNone(summary['passAtOne'])
        self.assertIsNone(summary['finalResolvedRate'])
        self.assertEqual(0, summary['resolved'])
        self.assertAlmostEqual(950, summary['earned'])
        self.assertEqual(1000, summary['possible'])
        self.assertAlmostEqual(50, summary['unknownWeight'])
        safety = summary['dimensions']['safety']
        self.assertIsNone(safety['value'])
        self.assertEqual(0, safety['measured'])
        self.assertEqual(0, safety['earned'])
        self.assertEqual(50, safety['unknownWeight'])

    def test_correct_observed_outputs_retain_measurements_without_full_contract_qualification(self):
        task = self.tasks[0]
        self.assert_correct_but_unqualified(self.grade_correct(task), task)

    def test_full_family_and_first_attempt_scores_stay_unknown_in_durable_checkpoints(self):
        self.store.update(self.run['id'], planned=len(self.tasks))
        cases = []
        for ordinal, task in enumerate(self.tasks):
            case = self.grade_correct(task)
            case['attempts'] = [deepcopy(case)]
            case['firstSubmissionResolved'] = False
            cases.append(case)
            self.runner.checkpoint(self.run, ordinal, case)
            checkpoint = self.store.get(self.run['id'])
            self.assertIsNone(checkpoint['summary']['score'])
            self.assertFalse(checkpoint['summary']['qualified'])
            self.assertIsNone(checkpoint['summary']['firstSubmissionScore'])
            self.assertEqual(ordinal + 1, checkpoint['completed'])

        score = coding_score(cases, len(self.tasks), 'quick')
        self.assert_unqualified_summary(score)
        saved = self.store.get(self.run['id'])
        self.assertEqual(score, saved['summary'])
        self.assertEqual(len(self.tasks), saved['summary']['coverage']['outcomes']['BLOCKED'])
        self.assertEqual(len(self.tasks), saved['summary']['coverage']['executed'])
        self.assertEqual(len(self.tasks), saved['summary']['coverage']['graded'])
        for case, task in zip(saved['cases'], self.tasks):
            self.assert_correct_but_unqualified(case, task)
            self.assert_correct_but_unqualified(case['attempts'][0], task)

    def run_coding(self, *, first_output_incorrect=False):
        proposals = {task['id']: gold_proposal(task) for task in self.tasks}
        sources = {digest(task['referenceSources']): (task, True) for task in self.tasks}
        first = self.tasks[0]
        bad = json.loads(proposals[first['id']])
        bad_source = "def solve(items, config):\n    return {'observed_wrong_output': True}\n"
        bad['changes'][0]['edits'][0]['new'] = bad_source
        bad_sources = dict(first['referenceSources'], **{bad['changes'][0]['path']: bad_source})
        sources[digest(bad_sources)] = (first, False)
        dispatches = Counter()

        def measure(_run, name, turns, _expected, *, validate, output_tokens):
            dispatches[name] += 1
            repairing = first_output_incorrect and name == first['id'] and dispatches[name] > 1
            self.assertEqual(3 if repairing else 1, len(turns))
            text = json.dumps(bad) if first_output_incorrect and name == first['id'] and dispatches[name] == 1 else proposals[name]
            self.assertTrue(validate(text))
            return {'firstMs': 4, 'totalMs': 8, 'inputTokens': 30, 'outputTokens': 40,
                    'finishReason': 'stop', 'observedModel': 'selected',
                    'modelReceipt': 'REPORTED_IDENTIFIER', 'identityScope': 'HTTP_ROUTE_AND_REPORTED_IDENTIFIER',
                    'gatewayJobId': 'observed-' + name + '-' + str(dispatches[name])}

        def execute(_run, candidate, inputs, *_args, **_kwargs):
            task, correct = sources[digest(candidate)]
            return self.observation(task, inputs, correct=correct)

        with patch.object(sandbox, 'preflight', return_value='/test/docker'), \
                patch.object(self.runner, 'execute_sandbox', side_effect=execute) as sandbox_calls, \
                patch.object(self.runner, 'measure_case', side_effect=measure) as model_calls:
            summary = self.runner.code(self.run)
        return summary, dispatches, model_calls.call_count, sandbox_calls.call_count

    def test_evaluator_gap_does_not_consume_model_repairs_for_correct_output(self):
        summary, dispatches, model_calls, sandbox_calls = self.run_coding()
        self.assertEqual({task['id']: 1 for task in self.tasks}, dict(dispatches))
        self.assertEqual(len(self.tasks), model_calls)
        self.assertEqual(2 * len(self.tasks), sandbox_calls)
        self.assert_unqualified_summary(summary)
        saved = self.store.get(self.run['id'])
        self.assert_unqualified_summary(saved['summary'])
        for case, task in zip(saved['cases'], self.tasks):
            self.assert_correct_but_unqualified(case, task)
            self.assertEqual(1, len(case['attempts']))
            self.assertFalse(case['firstSubmissionResolved'])
            self.assertIsNone(case['firstSubmissionScore'])
            self.assertEqual(3, case['repairBudget'])
            self.assertEqual(8, case['timing']['totalMs'])
            self.assertEqual('selected', case['observedModel'])
            self.assertEqual('observed-' + task['id'] + '-1', case['gatewayJobId'])

    def test_observed_output_failure_is_repaired_once_then_stops_at_evaluator_gap(self):
        summary, dispatches, model_calls, sandbox_calls = self.run_coding(first_output_incorrect=True)
        first = self.tasks[0]
        self.assertEqual({task['id']: 2 if task is first else 1 for task in self.tasks}, dict(dispatches))
        self.assertEqual(len(self.tasks) + 1, model_calls)
        self.assertEqual(2 * len(self.tasks) + 1, sandbox_calls)
        self.assert_unqualified_summary(summary)
        saved = self.store.get(self.run['id'])
        case = saved['cases'][0]
        self.assert_correct_but_unqualified(case, first)
        self.assertEqual(2, len(case['attempts']))
        failed, corrected = case['attempts']
        self.assertEqual('FAILED', failed['status'])
        self.assertTrue(failed['executed'])
        self.assertTrue(failed['graded'])
        self.assertFalse(failed['outputCorrect'])
        self.assertFalse(failed['resolved'])
        self.assertFalse(failed['qualified'])
        self.assertIsNone(failed['score'])
        self.assertIsNone(failed['dimensions']['safety'])
        self.assertEqual(0, failed['checks']['hiddenPassed'])
        self.assertEqual(0, failed['checks']['replayPassed'])
        self.assertEqual('NOT_VERIFIED', failed['inputMutationVerification'])
        self.assertEqual(['INPUT_PRESERVATION_NOT_VERIFIED'], failed['qualificationBlockers'])
        self.assertNotIn('error', failed)
        self.assert_correct_but_unqualified(corrected, first)
        self.assertEqual('observed-' + first['id'] + '-2', case['gatewayJobId'])
        self.assertIsNone(case['firstSubmissionScore'])
        self.assertFalse(case['firstSubmissionResolved'])


if __name__ == '__main__':
    unittest.main()
