"""Actual interpreter, negative controls, immutable tasks and durable grading."""
from copy import deepcopy
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from benchmarks import coding, fixtures, sandbox
from benchmarks.calibration import self_check, task_check
from benchmarks.contracts import content_digest, digest
from benchmarks.engineering_qualification import read_observations, run_manifest, selected_route, SCENARIOS, PRODUCTION_SCHEMA
from benchmarks.external import evaluate_request, public_task
from benchmarks.grader_manifest import seal
from benchmarks.runner import BenchmarkRunner
from benchmarks.store import BenchmarkStore, BenchmarkConflict
from benchmarks.verified_python import evaluate, UnsupportedLanguage, StepLimit
from v14.engineering_backend import blob_sha
from v14.execution_binding import fingerprint


SETTINGS = {'sandbox_image': 'python@sha256:' + 'a' * 64, 'sandbox_seconds': 30, 'repair_attempts': 1}


def proposal(task, candidate=None):
    candidate = candidate or task['referenceSources']
    return json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                   'edits': [{'old': source, 'new': candidate[path]}]}
                                  for path, source in task['sources'].items()],
                       'diagnosis': dict(task['sources']),
                       'tests': [{'input': vector, 'expected': fixtures.expected(task, vector)} for vector in task['vectors'][:6]],
                       'evidence': {'tests_executed': False, 'changed_files': list(task['sources'])}})


def interpreted(candidate, inputs, *_args, **_kwargs):
    try:
        result = evaluate(candidate, inputs)
    except UnsupportedLanguage:
        return {'status': 'ERROR', 'executed': True, 'outputs': [], 'error': 'GRADER_LANGUAGE_UNSUPPORTED'}
    return dict(result, status='COMPLETED', executed=True, outputDigest=digest(result), durationMs=1)


def submission(task=None, text=None):
    task = task or fixtures.find_task('pagination-1')
    return {'request_id': 'one-task-request', 'task_id': task['id'], 'seed': 17,
            'fixture_digest': task['taskDigest'], 'grader_digest': task['graderDigest'],
            'proposal': proposal(task) if text is None else text,
            'generation_receipt': {'model': 'selected/model', 'profile_uid': 'profile', 'profile_revision': 'profile-rev',
                'settings_digest': 'a' * 64, 'provider_kind': 'OPENROUTER', 'route_identity': 'b' * 64,
                'request_ids': ['physical-request'], 'observed_model': None, 'identity_verified': True,
                'input_tokens': None, 'output_tokens': None, 'duration_ms': 100, 'usage_complete': False}}


class IndependentGraderTests(unittest.TestCase):
    def test_all_gold_contracts_match_real_interpreter_and_detect_each_fault(self):
        tasks = fixtures.suite('deep')
        self.assertEqual(100, len({task['problemId'] for task in tasks}))
        self.assertEqual(13, len({task['family'] for task in tasks}))
        for task in tasks:
            with self.subTest(task=task['id']):
                result = evaluate(task['referenceSources'], task['vectors'])
                self.assertEqual([fixtures.expected(task, vector) for vector in task['vectors']], [row['output'] for row in result['outputs']])
                self.assertTrue(all(row['input_preserved'] for row in result['outputs']))
                self.assertEqual('PASSED', task_check(task)['status'])

    def test_actual_correct_candidate_resolves_with_independent_receipt(self):
        for task in fixtures.suite('quick') + fixtures.suite('deep')[-4:]:
            with self.subTest(task=task['id']):
                result = coding.grade(task, proposal(task), SETTINGS, threading.Event(), execute=interpreted)
                self.assertEqual('PASSED', result['status'])
                self.assertTrue(result['resolved'])
                self.assertTrue(result['qualified'])
                self.assertEqual(1000, result['score'])
                self.assertEqual('PASSED', result['inputMutationVerification'])

    def test_correct_return_with_input_mutation_cannot_resolve(self):
        task = fixtures.find_task('pagination-1')
        candidate = dict(task['referenceSources'])
        candidate['service.py'] = "from core import solve\ndef handle(request):\n    if not isinstance(request, dict) or not isinstance(request.get('items'), list):\n        return {'error': 'invalid_request'}\n    result = {'result': solve(request['items'], request.get('config', {}))}\n    request['items'].clear()\n    return result\n"
        result = coding.grade(task, proposal(task, candidate), SETTINGS, threading.Event(), execute=interpreted)
        self.assertTrue(result['outputCorrect'])
        self.assertTrue(result['qualified'])
        self.assertFalse(result['resolved'])
        self.assertEqual('CANDIDATE_INPUT_MUTATION', result['error'])
        self.assertEqual('FAILED', result['inputMutationVerification'])
        self.assertEqual(0, result['dimensions']['safety'])

    def test_explanation_wording_cannot_change_verified_core_correctness(self):
        task = fixtures.find_task('pagination-1')
        answer = json.loads(proposal(task))
        answer['diagnosis'] = {path: 'Preserve every page and select the newest revision.' for path in task['sources']}
        answer['tests'] = []
        result = coding.grade(task, json.dumps(answer), SETTINGS, threading.Event(), execute=interpreted)
        self.assertTrue(result['qualified'])
        self.assertTrue(result['resolved'])
        self.assertEqual('PASSED', result['status'])
        self.assertEqual('EXECUTED_BEHAVIOR_AND_INTEGRITY_V2', result['primaryOutcomePolicy'])
        self.assertEqual(0, result['dimensions']['diagnosis'])
        self.assertEqual(0, result['dimensions']['tests'])
        self.assertLess(result['score'], 1000)

    def test_compatible_positional_only_interface_executes_and_keeps_design_credit(self):
        task = fixtures.find_task('pagination-1')
        sources = dict(task['referenceSources'])
        sources['service.py'] = sources['service.py'].replace('def handle(request):', 'def handle(request, /):')
        result = coding.grade(task, proposal(task, sources), SETTINGS, threading.Event(), execute=interpreted)
        self.assertTrue(result['resolved'])
        self.assertEqual(1, result['dimensions']['design'])

    def test_counterfeit_snapshot_receipt_and_stale_source_are_unqualified(self):
        task = fixtures.find_task('pagination-1')
        def counterfeit(candidate, inputs, *_args, **_kwargs):
            result = interpreted(candidate, inputs)
            result['verification']['sourceDigest'] = '0' * 64
            return result
        result = coding.grade(task, proposal(task), SETTINGS, threading.Event(), execute=counterfeit)
        self.assertFalse(result['qualified'])
        self.assertIsNone(result['score'])
        self.assertEqual('NOT_VERIFIED', result['inputMutationVerification'])

    def test_calibration_and_language_authority_reject_escape_paths(self):
        self.assertEqual('PASSED', self_check()['status'])
        for source in ("import os\ndef handle(x): return os.getenv('TOKEN')",
                       "def handle(x): return x.__class__",
                       "def handle(x): return '{0.__class__}'.format(x)",
                       "import json\njson.dumps = lambda x: 'forged'\ndef handle(x): return x"):
            with self.subTest(source=source):
                with self.assertRaises(UnsupportedLanguage):
                    evaluate({'service.py': source}, [{}])
        payload = evaluate({'service.py': "def handle(x):\n    x.clear()\n    return {'input_preserved': True}\n"}, [{'x': 1}])
        self.assertFalse(payload['outputs'][0]['input_preserved'])
        with self.assertRaises(StepLimit):
            evaluate({'service.py': 'def handle(x):\n    while True:\n        pass\n'}, [{}], max_steps=100)

    def test_python_binding_and_augmented_assignment_match_declared_semantics(self):
        augmented = "def handle(x):\n    keys = [0, 1]\n    values = [10, 20]\n    values[keys.pop(0)] += 2\n    return [values, keys]\n"
        self.assertEqual([[12, 20], [1]], evaluate({'service.py': augmented}, [{}])['outputs'][0]['output'])
        local = "value = 10\ndef handle(x):\n    if False:\n        value = 2\n    return value\n"
        self.assertEqual('UnboundLocalError', evaluate({'service.py': local}, [{}])['outputs'][0]['error'])
        aliases = "def handle(x):\n    values = x['items']\n    values += [3]\n    return values\n"
        self.assertFalse(evaluate({'service.py': aliases}, [{'items': [1, 2]}])['outputs'][0]['input_preserved'])

    def test_task_identity_changes_for_each_oracle_test_schema_source_change(self):
        original = fixtures.find_task('pagination-1')
        for field in ('vectors', 'referenceSources', 'sources', 'requirements'):
            changed = deepcopy(original)
            if field == 'vectors':
                changed[field].append({'items': []})
            elif field == 'requirements':
                changed[field] += ' extra requirement'
            else:
                changed[field][next(iter(changed[field]))] += '\n# version change\n'
            expected = [fixtures.expected(changed, vector) for vector in changed['vectors']]
            seal(changed, expected)
            self.assertNotEqual(original['taskDigest'], changed['taskDigest'], field)
        public = public_task(original)
        self.assertNotIn('hiddenExpected', public)
        self.assertNotIn('referenceSources', public)


class ExternalEvaluationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.store = BenchmarkStore(Path(self.temporary.name) / 'journal.sqlite3')

    def test_reconcile_same_submission_and_keep_private_source_out_of_results(self):
        request, text = evaluate_request(submission())
        first, created = self.store.create('alice', request, {'executionIdentity': 'route'}, submission=text)
        again, repeated = self.store.create('alice', request, {'executionIdentity': 'route'}, submission=text)
        self.assertTrue(created)
        self.assertFalse(repeated)
        self.assertEqual(first['id'], again['id'])
        self.assertEqual(text, self.store.private_submission(first['id']))
        self.assertEqual(content_digest(text), first['proposalDigest'])
        self.assertNotIn('proposal', self.store.get(first['id'])['request'])
        self.assertEqual('NOT_OBSERVED', self.store.submission('bob', request['request_id'])['submissionState'])
        changed = dict(request, proposal_digest=content_digest(text + ' '))
        with self.assertRaises(BenchmarkConflict):
            self.store.create('alice', changed, {}, submission=text + ' ')

    def test_stale_task_grader_and_candidate_digest_rejected_before_admission(self):
        for field in ('fixture_digest', 'grader_digest', 'proposal_digest'):
            value = submission()
            value[field] = '0' * 64
            with self.assertRaises(ValueError):
                evaluate_request(value)

    def test_one_task_uses_client_model_evidence_without_gateway_inference(self):
        task = fixtures.find_task('pagination-1')
        request, text = evaluate_request(submission(task))
        run, _ = self.store.create('alice', request, {'executionIdentity': 'route'}, submission=text)
        self.store.update(run['id'], state='RUNNING')
        runner = BenchmarkRunner(self.store, 'http://not-used', threading.Event(), lambda: 'route', lambda: SETTINGS)
        runner.execute_sandbox = lambda run_value, candidate, inputs, *_args, **_kwargs: interpreted(candidate, inputs)
        with patch.object(sandbox, 'preflight', return_value='docker'), patch('benchmarks.runner.client_session', side_effect=AssertionError('No provider substitution')):
            summary = runner.execute(run)
        self.assertTrue(summary['qualified'])
        self.assertEqual(1, summary['resolved'])
        case = self.store.get(run['id'])['cases'][0]
        self.assertEqual('AUTHENTICATED_CLIENT_OBSERVATION', case['modelIdentityScope'])
        self.assertFalse(case['independentProviderIdentityVerified'])
        self.assertIsNone(case['observedModel'])
        self.assertEqual(content_digest(text), case['proposalDigest'])
        self.assertEqual(task['graderDigest'], case['graderDigest'])

    def test_empty_completed_answer_is_observed_model_failure_not_missing_execution(self):
        request, text = evaluate_request(submission(text=''))
        self.assertEqual('', text)
        task = fixtures.find_task(request['task_id'])
        result = coding.grade(task, text, SETTINGS, threading.Event(), execute=lambda *_args: self.fail('invalid JSON must not run'))
        self.assertEqual('FAILED', result['status'])
        self.assertTrue(result['outcomeKnown'])
        self.assertEqual('MODEL', result['failureDomain'])
        run, _ = self.store.create('alice', request, {'executionIdentity': 'route'}, submission=text)
        self.store.update(run['id'], state='RUNNING')
        runner = BenchmarkRunner(self.store, 'http://unused', threading.Event(), lambda: 'route', lambda: SETTINGS)
        with patch.object(sandbox, 'preflight', side_effect=AssertionError('Malformed JSON does not require a container')):
            summary = runner.execute(run)
        self.assertTrue(summary['outcomeComplete'])
        self.assertEqual(0, summary['firstAttemptRate'])

    def test_case_pages_freeze_bound_and_do_not_leak_another_owner(self):
        request, text = evaluate_request(submission())
        run, _ = self.store.create('alice', request, {}, submission=text)
        for index in range(60):
            self.store.case(run['id'], index, {'id': str(index), 'status': 'PASSED'})
        first = self.store.case_page(run['id'], 'alice')
        self.assertEqual(24, first['next'])
        self.store.case(run['id'], 60, {'id': '60', 'status': 'PASSED'})
        second = self.store.case_page(run['id'], 'alice', after=first['next'], through=first['throughOrdinal'])
        third = self.store.case_page(run['id'], 'alice', after=second['next'], through=first['throughOrdinal'])
        self.assertEqual(60, sum(len(page['cases']) for page in (first, second, third)))
        self.assertTrue(third['complete'])
        with self.assertRaises(KeyError):
            self.store.case_page(run['id'], 'bob')


class ProductionEvidenceTests(unittest.TestCase):
    def test_more_than_512_attempts_are_fully_scanned_with_exact_boundary(self):
        with sqlite3.connect(':memory:') as db:
            db.execute('CREATE TABLE engineering_model_attempts(job_id TEXT,payload TEXT)')
            db.executemany('INSERT INTO engineering_model_attempts VALUES(?,?)',
                           [('job', json.dumps({'index': index, 'usage': {'total_tokens': 1}})) for index in range(1200)])
            db.execute('INSERT INTO engineering_model_attempts VALUES(?,?)', ('other', '{}'))
            rows, receipt = read_observations(db, 'engineering_model_attempts', 'job')
            self.assertTrue(receipt['complete'])
            self.assertEqual(1200, len(rows))
            self.assertEqual(5, receipt['pages'])
            self.assertEqual(1199, rows[-1]['index'])

    def test_selected_route_requires_every_actual_attempt_binding_and_model(self):
        binding = {'binding_id': 'bind_abc', 'profile_uid': 'p', 'profile_revision': 'v',
                   'backend_endpoint_identity': 'endpoint', 'requested_model_id': 'selected',
                   'generation_parameters': {'temperature': 0}, 'generation_parameters_digest': fingerprint({'temperature': 0})}
        job = {'request': {'model': 'selected', 'profile_id': 'p', 'source_sha': 'a' * 40, 'execution_binding_id': 'bind_abc'}, 'execution_binding': binding}
        row = {'binding_revision': fingerprint(binding), 'requested_model_id': 'selected',
               'observed_model_id': 'selected', 'identity_status': 'VERIFIED', 'backend_endpoint_identity': 'endpoint'}
        self.assertTrue(selected_route(job, [row])[1])
        self.assertFalse(selected_route(job, [row, dict(row, observed_model_id='other')])[1])
        self.assertFalse(selected_route(job, [dict(row, binding_revision='stale')])[1])
        self.assertFalse(selected_route(job, [])[1])


if __name__ == '__main__':
    unittest.main()
