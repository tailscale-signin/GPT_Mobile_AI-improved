"""Real selector and durable runner behavior; no provider or Docker is contacted."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import coding, context_experiment, fixtures, sandbox
from benchmarks.context_fixture import blob_sha, public_context_inputs
from benchmarks.contracts import content_digest, digest, validate_request
from benchmarks.runner import BenchmarkRunner
from benchmarks.store import BenchmarkStore
from benchmarks.verified_python import evaluate
from benchmark_selected_model import BenchmarkInvocationError
from desktop import benchmark


SETTINGS = {'sandbox_image': 'python@sha256:' + 'a' * 64, 'sandbox_seconds': 30, 'repair_attempts': 0}


def request(**changes):
    return validate_request(dict(request_id='context-experiment', model='exact-selected-model', suite='coding',
                                 preset='quick', seed=17, concurrency=2, context_variants=['CURRENT', 'TASK'],
                                 context_repeats=1, **changes))


def execution(candidate, inputs, *_args, **_kwargs):
    result = evaluate(candidate, inputs)
    return dict(result, status='COMPLETED', executed=True, outputDigest=digest(result), durationMs=1)


def grader_control(task):
    """Test-only output from the independent control; never an input to retrieval."""
    return json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                   'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                                  for path, source in task['sources'].items()],
                       'diagnosis': dict(task['sources']), 'tests': [],
                       'evidence': {'tests_executed': False, 'changed_files': list(task['sources'])}})


class PublicContextFixtureTests(unittest.TestCase):
    def setUp(self):
        self.task = fixtures.find_task('pagination-1')

    def test_hidden_oracle_vectors_and_repairs_never_enter_context_inputs(self):
        baseline = public_context_inputs(self.task)
        changed = deepcopy(self.task)
        changed.update(referenceSources={'secret.py': 'ORACLE_SENTINEL'}, vectors=['HIDDEN_VECTOR'],
                       expected='EXPECTED_SENTINEL', diagnosis='ANSWER_SENTINEL')
        self.assertEqual(baseline, public_context_inputs(changed))
        rendered = json.dumps(baseline)
        self.assertNotIn('ORACLE_SENTINEL', rendered)
        self.assertNotIn('HIDDEN_VECTOR', rendered)
        self.assertEqual(digest(baseline['files']), baseline['sourceSha'])
        self.assertTrue(all(blob_sha(row['content']) == row['blobSha'] for row in baseline['files']))
        for row in baseline['files']:
            if row['path'] in self.task['sources']:
                self.assertEqual(self.task['sources'][row['path']], row['content'])

    def test_context_arms_use_actual_production_selector_and_local_cache(self):
        values = {arm: context_experiment.prepare(self.task, arm, 12000) for arm in ('CURRENT', 'REFERENCE', 'TASK', 'SEMANTIC')}
        current, baseline = values['CURRENT']
        self.assertEqual('', current)
        self.assertEqual(0, baseline['coldSourceReads'])
        self.assertEqual(0, baseline['contextBytes'])
        reference, ref = values['REFERENCE']
        self.assertIn('Public fixture code reference', reference)
        self.assertEqual(0, ref['selectedSourceCount'])
        task, selected = values['TASK']
        self.assertGreater(selected['selectedSourceCount'], 0)
        self.assertGreater(selected['warmCacheHits'], 0)
        self.assertEqual(selected['coldSourceReads'], selected['warmSourceReads'])
        self.assertEqual(0, selected['repeatedSourceReads'])
        self.assertEqual(len(task.encode()), selected['contextBytes'])
        self.assertLessEqual(selected['contextBytes'], 12000)
        self.assertEqual('LEXICAL_FALLBACK', values['SEMANTIC'][1]['retrievalMode'])
        self.assertTrue(all(value[1]['manifestDigest'] == baseline['manifestDigest'] for value in values.values()))
        self.assertIsNone(selected['relevantFileRecall'])
        self.assertIsNone(selected['testRecall'])
        self.assertNotIn(self.task['sources']['core.py'], json.dumps(selected))

    def test_frozen_source_or_selector_change_fails_before_preparation(self):
        frozen = context_experiment.freeze([self.task], {'request': request(), 'provenance': {'executionIdentity': 'route'}}, SETTINGS)
        changed = deepcopy(self.task)
        changed['sources']['core.py'] += '\n# other branch\n'
        with self.assertRaisesRegex(ValueError, 'CHANGED_AFTER_FREEZE'):
            context_experiment.prepare(changed, 'TASK', 12000, frozen=frozen)
        with patch.object(context_experiment, 'selector_digest', return_value='0' * 64):
            with self.assertRaisesRegex(ValueError, 'CHANGED_AFTER_FREEZE'):
                context_experiment.prepare(self.task, 'TASK', 12000, frozen=frozen)
        with self.assertRaisesRegex(ValueError, 'CHANGED_AFTER_FREEZE'):
            context_experiment.prepare(self.task, 'REFERENCE', 12000, frozen=frozen)

    def test_freeze_requires_execution_and_task_grader_identities(self):
        for execution_identity in (None, '', ' ', False, 12):
            with self.subTest(execution_identity=execution_identity), self.assertRaises(ValueError):
                context_experiment.freeze([self.task], {'request': request(), 'provenance': {'executionIdentity': execution_identity}}, SETTINGS)
        for key in ('taskDigest', 'graderDigest'):
            for identity in (None, '', False, 'x' * 64, 'a' * 63):
                with self.subTest(key=key, identity=identity), self.assertRaises(ValueError):
                    context_experiment.freeze([dict(self.task, **{key: identity})],
                                              {'request': request(), 'provenance': {'executionIdentity': 'route'}}, SETTINGS)

    def test_changed_task_or_grader_cannot_prepare_a_frozen_trial(self):
        frozen = context_experiment.freeze([self.task], {'request': request(), 'provenance': {'executionIdentity': 'route'}}, SETTINGS)
        for key in ('taskDigest', 'graderDigest'):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'CHANGED_AFTER_FREEZE'):
                context_experiment.prepare(dict(self.task, **{key: '0' * 64}), 'TASK', 12000, frozen=frozen)

    def test_cancelled_preparation_does_not_return_dispatchable_context(self):
        with self.assertRaises(InterruptedError):
            context_experiment.prepare(self.task, 'TASK', 12000, cancelled=lambda: True)

    def test_unsafe_or_reserved_source_manifest_is_rejected(self):
        for path in ('../secrets', '/private', 'src/../code.py', 'docs/architecture/feature-map.json'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                public_context_inputs(dict(self.task, sources={path: 'source'}))

    def test_arm_validation_and_alternating_order_preserve_exact_model(self):
        normalized = request()
        normalized['context_repeats'] = 2
        self.assertEqual(['CURRENT', 'TASK', 'TASK', 'CURRENT'],
                         [arm for _, arm, _ in context_experiment.trials([self.task], normalized)])
        for arms in (['TASK'], ['TASK', 'CURRENT'], ['CURRENT', 'CURRENT'], ['CURRENT', 'REMOTE_EMBEDDING']):
            with self.subTest(arms=arms), self.assertRaises(ValueError):
                validate_request(dict(normalized, context_variants=arms))
        for change in ({'context_repeats': True}, {'context_budget_bytes': 2048}, {'suite': 'quick_check'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_request(dict(normalized, **change))
        with self.assertRaisesRegex(ValueError, '1000 task episodes'):
            validate_request(dict(normalized, preset='deep', context_variants=['CURRENT', 'REFERENCE', 'TASK', 'SEMANTIC'], context_repeats=3))
        legacy = validate_request({key: value for key, value in normalized.items() if not key.startswith('context_')})
        self.assertFalse(any(key.startswith('context_') for key in legacy))
        self.assertEqual('exact-selected-model', normalized['model'])


class ContextExperimentEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.task = fixtures.find_task('pagination-1')
        self.request = request()
        self.frozen = context_experiment.freeze([self.task], {'request': self.request, 'provenance': {'executionIdentity': 'route'}}, SETTINGS)
        self.cases = []
        for arm in self.request['context_variants']:
            supplement, receipt = context_experiment.prepare(self.task, arm, 12000, frozen=self.frozen)
            receipt.update(dispatched=True, promptDigest=content_digest(fixtures.prompt(self.task) + supplement))
            outcome = coding.grade(self.task, grader_control(self.task), SETTINGS, threading.Event(), execute=execution)
            self.assertTrue(outcome['qualified'])
            self.assertTrue(outcome['resolved'])
            outcome['timing'] = {'inputTokens': 41, 'outputTokens': 17, 'totalMs': 12}
            outcome['outputAllowance'] = 4096
            self.cases.append(dict(outcome, id=self.task['id'] + ':' + arm + ':0', taskId=self.task['id'],
                                   family=self.task['family'], contextVariant=arm, contextRepeat=0,
                                   contextReceipt=receipt, finalResolved=True, attempts=[deepcopy(outcome)]))

    def summarize(self, cases=None):
        return context_experiment.summary(self.cases if cases is None else cases, 2, self.request, self.frozen)

    def test_bound_pair_uses_grader_and_never_fabricates_cost_or_recall(self):
        report = self.summarize()
        pair = report['contextComparisons'][0]
        self.assertEqual(1, pair['matchedPairs'])
        self.assertEqual(0, pair['differencePercentagePoints'])
        self.assertEqual('INSUFFICIENT_EVIDENCE', pair['state'])
        self.assertIsNone(report['score'])
        self.assertIsNone(report['contextArms']['TASK']['apiCost'])
        self.assertIsNone(report['contextArms']['TASK']['falseCompletionCount'])
        self.assertEqual(41, report['contextArms']['TASK']['inputTokens'])

    def test_stale_source_reference_index_and_experiment_do_not_pair(self):
        for field in ('sourceSha', 'manifestDigest', 'referenceDigest', 'indexDigest', 'experimentDigest'):
            cases = deepcopy(self.cases)
            cases[1]['contextReceipt'][field] = '0' * 64
            with self.subTest(field=field):
                self.assertEqual(0, self.summarize(cases)['contextComparisons'][0]['matchedPairs'])

    def test_semantic_fallback_cannot_hide_a_changed_experiment_identity(self):
        self.request['context_variants'] = ['CURRENT', 'SEMANTIC']
        cases = deepcopy(self.cases)
        cases[1]['contextVariant'] = 'SEMANTIC'
        cases[1]['contextReceipt'].update(variant='SEMANTIC', retrievalMode='LEXICAL_FALLBACK')
        self.assertEqual('IDENTITY_MISMATCH', self.summarize(cases)['contextComparisons'][0]['state'])

    def test_duplicate_trial_and_unknown_final_attempt_cannot_qualify(self):
        self.assertEqual(0, self.summarize(self.cases + [deepcopy(self.cases[1])])['contextComparisons'][0]['matchedPairs'])
        cases = deepcopy(self.cases)
        cases[1]['attempts'].append(dict(executed=None, graded=False, status='ERROR', resolved=False))
        cases[1]['finalResolved'] = False
        self.assertEqual(0, self.summarize(cases)['contextComparisons'][0]['matchedPairs'])
        self.assertIsNone(self.summarize(cases)['contextArms']['TASK']['inputTokens'])

    def test_known_invalid_patch_counts_as_failure_without_faking_execution(self):
        cases = deepcopy(self.cases)
        rejected = coding.grade(self.task, '{}', SETTINGS, threading.Event(), execute=execution)
        self.assertFalse(rejected['qualified'])
        self.assertFalse(rejected['executed'])
        self.assertTrue(rejected['outcomeKnown'])
        rejected['outputAllowance'] = 4096
        cases[1].update(attempts=[rejected], finalResolved=False)
        pair = self.summarize(cases)['contextComparisons'][0]
        self.assertEqual(1, pair['matchedPairs'])
        self.assertEqual(-100, pair['differencePercentagePoints'])

    def test_unknown_task_with_missing_source_receipts_cannot_pair(self):
        cases = deepcopy(self.cases)
        for case in cases:
            case['taskId'] = 'never-frozen-task'
            for key in ('manifestDigest', 'sourceSha', 'referenceDigest', 'indexDigest'):
                case['contextReceipt'].pop(key)
        pair = self.summarize(cases)['contextComparisons'][0]
        self.assertEqual(0, pair['matchedPairs'])
        self.assertIsNone(pair['differencePercentagePoints'])
        self.assertIsNone(pair['interval95'])

    def test_executed_candidate_bounds_remain_known_model_failures(self):
        for code in ('CANDIDATE_SYNTAX_ERROR', 'CANDIDATE_EXECUTION_ERROR', 'CANDIDATE_STEP_LIMIT',
                     'CANDIDATE_DEADLINE', 'CANDIDATE_OUTPUT_LIMIT'):
            with self.subTest(code=code):
                outcome = coding.grade(self.task, grader_control(self.task), SETTINGS, threading.Event(),
                    execute=lambda *_args, **_kwargs: dict(executed=True, status='ERROR', error=code))
                self.assertTrue(outcome['executed'])
                self.assertTrue(outcome['outcomeKnown'])
                self.assertFalse(outcome['qualified'])
                outcome['outputAllowance'] = 4096
                cases = deepcopy(self.cases)
                cases[1].update(attempts=[outcome], finalResolved=False)
                pair = self.summarize(cases)['contextComparisons'][0]
                self.assertEqual(1, pair['matchedPairs'])
                self.assertEqual(-100, pair['differencePercentagePoints'])

    def test_incomplete_frozen_task_manifest_or_source_identity_cannot_pair(self):
        for missing in ('taskManifest', 'contextManifestDigests', 'sourceDigests', 'referenceDigests'):
            with self.subTest(missing=missing):
                frozen, cases = deepcopy(self.frozen), deepcopy(self.cases)
                if missing == 'taskManifest':
                    frozen.pop(missing)
                else:
                    frozen[missing].pop(self.task['id'])
                    for case in cases:
                        for key in ('manifestDigest', 'sourceSha', 'referenceDigest', 'indexDigest'):
                            case['contextReceipt'].pop(key)
                frozen['digest'] = digest({key: value for key, value in frozen.items() if key != 'digest'})
                for case in cases:
                    case['contextReceipt']['experimentDigest'] = frozen['digest']
                report = context_experiment.summary(cases, 2, self.request, frozen)
                pair = report['contextComparisons'][0]
                self.assertEqual('IDENTITY_MISMATCH', pair['state'])
                self.assertEqual(0, pair['matchedPairs'])
                self.assertIsNone(pair['differencePercentagePoints'])
                self.assertIsNone(pair['interval95'])

    def test_case_family_changes_cannot_inflate_independent_trials(self):
        tasks = fixtures.suite('quick', 17)[:4]
        selected = dict(self.request, context_repeats=3)
        frozen = context_experiment.freeze(tasks, {'request': selected, 'provenance': {'executionIdentity': 'route'}}, SETTINGS)
        cases = []
        for task, arm, repeat in context_experiment.trials(tasks, selected):
            supplement, receipt = context_experiment.prepare(task, arm, 12000, frozen=frozen)
            receipt.update(dispatched=True, promptDigest=content_digest(fixtures.prompt(task) + supplement))
            proposal = json.loads(grader_control(task))
            if arm == 'CURRENT':
                for change in proposal['changes']:
                    change['edits'][0]['new'] = task['sources'][change['path']] + '\n# retains faulty behavior\n'
            outcome = coding.grade(task, json.dumps(proposal), SETTINGS, threading.Event(), execute=execution)
            self.assertTrue(outcome['qualified'])
            self.assertEqual(arm == 'TASK', outcome['resolved'])
            outcome['outputAllowance'] = 4096
            cases.append(dict(outcome, taskId=task['id'], contextVariant=arm, contextRepeat=repeat,
                              contextReceipt=receipt, finalResolved=outcome['resolved'], attempts=[deepcopy(outcome)]))
        before = context_experiment.summary(cases, len(cases), selected, frozen)['contextComparisons'][0]
        self.assertEqual(12, before['matchedPairs'])
        self.assertEqual(4, before['independentFamilies'])
        self.assertEqual('INSUFFICIENT_EVIDENCE', before['state'])
        for case in cases:
            case['family'] = f'{case["taskId"]}-repeat-{case["contextRepeat"]}'
        after = context_experiment.summary(cases, len(cases), selected, frozen)['contextComparisons'][0]
        self.assertEqual(before, after)

    def test_unknown_evaluation_and_contradictory_success_do_not_resolve(self):
        def unverified(candidate, inputs, *args, **kwargs):
            result = execution(candidate, inputs, *args, **kwargs)
            result.pop('verification')
            return result
        proposal = json.loads(grader_control(self.task))
        for change in proposal['changes']:
            change['edits'][0]['new'] = self.task['sources'][change['path']] + '\n# retains faulty behavior\n'
        unknown = coding.grade(self.task, json.dumps(proposal), SETTINGS, threading.Event(), execute=unverified)
        self.assertEqual('FAILED', unknown['status'])
        self.assertFalse(unknown['qualified'])
        self.assertFalse(unknown['outcomeKnown'])
        cases = deepcopy(self.cases)
        cases[1].update(attempts=[unknown], finalResolved=False)
        self.assertEqual(0, self.summarize(cases)['contextComparisons'][0]['matchedPairs'])
        for change in ({'qualified': False}, {'outcomeKnown': False}, {'status': 'FAILED'}):
            with self.subTest(change=change):
                cases = deepcopy(self.cases)
                cases[1]['attempts'][-1].update(change)
                pair = self.summarize(cases)['contextComparisons'][0]
                self.assertEqual(0, pair['matchedPairs'])
                self.assertIsNone(pair['differencePercentagePoints'])
                self.assertIsNone(pair['interval95'])

    def test_frozen_route_configuration_and_task_identities_must_be_complete(self):
        fields = ('executionIdentity', 'settingsDigest', 'selectorDigest', 'taskDigest', 'graderDigest')
        for field in fields:
            for invalid in (None, '', False, 42, ' ', 'not-a-digest'):
                if field == 'executionIdentity' and invalid == 'not-a-digest':
                    continue
                with self.subTest(field=field, invalid=invalid):
                    frozen, cases = deepcopy(self.frozen), deepcopy(self.cases)
                    target = frozen['taskManifest'][0] if field in {'taskDigest', 'graderDigest'} else frozen
                    if invalid is None:
                        target.pop(field)
                    else:
                        target[field] = invalid
                    frozen['taskManifestDigest'] = digest(frozen['taskManifest'])
                    frozen['digest'] = digest({key: value for key, value in frozen.items() if key != 'digest'})
                    for case in cases:
                        case['contextReceipt']['experimentDigest'] = frozen['digest']
                    report = context_experiment.summary(cases, 2, self.request, frozen)
                    pair = report['contextComparisons'][0]
                    self.assertEqual('IDENTITY_MISMATCH', pair['state'])
                    self.assertEqual(0, pair['matchedPairs'])
                    self.assertIsNone(pair['differencePercentagePoints'])
                    self.assertIsNone(pair['interval95'])

    def test_case_and_delivered_task_grader_identities_must_match_the_manifest(self):
        for field in ('taskDigest', 'graderDigest'):
            for location in ('case', 'delivered', 'both'):
                for identity in (None, '0' * 64):
                    with self.subTest(field=field, location=location, identity=identity):
                        cases = deepcopy(self.cases)
                        targets = ([cases[1]] if location == 'case' else [cases[1]['attempts'][-1]] if location == 'delivered'
                                   else [cases[1], cases[1]['attempts'][-1]])
                        for target in targets:
                            if identity is None:
                                target.pop(field)
                            else:
                                target[field] = identity
                        pair = self.summarize(cases)['contextComparisons'][0]
                        self.assertEqual(0, pair['matchedPairs'])
                        self.assertIsNone(pair['differencePercentagePoints'])
                        self.assertIsNone(pair['interval95'])

    def test_frozen_tasks_and_repeats_own_coverage_even_when_caller_plan_is_shorter(self):
        tasks = fixtures.suite('quick', 17)
        selected = dict(self.request, context_repeats=3)
        frozen = context_experiment.freeze(tasks, {'request': selected, 'provenance': {'executionIdentity': 'route'}}, SETTINGS)
        cases = []
        for task in tasks:
            outcome = coding.grade(task, grader_control(task), SETTINGS, threading.Event(), execute=execution)
            self.assertTrue(outcome['resolved'])
            outcome['outputAllowance'] = 4096
            for arm in selected['context_variants']:
                supplement, receipt = context_experiment.prepare(task, arm, 12000, frozen=frozen)
                receipt.update(dispatched=True, promptDigest=content_digest(fixtures.prompt(task) + supplement))
                for repeat in range(3):
                    cases.append(dict(outcome, taskId=task['id'], contextVariant=arm, contextRepeat=repeat,
                                      contextReceipt=deepcopy(receipt), finalResolved=True, attempts=[deepcopy(outcome)]))
        partial = [case for case in cases if case['contextRepeat'] == 0]
        report = context_experiment.summary(partial, len(partial), selected, frozen)
        pair = report['contextComparisons'][0]
        self.assertEqual(12, pair['matchedPairs'])
        self.assertEqual(36, pair['plannedPairs'])
        self.assertEqual(72, report['planned'])
        self.assertEqual('INCOMPLETE', pair['state'])
        complete = context_experiment.summary(cases, 2, selected, frozen)
        pair = complete['contextComparisons'][0]
        self.assertEqual(36, pair['matchedPairs'])
        self.assertEqual(36, pair['plannedPairs'])
        self.assertEqual(12, pair['independentFamilies'])
        self.assertEqual('DESCRIPTIVE_PAIRED_RESULT', pair['state'])
        self.assertEqual(72, complete['knownOutcomes'])

    def test_unobserved_or_unequal_output_allowances_keep_effort_without_paired_inference(self):
        for allowance in (None, False, 0, -1, 4096.0, '4096', 1024):
            with self.subTest(allowance=allowance):
                cases = deepcopy(self.cases)
                if allowance is None:
                    cases[1]['attempts'][-1].pop('outputAllowance')
                else:
                    cases[1]['attempts'][-1]['outputAllowance'] = allowance
                report = self.summarize(cases)
                pair = report['contextComparisons'][0]
                self.assertEqual(1, pair['matchedPairs'])
                self.assertEqual(0, pair['budgetMatchedPairs'])
                self.assertEqual('OUTPUT_BUDGET_MISMATCH' if allowance == 1024 else 'OUTPUT_BUDGET_UNKNOWN', pair['state'])
                self.assertIsNone(pair['differencePercentagePoints'])
                self.assertIsNone(pair['interval95'])
                self.assertEqual(2, report['knownOutcomes'])
                self.assertEqual(2, report['resolved'])
                for arm in ('CURRENT', 'TASK'):
                    self.assertEqual(1, report['contextArms'][arm]['recorded'])
                    self.assertEqual(1, report['contextArms'][arm]['evaluated'])
                    self.assertEqual(41, report['contextArms'][arm]['inputTokens'])
                    self.assertEqual(17, report['contextArms'][arm]['outputTokens'])
                    self.assertEqual(12, report['contextArms'][arm]['generationMs'])

    def test_adapted_earlier_attempt_is_not_hidden_by_equal_final_allowances(self):
        rejected = coding.grade(self.task, '{}', SETTINGS, threading.Event(), execute=execution)
        rejected.update(outputAllowance=4096, timing={'inputTokens': 7, 'outputTokens': 3, 'totalMs': 5})
        cases = deepcopy(self.cases)
        for case in cases:
            case['attempts'][-1]['outputAllowance'] = 1024
        cases[1]['attempts'].insert(0, rejected)
        report = self.summarize(cases)
        self.assertEqual('OUTPUT_BUDGET_MISMATCH', report['contextComparisons'][0]['state'])
        self.assertIsNone(report['contextComparisons'][0]['differencePercentagePoints'])
        self.assertEqual(48, report['contextArms']['TASK']['inputTokens'])
        self.assertEqual(20, report['contextArms']['TASK']['outputTokens'])
        self.assertEqual(17, report['contextArms']['TASK']['generationMs'])
        self.assertEqual([1024, 4096], report['contextArms']['TASK']['recordedOutputAllowances'])


class ContextExperimentRunnerTests(unittest.TestCase):
    def test_real_runner_injects_context_without_switching_model_and_exports_bound_arms(self):
        task = fixtures.find_task('pagination-1')
        with tempfile.TemporaryDirectory() as directory:
            store = BenchmarkStore(Path(directory) / 'benchmarks.sqlite3')
            run, _ = store.create('owner', request(), {'executionIdentity': 'route', 'benchmarkSettingsDigest': digest(SETTINGS)})
            store.update(run['id'], state='RUNNING')
            runner = BenchmarkRunner(store, 'http://unused', threading.Event(), lambda: 'route', lambda: SETTINGS)
            runner.execute_sandbox = lambda _run, *args, **kwargs: execution(*args, **kwargs)
            prompts = []
            def model(selected, task_id, turns, *_args, **kwargs):
                self.assertEqual('exact-selected-model', selected['request']['model'])
                self.assertEqual(task['id'], task_id)
                prompts.append(turns[0]['content'])
                kwargs['on_admitted']('observed-job')
                active = store.get(run['id'])['activeCases'][0]
                self.assertIn(active['contextVariant'], active['id'])
                self.assertTrue(active['contextReceipt']['dispatched'])
                kwargs['validate'](grader_control(task))
                return dict(finishReason='stop', totalMs=20, firstMs=2, inputTokens=100, outputTokens=30,
                            observedModel='exact-selected-model', gatewayJobId='observed-job')
            with patch.object(fixtures, 'suite', return_value=[task]), patch.object(sandbox, 'preflight', return_value='docker'), \
                    patch.object(runner, 'measure_case', side_effect=model):
                report = runner.code(run)
            saved = store.get(run['id'])
            self.assertEqual(fixtures.prompt(task), prompts[0])
            self.assertTrue(prompts[1].startswith(prompts[0]))
            self.assertGreater(len(prompts[1]), len(prompts[0]))
            self.assertEqual(2, len(saved['cases']))
            self.assertEqual([], saved['pendingCaseIds'])
            self.assertEqual(1, report['contextComparisons'][0]['matchedPairs'])
            self.assertEqual('MATCHED_RECORDED_REQUESTS', report['contextComparisons'][0]['outputBudgetStatus'])
            self.assertEqual(2, report['resolved'])
            self.assertTrue(all(case['firstVerifiedPatchMs'] is not None for case in saved['cases']))
            self.assertEqual(report['contextExperimentIdentity'], saved['summary']['contextExperimentIdentity'])
            self.assertNotIn('Repository reference for the same', json.dumps(saved))

    def test_real_runner_retains_admission_adaptation_without_an_equal_budget_claim(self):
        task = fixtures.find_task('pagination-1')
        with tempfile.TemporaryDirectory() as directory:
            store = BenchmarkStore(Path(directory) / 'benchmarks.sqlite3')
            run, _ = store.create('owner', request(), {'executionIdentity': 'route', 'benchmarkSettingsDigest': digest(SETTINGS)})
            store.update(run['id'], state='RUNNING')
            runner = BenchmarkRunner(store, 'http://unused', threading.Event(), lambda: 'route', lambda: SETTINGS)
            runner.execute_sandbox = lambda _run, *args, **kwargs: execution(*args, **kwargs)
            sent = []
            def model(_selected, _task_id, _turns, *_args, **kwargs):
                arm = store.get(run['id'])['activeCases'][0]['contextVariant']
                cap = kwargs['output_tokens']
                sent.append((arm, cap))
                if arm == 'TASK' and cap > 1024:
                    raise BenchmarkInvocationError('BENCHMARK_HTTP_REJECTED', status=413,
                                                   admission_code='context_length_exceeded', pre_dispatch=True)
                kwargs['on_admitted']('job-' + arm)
                kwargs['validate'](grader_control(task))
                return dict(finishReason='stop', totalMs=20, firstMs=2, inputTokens=100, outputTokens=30,
                            observedModel=run['request']['model'], gatewayJobId='job-' + arm)
            with patch.object(fixtures, 'suite', return_value=[task]), patch.object(sandbox, 'preflight', return_value='docker'), \
                    patch.object(runner, 'measure_case', side_effect=model):
                report = runner.code(run)
            saved = store.get(run['id'])
            self.assertEqual([('CURRENT', 4096), ('TASK', 4096), ('TASK', 2048), ('TASK', 1024)], sent)
            self.assertEqual([4096, 1024], [case['attempts'][-1]['outputAllowance'] for case in saved['cases']])
            self.assertEqual([4096, 2048], [row['outputAllowance'] for row in saved['cases'][1]['attempts'][-1]['admissionRejections']])
            self.assertEqual(2, report['knownOutcomes'])
            self.assertEqual(2, report['resolved'])
            pair = report['contextComparisons'][0]
            self.assertEqual('OUTPUT_BUDGET_MISMATCH', pair['state'])
            self.assertEqual(1, pair['matchedPairs'])
            self.assertEqual(0, pair['budgetMatchedPairs'])
            self.assertIsNone(pair['differencePercentagePoints'])
            self.assertIsNone(pair['interval95'])
            self.assertNotIn('SAME_MODEL_SOURCE_TASKS_BUDGET', report['scope'])
            self.assertEqual('RECORDED_REQUESTED_CAP_BEFORE_SELECTED_BINDING_CLAMP', report['outputBudgetMeasurement'])
            for arm, allowance in (('CURRENT', 4096), ('TASK', 1024)):
                self.assertEqual([allowance], report['contextArms'][arm]['recordedOutputAllowances'])
                self.assertEqual(100, report['contextArms'][arm]['inputTokens'])
                self.assertEqual(30, report['contextArms'][arm]['outputTokens'])
                self.assertEqual(20, report['contextArms'][arm]['generationMs'])

    def test_desktop_submission_preserves_context_identity_and_exact_selection(self):
        with patch.object(benchmark, 'service_request', return_value={'id': 'accepted'}) as submit:
            benchmark.start_run(Path('/unused'), 'exact-selected-model', 'coding', context_variants=['CURRENT', 'SEMANTIC'], context_repeats=3)
        payload = submit.call_args.kwargs['payload']
        self.assertEqual('exact-selected-model', payload['model'])
        self.assertEqual(['CURRENT', 'SEMANTIC'], payload['context_variants'])
        self.assertEqual(3, payload['context_repeats'])
        self.assertEqual(12000, payload['context_budget_bytes'])

    def test_native_bridge_freezes_context_defaults_before_uncertain_admission(self):
        from desktop.native_bridge import dispatch
        pending = []
        binding = dict(origin='http://selected', gateway_id='gateway', principal_id='owner')
        with patch.object(benchmark, 'load_pending', return_value=None), patch.object(benchmark, 'execution_binding', return_value=binding), \
                patch.object(benchmark, 'save_pending', side_effect=lambda _root, value: pending.append(value)), \
                patch.object(benchmark, 'service_request', return_value={'id': 'accepted'}) as submit:
            result = dispatch(Path('/unused'), 'benchmark-start', dict(request_id='context-native', model='exact-selected-model',
                              suite='coding', context_variants=['CURRENT', 'REFERENCE', 'TASK', 'SEMANTIC']), None)
        self.assertEqual('accepted', result['id'])
        self.assertEqual(3, pending[0]['context_repeats'])
        self.assertEqual(12000, pending[0]['context_budget_bytes'])
        self.assertEqual(pending[0]['context_variants'], submit.call_args.kwargs['payload']['context_variants'])
        self.assertEqual('exact-selected-model', submit.call_args.kwargs['payload']['model'])
        self.assertIsNone(pending[-1])


if __name__ == '__main__':
    unittest.main()
