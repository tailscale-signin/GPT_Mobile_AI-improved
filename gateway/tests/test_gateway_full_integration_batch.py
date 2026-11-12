"""Full Integration Batch modes remain host-authorized and journal-owned."""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_batch_plan import new_plan, page, summary, transition
from v14.engineering_batch_policy import DEFAULTS, authorize_request, capability, load_policy, save_policy


class IntegrationBatchTest(unittest.TestCase):
    def test_host_policy_rollback_and_reject_unsupported_scope(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'execution-policy.json'
            self.assertEqual('standard', load_policy(target)['default_mode'])
            self.assertEqual(1, load_policy(target)['local_validation_workers'])
            self.assertNotIn('full', capability(load_policy(target))['modes'])
            policy = dict(DEFAULTS, default_mode='full', full_enabled=True, local_validation_workers=3)
            self.assertEqual(policy, save_policy(target, policy))
            self.assertIn('full', capability(load_policy(target))['modes'])
            self.assertEqual(3, capability(load_policy(target))['local_validation_workers'])
            with self.assertRaises(ValueError):
                save_policy(target, dict(policy, github_api_max_inflight=8))
            with self.assertRaises(ValueError):
                save_policy(target, dict(policy, native_provider_batch=True))
            for workers in (0, 5, True):
                with self.subTest(workers=workers), self.assertRaises(ValueError):
                    save_policy(target, dict(policy, local_validation_workers=workers))
            self.assertEqual(policy, load_policy(target))

    def test_full_requires_explicit_host_enable_and_exact_version(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'policy.json'
            with patch.dict('os.environ', {'GATEWAY_EXECUTION_POLICY_PATH': str(path)}):
                self.assertEqual('standard', authorize_request({}))
                with self.assertRaises(ValueError):
                    authorize_request({'execution_mode': 'full', 'batch_policy_version': 1})
                save_policy(path, dict(DEFAULTS, default_mode='full', full_enabled=True))
                self.assertEqual('full', authorize_request({'execution_mode': 'full', 'batch_policy_version': 1}))
                with self.assertRaises(ValueError):
                    authorize_request({'execution_mode': 'full', 'batch_policy_version': 2})
                with self.assertRaises(ValueError):
                    authorize_request({'execution_mode': 'full', 'batch_policy_version': 1, 'batch_preferences': {'max_git_api_inflight': 3}})
                with self.assertRaises(ValueError):
                    authorize_request({'execution_mode': 'full', 'batch_policy_version': 1}, durable=False)

    def test_local_validation_worker_limit_is_frozen_and_can_be_narrowed_per_job(self):
        request = {'request_id': 'id', 'execution_mode': 'full', 'repository': 'a/b', 'branch': 'feature',
                   'base': 'main', 'acceptance': [], 'execution_budget': {}}
        job = {'id': 'job', 'principal': 'local', 'request': request, 'state': 'PLAN', 'revision': 0, 'data': {}}
        policy = dict(DEFAULTS, default_mode='full', full_enabled=True, local_validation_workers=3)
        self.assertEqual(3, new_plan(job, policy)['controls']['local_validation_workers'])
        request['batch_preferences'] = {'allow_parallel_local_tests': False}
        self.assertEqual(1, new_plan(job, policy)['controls']['local_validation_workers'])

    def test_stage_retries_extend_plan_without_fabricating_acceptance(self):
        request = {'request_id': 'id', 'execution_mode': 'full', 'repository': 'a/b', 'branch': 'feature',
                   'base': 'main', 'acceptance': [], 'execution_budget': {}}
        job = {'id': 'job', 'principal': 'local', 'request': request, 'state': 'PLAN', 'revision': 0, 'data': {}}
        plan = new_plan(job)
        job['data']['integration_batch'] = plan
        self.assertEqual('PLAN', summary(plan)['current_phase'])
        transition(job, 'READ', {'plan': 'verified'})
        job['state'], job['revision'] = 'READ', 1
        transition(job, 'READ', {})
        self.assertEqual('running', summary(plan)['current_state'])
        transition(job, 'IMPLEMENT', {'paths': ['x']})
        job['state'], job['revision'] = 'IMPLEMENT', 2
        transition(job, 'READ', {'changes': ['x']}) # repair rewinds stage graph
        self.assertEqual(2, [n['phase'] for n in plan['nodes']].count('READ'))
        self.assertEqual('stage-0004', plan['nodes'][-1]['id'])
        self.assertEqual(4, page(plan, limit=20)['summary']['total'])
        self.assertEqual(2, page(plan, after=2, limit=2)['nodes'][1]['attempt'])

    def test_stage_pauses_retain_unproven_completion(self):
        job = {'id': 'job', 'principal': 'local', 'request': {'request_id': 'id',
               'execution_mode': 'full', 'repository': 'a/b', 'branch': 'one', 'base': 'main'},
               'state': 'PLAN', 'revision': 0, 'data': {}}
        job['data']['integration_batch'] = new_plan(job)
        job['provider_wait'] = {'retry_at': time.time() + 120}
        transition(job, 'PLAN', {'error_code': 'ENGINEERING_RATE_LIMIT_WAIT'})
        self.assertEqual('waiting_rate_limit', summary(job['data']['integration_batch'])['current_state'])
        job.pop('provider_wait')
        transition(job, 'NEEDS_INPUT', {'error_code': 'EXECUTION_BINDING_CHANGED'})
        self.assertEqual('needs_attention', summary(job['data']['integration_batch'])['status'])
        self.assertEqual('blocked', job['data']['integration_batch']['nodes'][-1]['state'])

    def test_terminal_projection_can_be_reclassified_before_commit(self):
        job = {'id': 'job', 'principal': 'local', 'request': {'request_id': 'id',
               'execution_mode': 'full', 'repository': 'a/b', 'branch': 'one', 'base': 'main'},
               'state': 'PLAN', 'revision': 0, 'data': {}}
        job['data']['integration_batch'] = new_plan(job)
        transition(job, 'COMPLETED', {})
        self.assertEqual('completed', summary(job['data']['integration_batch'])['status'])
        transition(job, 'NEEDS_INPUT', {'error_code': 'PENDING_INSTRUCTIONS'})
        projection = summary(job['data']['integration_batch'])
        self.assertEqual('needs_attention', projection['status'])
        self.assertEqual('blocked', projection['current_state'])


if __name__ == '__main__':
    unittest.main()
