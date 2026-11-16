"""Local checkpoint, streaming and qualification failure regressions."""
from copy import deepcopy
import json
import threading
from pathlib import Path
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import test_gateway_v14_engineering as durable_fixture
from v14.engineering_checkpoints import rewind, history, candidate_details
from v14.engineering_specialists import investigate
from v14.engineering_operations import EngineeringOperations
from v14.engineering_validation import patch_identity
from v14.inference_execution import BoundModelInvocation
from benchmarks.engineering_qualification import SCENARIOS, run_manifest, snapshot


class AutonomousRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.fixture = durable_fixture.DurableEngineeringTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.service, self.store, self.backend = self.fixture.service, self.fixture.store, self.fixture.backend
        job = self.store.create('owner', self.fixture.request)
        job, lease, _ = self.store.claim(job['id'])
        head, policy = 'a' * 40, 'p' * 64
        changes = [{'path': 'file', 'expected_blob_sha': 'b' * 40, 'content': 'fixed', 'mode': '100644'}]
        job['data'].update(head_sha=head, files={}, tree={})
        candidate = self.service.artifacts.put_json(job, changes, 'workspace-check-candidate')
        self.check_id = 'e' * 64
        job['data']['workspace_checks'] = {self.check_id: {'check_id': self.check_id, 'source_sha': head,
            'policy_sha256': policy, 'patch_identity': patch_identity(head, changes), 'candidate_ref': candidate,
            'check_index': 0, 'status': 'PASSED'}}
        self.store.complete(job, lease, 'NEEDS_INPUT', {})
        self.job = self.store.get(job['id'], 'owner')
        self.service.evidence_access = Mock(side_effect=lambda principal, job_id: self.store.get(job_id, principal))
        self.backend.ref = Mock(return_value=head)
        self.backend.private_artifacts = self.service.artifacts
        mocker = patch('v14.engineering_workspace_checks.catalog', return_value=(policy, []))
        mocker.start()
        self.addCleanup(mocker.stop)

    def test_restore_revalidates_unpublished_candidate_and_invalidates_approval(self):
        restored = rewind(self.service, 'owner', self.job['id'], self.job['revision'], self.check_id)
        self.assertEqual('REVIEW', restored['state'])
        self.assertEqual('fixed', restored['data']['changes'][0]['content'])
        self.assertIsNone(restored['approval'])
        self.assertNotIn('local_validation', restored['data'])
        self.assertEqual(self.job['revision'] + 1, restored['revision'])
        with self.assertRaises(ValueError):
            rewind(self.service, 'owner', self.job['id'], self.job['revision'], self.check_id)

    def test_unknown_remote_mutation_prevents_local_rewind(self):
        def insert(db):
            EngineeringOperations.schema(db)
            db.execute('INSERT INTO engineering_operations VALUES(?,?,?,?,?)',
                       ('operation', self.job['id'], self.job['revision'], 'UNKNOWN_OUTCOME', '{}'))
        self.store.tx(insert)
        with self.assertRaisesRegex(ValueError, 'unpublished'):
            rewind(self.service, 'owner', self.job['id'], self.job['revision'], self.check_id)
        self.assertEqual('NEEDS_INPUT', self.store.get(self.job['id'])['state'])

    def test_changed_remote_head_prevents_rewind_and_history_has_test_receipts(self):
        value = history(self.service, 'owner', self.job['id'])
        self.assertEqual(self.check_id, value['candidates'][0]['check_id'])
        self.assertFalse(value['published'])
        details = candidate_details(self.service, 'owner', self.job['id'], self.check_id)
        self.assertIn('+fixed', details['diff'])
        self.assertEqual(self.check_id, details['check_id'])
        self.backend.ref.return_value = 'c' * 40
        with self.assertRaisesRegex(ValueError, 'branch changed'):
            rewind(self.service, 'owner', self.job['id'], self.job['revision'], self.check_id)

    def test_stream_checkpoints_never_become_executable_partial_patches(self):
        invocation = BoundModelInvocation.__new__(BoundModelInvocation)
        invocation.job = self.job
        invocation.service = SimpleNamespace(artifacts=Mock())
        invocation.current = {'dispatched': True}
        invocation.cancellation = None
        invocation.stream_checkpoint_size = 0
        invocation.persist = Mock()
        invocation.checkpoint_stream('{"changes":' + 'x' * 8192)
        saved = invocation.service.artifacts.put_json.call_args.args[1]
        self.assertFalse(saved['complete'])
        self.assertFalse(saved['executable'])
        self.assertFalse(invocation.current['partial_action']['executable'])
        invocation.checkpoint_stream('short')
        self.assertEqual(1, invocation.service.artifacts.put_json.call_count)

    def test_all_ten_scenarios_remain_unqualified_without_live_execution(self):
        manifest = {'schema': 'gateway.production-qualification.v1', 'test_repository': 'owner/repo',
                    'cases': [{'scenario': scenario} for scenario in SCENARIOS]}
        client = Mock()
        result = run_manifest(client, manifest)
        self.assertEqual(10, result['total'])
        self.assertEqual(0, result['passed'])
        self.assertFalse(result['qualified'])
        client.submit_engineering.assert_not_called()
        for scenario in SCENARIOS:
            result = snapshot(self.service, 'owner', self.job['id'], scenario)
            self.assertEqual('NOT_VERIFIED', result['status'])
            self.assertEqual('NOT_EXECUTED', result['liveGitHub'])
            self.assertIsNone(result['tokens'])

    def test_criterion_receipt_is_immutable_after_new_verification(self):
        first = self.service.completion_verification(self.job, self.backend.evidence)
        original = deepcopy(first)
        self.service.completion_verification(self.job, dict(self.backend.evidence, head_sha='c' * 40))
        self.assertEqual(original, first)
        self.assertTrue(first['criterion_receipts'])


class SpecialistCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.job = {'id': 'specialist-fixture', 'principal': 'test-owner',
                    'request': {'model': 'local-model', 'execution_mode': 'full',
                                'execution_binding_id': 'verified-route-a'},
                    'data': {'head_sha': 'a' * 40, 'tree': {}, 'files': {}, 'plan': {}}}
        self.tasks = [{'role': 'source explorer', 'evidence': {'focus': 'inspect source'}}]
        def save(job, value):
            job['data']['specialists'] = deepcopy(value)
        self.backend = SimpleNamespace(
            save_specialists=save, cancellation=Mock(return_value=threading.Event()), model=Mock())

    def test_malformed_model_response_recovers_and_checkpoint_is_binding_scoped(self):
        attempts = []
        def model(job, role, evidence):
            attempts.append(evidence['format_repair'])
            if len(attempts) == 1:
                raise ValueError('invalid structured response')
            return {'findings': ['bounded result ' + str(len(attempts))], 'paths': []}
        self.backend.model = Mock(side_effect=model)
        first = investigate(self.backend, self.job, self.tasks)
        self.assertEqual('COMPLETED', first[0]['status'])
        self.assertEqual(2, first[0]['calls'])
        self.assertEqual([False, True], attempts)
        self.assertEqual(first, investigate(self.backend, self.job, self.tasks))
        self.assertEqual(2, len(attempts))
        self.job['request']['execution_binding_id'] = 'verified-route-b'
        refreshed = investigate(self.backend, self.job, self.tasks)
        self.assertEqual(3, len(attempts))
        self.assertNotEqual(first[0]['findings'], refreshed[0]['findings'])

    def test_failed_format_checkpoint_is_retryable_without_claiming_success(self):
        self.backend.model.side_effect = ValueError('unusable response')
        rejected = investigate(self.backend, self.job, self.tasks)[0]
        self.assertEqual('FORMAT_REJECTED', rejected['status'])
        self.assertEqual([], rejected['findings'])
        self.assertEqual(2, rejected['calls'])
        self.backend.model.side_effect = None
        self.backend.model.return_value = {'findings': ['recovered'], 'paths': []}
        accepted = investigate(self.backend, self.job, self.tasks)[0]
        self.assertEqual('COMPLETED', accepted['status'])
        self.assertEqual(['recovered'], accepted['findings'])
        self.assertEqual('COMPLETED', self.job['data']['specialists']['results']['source explorer']['status'])

    def test_cancellation_stops_without_persisting_a_fake_result(self):
        cancelled = threading.Event()
        cancelled.set()
        self.backend.cancellation.return_value = cancelled
        with self.assertRaises(InterruptedError):
            investigate(self.backend, self.job, self.tasks)
        self.assertNotIn('specialists', self.job['data'])


if __name__ == '__main__':
    unittest.main()
