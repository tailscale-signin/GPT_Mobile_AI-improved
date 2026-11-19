"""Actual settings transactions and signed HTTPS API, including lost responses."""
import base64
import asyncio
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
from desktop.configuration import atomic_json, DEFAULTS
from desktop.control_store import ControlStore, ControlError, FIELDS
from desktop.control_security import server_secret, tls_identity
from desktop.control_server import create_app, proxy_permission
from desktop.control_settings import Controller
from desktop.manager import ManagerRequestError, Supervisor, request as manager_request
from v14.config import Config
from v14.identity import Admission, PrincipalResolver
from v14.engineering import EngineeringStore, principal_authorized
from v14.execution_binding import ExecutionBindingError
from v14.control_settings import install_control_settings
from v14.service import ServiceState


class ControlSettingsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.store = ControlStore(self.root)
        with self.store.tx() as db:
            db.execute('CREATE TABLE gateway_installation (key TEXT PRIMARY KEY,value TEXT NOT NULL)')
            db.execute("INSERT INTO gateway_installation VALUES ('gateway_id','installed-pc-123')")
        self.store.observe_runtime(True, [])
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public_key = base64.b64encode(self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        self.secret = b's' * 32

    def paired(self, role='owner', fields=None):
        invitation = self.store.pairing_create({'name': 'Test phone', 'role': role, 'fields': fields or sorted(FIELDS), 'repositories': ['owner/repo']})
        claim = self.store.pairing_claim(invitation['code'], {'installation_id': 'installed-pc-123', 'device_name': 'Android', 'public_key': self.public_key})
        self.store.pairing_confirm(claim['pairing_id'], True)
        result, status = self.store.pairing_finish(invitation['code'], {'pairing_id': claim['pairing_id'], 'public_key': self.public_key}, self.secret)
        self.assertEqual(200, status)
        return invitation, result

    def command(self, changes, revision=0, command_id=None):
        return {'installation_id': 'installed-pc-123', 'expected_revision': revision,
                'command_id': command_id or uuid.uuid4().hex, 'changes': changes}

    def test_lost_response_reuses_one_commit_and_rejects_changed_body(self):
        _, grant = self.paired()
        payload = self.command({'engineering_enabled': True})
        receipt = self.store.patch(grant['principal_id'], payload)
        reopened = ControlStore(self.root)
        again = reopened.patch(grant['principal_id'], payload)
        self.assertEqual(receipt['revision'], again['revision'])
        self.assertEqual(1, reopened.snapshot(grant['principal_id'])['revision'])
        self.assertEqual(receipt['command_id'], reopened.receipt(grant['principal_id'], payload['command_id'])['command_id'])
        with self.assertRaises(ControlError) as error:
            reopened.patch(grant['principal_id'], dict(payload, changes={'engineering_enabled': False}))
        self.assertEqual('command_identity_conflict', error.exception.code)

    def test_local_missing_receipt_has_confirmed_bounded_ipc_error(self):
        manager = Supervisor.__new__(Supervisor)
        manager.root = self.root
        manager.boot = Mock(return_value=0)
        manager.child = Mock()
        manager.child.poll.return_value = None
        manager.start_desktop_observer = Mock()
        manager.take_control_request = Mock(side_effect=[('control-settings-command', 'receipt-lookup'), ('stop', 'stop-after-lookup')])
        manager._request_payload = {'command_id': 'missing-command-123'}
        manager.log, manager.publish, manager.respond = Mock(), Mock(), Mock()
        manager.stop = Mock(return_value=True)
        with patch('desktop.manager.time.sleep'):
            self.assertEqual(0, manager.run())
        rejected = manager.respond.call_args_list[0].args[0]
        self.assertEqual({'id': 'receipt-lookup', 'accepted': False, 'error': 'command_not_found',
                          'error_code': 'command_not_found', 'http_status': 404}, rejected)
        atomic_json(self.root / 'response.json', rejected)
        with patch('desktop.manager.uuid.uuid4', return_value=SimpleNamespace(hex='receipt-lookup')):
            with self.assertRaises(ManagerRequestError) as error:
                manager_request(self.root, 'control-settings-command', timeout=1, payload=manager._request_payload)
        self.assertEqual(('command_not_found', 404), (error.exception.code, error.exception.status))
        unknown = ManagerRequestError({'error': 'The response timed out'})
        self.assertIsNone(unknown.code)
        self.assertIsNone(unknown.status)

    def test_desktop_and_phone_edit_same_owner_without_cancelling_existing_work(self):
        _, grant = self.paired()
        owner = grant['principal_id']
        self.store.patch(owner, self.command({'engineering_enabled': True}))
        adapter = SimpleNamespace(admission_settings=EngineeringStore.admission_settings)
        with self.store.tx() as db:
            db.execute('CREATE TABLE accepted_fixture (job_id TEXT PRIMARY KEY)')
            db.execute("INSERT INTO accepted_fixture VALUES ('original-job-id')")
            EngineeringStore.require_new_jobs(adapter, db, owner)
        self.store.patch(owner, self.command({'engineering_enabled': False}, revision=1), local=True)
        self.assertFalse(self.store.snapshot(owner)['effective']['engineering_enabled'])
        with self.store.tx() as db:
            with self.assertRaises(ExecutionBindingError) as error:
                EngineeringStore.require_new_jobs(adapter, db, owner)
            self.assertEqual('ENGINEERING_JOBS_PAUSED', error.exception.code)
            self.assertEqual('original-job-id', db.execute('SELECT job_id FROM accepted_fixture').fetchone()[0])
        config = Config(control_journal=str(self.store.path))
        self.assertTrue(principal_authorized(config, owner, None), 'Turning admission off must retain accepted-job authority')

    def test_stale_editor_gets_durable_rejection_and_original_receipt(self):
        _, grant = self.paired()
        self.store.patch(grant['principal_id'], self.command({'workers': 3}))
        stale = self.command({'workers': 4})
        receipt = self.store.patch(grant['principal_id'], stale, local=True)
        self.assertEqual('rejected', receipt['status'])
        self.assertEqual('settings_changed_elsewhere', receipt['error']['code'])
        self.assertEqual('not_dispatched', receipt['dispatch_state'])
        self.assertEqual(receipt, self.store.receipt(grant['principal_id'], stale['command_id']))
        self.assertEqual(3, self.store.snapshot(grant['principal_id'])['desired']['workers'])

    def test_full_command_journal_keeps_receipts_and_refuses_more_rejections(self):
        _, grant = self.paired()
        accepted = self.command({'engineering_enabled': True})
        rejected = self.command({'workers': 3})
        with patch('desktop.control_store.MAX_COMMANDS', 2):
            first = self.store.patch(grant['principal_id'], accepted)
            second = self.store.patch(grant['principal_id'], rejected)
            self.assertEqual('rejected', second['status'])
            for payload in (self.command({'workers': 4}), self.command({'workers': 4}, revision=1)):
                with self.assertRaises(ControlError) as error:
                    self.store.patch(grant['principal_id'], payload)
                self.assertEqual('command_journal_full', error.exception.code)
            replay = self.store.patch(grant['principal_id'], accepted)
            self.assertEqual((first['command_id'], first['revision'], first['status']),
                             (replay['command_id'], replay['revision'], replay['status']))
            self.assertEqual(second, self.store.patch(grant['principal_id'], rejected))
            self.assertEqual(second, self.store.receipt(grant['principal_id'], rejected['command_id']))
        with self.store.tx() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM gateway_control_commands').fetchone()[0])
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM gateway_control_rejections').fetchone()[0])

    def test_wrong_installation_viewer_field_limits_and_revocation_deny(self):
        _, viewer = self.paired(role='viewer')
        receipt = self.store.patch(viewer['principal_id'], self.command({'workers': 3}))
        self.assertEqual('field_permission_required', receipt['error']['code'])
        with self.assertRaises(ControlError):
            self.store.patch(viewer['principal_id'], dict(self.command({'workers': 3}), installation_id='another-pc'))
        _, owner = self.paired(fields=['engineering_enabled'])
        self.assertEqual('rejected', self.store.patch(owner['principal_id'], self.command({'workers': 3}))['status'])
        self.store.revoke(owner['device_id'])
        with self.assertRaises(ControlError):
            self.store.authenticate(owner['token'])
        with self.assertRaises(ControlError):
            self.store.snapshot('network:shared')

    def test_pairing_requires_local_confirmation_and_preserves_delivery_identity(self):
        invitation = self.store.pairing_create({'repositories': ['owner/repo']})
        payload = {'installation_id': 'installed-pc-123', 'device_name': 'Android', 'public_key': self.public_key}
        first = self.store.pairing_claim(invitation['code'], payload)
        self.assertEqual(first, self.store.pairing_claim(invitation['code'], payload))
        finish = {'pairing_id': first['pairing_id'], 'public_key': self.public_key}
        self.assertEqual(202, self.store.pairing_finish(invitation['code'], finish, self.secret)[1])
        with self.assertRaises(ControlError):
            self.store.pairing_claim(invitation['code'], dict(payload, public_key='another-device-key'))
        self.store.pairing_confirm(first['pairing_id'], True)
        received = self.store.pairing_finish(invitation['code'], finish, self.secret)
        self.assertEqual(received, self.store.pairing_finish(invitation['code'], finish, self.secret))
        self.store.now = lambda: invitation['expires_at'] + 1
        with self.assertRaises(ControlError):
            self.store.pairing_finish(invitation['code'], finish, self.secret)

    def signed(self, token, method, path, body=b'', nonce=None):
        stamp, nonce = str(int(time.time())), nonce or uuid.uuid4().hex
        message = '\n'.join(('gptmobile-control-v1', 'installed-pc-123', method, path, stamp, nonce, hashlib.sha256(body).hexdigest())).encode()
        signature = self.key.sign(message, ec.ECDSA(hashes.SHA256()))
        return {'Authorization': 'Bearer ' + token, 'X-Gateway-Installation-ID': 'installed-pc-123',
                'X-Gateway-Device-Time': stamp, 'X-Gateway-Device-Nonce': nonce,
                'X-Gateway-Device-Signature': base64.b64encode(signature).decode(), 'Content-Type': 'application/json'}

    def client(self):
        controller = SimpleNamespace(store=self.store, secret=self.secret, allowed_hosts={'testserver'},
                                     endpoint='https://testserver', fingerprint='f' * 64,
                                     phone_snapshot=lambda owner: self.store.snapshot(owner),
                                     patch=lambda owner, payload: self.store.patch(owner, payload))
        return TestClient(create_app(controller), base_url='https://testserver')

    def test_https_api_signature_replay_body_tamper_and_unpaired_denied(self):
        _, grant = self.paired()
        with self.client() as client:
            headers = self.signed(grant['token'], 'GET', '/gateway/settings')
            self.assertEqual(200, client.get('/gateway/settings', headers=headers).status_code)
            self.assertEqual(401, client.get('/gateway/settings', headers=headers).status_code)
            self.assertEqual(401, client.get('/gateway/settings', headers=self.signed('ordinary-profile-key', 'GET', '/gateway/settings')).status_code)
            body = json.dumps(self.command({'workers': 3})).encode()
            signed = self.signed(grant['token'], 'PATCH', '/gateway/settings', body)
            self.assertEqual(401, client.patch('/gateway/settings', content=body.replace(b'3', b'4'), headers=signed).status_code)
            self.assertEqual(403, client.get('http://testserver/gateway/settings', headers=self.signed(grant['token'], 'GET', '/gateway/settings')).status_code)

    def test_signature_preserves_encoded_path_and_query_and_unknown_pairing_uses_no_nonce(self):
        _, grant = self.paired()
        with self.client() as client:
            path = '/gateway/%73ettings?cursor=a%2Fb%20c'
            response = client.get(path, headers=self.signed(grant['token'], 'GET', path))
            self.assertEqual(200, response.status_code, response.text)
            body = json.dumps({'installation_id': 'installed-pc-123', 'device_name': 'Android', 'public_key': self.public_key}).encode()
            with self.store.tx() as db:
                before = db.execute('SELECT COUNT(*) FROM gateway_control_nonces').fetchone()[0]
            response = client.post('/gateway/pairing/claim', content=body,
                headers=self.signed('unknown-invitation', 'POST', '/gateway/pairing/claim', body))
            self.assertEqual(401, response.status_code)
            with self.store.tx() as db:
                self.assertEqual(before, db.execute('SELECT COUNT(*) FROM gateway_control_nonces').fetchone()[0])

    def test_https_full_pairing_flow_with_host_confirmation(self):
        invitation = self.store.pairing_create({'repositories': ['owner/repo']})
        with self.client() as client:
            body = json.dumps({'installation_id': 'installed-pc-123', 'device_name': 'Android', 'public_key': self.public_key}).encode()
            claimed = client.post('/gateway/pairing/claim', content=body, headers=self.signed(invitation['code'], 'POST', '/gateway/pairing/claim', body))
            self.assertEqual(200, claimed.status_code, claimed.text)
            finish = json.dumps({'pairing_id': claimed.json()['pairing_id'], 'public_key': self.public_key}).encode()
            self.assertEqual(202, client.post('/gateway/pairing/finish', content=finish, headers=self.signed(invitation['code'], 'POST', '/gateway/pairing/finish', finish)).status_code)
            self.store.pairing_confirm(claimed.json()['pairing_id'], True)
            delivered = client.post('/gateway/pairing/finish', content=finish, headers=self.signed(invitation['code'], 'POST', '/gateway/pairing/finish', finish))
            self.assertEqual(200, delivered.status_code, delivered.text)
            self.assertEqual('owner', delivered.json()['role'])

    def test_readiness_is_not_fabricated_for_enabled_owner(self):
        _, grant = self.paired()
        self.store.observe_runtime(False, ['github_connection'])
        receipt = self.store.patch(grant['principal_id'], self.command({'engineering_enabled': True}))
        self.assertEqual('setup_required', receipt['status'])
        self.assertTrue(receipt['snapshot']['desired']['engineering_enabled'])
        self.assertFalse(receipt['snapshot']['effective']['engineering_enabled'])
        self.assertIn('github_connection', receipt['snapshot']['engineering_prerequisites'])

    def test_deferred_settings_survive_restart_and_apply_original_command_once(self):
        _, grant = self.paired()
        receipt = self.store.patch(grant['principal_id'], self.command({'workers': 4}))
        supervisor = SimpleNamespace(root=self.root, cfg=dict(DEFAULTS))
        first = Controller(supervisor)
        baseline = {key: self.store.snapshot(local=True)['effective'][key] for key in ('workers', 'active_chats', 'backend', 'model', 'public_repository_maps', 'debug_mode')}
        def blocked(values=None):
            return {'status': 'waiting_for_jobs', 'effective': baseline, 'blocking_jobs': ['kept-job'], 'engineering_ready': True, 'engineering_prerequisites': []}
        first.runtime = blocked
        first.reconcile()
        saved = self.store.receipt(grant['principal_id'], receipt['command_id'])
        self.assertEqual('waiting_for_jobs', saved['status'])
        self.assertEqual(['kept-job'], saved['snapshot']['pending'][0]['blocking_jobs'])
        reopened = Controller(supervisor)
        def available(values=None):
            if values:
                baseline.update(values)
            return {'status': 'applied', 'effective': baseline, 'blocking_jobs': [], 'engineering_ready': True, 'engineering_prerequisites': []}
        reopened.runtime = available
        atomic_json(self.root / 'settings.json', dict(DEFAULTS, port=9010))
        reopened.reconcile()
        self.assertEqual('applied', self.store.receipt(grant['principal_id'], receipt['command_id'])['status'])
        self.assertEqual(4, json.loads((self.root / 'settings.json').read_text())['workers'])
        self.assertEqual(9010, json.loads((self.root / 'settings.json').read_text())['port'])
        self.assertEqual(DEFAULTS['port'], supervisor.cfg['port'], 'Saved host changes must not strand the running engine control port')
        with self.store.tx() as db:
            self.assertEqual(1, db.execute('SELECT COUNT(*) FROM gateway_control_commands').fetchone()[0])

    def test_pinned_tls_identity_reuses_protected_key(self):
        secret = server_secret(self.root)
        identity = tls_identity(self.root, '127.0.0.1', secret)
        self.assertEqual(identity['fingerprint'], tls_identity(self.root, '127.0.0.1', server_secret(self.root))['fingerprint'])
        self.assertNotIn(secret, (self.root / 'data' / 'control-private-key.pem').read_bytes())
        with self.assertRaises(ValueError):
            tls_identity(self.root, '192.0.2.4', secret)

    def test_runtime_worker_activation_waits_for_original_job_and_then_applies(self):
        state = ServiceState(2)
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=False, LLAMA_BASE=DEFAULTS['backend'],
                                  gateway_debug=SimpleNamespace(enabled=False), gateway_draining=False)
        engineering = SimpleNamespace(readiness=lambda: {'ready': True}, public_graph=object(), graph=SimpleNamespace())
        config = Config()
        app = FastAPI()
        install_control_settings(app, runtime, state, engineering, config)
        values = dict(workers=4, active_chats=8, backend=DEFAULTS['backend'], model='', public_repository_maps=False, debug_mode=True)
        state.workers['original-job'] = (None, None)
        with TestClient(app) as client:
            result = client.post('/gateway/admin/settings', json=values)
            self.assertEqual(409, result.status_code, result.text)
            self.assertEqual(2, state.max_workers)
            self.assertIn('original-job', state.workers)
            state.workers.clear()
            result = client.post('/gateway/admin/settings', json=values)
            self.assertEqual(200, result.status_code, result.text)
            self.assertEqual(4, state.max_workers)
            self.assertTrue(runtime.gateway_debug.enabled)
            self.assertFalse(state.draining)
            engineering.readiness = lambda: {'ready': False, 'checks': {'journal': 'ready', 'host_github_connection': 'missing',
                                                                       'worker': 'running', 'validation_policy': 'missing'}}
            observed = client.get('/gateway/admin/settings').json()
            self.assertFalse(observed['engineering_ready'])
            self.assertEqual(['github_connection', 'validation_policy_for_implementation'], observed['engineering_prerequisites'])

    def test_custom_port_backend_self_route_is_rejected_before_probe_or_commit(self):
        active_cfg = dict(DEFAULTS, port=9090, network_hostnames='gateway-box')
        # A saved future listener must not replace the live listener identity.
        atomic_json(self.root / 'settings.json', dict(active_cfg, port=9999))
        controller = Controller(SimpleNamespace(root=self.root, cfg=active_cfg))
        for origin in ('http://127.0.0.1:9090', 'http://localhost:9090', 'https://gateway-box:9090'):
            command = self.command({'backend': origin})
            receipt = controller.local('control-settings-patch', command)
            self.assertEqual('rejected', receipt['status'])
            self.assertEqual('invalid_backend', receipt['error']['code'])
            self.assertEqual('not_dispatched', receipt['dispatch_state'])
            self.assertEqual(receipt, controller.local('control-settings-command', {'command_id': command['command_id']}))
        snapshot = controller.local('control-settings-read', {})
        self.assertEqual(0, snapshot['revision'])
        self.assertEqual(DEFAULTS['backend'], snapshot['desired']['backend'])
        self.assertEqual(DEFAULTS['backend'], snapshot['effective']['backend'])
        state = ServiceState(2)
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=False, LLAMA_BASE=DEFAULTS['backend'],
                                  gateway_debug=SimpleNamespace(enabled=False), gateway_draining=False)
        engineering = SimpleNamespace(readiness=lambda: {'ready': True}, public_graph=object(), graph=SimpleNamespace())
        config = Config(port=9090, allowed_hosts=('127.0.0.1:9090', 'localhost:9090', 'gateway-box:9090'))
        app = FastAPI()
        install_control_settings(app, runtime, state, engineering, config)
        values = dict(workers=2, active_chats=8, backend='', model='', public_repository_maps=False, debug_mode=False)
        with TestClient(app) as client, patch('v14.control_settings.probe_backend') as probe:
            for origin in ('http://127.0.0.1:9090', 'http://localhost:9090', 'https://gateway-box:9090'):
                response = client.post('/gateway/admin/settings', json=dict(values, backend=origin))
                self.assertEqual(409, response.status_code, response.text)
                self.assertEqual(('setup_required', 'invalid_backend'), (response.json()['status'], response.json()['reason']))
                self.assertEqual(DEFAULTS['backend'], response.json()['effective']['backend'])
                self.assertEqual(DEFAULTS['backend'], runtime.LLAMA_BASE)
                self.assertFalse(state.draining)
            probe.assert_not_called()
            # Port 8090 is no longer incorrectly reserved on this 9090 Gateway.
            response = client.post('/gateway/admin/settings', json=dict(values, backend='http://127.0.0.1:8090'))
            self.assertEqual(200, response.status_code, response.text)
            probe.assert_called_once_with('http://127.0.0.1:8090', '')

    def test_host_retained_receipt_preserves_owner_without_remote_or_paired_access(self):
        _, grant = self.paired()
        job_id = 'eng_' + '1' * 32
        old = {'id': job_id, 'principal': 'network:shared', 'state': 'NEEDS_INPUT'}
        reads = []
        def retained(identifier):
            reads.append(identifier)
            return dict(old) if identifier == job_id else None
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=False, LLAMA_BASE=DEFAULTS['backend'],
                                  gateway_debug=SimpleNamespace(enabled=False), gateway_draining=False)
        engineering = SimpleNamespace(store=SimpleNamespace(get=retained, conversations=SimpleNamespace(gateway_id=lambda: 'installed-pc-123')),
                                      job_response=lambda task: {'id': task['id'], 'state': task['state']})
        config = Config(mode='open_network', host='0.0.0.0', allowed_hosts=('testserver',), allowed_origins=(),
                        management_token='local-secret', control_journal=str(self.store.path))
        app = FastAPI()
        app.add_middleware(Admission, config=config)
        install_control_settings(app, runtime, ServiceState(2), engineering, config)
        route = '/gateway/admin/retained-work/' + job_id
        with TestClient(app, client=('192.0.2.1', 9020)) as remote, TestClient(app, client=('127.0.0.1', 9021)) as local:
            self.assertEqual(401, remote.get(route).status_code)
            self.assertEqual(401, remote.get(route, headers={'X-Gateway-Management': 'local-secret'}).status_code)
            self.assertEqual(401, local.get(route).status_code)
            self.assertEqual(403, local.get(route, headers={'X-Gateway-Management': 'local-secret',
                            'X-Gateway-Control-Principal': grant['principal_id']}).status_code)
            self.assertEqual([], reads)
            result = local.get(route, headers={'X-Gateway-Management': 'local-secret'})
            self.assertEqual(200, result.status_code, result.text)
            self.assertEqual('network:shared', result.json()['principal_id'])
            self.assertEqual(job_id, result.json()['job']['id'])
            self.assertTrue(result.json()['read_only'])
            self.assertEqual(405, local.post(route, headers={'X-Gateway-Management': 'local-secret'}).status_code)
            self.assertEqual([job_id], reads)
        with self.assertRaises(ControlError):
            proxy_permission(route, 'GET', {}, self.store.authenticate(grant['token']))

    def test_shared_inference_preparation_stays_separate_from_paired_engineering(self):
        _, grant = self.paired()
        config = Config(mode='open_network', host='0.0.0.0', allowed_hosts=('testserver',), allowed_origins=(),
                        remote_engineering=True, management_token='local-secret', control_journal=str(self.store.path))
        app = FastAPI()
        app.add_middleware(Admission, config=config)
        async def observed(request: Request):
            return {'principal_id': request.state.principal.id, 'engineering': request.state.principal.engineering_allowed}
        for route, method in [('/gateway/capabilities', 'GET'), ('/gateway/ready', 'GET'), ('/v1/models', 'GET'),
                              ('/gateway/models/prepare', 'POST'), ('/gateway/engineering/bindings', 'POST'),
                              ('/gateway/engineering/jobs', 'POST')]:
            app.add_api_route(route, observed, methods=[method])
        with TestClient(app, client=('192.0.2.1', 9020)) as remote, TestClient(app, client=('127.0.0.1', 9021)) as local:
            for route, method in [('/gateway/capabilities', 'GET'), ('/gateway/ready', 'GET'), ('/v1/models', 'GET'),
                                  ('/gateway/models/prepare', 'POST')]:
                response = remote.request(method, route)
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual('network:shared', response.json()['principal_id'])
                self.assertFalse(response.json()['engineering'])
            for route in ['/gateway/engineering/bindings', '/gateway/engineering/jobs']:
                self.assertEqual(403, remote.post(route).status_code)
                paired = local.post(route, headers={'X-Gateway-Management': 'local-secret', 'X-Gateway-Control-Principal': grant['principal_id']})
                self.assertEqual(200, paired.status_code, paired.text)
                self.assertEqual(grant['principal_id'], paired.json()['principal_id'])
                self.assertTrue(paired.json()['engineering'])

    def test_proxy_allows_only_reviewed_routes_and_approved_repositories(self):
        _, grant = self.paired()
        device = self.store.authenticate(grant['token'])
        denied = [('/gateway/engineering/admission-settings', 'POST', {'enabled': True, 'revision': 0}),
                  ('/gateway/engineering/execution-settings', 'POST', {}),
                  ('/gateway/engineering/webhook', 'POST', {}),
                  ('/gateway/engineering/future-admin-operation', 'POST', {}),
                  ('/gateway/engineering/repository-operations', 'POST', {'operation': 'import_repository', 'source_owner': 'owner', 'source_repo': 'repo', 'destination_owner': 'owner', 'destination_repo': 'new'}),
                  ('/gateway/engineering/repository-operations', 'POST', {'operation': 'clone_repository', 'source_owner': 'other', 'source_repo': 'repo'}),
                  ('/gateway/engineering/graph', 'POST', {'repository': 'owner/repo', 'targets': [{'repository': 'other/repo'}]}),
                  ('/gateway/conversations/original/messages', 'POST', {'engineering_request': {'repository': 'other/repo'}})]
        for route, method, payload in denied:
            with self.subTest(route=route, payload=payload), self.assertRaises(ControlError):
                proxy_permission(route, method, payload, device)
        for route in ['/gateway/saved-messages', '/gateway/conversations/original/messages',
                      '/gateway/jobs/original/result', '/gateway/jobs/original/events', '/gateway/commands/original']:
            proxy_permission(route, 'GET', {}, device)
        proxy_permission('/gateway/jobs/original/cancel', 'POST', {}, device)
        proxy_permission('/gateway/engineering/repository-operations', 'POST', {'operation': 'clone_repository', 'source_owner': 'owner', 'source_repo': 'repo'}, device)

    def test_paired_profile_checks_forward_only_scoped_owner_posts(self):
        _, owner = self.paired()
        _, viewer = self.paired(role='viewer')
        route = '/gateway/engineering/profile-checks'
        payload = {'action': 'run', 'repository': 'owner/repo', 'branch': 'main', 'head_sha': 'a' * 40,
                   'profile_binding': 'b' * 64, 'request_id': 'original-check', 'check_index': 0, 'changes': []}
        body = json.dumps(payload).encode()
        forwarded = []

        def runtime(request):
            forwarded.append(request)
            self.assertEqual(('POST', route, body), (request.method, request.url.path, request.content))
            self.assertEqual(owner['principal_id'], request.headers['x-gateway-control-principal'])
            self.assertEqual(owner['principal_id'], request.headers['x-gateway-principal-id'])
            self.assertEqual('local-supervisor-secret', request.headers['x-gateway-management'])
            self.assertNotIn('authorization', request.headers)
            return httpx.Response(200, json={'state': 'RUNNING', 'request_id': payload['request_id']})

        controller = SimpleNamespace(store=self.store, secret=self.secret, allowed_hosts={'testserver'},
                                     endpoint='https://testserver', fingerprint='f' * 64,
                                     supervisor=SimpleNamespace(cfg=DEFAULTS, token='local-supervisor-secret'))
        original_client = httpx.AsyncClient
        with patch('desktop.control_server.httpx.AsyncClient', side_effect=lambda **kwargs:
                   original_client(transport=httpx.MockTransport(runtime), **kwargs)), \
                TestClient(create_app(controller), base_url='https://testserver') as client:
            response = client.post(route, content=body, headers=self.signed(owner['token'], 'POST', route, body))
            self.assertEqual(200, response.status_code, response.text)
            self.assertEqual({'state': 'RUNNING', 'request_id': 'original-check'}, response.json())
            denied = [(viewer, 'POST', route, payload, 'owner_role_required'),
                      (owner, 'POST', route, dict(payload, repository='other/repo'), 'repository_permission_required'),
                      (owner, 'GET', route, payload, 'control_route_not_admitted'),
                      (owner, 'POST', route + '/admin', payload, 'control_route_not_admitted'),
                      (owner, 'POST', '/gateway/engineering/future-admin-operation', payload, 'control_route_not_admitted')]
            for grant, method, path, value, code in denied:
                with self.subTest(role=grant['role'], method=method, path=path, repository=value['repository']):
                    raw = json.dumps(value).encode()
                    response = client.request(method, path, content=raw,
                                              headers=self.signed(grant['token'], method, path, raw))
                    self.assertEqual(403, response.status_code, response.text)
                    self.assertEqual(code, response.json()['error']['code'])
            self.assertEqual(1, len(forwarded), 'Denied requests must never reach the runtime')

    def test_paired_runtime_identity_requires_local_supervisor_secret_and_live_grant(self):
        _, grant = self.paired()
        config = Config(mode='open_network', host='0.0.0.0', management_token='private-supervisor', control_journal=str(self.store.path))
        resolver = PrincipalResolver(config, None)
        scope = {'client': ('127.0.0.1', 4321), 'server': ('127.0.0.1', 8090), 'path': '/gateway/engineering/jobs'}
        headers = {'x-gateway-control-principal': grant['principal_id'], 'x-gateway-management': 'private-supervisor'}
        principal = asyncio.run(resolver.resolve(scope, headers))
        self.assertEqual(grant['principal_id'], principal.id)
        self.assertFalse(principal.local_admin)
        self.assertIn('engineering', principal.scopes)
        self.assertIsNone(asyncio.run(resolver.resolve(dict(scope, client=('192.0.2.22', 4567)), headers)))
        self.assertIsNone(asyncio.run(resolver.resolve(scope, dict(headers, **{'x-gateway-management': 'wrong'}))))
        self.store.revoke(grant['device_id'])
        self.assertIsNone(asyncio.run(resolver.resolve(scope, headers)))


if __name__ == '__main__':
    unittest.main()
