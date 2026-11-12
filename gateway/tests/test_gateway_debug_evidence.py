"""Diagnostic exports retain causal and failure evidence without leaking payloads or claiming coverage."""
import asyncio
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.testclient import TestClient
from v14.config import Config
from v14.debug import DebugLogStore, DebugMiddleware, install_routes, sanitized_record
from v14.debug_projection import route_kind, response_summary, backend_build_provenance
from v14.identity import Admission, Principal


class DebugEvidenceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / 'debug.jsonl'
        self.store = DebugLogStore(self.path, enabled=True)
        self.addCleanup(self.store.stop)
        self.store.configure_identity(gatewayVersion='15.0.0', sourceCommit='1' * 40, instanceId='2' * 32,
                                      configurationFingerprint='3' * 64, backendIdentity='4' * 64,
                                      backendType='LLAMA', startupReason='process_start')

    def client(self, config=None, peer='127.0.0.1'):
        app = FastAPI()
        install_routes(app, self.store)
        @app.get('/gateway/ready')
        async def ready():
            await asyncio.sleep(0.04)
            return JSONResponse({'ready': False, 'reasonCode': 'BACKEND_CONNECTION_REFUSED', 'retryable': True,
                                 'capabilities': {'networkReachable': False, 'generationReady': None,
                                                  'dependency': 'backend_health', 'failureCategory': 'connection_refused',
                                                  'healthHttpStatus': 503, 'private': 'secret backend URL'},
                                 'checks': {'journal': 'ready'}, 'secret': 'private prompt and token'}, status_code=503)
        @app.get('/gateway/capabilities')
        async def capabilities():
            return {'selectedModel': {'limitsVerified': False, 'failureCode': 'EXECUTION_METADATA_DENIED',
                                      'reasonCode': 'BACKEND_METADATA_DENIED', 'dependency': 'backend_properties',
                                      'upstreamStatus': 403, 'retryable': False, 'reasoningControlState': 'UNVERIFIED',
                                      'requestedModel': 'private selected model'}}
        @app.get('/gateway/commands/{command_id}')
        async def rejected(command_id: str):
            return JSONResponse({'error': {'code': 'EXECUTION_PARAMETERS_UNSUPPORTED', 'message': 'private prompt and path',
                                          'failed_stage': 'provider_admission', 'dispatch_state': 'not_dispatched',
                                          'inference_attempted': False, 'retryable': False}}, status_code=409)
        @app.get('/gateway/admin/dashboard')
        async def dashboard(failed: bool = False):
            return JSONResponse({'error': {'code': 'DURABLE_STORAGE_UNAVAILABLE'}} if failed else {'ready': True},
                                status_code=503 if failed else 200)
        app.add_middleware(Admission, config=config or Config())
        app.add_middleware(DebugMiddleware, store=self.store, instance_id='2' * 32)
        client = TestClient(app, base_url='http://127.0.0.1:8090', client=(peer, 1234))
        self.addCleanup(client.close)
        return client

    def test_arrival_and_completion_reflect_actual_latency_with_unique_http_ids(self):
        client = self.client()
        responses = [client.get('/gateway/ready', headers={'X-Request-ID': 'logical-check', 'X-Gateway-Retry-Attempt': str(i)}) for i in range(2)]
        rows = [json.loads(row) for row in self.store.export().splitlines()]
        self.assertEqual(['client_request', 'gateway_response'] * 2, [row['event'] for row in rows])
        self.assertEqual(1, len({row['operationDigest'] for row in rows}))
        self.assertEqual(2, len({row['httpRequestId'] for row in rows}))
        for attempt in range(2):
            request, response = rows[attempt * 2:attempt * 2 + 2]
            self.assertEqual(request['httpRequestId'], responses[attempt].headers['X-Gateway-HTTP-Request-ID'])
            self.assertEqual(request['httpRequestId'], response['httpRequestId'])
            self.assertEqual(attempt, response['retryAttempt'])
            self.assertGreaterEqual(response['timestampMs'] - request['timestampMs'], 30)
            self.assertLess(abs(response['durationMs'] - (response['completedAtMs'] - request['arrivalAtMs'])), 15)
            self.assertLessEqual(request['arrivalAtMs'], request['recordedAtMs'])
            self.assertLessEqual(response['responseStartedAtMs'], response['completedAtMs'])
            self.assertTrue(response['responseBodyCompleted'])
            self.assertEqual('BACKEND_CONNECTION_REFUSED', response['observation']['reasonCode'])
        self.assertNotIn('secret', self.store.export())
        self.assertNotIn('private', self.store.export())

    def test_http_200_capability_failure_preserves_unverified_reason_and_upstream_status(self):
        self.assertEqual(200, self.client().get('/gateway/capabilities').status_code)
        result = json.loads(self.store.export().splitlines()[-1])
        self.assertEqual('EXECUTION_METADATA_DENIED', result['observation']['failureCode'])
        self.assertEqual('backend_properties', result['observation']['dependency'])
        self.assertEqual('UNVERIFIED', result['observation']['reasoningControlState'])
        self.assertEqual(403, result['observation']['upstreamStatus'])
        self.assertFalse(result['observation']['limitsVerified'])
        self.assertNotIn('private selected model', self.store.export())

    def test_command_rejection_preserves_cause_and_execution_stage_without_response_text(self):
        headers = {'X-Gateway-Diagnostic-Session': 'a' * 32, 'X-Gateway-Diagnostic-Started': str(int(time.time() * 1000))}
        response = self.client().get('/gateway/commands/private-command', headers=headers)
        self.assertEqual(409, response.status_code)
        with zipfile.ZipFile(io.BytesIO(self.store.export_bundle())) as archive:
            events = [json.loads(line) for line in archive.read('diagnostic-events.jsonl').splitlines()]
            text = archive.read('gateway-debug.jsonl').decode()
        receipt = next(row for row in events if row['stage'] == 'response')
        attributes = receipt['bounded_attributes']
        self.assertEqual('commands', attributes['route'])
        self.assertEqual('EXECUTION_PARAMETERS_UNSUPPORTED', attributes['reason_code'])
        self.assertEqual('provider_admission', attributes['failed_stage'])
        self.assertEqual('not_dispatched', attributes['execution_dispatch_state'])
        self.assertFalse(attributes['inference_attempted'])
        self.assertEqual('response_received', receipt['dispatch_state'])
        self.assertNotIn('private', text + json.dumps(events))

    def test_dashboard_successes_are_sampled_and_each_failure_pair_survives(self):
        client = self.client(Config(management_token='test-manager'))
        headers = {'X-Gateway-Management': 'test-manager'}
        for _ in range(12):
            self.assertEqual(200, client.get('/gateway/admin/dashboard', headers=headers).status_code)
        for _ in range(2):
            self.assertEqual(503, client.get('/gateway/admin/dashboard?failed=true', headers=headers).status_code)
        rows = [json.loads(line) for line in self.store.export().splitlines()]
        self.assertEqual(['client_request', 'gateway_response'] * 3, [row['event'] for row in rows])
        self.assertEqual([200, 503, 503], [row['status'] for row in rows if row['event'] == 'gateway_response'])
        self.assertEqual(11, self.store.snapshot()['suppressedSuccessfulPolls'])
        for request, response in zip(rows[::2], rows[1::2]):
            self.assertEqual(request['httpRequestId'], response['httpRequestId'])

    def test_sampled_success_header_does_not_hide_a_later_transport_failure(self):
        async def app(scope, receive, send):
            scope['state']['principal'] = Principal('local:manager', frozenset({'chat'}), True)
            await send({'type': 'http.response.start', 'status': 200, 'headers': []})
            await send({'type': 'http.response.body', 'body': b'{}'})
        middleware = DebugMiddleware(app, self.store, '2' * 32)
        async def call(fail):
            async def receive():
                return {'type': 'http.request', 'body': b''}
            async def send(message):
                if fail and message['type'] == 'http.response.body':
                    raise BrokenPipeError('private socket description')
            await middleware({'type': 'http', 'method': 'GET', 'path': '/gateway/admin/dashboard', 'headers': []}, receive, send)
        asyncio.run(call(False))
        with self.assertRaises(BrokenPipeError):
            asyncio.run(call(True))
        rows = [json.loads(line) for line in self.store.export().splitlines()]
        self.assertEqual(1, sum(row['event'] == 'transport_failure' for row in rows))
        self.assertFalse(rows[-1]['responseBodyCompleted'])
        self.assertEqual(0, self.store.snapshot()['suppressedSuccessfulPolls'])
        self.assertNotIn('private socket', self.store.export())

    def test_build_provenance_keeps_safe_revision_and_marks_unobserved_metadata(self):
        safe = backend_build_provenance({'version': 'b7654', 'commit': 'a' * 40, 'compiler': '/private/path'})
        self.assertEqual('b7654', safe['backendBuildVersion'])
        self.assertEqual('a' * 40, safe['backendBuildRevision'])
        self.assertEqual('observed', safe['backendBuildStatus'])
        self.assertNotIn('private', json.dumps(safe))
        opaque = backend_build_provenance('private server build /path')
        self.assertEqual({'backendBuildStatus', 'backendBuildDigest'}, set(opaque))
        self.assertEqual('digest_only', opaque['backendBuildStatus'])
        self.assertEqual({'backendBuildStatus': 'not_observed'}, backend_build_provenance(None))
        self.store.observe_backend_build({'version': 'b7654'}, 1000)
        with zipfile.ZipFile(io.BytesIO(self.store.export_bundle())) as archive:
            manifest = json.loads(archive.read('manifest.json'))
        self.assertEqual('b7654', manifest['versions'][-1]['backendBuildVersion'])
        self.assertEqual('observed', manifest['buildIdentity']['backendBuildStatus'])

    def test_expected_restriction_is_named_without_accepting_unadmitted_metadata(self):
        client = self.client(Config(mode='open_network', host='0.0.0.0'), peer='192.168.1.50')
        self.assertEqual(403, client.get('/health', headers={'X-Gateway-Client-Diagnostics': '{"schemaVersion":1,"androidSdk":36}'}).status_code)
        rows = [json.loads(row) for row in self.store.export().splitlines()]
        self.assertEqual('backend_health', rows[-1]['route'])
        self.assertEqual('remote_surface_not_admitted', rows[-1]['admissionCode'])
        self.assertFalse(rows[-1]['observation']['retryable'])
        self.assertNotIn('client', rows[0])

    def test_normalized_routes_do_not_export_identifiers_or_queries(self):
        expected = {'/v1/props': 'backend_properties', '/gateway/admin/dashboard': 'management_dashboard',
                    '/gateway/admin/maintenance': 'maintenance', '/gateway/identity': 'identity',
                    '/gateway/job-catalog': 'job_catalog', '/gateway/conversations/private/messages': 'messages',
                    '/gateway/commands/private': 'commands', '/gateway/tools': 'tools', '/gateway/session': 'session'}
        for path, kind in expected.items():
            self.assertEqual(kind, route_kind(path))
        self.client().get('/gateway/conversations/private/messages?token=private-token')
        self.assertNotIn('private', self.store.export())

    def test_full_bundle_contains_build_transport_and_real_retained_job_event_with_coverage(self):
        self.store.start()
        self.client().get('/gateway/ready')
        self.store.record_event('private-principal', 'a' * 32, int(time.time() * 1000), stage='operation_dispatch',
                                job_id='job_' + 'b' * 32, operation_id='c' * 32, category='model',
                                dispatch_state='not_dispatched', error_code='PRECHECK_REJECTED', severity='warning',
                                bounded_attributes={'output_tokens': 0, 'prompt': 'private contents'})
        raw = self.store.export_bundle()
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            self.assertEqual({'gateway-debug.jsonl', 'diagnostic-events.jsonl', 'manifest.json'}, set(archive.namelist()))
            manifest = json.loads(archive.read('manifest.json'))
            events = [json.loads(line) for line in archive.read('diagnostic-events.jsonl').splitlines()]
            self.assertEqual(1, len(events))
            self.assertEqual('not_dispatched', events[0]['dispatch_state'])
            self.assertEqual('job_' + 'b' * 32, events[0]['job_id'])
            self.assertEqual('1' * 40, manifest['versions'][-1]['sourceCommit'])
            self.assertEqual('3' * 64, manifest['versions'][-1]['configurationFingerprint'])
            self.assertEqual('retained_evidence', manifest['journal']['availability'])
            self.assertFalse(manifest['journal']['complete'])
            for item in manifest['files']:
                data = archive.read(item['path'])
                self.assertEqual(len(data), item['bytes'])
                self.assertEqual(hashlib.sha256(data).hexdigest(), item['sha256'])
            self.assertNotIn('private', b''.join(archive.read(name) for name in archive.namelist()).decode())

    def test_offline_transport_export_does_not_create_or_claim_a_missing_event_journal(self):
        self.store.record('gateway_started', provenance=self.store.provenance)
        self.store.flush()
        loaded = DebugLogStore(self.path)
        with zipfile.ZipFile(io.BytesIO(loaded.export_bundle())) as archive:
            manifest = json.loads(archive.read('manifest.json'))
        self.assertEqual('not_observed', manifest['journal']['availability'])
        self.assertFalse(loaded.journal.path.exists())
        self.assertEqual('1' * 40, manifest['versions'][0]['sourceCommit'])

    def test_manager_bundle_export_requires_local_socket_and_its_management_secret(self):
        config = Config(mode='open_network', host='0.0.0.0', management_token='test-management-secret')
        client = self.client(config)
        self.assertEqual(403, client.get('/gateway/admin/debug/export?format=zip').status_code)
        self.assertEqual(401, client.get('/gateway/admin/debug/export?format=zip', headers={'X-Gateway-Management': 'wrong'}).status_code)
        allowed = client.get('/gateway/admin/debug/export?format=zip', headers={'X-Gateway-Management': 'test-management-secret'})
        self.assertEqual(200, allowed.status_code)
        self.assertEqual('application/zip', allowed.headers['content-type'])
        remote = self.client(config, peer='192.168.1.50')
        self.assertEqual(401, remote.get('/gateway/admin/debug/export?format=zip', headers={'X-Gateway-Management': 'test-management-secret'}).status_code)

    def test_malformed_or_edited_observation_fields_never_become_arbitrary_export_text(self):
        row = sanitized_record({'event': 'gateway_response', 'observation': {'failureCode': 'private-token',
                                'reasonCode': [], 'dependency': {}, 'failureCategory': ['private'],
                                'metadataAccess': {}, 'ready': 'true', 'upstreamStatus': True},
                                'provenance': {'sourceCommit': 'private', 'backendType': {}, 'startupReason': []}})
        self.assertNotIn('observation', row)
        self.assertNotIn('provenance', row)

    def test_simultaneous_storage_health_and_models_failures_keep_independent_causes(self):
        body = {'ready': False, 'reasonCode': 'DURABLE_STORAGE_UNAVAILABLE', 'retryable': True,
                'checks': {'journal': 'unavailable'}, 'service': {'draining': False},
                'capabilities': {'networkReachable': True, 'modelAvailable': None, 'generationReady': None,
                                 'reasonCode': 'BACKEND_CONNECT_TIMEOUT', 'dependency': 'backend_health',
                                 'failureCategory': 'connect_timeout', 'metadataAccess': 'unknown',
                                 'retryable': True, 'modelsHttpStatus': 403, 'canQueue': False, 'canAccept': False,
                                 'observedAtMs': 1234567890000, 'observationAgeMs': 1234,
                                 'healthFailure': {'reasonCode': 'BACKEND_CONNECT_TIMEOUT', 'dependency': 'backend_health',
                                                   'failureCategory': 'connect_timeout', 'retryable': True},
                                 'modelsFailure': {'reasonCode': 'BACKEND_METADATA_DENIED', 'dependency': 'backend_models',
                                                   'failureCategory': 'denied', 'upstreamStatus': 403, 'retryable': False}}}
        self.store.record('gateway_response', status=503, httpRequestId='5' * 32, requestDigest='6' * 64,
                          principalDigest='7' * 64, operationDigest='8' * 64, traceId='9' * 32,
                          spanId='a' * 16, arrivalAtMs=1234567890000, completedAtMs=1234567891000,
                          responseStartedAtMs=1234567891000, durationMs=1000, observation=response_summary(body))
        with zipfile.ZipFile(io.BytesIO(self.store.export_bundle())) as archive:
            line = archive.read('gateway-debug.jsonl').splitlines()[0]
        self.assertLessEqual(len(line) + 1, 2048)
        projected = json.loads(line)['observation']
        self.assertEqual('DURABLE_STORAGE_UNAVAILABLE', projected['reasonCode'])
        self.assertEqual('durable_storage', projected['dependency'])
        self.assertEqual('storage', projected['failureCategory'])
        self.assertEqual('BACKEND_CONNECT_TIMEOUT', projected['conditions']['health']['reasonCode'])
        self.assertEqual('BACKEND_METADATA_DENIED', projected['conditions']['models']['reasonCode'])
        self.assertEqual(403, projected['conditions']['models']['upstreamStatus'])
        self.assertIn('modelAvailable', projected['conditions']['backend'])
        self.assertIsNone(projected['conditions']['backend']['modelAvailable'])
        self.assertIsNone(projected['conditions']['backend']['generationReady'])
        self.assertFalse(projected['conditions']['backend']['canAccept'])
        self.assertFalse(projected['conditions']['process']['draining'])


if __name__ == '__main__':
    unittest.main()
