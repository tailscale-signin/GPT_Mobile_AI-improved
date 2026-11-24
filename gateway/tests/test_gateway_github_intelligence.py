from copy import deepcopy
import json
from unittest import TestCase
from types import SimpleNamespace

from benchmarks.engineering_qualification import PRODUCTION_SCHEMA, SCENARIOS, run_manifest, snapshot
from benchmarks.github_intelligence import case_observations, compare, score
from v14.batch import digest
from v14.engineering_report import build_report


def report(model='model-a', permissions='9' * 64, tool_policy=('SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET'), initial_base='a' * 40,
           conversation_context=..., conversation_id=None, memory_mode='SESSION_ONLY', user_instructions=...):
    base, head = 'a' * 40, 'b' * 40
    route = dict(model=model, profile_uid=model, profile_revision='1', binding_id=model, endpoint_identity='endpoint',
                 base_sha=base, settings_digest='c' * 64)
    cases = []
    for case in SCENARIOS:
        ident = model + case
        failing = case in ('terminal_ci_blocker', 'post_merge_failure')
        requirement = {'id': case, 'checks': ['Unit'], 'paths': ['src/café.kt']}
        data = {'actor_id': 12, 'initial_base_sha': initial_base, 'initial_permission_revision': permissions, 'admission': {'policy_sha256': 'c' * 64, 'development_environment': {'image': 'fixture@sha256:' + 'd' * 64}},
                'head_sha': head, 'context_compaction': {'source_reference': 'retained'},
                'workspace_source_reads': {'paths': [digest([base, 'src/café.kt'])], 'ranges': ['one', 'two'],
                                           'total': 4, 'complete': True},
                'completion_verification': {'requirements': [dict(requirement, head_sha=head, receipt_ids=['r'],
                                                                  status='BEHAVIOR_VERIFIED')]},
                'ci': {'ci_verdict': 'BLOCKED', 'pr': 3, 'head_sha': head, 'checks_blocked': True,
                       'checks': [{'name': 'Unit', 'status': 'ACTION_REQUIRED'}]}, 'pr': 3}
        if case == 'post_merge_failure':
            data.update(merge_sha='1' * 40, post_merge={'status': 'FAILED'})
        job = dict(id=ident, state='NEEDS_INPUT' if failing else 'COMPLETED', data=data, revision=3,
                   execution_binding=dict(tool_owner='GATEWAY', privacy_scope='LOCAL', required_capability_policy=list(tool_policy)),
                   request=dict(request_id=ident, model=model, base='benchmark/' + model + '/' + case + '/base',
                                branch='benchmark/' + model + '/' + case + '/head',
                                repository='org/repo', objective=case, source_sha=base, acceptance=[requirement]))
        if conversation_context is not ...:
            job['conversation_context'] = deepcopy(conversation_context)
        if conversation_id is not None:
            job['conversation_id'] = conversation_id
            job['request']['conversation_id'] = conversation_id
        if memory_mode is not ...:
            job['request']['memory_mode'] = memory_mode
        if user_instructions is not ...:
            data['user_instructions'] = deepcopy(user_instructions)
        data['final_report'] = build_report(job, job['state'], dict(passed=not failing, requirements=[],
                                           failures=['The required check did not pass'] if failing else []))
        cases.append(dict(job_id=ident, repository='org/repo', source_sha=base, scenario=case,
                          comparison_route=route, selected_route_verified=True, measurement_complete=True,
                          intelligence=case_observations(job, case, True, not failing)))
    return dict(schema=PRODUCTION_SCHEMA, test_repository='org/repo', cases=cases, expected_route=route,
                execution_scope='PRODUCTION_JOBS', fixture_results_included=False, comparisonEligible=True)


class GitHubIntelligenceTest(TestCase):
    @staticmethod
    def conversation_context():
        return {'messages': [
            {'role': 'user', 'content': 'fictional-private-context-instruction', 'revision': 1},
            {'role': 'assistant', 'content': 'fictional-private-context-answer', 'revision': 2},
        ], 'bounded_history': True, 'history_complete': False}

    def test_identical_effective_context_compares_across_distinct_conversation_ids_without_exporting_text(self):
        context = self.conversation_context()
        before = deepcopy(context)
        left = report('model-a', conversation_context=context, conversation_id='conversation-left')
        right = report('model-b', conversation_context=deepcopy(context), conversation_id='conversation-right')
        result = compare(left, right)
        self.assertEqual(0, result['rightMinusLeftPoints'])
        self.assertEqual(score(left)['taskSetDigest'], score(right)['taskSetDigest'])
        self.assertEqual(before, context)
        exported = json.dumps({'left': left, 'right': right, 'comparison': result})
        for private in ('fictional-private-context-instruction', 'fictional-private-context-answer',
                        'conversation-left', 'conversation-right'):
            self.assertNotIn(private, exported)

    def test_different_effective_context_content_roles_order_revisions_or_flags_cannot_compare(self):
        context = self.conversation_context()
        left = report('model-a', conversation_context=context)
        for label, change in (
            ('content', lambda c: c['messages'][0].update(content='different-fictional-instruction')),
            ('role', lambda c: c['messages'][0].update(role='assistant')),
            ('order', lambda c: c['messages'].reverse()),
            ('revision', lambda c: c['messages'][0].update(revision=3)),
            ('bounded history', lambda c: c.update(bounded_history=False)),
            ('complete history', lambda c: c.update(history_complete=True)),
        ):
            with self.subTest(change=label):
                changed = deepcopy(context)
                change(changed)
                right = report('model-b', conversation_context=changed)
                with self.assertRaises(ValueError):
                    compare(left, right)
                self.assertNotEqual(score(left)['taskSetDigest'], score(right)['taskSetDigest'])
        self.assertEqual(self.conversation_context(), context)

    def test_absent_and_null_context_share_effective_identity_without_matching_present_empty_context(self):
        absent = report('model-a')
        explicit_null = report('model-b', conversation_context=None)
        self.assertEqual(0, compare(absent, explicit_null)['rightMinusLeftPoints'])
        self.assertEqual(score(absent)['taskSetDigest'], score(explicit_null)['taskSetDigest'])
        for present in ({}, {'messages': []}):
            with self.subTest(context=present):
                right = report('model-b', conversation_context=present)
                with self.assertRaises(ValueError):
                    compare(absent, right)
                self.assertNotEqual(score(absent)['taskSetDigest'], score(right)['taskSetDigest'])

    def test_legacy_opaque_case_contracts_cannot_claim_current_context_qualified_comparison(self):
        legacy = report('model-b')
        for row in legacy['cases']:
            row['intelligence'].pop('caseContractSchema', None)
        self.assertIsNone(score(legacy)['pointsOutOf100'])
        self.assertIsNone(score(legacy)['taskSetDigest'])
        with self.assertRaises(ValueError):
            compare(report('model-a'), legacy)

    def test_unfrozen_persistent_memory_is_unavailable_instead_of_a_comparison_score(self):
        left = report('model-a', memory_mode='SESSION_ONLY')
        self.assertIsNotNone(score(left)['pointsOutOf100'])
        for mode in (..., 'HYBRID', 'PERSISTENT'):
            with self.subTest(memory_mode=mode):
                right = report('model-b', memory_mode=mode)
                measured = score(right)
                self.assertIsNone(measured['pointsOutOf100'])
                self.assertIsNone(measured['taskSetDigest'])
                self.assertTrue(all(d['points'] is None for d in measured['dimensions'].values()))
                with self.assertRaisesRegex(ValueError, 'session-only memory and identical admitted conversation and follow-up inputs'):
                    compare(left, right)

    def test_effective_user_instructions_bind_sent_fields_order_and_absence_without_exporting_text(self):
        instructions = [
            {'command_id': 'fictional-first-command', 'content': 'fictional-private-first-instruction'},
            {'command_id': 'fictional-next-command', 'content': 'fictional-private-next-instruction'},
        ]
        before = deepcopy(instructions)
        left = report('model-a', conversation_id='conversation-left', user_instructions=instructions)
        same = report('model-b', conversation_id='conversation-right', user_instructions=deepcopy(instructions))
        self.assertEqual(0, compare(left, same)['rightMinusLeftPoints'])
        for label, change in (
            ('content', lambda values: values[0].update(content='different-fictional-instruction')),
            ('command identity', lambda values: values[0].update(command_id='different-command')),
            ('order', lambda values: values.reverse()),
        ):
            with self.subTest(change=label):
                changed = deepcopy(instructions)
                change(changed)
                right = report('model-b', user_instructions=changed)
                with self.assertRaises(ValueError):
                    compare(left, right)
                self.assertNotEqual(score(left)['taskSetDigest'], score(right)['taskSetDigest'])
        absent = report('model-a')
        self.assertEqual(0, compare(absent, report('model-b', user_instructions=[]))['rightMinusLeftPoints'])
        with self.subTest(instructions='explicit null'), self.assertRaises(ValueError):
            compare(absent, report('model-b', user_instructions=None))
        self.assertEqual(before, instructions)
        exported = json.dumps({'left': left, 'right': same, 'comparison': compare(left, same)})
        for item in instructions:
            self.assertNotIn(item['command_id'], exported)
            self.assertNotIn(item['content'], exported)

    def test_real_observation_rubric_honors_weights_and_unicode_source_paths(self):
        result = score(report())
        self.assertEqual(100, sum(d['weight'] for d in result['dimensions'].values()))
        self.assertEqual(2.5, result['dimensions']['tool_efficiency']['points'])
        self.assertEqual(97.5, result['pointsOutOf100'])

    def test_identical_frozen_work_is_comparable_only_with_complete_distinct_runs(self):
        a, b = report(), report('model-b')
        self.assertEqual(0, compare(a, b)['rightMinusLeftPoints'])
        for mutate in (
            lambda r: r['cases'][0]['intelligence']['measures'].pop('code_correctness'),
            lambda r: r['cases'][0].update(job_id=a['cases'][0]['job_id']),
            lambda r: r['cases'][0]['intelligence'].update(caseContract='f' * 64),
            lambda r: r['cases'].pop(),
            lambda r: r.update(execution_scope='SIMULATION'),
            lambda r: r.update(fixture_results_included=True),
            lambda r: r['cases'][0].update(selected_route_verified=False),
            lambda r: r['expected_route'].update(settings_digest='0' * 64),
            lambda r: r['cases'][0]['intelligence']['measures'].update(code_correctness=float('nan')),
        ):
            with self.subTest(mutate=mutate):
                changed = deepcopy(b)
                mutate(changed)
                with self.assertRaises(ValueError):
                    compare(a, changed)

    def test_different_repository_permissions_or_available_capabilities_exclude_a_comparison(self):
        for different in (report('model-b', permissions='4' * 64), report('model-b', tool_policy=['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET', 'JSON_OBJECT'])):
            with self.assertRaises(ValueError):
                compare(report(), different)

    def test_separate_cohort_refs_compare_only_at_the_same_initial_merge_base(self):
        left = report('cohort-a')
        self.assertEqual(0, compare(left, report('cohort-b'))['rightMinusLeftPoints'])
        for other_base in ('b' * 40, None):
            with self.subTest(initial_base=other_base), self.assertRaises(ValueError):
                compare(left, report('cohort-b', initial_base=other_base))

    def test_paused_ci_uses_the_failed_check_at_the_job_head_before_feature_acceptance(self):
        head = 'a' * 40
        job = dict(id='eng-case', state='NEEDS_INPUT', created_at=1,
                   request=dict(repository='org/repo', model='model', acceptance=[dict(id='terminal_ci_blocker')]),
                   data=dict(head_sha=head, pr=2, ci=dict(ci_verdict='BLOCKED', checks_blocked=True,
                             head_sha=head, pr=2, checks=[dict(name='Unit', status='ACTION_REQUIRED')])))
        service = SimpleNamespace(evidence_access=lambda principal, job_id: job,
                                  store=SimpleNamespace(now=lambda: 2, tx=lambda read: ([dict(dispatched=True)], [])))
        def observed():
            return snapshot(service, 'owner', job['id'], 'terminal_ci_blocker')['required_scenario_signal_observed']
        self.assertTrue(observed())
        job['data']['ci']['head_sha'] = 'b' * 40
        self.assertFalse(observed())

    def test_initial_verified_read_counts_even_when_the_coder_needs_no_additional_ranges(self):
        case, base, path = 'unfamiliar_bug', 'a' * 40, 'app/target.kt'
        data = dict(initial_source_acquisitions=2, initial_source_paths=[digest([base, path])])
        job = dict(state='COMPLETED', data=data, request=dict(source_sha=base, acceptance=[dict(id=case, paths=[path])]))
        measures = case_observations(job, case, True, True)['measures']
        self.assertEqual(1, measures['repository_understanding'])
        self.assertEqual(0.5, measures['tool_efficiency'])

    def test_same_initial_and_workspace_source_range_counts_only_once(self):
        case, base, path = 'source_paging', 'a' * 40, 'src/target.kt'
        path_id = digest([base, path])
        observation = digest([path_id, 'b' * 40, 1, 37])
        data = dict(initial_source_acquisitions=1, initial_source_paths=[path_id], initial_source_ranges=[observation],
                    workspace_source_reads=dict(complete=True, paths=[path_id], ranges=[observation], total=1))
        job = dict(state='COMPLETED', data=data, request=dict(source_sha=base, acceptance=[dict(id=case, paths=[path])]))
        self.assertEqual(0.5, case_observations(job, case, True, True)['measures']['tool_efficiency'])
        del data['initial_source_ranges']
        self.assertNotIn('tool_efficiency', case_observations(job, case, True, True)['measures'])

    def test_code_review_retries_cannot_satisfy_the_ci_repair_scenario(self):
        head, failed = 'b' * 40, 'a' * 40
        job = dict(id='eng-ci', state='COMPLETED', created_at=1, repairs={'review': 1}, request=dict(repository='org/repo', model='m', acceptance=[{'id': 'ci_repair'}]),
                   data=dict(head_sha=head, pr=7, completion_verification={'passed': True, 'requirements': [{'id': 'ci_repair', 'status': 'BEHAVIOR_VERIFIED'}]},
                             readiness={'head_sha': head, 'checks_passed': True}))
        service = SimpleNamespace(evidence_access=lambda owner, key: job, store=SimpleNamespace(now=lambda: 2, tx=lambda read: ([{'dispatched': True}], [])))
        def measured():
            return snapshot(service, 'owner', job['id'], 'ci_repair')['required_scenario_signal_observed']
        self.assertFalse(measured())
        job['repairs']['ci-fingerprint'] = 1
        job['data']['ci_repair_observation'] = dict(job_id=job['id'], pr=7, failed_head=failed, failure_fingerprint='ci-fingerprint')
        self.assertTrue(measured())
        job['data']['readiness']['head_sha'] = failed
        self.assertFalse(measured())

    def test_empty_production_readback_returns_coverage_without_submitting_or_awarding_zero(self):
        result = run_manifest(object(), dict(schema=PRODUCTION_SCHEMA, test_repository='org/repo',
                              cases=[dict(scenario=s) for s in SCENARIOS]))
        self.assertIsNone(result['githubIntelligenceScore']['pointsOutOf100'])
        self.assertTrue(all(v['observed'] == 0 for v in result['githubIntelligenceScore']['dimensions'].values()))

    def test_malformed_import_has_a_validation_error(self):
        for invalid in ([], {'cases': ['unexpected']}, {'cases': [{'scenario': 'example', 'intelligence': None}]}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                compare(report(), invalid)

    def test_stale_or_undeclared_behavior_test_cannot_earn_correctness(self):
        case = 'unfamiliar_bug'
        job = {'state': 'FAILED', 'request': {'acceptance': [{'id': case}], 'source_sha': 'base'},
               'data': {'head_sha': 'new', 'completion_verification': {'requirements': [
                        {'id': case, 'status': 'BEHAVIOR_VERIFIED', 'receipt_ids': ['r'], 'head_sha': 'old'}]}}}
        values = case_observations(job, case, False, False)['measures']
        self.assertEqual(0, values['task_completion'])
        self.assertNotIn('code_correctness', values)
        self.assertNotIn('tool_efficiency', values)
