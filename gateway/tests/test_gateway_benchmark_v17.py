"""Behavior regressions for durable, scoped and evidence-based v17 benchmarks."""
from contextlib import contextmanager
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmark_selected_model import BenchmarkInterrupted, measure
from benchmarks import coding, fixtures, sandbox
from benchmarks.contracts import CODING_WEIGHTS, coding_score, coverage, paired_summary, validate_request
from benchmarks.runner import BenchmarkRunner
from benchmarks.service import BenchmarkService, install_benchmark_routes
from benchmarks.store import BenchmarkConflict, BenchmarkStore
from desktop import benchmark
from desktop.configuration import DEFAULTS, atomic_json
from v14.engineering_backend import blob_sha
from v14.identity import Principal


def request(identity='request-1', **changes):
    return dict(dict(request_id=identity, model='selected', suite='coding', preset='quick', seed=17, concurrency=2), **changes)


def full_cases(preset):
    result = []
    for task in fixtures.suite(preset):
        row = {key: task[key] for key in ('id', 'family', 'capstone')}
        row.update(graded=True, executed=True, status='PASSED', integrity='VERIFIED', resolved=True,
                   dimensions={key: 1.0 for key in CODING_WEIGHTS})
        row['attempts'] = [deepcopy(row)]
        row['firstSubmissionResolved'] = True
        result.append(row)
    return result


def gold_proposal(task):
    return json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                   'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                                  for path, source in task['sources'].items()],
                       'diagnosis': dict(task['sources']),
                       'tests': [{'input': vector, 'expected': fixtures.expected(task, vector)} for vector in task['vectors'][:6]],
                       'evidence': {'tests_executed': False, 'changed_files': list(task['sources'])}})


class BenchmarkJournalTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = BenchmarkStore(Path(self.directory.name) / 'data' / 'benchmarks.sqlite3')

    def create(self, identity='request-1', principal='alice'):
        return self.store.create(principal, request(identity), {'executionIdentity': 'route'})[0]

    def test_identical_uncertain_start_is_deduplicated_but_changed_payload_rejected(self):
        run = self.create()
        again, created = self.store.create('alice', request(), {})
        self.assertFalse(created)
        self.assertEqual(run['id'], again['id'])
        with self.assertRaises(BenchmarkConflict):
            self.store.create('alice', dict(request(), model='different'), {})
        self.assertNotEqual(run['id'], self.create(principal='bob')['id'])

    def test_removed_history_keeps_replay_tombstone_and_owner_scope(self):
        run = self.create()
        self.store.update(run['id'], state='COMPLETED')
        self.assertFalse(self.store.remove(run['id'], 'bob'))
        self.assertIsNotNone(self.store.get(run['id'], 'alice'))
        self.assertTrue(self.store.remove(run['id'], 'alice'))
        self.assertEqual('REMOVED', self.store.submission('alice', 'request-1')['submissionState'])
        self.assertEqual('NOT_OBSERVED', self.store.submission('bob', 'request-1')['submissionState'])
        self.assertIsNone(self.store.get(run['id'], 'alice'))
        with self.assertRaisesRegex(BenchmarkConflict, 'removed result'):
            self.create()
        self.assertTrue(self.create('new-request'))

    def test_terminal_evidence_is_immutable_and_active_history_not_removable(self):
        run = self.create()
        with self.assertRaises(BenchmarkConflict):
            self.store.remove(run['id'], 'alice')
        row = {'id': 'case', 'status': 'PASSED'}
        self.store.case(run['id'], 0, row)
        with self.assertRaises(BenchmarkConflict):
            self.store.case(run['id'], 0, dict(row, status='FAILED'))
        self.store.update(run['id'], state='COMPLETED', summary={'score': 0})
        with self.assertRaises(BenchmarkConflict):
            self.store.update(run['id'], summary={'score': 1000})
        with self.assertRaises(BenchmarkConflict):
            self.store.case(run['id'], 1, row)

    def test_restart_marks_partial_attempts_interrupted_without_replay(self):
        run = self.create()
        self.store.update(run['id'], state='RUNNING')
        self.store.case(run['id'], 0, {'status': 'RUNNING', 'attempts': [{'score': 350}]})
        self.store.recover()
        saved = self.store.get(run['id'])
        self.assertEqual('INTERRUPTED', saved['state'])
        self.assertEqual('INTERRUPTED', saved['cases'][0]['status'])
        self.assertEqual(350, saved['cases'][0]['attempts'][0]['score'])
        self.assertEqual([], self.store.pending())

    def test_restart_clears_uncommitted_case_score_but_keeps_completed_evidence(self):
        run = self.create()
        self.store.update(run['id'], state='RUNNING', summary={'score': 1000, 'qualified': True})
        attempt = {'id': 'pagination-1', 'status': 'PASSED', 'graded': True, 'resolved': True, 'score': 1000}
        # The runner saves a measured attempt before committing its final case.
        provisional = dict(attempt, status='RUNNING', graded=False, attempts=[attempt], repairBudget=1)
        completed = dict(attempt, id='idempotency-1', attempts=[dict(attempt, id='idempotency-1')])
        self.store.case(run['id'], 0, provisional)
        self.store.case(run['id'], 1, completed)

        self.store.recover()

        saved = self.store.get(run['id'])
        self.assertEqual('INTERRUPTED', saved['state'])
        self.assertIsNone(saved['summary']['score'])
        self.assertFalse(saved['summary']['qualified'])
        interrupted = saved['cases'][0]
        self.assertEqual('INTERRUPTED', interrupted['status'])
        self.assertFalse(interrupted['graded'])
        self.assertFalse(interrupted['resolved'])
        self.assertIsNone(interrupted['score'])
        self.assertEqual([attempt], interrupted['attempts'])
        self.assertEqual(completed, saved['cases'][1])
        self.assertEqual([], self.store.pending())

    def test_history_pages_do_not_leak_other_principals(self):
        for index in range(5):
            run = self.create(str(index), 'alice' if index % 2 else 'bob')
            self.store.update(run['id'], state='COMPLETED')
        first = self.store.history('alice', 1)
        second = self.store.history('alice', 1, first['next'])
        self.assertEqual(['3', '1'], [page['runs'][0]['request']['request_id'] for page in (first, second)])
        self.assertIsNone(second['next'])


class BenchmarkScoreTest(unittest.TestCase):
    def test_score_has_no_speed_influence_and_sums_the_displayed_dimensions(self):
        cases = full_cases('deep')
        for row in cases:
            if row['capstone']:
                row['dimensions'] = {key: 0.0 for key in CODING_WEIGHTS}
        score = coding_score(cases, 100, 'deep')
        self.assertAlmostEqual(800, score['score'])
        self.assertAlmostEqual(score['score'], sum(item['value'] for item in score['dimensions'].values()))
        for row in cases:
            row['totalMs'] = 10**12
        self.assertEqual(score, coding_score(cases, 100, 'deep'))

    def test_missing_evidence_keeps_fixed_weight_and_no_overall_score(self):
        cases = full_cases('quick')
        cases[0].update(graded=False, executed=False, status='ERROR')
        cases[0]['dimensions'] = {key: None for key in CODING_WEIGHTS}
        score = coding_score(cases, 12, 'quick')
        self.assertIsNone(score['score'])
        self.assertFalse(score['qualified'])
        self.assertAlmostEqual(1000 / 12, score['unknownWeight'])
        self.assertAlmostEqual(11000 / 12, score['earned'])
        self.assertEqual(1000, score['possible'])
        self.assertEqual(12, sum(score['coverage']['outcomes'].values()))

    def test_repaired_success_is_not_first_submission_success(self):
        cases = full_cases('quick')
        cases[0]['firstSubmissionResolved'] = False
        cases[0]['attempts'][0]['dimensions'] = {key: 0.0 for key in CODING_WEIGHTS}
        score = coding_score(cases, 12, 'quick')
        self.assertAlmostEqual(11 / 12, score['passAtOne'])
        self.assertEqual(1, score['finalResolvedRate'])
        self.assertAlmostEqual(916.67, score['firstSubmissionScore'])
        del cases[1]['firstSubmissionResolved']
        self.assertIsNone(coding_score(cases, 12, 'quick')['passAtOne'])

    def test_duplicate_identity_or_integrity_violation_cannot_qualify(self):
        cases = full_cases('quick')
        cases[-1] = cases[0]
        self.assertIsNone(coding_score(cases, 12, 'quick')['score'])
        cases = full_cases('quick')
        cases[0]['integrity'] = 'VIOLATION'
        self.assertFalse(coding_score(cases, 12, 'quick')['qualified'])

    def test_pair_failure_and_missing_model_receipt_never_claim_speedup(self):
        rows = [{'pair': pair, 'arm': arm, 'status': 'PASSED', 'correct': 5, 'planned': 5,
                 'totalMs': 100 if arm == 'sequential' else 50, 'identityVerified': True}
                for pair in range(10) for arm in ('sequential', 'concurrent')]
        self.assertEqual(2, paired_summary(rows, 10)['speedup'])
        rows[0]['identityVerified'] = False
        summary = paired_summary(rows, 10)
        self.assertFalse(summary['qualified'])
        self.assertIsNone(summary['speedup'])
        self.assertEqual(9, summary['pairedCount'])
        rows[0]['identityVerified'] = True
        rows[1]['correct'] = 4
        self.assertIsNone(paired_summary(rows, 10)['speedup'])
        self.assertEqual('NOT_VERIFIED', summary['nativeInferenceBatching'])

    def test_requested_image_and_harness_paths_are_not_remote_options(self):
        with self.assertRaises(ValueError):
            validate_request(dict(request(), sandbox_image='unsafe:latest'))


class CodingFixtureCalibrationTest(unittest.TestCase):
    def test_counts_identify_seeded_trials_separately_from_distinct_problems(self):
        for preset, trials, problems in [('quick', 12, 12), ('standard', 36, 12), ('deep', 100, 16)]:
            tasks = fixtures.suite(preset)
            self.assertEqual(trials, len(tasks))
            self.assertEqual(problems, len({task['problemId'] for task in tasks}))

    def test_every_individual_shipped_defect_is_caught_with_other_files_fixed(self):
        tasks = fixtures.suite('quick') + fixtures.suite('deep')[-4:]
        for task in tasks:
            for path in task['sources']:
                with self.subTest(task=task['id'], defective=path):
                    mutant = dict(task, referenceSources=dict(task['referenceSources'], **{path: task['sources'][path]}))
                    rejected = False
                    for vector in task['vectors']:
                        before = deepcopy(vector)
                        expected = fixtures.expected(task, vector)
                        self.assertEqual(before, vector)
                        try:
                            actual = fixtures.trusted_fixture(mutant, vector)
                        except Exception:
                            rejected = True
                        else:
                            rejected |= not coding.same(expected, actual)
                    self.assertTrue(rejected)

    def test_gold_patches_execute_full_hidden_and_replay_vectors_and_grade(self):
        for task in fixtures.suite('quick') + fixtures.suite('deep')[-4:]:
            with self.subTest(task=task['id']):
                def execute(candidate, inputs, *_args, **_kwargs):
                    self.assertEqual(task['referenceSources'], candidate)
                    return {'executed': True, 'status': 'COMPLETED', 'outputs': [{'output': fixtures.expected(task, vector)} for vector in inputs]}
                result = coding.grade(task, gold_proposal(task), {'sandbox_image': 'python@sha256:' + 'a' * 64}, threading.Event(), execute=execute)
                self.assertTrue(result['resolved'])
                self.assertTrue(result['graded'])
                self.assertAlmostEqual(1000, result['score'])

    def test_container_launch_failure_is_ungraded_and_invalid_answer_is_observed_zero(self):
        task = fixtures.suite()[0]
        launch_failure = lambda *_a, **_kw: {'status': 'ERROR', 'executed': False, 'error': 'SANDBOX_EXECUTION_NOT_CONFIRMED'}
        result = coding.grade(task, gold_proposal(task), {'sandbox_image': 'image@sha256:' + 'a' * 64}, threading.Event(), execute=launch_failure)
        self.assertFalse(result['graded'])
        self.assertEqual('ERROR', result['status'])
        self.assertTrue(all(value is None for value in result['dimensions'].values()))
        invalid = coding.grade(task, 'not json', {}, threading.Event(), execute=Mock(side_effect=AssertionError('must not execute')))
        self.assertTrue(invalid['graded'])
        self.assertEqual(0, invalid['score'])
        self.assertFalse(invalid['resolved'])
        nested = coding.grade(task, '[' * 12000 + '0' + ']' * 12000, {}, threading.Event())
        self.assertTrue(nested['graded'])
        self.assertEqual(0, nested['score'])

    def test_candidate_cannot_replace_evaluator_or_write_outside_fixture(self):
        task = fixtures.suite()[0]
        payload = json.loads(gold_proposal(task))
        payload['changes'][0]['path'] = '../evaluator/runner.py'
        result = coding.grade(task, json.dumps(payload), {}, threading.Event(), execute=Mock(side_effect=AssertionError('must not execute')))
        self.assertEqual('VIOLATION', result['integrity'])
        self.assertFalse(result['executed'])

    def test_sandbox_engine_exit_is_not_candidate_failure(self):
        def launched(argv, cwd, timeout, maximum, destination=None):
            destination.write(b'docker daemon unavailable')
            return {'exit_code': 125, 'output_sha256': 'd', 'elapsed_ms': 10}
        with patch.object(sandbox, 'run_bounded', side_effect=launched), patch.object(sandbox.subprocess, 'run'):
            result = sandbox.execute({'service.py': 'def handle(x): return x'}, [{}],
                                     {'sandbox_image': 'image@sha256:' + 'a' * 64, 'sandbox_seconds': 5}, docker='docker')
        self.assertEqual('ERROR', result['status'])
        self.assertFalse(result['executed'])

    def test_candidate_exit_125_after_verified_start_is_a_real_failure(self):
        def launched(argv, cwd, timeout, maximum, destination=None):
            destination.write((sandbox.START_MARKER + '\n').encode())
            return {'exit_code': 125, 'output_sha256': 'd', 'elapsed_ms': 10}
        with patch.object(sandbox, 'run_bounded', side_effect=launched), patch.object(sandbox.subprocess, 'run'):
            result = sandbox.execute({'service.py': 'def handle(x): return x'}, [{}],
                                     {'sandbox_image': 'image@sha256:' + 'a' * 64, 'sandbox_seconds': 5}, docker='docker')
        self.assertEqual('FAILED', result['status'])
        self.assertTrue(result['executed'])

    def test_driver_has_one_scored_call_per_input_including_replays(self):
        # Execute only the trusted driver with a fixed test double, not candidate
        # or generated source. Alternation must be visible in returned outputs.
        calls = []
        def alternating(value):
            calls.append(value)
            return 'correct' if len(calls) % 2 else 'wrong'
        namespace = {}
        from io import StringIO
        from contextlib import redirect_stdout
        output = StringIO()
        with patch.dict(sys.modules, service=SimpleNamespace(handle=alternating)), \
                patch('builtins.open', return_value=StringIO('[1, 2, 2, 1]')), redirect_stdout(output):
            before = list(sys.path)
            try:
                exec(compile(sandbox.DRIVER, '<trusted-driver-test>', 'exec'), namespace)
            finally:
                sys.path[:] = before
        self.assertEqual(4, len(calls))
        payload = json.loads(output.getvalue().splitlines()[1])
        self.assertEqual(['correct', 'wrong', 'correct', 'wrong'], [row['output'] for row in payload['outputs']])

    def test_reference_output_count_must_match_before_dispatching_model(self):
        store = Mock()
        runner = BenchmarkRunner(store, 'http://localhost', threading.Event(), lambda: 'route', lambda: {'repair_attempts': 0})
        run = {'id': 'run', 'request': request(), 'provenance': {'executionIdentity': 'route'}}
        with patch.object(sandbox, 'preflight', return_value='docker'), \
                patch.object(sandbox, 'execute', return_value={'status': 'COMPLETED', 'outputs': []}), \
                patch.object(runner, 'measure_case', side_effect=AssertionError('must not dispatch')):
            with self.assertRaises(sandbox.SandboxUnavailable):
                runner.code(run)

    def test_coding_attempt_retains_observed_model_and_admitted_job_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            store = BenchmarkStore(Path(directory) / 'benchmarks.sqlite3')
            run, _ = store.create('owner', request(), {'executionIdentity': 'route'})
            runner = BenchmarkRunner(store, 'http://localhost', threading.Event(), lambda: 'route', lambda: {'repair_attempts': 0})
            task = fixtures.suite()[0]
            receipt = {'observedModel': 'selected', 'modelReceipt': 'REPORTED_IDENTIFIER',
                       'identityScope': 'HTTP_ROUTE_AND_REPORTED_IDENTIFIER', 'gatewayJobId': 'admitted-job'}
            def measured(*_args, **kwargs):
                kwargs['validate']('{}')
                return dict(receipt, firstMs=10, totalMs=20)
            result = full_cases('quick')[0]
            with patch.object(fixtures, 'suite', return_value=[task]), patch.object(sandbox, 'preflight', return_value='docker'), \
                    patch.object(sandbox, 'execute', return_value={'status': 'COMPLETED', 'outputs': [{'output': fixtures.expected(task, vector)} for vector in task['vectors']]}), \
                    patch.object(coding, 'grade', return_value=result), patch.object(runner, 'measure_case', side_effect=measured):
                runner.code(run)
            saved = store.get(run['id'])['cases'][0]
            for key, value in receipt.items():
                self.assertEqual(value, saved[key])
                self.assertEqual(value, saved['attempts'][0][key])


class BenchmarkLifecycleTest(unittest.TestCase):
    def make_service(self, directory):
        runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=str(Path(directory) / 'data' / 'jobs.sqlite3'),
                                  GATEWAY_DURABLE_JOBS=True, gateway_draining=False, LLAMA_BASE='http://model',
                                  gateway_service=SimpleNamespace(lock=threading.RLock(), draining=False, instance_id='instance', authorized=lambda owner: True))
        engineering = SimpleNamespace(authorized=lambda owner: True,
                                      store=SimpleNamespace(conversations=SimpleNamespace(gateway_id=lambda: 'installation')))
        return BenchmarkService(runtime, engineering)

    def test_lifespan_rebinds_store_before_recovery(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            service = self.make_service(first)
            service.runtime.GATEWAY_JOB_DB_PATH = str(Path(second) / 'data' / 'jobs.sqlite3')
            with patch.dict('os.environ', {}, clear=True):
                service.start()
                service.begin_drain()
                service.thread.join(3)
            self.assertFalse(service.thread.is_alive())
            self.assertEqual(Path(second) / 'data' / 'benchmarks.sqlite3', service.store.path)
            self.assertFalse((Path(first) / 'data' / 'benchmarks.sqlite3').exists())

    def test_disabled_durable_jobs_rebind_without_opening_any_benchmark_database(self):
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            service = self.make_service(first)
            service.runtime.GATEWAY_JOB_DB_PATH = str(Path(second) / 'data' / 'jobs.sqlite3')
            service.runtime.GATEWAY_DURABLE_JOBS = False
            with patch.dict('os.environ', {}, clear=True):
                service.start()
                self.assertEqual(0, service.snapshot()['queuedBenchmarks'])
            self.assertEqual(Path(second) / 'data' / 'benchmarks.sqlite3', service.store.path)
            self.assertEqual(Path(second), service.root)
            self.assertFalse((Path(first) / 'data' / 'benchmarks.sqlite3').exists())
            self.assertFalse((Path(second) / 'data' / 'benchmarks.sqlite3').exists())

    def test_packaged_build_provenance_uses_observed_source_and_actual_manifest_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            service = self.make_service(directory)
            service.runtime.gateway_debug = SimpleNamespace(provenance={'sourceCommit': 'b' * 40, 'buildStatus': 'verified_build_info'})
            raw = json.dumps({'sourceCommit': 'a' * 40}, indent=2).encode() + b'\n'
            with patch('benchmarks.service.Path.read_bytes', return_value=raw):
                packaged = service.provenance()
                self.assertEqual('b' * 40, packaged['sourceCommit'])
                self.assertEqual('verified_build_info', packaged['buildStatus'])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), packaged['manifestDigest'])
                del service.runtime.gateway_debug
                fallback = service.provenance()
                self.assertEqual('a' * 40, fallback['sourceCommit'])
                self.assertEqual('UNKNOWN', fallback['buildStatus'])
                self.assertEqual(packaged['manifestDigest'], fallback['manifestDigest'])

    def test_admission_obeys_shared_upgrade_lock_and_coding_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            service = self.make_service(directory)
            principal = Principal('alice', frozenset({'jobs', 'chat', 'tools'}))
            with self.assertRaises(PermissionError):
                service.create(principal, request())
            service.runtime.gateway_service.draining = True
            with self.assertRaises(BenchmarkConflict):
                service.create(Principal('admin', frozenset(), True), request())
            self.assertEqual([], service.store.pending())
            service.runtime.gateway_service.draining = False
            run = service.create(Principal('admin', frozenset(), True), request())
            self.assertEqual('QUEUED', run['state'])
            self.assertEqual('CANCELLED', service.cancel(run['id'], 'admin')['state'])

    def test_unknown_model_stop_is_interrupted_and_keeps_cancellation_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            service = self.make_service(directory)
            run = service.create(Principal('admin', frozenset(), True), request())
            with patch('benchmarks.service.BenchmarkRunner') as runner:
                runner.return_value.execute.side_effect = InterruptedError()
                runner.return_value.cancellation_receipts = [{'jobId': 'known-job', 'outcome': 'requested'}]
                service.run_one(run['id'])
            saved = service.store.get(run['id'])
            self.assertEqual('INTERRUPTED', saved['state'])
            self.assertEqual('CANCELLATION_UNCONFIRMED', saved['failure']['code'])

    def test_unknown_concurrent_outcome_fences_queued_trials_and_keeps_completed_pair_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            service = self.make_service(directory)
            run = service.create(Principal('admin', frozenset(), True), request(suite='batch_comparison', seed=1))
            running = threading.Event()
            observed_cancel = threading.Event()
            lock = threading.Lock()
            dispatched = []
            receipt = dict(passed=True, firstContentMs=1, totalMs=2, maximumContentGapMs=0,
                           backendTimings=None, usage=None, outputSha256='measured', finishReason='stop',
                           observedModel='selected', modelReceipt='REPORTED_IDENTIFIER',
                           identityScope='HTTP_ROUTE_AND_REPORTED_IDENTIFIER', gatewayJobId='completed')

            def measured(_post, _origin, _model, turns, **options):
                name = next(name for name, messages, _expected in benchmark.CASES if messages == turns)
                with lock:
                    dispatched.append(name)
                    ordinal = len(dispatched)
                # Warm-up and the first sequential arm have already completed.
                if ordinal <= 1 + len(benchmark.CASES):
                    return dict(receipt)
                if name == benchmark.CASES[0][0]:
                    self.assertTrue(running.wait(5), 'The second admitted request did not start')
                    raise BenchmarkInterrupted({'jobId': 'unsettled', 'outcome': 'unknown'})
                if name == benchmark.CASES[1][0]:
                    running.set()
                    if options['cancel_event'].wait(5):
                        observed_cancel.set()
                        raise BenchmarkInterrupted({'jobId': 'running', 'outcome': 'confirmed'})
                return dict(receipt)

            with patch.object(BenchmarkRunner, 'execute', BenchmarkRunner.paired), \
                    patch('benchmarks.runner.measure', side_effect=measured):
                service.run_one(run['id'])

            self.assertTrue(observed_cancel.is_set())
            self.assertCountEqual([entry[0] for entry in benchmark.CASES[:2]], dispatched[1 + len(benchmark.CASES):])
            saved = service.store.get(run['id'])
            self.assertEqual('INTERRUPTED', saved['state'])
            self.assertEqual('CANCELLATION_UNCONFIRMED', saved['failure']['code'])
            self.assertCountEqual([{'jobId': 'unsettled', 'outcome': 'unknown', 'case': benchmark.CASES[0][0]},
                                   {'jobId': 'running', 'outcome': 'confirmed', 'case': benchmark.CASES[1][0]}],
                                  [row for row in saved['failure']['cancellationReceipts'] if 'case' in row])
            self.assertEqual(1, len(saved['cases']))
            self.assertEqual('PASSED', saved['cases'][0]['status'])
            self.assertEqual('sequential', saved['cases'][0]['arm'])
            self.assertEqual(len(benchmark.CASES), len(saved['cases'][0]['samples']))
            self.assertFalse(saved['summary']['qualified'])
            self.assertEqual([], service.store.pending())

    def test_routes_scope_history_lookup_cancel_and_delete_to_admitted_owner(self):
        with tempfile.TemporaryDirectory() as directory:
            service = self.make_service(directory)
            app = FastAPI()
            @app.middleware('http')
            async def fixture_identity(http_request: Request, call_next):
                http_request.state.principal = Principal(http_request.headers.get('x-test-owner', 'alice'),
                                                        frozenset({'jobs', 'chat', 'tools', 'engineering'}))
                return await call_next(http_request)
            install_benchmark_routes(app, service)
            with TestClient(app) as client:
                created = client.post('/gateway/benchmarks', json=request())
                self.assertEqual(200, created.status_code)
                ident = created.json()['id']
                self.assertEqual('OBSERVED', client.get('/gateway/benchmarks?request_id=request-1').json()['submissionState'])
                stranger = {'x-test-owner': 'bob'}
                self.assertEqual([], client.get('/gateway/benchmarks', headers=stranger).json()['runs'])
                self.assertEqual('NOT_OBSERVED', client.get('/gateway/benchmarks?request_id=request-1', headers=stranger).json()['submissionState'])
                self.assertEqual(404, client.get('/gateway/benchmarks/' + ident, headers=stranger).status_code)
                self.assertEqual(404, client.post('/gateway/benchmarks/' + ident + '/cancel', headers=stranger).status_code)
                self.assertFalse(client.delete('/gateway/benchmarks/' + ident, headers=stranger).json()['removed'])
                self.assertEqual('CANCELLED', client.post('/gateway/benchmarks/' + ident + '/cancel').json()['state'])
                self.assertTrue(client.delete('/gateway/benchmarks/' + ident).json()['removed'])
                self.assertEqual('REMOVED', client.get('/gateway/benchmarks?request_id=request-1').json()['submissionState'])
                self.assertEqual(409, client.post('/gateway/benchmarks', json=request()).status_code)


class StreamCancellationTest(unittest.TestCase):
    def test_quiet_stream_cancel_closes_observer_and_cancels_observed_job(self):
        cancel, closed = threading.Event(), threading.Event()
        class Quiet:
            status_code = 200
            headers = {'X-Gateway-Job-ID': 'gateway-known'}
            def close(self):
                closed.set()
            def iter_lines(self, **_kwargs):
                cancel.set()
                self_closed = closed.wait(2)
                if not self_closed:
                    raise AssertionError('Quiet response was not closed')
                yield b''
        receipts = []
        def stop(job):
            receipts.append(job)
            return {'jobId': job, 'outcome': 'requested'}
        started = time.monotonic()
        with self.assertRaises(BenchmarkInterrupted) as error:
            measure(lambda *_a, **_kw: Quiet(), 'http://gateway', 'selected', [], cancel_event=cancel, cancel_request=stop)
        self.assertLess(time.monotonic() - started, 2)
        self.assertEqual(['gateway-known'], receipts)
        self.assertEqual('requested', error.exception.cancellation['outcome'])

    def test_missing_model_receipt_is_explicitly_route_only(self):
        class Stream:
            status_code = 200
            headers = {}
            def close(self): pass
            def iter_lines(self, **_kwargs):
                yield b'data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}'
                yield b'data: [DONE]'
        result = measure(lambda *_a, **_kw: Stream(), 'http://gateway', 'selected', [])
        self.assertIsNone(result['observedModel'])
        self.assertEqual('MISSING', result['modelReceipt'])
        self.assertEqual('HTTP_ROUTE_ONLY', result['identityScope'])


class DesktopSubmissionBindingTest(unittest.TestCase):
    def test_reconcile_reads_original_identity_without_mutation_or_replay(self):
        binding = {'origin': 'http://127.0.0.1:8090', 'gateway_id': 'original', 'principal_id': 'alice'}
        with patch.object(benchmark, 'service_request', return_value={'submissionState': 'NOT_OBSERVED'}) as read:
            self.assertEqual('NOT_OBSERVED', benchmark.reconcile_run('/root', 'request id', binding)['submissionState'])
        read.assert_called_once_with('/root', '?request_id=request+id', binding=binding)

    def test_uncertain_binding_survives_desktop_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            pending = dict(request(), _binding={'origin': 'http://gateway', 'gateway_id': 'g', 'principal_id': 'p'})
            benchmark.save_pending(directory, pending)
            recovered = benchmark.load_pending(directory)
            self.assertTrue(recovered['_uncertain'])
            self.assertEqual(pending['_binding'], recovered['_binding'])
            benchmark.save_pending(directory, None)
            self.assertIsNone(benchmark.load_pending(directory))

    def test_changed_endpoint_or_principal_prevents_uncertain_resubmission(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            atomic_json(root / 'settings.json', DEFAULTS)
            binding = {'origin': 'http://127.0.0.1:8090', 'gateway_id': 'original', 'principal_id': 'alice'}
            with patch.object(benchmark, 'get_json', return_value={'gateway_id': 'original', 'principal_id': 'bob'}), \
                    patch.object(benchmark, 'client_session') as sessions:
                with self.assertRaisesRegex(ValueError, 'account changed'):
                    benchmark.start_run(root, 'selected', binding=binding)
                sessions.return_value.__enter__.return_value.request.assert_not_called()
            atomic_json(root / 'settings.json', dict(DEFAULTS, port=8091))
            with patch.object(benchmark, 'client_session', side_effect=AssertionError('must not connect')):
                with self.assertRaisesRegex(ValueError, 'original Gateway'):
                    benchmark.start_run(root, 'selected', binding=binding)

    def test_http_rejection_is_distinct_from_uncertain_transport(self):
        with tempfile.TemporaryDirectory() as directory:
            atomic_json(Path(directory) / 'settings.json', DEFAULTS)
            response = Mock(status_code=403)
            response.iter_content.return_value = [b'{"error":{"code":"benchmark_scope_required"}}']
            with patch.object(benchmark, 'get_json', return_value={'gateway_id': 'g', 'principal_id': 'p'}), \
                    patch.object(benchmark, 'client_session') as session:
                session.return_value.__enter__.return_value.request.return_value.__enter__.return_value = response
                with self.assertRaises(benchmark.BenchmarkRejected):
                    benchmark.start_run(directory, 'selected')


if __name__ == '__main__':
    unittest.main()
