import copy
import sqlite3
import sys
import tempfile
import threading
import unittest
import time
import requests
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering import EngineeringStore, EngineeringService, EngineeringSupervisor, public_job, validate_request, principal_authorized
from v14.engineering_backend import prepare_changes, blob_sha
from v14.execution_binding import fingerprint


class Backend:
    def __init__(self):
        self.calls = []
        self.evidence = {'pr': 7, 'head_sha': 'a' * 40, 'base_sha': 'b' * 40, 'state': 'OPEN', 'checks_passed': True,
                         'policy_verified': True, 'mergeable': True, 'reviews_satisfied': True, 'draft': False, 'checks': [{'name': 'tests', 'status': 'SUCCESS', 'required': True}], 'changed_files': ['file']}
        self.recovered = None

    def step(self, job, phase):
        self.calls.append(phase)
        return {'PLAN': {'head_sha': 'a' * 40}, 'READ': {'files': {}}, 'IMPLEMENT': {'changes': [{'path': 'file', 'content': 'new'}]},
                'REVIEW': {'review': {'approved': True, 'issues': []}, 'local_validation': {'patch_identity': 'fixture-patch',
                    'requirement_evidence': [{'requirement_id': 'build-fix', 'kind': 'behavior_test', 'name': 'build-fix', 'scope': 'behavior', 'status': 'PASSED'}]}}, 'COMMIT': {'head_sha': 'a' * 40},
                'OPEN_PR': {'pr': 7, 'pr_url': 'https://github.com/owner/repo/pull/7'}, 'VERIFY': {'result': {'merged': True, 'merge_sha': 'c' * 40}}}[phase]

    def inspect(self, job):
        self.calls.append('inspect')
        return copy.deepcopy(self.evidence)

    def merge(self, job, evidence):
        self.calls.append('merge')
        return {'merge_sha': 'c' * 40}

    def reconcile(self, job, phase):
        self.calls.append('reconcile:' + phase)
        return self.recovered


class DurableEngineeringTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.time = 1000.
        self.runtime = SimpleNamespace(LLAMA_BASE='http://localhost:8080', GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                       _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / 'jobs.db'))
        self.store = EngineeringStore(self.runtime, lambda: self.time)
        self.backend = Backend()
        self.service = EngineeringService(self.store, self.backend)
        self.request = {'repository': 'owner/repo', 'branch': 'feature/job', 'base': 'main', 'objective': 'Repair the build',
                        'model': 'local', 'profile_id': 'test-profile', 'request_id': 'request', 'chat_id': 12,
                        'acceptance': [{'id': 'build-fix', 'paths': ['file'], 'checks': ['tests']}]}
        # These tests exercise durable state transitions, not a model runtime.
        # Actual metadata/HTTP/queue/repair integration has separate socket tests.
        self.service.capabilities._probe = lambda route, model: {
            'requested_model_id': model, 'reported_model_id': model, 'limits_verified': True,
            'context_tokens': 32768, 'max_output_tokens': 32768, 'model_revision': None,
            'template_digest': None, 'capability_revision': fingerprint([route, model]),
            'supported': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']}
        DurableEngineeringTests.admit(self)

    def admit(self, principal='owner'):
        result = self.service.prepare_binding(principal, {
            'profile_uid': 'test-profile', 'profile_revision': 'test-1', 'provider_connection_id': 'test-connection',
            'provider_connection_revision': 'test-1', 'provider_kind': 'LLAMA', 'configured_endpoint': self.runtime.LLAMA_BASE,
            'requested_model_id': 'local', 'generation_parameters': {}, 'privacy_scope': 'test-scope', 'tool_owner': 'GATEWAY',
            'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
        self.request['execution_binding_id'] = result['binding']['binding_id']

    def tearDown(self):
        self.temp.cleanup()

    def ready(self):
        job = self.store.create('owner', self.request)
        for _ in range(7):
            self.service.tick(job['id'])
        job = self.store.get(job['id'])
        self.assertEqual('MERGE_READY', job['state'])
        return job

    def test_durable_job_runs_to_ready_without_an_android_connection_and_waits(self):
        job = self.ready()
        self.assertNotIn('merge', self.backend.calls)
        self.assertEqual([], self.store.due())
        self.service.tick(job['id'])
        self.assertNotIn('merge', self.backend.calls)
        reopened = EngineeringStore(self.runtime, lambda: self.time).get(job['id'], 'owner')
        self.assertEqual(job, reopened)
        self.assertIsNone(self.store.get(job['id'], 'someone_else'))
        updates = self.store.updates(job['id'], 2)
        self.assertTrue(all(update['revision'] > 2 for update in updates))
        self.assertEqual(job['revision'], updates[-1]['revision'])
        self.assertTrue(public_job(job)['merge_ready'])

    def test_duplicate_creation_returns_original_and_conflicting_payload_rejected(self):
        first = self.store.create('owner', self.request)
        self.assertEqual(first['id'], self.store.create('owner', self.request)['id'])
        with self.assertRaises(ValueError):
            self.store.create('owner', dict(self.request, objective='different'))

    def test_worker_matches_admission_and_revoked_permissions_stop_execution(self):
        devices = SimpleNamespace(list_devices=lambda: [('paired', 'phone', 0, 1000), ('revoked', 'old', 1, 1000)])
        config = SimpleNamespace(mode='loopback', legacy_bearer=True)
        self.assertTrue(principal_authorized(config, 'local:single-user', devices))
        self.assertTrue(principal_authorized(config, 'paired', devices))
        self.assertFalse(principal_authorized(config, 'revoked', devices))
        config = SimpleNamespace(mode='trusted_proxy', trusted_users={'user': {'principalId': 'owner', 'scopes': ['engineering']}})
        self.assertTrue(principal_authorized(config, 'tailnet:owner', devices))
        config.trusted_users['user']['scopes'] = ['chat', 'jobs', 'tools']
        self.assertFalse(principal_authorized(config, 'tailnet:owner', devices))
        job = self.store.create('owner', self.request)
        self.service.authorized = lambda job: False
        self.service.tick(job['id'])
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
        self.assertEqual([], self.backend.calls)

    def test_exact_user_approval_merges_once_and_verifies(self):
        job = self.ready()
        self.service.approve(job['id'], 'owner', job['revision'], 7, 'a' * 40)
        self.service.tick(job['id'])
        self.service.tick(job['id'])
        self.service.tick(job['id'])
        self.assertEqual(1, self.backend.calls.count('merge'))
        self.assertEqual('COMPLETED', self.store.get(job['id'])['state'])

    def test_head_base_policy_and_expiry_invalidate_approval(self):
        for key, value in [('head_sha', 'd' * 40), ('base_sha', 'd' * 40), ('policy_verified', False), ('reviews_satisfied', False), ('draft', True), ('checks_passed', False), ('expired', True)]:
            with self.subTest(key=key):
                self.request['request_id'] = key
                self.backend.evidence.update(head_sha='a' * 40, base_sha='b' * 40, policy_verified=True, reviews_satisfied=True, draft=False, checks_passed=True)
                job = self.ready()
                self.service.approve(job['id'], 'owner', job['revision'], 7, 'a' * 40)
                if key == 'expired':
                    self.time += 1801
                else:
                    self.backend.evidence[key] = value
                self.service.tick(job['id'])
                self.assertEqual('CI', self.store.get(job['id'])['state'])
                self.assertIsNone(self.store.get(job['id'])['approval'])
        self.assertNotIn('merge', self.backend.calls)

    def test_model_cannot_approve_and_wrong_owner_or_sha_is_rejected(self):
        job = self.ready()
        for principal, sha in [('other', 'a' * 40), ('owner', 'd' * 40)]:
            with self.assertRaises(ValueError):
                self.service.approve(job['id'], principal, job['revision'], 7, sha)
        self.assertNotIn('merge', self.backend.calls)

    def test_crash_after_dispatch_reconciles_without_repeating_write(self):
        job = self.store.create('owner', self.request)
        for _ in range(4):
            self.service.tick(job['id'])
        self.assertEqual('COMMIT', self.store.get(job['id'])['state'])
        self.assertIsNotNone(self.store.claim(job['id']))  # Simulate process death during I/O.
        self.store.recover()
        self.backend.recovered = {'head_sha': 'a' * 40}
        EngineeringService(self.store, self.backend).tick(job['id'])
        self.assertEqual('OPEN_PR', self.store.get(job['id'])['state'])
        self.assertNotIn('COMMIT', self.backend.calls)
        self.assertIn('reconcile:COMMIT', self.backend.calls)

    def test_unknown_mutation_stops_for_reconciliation(self):
        job = self.store.create('owner', self.request)
        for _ in range(4):
            self.service.tick(job['id'])
        self.store.claim(job['id'])
        self.store.recover()
        self.service.tick(job['id'])
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
        self.assertNotIn('COMMIT', self.backend.calls)

    def test_cancellation_wins_over_late_completion(self):
        job = self.store.create('owner', self.request)
        claimed, lease, _ = self.store.claim(job['id'])
        self.store.control(job['id'], 'owner', 'cancel', job['revision'])
        self.assertFalse(self.store.complete(claimed, lease, 'READ', {'ok': True}))
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])

    def test_network_interruption_retries_with_a_separate_bounded_budget(self):
        job = self.store.create('owner', self.request)
        self.backend.step = lambda *args: (_ for _ in ()).throw(requests.ConnectionError('offline'))
        for _ in range(4):
            self.service.tick(job['id'])
        current = self.store.get(job['id'])
        self.assertEqual('PLAN', current['state'])
        self.assertEqual(0, current['stalls'])
        for _ in range(8):
            self.service.tick(job['id'])
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])

    def test_definitive_http_failures_pause_without_repeating_the_backend_request(self):
        for phase in ('PLAN', 'CI'):
            for status in (400, 401, 403, 404, 409, 422):
                with self.subTest(phase=phase, status=status):
                    self.request['request_id'] = f'blocked-{phase}-{status}'
                    job = self.store.create('owner', self.request)
                    claimed, lease, _ = self.store.claim(job['id'])
                    claimed['data']['retained_evidence'] = 'confirmed-before-failure'
                    self.store.complete(claimed, lease, phase, {})
                    calls = []
                    def fail(*args):
                        calls.append(phase)
                        response = requests.Response()
                        response.status_code = status
                        response.url = 'https://private.invalid/?token=secret'
                        raise requests.HTTPError('private upstream detail', response=response)
                    self.backend.step = fail
                    self.backend.inspect = fail
                    self.service.tick(job['id'])
                    current = self.store.get(job['id'])
                    self.assertEqual('NEEDS_INPUT', current['state'])
                    self.assertEqual('confirmed-before-failure', current['data']['retained_evidence'])
                    self.assertFalse(current['data']['blocked_reason']['retryable'])
                    self.assertEqual(status, current['data']['blocked_reason']['http_status'])
                    expected = ('ENGINEERING_AUTHENTICATION_REQUIRED' if status == 401 else
                                'ENGINEERING_ACCESS_DENIED' if status == 403 else 'ENGINEERING_VALIDATION_REJECTED')
                    self.assertEqual(expected, current['data']['blocked_reason']['code'])
                    for _ in range(5):
                        self.time += 600
                        self.assertNotIn(job['id'], self.store.due())
                        self.service.tick(job['id'])
                    self.assertEqual([phase], calls)
                    self.assertNotIn('secret', str(self.store.get(job['id'])))
                    self.assertNotIn('private upstream detail', str(self.store.get(job['id'])))

    def test_permission_validation_and_interruption_pause_after_one_stage_attempt(self):
        for error in (PermissionError('private authority'), ValueError('invalid immutable contract'),
                      TypeError('invalid selected input shape'), InterruptedError('private capture revoked')):
            with self.subTest(error=type(error).__name__):
                self.request['request_id'] = type(error).__name__
                job = self.store.create('owner', self.request)
                calls = []
                def fail(*args):
                    calls.append('PLAN')
                    raise error
                self.backend.step = fail
                self.service.tick(job['id'])
                self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
                for _ in range(5):
                    self.time += 600
                    self.assertNotIn(job['id'], self.store.due())
                    self.service.tick(job['id'])
                self.assertEqual(['PLAN'], calls)
                self.assertFalse(self.store.get(job['id'])['data']['blocked_reason']['retryable'])

    def test_validation_failure_after_dispatch_preserves_unknown_outcome_and_never_replays_write(self):
        job = self.store.create('owner', self.request)
        for _ in range(4):
            self.service.tick(job['id'])
        self.assertEqual('COMMIT', self.store.get(job['id'])['state'])
        self.backend.physical_operation_journal = True
        writes = []
        def fail(claimed, phase):
            self.assertEqual('COMMIT', phase)
            claimed['data']['execution_integrity'] = 'VERIFIED'
            operation = self.service.operations.prepare(claimed, 'PATCH', '/repos/owner/repo/git/refs/heads/feature/job', {'sha': 'a' * 40})
            self.service.operations.dispatch(claimed, operation)
            writes.append(operation['operation_id'])
            error = ValueError('Unparseable reply after physical dispatch')
            self.service.operations.failed(claimed, operation, error)
            raise error
        self.backend.step = fail
        self.service.tick(job['id'])
        current = self.store.get(job['id'])
        self.assertEqual('NEEDS_INPUT', current['state'])
        self.assertEqual('UNKNOWN_OUTCOME', current['data']['latest_operation']['state'])
        self.assertTrue(current['data']['latest_operation']['dispatched'])
        self.assertIn('reconcile', current['message'].lower())
        self.assertNotIn('blocked_reason', current['data'])
        for _ in range(5):
            self.time += 600
            self.assertNotIn(job['id'], self.store.due())
            self.service.tick(job['id'])
        self.assertEqual(1, len(writes))
        self.assertEqual(1, self.backend.calls.count('reconcile:COMMIT'))
        self.assertEqual('UNKNOWN_OUTCOME', self.store.get(job['id'])['data']['latest_operation']['state'])

    def test_stale_prompt_is_retired_and_unchanged_ci_polls_do_not_duplicate_updates(self):
        job = self.ready()
        self.backend.evidence['checks_passed'] = False
        with self.assertRaises(ValueError):
            self.service.approve(job['id'], 'owner', job['revision'], 7, 'a' * 40)
        self.assertEqual('CI', self.store.get(job['id'])['state'])
        self.service.tick(job['id'])
        count = len(self.store.updates(job['id']))
        for _ in range(10):
            self.service.tick(job['id'])
        self.assertEqual(count, len(self.store.updates(job['id'])))

    def test_worker_recovers_failed_checkpoint_without_a_client_or_repeated_commit(self):
        job = self.store.create('owner', self.request)
        complete = self.store.complete
        failed = False
        def save(current, *args, **kwargs):
            nonlocal failed
            if current['state'] == 'COMMIT' and not failed:
                failed = True
                raise OSError('simulated interrupted journal write')
            return complete(current, *args, **kwargs)
        self.store.complete = save
        self.backend.recovered = {'head_sha': 'a' * 40}
        self.service.start()
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and self.store.get(job['id'])['state'] != 'MERGE_READY':
                self.service.wake.set()
                threading.Event().wait(.02)
            self.assertEqual('MERGE_READY', self.store.get(job['id'])['state'])
            self.assertEqual(1, self.backend.calls.count('COMMIT'))
            self.assertIn('reconcile:COMMIT', self.backend.calls)
        finally:
            self.service.stop.set()
            self.service.wake.set()
            self.service.thread.join(3)

    def test_supervisor_bounds_duration_repairs_and_until_complete(self):
        job = self.store.create('owner', dict(self.request, duration_hours=0))
        self.assertEqual(72 * 3600, job['deadline'] - job['created_at'])
        self.assertIsNotNone(EngineeringSupervisor.limit(job, job['deadline']))
        self.assertTrue(all(EngineeringSupervisor.repair(job, 'same failure') for _ in range(5)))
        self.assertFalse(EngineeringSupervisor.repair(job, 'same failure'))
        for change in ({'duration_hours': -1}, {'repair_attempts': 6}, {'branch': 'main'}, {'branch': '../main'}):
            with self.assertRaises(ValueError):
                validate_request(dict(self.request, **change))

    def test_anchored_edits_sha_conflicts_and_partial_file_protection(self):
        text = 'start\nunique anchor\n' + 'large file\n' * 2000
        sha = blob_sha(text)
        files = {'src/file': {'sha': sha, 'content': text}}
        tree = {'src/file': {'sha': sha, 'mode': '100644'}}
        change = {'path': 'src/file', 'expected_blob_sha': sha, 'edits': [{'old': 'unique anchor', 'new': 'fixed anchor'}]}
        result = prepare_changes({'changes': [change]}, files, tree)
        self.assertEqual(text.replace('unique anchor', 'fixed anchor'), result[0]['content'])
        for invalid in (dict(change, expected_blob_sha='d' * 40), {'path': '../escape', 'content': 'oops'},
                        {'path': 'src/file', 'expected_blob_sha': sha, 'content': 'partial replacement'},
                        dict(change, edits=[{'old': 'large file', 'new': 'ambiguous'}])):
            with self.assertRaises(ValueError):
                prepare_changes({'changes': [invalid]}, files, tree)
