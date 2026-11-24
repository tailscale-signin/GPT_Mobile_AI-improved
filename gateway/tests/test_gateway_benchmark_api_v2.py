"""Durable one-task admission and qualification recovery, without live writes."""
from copy import deepcopy
import hashlib
import json
import sqlite3
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from benchmarks import coding, fixtures, sandbox
from benchmarks.contracts import coding_score, content_digest
from benchmarks.engineering_qualification import PRODUCTION_SCHEMA, SCENARIOS
from benchmarks.external import task_summary
from benchmarks.qualification_service import QualificationService
from benchmarks.runner import BenchmarkRunner
from benchmarks.service import BenchmarkService, install_benchmark_routes
from benchmarks.store import BenchmarkConflict, BenchmarkStore
from v14.execution_binding import fingerprint
from v14.engineering_backend import blob_sha
from v14.identity import Principal
from test_gateway_benchmark_independent_grader import SETTINGS, submission


def bound_route():
    binding = {'binding_id': 'bind_' + 'a' * 32, 'profile_uid': 'profile', 'profile_revision': 'revision',
               'backend_endpoint_identity': 'endpoint', 'requested_model_id': 'selected',
               'generation_parameters': {'temperature': 0},
               'generation_parameters_digest': fingerprint({'temperature': 0})}
    route = {'model': 'selected', 'profile_uid': 'profile', 'profile_revision': 'revision',
             'binding_id': binding['binding_id'], 'endpoint_identity': 'endpoint',
             'base_sha': 'b' * 40, 'settings_digest': binding['generation_parameters_digest']}
    return binding, route


def qualification_value(execute=True):
    _, route = bound_route()
    prefix = 'benchmark/fixture01/'
    return {'request_id': 'qualification-request', 'execute': execute, 'manifest': {
        'schema': PRODUCTION_SCHEMA, 'test_repository': 'owner/fixture', 'disposable': True,
        'cohort': 'fixture01', 'expected_route': route,
        'cases': [{'scenario': name, 'request': {
            'repository': 'owner/fixture', 'branch': prefix + name, 'base': prefix + 'base',
            'source_sha': route['base_sha'], 'model': route['model'], 'profile_id': route['profile_uid'],
            'execution_binding_id': route['binding_id'], 'task_scope': 'implement',
            'objective': 'Verify the isolated ' + name + ' acceptance contract.',
            'request_id': 'request-' + name,
        }} for name in SCENARIOS]}}


class QualificationAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.store = BenchmarkStore(Path(self.directory.name) / 'benchmarks.sqlite3')
        self.binding, self.route = bound_route()
        self.effects, self.admissions = {}, []
        self.lose_response = False
        def admit(principal, request):
            self.admissions.append(request['request_id'])
            ident = self.effects.setdefault(request['request_id'], 'eng_' + format(len(self.effects) + 1, '032x'))
            if self.lose_response:
                self.lose_response = False
                raise TimeoutError('The accepted response was lost')
            return {'id': ident}
        engineering = SimpleNamespace(
            authorized=lambda job: job['principal'] == 'alice', admit=admit,
            validate_submission=lambda principal, request: deepcopy(self.binding),
            evidence_access=lambda principal, ident: {'request': {'repository': 'owner/fixture'}},
            wake=threading.Event(),
        )
        self.service = SimpleNamespace(store=self.store, engineering=engineering)
        self.qualifications = QualificationService(self.service)
        self.principal = Principal('alice', frozenset({'engineering'}))
        self.observer = patch('benchmarks.qualification_service.snapshot', side_effect=lambda service, owner, ident, scenario: {
            'schema': PRODUCTION_SCHEMA, 'scenario': scenario, 'job_id': ident, 'repository': 'owner/fixture',
            'status': 'PASSED', 'comparison_route': deepcopy(self.route), 'selected_route_verified': True})
        self.observer.start()
        self.addCleanup(self.observer.stop)

    def test_idempotent_creation_reconciles_lost_admission_response_after_restart(self):
        self.lose_response = True
        value = qualification_value()
        with self.assertRaises(TimeoutError):
            self.qualifications.create(self.principal, value)
        receipt = self.qualifications.history('alice', value['request_id'])
        self.assertEqual('OBSERVED', receipt['submissionState'])
        self.assertEqual('READBACK_REQUIRED', receipt['run']['state'])
        resumed = QualificationService(self.service).refresh('alice', receipt['run']['id'], execute=True)
        self.assertEqual('COMPLETED', resumed['state'])
        self.assertEqual(10, len(self.effects))
        self.assertEqual(11, len(self.admissions))
        self.assertEqual(self.admissions[0], self.admissions[1])
        self.assertEqual(10, len(resumed['jobIds']))
        self.assertTrue(resumed['result']['comparisonEligible'])
        again = self.qualifications.create(self.principal, value)
        self.assertEqual(resumed['id'], again['id'])
        self.assertEqual(11, len(self.admissions))
        with self.assertRaises(BenchmarkConflict):
            self.qualifications.create(self.principal, dict(value, execute=False))
        self.assertEqual('NOT_OBSERVED', self.qualifications.history('bob', value['request_id'])['submissionState'])
        self.assertNotIn('manifest', resumed)

    def test_read_only_saved_manifest_cannot_be_promoted_to_execution(self):
        saved = self.qualifications.create(self.principal, qualification_value(execute=False))
        self.assertEqual('AWAITING_EVIDENCE', saved['state'])
        self.assertEqual([], self.admissions)
        with self.assertRaises(PermissionError):
            self.qualifications.refresh('alice', saved['id'], execute=True)
        with self.assertRaises(KeyError):
            self.qualifications.refresh('bob', saved['id'])
        self.assertEqual([], self.admissions)

    def test_saved_score_readback_is_owner_profile_and_binding_specific(self):
        value = qualification_value(execute=False)
        revision = 'a' * 64
        self.binding['profile_revision'] = self.route['profile_revision'] = revision
        value['manifest']['expected_route']['profile_revision'] = revision
        saved = self.qualifications.create(self.principal, value)
        self.service.engineering.bindings = SimpleNamespace(bound_route=lambda owner, bind: (deepcopy(self.binding), {}))
        # A later report on a different profile must not obscure this record.
        self.service.engineering.authorized = lambda _: True
        other = deepcopy(value)
        other['request_id'] = 'other-profile'
        other['manifest']['expected_route']['profile_revision'] = 'b' * 64
        self.qualifications.create(self.principal, other)
        result = self.qualifications.intelligence_score(self.principal, revision)
        self.assertEqual(saved['id'], result['report']['id'])
        self.assertEqual(8, len(result['report']['score']['dimensions']))
        self.assertIsNone(result['report']['score']['pointsOutOf100'])
        self.assertNotIn('cases', result['report'])
        self.assertIsNone(self.qualifications.intelligence_score(Principal('bob', self.principal.scopes), revision)['report'])
        self.binding['backend_endpoint_identity'] = 'changed'
        with self.assertRaises(PermissionError):
            self.qualifications.intelligence_score(self.principal, revision)
        self.assertEqual([], self.admissions)

    def test_paired_score_readback_rechecks_granted_repository_and_role(self):
        owner = Principal('paired:alice', self.principal.scopes)
        self.service.engineering.authorized = lambda job: job['principal'] == owner.id
        self.service.engineering.bindings = SimpleNamespace(bound_route=lambda who, binding: (self.binding, {}))
        value = qualification_value(execute=False)
        revision = 'a' * 64
        self.binding['profile_revision'] = value['manifest']['expected_route']['profile_revision'] = revision
        self.service.runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=Path(self.directory.name) / 'jobs.sqlite3')
        with sqlite3.connect(self.service.runtime.GATEWAY_JOB_DB_PATH) as db:
            db.execute('CREATE TABLE gateway_control_devices(id TEXT,role TEXT,repositories TEXT,revoked INTEGER)')
            db.execute('INSERT INTO gateway_control_devices VALUES(?,?,?,0)', ('alice', 'owner', '["owner/fixture"]'))
        saved = self.qualifications.create(owner, value)
        self.assertEqual(saved['id'], self.qualifications.intelligence_score(owner, revision)['report']['id'])
        for role, repos, revoked in [('owner', '[]', 0), ('viewer', '["owner/fixture"]', 0), ('owner', '["owner/fixture"]', 1)]:
            with self.subTest(role=role, repos=repos, revoked=revoked):
                with sqlite3.connect(self.service.runtime.GATEWAY_JOB_DB_PATH) as db:
                    db.execute('UPDATE gateway_control_devices SET role=?,repositories=?,revoked=?', (role, repos, revoked))
                with self.assertRaises(PermissionError):
                    self.qualifications.intelligence_score(owner, revision)
        self.assertEqual([], self.admissions)

    def test_all_requests_are_validated_before_any_admission(self):
        mutations = (
            lambda value: value['manifest']['cases'][-1]['request'].update(base='main'),
            lambda value: value['manifest']['cases'][-1]['request'].update(model='other'),
            lambda value: value['manifest']['cases'][-1]['request'].update(branch=value['manifest']['cases'][-1]['request']['base']),
            lambda value: value['manifest']['cases'][-1]['request'].update(request_id='request-unfamiliar_bug'),
        )
        for mutate in mutations:
            value = qualification_value()
            mutate(value)
            with self.subTest(value=value['manifest']['cases'][-1]['scenario']):
                with self.assertRaises(ValueError):
                    self.qualifications.create(self.principal, value)
                self.assertEqual([], self.admissions)

    def test_changed_live_binding_blocks_before_admission(self):
        self.binding['profile_revision'] = 'changed'
        with self.assertRaises(ValueError):
            self.qualifications.create(self.principal, qualification_value())
        self.assertEqual([], self.admissions)

    def test_accepted_ids_are_saved_before_the_first_readback(self):
        with patch('benchmarks.qualification_service.snapshot', side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                self.qualifications.create(self.principal, qualification_value())
        saved = self.qualifications.history('alice', 'qualification-request')['run']
        self.assertEqual(1, len(saved['jobIds']))
        self.assertEqual('READBACK_REQUIRED', saved['state'])
        self.qualifications.refresh('alice', saved['id'])
        self.assertEqual(1, len(self.admissions), 'GET/readback must not admit missing scenarios')


class BenchmarkApiV2Tests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        runtime = SimpleNamespace(GATEWAY_JOB_DB_PATH=str(Path(self.directory.name) / 'jobs.sqlite3'),
            GATEWAY_DURABLE_JOBS=True, LLAMA_BASE='http://model', gateway_draining=False,
            gateway_service=SimpleNamespace(lock=threading.RLock(), instance_id='instance', draining=False, authorized=lambda _: True))
        engineering = SimpleNamespace(authorized=lambda _: True,
            store=SimpleNamespace(conversations=SimpleNamespace(gateway_id='gateway')))
        self.service = BenchmarkService(runtime, engineering)
        app = FastAPI()
        @app.middleware('http')
        async def identity(request: Request, next_handler):
            scopes = {'jobs', 'chat', 'tools'}
            if request.headers.get('x-test-scope') != 'read':
                scopes.add('engineering')
            request.state.principal = Principal(request.headers.get('x-test-owner', 'alice'), frozenset(scopes))
            return await next_handler(request)
        install_benchmark_routes(app, self.service)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)

    def test_score_route_requires_both_the_frozen_owner_and_engineering_permission(self):
        from desktop.control_server import proxy_permission
        from desktop.control_store import ControlError
        route = '/gateway/benchmarks/intelligence-score'
        grant = {'role': 'owner', 'repositories': '["owner/fixture"]'}
        proxy_permission(route, 'GET', {}, grant)
        with self.assertRaises(ControlError):
            proxy_permission(route, 'POST', {}, grant)
        self.service.engineering.store.conversations.gateway_id = lambda: 'gateway'
        scope = hashlib.sha256(b'gateway\x00alice').hexdigest()
        target = route + '?profile_revision=' + 'a' * 64 + '&owner_scope=' + scope
        headers = {'X-Gateway-Installation-ID': 'gateway', 'X-Gateway-Principal-ID': 'alice'}
        self.assertEqual(200, self.client.get(target, headers=headers).status_code)
        self.assertEqual(403, self.client.get(target).status_code)
        self.assertEqual(403, self.client.get(target, headers=dict(headers, **{'x-test-scope': 'read'})).status_code)
        self.assertEqual(403, self.client.get(target.replace(scope, 'c' * 64), headers=headers).status_code)
        self.assertEqual(409, self.client.get(target, headers=dict(headers, **{'x-test-owner': 'bob'})).status_code)

    def test_one_task_catalog_admission_and_recovery_share_frozen_digests(self):
        with patch.object(sandbox, 'preflight', return_value='docker'):
            catalog = self.client.get('/gateway/benchmarks/fixtures?seed=17&preset=quick').json()
            task = self.client.get('/gateway/benchmarks/fixtures/pagination-1?seed=17&preset=deep').json()
        self.assertEqual(12, len(catalog['tasks']))
        self.assertEqual(catalog['graderDigest'], task['graderDigest'])
        self.assertEqual(catalog['environmentDigest'], task['environmentDigest'])
        self.assertEqual(catalog['tasks'][0]['taskDigest'], task['taskDigest'])
        value = submission()
        value['proposal_digest'] = content_digest(value['proposal'])
        created = self.client.post('/gateway/benchmarks/evaluate', json=value)
        self.assertEqual(200, created.status_code, created.text)
        receipt = created.json()
        self.assertEqual(task['graderDigest'], receipt['graderDigest'])
        self.assertEqual(value['proposal_digest'], receipt['proposalDigest'])
        self.assertEqual(receipt['id'], self.client.post('/gateway/benchmarks/evaluate', json=value).json()['id'])
        observed = self.client.get('/gateway/benchmarks?request_id=' + value['request_id']).json()
        self.assertEqual('OBSERVED', observed['submissionState'])
        self.assertNotIn('proposal', observed['run']['request'])
        self.assertEqual(404, self.client.get('/gateway/benchmarks/' + receipt['id'], headers={'x-test-owner': 'bob'}).status_code)
        self.assertEqual(403, self.client.post('/gateway/benchmarks/evaluate', json=value, headers={'x-test-scope': 'read'}).status_code)
        self.assertEqual(400, self.client.get('/gateway/benchmarks/fixtures?seed=-1').status_code)
        mismatch = self.client.post('/gateway/benchmarks/evaluate', json=dict(value, request_id='changed-environment', environment_digest='0' * 64))
        self.assertEqual('BENCHMARK_ENVIRONMENT_CHANGED', mismatch.json()['error']['code'])

    def test_retirement_fences_late_admission_without_a_job_id(self):
        value = submission()
        retired = self.client.post('/gateway/benchmarks/cancel-request', json={'request_id': value['request_id']}).json()
        self.assertEqual('REMOVED', retired['submissionState'])
        self.assertTrue(retired['terminal'])
        self.assertEqual('NOT_DISPATCHED', retired['dispatchState'])
        self.assertEqual(409, self.client.post('/gateway/benchmarks/evaluate', json=value).status_code)
        self.assertEqual('REMOVED', self.client.get('/gateway/benchmarks?request_id=' + value['request_id']).json()['submissionState'])
        self.assertEqual('NOT_OBSERVED', self.client.get('/gateway/benchmarks?request_id=' + value['request_id'], headers={'x-test-owner': 'bob'}).json()['submissionState'])

    def test_retired_active_run_remains_observable_until_physical_cleanup(self):
        value = submission()
        run = self.client.post('/gateway/benchmarks/evaluate', json=value).json()
        self.service.store.update(run['id'], state='RUNNING')
        cancellation = self.service.active[run['id']] = threading.Event()
        name = 'gateway-benchmark-' + 'c' * 32
        self.service.store.claim_sandbox(run['id'], name)
        retired = self.client.post('/gateway/benchmarks/cancel-request', json={'request_id': value['request_id']}).json()
        self.assertEqual('OBSERVED', retired['submissionState'])
        self.assertEqual('CANCELLING', retired['run']['state'])
        self.assertTrue(cancellation.is_set())
        self.service.store.update(run['id'], state='INTERRUPTED', failure={'cancellationReceipts': [
            {'kind': 'sandbox', 'container': name, 'outcome': 'unknown'}]})
        self.assertEqual('OBSERVED', self.service.store.submission('alice', value['request_id'])['submissionState'])
        self.service.store.release_sandbox(run['id'], name)
        self.assertEqual('REMOVED', self.service.store.submission('alice', value['request_id'])['submissionState'])

    def test_runtime_setting_change_blocks_before_inference_or_execution(self):
        run = self.client.post('/gateway/benchmarks/evaluate', json=submission()).json()
        with patch('benchmarks.service.load_settings', return_value=SETTINGS), \
                patch('benchmarks.runner.client_session', side_effect=AssertionError('No inference after environment drift')):
            self.service.run_one(run['id'])
        saved = self.service.store.get(run['id'])
        self.assertEqual('BLOCKED', saved['state'])
        self.assertEqual('BENCHMARK_ENVIRONMENT_CHANGED', saved['failure']['reason'])

    def test_live_cleanup_blocks_admission_and_appends_settlement_without_rewriting_score(self):
        value = submission()
        run = self.client.post('/gateway/benchmarks/evaluate', json=value).json()
        receipt = {'repository': 'owner/fixture', 'baseRevision': 'b' * 40, 'publishedRevision': 'c' * 40,
                   'workflowPath': '.github/workflows/owned.yml', 'branch': 'benchmark/cohort/owned', 'mutations': []}
        cleanup = {'kind': 'github_actions', 'outcome': 'unknown', 'repository': receipt['repository'],
                   'revision': receipt['publishedRevision'], 'workflowPath': receipt['workflowPath']}
        self.service.store.update(run['id'], state='RUNNING', liveGitHubReceipt=receipt)
        before = self.service.store.update(run['id'], state='INTERRUPTED', failure={
            'code': 'CANCELLATION_UNCONFIRMED', 'cancellationReceipts': [cleanup]})
        self.assertEqual(1, len(self.service.external_cleanups()))
        self.assertEqual(409, self.client.post('/gateway/benchmarks/evaluate', json=dict(value, request_id='blocked-next')).status_code)
        with self.assertRaises(BenchmarkConflict):
            self.service.store.remove(run['id'], 'alice')
        def reconcile(service, owner, saved):
            self.assertEqual('alice', owner)
            self.assertNotIn(saved['id'], service.active)
            settled = dict(cleanup, outcome='confirmed', workflowRunId=42)
            service.store.update(saved['id'], liveGitHubReceipt=dict(receipt, cleanup=settled,
                cancellationMutation={'workflowRunId': 42, 'state': 'DISPATCHED'}))
            return [settled]
        with patch('benchmarks.live_github.reconcile_interrupted', side_effect=reconcile):
            retired = self.client.post('/gateway/benchmarks/cancel-request', json={'request_id': value['request_id']}).json()
        self.assertEqual('REMOVED', retired['submissionState'])
        self.assertEqual('BENCHMARK_STOPPED', retired['run']['failure']['code'])
        self.assertEqual(before['completionReceipt'], retired['run']['completionReceipt'])
        self.assertEqual(before['failure'], retired['run']['recordedFailure'])
        self.assertEqual([], self.service.external_cleanups())
        self.assertEqual(200, self.client.post('/gateway/benchmarks/evaluate', json=dict(value, request_id='allowed-next')).status_code)
        with self.assertRaises(BenchmarkConflict):
            self.service.store.update(run['id'], liveGitHubReceipt=dict(retired['run']['liveGitHubReceipt'], publishedRevision='d' * 40))

    def test_native_catalog_and_regular_runner_keep_outcomes_separate_from_rubric(self):
        from benchmarks import repository_tasks, repository_sandbox
        tasks = repository_tasks.task_manifest('quick', 17)
        with patch.object(sandbox, 'preflight', return_value='docker'):
            catalog = self.client.get('/gateway/benchmarks/fixtures?task_pack=repository&preset=quick').json()
            descriptor = self.client.get('/gateway/benchmarks/fixtures/' + tasks[1]['id'] + '?task_pack=repository').json()
        self.assertEqual(4, len(catalog['tasks']))
        self.assertEqual(catalog['environmentDigest'], descriptor['environmentDigest'])
        self.assertEqual(60, descriptor['environment']['seconds'])
        self.assertEqual('object', descriptor['outputSchema']['type'])
        request = dict(request_id='native-suite', model='selected', suite='coding', preset='quick', seed=17, task_pack='repository')
        run, _ = self.service.store.create('alice', request, {'executionIdentity': 'route'})
        self.service.store.update(run['id'], state='RUNNING')
        runner = BenchmarkRunner(self.service.store, 'http://unused', threading.Event(), lambda: 'route',
                                 lambda: dict(SETTINGS, repository_image='native@sha256:' + 'd' * 64))
        calls = {}
        def measure(_run, name, _turns, _expected, **options):
            task = next(task for task in tasks if task['id'] == name)
            calls[name] = calls.get(name, 0) + 1
            text = json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                           'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                                          for path, source in task['sources'].items()]})
            if name == tasks[0]['id'] and calls[name] == 1:
                text = 'invalid completed JSON'
            options['validate'](text)
            return {'finishReason': 'stop', 'totalMs': 2, 'firstMs': 1, 'inputTokens': 1, 'outputTokens': 1}
        def observed(task, sources, *_args, **_kwargs):
            return {'executed': True, 'runtime': {'kind': task['language']}, 'durationMs': 1,
                    'outputDigest': fingerprint(sources), 'outputs': task['expected'] if sources == task['referenceSources'] else []}
        with patch.object(sandbox, 'preflight', return_value='docker'), \
                patch.object(runner, 'measure_case', side_effect=measure), \
                patch.object(repository_sandbox, 'execute', side_effect=observed):
            summary = runner.code(run)
        self.assertTrue(summary['qualified'])
        self.assertIsNone(summary['score'])
        self.assertEqual(.75, summary['firstAttemptRate'])
        self.assertEqual(1, summary['finalAttemptRate'])
        self.assertEqual(1, summary['bestWithinBudgetRate'])
        saved = self.service.store.get(run['id'])
        self.assertEqual(4, len(saved['cases']))
        self.assertEqual(2, len(saved['cases'][0]['attempts']))
        self.assertNotIn('_candidate_sources', json.dumps(saved))

    def test_known_model_failures_stay_in_outcome_denominator(self):
        cases = []
        for task in fixtures.suite():
            case = coding.rejected_submission(task, 'PATCH_JSON_INVALID')
            case.update(attempts=[deepcopy(case)], firstSubmissionResolved=False,
                        initialResolved=False, finalResolved=False, bestResolved=False)
            cases.append(case)
        summary = coding_score(cases, 12, 'quick')
        self.assertIsNone(summary['score'])
        self.assertTrue(summary['outcomeComplete'])
        self.assertEqual(12, summary['knownOutcomes'])
        self.assertEqual(0, summary['firstAttemptRate'])
        self.assertEqual(0, summary['finalAttemptRate'])
        self.assertEqual(0, summary['bestWithinBudgetRate'])
        self.assertEqual(0, task_summary(cases[0])['firstAttemptRate'])
        native = task_summary({'graded': True, 'qualified': True, 'resolved': True, 'outcomeKnown': True,
                               'score': None, 'graderDigest': 'native', 'executionScope': 'ISOLATED_NATIVE_CONTRACT'})
        self.assertTrue(native['qualified'])
        self.assertEqual('OUTCOME_ONLY', native['scoreQualification'])
        self.assertEqual('native', native['graderDigest'])
        self.assertEqual(1, native['finalAttemptRate'])


if __name__ == '__main__':
    unittest.main()
