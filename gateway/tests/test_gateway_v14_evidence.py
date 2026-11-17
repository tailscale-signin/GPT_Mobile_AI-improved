import copy
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.evidence import EvidenceLedger, completion_gate, verify
from v14.engineering import EngineeringStore, validate_request
import test_gateway_v14_engineering as fixtures


class EvidenceTests(unittest.TestCase):
    def test_android_gateway_shared_claim_contract(self):
        for case in json.loads((Path(__file__).resolve().parents[1] / 'contracts/github-evidence-v1.json').read_text()):
            with self.subTest(case['name']):
                self.assertEqual(case['status'], verify(case['claim'], case['receipts'], case['now_ms'], case['epoch'])['status'])

    def test_ledger_survives_restart_preserves_hashes_and_rejects_updates(self):
        with tempfile.TemporaryDirectory() as folder:
            runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                      _durable_connect=lambda: sqlite3.connect(Path(folder) / 'jobs.db'))
            store = EngineeringStore(runtime, lambda: 2000.)
            ledger = EvidenceLedger(store)
            first = ledger.append({'id': 'one'}, 'github_api', {'result': {'count': 3}, 'success': True})
            second = ledger.append({'id': 'one'}, 'github_api', {'result': {'count': 4}, 'success': True})
            reopened = EvidenceLedger(EngineeringStore(runtime, lambda: 3000.))
            self.assertEqual(3, reopened.read('one', first['receipt_id'])['payload']['result']['count'])
            self.assertIsNone(reopened.read('other', first['receipt_id']))
            page = reopened.page('one')['receipts']
            self.assertEqual(first['sha256'], page[1]['previous_hash'])
            self.assertEqual(second['receipt_id'], page[1]['receipt_id'])
            for statement in ('UPDATE engineering_evidence SET payload=\'{}\'', 'DELETE FROM engineering_evidence'):
                with self.assertRaises(sqlite3.IntegrityError):
                    store.tx(lambda db: db.execute(statement))

    def test_missing_skipped_wrong_head_or_unimplemented_requirement_never_passes(self):
        job = {'request': {'acceptance': [{'id': 'fix', 'paths': ['Fix.kt'], 'checks': ['regression']}]},
               'data': {'head_sha': 'a' * 40, 'merge_sha': 'b' * 40, 'result': {'merged': True, 'merge_sha': 'b' * 40}}}
        state = {'head_sha': 'a' * 40, 'checks_passed': True, 'changed_files': ['Fix.kt'], 'checks': [{'name': 'regression', 'required': True, 'status': 'SUCCESS'}]}
        # A changed file and a green check name do not prove feature semantics.
        self.assertFalse(completion_gate(job, state, merged=True)['passed'])
        evidence = {'requirement_id': 'fix', 'receipt_id': 'ev_test', 'kind': 'behavior_test', 'name': 'fix', 'scope': 'behavior',
                    'head_sha': 'a' * 40, 'status': 'PASSED', 'source': 'HOST_VALIDATION', 'trusted': True}
        self.assertTrue(completion_gate(job, state, merged=True, semantic_receipts=[evidence])['passed'])
        for change in ({'checks': []}, {'head_sha': 'c' * 40}, {'changed_files': []}, {'checks': [{'name': 'regression', 'required': True, 'status': 'SKIPPED'}]}):
            self.assertFalse(completion_gate(job, dict(state, **change), semantic_receipts=[evidence])['passed'])
        for change in ({'head_sha': 'c' * 40}, {'status': 'FAILED'}, {'name': 'unrelated'}, {'scope': 'build'}, {'trusted': False}):
            self.assertFalse(completion_gate(job, state, semantic_receipts=[dict(evidence, **change)])['passed'])
        job['data']['result'] = {'model_says_merged': True}
        self.assertFalse(completion_gate(job, state, merged=True)['passed'])

    def test_unconfigured_acceptance_exposes_discovered_tests_without_executing_them(self):
        from unittest.mock import patch
        from v14.engineering_preflight import preflight

        source = 'a' * 40
        request = {
            'repository': 'owner/repo', 'branch': 'fix/report', 'base': 'main',
            'objective': 'Repair report validation', 'model': 'local',
            'request_id': 'acceptance-discovery', 'task_scope': 'implement',
            'source_sha': source,
        }
        policy = {
            'allowed_checks': ['Required verification'], 'acceptance_templates': [],
            'draft_repair_requirements': [], 'post_merge': {'artifacts': [], 'installation_checks': [], 'smoke_checks': []},
        }
        candidate = {
            'case_id': 'test_reports.ReportTest.test_recovery', 'path': 'gateway/tests/test_reports.py',
            'framework': 'unittest', 'class_name': 'test_reports.ReportTest',
        }
        def probe(args, _cwd, _timeout, maximum=4096):
            if args[:3] == ['git', '--git-dir=/mirror', 'rev-parse']:
                return {'exit_code': 0, 'output_tail': 'true'}
            if args[0] == 'docker':
                return {'exit_code': 0, 'output_tail': 'sha256:' + 'b' * 64}
            return {'exit_code': 0, 'output_tail': ''}

        with patch('v14.engineering_preflight.load_policy', return_value=(
                policy, Path('/mirror'), 'docker.io/library/python@sha256:' + 'c' * 64,
                [['python3', '--version']], [])), patch(
                'desktop.acceptance_discovery.discover',
                return_value={'candidates': [candidate], 'truncated': False}) as discovery:
            result = preflight(request, runner=probe)
        self.assertFalse(result['ready'])
        self.assertEqual('ACCEPTANCE_CONTRACT_REQUIRED', result['blockers'][0]['reason_code'])
        self.assertEqual('REVIEW_REQUIRED', result['acceptance_preparation']['status'])
        self.assertEqual(False, result['acceptance_preparation']['executed'])
        self.assertEqual(False, result['acceptance_preparation']['approved_mappings_present'])
        self.assertEqual([], result['acceptance_preparation']['candidates'][0]['requirement_ids'])
        discovery.assert_called_once_with({'mirror': '/mirror'}, source_sha=source, approved_cases=None,
                                          max_files=12, max_candidates=48)

    def test_acceptance_contract_rejects_unbounded_or_empty_requirements(self):
        request = {'repository': 'owner/repo', 'base': 'main', 'branch': 'fix', 'objective': 'fix it', 'model': 'local', 'request_id': 'test'}
        valid = {'id': 'fix', 'paths': ['Fix.kt'], 'checks': ['tests']}
        self.assertEqual([valid], validate_request(dict(request, acceptance=[valid]))['acceptance'])
        for value in ([dict(valid, paths=[])], [dict(valid, paths=['../x'])], [valid, valid], [dict(valid, arbitrary=True)]):
            with self.assertRaises(ValueError):
                validate_request(dict(request, acceptance=value))


    def test_evidence_suite_does_not_duplicate_engineering_endurance_tests(self):
        evidence_cases = set(unittest.defaultTestLoader.getTestCaseNames(CompletionIntegrationTests))
        self.assertEqual({
            'test_required_feature_path_stops_before_merge_prompt',
            'test_empty_ci_receipts_cannot_create_a_merge_prompt',
        }, evidence_cases)
        self.assertIn(
            'test_until_complete_checkpoints_one_thousand_steps_without_resetting_authority',
            unittest.defaultTestLoader.getTestCaseNames(fixtures.DurableEngineeringTests),
        )


class CompletionIntegrationTests(unittest.TestCase):
    # Reuse the durable service fixture without inheriting its 47 engineering
    # tests. Inheritance duplicated the 1,000-step SQLite endurance test and
    # exceeded the evidence suite's 120-second Windows CI watchdog.
    setUp = fixtures.DurableEngineeringTests.setUp
    tearDown = fixtures.DurableEngineeringTests.tearDown

    def test_required_feature_path_stops_before_merge_prompt(self):
        self.request['acceptance'] = [{'id': 'requested', 'paths': ['missing.kt'], 'checks': ['tests']}]
        job = self.store.create('owner', self.request)
        for _ in range(7):
            self.service.tick(job['id'])
        saved = self.store.get(job['id'])
        self.assertEqual('NEEDS_INPUT', saved['state'])
        self.assertFalse(saved['data']['completion_verification']['passed'])
        self.assertNotIn('merge', self.backend.calls)

    def test_empty_ci_receipts_cannot_create_a_merge_prompt(self):
        self.backend.evidence['checks'] = []
        job = self.store.create('owner', self.request)
        for _ in range(7):
            self.service.tick(job['id'])
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
