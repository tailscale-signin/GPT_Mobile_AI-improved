import json
import hashlib
import hmac
import io
import os
import sys
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI
from fastapi.testclient import TestClient
from v14.engineering_routes import install_engineering_routes
from v14.engineering_backend import GitHubEngineeringBackend
from v14.identity import Admission
from v14.config import Config
import test_gateway_v14_engineering as fixtures
from v14.code_graph.public_github import PublicGitHubReader
from v14.code_graph.service import GraphService
from v14.engineering_backend import blob_sha
from requests import Response


class EngineeringHTTPTests(unittest.TestCase):
    def test_selection_rejection_explains_safe_fields_before_any_provider_probe(self):
        from test_gateway_selected_execution import selected
        base = selected(self.runtime.LLAMA_BASE)
        cases = [
            (None, 'SELECTION_NOT_OBJECT', {}),
            ({key: value for key, value in base.items() if key not in {'tool_owner', 'required_capability_policy'}},
             'SELECTION_SCHEMA_INVALID', {'missing_fields': ['required_capability_policy', 'tool_owner'], 'unknown_field_count': 0}),
            (dict(base, private_unknown_field='private-secret-value'), 'SELECTION_SCHEMA_INVALID',
             {'missing_fields': [], 'unknown_field_count': 1}),
            (dict(base, profile_uid='private-identity\n', provider_connection_id=42), 'SELECTION_IDENTITY_INVALID',
             {'invalid_fields': ['profile_uid', 'provider_connection_id']}),
            (dict(base, credential_reference={'private-credential': 'private-secret-value'}), 'SELECTION_CREDENTIAL_REFERENCE_INVALID',
             {'invalid_fields': ['credential_reference']}),
        ]
        before = self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_bindings').fetchone()[0])
        with patch.object(self.service.capabilities, 'inspect') as probe:
            for value, reason, fields in cases:
                with self.subTest(reason=reason, fields=fields):
                    response = self.client.post('/gateway/engineering/bindings', json={'selection': value}, headers=self.headers)
                    self.assertEqual(409, response.status_code, response.text)
                    observed = response.json()['error']
                    self.assertEqual('EXECUTION_SELECTION_INVALID', observed['code'])
                    self.assertEqual(reason, observed['reasonCode'])
                    self.assertEqual('provider_admission', observed['failed_stage'])
                    self.assertEqual('not_dispatched', observed['dispatch_state'])
                    self.assertIs(False, observed['inference_attempted'])
                    self.assertIs(False, observed['retryable'])
                    self.assertIs(False, observed['routeSubstituted'])
                    for key, expected in fields.items():
                        self.assertEqual(expected, observed[key])
                    for private in ('private_unknown_field', 'private-secret-value', 'private-identity', 'private-credential'):
                        self.assertNotIn(private, response.text)
            probe.assert_not_called()
        self.assertEqual(before, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_bindings').fetchone()[0]))
        self.assertEqual([], self.backend.calls)

    def test_selection_error_payload_never_exports_unrecognized_field_names(self):
        from v14.execution_binding import ExecutionBindingError
        error = ExecutionBindingError('EXECUTION_SELECTION_INVALID', 'Invalid selection.', observation={
            'invalid_fields': ['credential_reference', 'private_secret_key', {'private': 'value'}],
            'missing_fields': ['tool_owner', 'private_unknown_field'], 'unknown_field_count': -1,
        })
        observed = error.error_payload(stage='provider_admission')
        self.assertEqual(['credential_reference'], observed['invalid_fields'])
        self.assertEqual(['tool_owner'], observed['missing_fields'])
        self.assertNotIn('unknown_field_count', observed)
        self.assertNotIn('private', json.dumps(observed))

    def test_binding_failure_keeps_typed_dependency_and_transient_retryability(self):
        from v14.execution_binding import ExecutionBindingError
        error = ExecutionBindingError('EXECUTION_BACKEND_UNAVAILABLE', 'The selected backend is not ready.', 503)
        error.retryable = True
        error.observation = {'reasonCode': 'BACKEND_CONNECTION_REFUSED', 'dependency': 'backend_models',
                             'failureCategory': 'connection_refused', 'observationAgeMs': 0}
        with patch.object(self.service, 'prepare_binding', side_effect=error):
            response = self.client.post('/gateway/engineering/bindings', json={'selection': {}}, headers=self.headers)
        self.assertEqual(503, response.status_code)
        observed = response.json()['error']
        self.assertEqual('EXECUTION_BACKEND_UNAVAILABLE', observed['code'])
        self.assertEqual('BACKEND_CONNECTION_REFUSED', observed['reasonCode'])
        self.assertEqual('backend_models', observed['dependency'])
        self.assertEqual('provider_admission', observed['failed_stage'])
        self.assertIs(False, observed['inference_attempted'])
        self.assertEqual('not_dispatched', observed['dispatch_state'])
        self.assertTrue(observed['retryable'])
        self.assertFalse(observed['routeSubstituted'])

    def test_evidence_pagination_preview_and_account_isolation(self):
        job = self.store.create('tailnet:owner', self.request)
        for index in range(21):
            last = self.service.evidence.append(job, 'github_api', {'index': index, 'source': 'x' * 17000})
        url = '/gateway/engineering/jobs/' + job['id'] + '/evidence'
        first = self.client.get(url, headers=self.headers).json()
        self.assertEqual(20, len(first['receipts']))
        self.assertTrue(first['has_more'])
        second = self.client.get(url + '?after=' + str(first['next_cursor']), headers=self.headers).json()
        self.assertEqual(last['receipt_id'], second['receipts'][0]['receipt_id'])
        receipt = url + '/' + last['receipt_id']
        preview = self.client.get(receipt + '?preview=true', headers=self.headers).json()
        self.assertTrue(preview['truncated'])
        self.assertEqual(16000, len(preview['preview']))
        self.assertIsNone(preview['payload'])
        self.assertEqual(20, self.client.get(receipt, headers=self.headers).json()['payload']['index'])
        for path in (url, receipt):
            self.assertEqual(404, self.client.get(path, headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
            self.assertEqual(403, self.client.get(path, headers={'Tailscale-User-Login': 'reader@example.test'}).status_code)
        self.assertEqual(409, self.client.get(url + '?after=-2', headers=self.headers).status_code)

    def test_diagnostic_job_resumes_without_duplicate_execution_or_repository_writes(self):
        url = '/gateway/engineering/diagnostics/jobs'
        request = {'kind': 'transport_probe', 'request_id': 'a' * 64}
        created = self.client.post(url, json=request, headers=self.headers)
        self.assertEqual(200, created.status_code, created.text)
        job = created.json()
        first = self.client.get(url + '/' + job['id'] + '?after=0&limit=1', headers=self.headers).json()
        self.assertEqual([1], [event['sequence'] for event in first['events']])
        resumed = self.client.get(url + '/' + job['id'] + '?after=1', headers=self.headers).json()
        self.assertEqual([2, 3], [event['sequence'] for event in resumed['events']])
        self.assertEqual('COMPLETED', resumed['status'])
        self.assertEqual(job['id'], self.client.post(url, json=request, headers=self.headers).json()['id'])
        self.assertEqual([], self.client.get(url + '/' + job['id'] + '?after=3', headers=self.headers).json()['events'])
        stream = self.client.get(url + '/' + job['id'] + '/events?after=1', headers=self.headers)
        events = [json.loads(line[6:]) for line in stream.text.splitlines() if line.startswith('data: {')]
        self.assertEqual([2, 3], [event['sequence'] for event in events])
        self.assertTrue(all(event['job_id'] == job['id'] for event in events))
        self.assertIn('data: [DONE]', stream.text)
        self.assertEqual([], self.backend.calls)
        self.assertEqual(404, self.client.get(url + '/' + job['id'], headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
        self.assertEqual(403, self.client.post(url, json=request, headers={'Tailscale-User-Login': 'reader@example.test'}).status_code)
        self.assertEqual(409, self.client.get(url + '/' + job['id'] + '?after=4', headers=self.headers).status_code)

    def setUp(self):
        fixtures.DurableEngineeringTests.setUp(self)
        fixtures.DurableEngineeringTests.admit(self, 'tailnet:owner')
        self.backend.available = lambda: True
        self.store.tx(lambda db: db.execute('SELECT 1'))
        self.service.thread = SimpleNamespace(is_alive=lambda: True)
        app = FastAPI()
        config = Config(mode='trusted_proxy', allowed_hosts=('testserver',), allowed_origins=(),
                        trusted_users={'owner@example.test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                                       'other@example.test': {'principalId': 'other', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                                       'reader@example.test': {'principalId': 'reader', 'scopes': ['chat', 'jobs', 'tools']}})
        app.add_middleware(Admission, config=config)
        install_engineering_routes(app, self.service)
        self.client = TestClient(app, client=('127.0.0.1', 12000))
        self.headers = {'Tailscale-User-Login': 'owner@example.test'}
        # Admission infrastructure boundary: this HTTP suite exercises real policy
        # parsing/producer resolution, while mirror/image execution is tested separately.
        self.request['source_sha'] = 'a' * 40
        policy = Path(self.temp.name) / 'validation.json'
        policy.write_text(json.dumps({'owner/repo': {'mirror': self.temp.name, 'image': 'fixture@sha256:' + 'a' * 64,
            'allowed_checks': ['tests'], 'commands': [['fixture-tests']], 'acceptance_map': [{'requirement_id': 'build-fix', 'case_id': 'build-fix',
                'command_index': 0, 'scope': 'behavior', 'report_path': 'tests.xml'}]}}))
        import os
        self.policy_environment = patch.dict(os.environ, GATEWAY_ENGINEERING_VALIDATION_POLICY=str(policy))
        self.policy_environment.start()
        self.addCleanup(self.policy_environment.stop)
        self.validation_probe = patch('v14.engineering_preflight.run_bounded', side_effect=lambda argv, *a, **k: {
            'exit_code': 0, 'output_tail': 'sha256:' + 'b' * 64 if argv[0] == 'docker' else 'true'})
        self.validation_probe.start()
        self.addCleanup(self.validation_probe.stop)


    def tearDown(self):
        self.client.close()
        fixtures.DurableEngineeringTests.tearDown(self)

    def test_app_create_close_resume_updates_and_explicit_merge_endpoint(self):
        response = self.client.post('/v1/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, response.status_code, response.text)
        self.assertEqual(24, response.json()['duration_hours'])
        self.assertEqual(512, response.json()['execution_budget']['model_calls'])
        caps = self.client.get('/gateway/engineering/capabilities', headers=self.headers).json()
        self.assertIn(24, caps['duration_hours'])
        self.assertEqual(24, caps['default_duration_hours'])
        self.assertEqual(512, caps['execution_budget_limits']['model_calls'])
        job_id = response.json()['id']
        # No connected client participates in any of these workflow steps.
        for _ in range(7):
            self.service.tick(job_id)
        response = self.client.get('/v1/gateway/engineering/jobs/' + job_id + '?after_revision=2', headers=self.headers)
        self.assertEqual(200, response.status_code)
        snapshot = response.json()
        self.assertTrue(snapshot['merge_ready'])
        self.assertTrue(all(update['revision'] > 2 for update in snapshot['updates']))
        self.assertEqual(404, self.client.get('/gateway/engineering/jobs/' + job_id, headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
        approval = {'revision': snapshot['revision'], 'pr': 7, 'head_sha': 'a' * 40}
        url = '/gateway/engineering/jobs/' + job_id + '/approve'
        self.assertEqual(403, self.client.post(url, json=approval, headers=self.headers).status_code)
        approved = self.client.post(url, json=approval, headers=dict(self.headers, **{'X-Gateway-User-Action': 'approve-and-merge'}))
        self.assertEqual(200, approved.status_code, approved.text)
        self.service.tick(job_id)
        self.service.tick(job_id)
        self.assertEqual('COMPLETED', self.store.get(job_id)['state'])

    def test_mutations_check_optional_gateway_and_principal_preconditions_before_admission(self):
        gateway_id = self.service.bindings.installation_id()
        valid = dict(self.headers, **{'X-Gateway-Installation-ID': gateway_id, 'X-Gateway-Principal-ID': 'tailnet:owner'})
        invalid = [
            (dict(valid, **{'X-Gateway-Installation-ID': 'gw_replaced'}), 409, 'CONTINUITY_IDENTITY_MISMATCH'),
            (dict(valid, **{'X-Gateway-Principal-ID': 'tailnet:another'}), 409, 'CONTINUITY_IDENTITY_MISMATCH'),
            (dict(self.headers, **{'X-Gateway-Installation-ID': gateway_id}), 400, 'CONTINUITY_IDENTITY_INVALID'),
            (dict(self.headers, **{'X-Gateway-Principal-ID': 'tailnet:owner'}), 400, 'CONTINUITY_IDENTITY_INVALID'),
        ]
        with patch.object(self.service, 'prepare_binding') as binding, patch.object(self.service, 'validate_submission') as validate, \
                patch.object(self.store, 'create') as create, patch.object(self.service.graph, 'execute') as graph:
            for headers, status, code in invalid:
                for path, body in (('/v1/gateway/engineering/bindings', {'selection': {}}),
                                   ('/v1/gateway/engineering/jobs', self.request),
                                   ('/v1/gateway/engineering/graph', {'action': 'status'})):
                    response = self.client.post(path, headers=headers, json=body)
                    self.assertEqual(status, response.status_code, response.text)
                    self.assertEqual(code, response.json()['error']['code'])
            binding.assert_not_called()
            validate.assert_not_called()
            create.assert_not_called()
            graph.assert_not_called()
        self.assertEqual(0, self.store.tx(lambda db: db.execute('SELECT COUNT(*) FROM engineering_jobs').fetchone()[0]))
        admitted = self.client.post('/v1/gateway/engineering/jobs', json=self.request, headers=valid)
        self.assertEqual(202, admitted.status_code, admitted.text)
        job_id = admitted.json()['id']
        with patch.object(self.store, 'control') as control:
            response = self.client.post('/v1/gateway/engineering/jobs/' + job_id + '/cancel',
                json={'revision': 0}, headers=invalid[0][0])
            self.assertEqual(409, response.status_code, response.text)
            self.assertEqual('CONTINUITY_IDENTITY_MISMATCH', response.json()['error']['code'])
            control.assert_not_called()
        self.assertEqual([], self.backend.calls)

    def test_graph_route_requires_engineering_scope_and_keeps_principal_server_owned(self):
        payload = {'action': 'status', 'repository': 'owner/repo', 'branch': 'feature', 'commit_sha': 'a' * 40}
        with patch.object(self.service.graph, 'execute', return_value={'status': 'NOT_INDEXED'}) as graph:
            denied = self.client.post('/gateway/engineering/graph', json=payload, headers={'Tailscale-User-Login': 'reader@example.test'})
            self.assertEqual(403, denied.status_code)
            graph.assert_not_called()
            response = self.client.post('/v1/gateway/engineering/graph', json=payload, headers=self.headers)
            self.assertEqual(200, response.status_code)
            graph.assert_called_once_with('tailnet:owner', payload)

    def test_graph_capacity_does_not_fail_as_a_successful_result(self):
        with patch.object(self.service.graph, 'execute', side_effect=TimeoutError('capacity')):
            response = self.client.post('/gateway/engineering/graph', json={'action': 'query'}, headers=self.headers)
            self.assertEqual(503, response.status_code)

    def test_existing_read_scope_cannot_start_engineering_and_no_credential_is_returned(self):
        response = self.client.post('/gateway/engineering/jobs', json=self.request, headers={'Tailscale-User-Login': 'reader@example.test'})
        self.assertEqual(403, response.status_code)
        capability = self.client.get('/gateway/engineering/capabilities', headers=self.headers)
        self.assertTrue(capability.json()['explicit_merge_approval'])
        self.assertEqual('android_background_sync', capability.json()['notifications'])

    def test_disabled_durable_storage_does_not_break_gateway_startup(self):
        self.runtime.GATEWAY_DURABLE_JOBS = False
        self.service.thread = None
        self.service.start()
        self.assertIsNone(self.service.thread)
        self.assertFalse(self.client.get('/gateway/engineering/capabilities', headers=self.headers).json()['enabled'])
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers).status_code)

    def test_readiness_separates_host_configuration_storage_worker_and_chat(self):
        url = '/gateway/engineering/capabilities'
        ready = self.client.get(url, headers=self.headers).json()
        self.assertTrue(ready['enabled'])
        self.assertTrue(ready['code_graph']['enabled'])
        self.assertIsNone(ready['readiness']['inference_ready'])
        self.assertIsNone(ready['readiness']['github_reachable'])
        self.assertIsNone(ready['readiness']['repository_access'])
        self.backend.available = lambda: False
        missing = self.client.get(url, headers=self.headers).json()
        self.assertFalse(missing['enabled'])
        self.assertFalse(missing['code_graph']['enabled'])
        self.assertEqual('missing', missing['readiness']['checks']['host_github_connection'])
        self.assertTrue(missing['readiness']['saved_results'])
        with patch.object(self.service.graph, 'execute') as graph:
            response = self.client.post('/gateway/engineering/graph', json={'action': 'status'}, headers=self.headers)
            self.assertEqual(412, response.status_code)
            self.assertFalse(response.json()['error']['retryable'])
            self.assertEqual('HOST_GITHUB_CONNECTION_REQUIRED', response.json()['error']['reason'])
            graph.assert_not_called()
        self.backend.available = lambda: True
        self.service.thread = None
        stopped = self.client.get(url, headers=self.headers).json()
        self.assertFalse(stopped['enabled'])
        self.assertTrue(stopped['code_graph']['enabled'])
        self.assertEqual('unavailable', stopped['readiness']['checks']['worker'])
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers).status_code)
        self.runtime.durable_write_healthy = False
        blocked = self.client.get(url, headers=self.headers).json()
        self.assertFalse(blocked['code_graph']['enabled'])
        self.assertEqual('write_failed', blocked['readiness']['checks']['journal'])
        self.assertTrue(blocked['readiness']['saved_results'])

    def test_default_host_connection_is_configured_without_claiming_remote_access(self):
        config = {'github-official': {'env': {'GITHUB_TOKEN': 'host-github-secret'}}}
        self.runtime.load_mcp_config = lambda: config
        self.service.backend = GitHubEngineeringBackend(self.runtime)
        self.service.graph.backend = self.service.backend.graph_reader()
        with patch.dict(os.environ, {}, clear=True), patch('requests.request') as dispatch, patch('requests.get') as read:
            response = self.client.get('/gateway/engineering/capabilities', headers=self.headers)
            self.assertEqual(200, response.status_code)
            readiness = response.json()['readiness']
            self.assertTrue(readiness['ready'])
            self.assertEqual('configured', readiness['checks']['host_github_connection'])
            self.assertIsNone(readiness['github_reachable'])
            self.assertIsNone(readiness['repository_access'])
            self.assertNotIn('host-github-secret', response.text)
            config['github-official']['disabled'] = True
            rejected = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
            self.assertEqual(503, rejected.status_code)
            self.assertEqual('missing', rejected.json()['readiness']['checks']['host_github_connection'])
            self.assertEqual([], self.store.list('tailnet:owner'))
            dispatch.assert_not_called()
            read.assert_not_called()

    def test_read_only_graph_readiness_is_independent_from_engineering_worker_and_model(self):
        config = {'github-official': {'env': {'GITHUB_TOKEN': 'graph-only-secret', 'GITHUB_READ_ONLY': 'true'}}}
        self.runtime.load_mcp_config = lambda: config
        self.runtime.LLAMA_BASE = ''
        self.service.backend = GitHubEngineeringBackend(self.runtime)
        self.service.graph.backend = self.service.backend.graph_reader()
        self.service.thread = None
        with patch.dict(os.environ, {}, clear=True), patch('requests.request') as dispatch, patch('requests.get') as read:
            response = self.client.get('/gateway/engineering/capabilities', headers=self.headers)
            self.assertEqual(200, response.status_code)
            capability = response.json()
            self.assertFalse(capability['enabled'])
            self.assertTrue(capability['code_graph']['configured'])
            self.assertTrue(capability['code_graph']['enabled'])
            self.assertEqual('missing', capability['readiness']['checks']['host_github_connection'])
            self.assertEqual('configured', capability['readiness']['checks']['graph_host_github_connection'])
            self.assertEqual('unavailable', capability['readiness']['checks']['worker'])
            for field in ('inference_ready', 'github_reachable', 'repository_access'):
                self.assertIsNone(capability['readiness'][field])
            self.assertNotIn('graph-only-secret', response.text)
            payload = {'action': 'status', 'repository': 'owner/repo', 'branch': 'feature', 'commit_sha': 'a' * 40}
            with patch.object(self.service.graph, 'execute', return_value={'status': 'NOT_INDEXED'}) as graph:
                response = self.client.post('/gateway/engineering/graph', json=payload, headers=self.headers)
                self.assertEqual(200, response.status_code, response.text)
                graph.assert_called_once_with('tailnet:owner', payload)
                self.service.thread = SimpleNamespace(is_alive=lambda: True)
                self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers).status_code)
                self.assertEqual([], self.store.list('tailnet:owner'))
                config['github-official']['disabled'] = True
                graph.reset_mock()
                response = self.client.post('/gateway/engineering/graph', json=payload, headers=self.headers)
                self.assertEqual(412, response.status_code)
                self.assertEqual('HOST_GITHUB_CONNECTION_REQUIRED', response.json()['error']['reason'])
                graph.assert_not_called()
            dispatch.assert_not_called()
            read.assert_not_called()

    def test_public_map_builds_without_host_credentials_or_network_engineering_grant(self):
        backend = PublicGitHubReader()
        self.addCleanup(backend.close)
        self.service.public_graph = GraphService(self.store, backend, public=True)
        app = FastAPI()
        app.add_middleware(Admission, config=Config(mode='open_network', host='0.0.0.0',
                                                  allowed_hosts=('testserver',), allowed_origins=(),
                                                  public_repository_maps=True))
        install_engineering_routes(app, self.service)
        sha = 'c' * 40
        source = b'def map_this():\n    return True\n'
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as zipped:
            zipped.writestr('repo-' + sha + '/code.py', source)
        repository = 'owner/repo'
        url = 'https://api.github.com/repos/' + repository
        archive_url = f'https://codeload.github.com/{repository}/zip/{sha}'
        fixtures_by_url = {
            url: {'full_name': repository, 'private': False, 'visibility': 'public'},
            url + '/git/ref/heads/main': {'object': {'sha': sha}},
            url + '/git/trees/' + sha + '?recursive=1': {'truncated': False, 'tree': [
                {'path': 'code.py', 'type': 'blob', 'mode': '100644', 'size': len(source), 'sha': blob_sha(source)}]},
            archive_url: archive.getvalue()
        }
        requests_made = []

        def fetch(target, **options):
            self.assertFalse(options['allow_redirects'])
            self.assertNotIn('Authorization', options.get('headers') or {})
            requests_made.append(target)
            payload = fixtures_by_url.get(target)
            response = Response()
            response.url = target
            response.status_code = 200 if payload is not None else 404
            response._content = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
            response._content_consumed = True
            return response

        payload = {'repository': repository, 'branch': 'main', 'commit_sha': sha}
        graph = '/v1/gateway/engineering/graph'
        with TestClient(app) as client, patch('v14.code_graph.public_github.requests.get', side_effect=fetch), \
                patch.object(self.service.graph, 'execute') as private, patch.object(self.backend, 'available') as host:
            capabilities = client.get('/v1/gateway/engineering/capabilities').json()
            self.assertTrue(capabilities['code_graph']['enabled'])
            self.assertEqual('public_only', capabilities['code_graph']['access'])
            self.assertFalse(capabilities['enabled'])
            self.assertNotIn('host_github_connection', capabilities['readiness']['checks'])
            self.assertEqual(403, client.post('/v1/gateway/engineering/jobs', json=self.request).status_code)
            self.assertEqual('NOT_INDEXED', client.post(graph, json=dict(payload, action='status')).json()['status'])
            indexed = client.post(graph, json=dict(payload, action='index'))
            self.assertEqual(200, indexed.status_code, indexed.text)
            self.assertEqual(1, indexed.json()['processed_files'])
            self.assertFalse(indexed.json()['has_more'])
            query = client.post(graph, json=dict(payload, action='query', query='map_this'))
            self.assertIn('map_this', query.text)
            node = next(node for node in query.json()['nodes'] if node['name'] == 'map_this')
            self.assertEqual('code.py', node['path'])
            self.assertTrue(node['url'].endswith('/' + sha + '/code.py#L1'))
            self.assertEqual(403, client.post(graph, json=dict(payload, action='note')).status_code)
            self.assertEqual(403, client.post(graph, json=dict(payload, action='provider')).status_code)
            fixtures_by_url.pop(url)
            hidden = client.post(graph, json=dict(payload, action='query', query='map_this'))
            self.assertEqual(412, hidden.status_code)
            self.assertEqual('missing', hidden.json()['readiness']['checks']['graph_host_github_connection'])
            self.assertNotIn('map_this', hidden.text)
            private.assert_not_called()
            host.assert_not_called()
        self.assertEqual(1, requests_made.count(archive_url))
        self.assertFalse(any('/git/blobs/' in target or target.endswith('/user') for target in requests_made))

    def test_lost_create_receipt_recovers_same_terminal_job_when_new_work_is_unavailable(self):
        created = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, created.status_code)
        job = created.json()
        self.store.control(job['id'], 'tailnet:owner', 'cancel', job['revision'])
        self.backend.available = lambda: False
        self.service.thread = None
        recovered = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(200, recovered.status_code, recovered.text)
        self.assertEqual(job['id'], recovered.json()['id'])
        self.assertEqual('CANCELLED', recovered.json()['state'])
        self.assertEqual(200, self.client.get('/gateway/engineering/jobs/' + job['id'], headers=self.headers).status_code)
        self.assertEqual(409, self.client.post('/gateway/engineering/jobs', json=dict(self.request, objective='different task'), headers=self.headers).status_code)
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=dict(self.request, request_id='new-work'), headers=self.headers).status_code)
        self.assertEqual(1, len(self.store.list('tailnet:owner')))
        self.assertEqual([], self.backend.calls)

    def test_missed_updates_paginate_without_replaying_execution(self):
        job = self.store.create('tailnet:owner', self.request)
        def insert(db):
            for revision in range(1, 260):
                self.store.event(db, dict(job, message='Saved update ' + str(revision)), 'CI', revision, self.time)
            db.execute('UPDATE engineering_jobs SET revision=259 WHERE id=?', (job['id'],))
        self.store.tx(insert)
        url = '/gateway/engineering/jobs/' + job['id']
        first = self.client.get(url + '?after_revision=-1', headers=self.headers).json()
        self.assertTrue(first['has_more_updates'])
        second = self.client.get(url + '?after_revision=' + str(first['next_revision']), headers=self.headers).json()
        self.assertFalse(second['has_more_updates'])
        self.assertEqual(list(range(260)), [event['revision'] for event in first['updates'] + second['updates']])
        self.assertEqual([], self.backend.calls)

    def test_signed_webhook_advances_reconciliation_without_granting_merge(self):
        job = self.store.create('tailnet:owner', self.request)
        for _ in range(6):
            self.service.tick(job['id'])
        self.backend.evidence['checks_passed'] = False
        self.service.tick(job['id'])
        self.assertEqual([], self.store.due())
        body = b'{"action":"completed"}'
        signature = 'sha256=' + hmac.new(b'test-secret', body, hashlib.sha256).hexdigest()
        with patch.dict('os.environ', {'GATEWAY_ENGINEERING_WEBHOOK_SECRET': 'test-secret'}):
            bad = self.client.post('/gateway/engineering/webhook', content=body, headers=dict(self.headers, **{'X-Hub-Signature-256': 'wrong'}))
            self.assertEqual(403, bad.status_code)
            self.assertEqual([], self.store.due())
            good = self.client.post('/gateway/engineering/webhook', content=body, headers=dict(self.headers, **{'X-Hub-Signature-256': signature}))
            self.assertEqual(200, good.status_code)
            duplicate = self.client.post('/gateway/engineering/webhook', content=body, headers=dict(self.headers, **{'X-Hub-Signature-256': signature}))
            self.assertTrue(duplicate.json()['duplicate'])
        self.assertEqual([job['id']], self.store.due())
        self.assertEqual('CI', self.store.get(job['id'])['state'])
        self.assertNotIn('merge', self.backend.calls)
