import copy
import json
import sys
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.adaptive_sampling import resolve_policy, validate_policy, generation_parameters
from v14.engineering import validate_request
from v14.engineering_defaults import retained_request_defaults
from v14.engineering_backend import GitHubEngineeringBackend

POLICY = {'version': 1, 'mode': 'ADAPTIVE', 'creativity': .8}
OPTIONS = {'options': [dict(id=identity, proposal='Inspect source', tradeoffs='More validation', assumptions='Unverified') for identity in ('A', 'B')]}
SELECTION = dict(selected_id='A', rationale='Lower maintenance cost', risks=['Source inspection pending'], tests=['Named regression'], paths=['app/Main.kt'], plan='Inspect, implement, verify')


class AdaptiveSamplingTests(unittest.TestCase):
    def test_request_validation_rejects_unknown_nonfinite_or_boolean_settings(self):
        request = dict(repository='owner/repo', branch='feature/test', objective='Design UI', model='A', request_id='request')
        self.assertEqual(POLICY, validate_request(dict(request, sampling_policy=POLICY))['sampling_policy'])
        for update in ({'version': True}, {'version': 2}, {'mode': 'auto'}, {'creativity': True}, {'creativity': float('nan')}, {'creativity': 1.1}, {'tool_authority': True}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                validate_request(dict(request, sampling_policy=dict(POLICY, **update)))
        self.assertNotIn('sampling_policy', validate_request(request))

    def test_retained_job_uses_its_accepted_policy_when_old_client_omits_it(self):
        retained = retained_request_defaults({}, {'sampling_policy': POLICY})
        self.assertEqual(POLICY, retained['sampling_policy'])
        self.assertIsNot(POLICY, retained['sampling_policy'])
        manual = dict(POLICY, mode='MANUAL')
        self.assertEqual(manual, retained_request_defaults({'sampling_policy': manual}, {'sampling_policy': POLICY})['sampling_policy'])
        self.assertNotIn('sampling_policy', retained_request_defaults({}, {}))

    def test_sampling_never_changes_selected_identity_or_reasoning(self):
        selected = {'temperature': .9, 'top_p': .7, 'seed': 123, 'reasoning': True}
        original = copy.deepcopy(selected)
        expected = {'design options': .94, 'architecture options': .64, 'planner': .1, 'coding agent': .2, 'selected-model reviewer': .1, 'final summary': .45, 'unknown': .05}
        for role, temperature in expected.items():
            policy = resolve_policy({'sampling_policy': POLICY}, role)
            wire = generation_parameters(selected, policy)
            self.assertAlmostEqual(temperature, wire['temperature'])
            self.assertNotIn('top_p', wire)
            self.assertTrue(wire['reasoning'])
            self.assertEqual(123, wire['seed'])
        self.assertEqual(original, selected)
        self.assertEqual(selected, generation_parameters(selected, resolve_policy({}, 'planner')))
        self.assertAlmostEqual(1., resolve_policy({'sampling_policy': dict(POLICY, mode='MANUAL')}, 'planner')['temperature'])

    def test_failed_selection_resumes_saved_options_and_never_authorizes_mutations(self):
        backend = GitHubEngineeringBackend(SimpleNamespace())
        backend.api = Mock(return_value={'permissions': {'pull': True}})
        backend.save_adaptive_planning = lambda job, value: job['data'].update(adaptive_planning=copy.deepcopy(value))
        job = dict(id='job', principal='owner', deadline=time.time() + 30, data={}, request=dict(objective='Redesign the UI', model='A', repository='owner/repo', sampling_policy=POLICY))
        roles = []
        fail = True
        def execute(job, role, payload, cancellation):
            roles.append(role)
            self.assertNotIn('tools', payload)
            if role == 'planner' and fail:
                raise ConnectionError('Transport interrupted')
            result = OPTIONS if role == 'design options' else SELECTION
            return {'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(result)}}]}
        backend.model_executor = execute
        with self.assertRaises(ConnectionError):
            backend.model(job, 'planner', {'head_sha': 'a' * 40})
        self.assertEqual(OPTIONS, job['data']['adaptive_planning']['options'])
        fail = False
        self.assertEqual(SELECTION, backend.model(job, 'planner', {'head_sha': 'a' * 40}))
        backend.model(job, 'planner', {'head_sha': 'a' * 40})
        self.assertEqual(['design options', 'planner', 'planner'], roles)
        backend.model(job, 'planner', {'head_sha': 'b' * 40})
        self.assertEqual(['design options', 'planner'], roles[-2:])
        self.assertTrue(all(call.args[0] == '/repos/owner/repo' for call in backend.api.call_args_list))

    def test_manual_or_noncreative_tasks_do_not_add_extra_model_calls(self):
        for objective, policy in [('Repair CI', POLICY), ('Redesign the UI', dict(POLICY, mode='MANUAL'))]:
            backend = GitHubEngineeringBackend(SimpleNamespace())
            backend._model_once = Mock(return_value={'plan': 'inspect'})
            job = dict(request=dict(objective=objective, sampling_policy=policy))
            self.assertEqual({'plan': 'inspect'}, backend.model(job, 'planner', {}))
            backend._model_once.assert_called_once()
