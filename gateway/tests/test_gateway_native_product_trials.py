"""Attested common-suite evidence is independent of the tested products' prose."""
import copy
import json
import math
import unittest

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from benchmarks import product_trials as trials
from benchmarks.contracts import digest


def chatgpt_capture(answer):
    messages = [
        None,
        {'author': {'role': 'user'}, 'content': {'content_type': 'text', 'parts': ['one task']}},
        {'author': {'role': 'assistant'}, 'recipient': 'github.search', 'end_turn': False,
         'content': {'content_type': 'code', 'text': 'lookup'}},
        {'author': {'role': 'tool'}, 'content': {'content_type': 'text', 'parts': ['private source']}},
        {'author': {'role': 'assistant'}, 'recipient': 'all', 'status': 'finished_successfully', 'end_turn': True,
         'metadata': {'finish_details': {'type': 'stop'}}, 'content': {'content_type': 'text', 'parts': [answer]}},
    ]
    ids = ['root', 'task', 'call', 'result', 'answer']
    nodes = {node: {'id': node, 'parent': ids[i - 1] if i else None, 'children': ids[i + 1:i + 2], 'message': messages[i]}
             for i, node in enumerate(ids)}
    return {'id': 'test-conversation', 'current_node': ids[-1], 'mapping': nodes}


class ProductTrialsTests(unittest.TestCase):
    def setUp(self):
        self.observer, self.grader = Ed25519PrivateKey.generate(), Ed25519PrivateKey.generate()
        self.plan = dict(schema=trials.PLAN, cache='cold', controlsDigest=digest('frozen matched controls'),
                         orderSeed=digest('held-out schedule'), versions={p: '1' for p in trials.PRODUCTS},
                         maxSeconds=20, maxCostUsd=1.5, repeats=1, marginPoints=5,
                         cases=[dict(taskDigest=digest('task'), family='pagination',
                                     environmentDigest=digest('e'), graderDigest=digest('hidden grader'))])

    @staticmethod
    def sign(value, key):
        return dict(payload=value, signature=key.sign(trials.signed_bytes(value)).hex())

    def run_suite(self, update=None):
        observations, grades = [], []
        for index, slot in enumerate(trials.schedule(self.plan)):
            case = next(c for c in self.plan['cases'] if c['taskDigest'] == slot['taskDigest'])
            run = digest([slot['product'], slot['taskDigest'], slot['repeat']])
            observation = dict(slot, runId=run, schema=trials.OBSERVATION, state='DELIVERED', planDigest=digest(self.plan),
                               controlsDigest=self.plan['controlsDigest'], cache='cold', seconds=3, costUsd=.01,
                               version='1', artifactDigest=digest(run), interventions=0, scheduleIndex=index)
            grade = dict(schema=trials.GRADE, runId=run, graderRunId='grade-' + run,
                         planDigest=digest(self.plan), taskDigest=case['taskDigest'], graderDigest=case['graderDigest'],
                         environmentDigest=case['environmentDigest'], artifactDigest=digest(run),
                         result='PASS' if slot['product'] == trials.PRODUCTS[0] else 'FAIL')
            if update:
                update(observation, grade)
            if observation:
                observations.append(self.sign(observation, self.observer))
            if grade:
                grades.append(self.sign(grade, self.grader))
        return trials.compare_signed(self.sign(self.plan, self.observer), observations, grades,
                                     self.observer.public_key().public_bytes_raw(), self.grader.public_key().public_bytes_raw())

    def test_repeats_of_one_family_do_not_establish_superiority(self):
        self.plan['repeats'] = 8
        r = self.run_suite()
        self.assertEqual('INCONCLUSIVE', r['verdict'])
        self.assertEqual(1, r['comparisonsAgainstMobile']['Codex']['independentFamiliesObserved'])

    def test_both_comparisons_must_clear_joint_bound(self):
        base = self.plan['cases'][0]
        self.plan['cases'] = [dict(base, taskDigest=digest(i), family=str(i)) for i in range(30)]
        self.assertEqual('SUPERIOR_ON_REGISTERED_SUITE', self.run_suite()['verdict'])
        def tie(row, grade):
            if row['product'] == 'Codex':
                grade['result'] = 'PASS'
        self.assertEqual('INCONCLUSIVE', self.run_suite(tie)['verdict'])

    def test_budget_and_product_failure_count_missing_and_harness_remain_visible(self):
        def faults(row, grade):
            if row['product'] == 'GPT Mobile AI':
                row['costUsd'] = None
            elif row['product'] == 'Claude Code':
                row.update(state='PRODUCT_FAILURE', artifactDigest=None)
                grade.clear()
            else:
                row.clear()
                grade.clear()
        result = self.run_suite(faults)
        self.assertEqual('INCOMPLETE', result['verdict'])
        self.assertEqual({'COST_UNOBSERVED': 1}, result['coverage']['GPT Mobile AI'])
        self.assertEqual({'PRODUCT_FAILURE': 1}, result['coverage']['Claude Code'])
        self.assertEqual({'MISSING_OBSERVATION': 1}, result['coverage']['Codex'])
        self.assertEqual(1, self.run_suite(lambda r, _: r.update(seconds=25))['coverage']['GPT Mobile AI']['INTERVENTION_OR_BUDGET_EXCEEDED'])

    def test_evidence_substitutions_and_self_attestation_are_rejected(self):
        for mutate in (lambda r, _: r.update(cache='warm'), lambda r, _: r.update(version='different'),
                       lambda r, _: r.update(scheduleIndex=-1), lambda r, _: r.update(controlsDigest=digest('other')),
                       lambda _, g: g.update(artifactDigest=digest('other')), lambda _, g: g.update(environmentDigest=digest('other')),
                       lambda _, g: g.update(graderRunId='reused')):
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                self.run_suite(mutate)
        pub = self.observer.public_key().public_bytes_raw()
        with self.assertRaises(ValueError):
            trials.compare_signed(self.sign(self.plan, self.observer), [], [], pub, pub)
        forged = self.sign(self.plan, self.observer)
        forged['payload']['maxCostUsd'] = 4
        with self.assertRaisesRegex(ValueError, 'signature'):
            trials.verified(forged, self.observer.public_key(), trials.PLAN)

    def test_native_adapters_preserve_answer_and_reject_interrupted_turns(self):
        answer = ' answer\r\n'
        codex = [dict(type='thread.started', thread_id='test'), dict(type='turn.started'),
                 dict(type='item.completed', item=dict(type='agent_message', phase='commentary', text='progress')),
                 dict(type='item.completed', item=dict(type='agent_message', phase='final_answer', text=answer)), dict(type='turn.completed')]
        claude = dict(type='result', subtype='success', is_error=False, session_id='test', result=answer)
        mobile = dict(conversation=dict(history_complete=True, conversation_id='test', active_job_id='retained', job_state='COMPLETED'),
                      messages=[dict(role='user', content='prompt', command_id='turn'),
                                dict(role='assistant', status='completed', command_id='turn', content=answer)])
        dumps = lambda items: '\n'.join(map(json.dumps, items))
        for name, capture in [('Codex', dumps(codex)), ('Claude Code', json.dumps(claude)), ('GPT Mobile AI', json.dumps(mobile))]:
            self.assertEqual(answer, trials.native_answer(name, capture)['answer'])
        for name, capture in [('Codex', dumps(codex[:-1])), ('Claude Code', json.dumps(dict(claude, is_error=True))),
                              ('GPT Mobile AI', json.dumps(dict(mobile, conversation=dict(mobile['conversation'], job_state='RUNNING'))))]:
            with self.assertRaises(ValueError):
                trials.native_answer(name, capture)

    def test_chatgpt_export_returns_only_the_exact_final_answer(self):
        answer = ' answer\r\n'
        capture = chatgpt_capture(answer)
        capture['mapping']['answer']['message']['content']['parts'] = [' ans', 'wer\r\n']
        expected = {'runId': digest(['ChatGPT', capture['id']]), 'artifactDigest': trials.content_digest(answer), 'answer': answer}
        for value in (capture, [capture]):
            self.assertEqual(expected, trials.native_answer('ChatGPT', json.dumps(value)))

    def test_chatgpt_export_rejects_other_sessions_branches_and_incomplete_deliveries(self):
        capture = chatgpt_capture('answer')
        for mutate in (lambda c: c.update(current_node='result'), lambda c: c.update(conversation_id='another'),
                       lambda c: c['mapping'].update(orphan=c['mapping']['answer']),
                       lambda c: c['mapping']['root'].update(parent='answer'),
                       lambda c: c['mapping']['task']['children'].append('answer'),
                       lambda c: c['mapping']['result']['message'].update(author={'role': 'user'}),
                       lambda c: c['mapping']['call']['message'].update(recipient='all', end_turn=True),
                       lambda c: c['mapping']['answer']['message'].update(end_turn=False),
                       lambda c: c['mapping']['answer']['message'].update(channel='commentary'),
                       lambda c: c['mapping']['answer']['message']['metadata'].update(is_visually_hidden_from_conversation=True),
                       lambda c: c['mapping']['answer']['message']['metadata'].update(finish_details={'type': 'max_tokens'}),
                       lambda c: c['mapping']['answer']['message']['content'].update(parts=[{'private': 'record'}])):
            altered = copy.deepcopy(capture)
            mutate(altered)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                trials.native_answer('ChatGPT', json.dumps(altered))
        with self.assertRaises(ValueError):
            trials.native_answer('ChatGPT', json.dumps([capture, capture]))

    def test_frozen_products_govern_schedule_coverage_and_joint_bound(self):
        # Replaying signed historical plans keeps their original order and two-comparison correction.
        legacy_products = ('GPT Mobile AI', 'Codex', 'Claude Code')
        for selected in (legacy_products, ('GPT Mobile AI', 'ChatGPT', 'Codex'), ('GPT Mobile AI', 'ChatGPT'), trials.PRODUCTS):
            with self.subTest(selected=selected):
                self.plan['versions'] = dict.fromkeys(selected, '1')
                task = self.plan['cases'][0]['taskDigest']
                order = sorted(selected, key=lambda p: digest([self.plan['orderSeed'], task, 0, p]))
                self.assertEqual(order, [row['product'] for row in trials.schedule(self.plan)])
                base = self.plan['cases'][0]
                self.plan['cases'] = [dict(base, taskDigest=digest(i), family=str(i)) for i in range(12)]
                result = self.run_suite()
                self.assertEqual(set(selected), set(result['coverage']))
                self.assertEqual(set(selected[1:]), set(result['comparisonsAgainstMobile']))
                for paired in result['comparisonsAgainstMobile'].values():
                    self.assertAlmostEqual(100 * (1 - math.sqrt(2 * math.log((len(selected) - 1) / .05) / 12)),
                                           paired['lowerSimultaneous95Points'])
                self.plan['cases'] = [base]
        for invalid in ({'GPT Mobile AI': '1'}, {'ChatGPT': '1', 'Codex': '1'}, {'GPT Mobile AI': '1', 'Copy': '1'}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                trials.schedule(dict(self.plan, versions=invalid))
