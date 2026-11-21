"""Exercise the production control listener over a real loopback TLS socket.

This verifies transport integration, not a physical Android/Windows pairing.
"""
import base64
import hashlib
import json
from pathlib import Path
import socket
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
import uuid
from unittest.mock import patch

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.configuration import DEFAULTS, atomic_json
from desktop.control_settings import Controller


class PairedControlTlsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        atomic_json(self.root / 'settings.json', DEFAULTS)
        self.control = Controller(SimpleNamespace(root=self.root, cfg=dict(DEFAULTS)))
        with self.control.store.tx() as db:
            db.execute('CREATE TABLE gateway_installation (key TEXT PRIMARY KEY,value TEXT NOT NULL)')
            db.execute("INSERT INTO gateway_installation VALUES ('gateway_id','tls-integration-pc')")
        self.control.store.observe_runtime(True, [])
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        self.control.enable({'host': '127.0.0.1', 'port': port})
        self.addCleanup(self.control.close)
        deadline = time.monotonic() + 5
        while not self.control.description()['enabled'] and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue(self.control.description()['enabled'], 'The production TLS listener did not start')
        self.client = requests.Session()
        self.client.trust_env = False
        self.addCleanup(self.client.close)
        self.certificate = str(self.root / 'data' / 'control-certificate.pem')
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public = base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def headers(self, token, method, path, body):
        stamp, nonce = str(int(time.time())), uuid.uuid4().hex
        message = '\n'.join(('gptmobile-control-v1', 'tls-integration-pc', method, path,
                             stamp, nonce, hashlib.sha256(body).hexdigest())).encode()
        signature = base64.b64encode(self.key.sign(message, ec.ECDSA(hashes.SHA256()))).decode()
        return {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json',
                'X-Gateway-Installation-ID': 'tls-integration-pc',
                'X-Gateway-Device-Time': stamp, 'X-Gateway-Device-Nonce': nonce,
                'X-Gateway-Device-Signature': signature}

    def call(self, token, method, path, value=None, headers=None):
        body = json.dumps(value, separators=(',', ':')).encode() if value is not None else b''
        return self.client.request(method, self.control.endpoint + path, data=body,
                                   headers=headers or self.headers(token, method, path, body),
                                   verify=self.certificate, timeout=(3, 5), allow_redirects=False)

    def pair(self):
        invitation = self.control.local('control-pairing-create', {
            'name': 'Transport test', 'repositories': ['owner/approved']})
        response = self.call(invitation['code'], 'POST', '/gateway/pairing/claim', {
            'installation_id': invitation['installation_id'], 'device_name': 'Test device',
            'public_key': self.public})
        self.assertEqual(200, response.status_code, response.text)
        finish = {'pairing_id': response.json()['pairing_id'], 'public_key': self.public}
        self.assertEqual(202, self.call(invitation['code'], 'POST', '/gateway/pairing/finish', finish).status_code)
        self.control.local('control-pairing-confirm', {'pairing_id': finish['pairing_id'], 'approved': True})
        response = self.call(invitation['code'], 'POST', '/gateway/pairing/finish', finish)
        self.assertEqual(200, response.status_code, response.text)
        grant = response.json()
        self.assertEqual(invitation['fingerprint'], grant['fingerprint'])
        return grant

    def test_private_address_one_exchange_restores_same_grant_and_writes_shared_settings(self):
        from desktop.control_store import FIELDS
        from urllib.parse import parse_qs, urlsplit
        # Map the fixture's TLS socket to its test inference address. All requests
        # below traverse the real listener, certificate verification and device signatures.
        self.control.supervisor.cfg.update(access='network', bind_host='192.168.1.8')
        with patch.object(self.control, 'connection_port', return_value=8090):
            original = self.control.endpoint
            self.control.endpoint = 'https://192.168.1.8:8091'
            try:
                address = self.control.local('control-connect-address', {})['address']
            finally:
                self.control.endpoint = original
            uri = urlsplit(address)
            self.assertEqual('http://192.168.1.8:8090/v1', uri._replace(fragment='').geturl())
            code = parse_qs(uri.fragment)['code'][0]
            body = {'public_key': self.public}
            first = self.call(code, 'POST', '/gateway/connect', body)
            self.assertEqual(200, first.status_code, first.text)
            grant = first.json()
            retry = self.call(code, 'POST', '/gateway/connect', body)
            self.assertEqual(grant, retry.json())
            initial = self.call(grant['token'], 'GET', '/gateway/settings').json()
            self.assertEqual(FIELDS, set(initial['writable_fields']))
            self.assertTrue(initial['desired']['engineering_enabled'])
            command = {'installation_id': initial['installation_id'], 'expected_revision': initial['revision'],
                       'command_id': uuid.uuid4().hex, 'changes': {'workers': 3, 'active_chats': 9}}
            receipt = self.call(grant['token'], 'PATCH', '/gateway/settings', command)
            self.assertEqual(200, receipt.status_code)
            self.assertEqual(3, self.control.local('control-settings-read', {})['desired']['workers'])
            self.assertEqual(1, len(self.control.store.devices()))
            other = ec.generate_private_key(ec.SECP256R1())
            self.key = other
            other_key = base64.b64encode(other.public_key().public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
            self.assertIn(self.call(code, 'POST', '/gateway/connect', {'public_key': other_key}).status_code, (401, 403))

    def test_ip_connection_is_limited_to_selected_peer_and_reuses_device_for_settings(self):
        from urllib.parse import parse_qs, urlsplit
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from desktop.control_server import create_app
        from v14.config import Config
        from v14.identity import Admission
        from v14.job_catalog import install_routes

        host, phone = '192.168.1.8', '192.168.1.20'
        self.control.supervisor.cfg.update(access='network', bind_host=host)
        atomic_json(self.root / 'settings.json', self.control.supervisor.cfg)
        self.control.endpoint = 'https://' + host + ':8091'
        self.control.allowed_hosts.add(host + ':8091')
        self.control.local('control-connect-address', {'host': host, 'phone_ip': phone})
        app = FastAPI()
        app.state.connected_settings_root = self.root
        records = SimpleNamespace(runtime=SimpleNamespace(GATEWAY_DURABLE_JOBS=True), now=time.time,
                                  gateway_id=lambda: 'tls-integration-pc')
        install_routes(app, records, None, SimpleNamespace(instance_id='test'), None)
        app.add_middleware(Admission, config=Config(mode='open_network', host=host, allowed_hosts=(host + ':8090',)))
        def identity(source, headers=None):
            with TestClient(app, base_url='http://' + host + ':8090', client=(source, 4321)) as client:
                return client.get('/v1/gateway/identity', headers=headers or {}).json()
        shared = identity('192.168.1.21')
        self.assertNotIn('phone_connection', shared)
        self.assertEqual(2, shared['network_settings']['workers'])
        self.assertNotIn('network_settings', identity(phone, {'x-forwarded-for': phone}))
        self.assertNotIn('network_settings', identity('8.8.8.8'))
        self.assertNotIn('phone_connection', identity(phone, {'x-forwarded-for': phone}))
        link = identity(phone)['phone_connection']
        ticket = parse_qs(urlsplit(link).query)['code'][0]
        self.assertNotIn(self.control.lan_window['code'], link)
        tls = create_app(self.control)
        def signed(source, token, method, path, payload=None):
            body = json.dumps(payload).encode() if payload is not None else b''
            with TestClient(tls, base_url=self.control.endpoint, client=(source, 4321)) as client:
                return client.request(method, path, content=body, headers=self.headers(token, method, path, body))
        path = '/gateway/connect'
        self.assertEqual(403, signed('192.168.1.21', ticket, 'POST', path, {'public_key': self.public}).status_code)
        grant = signed(phone, ticket, 'POST', path, {'public_key': self.public})
        self.assertEqual(200, grant.status_code, grant.text)
        token = grant.json()['token']
        self.assertEqual(token, signed(phone, ticket, 'POST', path, {'public_key': self.public}).json()['token'])
        self.control.lan_window['deadline'] = 0
        self.assertEqual(403, signed(phone, ticket, 'POST', path, {'public_key': self.public}).status_code)
        current = signed(phone, token, 'GET', '/gateway/settings').json()
        self.assertTrue(current['capabilities']['connectionSettings.v1'])
        self.assertTrue(current['desired']['engineering_enabled'])
        path = '/gateway/settings/connection'
        original = signed(phone, token, 'GET', path).json()
        command = {'original': original, 'changes': {'port': 9234, 'shutdown_seconds': 48}}
        applied = signed(phone, token, 'PATCH', path, command)
        self.assertEqual(200, applied.status_code, applied.text)
        self.assertEqual(9234, applied.json()['port'])
        self.assertEqual(409, signed(phone, token, 'PATCH', path, command).status_code)
        self.assertEqual(8090, self.control.supervisor.cfg['port'])
        self.assertEqual(400, signed(phone, token, 'PATCH', path, {'original': applied.json(), 'changes': {'mcp_config': 'arbitrary'}}).status_code)

    def test_private_network_owner_proof_enables_settings_and_engineering_without_an_invitation(self):
        from desktop.account_address import credential_key, tag
        from desktop.control_store import FIELDS, retained_device_grant
        from desktop.control_server import create_app
        from desktop.workspace_actions import connection
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from urllib.parse import parse_qs, urlsplit
        from v14.config import Config
        from v14.identity import Admission
        from v14.job_catalog import install_routes
        from v14.engineering import EngineeringStore

        host, phone = '192.168.1.8', '192.168.1.25'
        self.control.supervisor.cfg.update(access='network', bind_host=host)
        atomic_json(self.root / 'settings.json', self.control.supervisor.cfg)
        self.control.endpoint = 'https://' + host + ':8091'
        self.control.allowed_hosts.add(host + ':8091')
        atomic_json(self.root / 'data' / 'account-address-listener.json',
                    dict(endpoint=self.control.endpoint, fingerprint=self.control.fingerprint))
        app = FastAPI()
        app.state.connected_settings_root = self.root
        records = SimpleNamespace(runtime=SimpleNamespace(GATEWAY_DURABLE_JOBS=True), now=time.time,
                                  gateway_id=lambda: 'tls-integration-pc')
        install_routes(app, records, None, SimpleNamespace(instance_id='test'), None)
        app.add_middleware(Admission, config=Config(mode='open_network', host=host, allowed_hosts=(host + ':8090',)))
        tls = create_app(self.control)

        def issue(token, path='/gateway/connect', method='POST', payload=None):
            body = json.dumps({'public_key': self.public} if payload is None else payload).encode() if method != 'GET' else b''
            with TestClient(tls, base_url=self.control.endpoint, client=(phone, 4321)) as client:
                return client.request(method, path, content=body, headers=self.headers(token, method, path, body))

        def discover(path):
            with TestClient(app, base_url='http://' + host + ':8090', client=(phone, 4321)) as client:
                return client.get(path).json()

        from desktop.validation_policy import load_policy_snapshot, save_policy_snapshot
        policy = {'owner/repo': {'mirror': str(self.root / 'mirror'), 'image': 'fixture@sha256:' + 'a' * 64,
                                    'commands': [['python3', '-m', 'unittest']]}}
        save_policy_snapshot(self.root, policy, enabled=True, expected_revision=load_policy_snapshot(self.root)['revision'])
        with patch('desktop.account_address.read_token', return_value='ghp_' + 'A' * 40) as secret:
            self.assertNotIn('account_connection', discover('/v1/gateway/identity'))
            offer = discover('/v1/gateway/identity?connect=1')['account_connection']
            fields = {k: v[0] for k, v in parse_qs(urlsplit(offer).query).items()}
            key = credential_key(self.root)
            context = [fields[k] for k in ('installation_id', 'endpoint', 'fingerprint', 'gateway_port', 'expires')]
            self.assertEqual('host_' + fields['expires'] + '_' + tag(key, 'server', *context), fields['code'])
            proof = 'device_' + fields['expires'] + '_' + tag(key, 'device', *context, self.public)
            self.assertIn(issue(fields['code']).status_code, (401, 403))
            self.assertEqual(403, issue(proof[:-1] + ('1' if proof[-1] == '0' else '0')).status_code)
            grant = issue(proof)
            self.assertEqual(200, grant.status_code, grant.text)
            token, principal = (grant.json()[k] for k in ('token', 'principal_id'))
            self.assertEqual(token, issue(proof).json()['token'])
            confirmed = issue(token, '/gateway/settings', 'GET').json()
            self.assertEqual(FIELDS, set(confirmed['writable_fields']))
            self.assertTrue(confirmed['desired']['engineering_enabled'])
            self.assertEqual(['owner/repo'], json.loads(retained_device_grant(self.control.store.path, principal)['repositories']))
            # Exercise the same worker state machine against the new principal and actual SQLite grant.
            import sqlite3
            import threading
            from v14.engineering import EngineeringService
            from v14.execution_binding import fingerprint
            from test_gateway_v14_engineering import Backend, DurableEngineeringTests

            def connect_db():
                db = sqlite3.connect(self.control.store.path)
                db.row_factory = sqlite3.Row
                return db

            runtime = SimpleNamespace(LLAMA_BASE='http://localhost:8080', GATEWAY_DURABLE_JOBS=True,
                                      durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                      _durable_connect=connect_db)
            store = EngineeringStore(runtime)
            backend = Backend()
            service = EngineeringService(store, backend)
            service.capabilities._probe = lambda route, model: {
                'requested_model_id': model, 'reported_model_id': model, 'limits_verified': True,
                'context_tokens': 32768, 'max_output_tokens': 32768, 'model_revision': None,
                'template_digest': None, 'capability_revision': fingerprint([route, model]),
                'supported': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']}
            request = {'repository': 'owner/repo', 'branch': 'feature/job', 'base': 'main', 'objective': 'Repair the build',
                       'model': 'local', 'profile_id': 'test-profile', 'request_id': 'private-device', 'chat_id': 12,
                       'acceptance': [{'id': 'build-fix', 'paths': ['file'], 'checks': ['tests']}]}
            DurableEngineeringTests.admit(SimpleNamespace(service=service, runtime=runtime, request=request), principal)
            job = store.create(principal, request)
            for _ in range(7):
                service.tick(job['id'])
            self.assertEqual('MERGE_READY', store.get(job['id'])['state'])
            self.assertNotIn('merge', backend.calls)

            file = self.root / 'approved.json'
            file.write_text('{}')
            local = connection(self.root, 'connection-read', {})['settings']
            connection(self.root, 'connection-save', {'original': local, 'changes': {'mcp_config': str(file)}})
            path = '/gateway/settings/connection'
            current = issue(token, path, 'GET').json()
            self.assertIn(str(file), current['mcp_choices'])
            def select(original, name):
                return issue(token, path, 'PATCH', {'original': original, 'changes': {'mcp_config': name}})
            default = select(current, '').json()
            self.assertEqual(str(file), select(default, str(file)).json()['mcp_config'])
            default = select(issue(token, path, 'GET').json(), '').json()
            file.write_text('{"changed":true}')
            self.assertEqual(400, select(default, str(file)).status_code)
            secret.return_value = None
            self.assertEqual(401, issue(token, '/gateway/settings', 'GET').status_code)
            self.assertIsNone(retained_device_grant(self.control.store.path, principal))
            self.assertNotIn('account_connection', discover('/v1/gateway/identity?connect=1'))
            secret.return_value = 'ghp_' + 'A' * 40
            self.control.store.revoke(grant.json()['device_id'])
            self.assertEqual(401, issue(proof).status_code)

    def test_real_tls_pair_shared_revision_original_receipt_and_revocation(self):
        grant = self.pair()
        initial = self.call(grant['token'], 'GET', '/gateway/settings').json()
        command = {'installation_id': grant['installation_id'], 'expected_revision': initial['revision'],
                   'command_id': uuid.uuid4().hex, 'changes': {'engineering_enabled': True}}
        # Deliberately discard the mutation response; only a read reconciles it.
        response = self.call(grant['token'], 'PATCH', '/gateway/settings', command)
        self.assertEqual(200, response.status_code)
        response.close()
        receipt = self.call(grant['token'], 'GET', '/gateway/settings/commands/' + command['command_id']).json()
        self.assertEqual('applied', receipt['status'])
        self.assertEqual(command['command_id'], receipt['command_id'])
        self.assertEqual(initial['revision'] + 1, receipt['revision'])
        desktop = self.control.local('control-settings-read', {'owner_principal': grant['principal_id']})
        self.assertEqual(receipt['revision'], desktop['revision'])
        self.assertTrue(desktop['effective']['engineering_enabled'])
        stale = dict(command, command_id=uuid.uuid4().hex, changes={'engineering_enabled': False})
        rejected = self.call(grant['token'], 'PATCH', '/gateway/settings', stale)
        self.assertEqual(409, rejected.status_code)
        self.assertEqual('not_dispatched', rejected.json()['dispatch_state'])
        self.assertEqual('rejected', self.call(grant['token'], 'GET', '/gateway/settings/commands/' + stale['command_id']).json()['status'])
        self.control.local('control-device-revoke', {'device_id': grant['device_id']})
        self.assertEqual(401, self.call(grant['token'], 'GET', '/gateway/settings').status_code)

    def test_tls_trust_and_exact_signed_request_are_required(self):
        with self.assertRaises(requests.exceptions.SSLError):
            self.client.get(self.control.endpoint + '/gateway/settings', timeout=(3, 5))
        grant = self.pair()
        headers = self.headers(grant['token'], 'GET', '/gateway/settings', b'')
        self.assertEqual(200, self.call(grant['token'], 'GET', '/gateway/settings', headers=headers).status_code)
        replay = self.call(grant['token'], 'GET', '/gateway/settings', headers=headers)
        self.assertEqual(401, replay.status_code)
        self.assertEqual('request_replayed', replay.json()['error']['code'])
        wrong_key = ec.generate_private_key(ec.SECP256R1())
        self.key = wrong_key
        self.assertEqual(401, self.call(grant['token'], 'GET', '/gateway/settings').status_code)


if __name__ == '__main__':
    unittest.main()
