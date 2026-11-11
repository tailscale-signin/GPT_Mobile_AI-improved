import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_preflight import preflight, EngineeringPreflightError
from v14.engineering_validation import run_bounded
import test_gateway_v14_engineering as fixtures


class EngineeringPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.mirror = self.root / 'fixture.git'
        env = {key: value for key, value in os.environ.items() if not key.upper().startswith('GIT_')}
        env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, GIT_AUTHOR_NAME='Fixture',
                   GIT_COMMITTER_NAME='Fixture', GIT_AUTHOR_EMAIL='fixture@example.invalid', GIT_COMMITTER_EMAIL='fixture@example.invalid')
        subprocess.run(['git', 'init', '--bare', '-q', str(self.mirror)], check=True, env=env)
        tree = subprocess.check_output(['git', '--git-dir=' + str(self.mirror), 'hash-object', '-w', '-t', 'tree', '--stdin'], input=b'', env=env).decode().strip()
        self.sha = subprocess.check_output(['git', '--git-dir=' + str(self.mirror), 'commit-tree', tree], input=b'Fixture\n', env=env).decode().strip()
        self.request = {'repository': 'owner/repo', 'branch': 'feature/job', 'base': 'main', 'objective': 'Repair the build',
                        'model': 'local', 'profile_id': 'test-profile', 'request_id': 'preflight', 'source_sha': self.sha}
        self.requirement = {'id': 'build-fix', 'paths': ['file'], 'checks': ['tests']}
        self.policy = {'mirror': str(self.mirror), 'image': 'fixture@sha256:' + 'a' * 64,
                       'allowed_checks': ['tests'], 'commands': [['run-approved-tests']],
                       'acceptance_map': [{'requirement_id': 'build-fix', 'case_id': 'Fixture.build', 'command_index': 0, 'scope': 'behavior', 'report_path': 'reports/tests.xml'}],
                       'acceptance_templates': [{'objectives': ['Repair the build'], 'requirements': [self.requirement]}]}
        self.path = self.root / 'policy.json'
        self.environment = patch.dict(os.environ, GATEWAY_ENGINEERING_VALIDATION_POLICY=str(self.path))
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.calls = []

    def probe(self, argv, cwd, timeout, maximum):
        self.calls.append(argv)
        if argv[0] == 'docker':
            self.assertEqual(['docker', 'image', 'inspect'], argv[:3])
            return {'exit_code': 0, 'output_tail': 'sha256:' + 'b' * 64}
        return run_bounded(argv, cwd, timeout, maximum)

    def check(self, request=None):
        self.path.write_text(json.dumps({'owner/repo': self.policy}))
        return preflight(request or self.request, runner=self.probe)

    def test_default_contract_is_selected_from_objective_and_has_exact_producers(self):
        result = self.check()
        self.assertTrue(result['ready'], result)
        self.assertEqual('HOST_OBJECTIVE_TEMPLATE', result['acceptance_source'])
        self.assertEqual([{'kind': 'behavior_test', 'name': 'Fixture.build'}], result['acceptance'][0]['evidence'])
        self.assertEqual(hashlib.sha256(self.request['objective'].encode()).hexdigest(), result['objective_sha256'])
        self.assertEqual('NOT_ADMITTED', result['execution_owner'])
        self.assertFalse(any('run' in call for call in self.calls))

    def test_missing_or_ambiguous_contract_blocks_without_inventing_behavior_coverage(self):
        for templates in ([], self.policy['acceptance_templates'] * 2):
            self.policy['acceptance_templates'] = templates
            result = self.check()
            self.assertFalse(result['ready'])
            self.assertEqual('ACCEPTANCE_CONTRACT_REQUIRED', result['blockers'][0]['reason_code'])

    def test_unknown_objective_does_not_inherit_unrelated_template(self):
        result = self.check(dict(self.request, objective='Add an unrelated widget'))
        self.assertFalse(result['ready'])
        self.assertEqual([], result['acceptance'])

    def test_explicit_requirements_need_real_matching_producers(self):
        for evidence in ([{'kind': 'device_trace', 'name': 'physical'}], [{'kind': 'behavior_test', 'name': 'missing'}]):
            result = self.check(dict(self.request, acceptance=[dict(self.requirement, evidence=evidence)]))
            self.assertFalse(result['ready'])
            self.assertIn('ACCEPTANCE_PRODUCER_REQUIRED', [item['reason_code'] for item in result['blockers']])

    def test_missing_commit_is_distinct_from_runtime_or_policy(self):
        result = self.check(dict(self.request, source_sha='f' * 40))
        self.assertEqual('VALIDATION_REVISION_UNAVAILABLE', result['blockers'][0]['reason_code'])
        self.assertEqual('ready', result['checks']['validation_runtime'])

    def test_missing_runtime_recovers_on_next_preflight(self):
        self.path.write_text(json.dumps({'owner/repo': self.policy}))
        def offline(argv, *args, **kwargs):
            if argv[0] == 'docker':
                raise FileNotFoundError('No runtime')
            return self.probe(argv, *args, **kwargs)
        failed = preflight(self.request, runner=offline)
        self.assertEqual('VALIDATION_RUNTIME_UNAVAILABLE', failed['blockers'][0]['reason_code'])
        self.assertTrue(self.check()['ready'])

    def test_read_and_plan_do_not_require_implementation_infrastructure(self):
        self.path.unlink(missing_ok=True)
        for scope in ('read', 'plan'):
            result = preflight(dict(self.request, task_scope=scope), runner=lambda *a, **k: self.fail('No validation process for read/plan'))
            self.assertTrue(result['ready'])


class AdmissionPersistenceTests(unittest.TestCase):
    setUp = fixtures.DurableEngineeringTests.setUp
    tearDown = fixtures.DurableEngineeringTests.tearDown
    # Retain the durable suite's selected-binding and in-memory backend boundary.
    def test_admission_persists_contract_without_changing_original_request_identity(self):
        result = {'ready': True, 'acceptance': [dict(self.request['acceptance'][0], evidence=[{'kind': 'behavior_test', 'name': 'build-fix'}])],
                  'checks': {'acceptance': 'ready'}, 'objective_sha256': hashlib.sha256(self.request['objective'].encode()).hexdigest()}
        original = dict(self.request, acceptance=[])
        with patch('v14.engineering_preflight.preflight', return_value=result) as check:
            job = self.service.admit('owner', original)
            self.assertEqual(result['acceptance'], job['request']['acceptance'])
            self.assertEqual(result, self.store.get(job['id'])['data']['admission'])
            self.assertEqual(job['id'], self.service.admit('owner', original)['id'])
            self.assertEqual(1, check.call_count)
            self.assertEqual([], self.backend.calls)

    def test_rejected_preflight_has_no_job_or_consumed_steps(self):
        result = {'ready': False, 'checks': {}, 'blockers': [{'reason_code': 'ACCEPTANCE_CONTRACT_REQUIRED', 'message': 'Define acceptance'}]}
        with patch('v14.engineering_preflight.preflight', return_value=result):
            with self.assertRaises(EngineeringPreflightError):
                self.service.admit('owner', self.request)
        self.assertEqual([], self.store.list('owner'))
        self.assertEqual([], self.backend.calls)
