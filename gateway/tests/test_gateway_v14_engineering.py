import copy
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
import time
import requests
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering import EngineeringStore, EngineeringService, EngineeringSupervisor, public_job, validate_request, principal_authorized, digest
from v14.engineering_defaults import DEFAULT_EXECUTION_BUDGET, LEGACY_EXECUTION_BUDGET
from v14.engineering_backend import prepare_changes, blob_sha
from v14.engineering_batch_policy import DEFAULTS as BATCH_POLICY_DEFAULTS
from v14.execution_binding import ExecutionBindingError, fingerprint
from v14.github_api_governor import GitHubApiRateWait


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

    def test_new_default_is_frozen_ultra_and_explicit_smaller_limits_remain_authoritative(self):
        original = copy.deepcopy(self.request)
        job = self.store.create('owner', self.request)
        self.assertEqual(24 * 3600, job['deadline'] - job['created_at'])
        self.assertEqual(DEFAULT_EXECUTION_BUDGET, job['request']['execution_budget'])
        self.assertEqual(original, self.request)
        self.assertEqual(job['id'], self.store.create('owner', self.request)['id'])
        public = public_job(job)
        self.assertEqual(24, public['duration_hours'])
        self.assertEqual(DEFAULT_EXECUTION_BUDGET, public['execution_budget'])
        small = self.store.create('owner', dict(self.request, request_id='smaller', duration_hours=3,
                                              execution_budget={'model_calls': 8, 'model_tokens': 4096}))
        self.assertEqual(3 * 3600, small['deadline'] - small['created_at'])
        self.assertEqual(8, small['request']['execution_budget']['model_calls'])
        self.assertEqual(4096, small['request']['execution_budget']['model_tokens'])
        with self.assertRaises(ValueError):
            self.store.create('owner', dict(self.request, execution_budget={'model_calls': 8}))
        with self.assertRaises(ValueError):
            validate_request(dict(self.request, execution_budget={'model_calls': 513}))

    def test_legacy_lost_response_reconciles_without_upgrading_frozen_budget_or_dispatching(self):
        job = self.store.create('owner', self.request)
        legacy = copy.deepcopy(job)
        legacy['request'] = validate_request(self.request)
        legacy['deadline'] = legacy['created_at'] + 3 * 3600
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=?,request_digest=? WHERE id=?',
                                           (json.dumps(legacy), digest(legacy['request']), job['id'])))
        restored = self.store.create('owner', self.request)
        self.assertEqual(job['id'], restored['id'])
        self.assertEqual(3, restored['request']['duration_hours'])
        self.assertNotIn('execution_budget', restored['request'])
        self.assertEqual(legacy['deadline'], restored['deadline'])
        self.assertEqual(LEGACY_EXECUTION_BUDGET, public_job(restored)['execution_budget'])
        with self.assertRaises(ValueError):
            self.store.create('owner', dict(self.request, duration_hours=24))
        self.assertEqual(1, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0]))
        self.assertEqual([], self.backend.calls)

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

    def enable_full_batch(self):
        policy = patch(
            'v14.engineering.load_batch_policy',
            return_value=dict(BATCH_POLICY_DEFAULTS, default_mode='full', full_enabled=True),
        )
        policy.start()
        self.addCleanup(policy.stop)
        self.request.update(execution_mode='full', batch_policy_version=1)

    def test_full_batch_tracks_approval_through_verified_completion(self):
        self.enable_full_batch()
        job = self.ready()
        self.service.approve(job['id'], 'owner', job['revision'], 7, 'a' * 40)
        approved = self.store.get(job['id'])
        self.assertEqual('MERGE', public_job(approved)['batch_progress']['current_phase'])
        self.service.tick(job['id'])
        self.service.tick(job['id'])
        reopened = EngineeringStore(self.runtime, lambda: self.time).get(job['id'], 'owner')
        self.assertEqual('COMPLETED', reopened['state'])
        self.assertEqual('completed', public_job(reopened)['batch_progress']['status'])
        self.assertEqual('VERIFY', public_job(reopened)['batch_progress']['current_phase'])
        self.assertEqual(1, self.backend.calls.count('merge'))

    def test_full_batch_tracks_cancel_without_accepting_late_worker_output(self):
        self.enable_full_batch()
        job = self.store.create('owner', self.request)
        claimed, lease, _ = self.store.claim(job['id'])
        self.assertEqual('running', public_job(self.store.get(job['id']))['batch_progress']['current_state'])
        cancelled = self.store.control(job['id'], 'owner', 'cancel', job['revision'])
        self.assertEqual('cancelling', public_job(cancelled)['batch_progress']['status'])
        self.assertFalse(self.store.complete(claimed, lease, 'READ', {'ok': True}))
        stored = self.store.get(job['id'])
        self.assertEqual('CANCELLED', stored['state'])
        self.assertEqual('cancelled', public_job(stored)['batch_progress']['status'])
        self.assertEqual(0, public_job(stored)['batch_progress']['completed'])

    def test_full_batch_tracks_changed_readiness_and_request_changes(self):
        self.enable_full_batch()
        job = self.ready()
        self.backend.evidence['checks_passed'] = False
        with self.assertRaises(ValueError):
            self.service.approve(job['id'], 'owner', job['revision'], 7, 'a' * 40)
        waiting = self.store.get(job['id'])
        self.assertEqual('CI', waiting['state'])
        self.assertEqual('CI', public_job(waiting)['batch_progress']['current_phase'])
        changed = self.store.control(job['id'], 'owner', 'request_changes', waiting['revision'])
        self.assertEqual('needs_attention', public_job(changed)['batch_progress']['status'])
        cancelled = self.store.control(job['id'], 'owner', 'cancel', changed['revision'])
        self.assertEqual('cancelled', public_job(cancelled)['batch_progress']['status'])
        self.assertNotIn('merge', self.backend.calls)

    def test_full_batch_model_wait_is_visible_and_claimed_as_running_when_due(self):
        self.enable_full_batch()
        self.backend.step = lambda *args: (_ for _ in ()).throw(ExecutionBindingError('EXECUTION_MODEL_LOADING', 'Loading'))
        job = self.store.create('owner', self.request)
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.assertEqual('waiting_model', public_job(waiting)['batch_progress']['current_state'])
        self.assertIsNone(self.store.claim(job['id']))
        self.time = waiting['provider_wait']['retry_at'] + 1
        claimed, _, _ = self.store.claim(job['id'])
        self.assertEqual('running', public_job(claimed)['batch_progress']['current_state'])
        self.assertEqual(0, public_job(claimed)['batch_progress']['completed'])

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

    def test_server_cooldown_is_preserved_in_the_durable_schedule(self):
        job = self.store.create('owner', self.request)
        response = requests.Response()
        response.status_code = 429
        response.headers['Retry-After'] = '90'
        self.backend.step = lambda *args: (_ for _ in ()).throw(requests.HTTPError('fixture limit', response=response))
        self.service.tick(job['id'])
        self.assertEqual('PLAN', self.store.get(job['id'])['state'])
        self.time += 89
        self.assertNotIn(job['id'], self.store.due())
        self.time += 2
        self.assertIn(job['id'], self.store.due())
        reopened = EngineeringStore(self.runtime, lambda: self.time)
        self.assertIn(job['id'], reopened.due())

    def test_long_provider_cooldown_resumes_same_stage_after_store_recovery(self):
        job = self.store.create('owner', self.request)
        original_step = self.backend.step
        response = requests.Response()
        response.status_code = 429
        response.headers['Retry-After'] = '600'
        attempts = []
        def step(current, phase):
            attempts.append((current['id'], phase))
            if len(attempts) == 1:
                raise requests.HTTPError('fixture rate limit', response=response)
            return original_step(current, phase)
        self.backend.step = step
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.assertEqual('PLAN', waiting['state'])
        self.assertEqual('RETRY_AFTER', waiting['provider_wait']['source'])
        self.assertGreaterEqual(waiting['provider_wait']['retry_at'], self.time + 600)
        self.assertEqual(0, waiting['stalls'])
        self.store.recover()
        self.time += 599
        self.assertNotIn(job['id'], self.store.due())
        self.service.tick(job['id'])
        self.assertEqual([(job['id'], 'PLAN')], attempts)
        self.time += 2
        self.service.tick(job['id'])
        resumed = self.store.get(job['id'])
        self.assertEqual('READ', resumed['state'])
        self.assertNotIn('provider_wait', resumed)
        self.assertEqual([(job['id'], 'PLAN'), (job['id'], 'PLAN')], attempts)

    def test_repeated_provider_cooldowns_keep_the_durable_job_until_its_deadline(self):
        job = self.store.create('owner', self.request)
        response = requests.Response()
        response.status_code = 429
        response.headers['Retry-After'] = '1'
        self.backend.step = lambda *args: (_ for _ in ()).throw(requests.HTTPError('fixture rate limit', response=response))
        for attempt in range(1, 11):
            self.service.tick(job['id'])
            waiting = self.store.get(job['id'])
            self.assertEqual('PLAN', waiting['state'])
            self.assertEqual(attempt, waiting['provider_wait']['attempts'])
            self.assertEqual('RETRY_AFTER', waiting['provider_wait']['source'])
            self.assertEqual(0, waiting['stalls'])
            self.time = waiting['provider_wait']['retry_at'] + .1
        self.assertNotIn('blocked_reason', self.store.get(job['id'])['data'])

    def test_secondary_provider_limit_uses_the_same_durable_wait(self):
        job = self.store.create('owner', self.request)
        response = requests.Response()
        response.status_code = 403
        response.headers['X-RateLimit-Remaining'] = '0'
        response.headers['Retry-After'] = '45'
        self.backend.step = lambda *args: (_ for _ in ()).throw(requests.HTTPError('fixture secondary limit', response=response))

        self.service.tick(job['id'])

        waiting = self.store.get(job['id'])
        self.assertEqual('PLAN', waiting['state'])
        self.assertEqual('RETRY_AFTER', waiting['provider_wait']['source'])
        self.assertGreaterEqual(waiting['provider_wait']['retry_at'], self.time + 45)
        self.assertEqual(0, waiting['stalls'])
        self.assertNotIn('blocked_reason', waiting['data'])

    def test_selected_model_readiness_wait_resumes_the_same_bound_stage(self):
        job = self.store.create('owner', self.request)
        original_probe = self.service.capabilities._probe

        def not_loaded(*_args, **_kwargs):
            error = ExecutionBindingError(
                'EXECUTION_MODEL_NOT_LOADED',
                'The exact selected model is advertised but not loaded.',
                503,
            )
            error.retryable = True
            raise error

        self.service.capabilities._probe = not_loaded
        self.service.capabilities.cache.clear()
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.assertEqual('PLAN', waiting['state'])
        self.assertEqual('MODEL_READINESS', waiting['provider_wait']['source'])
        self.assertIn('exact selected model', waiting['message'])
        self.assertNotIn('blocked_reason', waiting['data'])
        self.assertEqual(0, waiting['stalls'])
        self.assertEqual([], self.backend.calls)

        self.time = waiting['provider_wait']['retry_at'] + .1
        self.service.capabilities._probe = original_probe
        self.service.capabilities.cache.clear()
        self.service.tick(job['id'])
        resumed = self.store.get(job['id'])
        self.assertEqual('READ', resumed['state'])
        self.assertNotIn('provider_wait', resumed)
        self.assertEqual(['PLAN'], self.backend.calls)

    def test_stop_during_provider_cooldown_prevents_due_dispatch(self):
        job = self.store.create('owner', self.request)
        response = requests.Response()
        response.status_code = 429
        self.backend.step = lambda *args: (_ for _ in ()).throw(requests.HTTPError('fixture rate limit', response=response))
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.assertEqual('APP_BACKOFF', waiting['provider_wait']['source'])
        self.store.control(job['id'], 'owner', 'cancel', waiting['revision'])
        self.time += 120
        self.assertNotIn(job['id'], self.store.due())
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])

    def test_retryable_selected_backend_outage_retains_deadline_binding_and_due_stage(self):
        job = self.store.create('owner', self.request)
        original_probe = self.service.capabilities._probe
        attempts = []
        def unavailable(*args, **kwargs):
            attempts.append(1)
            error = ExecutionBindingError('EXECUTION_BACKEND_UNAVAILABLE', 'Selected backend temporarily unavailable.', 503)
            error.retryable = True
            error.observation = {'failureCategory': 'connection_refused', 'reasonCode': 'BACKEND_CONNECTION_REFUSED',
                                 'dependency': 'backend_models', 'retryable': True}
            raise error
        self.service.capabilities._probe = unavailable
        self.service.capabilities.cache.clear()
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.assertEqual('PLAN', waiting['state'])
        self.assertEqual('BACKEND_READINESS', waiting['provider_wait']['source'])
        self.assertEqual('EXECUTION_BACKEND_UNAVAILABLE', waiting['provider_wait']['reason_code'])
        self.assertEqual(job['deadline'], waiting['deadline'])
        self.assertEqual(job['request']['execution_binding_id'], waiting['request']['execution_binding_id'])
        self.assertEqual(0, waiting['stalls'])
        self.assertEqual([], self.backend.calls)
        self.service.tick(job['id'])
        self.assertEqual(1, len(attempts))
        # A host restart retains the pending time and cannot dispatch early.
        reopened = EngineeringStore(self.runtime, lambda: self.time)
        reopened.recover()
        self.assertEqual(waiting['provider_wait'], reopened.get(job['id'])['provider_wait'])
        self.assertNotIn(job['id'], reopened.due())
        self.time = waiting['provider_wait']['retry_at'] + .1
        self.service.capabilities._probe = original_probe
        self.service.capabilities.cache.clear()
        self.service.tick(job['id'])
        resumed = self.store.get(job['id'])
        self.assertEqual('READ', resumed['state'])
        self.assertNotIn('provider_wait', resumed)
        self.assertEqual(['PLAN'], self.backend.calls)
        self.assertEqual(job['request']['execution_binding_id'], resumed['request']['execution_binding_id'])

    def test_selected_backend_wait_obeys_deadline_cancel_and_full_batch_policy(self):
        original_step = self.backend.step
        def unavailable(*args):
            error = ExecutionBindingError('EXECUTION_BACKEND_UNAVAILABLE', 'Selected backend unavailable.', 503)
            error.retryable = True
            raise error
        self.backend.step = unavailable
        job = self.store.create('owner', self.request)
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.store.control(job['id'], 'owner', 'cancel', waiting['revision'])
        self.time = waiting['provider_wait']['retry_at'] + 1
        self.assertNotIn(job['id'], self.store.due())
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])
        deadline_job = self.store.create('owner', dict(self.request, request_id='backend-deadline'))
        self.time = deadline_job['deadline'] - deadline_job['request']['execution_budget']['final_seconds'] - 1
        self.service.tick(deadline_job['id'])
        blocked = self.store.get(deadline_job['id'])
        self.assertEqual('NEEDS_INPUT', blocked['state'])
        self.assertFalse(blocked['data']['blocked_reason']['retryable'])
        self.assertNotIn('provider_wait', blocked)
        self.enable_full_batch()
        self.request['batch_preferences'] = {'resume_after_transient_limits': False}
        full_job = self.store.create('owner', dict(self.request, request_id='backend-no-autoresume'))
        self.service.tick(full_job['id'])
        self.assertEqual('NEEDS_INPUT', self.store.get(full_job['id'])['state'])
        self.assertNotIn('provider_wait', self.store.get(full_job['id']))
        self.backend.step = original_step

    def test_typed_backend_recovery_never_replays_unknown_writes_or_denied_metadata(self):
        for code, retryable in [('EXECUTION_BACKEND_UNAVAILABLE', False), ('EXECUTION_METADATA_DENIED', False),
                                ('EXECUTION_MODEL_MISMATCH', True)]:
            with self.subTest(code=code):
                error = ExecutionBindingError(code, 'Selected route cannot be used.', 403)
                error.retryable = retryable
                self.backend.step = lambda *args: (_ for _ in ()).throw(error)
                job = self.store.create('owner', dict(self.request, request_id=code))
                self.service.tick(job['id'])
                self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
                self.assertNotIn('provider_wait', self.store.get(job['id']))
        self.backend.step = Backend.step.__get__(self.backend, Backend)
        mutation = self.store.create('owner', dict(self.request, request_id='unknown-mutation'))
        for _ in range(4):
            self.service.tick(mutation['id'])
        self.assertEqual('COMMIT', self.store.get(mutation['id'])['state'])
        error = ExecutionBindingError('EXECUTION_BACKEND_UNAVAILABLE', 'Lost mutation outcome.', 503)
        error.retryable = True
        error.dispatched = True
        self.backend.step = lambda *args: (_ for _ in ()).throw(error)
        self.service.tick(mutation['id'])
        self.assertEqual('NEEDS_INPUT', self.store.get(mutation['id'])['state'])
        self.assertIn('reconcile:COMMIT', self.backend.calls)
        self.assertNotIn('COMMIT', self.backend.calls)
        self.assertNotIn('provider_wait', self.store.get(mutation['id']))

    def test_host_shutdown_preserves_a_nonmutating_stage(self):
        job = self.store.create('owner', self.request)
        original_step = self.backend.step
        def interrupt(*args):
            self.service.stop.set()
            raise InterruptedError('gateway shutdown')
        self.backend.step = interrupt
        self.service.tick(job['id'])
        saved = self.store.get(job['id'])
        self.assertEqual('PLAN', saved['state'])
        self.assertEqual(0, saved['stalls'])
        self.service.stop.clear()
        self.store.recover()
        self.backend.step = original_step
        self.service.tick(job['id'])
        self.assertEqual('READ', self.store.get(job['id'])['state'])

    def test_tls_failures_and_excessive_cooldowns_pause_without_replaying_stages(self):
        response = requests.Response()
        response.status_code = 503
        response.headers['Retry-After'] = '600'
        errors = (requests.exceptions.SSLError('fixture TLS secret'), requests.HTTPError('fixture cooldown secret', response=response))
        for index, error in enumerate(errors):
            self.request['request_id'] = f'safe-stop-{index}'
            job = self.store.create('owner', self.request)
            calls = []
            def fail(*args):
                calls.append('PLAN')
                raise error
            self.backend.step = fail
            self.service.tick(job['id'])
            current = self.store.get(job['id'])
            self.assertEqual('NEEDS_INPUT', current['state'])
            self.assertFalse(current['data']['blocked_reason']['retryable'])
            self.assertNotIn('secret', str(current))
            self.time += 1000
            self.service.tick(job['id'])
            self.assertEqual(['PLAN'], calls)

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

    def test_api_cooldown_before_dispatch_preserves_the_mutation_stage(self):
        job = self.store.create('owner', self.request)
        for _ in range(4):
            self.service.tick(job['id'])
        self.assertEqual('COMMIT', self.store.get(job['id'])['state'])
        self.backend.physical_operation_journal = True
        original_step = self.backend.step

        def wait_for_capacity(claimed, phase):
            self.assertEqual('COMMIT', phase)
            claimed['data']['execution_integrity'] = 'VERIFIED'
            self.service.operations.prepare(claimed, 'PATCH', '/repos/owner/repo/git/refs/heads/feature/job', {'sha': 'a' * 40})
            raise GitHubApiRateWait(60)

        self.backend.step = wait_for_capacity
        self.service.tick(job['id'])
        waiting = self.store.get(job['id'])
        self.assertEqual('COMMIT', waiting['state'])
        self.assertEqual('PREPARED', waiting['data']['latest_operation']['state'])
        self.assertFalse(waiting['data']['latest_operation']['dispatched'])
        self.assertEqual('RETRY_AFTER', waiting['provider_wait']['source'])
        self.assertNotIn(job['id'], self.store.due())

        self.time = waiting['provider_wait']['retry_at'] + .1
        self.backend.step = original_step
        self.service.tick(job['id'])
        self.assertEqual('OPEN_PR', self.store.get(job['id'])['state'])
        self.assertNotIn('reconcile:COMMIT', self.backend.calls)

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
